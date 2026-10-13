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
        "zagent.py",
        "zcore/__init__.py",
        "zcore/util.py",
        "zguard/__init__.py",
        "zguard/boot_guard.py",
        "zguard/boot_guard_selftest.py",
        "zguard/shadow_child.py",
        "zguard/shadow_test.py"
    )

    @Volatile
    private var bootstrapped = false

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
        val app = context.applicationContext
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(app))
        }
        AndroidAPI.init(app)
        val genome = deployFactoryGenome(app)
        insertSysPathHead(genome)
        Python.getInstance().getModule("zagent_app")
            .callAttr("bootstrap", homeDir(app).absolutePath)
        bootstrapped = true
    }

    /** home = filesDir/zagent_home（zagent 的 HOME、mind/、plugins/ 都在其下） */
    private fun homeDir(context: Context): File =
        File(context.applicationContext.filesDir, "zagent_home")

    /**
     * 部署出厂基因：assets/zagent_factory → home/factory。
     * versionCode 变化或文件缺失时全量重拷；拷完逐一校验确实是 .py 文本。
     */
    private fun deployFactoryGenome(context: Context): File {
        val factory = File(homeDir(context), "factory")
        val mark = File(factory, FACTORY_MARK)
        val vc = versionCode(context)

        val upToDate = runCatching {
            GENOME_FILES.all { File(factory, it).isFile } &&
                    mark.isFile && mark.readText().trim() == vc.toString()
        }.getOrDefault(false)
        if (upToDate) {
            Log.i(TAG, "出厂基因已是最新（v$vc）：$factory")
            return factory
        }

        deleteRecursively(factory)
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
    fun runAgent(context: Context, userInput: String): String {
        ensureBootstrapped(context)
        return Python.getInstance().getModule("zagent_app")
            .callAttr("run_agent", userInput)?.toString() ?: ""
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
}