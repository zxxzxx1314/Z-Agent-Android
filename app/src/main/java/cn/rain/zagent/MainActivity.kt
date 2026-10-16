package cn.rain.zagent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.dynamicanimation.animation.SpringAnimation
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import androidx.activity.OnBackPressedCallback
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo

class MainActivity : AppCompatActivity() {

private val pendingFiles = mutableListOf<String>()

private val pickFile = registerForActivityResult(
ActivityResultContracts.OpenDocument()
) { uri: Uri? ->
if (uri != null) handlePickedFile(uri)
}

private lateinit var statusText: TextView
private lateinit var outputScroll: ScrollView
private lateinit var chatContainer: LinearLayout
private lateinit var emptyState: LinearLayout
private lateinit var inputEdit: EditText
private lateinit var inputCard: LinearLayout
private lateinit var sendButton: FrameLayout
private lateinit var sendIcon: android.widget.ImageView
private lateinit var topBar: LinearLayout
private lateinit var themeButton: View
 private lateinit var themeIcon: ImageView
private lateinit var inputBar: LinearLayout
private lateinit var segPlan: TextView
private lateinit var segBuild: TextView
private lateinit var btnPlus: FrameLayout
private lateinit var chipThink2: TextView
private lateinit var chipSearch: TextView
private lateinit var attachScroll: HorizontalScrollView
private lateinit var attachRow: LinearLayout
 private lateinit var scrollBottomBtn: FrameLayout
 // ── 快捷条（可收起/展开/自定义）──
 private var quickButtons: MutableList<QuickBtn> = mutableListOf()
 private var quickEditMode = false
 private var quickExpanded = false
 private var quickEditRow: LinearLayout? = null
 private var quickDragFrom = -1
 private val quickAnimating = mutableSetOf<android.view.View>()


private var lastBubbleTs = 0L
private var lastBubbleIsUser = false
private var busy = false
private var reportedStartup = false
private var stopRequested = false

// 流式渲染状态
private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

private val placeholderHints = arrayOf(
"输入消息…", "解释这个文件", "重构这个函数",
"跑一下这段代码", "查这个报错", "帮我做代码审查",
"写个单元测试", "看看我的待办",
)
private var placeholderIdx = 0
private val placeholderRotator = object : Runnable {
    override fun run() {
        if (inputEdit.text.isNullOrEmpty()) {
            inputEdit.hint = placeholderHints[placeholderIdx % placeholderHints.size]
            placeholderIdx++
        }
        uiHandler.postDelayed(this, 4000)
    }
}
private var streamPolling = false
private var streamingBubble: TextView? = null
private var streamingSb = StringBuilder()
private var reasoningSb = StringBuilder()
private var reasoningBox: LinearLayout? = null
private var reasoningHeader: TextView? = null
private var reasoningBody: TextView? = null
private var reasoningStartMs = 0L
private var cursorOn = true
private var loadingDotsView: TextView? = null
private var stepBarBox: LinearLayout? = null
private var stepBarHead: LinearLayout? = null
private var stepBarLabel: TextView? = null
private var stepBarChev: TextView? = null
private var stepBarSteps: LinearLayout? = null
private var stepBarProgress: View? = null
private var stepBarProgressFill: View? = null
private var stepBarExpanded = false
private var stepBarStartMs = 0L
private var stepBarFailed = false
private val stepRows = HashMap<Int, LinearLayout>()
private var settingsSheet: BottomSheetDialog? = null
private var thinkOn = false
private var appMode = "build"

private lateinit var sp: SharedPreferences

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
setContentView(R.layout.activity_main)

    // ── 签名校验：防止二次打包 ──
    if (!SignatureCheck.verify(this, BuildConfig.ZAGENT_EXPECTED_SIGNATURE)) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("安全警告")
            .setMessage("当前 APK 签名与官方不一致，可能被篡改或二次打包。\n\n为保护你的 API Key 与数据安全，应用将退出。")
            .setCancelable(false)
            .setPositiveButton("退出") { _, _ -> finishAffinity() }
            .show()
        return
    }

    // ── API Key：优先用源码外配置（BuildConfig），首次启动自动写入 ──
    if (BuildConfig.ZAGENT_API_KEY.isNotBlank()) {
        threadRun { safeBridge {
            try {
                PythonBridge.setApiKey(this@MainActivity, BuildConfig.ZAGENT_API_KEY)
            } catch (_: Exception) { }
        } }
    }
WindowCompat.setDecorFitsSystemWindows(window, false)

sp = getSharedPreferences("zagent", Context.MODE_PRIVATE)


// Predictive back：设置 Sheet 打开时先收 Sheet，再次返回才退出

onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
override fun handleOnBackPressed() {
val s = settingsSheet
if (s != null && s.isShowing) { s.dismiss(); settingsSheet = null; return }
if (busy) {
stopRequested = true
threadRun { try { PythonBridge.stopStream(this@MainActivity) } catch (e: Exception) { } }
toast("正在停止……")
return
}
isEnabled = false
onBackPressedDispatcher.onBackPressed()
}
})

// 首次：从 EncryptedSharedPreferences 注入 API Key 到 Python 层
val encKey = SecurePrefs.getApiKey(this)
if (!encKey.isNullOrBlank()) {
threadRun { safeBridge { try { PythonBridge.setApiKey(this@MainActivity, encKey) } catch (e: Exception) { } } }
}

statusText = findViewById(R.id.statusText)
outputScroll = findViewById(R.id.outputScroll)
chatContainer = findViewById(R.id.chatContainer)
emptyState = findViewById(R.id.emptyState)
inputEdit = findViewById(R.id.inputEdit)
inputCard = findViewById(R.id.inputCard)
sendButton = findViewById(R.id.sendButton)
    sendIcon = sendButton.getChildAt(0) as android.widget.ImageView
topBar = findViewById(R.id.topBar)
themeButton = findViewById(R.id.themeButton)
 themeIcon = findViewById(R.id.themeIcon)
inputBar = findViewById(R.id.inputBar)
segPlan = findViewById(R.id.segPlan)
segBuild = findViewById(R.id.segBuild)
btnPlus = findViewById(R.id.btnPlus)
chipThink2 = findViewById(R.id.chipThink2)
chipSearch = findViewById(R.id.chipSearch)
attachScroll = findViewById(R.id.attachScroll)
attachRow = findViewById(R.id.attachRow)

scrollBottomBtn = findViewById(R.id.scrollBottomBtn)

// 回到底部：滚动离开底部时显示悬浮按钮
outputScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
val ch = outputScroll.getChildAt(0) ?: return@setOnScrollChangeListener
val atBottom = scrollY >= ch.height - outputScroll.height - dp(8)
autoScrollEnabled = atBottom
scrollBottomBtn.visibility = if (atBottom) View.GONE else View.VISIBLE
}
scrollBottomBtn.setOnClickListener {
haptic(it)
outputScroll.post {
 outputScroll.fullScroll(View.FOCUS_DOWN)
autoScrollEnabled = true
 scrollBottomBtn.visibility = View.GONE
 }
}

// 状态栏长按复制
statusText.setOnLongClickListener {
haptic(it)
copyText(statusText.text.toString())
true
}

// 输入草稿自动保存 / 恢复
val _draft = sp.getString(KEY_DRAFT, "") ?: ""; if (_draft.isNotBlank()) { inputEdit.setText(_draft); inputEdit.hint = "[草稿] 点击继续编辑" }

// ── 空状态 Prompt 卡片：点击填入输入框 ──
findViewById<View>(R.id.promptCard1)?.setOnClickListener {
haptic(it)
inputEdit.setText("帮我写一份本周工作周报，按「本周进展 / 遇到的问题 / 下周计划」结构组织")
inputEdit.setSelection(inputEdit.text.length)
setAppMode("plan"); try { segPlan.thinkingPulseOnce() } catch (x: Throwable) {}
}
findViewById<View>(R.id.promptCard2)?.setOnClickListener {
haptic(it)
inputEdit.setText("解释下面这段代码的逻辑，指出关键控制流和潜在问题：\n")
inputEdit.setSelection(inputEdit.text.length)
setAppMode("build"); try { segBuild.thinkingPulseOnce() } catch (x: Throwable) {}
}
findViewById<View>(R.id.promptCard3)?.setOnClickListener {
haptic(it)
inputEdit.setText("深度分析并总结下面这篇文档的核心论点：\n")
inputEdit.setSelection(inputEdit.text.length)
if (!thinkOn) { thinkOn = true; refreshThinkChip(); sp.edit().putBoolean(KEY_THINK, true).apply(); threadRun { safeBridge { try { PythonBridge.setThink(this@MainActivity, true) } catch (e: Exception) { } } } }
}

// ── 发送键状态色：空置灰、有字高亮 ──
inputEdit.addTextChangedListener(object : android.text.TextWatcher {
override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
override fun afterTextChanged(s: android.text.Editable?) {
refreshSendButton()
}
})
inputEdit.setSelection(inputEdit.text.length)
// 输入框 Enter = 发送（多行视觉但仍走 IME_ACTION_SEND）
inputEdit.setOnEditorActionListener { _, actionId, event ->
if (actionId == EditorInfo.IME_ACTION_SEND ||
(event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
onSend(); true
} else false
}
inputEdit.setOnKeyListener { _, keyCode, event ->
if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN && !event.isShiftPressed) {
onSend(); true
} else false
}
inputEdit.addTextChangedListener(object : android.text.TextWatcher {
override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
override fun afterTextChanged(s: android.text.Editable?) {
sp.edit().putString(KEY_DRAFT, s?.toString() ?: "").apply()
}
})

// 长按发送键 = 清空输入
sendButton.setOnLongClickListener {
haptic(it)
inputEdit.setText("")
sp.edit().remove(KEY_DRAFT).apply()
toast("已清空输入")
true
}
applyTopInset()
applySavedTheme()

findViewById<View>(R.id.settingsButton).setOnClickListener { haptic(it); showSettingsTransform() }


themeButton.setOnClickListener { haptic(it); toggleTheme() }

// v6.4 restore thinkOn
thinkOn = sp.getBoolean(KEY_THINK, false)
refreshThinkChip()
threadRun { safeBridge { try { PythonBridge.setThink(this@MainActivity, thinkOn) } catch (e: Exception) { } } }
initQuickBar()
refreshAutoSwitch(sp.getBoolean(KEY_AUTO_EXEC, false))


sendButton.setOnClickListener { haptic(it); onSend() }
btnPlus.setOnClickListener {
haptic(it)
try { pickFile.launch(arrayOf("*/*")) }
catch (e: Exception) { toast("无法打开文件选择器: " + (e.message ?: "")) }
}

segPlan.setOnClickListener { haptic(it); setAppMode("plan") }
segBuild.setOnClickListener { haptic(it); setAppMode("build") }

chipThink2.setOnClickListener {
    haptic(it)
    thinkOn = !thinkOn
    refreshThinkChip()
    sp.edit().putBoolean(KEY_THINK, thinkOn).apply()
    threadRun { safeBridge { try { PythonBridge.setThink(this@MainActivity, thinkOn) } catch (e: Exception) { } } }
}
chipSearch.visibility = View.GONE
refreshThinkChip()
 refreshSegVisual()


inputEdit.setOnFocusChangeListener { _, hasFocus ->
    inputCard.setBackgroundResource(
        if (hasFocus) R.drawable.bg_input_card_focus else R.drawable.bg_input_card
    )
}
inputEdit.addTextChangedListener(object : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) {
        if (!busy) refreshSendButton()
    }
})
inputCard.setOnClickListener {
if (inputEdit.text.isNullOrEmpty()) {
val h = inputEdit.hint?.toString() ?: ""
if (h.isNotEmpty()) {
inputEdit.setText(h)
inputEdit.setSelection(h.length)
}
}
inputEdit.requestFocus()
}


    // 圆形按钮按下缩放
    btnPlus.pressScale()
    sendButton.pressScale()


// appendOutput("Z-Agent APK 启动中……\n")
threadRun {
try {
val st = PythonBridge.status(this@MainActivity)
val savedModel = sp.getString(KEY_MODEL_NAME, "") ?: ""
if (savedModel.isNotBlank()) {
try {
PythonBridge.setModel(this@MainActivity, savedModel)
} catch (e: Exception) {
runOnUiThread { appendOutput("[设置] 模型名同步失败：" + (e.message ?: "") + "\n") }
}
}
runOnUiThread {
showStatus(st)
// appendOutput("输入消息开始对话；点下方按钮直达功能。\n")
}
} catch (e: Exception) {
runOnUiThread {
statusText.text = "初始化失败"
appendOutput("\n初始化异常：" + e.toString() + "\n")
}
}
}

requestNotifyPermission()
}

override fun onResume() {
    super.onResume()
    ensureExternalAccess()
    if (!reportedStartup) {
        val ms = AppStartup.elapsed()
        if (ms in 1..30000) {
            reportedStartup = true
            statusText.text = "冷启动 " + ms + "ms"
            uiHandler.postDelayed({ refreshStatusQuiet() }, 4000)
        }
    }
    uiHandler.postDelayed(placeholderRotator, 4000)

}

override fun onPause() {
    super.onPause()
    uiHandler.removeCallbacks(placeholderRotator)
}

private val secretRegex = Regex("(ghp_|github_pat_|sk-)([A-Za-z0-9_-]{8,})|(Bearer +)([A-Za-z0-9._-]{8,})")

private fun maskOne(s: String): String =
    if (s.length <= 8) s
    else s.substring(0, 4) + "..." + s.substring(s.length - 4) + " [已脱敏]"

private fun maskSecret(s: String): String =
    secretRegex.replace(s) { m ->
        if (m.groupValues[1].isNotEmpty())
            m.groupValues[1] + maskOne(m.groupValues[2])
        else
            m.groupValues[3] + maskOne(m.groupValues[4])
    }


private fun haptic(v: View) {
HapticTokens.tap(v)
}

private fun fillInput(t: String) {
inputEdit.setText(t)
inputEdit.setSelection(t.length)
inputEdit.requestFocus()
}
private var autoScrollEnabled = true
private var lastAutoScrollMs = 0L

private fun smartScrollBottom() {
 if (!autoScrollEnabled) return
 val now = System.currentTimeMillis()
 if (now - lastAutoScrollMs < 90L) return
 lastAutoScrollMs = now
 outputScroll.post {
 try { outputScroll.fullScroll(View.FOCUS_DOWN) } catch (x: Throwable) {}
 }
}


private fun hideEmpty() {
if (emptyState.visibility != View.GONE) emptyState.visibility = View.GONE
}

private fun applyTopInset() {
 val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
 val statusH = if (resId > 0) resources.getDimensionPixelSize(resId) else 0
 val baseTop = statusH + dp(10)
 topBar.setPadding(topBar.paddingLeft, baseTop, topBar.paddingRight, topBar.paddingBottom)
 val root = findViewById<View>(R.id.rootView)
 // 状态栏 padding 加到根视图顶部（避免局部 view 首次布局时 insets 未分发）
 ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
 val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
 val wantTop = bars.top + dp(10)
 if (wantTop > topBar.paddingTop) {
  topBar.setPadding(topBar.paddingLeft, wantTop, topBar.paddingRight, topBar.paddingBottom)
 }
 applyImePadding(insets)
 insets
 }
 // IME inset：键盘弹出时输入卡贴键盘上沿跟手上移（不瞬移）
 ViewCompat.setWindowInsetsAnimationCallback(root,
 object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
 override fun onProgress(
 insets: WindowInsetsCompat,
 runningAnimations: MutableList<WindowInsetsAnimationCompat>
 ): WindowInsetsCompat {
 applyImePadding(insets)
 return insets
 }
 })
 ViewCompat.requestApplyInsets(root)
}

// 计算并应用 IME + 导航栏 padding（带范围保护）
private fun applyImePadding(insets: WindowInsetsCompat) {
    val sys = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
    val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
    val h = resources.displayMetrics.heightPixels
    val imeBottom = if (ime.bottom in 1..(h / 2)) ime.bottom else 0
    val pad = maxOf(sys.bottom, imeBottom).coerceIn(0, h / 3)
    inputBar.setPadding(inputBar.paddingLeft, inputBar.paddingTop,
        inputBar.paddingRight, pad)
}

private fun applySavedTheme() {
val light = sp.getBoolean(KEY_LIGHT_THEME, true)
AppCompatDelegate.setDefaultNightMode(
if (light) AppCompatDelegate.MODE_NIGHT_NO
else AppCompatDelegate.MODE_NIGHT_YES)
themeIcon.setImageResource(if (light) R.drawable.ic_sun else R.drawable.ic_moon)
 themeButton.contentDescription = if (light) "当前亮色主题，点击切换到暗色" else "当前暗色主题，点击切换到亮色"
}

private fun toggleTheme() {
val light = sp.getBoolean(KEY_LIGHT_THEME, true)
sp.edit().putBoolean(KEY_LIGHT_THEME, !light).apply()
AppCompatDelegate.setDefaultNightMode(
if (!light) AppCompatDelegate.MODE_NIGHT_NO
else AppCompatDelegate.MODE_NIGHT_YES)
themeIcon.setImageResource(if (!light) R.drawable.ic_sun else R.drawable.ic_moon)
}

// 自动执行：不再放主界面，仅在设置页内切换
private fun refreshAutoSwitch(on: Boolean) {
sheetRows["auto"]?.text = if (on) "跳过确认" else "每步询问"
}

// 设置页里点「自动执行（跳过确认）」→ 二次确认
private fun toggleAutoExec() {
val cur = sp.getBoolean(KEY_AUTO_EXEC, false)
if (cur) {
confirmSheet(
title = "关闭自动执行？",
message = "关闭后，Agent 每次操作都会先询问你确认。",
confirmLabel = "关闭"
) {
sp.edit().putBoolean(KEY_AUTO_EXEC, false).apply()
threadRun { safeBridge { try { PythonBridge.setAuto(this@MainActivity, false) } catch (e: Exception) { } } }
refreshAutoSwitch(false)
toast("已关闭自动执行")
}
} else {
confirmSheet(
title = "开启自动执行（跳过确认）？",
message = "开启后 Agent 操作不再逐条询问，可能直接改动文件或应用。仅在你完全信任当前任务时开启。",
confirmLabel = "开启",
danger = true
) {
sp.edit().putBoolean(KEY_AUTO_EXEC, true).apply()
threadRun { safeBridge { try { PythonBridge.setAuto(this@MainActivity, true) } catch (e: Exception) { } } }
refreshAutoSwitch(true)
toast("已开启自动执行（跳过确认）")
}
}
}

private fun showSettingsTransform() {
    val loc = IntArray(2)
    themeButton.getLocationInWindow(loc)
    showSettings(loc[0] + themeButton.width / 2f, loc[1] + themeButton.height / 2f)
}

private fun showSettings(originX: Float = -1f, originY: Float = -1f) {
val sheet = BottomSheetDialog(this)
    settingsSheet = sheet
val root = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_sheet)
setPadding(0, dp(8), 0, dp(18))
}

// 拖柄
val handle = View(this).apply {
setBackgroundResource(R.drawable.bg_handle)
layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
gravity = Gravity.CENTER_HORIZONTAL
topMargin = dp(6); bottomMargin = dp(14)
}
}
root.addView(handle)

// 标题
root.addView(TextView(this).apply {
text = "设置"
textSize = 22f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setTypeface(null, android.graphics.Typeface.BOLD)
setPadding(dp(22), 0, dp(22), dp(6))
})

// 分组：连接
root.addView(groupLabel("连接"))
root.addView(settingsRow(R.drawable.ic_key, "API Key", apiKeySummary) { promptApiKey() })
root.addView(settingsRow(R.drawable.ic_globe, "API 地址", baseUrlSummary) { promptBaseUrl() })
root.addView(settingsRow(R.drawable.ic_model, "模型名称", modelSummary) { promptModel() })
val _testRow = settingsRow(R.drawable.ic_refresh, "测试连接", if (apiKeySummary == "未设置") "先去配置 API Key" else "检查 API 地址与 Key") { if (apiKeySummary == "未设置") promptApiKey() else testConnection() }
 root.addView(_testRow)
 _testRow.alpha = if (apiKeySummary == "未设置") 0.5f else 1f
root.addView(settingsRow(R.drawable.ic_refresh, "自动执行（跳过确认）", if (sp.getBoolean(KEY_AUTO_EXEC, false)) "开启 · 跳过确认" else "关闭 · 每步询问") { toggleAutoExec() })

// 分组：会话
root.addView(groupLabel("会话"))

 root.addView(settingsRow(R.drawable.ic_info, "运行日志", "查看执行日志") { showLogSheet() })
root.addView(settingsRow(R.drawable.ic_info, "关于", statusSummary) {
threadRun { safeBridge {
val st = try { PythonBridge.status(this@MainActivity) } catch (e: Exception) { "{}" }
runOnUiThread { showStatus(st) }
} }
})

root.addView(groupLabel("危险操作"))
 root.addView(settingsRow(R.drawable.ic_trash, "清空会话", turnsSummary, danger = true) { confirmClear() })
 sheet.setContentView(root)
sheet.show()

    val sv = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
    if (sv != null && originX > 0f && originY > 0f) {
        sv.setBackgroundResource(R.drawable.bg_sheet)
        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val startScale = 44f * resources.displayMetrics.density / screenW
        sv.pivotX = 0f
        sv.pivotY = sv.height.toFloat()
        sv.scaleX = startScale
        sv.scaleY = 0.4f
        sv.translationX = originX - screenW / 2f
        sv.alpha = 0.5f
        sv.post {
            if (MotionPrefs.reduceMotion(sv)) {
                sv.scaleX = 1f
                sv.scaleY = 1f
                sv.translationX = 0f
                sv.alpha = 1f
            } else {
                val a1 = SpringAnimation(sv, SpringAnimation.SCALE_X)
                a1.spring = MotionTokens.sheet()
                a1.animateToFinalPosition(1f)
                val a2 = SpringAnimation(sv, SpringAnimation.SCALE_Y)
                a2.spring = MotionTokens.sheet()
                a2.animateToFinalPosition(1f)
                val a3 = SpringAnimation(sv, SpringAnimation.TRANSLATION_X)
                a3.spring = MotionTokens.sheet()
                a3.animateToFinalPosition(0f)
                sv.animate().alpha(1f).setDuration(MotionTokens.FADE_MS).start()
            }
        }
    }

// 拉一次状态刷新摘要
threadRun { safeBridge {
val st = try { PythonBridge.status(this@MainActivity) } catch (e: Exception) { "{}" }
runOnUiThread { refreshSettingsSummaries(st) }
} }
}

// —— 设置摘要（异步填充）——
private var apiKeySummary = "未设置"
private var baseUrlSummary = "未设置"
private var modelSummary = "未设置"
private var turnsSummary = ""
private var statusSummary = ""
private var sheetRows = mutableMapOf<String, TextView>()

private fun refreshSettingsSummaries(json: String) {
try {
val o = JSONObject(json)
val keyOk = o.optBoolean("api_key_set", false)
apiKeySummary = if (keyOk) "已配置" else "未设置"
baseUrlSummary = o.optString("base_url", "").let { v -> if (v.isBlank()) "未设置" else try { android.net.Uri.parse(v).host ?: v } catch (e: Exception) { v } }
modelSummary = o.optString("model", "").ifBlank { "未设置" }
val t = o.optInt("turns", 0)
turnsSummary = if (t > 0) "$t 条" else ""
statusSummary = "v" + (o.opt("version")?.toString() ?: "?")
} catch (_: Exception) {}
sheetRows["key"]?.text = apiKeySummary
sheetRows["url"]?.text = baseUrlSummary
sheetRows["model"]?.text = modelSummary
sheetRows["turns"]?.text = turnsSummary
sheetRows["status"]?.text = statusSummary
}

private fun groupLabel(t: String): TextView = TextView(this).apply {
text = t
textSize = 11f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
setTypeface(null, android.graphics.Typeface.BOLD)
letterSpacing = 0.05f
setPadding(dp(22), dp(14), dp(22), dp(6))
}

private fun settingsRow(icon: Int, title: String, value: String, danger: Boolean = false, onClick: () -> Unit): LinearLayout {
val row = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER_VERTICAL
setBackgroundResource(R.drawable.bg_sheet_row)
isClickable = true; isFocusable = true
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
).apply { marginStart = dp(10); marginEnd = dp(10) }
setPadding(dp(12), 0, dp(12), 0)
}
val ic = ImageView(this).apply {
setImageResource(icon)
layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
}
if (danger) {
ic.setColorFilter(ContextCompat.getColor(this, R.color.danger))
}
val tx = TextView(this).apply {
text = title
textSize = 15f
setTextColor(ContextCompat.getColor(this@MainActivity,
if (danger) R.color.danger else R.color.text_primary))
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
marginStart = dp(14)
}
}
val vl = TextView(this).apply {
text = value
textSize = 13f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
maxLines = 1
ellipsize = android.text.TextUtils.TruncateAt.END
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
).apply { marginStart = dp(12) }
maxWidth = dp(150)
gravity = Gravity.END
}
row.addView(ic); row.addView(tx); row.addView(vl)
row.setOnClickListener { haptic(it); onClick() }
// 登记以便刷新
when (title) {
"API Key" -> sheetRows["key"] = vl
"API 地址" -> sheetRows["url"] = vl
"模型名称" -> sheetRows["model"] = vl
"清空会话" -> sheetRows["turns"] = vl
"关于" -> sheetRows["status"] = vl
"测试连接" -> sheetRows["test"] = vl
 "自动执行（跳过确认）" -> sheetRows["auto"] = vl
}
return row
}


// ── 统一 BottomSheet 输入弹窗（与主 UI 一致：圆角卡 + accent 按钮）──
private fun inputSheet(
title: String,
hint: String = "",
initial: String = "",
message: String = "",
saveLabel: String = "保存",
onSave: (String) -> Unit
) {
val sheet = BottomSheetDialog(this)
val root = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_sheet)
setPadding(0, dp(8), 0, dp(24))
}
root.addView(View(this).apply {
setBackgroundResource(R.drawable.bg_handle)
layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
gravity = Gravity.CENTER_HORIZONTAL
topMargin = dp(6); bottomMargin = dp(14)
}
})
root.addView(TextView(this).apply {
text = title
textSize = 18f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setTypeface(null, android.graphics.Typeface.BOLD)
setPadding(dp(22), 0, dp(22), if (message.isEmpty()) dp(14) else dp(4))
})
if (message.isNotEmpty()) {
root.addView(TextView(this).apply {
text = message
textSize = 13f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(dp(22), 0, dp(22), dp(14))
})
}
val input = EditText(this).apply {
this.hint = hint
setText(initial)
textSize = 15f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
setBackgroundResource(R.drawable.bg_input_card)
setPadding(dp(14), dp(12), dp(14), dp(12))
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { marginStart = dp(22); marginEnd = dp(22); bottomMargin = dp(18) }
}
root.addView(input)
val row = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.END
setPadding(dp(22), 0, dp(22), 0)
}
row.addView(TextView(this).apply {
text = "取消"
textSize = 14f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(dp(20), dp(10), dp(20), dp(10))
setBackgroundResource(R.drawable.bg_quick)
isClickable = true
setOnClickListener { HapticTokens.tap(it); sheet.dismiss() }
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.WRAP_CONTENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { marginEnd = dp(8) }
})
row.addView(TextView(this).apply {
text = saveLabel
textSize = 14f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
setTypeface(null, android.graphics.Typeface.BOLD)
setPadding(dp(24), dp(10), dp(24), dp(10))
setBackgroundResource(R.drawable.bg_quick)
isClickable = true
setOnClickListener {
HapticTokens.tap(it)
val v = input.text.toString()
sheet.dismiss()
onSave(v)
}
})
root.addView(row)
sheet.setContentView(root)
sheet.show()
input.requestFocus()
input.post {
val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
}
}

// ── 统一 BottomSheet 确认弹窗 ──
private fun confirmSheet(
title: String,
message: String,
confirmLabel: String = "确定",
danger: Boolean = false,
onConfirm: () -> Unit
) {
val sheet = BottomSheetDialog(this)
val root = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_sheet)
setPadding(0, dp(8), 0, dp(24))
}
root.addView(View(this).apply {
setBackgroundResource(R.drawable.bg_handle)
layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
gravity = Gravity.CENTER_HORIZONTAL
topMargin = dp(6); bottomMargin = dp(14)
}
})
root.addView(TextView(this).apply {
text = title
textSize = 18f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setTypeface(null, android.graphics.Typeface.BOLD)
setPadding(dp(22), 0, dp(22), dp(6))
})
root.addView(TextView(this).apply {
text = message
textSize = 14f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(dp(22), 0, dp(22), dp(18))
})
val row = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.END
setPadding(dp(22), 0, dp(22), 0)
}
row.addView(TextView(this).apply {
text = "取消"
textSize = 14f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(dp(20), dp(10), dp(20), dp(10))
setBackgroundResource(R.drawable.bg_quick)
isClickable = true
setOnClickListener { HapticTokens.tap(it); sheet.dismiss() }
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.WRAP_CONTENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { marginEnd = dp(8) }
})
row.addView(TextView(this).apply {
text = confirmLabel
textSize = 14f
setTextColor(ContextCompat.getColor(this@MainActivity,
if (danger) R.color.danger else R.color.accent))
setTypeface(null, android.graphics.Typeface.BOLD)
setPadding(dp(24), dp(10), dp(24), dp(10))
setBackgroundResource(R.drawable.bg_quick)
isClickable = true
setOnClickListener {
HapticTokens.tap(it)
sheet.dismiss()
onConfirm()
}
})
root.addView(row)
sheet.setContentView(root)
sheet.show()
}
private fun promptApiKey() {
inputSheet(
title = "DeepSeek API Key",
hint = "sk-...",
message = "加密存储在 EncryptedSharedPreferences（AES256），绝不上传"
) { key ->
val k = key.trim()
SecurePrefs.putApiKey(this@MainActivity, k)
HapticTokens.success(sendButton)
threadRun {
safeBridge {
PythonBridge.setApiKey(this@MainActivity, k)
runOnUiThread {
appendOutput("[设置] API Key 已保存" + (10).toChar())
toast("已保存")
val has = k.isNotEmpty()
apiKeySummary = if (has) "已配置" else "未设置"
sheetRows["key"]?.text = apiKeySummary
}
}
}
}
}

private fun promptBaseUrl() {
threadRun {
val cur = try {
val st = PythonBridge.status(this@MainActivity)
JSONObject(st).optString("base_url", "")
} catch (e: Exception) { "" }
runOnUiThread { showBaseUrlDialog(cur) }
}
}

private fun showBaseUrlDialog(current: String) {
inputSheet(
title = "API 地址（Base URL）",
hint = "api.deepseek.com",
initial = current,
message = "支持裸域名、带 /v1 或完整端点（自动补 /chat/completions）"
) { url ->
val u = url.trim()
HapticTokens.success(sendButton)
threadRun {
safeBridge {
val r = PythonBridge.setBaseUrl(this@MainActivity, u)
runOnUiThread {
appendOutput("[设置] API 地址 " + r + (10).toChar())
toast("已保存")
baseUrlSummary = if (u.isBlank()) "未设置" else try { android.net.Uri.parse(u).host ?: u } catch (e: Exception) { u }
sheetRows["url"]?.text = baseUrlSummary
}
}
}
}
}

private fun testConnection() {
toast("正在测试连接……")
threadRun { safeBridge {
val st = try { PythonBridge.status(this@MainActivity) } catch (e: Exception) { "{}" }
val url = try { JSONObject(st).optString("base_url", "") } catch (e: Exception) { "" }
val keySet = try { JSONObject(st).optBoolean("api_key_set", false) } catch (e: Exception) { false }
if (url.isBlank()) { runOnUiThread { toast("未配置 API 地址") }; return@safeBridge }
if (!keySet) { runOnUiThread { toast("未配置 API Key") }; return@safeBridge }
val result = try {
val u = java.net.URL(url)
val conn = (u.openConnection() as java.net.HttpURLConnection)
conn.requestMethod = "GET"
conn.connectTimeout = 8000
conn.readTimeout = 8000
val code = try { conn.responseCode } catch (e: Exception) { 0 }
var errBody = ""
try {
val es = if (code >= 400) conn.errorStream else conn.inputStream
if (es != null) {
val buf = ByteArray(1024)
val n = es.read(buf)
if (n > 0) errBody = String(buf, 0, n, Charsets.UTF_8)
es.close()
}
} catch (e: Exception) { }
conn.disconnect()
val isModelErr = errBody.contains("model", true) && (errBody.contains("not exist", true) || errBody.contains("not found", true) || errBody.contains("invalid", true))
if (code in 200..299) "✓ 已连通 · HTTP " + code
else if (isModelErr) "✗ 模型名可能无效，请检查「模型名称」"
else if (code == 401 || code == 403) "✗ Key 被拒 · HTTP " + code
else if (code == 404) "✗ 地址或路径错误 · HTTP 404"
else if (code > 0) "✗ HTTP " + code
else "✗ 连接失败（无响应）"
} catch (e: Exception) {
"✗ 连接失败：" + (e.message ?: e.javaClass.simpleName)
}
runOnUiThread {
toast(result)
sheetRows["test"]?.text = result
val okColor = ContextCompat.getColor(this@MainActivity, R.color.ok)
val dngColor = ContextCompat.getColor(this@MainActivity, R.color.danger)
sheetRows["test"]?.setTextColor(if (result.startsWith("✓")) okColor else dngColor)
}

} }
}

private fun promptModel() {
val saved = sp.getString(KEY_MODEL_NAME, "") ?: ""
threadRun {
val cur0 = if (saved.isNotBlank()) saved else try {
val st = PythonBridge.status(this@MainActivity)
JSONObject(st).optString("model", "")
} catch (e: Exception) { "" }
runOnUiThread { showModelPicker(cur0) }
}
}

private val presetModels = listOf(
"deepseek-chat",
"deepseek-reasoner",
"glm-4-flash",
"glm-4-plus",
"gpt-4o-mini",
"gpt-4o",
"claude-3-5-sonnet-20241022"
)

private fun showModelPicker(current: String) {
val sheet = BottomSheetDialog(this)
val root = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_sheet)
setPadding(0, dp(8), 0, dp(24))
}
root.addView(TextView(this).apply {
text = "选择模型"
textSize = 20f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setTypeface(null, android.graphics.Typeface.BOLD)
setPadding(dp(22), dp(8), dp(22), dp(4))
})
root.addView(TextView(this).apply {
text = "当前：" + (if (current.isBlank()) "（未设置）" else current)
textSize = 12f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(dp(22), 0, dp(22), dp(10))
})
for (m in presetModels) {
val row = TextView(this).apply {
text = (if (m == current) "✓ " else " ") + m
textSize = 15f
setTextColor(ContextCompat.getColor(this@MainActivity,
if (m == current) R.color.accent else R.color.text_primary))
setPadding(dp(22), dp(12), dp(22), dp(12))
isClickable = true
setBackgroundResource(R.drawable.bg_sheet_row)
}
row.setOnClickListener {
HapticTokens.tap(it)
applyModel(m)
sheet.dismiss()
}
root.addView(row)
}
val custom = TextView(this).apply {
text = "自定义…"
textSize = 15f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
setPadding(dp(22), dp(14), dp(22), dp(14))
isClickable = true
}
custom.setOnClickListener {
HapticTokens.tap(it)
sheet.dismiss()
showModelDialogCustom(current)
}
root.addView(custom)
sheet.setContentView(root)
sheet.show()
}

private fun applyModel(name: String) {
val n = name.trim()
HapticTokens.success(sendButton)
sp.edit().putString(KEY_MODEL_NAME, n).apply()
threadRun {
safeBridge {
val r = PythonBridge.setModel(this@MainActivity, n)
runOnUiThread {
appendOutput("[设置] 模型名称 " + r + (10).toChar())
toast("已切换模型：" + n)
refreshStatusQuiet()
}
}
}
}

private fun showModelDialogCustom(current: String) {
inputSheet(
title = "自定义模型",
hint = "服务商文档里的 model id",
initial = current,
message = "仅在列表里没有你的模型时使用；留空 = 恢复出厂默认"
) { name ->
val n = name.trim()
sp.edit().putString(KEY_MODEL_NAME, n).apply()
threadRun { safeBridge {
val r = PythonBridge.setModel(this@MainActivity, n)
runOnUiThread {
appendOutput("[设置] 模型名称 " + r + (10).toChar())
toast("已保存")
refreshStatusQuiet()
}
} }
}
}


private fun showModelDialog(current: String) {
inputSheet(
title = "模型名称",
hint = "如 deepseek-chat / deepseek-reasoner",
initial = current,
message = "留空 = 恢复出厂默认模型，下一轮对话立即生效"
) { name ->
val n = name.trim()
HapticTokens.success(sendButton)
sp.edit().putString(KEY_MODEL_NAME, n).apply()
threadRun {
safeBridge {
val r = PythonBridge.setModel(this@MainActivity, n)
runOnUiThread {
appendOutput("[设置] 模型名称 " + r + (10).toChar())
toast("已保存")
refreshStatusQuiet()
}
}
}
}
}

private fun confirmClear() {
confirmSheet(
title = "清空会话",
message = "将清空对话记忆与待办（未验收合同挂起）。确定？",
confirmLabel = "清空",
danger = true
) {
HapticTokens.warn(window.decorView)
threadRun {
safeBridge {
val r = PythonBridge.resetSession(this@MainActivity)
runOnUiThread {
appendOutput("[会话] " + r + (10).toChar())
chatContainer.removeAllViews()
emptyState.visibility = View.VISIBLE
}
}
}
}
}

private fun promptSavePlugin() {
inputSheet(
title = "把当前会话保存为插件",
hint = "插件名称（如 每日待办）",
message = "保存后可在「插件」页面查看源码 / 删除"
) { name ->
val n = name.trim()
HapticTokens.success(sendButton)
if (n.isEmpty()) {
toast("名称不能为空")
} else {
sendCommand("/save " + n)
}
}
}

// 发送键状态：空 → 灰；有内容 → accent 实心；生成中 → 停止按钮
private fun refreshSendButton() {
 if (busy) {
 sendIcon.setImageResource(R.drawable.ic_stop)
 sendButton.alpha = 1f
 sendButton.setBackgroundResource(R.drawable.bg_circle_send)
 return
 }
 val has = inputEdit.text?.isNotEmpty() == true || pendingFiles.isNotEmpty()
 sendButton.alpha = 1f
 sendButton.setBackgroundResource(if (has) R.drawable.bg_circle_send else R.drawable.bg_circle_send_disabled)
 sendIcon.setImageResource(R.drawable.ic_arrow_up)
}

private fun onSend() {
    if (busy) {
        // 生成中点击 = 请求停止
        stopRequested = true
        toast("正在停止……")
        threadRun {
            try { PythonBridge.stopStream(this@MainActivity) } catch (e: Exception) { }
        }
        return
    }
val text = inputEdit.text.toString().trim()
if (text.isEmpty() && pendingFiles.isEmpty()) return
inputEdit.setText("")
inputEdit.requestFocus()
val attachInfo = if (pendingFiles.isEmpty()) ""
else "\n[附件] " + pendingFiles.joinToString(" ")
val fullText = text + attachInfo
pendingFiles.clear()
refreshAttachRow()
val payload = if (fullText.startsWith("/")) fullText
else if (appMode == "plan") "/plan " + fullText
else fullText
appendOutput("你> " + text + "\n")
sendCommand(payload)
inputEdit.hint = "输入消息..."
}

// ============ 流式渲染 ============
private val cursorBlink = object : Runnable {
    override fun run() {
        cursorOn = !cursorOn
        streamingBubble?.let { renderStreamText(it) }
        uiHandler.postDelayed(this, 500)
    }
}

private val streamPoll = object : Runnable {
    override fun run() {
        if (!streamPolling) return
        var done = false
        try {
            val json = PythonBridge.pollStream(this@MainActivity)
            if (json.isNotBlank()) {
                val o = JSONObject(json)
                if (o.optBoolean("ok")) {
                    val arr = o.optJSONArray("chunks")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val c = arr.optJSONObject(i) ?: continue
                            when (c.optString("t")) {
                                "content" -> appendStream(c.optString("v"))
                                "step" -> onStepEvent(c.optString("v"))
 "ui" -> onUiEvent(c.optString("v"))
 "btn" -> onBtnEvent(c.optString("v"))
 "reason" -> appendReason(c.optString("v"))
                            }
                        }
                    }
                    done = o.optBoolean("done", false)
                }
            }
        } catch (e: Exception) { }
        if (done) { streamPolling = false; return }
        uiHandler.postDelayed(this, 60)
    }
}

private fun fmtMs(ms: Int): String =
    if (ms < 1000) ms.toString() + "ms" else String.format("%.1fs", ms / 1000.0)

private fun beginStepBarIfNeeded() {
if (stepBarBox != null) return
stepBarStartMs = System.currentTimeMillis()
stepBarFailed = false
val accent = ContextCompat.getColor(this@MainActivity, R.color.accent)
val accentSubtle = ContextCompat.getColor(this@MainActivity, R.color.accent_subtle)
val col = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_step_bar)
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(4); bottomMargin = dp(4) }
}
val bar = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER_VERTICAL
setPadding(dp(14), dp(12), dp(14), dp(12))
isClickable = true
setBackgroundColor(accentSubtle)
}
val marker = TextView(this).apply {
text = "▍"
textSize = 14f
setTextColor(accent)
setTypeface(null, android.graphics.Typeface.BOLD)
}
val label = TextView(this).apply {
text = "正在准备…"
textSize = 14f
setTextColor(accent)
setTypeface(null, android.graphics.Typeface.BOLD)
setPadding(dp(8), 0, 0, 0)
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}
val chev = TextView(this).apply {
text = "▶"
textSize = 11f
setTextColor(accent)
}
bar.addView(marker)
bar.addView(label)
bar.addView(chev)
bar.setOnClickListener {
HapticTokens.tap(it)
stepBarExpanded = !stepBarExpanded
stepBarSteps?.visibility = if (stepBarExpanded) View.VISIBLE else View.GONE
stepBarChev?.text = if (stepBarExpanded) "▼" else "▶"
}
val progTrack = FrameLayout(this).apply {
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(2))
setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.accent_line))
}
val progFill = View(this).apply {
layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
setBackgroundColor(accent)
}
progTrack.addView(progFill)
val steps = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(dp(14), dp(6), dp(14), dp(12))
visibility = View.GONE
}
col.addView(bar)
col.addView(progTrack)
col.addView(steps)
chatContainer.addView(col)
stepBarBox = col
stepBarHead = bar
stepBarLabel = label
stepBarChev = chev
stepBarSteps = steps
stepBarProgress = progTrack
stepBarProgressFill = progFill
stepBarExpanded = false
stepRows.clear()
hideEmpty()
}

private fun onStepEvent(json: String) {
val o = try { JSONObject(json) } catch (e: Exception) { return }
val idx = o.optInt("idx", 0)
val total = o.optInt("total", 0)
val label = o.optString("label", "step")
val status = o.optString("status", "run")
val ms = if (o.has("elapsed_ms")) o.optInt("elapsed_ms", -1) else -1
beginStepBarIfNeeded()
val steps = stepBarSteps ?: return
var row = stepRows[idx]
if (row == null) {
row = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER_VERTICAL
setPadding(0, dp(4), 0, dp(4))
}
val ico = TextView(this).apply {
text = "○"
textSize = 13f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
setPadding(dp(2), 0, dp(10), 0)
tag = "ico"
}
val cmd = TextView(this).apply {
text = label
textSize = 12.5f
typeface = android.graphics.Typeface.MONOSPACE
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
maxLines = 1
ellipsize = android.text.TextUtils.TruncateAt.END
tag = "cmd"
}
val tm = TextView(this).apply {
text = "-"
textSize = 11.5f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
tag = "tm"
}
row.addView(ico)
row.addView(cmd)
row.addView(tm)
steps.addView(row)
stepRows[idx] = row
}
val ico = row.findViewWithTag<TextView>("ico")
val tm = row.findViewWithTag<TextView>("tm")
val accent = ContextCompat.getColor(this@MainActivity, R.color.accent)
val okc = ContextCompat.getColor(this@MainActivity, R.color.ok)
val dng = ContextCompat.getColor(this@MainActivity, R.color.danger)
when (status) {
"run" -> {
ico?.text = "●"
ico?.setTextColor(accent)
tm?.text = "…"
}
"ok" -> {
ico?.text = "✓"
ico?.setTextColor(okc)
tm?.text = if (ms >= 0) fmtMs(ms) else "-"
}
"err" -> {
ico?.text = "✗"
ico?.setTextColor(dng)
tm?.text = if (ms >= 0) fmtMs(ms) else "-"
stepBarFailed = true
}
}
if (total > 0) {
val pct = ((idx.toFloat() / total) * 100).toInt().coerceIn(0, 100)
val fill = stepBarProgressFill
val track = stepBarProgress
if (fill != null && track != null) {
val applyW: () -> Unit = {
val w = track.width
if (w > 0) {
val lp = fill.layoutParams as FrameLayout.LayoutParams
lp.width = w * pct / 100
fill.layoutParams = lp
}
}
applyW()
track.post { applyW() }
}
}
if (status == "run") {
stepBarLabel?.text = "▍正在执行 " + idx + "/" + total + " · " + label
stepBarLabel?.setTextColor(accent)
} else if (idx == total) {
var okCount = 0
for (r in stepRows.values) {
if (r.findViewWithTag<TextView>("ico")?.text == "✓") okCount++
}
val secs = ((System.currentTimeMillis() - stepBarStartMs) / 1000).toInt()
val okAll = stepRows.isNotEmpty() && okCount == stepRows.size
if (okAll) {
stepBarLabel?.text = "✓ " + total + "/" + total + " · " + secs + "s"
stepBarLabel?.setTextColor(okc)
HapticTokens.tap(stepBarHead ?: chatContainer)
} else {
stepBarLabel?.text = "✗ " + total + "/" + total + " · 失败 · " + label
stepBarLabel?.setTextColor(dng)
HapticTokens.warn(stepBarHead ?: chatContainer)
}
stepBarProgress?.visibility = View.GONE
stepBarSteps?.visibility = View.GONE
stepBarChev?.text = "▶"
stepBarExpanded = false
}
}

private fun endStepBar() {
stepBarBox = null
stepBarHead = null
stepBarLabel = null
stepBarChev = null
stepBarSteps = null
stepBarProgress = null
stepBarProgressFill = null
stepRows.clear()
}

private fun renderStreamText(tv: TextView) {
    val _masked = maskSecret(streamingSb.toString())
    tv.text = if (cursorOn) _masked + "▌" else _masked
}

private fun beginStreaming() {
 streamingSb = StringBuilder()
 reasoningSb = StringBuilder()
 cursorOn = true
 hideEmpty()
 // 不预建空气泡，避免加载时白块；首次 chunk 到达再创建
 streamingBubble = null
 loadingDotsView = showLoadingDots()
 uiHandler.removeCallbacks(cursorBlink)
}

// 惰性创建流式气泡：appendStream 首次调用时执行
private fun ensureStreamBubble(): TextView {
 streamingBubble?.let { return it }
 val row = LinearLayout(this).apply {
 orientation = LinearLayout.HORIZONTAL
 gravity = Gravity.START
 layoutParams = LinearLayout.LayoutParams(
 LinearLayout.LayoutParams.MATCH_PARENT,
 LinearLayout.LayoutParams.WRAP_CONTENT
 ).apply { topMargin = dp(4); bottomMargin = dp(4) }
 }
 val bubble = TextView(this).apply {
 textSize = 15f
 setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
 setBackgroundResource(R.drawable.bg_bubble_ai)
 setPadding(dp(14), dp(10), dp(14), dp(10))
 maxWidth = dp(300)
 setTextIsSelectable(true)
 }
 row.addView(bubble)
 val wrapped = wrapWithTime(row, false)
 chatContainer.addView(wrapped)
 wrapped.post { bounceIn(wrapped) }
 streamingBubble = bubble
 uiHandler.postDelayed(cursorBlink, 500)
 return bubble
}

private fun appendReason(t: String) {
if (t.isEmpty()) return
try {
if (reasoningSb.isEmpty()) {
hideLoadingDots(loadingDotsView)
loadingDotsView = null
reasoningStartMs = System.currentTimeMillis()
ensureReasoningBox()
}
reasoningSb.append(t)
if (autoScrollEnabled) reasoningBody?.text = maskSecret(reasoningSb.toString())
smartScrollBottom()
} catch (e: Throwable) {
appendReasonError(e)
}
}

private fun ensureReasoningBox(): LinearLayout? {
reasoningBox?.let { return it }
return try {
val box = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_reasoning_box)
setPadding(dp(12), dp(8), dp(12), dp(8))
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(4); bottomMargin = dp(4) }
}
val header = TextView(this).apply {
text = "思考中"
textSize = 11f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(dp(2), dp(2), dp(2), dp(2))
isClickable = true
}
val body = TextView(this).apply {
text = ""
textSize = 13f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setPadding(dp(4), dp(6), dp(4), dp(2))
setTextIsSelectable(true)
visibility = View.VISIBLE
}
header.setOnClickListener {
HapticTokens.tap(it)
val expanded = body.visibility == View.VISIBLE
body.visibility = if (expanded) View.GONE else View.VISIBLE
header.text = if (expanded) "思考过程 (点开)" else "思考过程 (收起)"
}
box.addView(header)
box.addView(body)
chatContainer.addView(box)
box.post { try { bounceIn(box) } catch (x: Throwable) {} }
reasoningBox = box
reasoningHeader = header
reasoningBody = body
box
} catch (e: Throwable) {
appendReasonError(e)
null
}
}

private fun appendReasonError(e: Throwable) {
try {
val sw = java.io.StringWriter()
e.printStackTrace(java.io.PrintWriter(sw))
val msg = "[reason-err] " + e.javaClass.simpleName + ": " + (e.message ?: "")
android.util.Log.e("ZAgent", msg, e)
android.util.Log.e("ZAgent", sw.toString())
} catch (x: Throwable) {}
}


private fun appendStream(t: String) {
 if (t.isEmpty()) return
 if (streamingSb.isEmpty()) { hideLoadingDots(loadingDotsView); loadingDotsView = null }
 streamingSb.append(t)
 val b = ensureStreamBubble()
 renderStreamText(b)
 smartScrollBottom()
}

private fun endStreaming(): Boolean {
uiHandler.removeCallbacks(cursorBlink)
val had = streamingSb.isNotEmpty()
streamingBubble?.let { it.text = streamingSb.toString() }
streamingBubble = null
streamingSb = StringBuilder()
try {
reasoningBox?.let {
val secs = (System.currentTimeMillis() - reasoningStartMs).coerceAtLeast(0L) / 1000L
reasoningHeader?.text = "思考过程 · " + secs + "s"
reasoningBody?.visibility = View.GONE
}
if (reasoningBox == null && reasoningSb.isNotEmpty()) {
addReasoningBox(reasoningSb.toString())
}
} catch (e: Throwable) {
appendReasonError(e)
}
reasoningSb = StringBuilder()
reasoningBox = null
reasoningHeader = null
reasoningBody = null
reasoningStartMs = 0L
return had
}

private fun sendCommand(cmd: String) {
    if (busy) return
 try { if (!quickExpanded) quickBall.visibility = View.VISIBLE } catch (x: Throwable) {}
    busy = true
    stopRequested = false
    refreshSendButton()
    beginStreaming()
    streamPolling = true
    uiHandler.post(streamPoll)
    threadRun {
        safeBridge {
            val reply = PythonBridge.runAgent(this@MainActivity, cmd)
            runOnUiThread {
                streamPolling = false
                uiHandler.removeCallbacks(streamPoll)
                val had = endStreaming()
                hideLoadingDots(loadingDotsView)
                loadingDotsView = null
                val wasStopped = stopRequested
val looksLikeError = reply.contains("[API错误") || reply.contains("[网络错误") || reply.contains("[流读取中断]")
if (!had && !wasStopped && !looksLikeError) {
appendOutput(reply + "\n")
}
if (wasStopped && !had) appendOutput("[已停止]\n")
endStepBar()
busy = false
stopRequested = false
refreshSendButton()
val failed = wasStopped
|| reply.contains("[API错误")
|| reply.contains("[网络错误")
|| reply.contains("[流读取中断]")
if (failed) HapticTokens.warn(sendButton) else HapticTokens.success(sendButton)
if (!failed) sendButton.animateFinish()
if (failed) addErrorBar(cmd, extractErrorReason(reply, wasStopped), reply, classifyError(reply, wasStopped))

            }
        }
    }
}

private fun appendOutput(text: String) {
if (text.isEmpty()) return
var rest = text
while (true) {
val b = rest.indexOf(BEG)
if (b < 0) { renderPlain(rest); break }
val e = rest.indexOf(END, b)
if (e < 0) {
renderPlain(rest.substring(0, b))
addReasoningBox(rest.substring(b + BEG.length).trim())
break
}
renderPlain(rest.substring(0, b))
addReasoningBox(rest.substring(b + BEG.length, e).trim())
rest = rest.substring(e + END.length)
}
smartScrollBottom()
}

private fun renderPlain(text: String) {
if (text.isEmpty()) return
val lines = text.trim('\n').split('\n')
val buf = StringBuilder()
fun flushAi() {
if (buf.isNotEmpty()) { addAiBubble(buf.toString().trim()); buf.setLength(0) }
}
for (line in lines) {
when {
line.startsWith("你>") -> {
flushAi()
addUserBubble(line.removePrefix("你>").trim())
}
line.startsWith("[") -> {
flushAi()
addSystemLine(line)
}
isApiErrorLine(line) -> {
flushAi()
addLocalizedError(line)
}
else -> {
if (buf.isNotEmpty()) buf.append('\n')
buf.append(line)
}
}
}
flushAi()
}

// 识别裸英文报错行（api error / error / failed）
private fun isApiErrorLine(line: String): Boolean {
val t = line.trim()
if (t.isEmpty()) return false
val low = t.lowercase()
if (t.startsWith("api error") || t.startsWith("http ") || t.startsWith("error:")) return true
if (low.contains("authentication fails")) return true
if (low.contains("auth header format")) return true
if (low.contains("connection refused") || low.contains("timed out")) return true
return false
}

// 英文报错中文化：主文案 + 折叠原文
private fun addLocalizedError(raw: String) {
hideEmpty()
val low = raw.lowercase()
val cn: String
val tip: String
when {
low.contains("401") || low.contains("authentication fails") || low.contains("auth header") -> {
cn = "API Key 无效或未配置"
tip = "请在设置 → API Key 中填写正确的 Key（sk- 开头）"
}
low.contains("403") -> {
cn = "API Key 无权限"
tip = "请确认该 Key 已开通对应模型权限"
}
low.contains("429") -> { cn = "请求过于频繁"; tip = "稍等几秒后重试" }
low.contains("timeout") || low.contains("timed out") -> { cn = "网络超时"; tip = "请检查网络或 API 地址" }
low.contains("connection refused") || low.contains("failed to connect") -> { cn = "无法连接到服务器"; tip = "请检查网络或 API 地址" }
raw.contains("5") && (low.contains("500") || low.contains("502") || low.contains("503")) -> {
cn = "服务端错误"; tip = "稍后重试，或换用其它模型"
}
else -> { cn = "请求失败"; tip = "点下方按钮查看详情" }
}

val card = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_alert_card)
setPadding(dp(12), dp(10), dp(12), dp(10))
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(6); bottomMargin = dp(6) }
}
val danger = ContextCompat.getColor(this@MainActivity, R.color.danger)
val title = TextView(this).apply {
text = "⚠ " + cn
textSize = 14f
setTextColor(danger)
setTypeface(null, android.graphics.Typeface.BOLD)
}
val tipTv = TextView(this).apply {
text = tip
textSize = 12f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(0, dp(4), 0, 0)
}
val detail = TextView(this).apply {
text = maskSecret(raw.trim())
textSize = 11f
typeface = android.graphics.Typeface.MONOSPACE
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.log_text))
setPadding(dp(2), dp(6), dp(2), dp(2))
setTextIsSelectable(true)
visibility = View.GONE
}
val toggle = TextView(this).apply {
text = "查看详情"
textSize = 12f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
setPadding(0, dp(6), 0, 0)
isClickable = true
}
toggle.setOnClickListener {
HapticTokens.tap(it)
val expanded = detail.visibility == View.VISIBLE
detail.visibility = if (expanded) View.GONE else View.VISIBLE
toggle.text = if (expanded) "查看详情" else "收起详情"
}
card.addView(title); card.addView(tipTv); card.addView(toggle); card.addView(detail)
chatContainer.addView(card)
card.post { bounceIn(card) }
smartScrollBottom()
}

private fun wrapWithTime(bubbleRow: View, alignEnd: Boolean): LinearLayout {
 val col = LinearLayout(this).apply {
 orientation = LinearLayout.VERTICAL
 layoutParams = LinearLayout.LayoutParams(
 LinearLayout.LayoutParams.MATCH_PARENT,
 LinearLayout.LayoutParams.WRAP_CONTENT
 )
 gravity = if (alignEnd) Gravity.END else Gravity.START
 }
 // 时间戳节流：仅首条 / 间隔>=5分钟 / 发送方切换 时显示
 val now = System.currentTimeMillis()
 val showTime = lastBubbleTs == 0L
 || (now - lastBubbleTs) >= 5 * 60 * 1000L
 || lastBubbleIsUser != alignEnd
 if (showTime) {
 val time = TextView(this).apply {
 text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
 .format(java.util.Date(now))
 textSize = 11f
 setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
 setPadding(dp(6), dp(2), dp(6), 0)
 }
 col.addView(time)
 }
 col.addView(bubbleRow)
 lastBubbleTs = now
 lastBubbleIsUser = alignEnd
 return col
}

private fun addUserBubble(content: String) {
hideEmpty()
val row = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.END
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(4); bottomMargin = dp(4) }
}
val bubble = TextView(this).apply {
text = maskSecret(content)
textSize = 15f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setBackgroundResource(R.drawable.bg_bubble_user)
setPadding(dp(14), dp(10), dp(14), dp(10))
minWidth = dp(56)
maxWidth = dp(280)
setTextIsSelectable(true)
isClickable = true
isLongClickable = true
}
bubble.setOnLongClickListener { v ->
HapticTokens.longPress(v)
showBubbleMenu(v, row, content, true)
true
}
row.addView(bubble)
 val wrapped = wrapWithTime(row, true)
 chatContainer.addView(wrapped)
 wrapped.post { bounceIn(wrapped) }
}

private fun makeCodeBlock(parent: LinearLayout, code: String, lang: String = "") {
val card = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_step_body)
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(6); bottomMargin = dp(6) }
}
val head = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER_VERTICAL
setPadding(dp(12), dp(4), dp(4), dp(2))
}
val accent = ContextCompat.getColor(this@MainActivity, R.color.accent)
val sec = ContextCompat.getColor(this@MainActivity, R.color.text_secondary)
val ter = ContextCompat.getColor(this@MainActivity, R.color.text_tertiary)
val langLabel = TextView(this).apply {
text = guessLang(lang, code)
textSize = 11f
setTextColor(ter)
typeface = android.graphics.Typeface.MONOSPACE
letterSpacing = 0.06f
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}
val actions = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER_VERTICAL
}
fun mk(label: String, col: Int, onClick: () -> Unit): TextView {
return TextView(this).apply {
text = label
textSize = 12f
setTextColor(col)
setPadding(dp(8), dp(6), dp(8), dp(6))
isClickable = true
setOnClickListener { HapticTokens.tap(it); onClick() }
}
}
val runBtn = mk("▶ 运行", accent) {
val outCard = mkRunOutputCard(parent, "● 正在运行…")
val t0 = System.currentTimeMillis()
threadRun {
safeBridge {
val reply = try { PythonBridge.runAgent(this@MainActivity, "/zrun " + b64(code)) }
catch (e: Exception) { "[运行异常] " + (e.message ?: e.javaClass.simpleName) }
val el = (System.currentTimeMillis() - t0).toInt()
runOnUiThread { fillRunOutputCard(outCard, reply, el) }
}
}
}
val saveBtn = mk("＋ 插件", sec) {
inputSheet(
title = "存为插件",
hint = "插件名（默认 code+时间戳）",
message = "把这段代码存成插件，可在「插件」页面查看"
) { nmRaw ->
val nm = nmRaw.trim().ifBlank { "code" + System.currentTimeMillis() }
sendCommand("/zsave " + b64(nm) + " " + b64(code))
}
}
val copyBtn = mk("⧉ 复制", sec) { copyText(code) }
actions.addView(runBtn)
actions.addView(saveBtn)
actions.addView(copyBtn)
head.addView(langLabel)
head.addView(actions)
card.addView(head)
val pre = TextView(this).apply {
text = code
textSize = 12.5f
typeface = android.graphics.Typeface.MONOSPACE
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setPadding(dp(12), dp(2), dp(12), dp(12))
setTextIsSelectable(true)
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
)
}
card.addView(pre)
parent.addView(card)
}

private fun guessLang(lang: String, code: String): String {
val l = lang.lowercase().trim()
if (l.isNotEmpty()) return l.uppercase()
val c = code
return when {
c.contains("<html") || c.contains("<!DOCTYPE") || c.contains("<div") -> "HTML"
c.contains("function ") || c.contains("const ") || c.contains("=>") -> "JS"
c.contains("def ") || c.contains("import ") || c.startsWith("class ") -> "PY"
c.contains("SELECT ") || c.contains("INSERT INTO") -> "SQL"
c.contains("fun ") || c.contains("val ") -> "KT"
c.startsWith("{") || c.startsWith("[") -> "JSON"
else -> "CODE"
}
}

private fun mkRunOutputCard(parent: LinearLayout, initial: String): LinearLayout {
val card = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_step_body)
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(4); bottomMargin = dp(6) }
}
val accent = ContextCompat.getColor(this@MainActivity, R.color.accent)
val sec = ContextCompat.getColor(this@MainActivity, R.color.text_secondary)
val head = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER_VERTICAL
setPadding(dp(12), dp(10), dp(12), dp(10))
isClickable = true
}
val ico = TextView(this).apply {
text = "●"; textSize = 13f
setTextColor(accent); tag = "ico"
}
val label = TextView(this).apply {
text = initial; textSize = 13f
setTextColor(accent)
setPadding(dp(8), 0, 0, 0)
layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
tag = "label"
}
val meta = TextView(this).apply {
text = ""; textSize = 11.5f
setTextColor(sec); tag = "meta"
}
val chev = TextView(this).apply {
text = "▶"; textSize = 11f
setTextColor(sec); setPadding(dp(8), 0, 0, 0)
tag = "chev"
}
head.addView(ico)
head.addView(label)
head.addView(meta)
head.addView(chev)
val body = TextView(this).apply {
text = ""; textSize = 12f
typeface = android.graphics.Typeface.MONOSPACE
setTextColor(sec)
setPadding(dp(12), 0, dp(12), dp(12))
visibility = View.GONE
setTextIsSelectable(true)
tag = "body"
}
head.setOnClickListener {
HapticTokens.tap(it)
val ex = body.visibility == View.VISIBLE
body.visibility = if (ex) View.GONE else View.VISIBLE
chev.text = if (ex) "▶" else "▼"
}
card.addView(head)
card.addView(body)
parent.addView(card)
return card
}

private fun fillRunOutputCard(card: LinearLayout, output: String, elapsedMs: Int) {
val ico = card.findViewWithTag<TextView>("ico") ?: return
val label = card.findViewWithTag<TextView>("label")
val meta = card.findViewWithTag<TextView>("meta")
val body = card.findViewWithTag<TextView>("body")
val chev = card.findViewWithTag<TextView>("chev")
val okc = ContextCompat.getColor(this@MainActivity, R.color.ok)
val dng = ContextCompat.getColor(this@MainActivity, R.color.danger)
val m = Regex("\\[退出码 (\\d+)\\]").find(output)
val code = m?.groupValues?.getOrNull(1)?.toIntOrNull()
val isErr = output.contains("[运行异常]") || (code != null && code != 0)
val secs = elapsedMs / 1000.0
val metaTxt = String.format("%.2fs · 退出码 %s", secs, code?.toString() ?: "-")
if (isErr) {
ico.text = "✗"; ico.setTextColor(dng)
label?.text = "运行失败"
label?.setTextColor(dng)
HapticTokens.warn(card)
} else {
ico.text = "✓"; ico.setTextColor(okc)
label?.text = "运行成功"
label?.setTextColor(okc)
HapticTokens.success(card)
}
meta?.text = metaTxt
var bodyTxt = output
bodyTxt = bodyTxt.replace(Regex("^\\[运行\\] 开始执行…\\n"), "")
bodyTxt = bodyTxt.replace(Regex("\\[退出码 \\d+\\]\\n?"), "")
bodyTxt = bodyTxt.trim()
if (bodyTxt.isEmpty()) bodyTxt = "（无输出）"
body?.text = maskSecret(bodyTxt)
body?.visibility = View.VISIBLE
chev?.text = "▼"
}

private fun b64(s: String): String =
android.util.Base64.encodeToString(s.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

private fun addAiBubbleRich(content: String) {
val col = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
gravity = Gravity.START
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(4); bottomMargin = dp(4) }
}
    val open = "``" + "`"
    val close = "``" + "`"
var idx = 0
while (idx < content.length) {
val a = content.indexOf(open, idx)
if (a < 0) {
val tail = content.substring(idx).trim()
if (tail.isNotEmpty()) col.addView(makePlainAiBubble(col, tail))
break
}
val head = content.substring(idx, a).trim()
if (head.isNotEmpty()) col.addView(makePlainAiBubble(col, head))
var p = a + 3
while (p < content.length && content[p] != '\n' && content[p] != ' ') p++
while (p < content.length && content[p] != '\n') p++
if (p < content.length) p++
val b = content.indexOf(close, p)
if (b < 0) {
val rest = content.substring(a).trim()
if (rest.isNotEmpty()) col.addView(makePlainAiBubble(col, rest))
break
}
val langStart = a + 3
var langEnd = langStart
while (langEnd < content.length && content[langEnd] != '\n' && content[langEnd] != ' ') langEnd++
val langTag = content.substring(langStart, langEnd)
makeCodeBlock(col, content.substring(p, b), langTag)

idx = b + 3
}
if (col.childCount == 0) col.addView(makePlainAiBubble(col, content))
val wrapped = wrapWithTime(col, false)
chatContainer.addView(wrapped)
wrapped.post { bounceIn(wrapped) }
}

private fun makePlainAiBubble(parent: LinearLayout, text: String): TextView {
val bubble = TextView(this).apply {
this.text = maskSecret(text)
textSize = 15f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setBackgroundResource(R.drawable.bg_bubble_ai)
setPadding(dp(14), dp(10), dp(14), dp(10))
maxWidth = dp(300)
setTextIsSelectable(true)
isClickable = true
isLongClickable = true
}
bubble.setOnLongClickListener { v ->
HapticTokens.longPress(v)
showBubbleMenu(v, parent, text, false)
true
}
return bubble
}


private fun addAiBubble(content: String) {
    if (content.contains("``" + "`")) { addAiBubbleRich(content); return }
hideEmpty()
val row = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.START
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(4); bottomMargin = dp(4) }
}
val bubble = TextView(this).apply {
text = maskSecret(content)
textSize = 15f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setBackgroundResource(R.drawable.bg_bubble_ai)
setPadding(dp(14), dp(10), dp(14), dp(10))
maxWidth = dp(300)
setTextIsSelectable(true)
isClickable = true
isLongClickable = true
}
bubble.setOnLongClickListener { v ->
HapticTokens.longPress(v)
showBubbleMenu(v, row, content, false)
true
}
row.addView(bubble)
 val wrapped = wrapWithTime(row, false)
 chatContainer.addView(wrapped)
 wrapped.post { bounceIn(wrapped) }
}

private fun showBubbleMenu(anchor: View, row: View, content: String, isUser: Boolean) {
val popup = PopupMenu(this, anchor)
popup.menu.add(0, 1, 0, "复制")
if (isUser) popup.menu.add(0, 2, 1, "重新发送")
popup.menu.add(0, 3, 2, "删除")
popup.setOnMenuItemClickListener { item ->
when (item.itemId) {
1 -> { copyText(content); true }
2 -> {
appendOutput("你> " + content + "\n")
sendCommand(content)
true
}
3 -> {
val target = (row.parent as? View) ?: row
confirmSheet(
title = "删除消息",
message = "确定删除这条消息？",
confirmLabel = "删除",
danger = true
) {
chatContainer.removeView(target)
if (chatContainer.childCount <= 1) emptyState.visibility = View.VISIBLE
}
true
}
else -> false
}
}
popup.show()
}

// 失败/中断后追加行内重试按钮
// 从回复中提取错误原因
private fun extractErrorReason(reply: String, stopped: Boolean): String {
if (stopped) return "已手动停止"
if (reply.contains("[网络错误")) return "网络连接失败，请检查网络或 API 地址"
if (reply.contains("[API错误")) {
 val m = Regex("\\[API错误 (\\d+)\\]").find(reply)
val code = m?.groupValues?.getOrNull(1) ?: ""
val low = reply.lowercase()
if (low.contains("model") && (low.contains("not exist") || low.contains("not found") || low.contains("invalid"))) return "模型名称无效，请在设置里重新选择"
if (code == "401") return "API Key 无效或已过期"
if (code == "403") return "Key 权限不足或已被封禁"
if (code == "404") return "API 地址或路径错误（HTTP 404）"
if (code == "429") return "请求过于频繁，稍后重试"
if (code == "400") return "请求被拒（HTTP 400），检查模型名与参数"
if (code.startsWith("5")) return "服务端错误（" + code + "）"
return "API 错误（" + code + "）"
}
if (reply.contains("[流读取中断]")) return "连接中断"
return "生成失败"
}

private fun classifyError(reply: String, stopped: Boolean): String {
if (stopped) return "other"
if (reply.contains("[网络错误")) return "network"
if (reply.contains("[流读取中断]")) return "network"
if (reply.contains("[API错误")) {
 val m = Regex("\\[API错误 (\\d+)\\]").find(reply)
val code = m?.groupValues?.getOrNull(1) ?: ""
if (code == "401" || code == "403") return "auth"
if (code == "429") return "other"
if (code.startsWith("5")) return "server"
return "other"
}
return "other"
}


// 错误态：行内提示条（原因 + 去设置 + 重试）
private fun addErrorBar(cmd: String, reason: String, detail: String = "", category: String = "other") {
try { quickBall.visibility = View.GONE } catch (x: Throwable) {}
hideEmpty()
val card = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_reasoning_box)
setPadding(dp(14), dp(10), dp(14), dp(10))
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(4); bottomMargin = dp(8) }
}
val msgTv = TextView(this).apply {
text = maskSecret("⚠ " + reason)
textSize = 13f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.danger))
}
card.addView(msgTv)

val detailTv = TextView(this).apply {
val d = if (detail.length > 1000)
detail.substring(0, 1000) + "\n…（已截断，共 " + detail.length + " 字）"
else detail
text = maskSecret(d.ifBlank { "（无更多信息）" })
textSize = 12f
typeface = android.graphics.Typeface.MONOSPACE
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(dp(2), dp(8), dp(2), dp(2))
setTextIsSelectable(true)
visibility = View.GONE
}
card.addView(detailTv)

val btnRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.END
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(8) }
}
val detailBtn = TextView(this).apply {
text = "查看详情"
textSize = 13f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(dp(12), dp(6), dp(12), dp(6))
isClickable = true
setBackgroundResource(R.drawable.bg_quick)
}
detailBtn.setOnClickListener {
HapticTokens.tap(it)
val expanded = detailTv.visibility == View.VISIBLE
detailTv.visibility = if (expanded) View.GONE else View.VISIBLE
detailBtn.text = if (expanded) "查看详情" else "收起"
}
val retryBtn = TextView(this).apply {
text = "↻ 重试"
textSize = 13f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
setPadding(dp(12), dp(6), dp(12), dp(6))
isClickable = true
setBackgroundResource(R.drawable.bg_quick)
}
retryBtn.layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.WRAP_CONTENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { marginStart = dp(8) }
retryBtn.setOnClickListener {
HapticTokens.tap(it)
chatContainer.removeView(card)
appendOutput("你> " + cmd + "\n")
sendCommand(cmd)
}
btnRow.addView(detailBtn)
btnRow.addView(retryBtn)
card.addView(btnRow)

if (category == "auth" || category == "config") {
val setRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.END
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(4) }
}
val settingsBtn = TextView(this).apply {
text = "去设置 →"
textSize = 12f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
setPadding(dp(6), dp(2), dp(6), dp(2))
isClickable = true
}
settingsBtn.setOnClickListener {
HapticTokens.tap(it)
showSettingsTransform()
}
setRow.addView(settingsBtn)
card.addView(setRow)
}

chatContainer.addView(card)
card.animateErrorCard()
smartScrollBottom()
}



// 加载态：3 点跳动
private fun showLoadingDots(): TextView {
    val tv = TextView(this).apply {
        text = "●○○"
        textSize = 18f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setBackgroundResource(R.drawable.bg_bubble_ai)
    }
    val row = LinearLayout(this).apply {
        gravity = Gravity.START
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(4) }
    }
    row.addView(tv)
    tv.tag = row
    chatContainer.addView(row)
    val phases = arrayOf("●○○", "○●○", "○○●")
    var idx = 0
    val r = object : Runnable {
        override fun run() {
            if (tv.parent == null) return
            idx = (idx + 1) % 3
            tv.text = phases[idx]
            tv.postDelayed(this, 350)
        }
    }
    tv.thinkingPulseOnce()
tv.postDelayed(r, 350)
    tv.setTag(R.id.tag_press_scale, r)
    return tv
}

private fun hideLoadingDots(tv: TextView?) {
    tv ?: return
    val r = tv.getTag(R.id.tag_press_scale) as? Runnable
    if (r != null) tv.removeCallbacks(r)
    val row = tv.tag as? View
    if (row != null) chatContainer.removeView(row)
}

private fun addRetryRow(cmd: String) {
    val tv = TextView(this).apply {
        text = "↻ 重试"
        textSize = 13f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
        setPadding(dp(14), dp(6), dp(14), dp(6))
        isClickable = true
        setBackgroundResource(R.drawable.bg_quick)
    }
    val wrap = LinearLayout(this).apply {
        gravity = Gravity.START
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2); bottomMargin = dp(8) }
    }
    wrap.addView(tv)
    tv.setOnClickListener {
        HapticTokens.tap(it)
        chatContainer.removeView(wrap)
        appendOutput("你> " + cmd + "\n")
        sendCommand(cmd)
    }
    chatContainer.addView(wrap)
    smartScrollBottom()
}

private fun copyText(t: String) {
val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
cm.setPrimaryClip(ClipData.newPlainText("zagent", t))
toast("已复制")
}

private var logBox: LinearLayout? = null
private var logHead: TextView? = null

private fun addSystemLine(content: String) {
val raw = maskSecret(content)
val low = raw.lowercase()
val isAlert = raw.contains("异常") || raw.contains("错误") || raw.contains("失败") ||
raw.contains("[API") || raw.contains("[网络") || raw.contains("[流读取") ||
low.contains("error") || low.contains("fail")
val isWarn = !isAlert && (raw.contains("警告") || raw.contains("注意") || low.contains("warn"))
val isLog = raw.startsWith("[bootstrap]") || raw.startsWith("[boot_guard]") ||
raw.startsWith("[boot]") || raw.startsWith("[设置]") ||
raw.startsWith("[会话]") || raw.startsWith("[桥接")
when {
isAlert -> addAlertLine(raw, false)
isWarn -> addAlertLine(raw, true)
isLog -> addLogLine(raw)
else -> {
val pill = TextView(this).apply {
text = raw
textSize = 12f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
gravity = Gravity.CENTER
setPadding(dp(14), dp(6), dp(14), dp(6))
setBackgroundResource(R.drawable.bg_quick)
}
val row = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(4); bottomMargin = dp(4) }
}
row.addView(pill)
chatContainer.addView(row)
}
}
}

private fun addAlertLine(content: String, warnMode: Boolean) {
// 错误卡：浅红底，无粗边框，保留分级（图标+颜色深浅），文案精简
hideEmpty()
val row = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_error_soft)
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(6); bottomMargin = dp(6) }
setPadding(dp(14), dp(12), dp(14), dp(12))
}
val headRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.CENTER_VERTICAL
}
val tint = ContextCompat.getColor(this@MainActivity,
if (warnMode) R.color.warn else R.color.danger)
val icon = TextView(this).apply {
text = if (warnMode) "⚠" else "⛔"
textSize = 14f
setTextColor(tint)
setPadding(0, 0, dp(8), 0)
}
// 文案精简：只取第一句、去掉"严重/警告"这类前缀词
val short = content
.removePrefix("严重").removePrefix("警告")
.removePrefix("：").removePrefix(":").trim()
.let { if (it.length > 60) it.substring(0, 60) + "…" else it }
val tv = TextView(this).apply {
text = short
textSize = 13f
setTextColor(tint)
setTypeface(null, android.graphics.Typeface.BOLD)
setTextIsSelectable(true)
}
headRow.addView(icon); headRow.addView(tv)
row.addView(headRow)

// 首句短提示 + 去设置按钮（仅 auth/config 类）
val low = content.lowercase()
val isKeyIssue = low.contains("key") || low.contains("401") || low.contains("auth") ||
content.contains("密钥") || content.contains("未配置")
if (isKeyIssue) {
val btnRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.END
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(8) }
}
val settingsBtn = TextView(this).apply {
text = "去设置 →"
textSize = 12f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
setPadding(dp(12), dp(6), dp(12), dp(6))
isClickable = true
setBackgroundResource(R.drawable.bg_quick)
}
settingsBtn.setOnClickListener {
HapticTokens.tap(it)
showSettingsTransform()
}
btnRow.addView(settingsBtn)
row.addView(btnRow)
}
chatContainer.addView(row)
// 进场动效：淡入 + 从下浮起 6dp
if (!MotionPrefs.reduceMotion(row)) {
row.alpha = 0f
row.translationY = dp(6).toFloat()
row.animate().alpha(1f).translationY(0f).setDuration(200)
.setInterpolator(android.view.animation.DecelerateInterpolator()).start()
}
smartScrollBottom()
}

private fun addLogLine(content: String) {
 logBuffer.add(content)
 while (logBuffer.size > 500) logBuffer.removeAt(0)
}

private val logBuffer = mutableListOf<String>()

private fun showLogSheet() {
 val sheet = BottomSheetDialog(this)
 val root = LinearLayout(this).apply {
 orientation = LinearLayout.VERTICAL
 setBackgroundResource(R.drawable.bg_sheet)
 setPadding(0, dp(8), 0, dp(24))
 }
 root.addView(TextView(this).apply {
 text = "运行日志"
 textSize = 18f
 setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
 setTypeface(null, android.graphics.Typeface.BOLD)
 setPadding(dp(22), dp(6), dp(22), dp(10))
 })
 val body = TextView(this).apply {
 text = if (logBuffer.isEmpty()) "（暂无日志）" else logBuffer.joinToString(System.lineSeparator())
 textSize = 11f
 typeface = android.graphics.Typeface.MONOSPACE
 setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
 setPadding(dp(22), 0, dp(22), 0)
 setTextIsSelectable(true)
 }
 val sv = android.widget.ScrollView(this).apply {
 layoutParams = LinearLayout.LayoutParams(
 LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
 }
 sv.addView(body)
 val maxH = (resources.displayMetrics.heightPixels * 0.6f).toInt()
 sv.layoutParams = LinearLayout.LayoutParams(
 LinearLayout.LayoutParams.MATCH_PARENT, maxH)
 root.addView(sv)
 sheet.setContentView(root)
 sheet.show()
}


private fun addReasoningBox(reasoning: String) {
if (reasoning.isEmpty()) return
hideEmpty()
val box = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_reasoning_box)
setPadding(dp(12), dp(8), dp(12), dp(8))
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(4); bottomMargin = dp(4) }
}
val header = TextView(this).apply {
text = "▸ 思考过程（点击展开）"
textSize = 11f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(dp(2), dp(2), dp(2), dp(2))
isClickable = true
}
val body = TextView(this).apply {
text = reasoning
textSize = 13f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setPadding(dp(4), dp(6), dp(4), dp(2))
setTextIsSelectable(true)
visibility = View.GONE
}
header.setOnClickListener {
HapticTokens.tap(it)
val expanded = body.visibility == View.VISIBLE
body.visibility = if (expanded) View.GONE else View.VISIBLE
header.text = if (expanded) "▸ 思考过程（点击展开）" else "▾ 思考过程（点击收起）"
}
box.addView(header)
box.addView(body)
chatContainer.addView(box)
box.post { bounceIn(box) }
smartScrollBottom()
}

private var bubbleBatchStart = 0L
private var bubbleIndex = 0

private fun bounceIn(view: View) {
    // 批次判定：200ms 内视为同一批，序号递增触发 stagger
    val now = System.currentTimeMillis()
    if (now - bubbleBatchStart > 200L) {
        bubbleIndex = 0
        bubbleBatchStart = now
    }
    val delay = minOf(bubbleIndex * MotionTokens.STAGGER_MS, MotionTokens.STAGGER_CAP_MS)
    bubbleIndex++

    // 起始态
    view.cameraDistance = 8000f * resources.displayMetrics.density
    view.rotationX = 8f
    view.translationY = 14f * resources.displayMetrics.density
    view.scaleX = 0.94f
    view.scaleY = 0.94f
    view.alpha = 0f

    view.postDelayed({
        // 位移/缩放 spring（可打断）
        view.springY(0f, MotionTokens.appear())
        view.springScale(1f, MotionTokens.appear())
        // 倾斜 + 透明度 tween
        view.animate().rotationX(0f).setDuration(360)
            .setInterpolator(android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)).start()
        view.animate().alpha(1f).setDuration(MotionTokens.FADE_MS)
            .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)).start()
    }, delay)
}

private fun refreshThinkChip() {
 if (thinkOn) {
  chipThink2.setBackgroundResource(R.drawable.bg_chip_active)
  chipThink2.setTextColor(ContextCompat.getColor(this, R.color.accent))
  chipThink2.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_think_active, 0, 0, 0)
 } else {
  chipThink2.setBackgroundResource(R.drawable.bg_chip_ghost)
  chipThink2.setTextColor(ContextCompat.getColor(this, R.color.chip_text_inactive))
  chipThink2.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_think, 0, 0, 0)
 }
}

private fun setAppMode(m: String) {
if (appMode == m) return
appMode = m
refreshSegVisual()
}

private fun refreshSegVisual() {
 val planOn = appMode == "plan"
 if (planOn) {
  segPlan.setBackgroundResource(R.drawable.bg_chip_active)
  segPlan.setTextColor(ContextCompat.getColor(this, R.color.accent))
  segPlan.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_plan_active, 0, 0, 0)
  segBuild.setBackgroundResource(R.drawable.bg_chip_ghost)
  segBuild.setTextColor(ContextCompat.getColor(this, R.color.chip_text_inactive))
  segBuild.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_build, 0, 0, 0)
 } else {
  segPlan.setBackgroundResource(R.drawable.bg_chip_ghost)
  segPlan.setTextColor(ContextCompat.getColor(this, R.color.chip_text_inactive))
  segPlan.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_plan, 0, 0, 0)
  segBuild.setBackgroundResource(R.drawable.bg_chip_active)
  segBuild.setTextColor(ContextCompat.getColor(this, R.color.accent))
  segBuild.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_build_active, 0, 0, 0)
 }
}


private fun refreshAttachRow() {
attachRow.removeAllViews()
if (pendingFiles.isEmpty()) {
attachScroll.visibility = View.GONE
return
}
attachScroll.visibility = View.VISIBLE
for (p in pendingFiles) {
val f = java.io.File(p)
val name = f.name.substringAfter('_', f.name)
val chip = TextView(this).apply {
text = name + " ✕"
textSize = 11f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setBackgroundResource(R.drawable.bg_quick)
setPadding(dp(10), dp(5), dp(10), dp(5))
isClickable = true
tag = p
}
val lp = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.WRAP_CONTENT,
LinearLayout.LayoutParams.WRAP_CONTENT
)
lp.rightMargin = dp(6)
chip.layoutParams = lp
chip.setOnClickListener { v ->
HapticTokens.tap(v)
pendingFiles.remove(v.tag as String)
refreshAttachRow()
}
attachRow.addView(chip)
}
}

private fun handlePickedFile(uri: Uri) {
threadRun {
try {
var name = "file"
contentResolver.query(uri, null, null, null, null)?.use { cur ->
val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
if (idx >= 0 && cur.moveToFirst()) name = cur.getString(idx) ?: name
}
val dir = java.io.File(filesDir, "uploads")
if (!dir.exists()) dir.mkdirs()
val safe = name.replace(Regex("[^A-Za-z0-9.-]"), "")
val dest = java.io.File(dir, System.currentTimeMillis().toString() + "_" + safe)
contentResolver.openInputStream(uri)?.use { ins ->
dest.outputStream().use { outs -> ins.copyTo(outs) }
}
val size = dest.length()
runOnUiThread {
pendingFiles.add(dest.absolutePath)
refreshAttachRow()
toast("已附加: " + name + "（" + (size / 1024) + " KB）")
}
} catch (e: Exception) {
runOnUiThread { toast("读取文件失败: " + (e.message ?: "")) }
}
}
}

private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

private fun showStatus(json: String) {
// 顶栏只显示模型名（可点切换）；版本号/Key 状态收进设置页
val tv = statusText
try {
val o = JSONObject(json)
val keyOk = o.optBoolean("api_key_set", false)
val model = if (o.isNull("model")) "" else (o.optString("model", ""))
tv.text = if (model.isNotBlank()) "已就绪 · " + model else "未选择模型"
// Key 未配置时用 accent 提示，已配置用常规灰
val col = if (keyOk) R.color.text_secondary else R.color.warn
tv.setTextColor(ContextCompat.getColor(this@MainActivity, col))
// 长按复制全部状态（保留调试能力）
tv.setOnLongClickListener {
val sb = StringBuilder()
sb.append("v").append(o.opt("version")?.toString() ?: "?")
sb.append(" / Gen-").append(o.opt("gen")?.toString() ?: "?")
sb.append(" | ").append(if (keyOk) "Key 已配置" else "Key 未配置")
if (model.isNotBlank()) sb.append(" | ").append(model)
copyText(sb.toString())
true
}
} catch (e: Exception) {
tv.text = "状态不可用"
}
}

private fun refreshStatusQuiet() {
threadRun {
safeBridge {
val st = try { PythonBridge.status(this@MainActivity) } catch (e: Exception) { "{}" }
runOnUiThread { showStatus(st) }
}
}
}

private fun threadRun(block: () -> Unit) {
Thread { block() }.start()
}

private fun safeBridge(block: () -> Unit) {
try {
block()
} catch (e: Exception) {
runOnUiThread {
appendOutput("\n[桥接异常] " + (e.message ?: "") + "\n")
busy = false
sendButton.isClickable = true; sendButton.alpha = 1f
}
}
}

private fun toast(msg: String) {
Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

private fun requestNotifyPermission() {
if (Build.VERSION.SDK_INT >= 33) {
if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
!= PackageManager.PERMISSION_GRANTED) {
requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
}
}
}

// ── AI 交互卡片：解析 [ui] JSON，渲染与主 UI 同源的 Material 风卡片 ──
// P5 render [btn:label|/cmd]
private fun onBtnEvent(json: String) {
    val o = try { JSONObject(json) } catch (e: Exception) { return }
    val label = o.optString("label", "")
    val cmd = o.optString("cmd", "")
    if (label.isEmpty() || cmd.isEmpty()) return
    hideEmpty()
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(8) }
    }
    val btn = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_accent))
        setTypeface(null, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(10), dp(20), dp(10))
        setBackgroundResource(R.drawable.bg_circle_send)
        isClickable = true
        isFocusable = true
    }
    btn.setOnClickListener { v ->
        HapticTokens.tap(v)
        sendCommand(cmd)
    }
    wrap.addView(btn)
    chatContainer.addView(wrap)
    uiHandler.post { smartScrollBottom() }
}


private fun onUiEvent(json: String) {
val o = try { JSONObject(json) } catch (e: Exception) { return }
hideEmpty()
val card = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_step_body)
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(6); bottomMargin = dp(6) }
setPadding(dp(14), dp(12), dp(14), dp(12))
}
val title = o.optString("title", "")
if (title.isNotEmpty()) {
card.addView(TextView(this).apply {
text = title
textSize = 14f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setTypeface(null, android.graphics.Typeface.BOLD)
setPadding(0, 0, 0, dp(8))
})
}
val fields = LinkedHashMap<String, () -> Any>()
val arr = o.optJSONArray("components")
var submitAction = ""
var submitLabel = "提交"
var hasButton = false
if (arr != null) {
for (ci in 0 until arr.length()) {
val c = arr.optJSONObject(ci) ?: continue
val t = c.optString("type", "")
when (t) {
"text" -> {
val txt = c.optString("text", "")
if (txt.isNotEmpty()) {
card.addView(TextView(this).apply {
text = txt
textSize = 13f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(0, dp(2), 0, dp(6))
})
}
}
"input" -> {
val name = c.optString("name", "input_" + ci)
val label = c.optString("label", "")
if (label.isNotEmpty()) {
card.addView(TextView(this).apply {
text = label
textSize = 12f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(0, dp(4), 0, dp(4))
})
}
val et = EditText(this).apply {
hint = c.optString("placeholder", "")
setText(c.optString("value", ""))
textSize = 14f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
setBackgroundResource(R.drawable.bg_input_card)
setPadding(dp(12), dp(10), dp(12), dp(10))
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { bottomMargin = dp(8) }
}
card.addView(et)
fields[name] = { et.text.toString() }
}
"select" -> {
val name = c.optString("name", "select_" + ci)
val label = c.optString("label", "")
if (label.isNotEmpty()) {
card.addView(TextView(this).apply {
text = label
textSize = 12f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(0, dp(4), 0, dp(4))
})
}
val row = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { bottomMargin = dp(8) }
}
val opts = c.optJSONArray("options")
val selected = arrayOf("")
if (opts != null) {
for (oj in 0 until opts.length()) {
val op = opts.optJSONObject(oj) ?: continue
val v = op.optString("value", "")
val lab = op.optString("label", v)
val chip = TextView(this).apply {
text = lab
textSize = 13f
setPadding(dp(12), dp(8), dp(12), dp(8))
isClickable = true
setBackgroundResource(R.drawable.bg_quick)
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { bottomMargin = dp(4) }
}
chip.setOnClickListener {
HapticTokens.tap(it)
selected[0] = v
for (k in 0 until row.childCount) {
val ch = row.getChildAt(k) as? TextView ?: continue
val cv = (ch.tag as? String) ?: ""
if (cv == v) {
ch.setBackgroundResource(R.drawable.bg_mini_pill)
ch.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
ch.setTypeface(null, android.graphics.Typeface.BOLD)
} else {
ch.setBackgroundResource(R.drawable.bg_quick)
ch.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
ch.setTypeface(null, android.graphics.Typeface.NORMAL)
}
}
}
chip.tag = v
row.addView(chip)
}
}
card.addView(row)
fields[name] = { selected[0] }
}
"switch" -> {
val name = c.optString("name", "switch_" + ci)
val sw = android.widget.Switch(this).apply {
text = c.optString("label", name)
textSize = 13f
isChecked = c.optBoolean("value", false)
setPadding(0, dp(6), 0, dp(6))
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
}
card.addView(sw)
fields[name] = { sw.isChecked }
}
"button" -> {
submitLabel = c.optString("label", "提交")
submitAction = c.optString("action", "")
hasButton = true
}
}
}
}
if (hasButton || fields.isNotEmpty()) {
val btnRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
gravity = Gravity.END
setPadding(0, dp(6), 0, 0)
}
val btn = TextView(this).apply {
text = submitLabel
textSize = 14f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_accent))
setBackgroundResource(R.drawable.bg_circle_send)
setPadding(dp(22), dp(10), dp(22), dp(10))
isClickable = true
setTypeface(null, android.graphics.Typeface.BOLD)
}
btn.setOnClickListener { v ->
HapticTokens.success(v)
val payload = JSONObject()
if (submitAction.isNotEmpty()) payload.put("action", submitAction)
for ((k, getter) in fields) {
try {
payload.put(k, getter())
} catch (e: Exception) { }
}
v.isEnabled = false
v.alpha = 0.45f
val msg = payload.toString()
appendOutput("你> " + msg + "\n")
sendCommand(msg)
}
btnRow.addView(btn)
card.addView(btnRow)
}
chatContainer.addView(card)
smartScrollBottom()
card.post { bounceIn(card) }
}


// ══════ 快捷条：可收起/展开/自定义 ══════
data class QuickBtn(val id: String, val label: String, val action: String, val desc: String = "")

private lateinit var quickRow: LinearLayout
private lateinit var quickRowWrap: View
private lateinit var quickCollapse: View
private lateinit var quickEditBar: View
private lateinit var quickBarContainer: View
private lateinit var quickBall: View
private val quickShakeAnims = mutableListOf<android.animation.ObjectAnimator>()

private val allQuickCommands = listOf(
QuickBtn("idle", "自主", "cmd:/idle", "自主执行子任务队列"),
QuickBtn("think", "深思", "cmd:/think", "开启深度思考"),
QuickBtn("contract", "合同", "cmd:/contract", "查看验收合同"),
QuickBtn("versions", "版本", "cmd:/versions", "查看/回滚版本"),
QuickBtn("evolve", "进化", "cmd:/evolve", "自我改进（高风险）"),
QuickBtn("rollback", "回滚", "cmd:/rollback", "回滚到上一版本"),
QuickBtn("plan", "计划", "cmd:/plan", "仅拆解任务，不执行"),
QuickBtn("build", "构建", "cmd:/build", "执行代码任务"),
QuickBtn("auto", "自动", "cmd:/auto", "切换自动执行"),
QuickBtn("skill", "技能", "cmd:/skill list", "列出技能"),
QuickBtn("save", "存插件", "saveplugin", "把当前代码存为插件"),
QuickBtn("plugins", "插件", "plugin", "打开插件页"),
QuickBtn("clear", "清屏", "cmd:/clear", "清空对话"),
QuickBtn("restart", "重启", "cmd:/restart", "重启 Agent"),

QuickBtn("analyze", "分析", "analyze", "REA 分析器")
)

private fun defaultQuickButtons(): MutableList<QuickBtn> {
val ids = listOf("idle", "analyze", "contract", "versions", "plugins", "save", "clear")
val out = mutableListOf<QuickBtn>()
for (id in ids) {
val b = allQuickCommands.firstOrNull { it.id == id }
if (b != null) out.add(b)
}
return out
}

private fun loadQuickButtons(): MutableList<QuickBtn> {
val raw = sp.getString(KEY_QUICK_BUTTONS, "") ?: ""
if (raw.isEmpty()) return defaultQuickButtons()
return try {
val arr = org.json.JSONArray(raw)
val list = mutableListOf<QuickBtn>()
for (i in 0 until arr.length()) {
val o = arr.getJSONObject(i)
val id = o.optString("id", "")
if (id.isEmpty()) continue
val known = allQuickCommands.firstOrNull { it.id == id } ?: continue
list.add(known)
}
if (list.isEmpty()) defaultQuickButtons() else list
} catch (e: Exception) {
defaultQuickButtons()
}
}

private fun saveQuickButtons() {
try {
val arr = org.json.JSONArray()
for (b in quickButtons) {
val o = org.json.JSONObject()
o.put("id", b.id)
arr.put(o)
}
sp.edit().putString(KEY_QUICK_BUTTONS, arr.toString()).apply()
} catch (e: Exception) { }
}

private fun initQuickBar() {
quickRow = findViewById(R.id.quickRow)
quickRowWrap = findViewById(R.id.quickRowWrap)
quickCollapse = findViewById(R.id.quickCollapse)
quickEditBar = findViewById(R.id.quickEditBar)
quickBarContainer = findViewById(R.id.quickBarContainer)
quickBall = findViewById(R.id.quickBall)

quickButtons = loadQuickButtons()
rebuildQuickRow()

quickCollapse.setOnClickListener { haptic(it); collapseQuick() }
quickBall.setOnClickListener { haptic(it); if (quickExpanded) collapseQuick() else expandQuick() }
findViewById<View>(R.id.quickEditDone).setOnClickListener { haptic(it); exitQuickEdit() }
quickBarContainer.setOnClickListener { if (quickEditMode) exitQuickEdit() }
}

private fun rebuildQuickRow() {
quickRow.removeAllViews()
for ((idx, b) in quickButtons.withIndex()) {
quickRow.addView(makeQuickChip(b, idx))
}
if (quickEditMode) quickRow.addView(makePlusChip())
if (quickExpanded) { quickBarContainer.visibility = View.VISIBLE; quickRowWrap.visibility = View.VISIBLE; quickBall.visibility = View.GONE } else { quickBarContainer.visibility = View.VISIBLE; quickRowWrap.visibility = View.GONE; quickBall.visibility = View.VISIBLE }

}

private fun makeQuickChip(b: QuickBtn, idx: Int): View {
val wrapper = FrameLayout(this).apply {
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)
).apply { marginStart = dp(8) }
}
val tv = TextView(this).apply {
text = b.label
textSize = 14f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
gravity = Gravity.CENTER
setPadding(dp(14), 0, dp(14), 0)
setBackgroundResource(if (quickEditMode) R.drawable.bg_quick_chip_edit else R.drawable.bg_quick)
layoutParams = FrameLayout.LayoutParams(
FrameLayout.LayoutParams.WRAP_CONTENT, dp(40)
)
isClickable = true
isFocusable = true
}
tv.setOnClickListener {
if (quickEditMode) return@setOnClickListener
HapticTokens.tap(it)
dispatchQuick(b)
}
tv.setOnLongClickListener {
if (!quickEditMode && b.desc.isNotBlank()) {
HapticTokens.longPress(it)
toast(b.label + " · " + b.desc)
return@setOnLongClickListener true
}
if (quickEditMode) {
HapticTokens.longPress(it)
val data = ClipData.newPlainText("qi", idx.toString())
val shadow = android.view.View.DragShadowBuilder(tv)
tv.startDragAndDrop(data, shadow, wrapper, 0)
} else {
HapticTokens.longPress(it)
enterQuickEdit()
}
true
}
wrapper.addView(tv)

if (quickEditMode) {
val del = TextView(this).apply {
text = "−"
textSize = 12f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
gravity = Gravity.CENTER
setBackgroundResource(R.drawable.bg_quick_del_subtle)
layoutParams = FrameLayout.LayoutParams(dp(18), dp(18)).apply {
gravity = Gravity.TOP or Gravity.END
topMargin = -dp(4)
marginEnd = -dp(4)
}
isClickable = true
}
del.setOnClickListener {
HapticTokens.warn(it)
if (idx in quickButtons.indices) {
quickButtons.removeAt(idx)
saveQuickButtons()
rebuildQuickRow()
startShakeAll()
}
}
wrapper.addView(del)
}

wrapper.setOnDragListener { _, event ->
when (event.action) {
android.view.DragEvent.ACTION_DRAG_ENTERED -> wrapper.alpha = 0.5f
android.view.DragEvent.ACTION_DRAG_EXITED -> wrapper.alpha = 1f
android.view.DragEvent.ACTION_DROP -> {
wrapper.alpha = 1f
val from = event.clipData.getItemAt(0).text.toString().toIntOrNull() ?: -1
if (from >= 0 && from != idx && from in quickButtons.indices && idx in quickButtons.indices) {
val moved = quickButtons.removeAt(from)
quickButtons.add(idx, moved)
saveQuickButtons()
rebuildQuickRow()
startShakeAll()
}
}
}
true
}
return wrapper
}

private fun makePlusChip(): View {
val wrapper = FrameLayout(this).apply {
layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) }
}
val tv = TextView(this).apply {
text = "＋"
textSize = 16f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
gravity = Gravity.CENTER
setBackgroundResource(R.drawable.bg_quick_chip_edit)
layoutParams = FrameLayout.LayoutParams(dp(40), dp(40))
isClickable = true
}
tv.setOnClickListener {
HapticTokens.tap(it)
showAddQuickSheet()
}
wrapper.addView(tv)
return wrapper
}

private fun dispatchQuick(b: QuickBtn) {
when {
b.action == "analyze" -> showAnalyzePanel()
 b.action == "plugin" -> startActivity(Intent(this, PluginActivity::class.java))
b.action == "saveplugin" -> promptSavePlugin()
b.action.startsWith("cmd:") -> sendCommand(b.action.removePrefix("cmd:"))
}
}

private fun expandQuick() {
// 打断旧动画
quickRowWrap.animate().cancel()
for (i in 0 until quickRow.childCount) quickRow.getChildAt(i).animate().cancel()
quickExpanded = true
try { quickBall.visibility = View.GONE } catch (x: Throwable) {}
quickBarContainer.visibility = View.VISIBLE
quickRowWrap.visibility = View.VISIBLE
quickRowWrap.alpha = 1f

if (MotionPrefs.reduceMotion(quickRowWrap)) {
val lp0 = quickRowWrap.layoutParams
lp0.height = LinearLayout.LayoutParams.WRAP_CONTENT
quickRowWrap.layoutParams = lp0
quickRowWrap.translationY = 0f
for (i in 0 until quickRow.childCount) {
quickRow.getChildAt(i).alpha = 1f
quickRow.getChildAt(i).translationY = 0f
}
return
}

// 先量出目标高度（避免 visible 后一帧闪动）
val lp = quickRowWrap.layoutParams
lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
quickRowWrap.layoutParams = lp
val wSpec = View.MeasureSpec.makeMeasureSpec(
resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY)
val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
quickRowWrap.measure(wSpec, hSpec)
val targetH = quickRowWrap.measuredHeight
if (targetH <= 0) { lp.height = LinearLayout.LayoutParams.WRAP_CONTENT; quickRowWrap.layoutParams = lp; return }
// 先塌到 0，再撑开
lp.height = 0
quickRowWrap.layoutParams = lp

// 胶囊：从下往上 10dp 浮入，错开 35ms
for (i in 0 until quickRow.childCount) {
val v = quickRow.getChildAt(i)
v.animate().cancel()
v.alpha = 0f
v.translationY = dp(10).toFloat()
v.animate().alpha(1f).translationY(0f)
.setStartDelay(i * 35L)
.setDuration(280)
.setInterpolator(android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f))
.start()
}
// 高度 300ms 平滑撑开（输入框被顶上去）
val anim = android.animation.ValueAnimator.ofInt(0, targetH)
anim.duration = 300
anim.interpolator = android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)
anim.addUpdateListener { a ->
lp.height = a.animatedValue as Int
quickRowWrap.layoutParams = lp
}
anim.start()
}

private fun collapseQuick() {
quickRowWrap.animate().cancel()
quickExpanded = false
if (MotionPrefs.reduceMotion(quickRowWrap)) {
val lp0 = quickRowWrap.layoutParams
lp0.height = LinearLayout.LayoutParams.WRAP_CONTENT
quickRowWrap.layoutParams = lp0
quickRowWrap.visibility = View.GONE
quickBall.visibility = View.VISIBLE
return
}
// 第一步：胶囊快速消失（无错开，110ms）
for (i in 0 until quickRow.childCount) {
val v = quickRow.getChildAt(i)
v.animate().cancel()
v.animate().alpha(0f).translationY(dp(6).toFloat())
.setDuration(110)
.setInterpolator(android.view.animation.AccelerateInterpolator())
.start()
}
// 第二步：高度收回（60ms 延迟 + 150ms）
val lp = quickRowWrap.layoutParams
val startH = if (quickRowWrap.height > 0) quickRowWrap.height else lp.height
if (startH <= 0) { finishCollapse(); return }
val anim = android.animation.ValueAnimator.ofInt(startH, 0)
anim.duration = 150
anim.startDelay = 60
anim.interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.7f, 0.2f)
anim.addUpdateListener { a ->
lp.height = a.animatedValue as Int
quickRowWrap.layoutParams = lp
}
anim.addListener(object : android.animation.AnimatorListenerAdapter() {
override fun onAnimationEnd(animation: android.animation.Animator) {
if (quickExpanded) return
finishCollapse()
}
})
anim.start()
}

private fun finishCollapse() {
if (quickExpanded) return
val lp = quickRowWrap.layoutParams
lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
quickRowWrap.layoutParams = lp
quickRowWrap.alpha = 1f
for (i in 0 until quickRow.childCount) {
val v = quickRow.getChildAt(i)
v.alpha = 1f
v.translationY = 0f
}
quickRowWrap.visibility = View.GONE
quickBall.visibility = View.VISIBLE
}

private fun startShakeAll() {
stopShakeAll()
if (MotionPrefs.reduceMotion(quickRow)) return
for (i in 0 until quickRow.childCount) {
val v = quickRow.getChildAt(i)
val anim = android.animation.ObjectAnimator.ofFloat(v, "rotation", -1.5f, 1.5f).apply {
duration = 160
repeatCount = android.animation.ValueAnimator.INFINITE
repeatMode = android.animation.ValueAnimator.REVERSE
}
anim.start()
quickShakeAnims.add(anim)
}
}

private fun stopShakeAll() {
for (a in quickShakeAnims) a.cancel()
quickShakeAnims.clear()
for (i in 0 until quickRow.childCount) {
quickRow.getChildAt(i).rotation = 0f
}
}

private fun enterQuickEdit() {
if (quickEditMode) return
quickEditMode = true
quickEditBar.visibility = View.VISIBLE
rebuildQuickRow()
startShakeAll()
}

private fun exitQuickEdit() {
if (!quickEditMode) return
quickEditMode = false
stopShakeAll()
quickEditBar.visibility = View.GONE
saveQuickButtons()
rebuildQuickRow()
}

private fun showMoreSheet() {
    val sheet = BottomSheetDialog(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_sheet)
        setPadding(0, dp(8), 0, dp(24))
    }
    root.addView(View(this).apply {
        setBackgroundResource(R.drawable.bg_handle)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(6); bottomMargin = dp(14)
        }
    })
    root.addView(TextView(this).apply {
        text = "更多命令"
        textSize = 18f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(dp(22), 0, dp(22), dp(8))
    })
    val scroll = android.widget.ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(440)
        )
    }
    val listBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    for (c in allQuickCommands) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_sheet_row)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { marginStart = dp(10); marginEnd = dp(10) }
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
        }
        val tv = TextView(this).apply {
            text = c.label
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        }
        row.addView(tv)
        row.setOnClickListener {
            HapticTokens.tap(row)
            sheet.dismiss()
            dispatchQuick(c)
        }
        listBox.addView(row)
    }
    scroll.addView(listBox)
    root.addView(scroll)
    sheet.setContentView(root)
    sheet.show()
}

private fun showAddQuickSheet() {
    val sheet = BottomSheetDialog(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_sheet)
        setPadding(0, dp(8), 0, dp(24))
    }
    root.addView(View(this).apply {
        setBackgroundResource(R.drawable.bg_handle)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(6); bottomMargin = dp(14)
        }
    })
    root.addView(TextView(this).apply {
        text = "添加按钮"
        textSize = 18f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(dp(22), 0, dp(22), dp(8))
    })
    val checks = mutableMapOf<String, android.widget.CheckBox>()
    val scroll = android.widget.ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(360)
        )
    }
    val listBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    for (c in allQuickCommands) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_sheet_row)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { marginStart = dp(10); marginEnd = dp(10) }
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
        }
        val cb = android.widget.CheckBox(this).apply {
            isChecked = quickButtons.any { it.id == c.id }
            isClickable = false
            isFocusable = false
        }
        val tv = TextView(this).apply {
            text = c.label
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
        }
        row.addView(cb); row.addView(tv)
        row.setOnClickListener {
            HapticTokens.tap(row)
            cb.isChecked = !cb.isChecked
        }
        checks[c.id] = cb
        listBox.addView(row)
    }
    scroll.addView(listBox)
    root.addView(scroll)
    val save = TextView(this).apply {
        text = "确定"
        textSize = 15f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_accent))
        setBackgroundResource(R.drawable.bg_circle_send)
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(10), dp(22), dp(10))
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12); gravity = Gravity.END; marginEnd = dp(16) }
    }
    save.setOnClickListener {
        HapticTokens.success(it)
        val newList = mutableListOf<QuickBtn>()
        for (c in allQuickCommands) {
            if (checks[c.id]?.isChecked == true) newList.add(c)
        }
        quickButtons = if (newList.isEmpty()) defaultQuickButtons() else newList
        saveQuickButtons()
        rebuildQuickRow()
        if (quickEditMode) startShakeAll()
        sheet.dismiss()
    }
    root.addView(save)
    sheet.setContentView(root)
    sheet.show()
}


    // -- 外部插件目录授权（方案 A）--
    private fun ensureExternalAccess() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        if (Environment.isExternalStorageManager()) return
        if (sp.getBoolean("ext_asked", false)) return
        sp.edit().putBoolean("ext_asked", true).apply()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("启用插件外部目录")
            .setMessage("为了从 /sdcard/Download/zagent_plugins/ 热加载插件，需要「管理所有文件」权限。不授予也能用，只是插件走内置目录。")
            .setPositiveButton("去设置") { _, _ ->
                try {
                    startActivity(Intent("android.settings.MANAGE_ALL_FILES_ACCESS_SETTINGS",
                        android.net.Uri.parse("package:" + packageName)))
                } catch (e: Exception) { }
            }
            .setNegativeButton("以后再说", null)
            .show()
    }


companion object {
private const val KEY_MODEL_NAME = "model_name"
private const val KEY_LIGHT_THEME = "light_theme"
 private const val KEY_AUTO_EXEC = "auto_exec"
 private const val KEY_QUICK_BUTTONS = "quick_buttons_v1"
private const val KEY_DRAFT = "input_draft"
private const val KEY_THINK = "think_on"
private const val BEG = "===REASONING_BEGIN==="
private const val END = "===REASONING_END==="
}
private var analyzeTarget: String = ""
private val analyzeFilePicker = registerForActivityResult(
ActivityResultContracts.OpenDocument()
) { uri ->
if (uri != null) {
val p = uriToPath(uri)
if (p != null) {
analyzeTarget = p
toast("已选: " + p.substringAfterLast('/'))
showAnalyzePanel()
} else {
toast("无法解析路径")
}
}
}
private fun uriToPath(uri: android.net.Uri): String? {
return try {
if (uri.scheme == "file") uri.path
else {
val f = java.io.File(cacheDir, "analyze_tmp_" + System.currentTimeMillis())
contentResolver.openInputStream(uri)?.use { ins ->
f.outputStream().use { outs -> ins.copyTo(outs) }
}
f.absolutePath
}
} catch (e: Exception) { null }
}
private fun pickAnalyzeFile() {
analyzeFilePicker.launch(arrayOf("*/*"))
}
private fun pickInstalledApp() {
threadRun {
val pm = packageManager
val apps = pm.getInstalledApplications(0)
val items = mutableListOf<String>()
for (ai in apps) {
try {
val label = pm.getApplicationLabel(ai).toString()
val ap = pm.getApplicationInfo(ai.packageName, 0).sourceDir
if (ap != null && ap.endsWith(".apk")) {
items.add(label + "|" + ap)
}
} catch (e: Exception) { }
}
items.sort()
runOnUiThread { showAppPicker(items) }
}
}
private fun showAppPicker(items: MutableList<String>) {
val names = Array(items.size) { i -> items[i].substringBefore('|') }
android.app.AlertDialog.Builder(this)
.setTitle("选择应用")
.setItems(names) { _, i ->
val path = items[i].substringAfter('|')
analyzeTarget = path
toast("已选: " + names[i])
showAnalyzePanel()
}
.setNegativeButton("取消", null)
.show()
}
private fun showAnalyzePanel() {
val sheet = BottomSheetDialog(this)
val root = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_sheet)
setPadding(0, dp(8), 0, dp(18))
}
val handle = View(this).apply {
setBackgroundResource(R.drawable.bg_handle)
layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
gravity = Gravity.CENTER_HORIZONTAL
topMargin = dp(6); bottomMargin = dp(14)
}
}
root.addView(handle)
root.addView(TextView(this).apply {
text = "分析"
textSize = 22f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setTypeface(null, android.graphics.Typeface.BOLD)
setPadding(dp(22), 0, dp(22), dp(6))
})
val tgt = TextView(this).apply {
text = if (analyzeTarget.isEmpty()) "未选择目标" else analyzeTarget
textSize = 12f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
setPadding(dp(22), dp(2), dp(22), dp(10))
setTextIsSelectable(true)
}
root.addView(tgt)
root.addView(groupLabel("目标"))
root.addView(settingsRow(R.drawable.ic_plus, "选择文件", "扫描 APK/DEX/JS/PY 等") { pickAnalyzeFile() })
root.addView(settingsRow(R.drawable.ic_gear, "选择已装应用", "从系统应用列表选") { pickInstalledApp() })
root.addView(groupLabel("操作"))
root.addView(settingsRow(R.drawable.ic_arrow_up, "立即分析", "对当前目标跑一遍") {
if (analyzeTarget.isEmpty()) {
toast("先选目标")
} else {
sheet.dismiss()
runAnalyze(analyzeTarget)
}
})
root.addView(settingsRow(R.drawable.ic_refresh, "最近会话", "查看历史分析记录") {
sheet.dismiss()
showAnalyzeSessions()
})
root.addView(settingsRow(R.drawable.ic_info, "生成报告", "把最近一次分析导出为报告") {
sheet.dismiss()
runAnalyzeReport("")
})
sheet.setContentView(root)
sheet.show()
}

private fun runAnalyze(path: String) {
toast("分析中…")
hideEmpty()
val card = addAnalyzeCard(path, "running", null)
threadRun { safeBridge {
val res = PythonBridge.analyzeAction(this@MainActivity, "run", path)
runOnUiThread {
updateAnalyzeCard(card, path, res)
}
} }
}
private fun addAnalyzeCard(path: String, state: String, resJson: String?): LinearLayout {
val card = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_card)
setPadding(dp(14), dp(12), dp(14), dp(12))
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(6); bottomMargin = dp(6) }
}
val title = TextView(this).apply {
text = "分析 · " + path.substringAfterLast('/')
textSize = 14f
setTypeface(null, android.graphics.Typeface.BOLD)
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
}
val sub = TextView(this).apply {
text = "会话：准备中…"
textSize = 12f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(0, dp(4), 0, dp(6))
}
val steps = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
}
val actions = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setPadding(0, dp(6), 0, 0)
}
card.addView(title)
card.addView(sub)
card.addView(steps)
card.addView(actions)
card.tag = "card"
chatContainer.addView(card)
card.post { appearCardOnce(card) }
smartScrollBottom()
return card
}
private fun appearCardOnce(card: View) {
if (MotionPrefs.reduceMotion(card)) return
card.alpha = 0f
card.translationY = 14f * resources.displayMetrics.density
card.springY(0f, MotionTokens.appear())
card.animate().alpha(1f).setDuration(MotionTokens.FADE_MS).start()
}
private fun updateAnalyzeCard(card: LinearLayout, path: String, resJson: String) {
try {
val o = org.json.JSONObject(resJson)
val sub = card.getChildAt(1) as TextView
val steps = card.getChildAt(2) as LinearLayout
val actions = card.getChildAt(3) as LinearLayout
steps.removeAllViews()
actions.removeAllViews()
if (!o.optBoolean("ok")) {
sub.text = "失败：" + o.optString("err", "?")
HapticTokens.warn(card)
return
}
val sid = o.optString("sid", "")
sub.text = "会话：" + sid
val items = o.optJSONArray("items")
val n = if (items != null) items.length() else 0
for (i in 0 until n) {
val item = items.getJSONObject(i)
val row = TextView(this).apply {
text = " · " + item.optString("id") + " " + item.optString("type") + " (" + item.optString("backend") + ") " + item.optInt("content_len") + "B"
textSize = 12f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(0, dp(2), 0, dp(2))
}
row.isClickable = true
row.setOnClickListener {
showEvidenceDetail(sid, item.optString("id"))
}
steps.addView(row)
}
val btnRep = TextView(this).apply {
text = "查看报告"
textSize = 13f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
setPadding(dp(8), dp(6), dp(8), dp(6))
isClickable = true
setBackgroundResource(R.drawable.bg_quick)
}
btnRep.setOnClickListener { HapticTokens.tap(it); runAnalyzeReport(sid) }
val btnTrace = TextView(this).apply {
text = "证据"
textSize = 13f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(dp(8), dp(6), dp(8), dp(6))
isClickable = true
setBackgroundResource(R.drawable.bg_quick)
}
btnTrace.setOnClickListener { HapticTokens.tap(it); showAnalyzeSessions() }
actions.addView(btnRep)
actions.addView(btnTrace)
HapticTokens.success(card)
smartScrollBottom()
} catch (e: Exception) {
val sub = card.getChildAt(1) as TextView
sub.text = "解析失败：" + (e.message ?: "?")
}
}
private fun showEvidenceDetail(sid: String, eid: String) {
threadRun { safeBridge {
val arg = org.json.JSONObject().put("sid", sid).put("eid", eid).toString()
val res = PythonBridge.analyzeAction(this@MainActivity, "show", arg)
runOnUiThread { renderEvidenceDetail(sid, eid, res) }
} }
}
private fun renderEvidenceDetail(sid: String, eid: String, resJson: String) {
try {
val o = org.json.JSONObject(resJson)
if (!o.optBoolean("ok")) { toast("读取失败"); return }
val item = o.getJSONObject("item")
val sheet = BottomSheetDialog(this)
val root = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_sheet)
setPadding(dp(20), dp(16), dp(20), dp(20))
}
val title = TextView(this).apply {
text = eid + " · " + item.optString("type")
textSize = 17f
setTypeface(null, android.graphics.Typeface.BOLD)
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
}
root.addView(title)
val meta = TextView(this).apply {
text = "backend: " + item.optString("backend") + " confidence: " + item.optString("confidence")
textSize = 11f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
setPadding(0, dp(4), 0, dp(8))
}
root.addView(meta)
val body = TextView(this).apply {
text = item.optString("content", "")
textSize = 11f
typeface = android.graphics.Typeface.MONOSPACE
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setTextIsSelectable(true)
setPadding(0, dp(6), 0, dp(6))
}
val sv = android.widget.ScrollView(this).apply {
addView(body)
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(360))
}
root.addView(sv)
val copyBtn = TextView(this).apply {
text = "复制全文"
textSize = 13f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
setPadding(dp(8), dp(8), dp(8), dp(8))
isClickable = true
}
copyBtn.setOnClickListener { HapticTokens.tap(it); copyText(item.optString("content", "")); toast("已复制") }
root.addView(copyBtn)
sheet.setContentView(root)
sheet.show()
} catch (e: Exception) { toast("异常：" + (e.message ?: "?")) }
}
private fun runAnalyzeReport(sid: String) {
threadRun { safeBridge {
val res = PythonBridge.analyzeAction(this@MainActivity, "report", sid)
runOnUiThread { renderAnalyzeReport(sid, res) }
} }
}
private fun renderAnalyzeReport(sid: String, resJson: String) {
try {
val o = org.json.JSONObject(resJson)
if (!o.optBoolean("ok")) { toast("报告失败"); return }
val md = o.optString("md", "")
val sheet = BottomSheetDialog(this)
val root = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundResource(R.drawable.bg_sheet)
setPadding(dp(20), dp(16), dp(20), dp(20))
}
root.addView(TextView(this).apply {
text = "分析报告"
textSize = 20f
setTypeface(null, android.graphics.Typeface.BOLD)
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
})
val body = TextView(this).apply {
text = md
textSize = 11f
typeface = android.graphics.Typeface.MONOSPACE
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setTextIsSelectable(true)
}
val sv = android.widget.ScrollView(this).apply {
addView(body)
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(400))
}
root.addView(sv)
val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
val cp = TextView(this).apply {
text = "复制报告"
textSize = 13f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
setPadding(dp(8), dp(8), dp(8), dp(8))
isClickable = true
}
cp.setOnClickListener { HapticTokens.tap(it); copyText(md); toast("已复制") }
row.addView(cp)
root.addView(row)
sheet.setContentView(root)
sheet.show()
} catch (e: Exception) { toast("异常：" + (e.message ?: "?")) }
}
private fun showAnalyzeSessions() {
threadRun { safeBridge {
val res = PythonBridge.analyzeAction(this@MainActivity, "sessions", "")
runOnUiThread { renderAnalyzeSessions(res) }
} }
}
private fun renderAnalyzeSessions(resJson: String) {
try {
val o = org.json.JSONObject(resJson)
if (!o.optBoolean("ok")) { toast("会话列表失败"); return }
val arr = o.optJSONArray("sessions") ?: return
val labels = mutableListOf<String>()
val sids = mutableListOf<String>()
for (i in 0 until arr.length()) {
val s = arr.getJSONObject(i)
sids.add(s.optString("sid"))
labels.add(s.optString("sid") + " " + s.optString("type") + " " + s.optString("target").substringAfterLast('/'))
}
if (labels.isEmpty()) { toast("暂无会话"); return }
android.app.AlertDialog.Builder(this)
.setTitle("历史会话")
.setItems(labels.toTypedArray()) { _, i ->
showAnalyzeSessionDetail(sids[i])
}
.setNegativeButton("取消", null)
.show()
} catch (e: Exception) { toast("异常") }
}
private fun showAnalyzeSessionDetail(sid: String) {
threadRun { safeBridge {
val res = PythonBridge.analyzeAction(this@MainActivity, "evidence", sid)
runOnUiThread { renderAnalyzeEvidenceList(sid, res) }
} }
}
private fun renderAnalyzeEvidenceList(sid: String, resJson: String) {
try {
val o = org.json.JSONObject(resJson)
if (!o.optBoolean("ok")) { toast("证据列表失败"); return }
val arr = o.optJSONArray("items") ?: return
val labels = mutableListOf<String>()
val eids = mutableListOf<String>()
for (i in 0 until arr.length()) {
val it = arr.getJSONObject(i)
eids.add(it.optString("id"))
labels.add(it.optString("id") + " " + it.optString("type") + " " + it.optInt("content_len") + "B")
}
android.app.AlertDialog.Builder(this)
.setTitle(sid)
.setItems(labels.toTypedArray()) { _, i ->
showEvidenceDetail(sid, eids[i])
}
.setPositiveButton("生成报告") { _, _ -> runAnalyzeReport(sid) }
.setNegativeButton("关闭", null)
.show()
} catch (e: Exception) { toast("异常") }
}

}