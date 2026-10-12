package com.example.zagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * MainActivity —— Z-Agent 主界面（微信/QQ 式三段布局：标题栏 / 对话区 / 输入栏）。
 *
 * 职责：
 *  1. 启动引导（PythonBridge.ensureBootstrapped → zagent_app.bootstrap）；
 *  2. 对话发送与输出着色（appendOutput / lineColor）；
 *  3. 右上角设置弹窗（5 项）：
 *       设置 API Key / 设置 API 地址（Base URL）/ 设置模型名称 /
 *       清空会话 / 查看状态；
 *  4. 模型名持久化：SharedPreferences("zagent") 的 model_name 键，
 *     每次启动引导完成后自动同步到 Python 层（config.json + MODEL 热更新）。
 *
 * 线程约定（与原版一致）：
 *  - 所有 Python 桥接调用都在 threadRun{} 后台线程执行；
 *  - UI 更新一律 runOnUiThread{}；
 *  - 后台块套 safeBridge{} 统一兜底，异常显示为 [桥接异常]。
 */
class MainActivity : AppCompatActivity() {

    // ---- 视图（与 activity_main.xml 的 id 一一对应）----
    private lateinit var statusText: TextView
    private lateinit var outputText: TextView
    private lateinit var outputScroll: ScrollView
    private lateinit var inputEdit: EditText
    private lateinit var sendButton: Button
    private lateinit var autoSwitch: android.widget.Switch
    private lateinit var chipThink: TextView
    private lateinit var chipPlan: TextView
    private lateinit var chipBuild: TextView

    // ---- 状态 ----
    private var busy = false                       // 一轮交互进行中（防重入）
    private var mode = "build"                     // think / plan / build

    // ---- 模型名持久化（新增）----
    private lateinit var sp: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sp = getSharedPreferences("zagent", Context.MODE_PRIVATE)

        // ---- 绑定视图 ----
        statusText = findViewById(R.id.statusText)
        outputText = findViewById(R.id.outputText)
        outputScroll = findViewById(R.id.outputScroll)
        inputEdit = findViewById(R.id.inputEdit)
        sendButton = findViewById(R.id.sendButton)
        autoSwitch = findViewById(R.id.autoSwitch)
        chipThink = findViewById(R.id.chipThink)
        chipPlan = findViewById(R.id.chipPlan)
        chipBuild = findViewById(R.id.chipBuild)

        // ---- 事件绑定 ----
        findViewById<View>(R.id.settingsButton).setOnClickListener { showSettings() }
        findViewById<View>(R.id.btnClear).setOnClickListener { sendCommand("/clear") }
        findViewById<View>(R.id.btnContract).setOnClickListener { sendCommand("/contract") }
        findViewById<View>(R.id.btnHelp).setOnClickListener { sendCommand("/help") }
        findViewById<View>(R.id.btnIdle).setOnClickListener { sendCommand("/idle") }
        findViewById<View>(R.id.btnVersions).setOnClickListener { sendCommand("/versions") }
        findViewById<View>(R.id.btnPlugins).setOnClickListener {
            startActivity(Intent(this, PluginActivity::class.java))
        }
        findViewById<View>(R.id.btnSavePlugin).setOnClickListener { promptSavePlugin() }

        chipThink.setOnClickListener { switchMode("think") }
        chipPlan.setOnClickListener { switchMode("plan") }
        chipBuild.setOnClickListener { switchMode("build") }

        autoSwitch.setOnCheckedChangeListener { _, checked ->
            threadRun {
                safeBridge {
                    PythonBridge.INSTANCE.setAuto(this@MainActivity, checked)
                }
            }
        }

        sendButton.setOnClickListener { onSend() }

        refreshChipVisual()

        // ---- 启动引导（后台线程，完成前先显示占位文案）----
        appendOutput("Z-Agent APK 启动中……\n")
        threadRun {
            try {
                val st = PythonBridge.INSTANCE.status(this@MainActivity)
                // 引导完成后：把 SharedPreferences 里的模型名同步给 Python 层
                val savedModel = sp.getString(KEY_MODEL_NAME, "") ?: ""
                if (savedModel.isNotBlank()) {
                    try {
                        PythonBridge.INSTANCE.setModel(this@MainActivity, savedModel)
                    } catch (e: Exception) {
                        runOnUiThread { appendOutput("[设置] 模型名同步失败：${e.message}\n") }
                    }
                }
                runOnUiThread {
                    showStatus(st)
                    appendOutput("输入消息开始对话；点上方按钮直达功能。\n")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "初始化失败"
                    appendOutput("\n初始化异常：$e\n")
                }
            }
        }

        requestNotifyPermission()
    }

    // ------------------------------------------------------------------
    // 设置弹窗（右上角入口；本版新增「设置模型名称」共 5 项）
    // ------------------------------------------------------------------

    private fun showSettings() {
        val items = arrayOf(
            "设置 API Key",
            "设置 API 地址（Base URL）",
            "设置模型名称（Model Name）",
            "清空会话",
            "查看状态"
        )
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> promptApiKey()
                    1 -> promptBaseUrl()
                    2 -> promptModel()
                    3 -> confirmClear()
                    4 -> threadRun {
                        safeBridge {
                            val st = try {
                                PythonBridge.INSTANCE.status(this@MainActivity)
                            } catch (e: Exception) {
                                "{}"
                            }
                            runOnUiThread { showStatus(st) }
                        }
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 设置 API Key：输入 → 线程写 Python config.json → 回显结果。 */
    private fun promptApiKey() {
        val input = EditText(this).apply { hint = "sk-..." }
        AlertDialog.Builder(this)
            .setTitle("DeepSeek API Key")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val key = input.text.toString().trim()
                threadRun {
                    safeBridge {
                        val r = PythonBridge.INSTANCE.setApiKey(this@MainActivity, key)
                        runOnUiThread {
                            appendOutput("[设置] API Key $r\n")
                            toast("已保存")
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 设置 API 地址：先查当前值再弹框（当前值回填）。 */
    private fun promptBaseUrl() {
        threadRun {
            val cur = try {
                val st = PythonBridge.INSTANCE.status(this@MainActivity)
                JSONObject(st).optString("base_url", "")
            } catch (e: Exception) {
                ""
            }
            runOnUiThread { showBaseUrlDialog(cur) }
        }
    }

    private fun showBaseUrlDialog(current: String) {
        val input = EditText(this).apply {
            hint = "api.deepseek.com"
            setText(current)
        }
        AlertDialog.Builder(this)
            .setTitle("API 地址（Base URL）")
            .setMessage("支持裸域名、带 /v1 或完整端点（自动补 /chat/completions）。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val url = input.text.toString().trim()
                threadRun {
                    safeBridge {
                        val r = PythonBridge.INSTANCE.setBaseUrl(this@MainActivity, url)
                        runOnUiThread {
                            appendOutput("[设置] API 地址 $r\n")
                            toast("已保存")
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 设置模型名称（新增）：
     * 取值优先级 = SharedPreferences(model_name) → Python 状态(model)。
     * 保存时双写：SharedPreferences + Python config.json（热更新 MODEL）。
     */
    private fun promptModel() {
        val saved = sp.getString(KEY_MODEL_NAME, "") ?: ""
        if (saved.isNotBlank()) {
            showModelDialog(saved)
        } else {
            threadRun {
                val cur = try {
                    val st = PythonBridge.INSTANCE.status(this@MainActivity)
                    JSONObject(st).optString("model", "")
                } catch (e: Exception) {
                    ""
                }
                runOnUiThread { showModelDialog(cur) }
            }
        }
    }

    private fun showModelDialog(current: String) {
        val input = EditText(this).apply {
            hint = "如 deepseek-chat / deepseek-reasoner"
            setText(current)
        }
        AlertDialog.Builder(this)
            .setTitle("模型名称（Model Name）")
            .setMessage("留空并保存 = 恢复出厂默认模型。保存后下一轮对话立即生效。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                // 1) 持久化到 SharedPreferences（即使 Python 未就绪也不丢失）
                sp.edit().putString(KEY_MODEL_NAME, name).apply()
                // 2) 同步到 Python 层（config.json + MODEL 热更新）
                threadRun {
                    safeBridge {
                        val r = PythonBridge.INSTANCE.setModel(this@MainActivity, name)
                        runOnUiThread {
                            appendOutput("[设置] 模型名称 $r\n")
                            toast("已保存")
                            refreshStatusQuiet()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 清空会话（带确认；等价 /clear）。 */
    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空会话")
            .setMessage("将清空对话记忆与待办（未验收合同挂起）。确定？")
            .setPositiveButton("确定") { _, _ ->
                threadRun {
                    safeBridge {
                        val r = PythonBridge.INSTANCE.resetSession(this@MainActivity)
                        runOnUiThread { appendOutput("[会话] $r\n") }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 保存会话为插件（/save 命令）。 */
    private fun promptSavePlugin() {
        val input = EditText(this).apply { hint = "插件名称（如 每日待办）" }
        AlertDialog.Builder(this)
            .setTitle("把当前会话保存为插件")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("名称不能为空")
                    return@setPositiveButton
                }
                sendCommand("/save $name")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------
    // 对话发送
    // ------------------------------------------------------------------

    private fun onSend() {
        if (busy) {
            toast("上一轮还在进行中……")
            return
        }
        val text = inputEdit.text.toString().trim()
        if (text.isEmpty()) return
        inputEdit.setText("")
        appendOutput("你> $text\n")
        sendCommand(text)
    }

    /** 发送一条消息/命令到 Python 层（busy 期间禁用发送按钮）。 */
    private fun sendCommand(cmd: String) {
        if (busy) {
            toast("上一轮还在进行中……")
            return
        }
        busy = true
        sendButton.isEnabled = false
        threadRun {
            safeBridge {
                val reply = PythonBridge.INSTANCE.runAgent(this@MainActivity, cmd)
                runOnUiThread {
                    appendOutput(reply + "\n")
                    busy = false
                    sendButton.isEnabled = true
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 模式切换（think / plan / build）
    // ------------------------------------------------------------------

    private fun switchMode(m: String) {
        mode = m
        refreshChipVisual()
        when (m) {
            "think" -> {
                inputEdit.hint = "要深度思考的任务……"
                sendCommand("/think")
            }
            "plan" -> {
                inputEdit.hint = "输入消息，或点上方按钮"
                sendCommand("/plan")
            }
            else -> {
                inputEdit.hint = "输入消息，或点上方按钮"
                sendCommand("/build")
            }
        }
    }

    private fun refreshChipVisual() {
        chipThink.isSelected = mode == "think"
        chipPlan.isSelected = mode == "plan"
        chipBuild.isSelected = mode == "build"
    }

    // ------------------------------------------------------------------
    // 输出渲染
    // ------------------------------------------------------------------

    /** 追加对话输出并按行着色，随后滚动到底部。 */
    private fun appendOutput(text: String) {
        val tv = outputText
        val start = tv.length()
        tv.append(text)
        val e = tv.text as? Editable ?: return
        var off = 0
        for (line in text.split('\n')) {
            val lineStart = start + off
            val len = minOf(line.length, 400)
            if (len > 0) {
                lineColor(line)?.let { color ->
                    e.setSpan(
                        ForegroundColorSpan(color), lineStart, lineStart + len,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            off += line.length + 1
        }
        // 滚动到底部（微信聊天式贴底）
        outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 行级配色：错误=danger，引导=ok，用户=warn，其余=accent。 */
    private fun lineColor(line: String): Int? {
        return when {
            line.startsWith("[FATAL]") || line.startsWith("[桥接异常]") ||
                line.contains("未就绪") || line.contains("初始化异常") ->
                ContextCompat.getColor(this, R.color.danger)
            line.startsWith("[bootstrap]") || line.startsWith("[boot_guard]") ||
                line.contains("就绪：v") || line.contains("体检 通过") ->
                ContextCompat.getColor(this, R.color.ok)
            line.startsWith("你>") ->
                ContextCompat.getColor(this, R.color.warn)
            else ->
                ContextCompat.getColor(this, R.color.accent)
        }
    }

    /** 解析 get_status JSON 并刷新顶部状态条（含模型名）。 */
    private fun showStatus(json: String) {
        val tv = statusText
        try {
            val o = JSONObject(json)
            val version = if (o.isNull("version")) "?" else (o.opt("version")?.toString() ?: "?")
            val gen = if (o.isNull("gen")) "?" else (o.opt("gen")?.toString() ?: "?")
            val keyOk = o.optBoolean("api_key_set", false)
            val turns = o.optInt("turns", 0)
            val model = if (o.isNull("model")) "" else (o.optString("model", ""))
            val sb = StringBuilder()
                .append("v").append(version)
                .append(" / Gen-").append(gen)
                .append(" ｜ ").append(if (keyOk) "Key 已配置" else "Key 未配置")
                .append(" ｜ 会话 ").append(turns).append(" 条")
            if (model.isNotBlank()) sb.append(" ｜ 模型 ").append(model)
            tv.text = sb.toString()
        } catch (e: Exception) {
            tv.text = "状态不可用: ${e.message}"
        }
    }

    /** 后台静默刷新一次状态条（保存模型名后调用）。 */
    private fun refreshStatusQuiet() {
        threadRun {
            safeBridge {
                val st = try {
                    PythonBridge.INSTANCE.status(this@MainActivity)
                } catch (e: Exception) {
                    "{}"
                }
                runOnUiThread { showStatus(st) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    /** 后台线程执行（所有 Python 桥接调用都必须走这里）。 */
    private fun threadRun(block: () -> Unit) {
        Thread { block() }.start()
    }

    /** 桥接异常统一兜底（显示到对话区，不崩溃）。 */
    private fun safeBridge(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            runOnUiThread {
                appendOutput("\n[桥接异常] ${e.message}\n")
                busy = false
                sendButton.isEnabled = true
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun requestNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    companion object {
        private const val KEY_MODEL_NAME = "model_name"
    }
}
