package cn.rain.zagent

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileInputStream

/**
    * PythonBridge —— Kotlin ⇆ Python（Chaquopy）唯一桥。
    *
    * 设计要点（与原版保持一致）：
    *  1. object 单例，Java 侧通过 INSTANCE 访问；
    *  2. 出厂基因（assets/zagent_factory）部署到 filesDir/zagent_home/factory，
    *     以 .factory_version（内容=versionCode）判定是否需要重新复制；
    *  3. ensureBootstrapped() 幂等且线程安全（synchronized）：
    *     AndroidAPI.init → 部署基因 → sys.path 头插 genome → zagent_app.bootstrap(home)；
    *  4. 所有桥接方法统一空安全模式：
    *         callAttr(...)?.toString() ?: ""
    *     规避 Chaquopy 平台类型（PyObject!）导致的空安全编译报错。
    *
    * 本版新增：setModel(context, name) —— 把用户在设置弹窗里填写的
    * 模型名传给 Python 层（zagent_app.set_model → config.json + 热更新 MODEL）。
    */
object PythonBridge {

    private const val TAG = "PythonBridge"

    /** assets 内的出厂基因根目录 */
    private const val ASSET_ROOT = "zagent_factory"

    /** 部署目录内的版本标记文件（内容 = versionCode） */
    private const val FACTORY_MARK = ".factory_version"

    /** 出厂基因静态清单：部署与完整性校验共用（与 assets 打包内容一致） */
    private val GENOME_FILES = listOf(
        "android_api.py",
        "rea_core.py",
        "rea_mcp.py",
        "zagent.py",
        "zagent_app.py",
        "zagent_sessions.py",
        "zcore/__init__.py",
        "zcore/action_manifest.py",
        "zcore/actions.py",
        "zcore/audit_verify.py",
        "zcore/checkpoint.py",
        "zcore/events.py",
        "zcore/facts.py",
        "zcore/gate.py",
        "zcore/limiter.py",
        "zcore/mask.py",
        "zcore/proc.py",
        "zcore/sys_mcp.py",
        "zcore/util.py",
        "zguard/__init__.py",
        "zguard/boot_guard.py",
        "zguard/boot_guard_selftest.py",
        "zguard/shadow_child.py",
        "zguard/shadow_test.py",
    )

    @Volatile
    private var bootstrapped = false
    private var bootFailed = false
    private var bootFailMsg = ""

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    /**
    * 幂等引导：首次调用完成 Chaquopy 启动、基因部署与 zagent_app.bootstrap。
    * 所有桥接方法入口处都会先调它，业务代码无需关心初始化时机。
    */
    @Synchronized
    fun ensureBootstrapped(context: Context) {
        if (bootstrapped) return
        if (bootFailed) throw IllegalStateException("bootstrap failed: " + bootFailMsg)
        val app = context.applicationContext
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(app))
            }
            AndroidAPI.init(app)
            val genome = deployFactoryGenome(app)
            deployBuiltinPlugins(app)
            insertSysPathHead(genome)
            Python.getInstance().getModule("zagent_app")
                .callAttr("bootstrap", homeDir(app).absolutePath)
            bootstrapped = true
        } catch (t: Throwable) {
            bootFailed = true
            bootFailMsg = t.message ?: t.javaClass.simpleName
            throw t
        }
    }

    /** home = filesDir/zagent_home（zagent 的 HOME、mind/、plugins/ 都在其下） */
    private fun homeDir(context: Context): File =
        File(context.applicationContext.filesDir, "zagent_home")

    /**
    * 部署出厂基因：assets/zagent_factory → home/factory。
    * versionCode 变化或文件缺失时全量重拷；拷完逐一校验确实是 .py 文本。
    */
   
/**

部署内置插件：assets/plugins → home/plugins。

每次启动强制覆盖内置插件，用户自定义插件不受影响。
*/
    private fun deployBuiltinPlugins(context: Context) {
        val homePlugins = File(homeDir(context), "plugins")
        homePlugins.mkdirs()
        try {
            val children = context.assets.list("plugins").orEmpty()
            for (child in children) {
if (child.startsWith("test_") && child.endsWith(".py")) continue
                copyAssetsDir(context, "plugins/" + child, File(homePlugins, child))
            }
        } catch (e: Exception) {
            Log.w(TAG, "部署内置插件失败：", e)
        }
    }

    private fun deployFactoryGenome(context: Context): File {
        val factory = File(homeDir(context), "factory")
        val mark = File(factory, FACTORY_MARK)
        val vc = versionCode(context)
        
        // 出厂基因每次启动全量覆盖：versionCode 不变也要更新 .py，
        // 否则已装 APK 升级后仍用旧的 zagent.py / zagent_app.py（静默降级）。
        runCatching { deleteRecursively(factory) }
            .onFailure { Log.w(TAG, "清理旧出厂基因失败", it) }
        factory.mkdirs()
        
        try {
            copyAssetsDir(context, ASSET_ROOT, factory)
        } catch (e: Exception) {
            throw IllegalStateException(
                "读取 assets/$ASSET_ROOT 失败：${e.message}。" +
                        "请确认工程里存在 app/src/main/assets/zagent_factory/" +
                        "{zagent.py, zcore/, zguard/} 且已随 APK 打包。", e)
        }

        val missing = GENOME_FILES.filterNot { File(factory, it).isFile }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "出厂基因复制不完整（缺少 ${missing.joinToString("、")}）。" +
                        "请确认 app/src/main/assets/zagent_factory 下包含全部 " +
                        ".py 文件后重新打包。")
        }
        GENOME_FILES.forEach { rel ->
            val f = File(factory, rel)
            if (looksLikePyc(f)) {
                throw IllegalArgumentException(
                    "出厂基因文件 $rel 不是 .py 源码（疑似 .pyc 字节码）。" +
                            "请勿把 __pycache__ 产物放进 assets/zagent_factory。")
            }
        }
        mark.writeText(vc.toString())
        Log.i(TAG, "出厂基因已复制：$factory（v$vc，${GENOME_FILES.size} 个文件，已校验为 .py 源码）")
        return factory
    }

    /** 递归复制 assets 子目录到目标目录（同名覆盖） */
    private fun copyAssetsDir(context: Context, assetPath: String, outDir: File) {
        val am = context.assets
        val children = am.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            // 是文件
            val target = outDir
            target.parentFile?.mkdirs()
            am.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        outDir.mkdirs()
        for (child in children) {
if (child.startsWith("test_") && child.endsWith(".py")) continue
            copyAssetsDir(context, "$assetPath/$child", File(outDir, child))
        }
    }

    private fun deleteRecursively(f: File) {
        if (f.isDirectory) {
            f.listFiles()?.forEach { deleteRecursively(it) }
        }
        f.delete()
    }

    /** pyc 魔数探测：旧式 0D 0A 开头，或 py3 的 "3\\r\\n"（0x33 0x0D 0x0A） */
    private fun looksLikePyc(f: File): Boolean {
        return try {
            FileInputStream(f).use { ins ->
                val b = ByteArray(4)
                val n = ins.read(b)
                if (n < 4) return@use false
                (b[0] == 0x0D.toByte() && b[1] == 0x0A.toByte()) ||
                        (b[0] == 0x33.toByte() && b[1] == 0x0D.toByte() &&
                                b[2] == 0x0A.toByte())
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 把目录插到 sys.path 头部（失败时退回 exec 兜底） */
    private fun insertSysPathHead(dir: File) {
        val path = dir.absolutePath
        val py = Python.getInstance()
        try {
    val sysPath = py.getModule("sys").get("path")
    ?: throw IllegalStateException("sys.path 不可用")
            sysPath.callAttr("insert", 0, path)
        } catch (e: Exception) {
            Log.w(TAG, "sys.path.insert 直调失败，改用 exec：$e")
            py.getModule("builtins").callAttr(
                "exec",
                "import sys; sys.path.insert(0, '$path')"
            )
        }
    }

    private fun versionCode(context: Context): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取 versionCode 失败：$e")
            0L
        }
    }

    // ------------------------------------------------------------------
    // 业务桥接（统一空安全：?.toString() ?: ""）
    // ------------------------------------------------------------------

    /** 一轮交互（命令或对话），返回本轮全部输出文本 */
    private val runLock = java.util.concurrent.atomic.AtomicBoolean(false)

    fun runAgent(context: Context, userInput: String): String {
        if (!runLock.compareAndSet(false, true)) {
            return "[busy] 已有任务在执行，请等待完成或先点击停止。"
        }
        try {
            ensureBootstrapped(context)
            return Python.getInstance().getModule("zagent_app")
                .callAttr("run_agent", userInput)?.toString() ?: ""
        } finally {
            runLock.set(false)
        }
    }

    /** 写入 API Key 并热更新 */
    fun setApiKey(context: Context, key: String): String {
        ensureBootstrapped(context)
        return Python.getInstance().getModule("zagent_app")
            .callAttr("set_api_key", key)?.toString() ?: ""
    }

    /** 设置 API 地址（Base URL），空串 = 恢复默认端点 */
    fun setBaseUrl(context: Context, url: String): String {
        ensureBootstrapped(context)
        return Python.getInstance().getModule("zagent_app")
            .callAttr("set_base_url", url)?.toString() ?: ""
    }

    /**
    * 设置模型名称（本版新增）。
    * 空串 = 恢复出厂默认模型；非空立即热更新 zag.MODEL，
    * 下一轮对话的 payload.model 立即生效。
    */
    fun setModel(context: Context, name: String): String {
        ensureBootstrapped(context)
        return Python.getInstance().getModule("zagent_app")
            .callAttr("set_model", name)?.toString() ?: ""
    }

    /** 切换 AUTO_EXECUTE（自动执行确认开关） */
    fun setAuto(context: Context, flag: Boolean): String {
        ensureBootstrapped(context)
        return Python.getInstance().getModule("zagent_app")
            .callAttr("set_auto", flag)?.toString() ?: ""
    }

    /** v6.4 切换 THINK_ENABLED */
    fun setThink(context: Context, flag: Boolean): String {
        ensureBootstrapped(context)
        return Python.getInstance().getModule("zagent_app")
            .callAttr("set_think", flag)?.toString() ?: ""
    }

    /** 清空会话（等价 /clear） */
    fun resetSession(context: Context): String {
        ensureBootstrapped(context)
        return Python.getInstance().getModule("zagent_app")
            .callAttr("reset_session")?.toString() ?: ""
    }

    /** JSON 状态：version/gen/api_key_set/base_url/model/auto/turns… */
    fun status(context: Context): String {
        ensureBootstrapped(context)
        return Python.getInstance().getModule("zagent_app")
            .callAttr("get_status")?.toString() ?: ""
    }

    // ------------------------------------------------------------------
    // 插件桥接（PluginActivity 专用）
    // ------------------------------------------------------------------

    fun listPlugins(context: Context): String {
        ensureBootstrapped(context)
        return Python.getInstance().getModule("zagent_app")
            .callAttr("plugins_list")?.toString() ?: ""
    }

    fun readPlugin(context: Context, pid: String): String {
        ensureBootstrapped(context)
        return Python.getInstance().getModule("zagent_app")
            .callAttr("plugin_read", pid)?.toString() ?: ""
    }

    fun deletePlugin(context: Context, pid: String): String {
        ensureBootstrapped(context)
        return Python.getInstance().getModule("zagent_app")
            .callAttr("plugin_delete", pid)?.toString() ?: ""
    }
    fun togglePlugin(context: Context, pid: String, enabled: Boolean): String {
        ensureBootstrapped(context)
        return Python.getInstance().getModule("zagent_app")
            .callAttr("plugin_toggle", pid, enabled)?.toString() ?: ""
    }

    /** 轮询流式缓冲（JSON: {ok,chunks:[{t,v}],done}）*/
    fun pollStream(context: Context): String {
        ensureBootstrapped(context)
        return Python.getInstance().getModule("zagent_app")
            .callAttr("poll_stream")?.toString() ?: ""
    }

    /** 请求停止当前流式输出 */
    fun stopStream(context: Context): String {
        ensureBootstrapped(context)
        return Python.getInstance().getModule("zagent_app")
            .callAttr("stop_stream")?.toString() ?: ""
    }
    /** 分析模块 - Kotlin 直接调用，绕过命令字符串 */
    fun analyzeAction(context: Context, action: String, arg: String): String {
    ensureBootstrapped(context)
    return Python.getInstance().getModule("zagent_app")
    .callAttr("analyze_action", action, arg)?.toString() ?: ""
    }


    /** 联网搜索开关：写 config.json 并回填 zagent.SEARCH_ENABLED */
    fun setSearch(context: Context, flag: Boolean): String {
    ensureBootstrapped(context)
    return Python.getInstance().getModule("zagent_app")
    .callAttr("set_search", flag)?.toString() ?: ""
    }

    /** 以命令方式跑一轮（/plan、/build 等） */
    fun runCommand(context: Context, cmd: String): String = runAgent(context, cmd)

    /* 拉取网页正文（JSON） */
    fun fetchPage(context: Context, url: String): String {
    ensureBootstrapped(context)
    return Python.getInstance().getModule("zagent_app")
    .callAttr("fetch_page_api", url)?.toString() ?: ""
    }

 // ---------------- 会话管理 ----------------

    fun sessionsList(context: Context): String {
    ensureBootstrapped(context)
    return Python.getInstance().getModule("zagent_app")
    .callAttr("sessions_list")?.toString() ?: ""
    }

    fun sessionNew(context: Context): String {
    ensureBootstrapped(context)
    return Python.getInstance().getModule("zagent_app")
    .callAttr("session_new")?.toString() ?: ""
    }

    fun sessionSwitch(context: Context, sid: String): String {
    ensureBootstrapped(context)
    return Python.getInstance().getModule("zagent_app")
    .callAttr("session_switch", sid)?.toString() ?: ""
    }

    fun sessionDelete(context: Context, sid: String): String {
    ensureBootstrapped(context)
    return Python.getInstance().getModule("zagent_app")
    .callAttr("session_delete", sid)?.toString() ?: ""
    }

    fun sessionRename(context: Context, sid: String, title: String): String {
    ensureBootstrapped(context)
    return Python.getInstance().getModule("zagent_app")
    .callAttr("session_rename", sid, title)?.toString() ?: ""
    }

    fun sessionCurrent(context: Context): String {
    ensureBootstrapped(context)
    return Python.getInstance().getModule("zagent_app")
    .callAttr("session_current")?.toString() ?: ""
    }

    fun sessionExport(context: Context, sid: String, outDir: String): String {
    ensureBootstrapped(context)
    return Python.getInstance().getModule("zagent_app")
    .callAttr("session_export", sid, outDir)?.toString() ?: ""
    }


    /** 免费模式开关：free=true 时走服务端代理，不持有真实 API Key */
    fun setFreeMode(context: Context, free: Boolean, androidId: String, deviceCode: String): String {
    ensureBootstrapped(context)
    return Python.getInstance().getModule("zagent_app")
    .callAttr("set_free_mode", free, androidId, deviceCode)?.toString() ?: ""
    }


 // 在 proot + Termux bootstrap 环境执行 Linux 命令，返回 JSON 字符串
 
fun showDynamicUi(context: Context, jsonStr: String): String {
val act = AndroidAPI.getActivity() ?: return "ERROR: Activity not found"
return DynamicUiRenderer.show(act, jsonStr)
}

    fun execShell(context: Context, cmd: String, timeoutSec: Int): String {
    ensureBootstrapped(context)
    return cn.rain.zagent.linux.LinuxShellBridge.execJson(cmd, timeoutSec)
    }

 // 查看 LinuxShellBridge 状态（是否已解压完毕）
    fun linuxStatus(context: Context): String {
    ensureBootstrapped(context)
    return cn.rain.zagent.linux.LinuxShellBridge.statusJson()
    }

 // 主动触发 bootstrap（首次调用会解压 90MB rootfs）
    fun linuxBootstrap(context: Context): String {
    ensureBootstrapped(context)
    val ok = cn.rain.zagent.linux.LinuxShellBridge.ensureBootstrapped(context)
    return if (ok) "ready" else "fail: " + cn.rain.zagent.linux.LinuxShellBridge.getLastError()
    }

 /** 动作注册表快照（PluginActivity 动作页 / 快捷栏） */
 fun actionsList(context: Context): String {
    ensureBootstrapped(context)
    return Python.getInstance().getModule("zagent_app")
        .callAttr("actions_list")?.toString() ?: ""
 }

    /* 快捷按钮候选：tags 含 quick 或 android.* 前缀，且 level<=2 的动作 */
    fun actionsQuickList(context: Context): String {
        ensureBootstrapped(context)
        return Python.getInstance().getModule("zagent_app")
            .callAttr("actions_quick_list")?.toString() ?: "[]"
    }

    /* 执行一个已注册动作，argsJson 是 JSON 对象字符串 */
    fun actionsExecute(context: Context, aid: String, argsJson: String): String {
        ensureBootstrapped(context)
        return Python.getInstance().getModule("zagent_app")
            .callAttr("actions_execute", aid, argsJson)?.toString() ?: ""
    }
 /* 单个动作的完整 manifest（含 args schema） */
 fun actionRead(context: Context, aid: String): String {
    ensureBootstrapped(context)
    return Python.getInstance().getModule("zagent_app")
        .callAttr("action_read", aid)?.toString() ?: ""
 }
}