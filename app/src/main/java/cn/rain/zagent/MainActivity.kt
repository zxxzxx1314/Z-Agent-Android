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
private lateinit var autoSwitch: android.widget.Switch
private lateinit var topBar: LinearLayout
private lateinit var themeButton: View
 private lateinit var themeIcon: ImageView
 private lateinit var autoLabel: TextView
private lateinit var inputBar: LinearLayout
private lateinit var segContainer: FrameLayout
private lateinit var segThumb: View
private lateinit var segPlan: TextView
private lateinit var segBuild: TextView
private lateinit var btnPlus: FrameLayout
private lateinit var chipThink2: TextView
private lateinit var chipSearch: TextView
private lateinit var attachScroll: HorizontalScrollView
private lateinit var attachRow: LinearLayout
 private lateinit var scrollBottomBtn: FrameLayout

private var busy = false
private var reportedStartup = false
private var stopRequested = false

// 流式渲染状态
private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

private val placeholderHints = arrayOf(
"/help 看看能做什么", "解释这个文件", "重构这个函数",
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
autoSwitch = findViewById(R.id.autoSwitch)
topBar = findViewById(R.id.topBar)
themeButton = findViewById(R.id.themeButton)
 themeIcon = findViewById(R.id.themeIcon)
 autoLabel = findViewById(R.id.autoLabel)
inputBar = findViewById(R.id.inputBar)
segContainer = findViewById(R.id.segContainer)
segThumb = findViewById(R.id.segThumb)
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
scrollBottomBtn.visibility = if (atBottom) View.GONE else View.VISIBLE
}
scrollBottomBtn.setOnClickListener {
haptic(it)
outputScroll.post {
 outputScroll.fullScroll(View.FOCUS_DOWN)
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
inputEdit.setText(sp.getString(KEY_DRAFT, "") ?: "")
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
findViewById<View>(R.id.btnClear).setOnClickListener { haptic(it); sendCommand("/clear") }
findViewById<View>(R.id.btnContract).setOnClickListener { haptic(it); sendCommand("/contract") }
findViewById<View>(R.id.btnHelp).setOnClickListener { haptic(it); sendCommand("/help") }
findViewById<View>(R.id.btnIdle).setOnClickListener { haptic(it); sendCommand("/idle") }
findViewById<View>(R.id.btnVersions).setOnClickListener { haptic(it); sendCommand("/versions") }
findViewById<View>(R.id.btnPlugins).setOnClickListener {
haptic(it); startActivity(Intent(this, PluginActivity::class.java))
}
findViewById<View>(R.id.btnSavePlugin).setOnClickListener { haptic(it); promptSavePlugin() }


themeButton.setOnClickListener { haptic(it); toggleTheme() }

 autoSwitch.setOnCheckedChangeListener { v, checked ->
 if (checked) HapticTokens.warn(v) else HapticTokens.tap(v)
 refreshAutoSwitch(checked)
threadRun { safeBridge { try { PythonBridge.setAuto(this@MainActivity, checked) } catch (e: Exception) { } } }
 }
 refreshAutoSwitch(autoSwitch.isChecked)


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
}
chipSearch.visibility = View.GONE
refreshThinkChip()

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

segContainer.post { layoutSegThumb(false) }

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

private fun hideEmpty() {
if (emptyState.visibility != View.GONE) emptyState.visibility = View.GONE
}

private fun applyTopInset() {
    ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
        v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
        insets
    }
    val root = findViewById<View>(R.id.rootView)

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

    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        applyImePadding(insets)
        insets
    }
    ViewCompat.requestApplyInsets(topBar)
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
themeIcon.setImageResource(if (light) R.drawable.ic_moon else R.drawable.ic_sun)
}

private fun toggleTheme() {
val light = sp.getBoolean(KEY_LIGHT_THEME, true)
sp.edit().putBoolean(KEY_LIGHT_THEME, !light).apply()
AppCompatDelegate.setDefaultNightMode(
if (!light) AppCompatDelegate.MODE_NIGHT_NO
else AppCompatDelegate.MODE_NIGHT_YES)
themeIcon.setImageResource(if (!light) R.drawable.ic_moon else R.drawable.ic_sun)
}

private fun refreshAutoSwitch(on: Boolean) {
val accent = ContextCompat.getColor(this, R.color.accent)
val off = ContextCompat.getColor(this, R.color.text_tertiary)
val white = ContextCompat.getColor(this, R.color.bg_elevated)
autoSwitch.thumbTintList = android.content.res.ColorStateList.valueOf(white)
autoSwitch.trackTintList = android.content.res.ColorStateList.valueOf(if (on) accent else off)
autoLabel.setTextColor(if (on) accent else off)
autoLabel.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
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
root.addView(settingsRow(R.drawable.ic_refresh, "测试连接", "检查 API 地址与 Key") { testConnection() })

// 分组：会话
root.addView(groupLabel("会话"))
root.addView(settingsRow(R.drawable.ic_trash, "清空会话", turnsSummary, danger = true) { confirmClear() })
root.addView(settingsRow(R.drawable.ic_info, "查看状态", statusSummary) {
threadRun { safeBridge {
val st = try { PythonBridge.status(this@MainActivity) } catch (e: Exception) { "{}" }
runOnUiThread { showStatus(st) }
} }
})

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
baseUrlSummary = o.optString("base_url", "").ifBlank { "未设置" }
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
"查看状态" -> sheetRows["status"] = vl
"测试连接" -> sheetRows["test"] = vl
}
return row
}

private fun promptApiKey() {
val input = EditText(this).apply { hint = "sk-..." }
AlertDialog.Builder(this)
.setTitle("DeepSeek API Key")
.setView(input)
.setPositiveButton("保存") { _, _ ->
val key = input.text.toString().trim()
SecurePrefs.putApiKey(this@MainActivity, key)
HapticTokens.success(sendButton)
threadRun {
safeBridge {
PythonBridge.setApiKey(this@MainActivity, key)
runOnUiThread {
appendOutput("[设置] API Key 已保存\n")
toast("已保存")
}
}
}
}
.setNegativeButton("取消", null)
.show()
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
HapticTokens.success(sendButton)
threadRun {
safeBridge {
val r = PythonBridge.setBaseUrl(this@MainActivity, url)
runOnUiThread {
appendOutput("[设置] API 地址 " + r + "\n")
toast("已保存")
}
}
}
}
.setNegativeButton("取消", null)
.show()
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
conn.disconnect()
if (code in 200..299) "✓ 已连通 · HTTP " + code
else if (code == 401 || code == 403) "✓ 已连通 · Key 被拒 · HTTP " + code
else if (code > 0) "✓ 已连通 · HTTP " + code
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
if (saved.isNotBlank()) {
showModelDialog(saved)
} else {
threadRun {
val cur = try {
val st = PythonBridge.status(this@MainActivity)
JSONObject(st).optString("model", "")
} catch (e: Exception) { "" }
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
HapticTokens.success(sendButton)
sp.edit().putString(KEY_MODEL_NAME, name).apply()
threadRun {
safeBridge {
val r = PythonBridge.setModel(this@MainActivity, name)
runOnUiThread {
appendOutput("[设置] 模型名称 " + r + "\n")
toast("已保存")
refreshStatusQuiet()
}
}
}
}
.setNegativeButton("取消", null)
.show()
}

private fun confirmClear() {
AlertDialog.Builder(this)
.setTitle("清空会话")
.setMessage("将清空对话记忆与待办（未验收合同挂起）。确定？")
.setPositiveButton("确定") { _, _ ->
HapticTokens.warn(window.decorView)
threadRun {
safeBridge {
val r = PythonBridge.resetSession(this@MainActivity)
runOnUiThread {
appendOutput("[会话] " + r + "\n")
chatContainer.removeAllViews()
emptyState.visibility = View.VISIBLE
}
}
}
}
.setNegativeButton("取消", null)
.show()
}

private fun promptSavePlugin() {
val input = EditText(this).apply { hint = "插件名称（如 每日待办）" }
AlertDialog.Builder(this)
.setTitle("把当前会话保存为插件")
.setView(input)
.setPositiveButton("保存") { _, _ ->
val name = input.text.toString().trim()
HapticTokens.success(sendButton)
if (name.isEmpty()) toast("名称不能为空")
else sendCommand("/save " + name)
}
.setNegativeButton("取消", null)
.show()
}

// 发送键状态：空 → 灰；有内容 → accent 实心；生成中 → 停止按钮
private fun refreshSendButton() {
    if (busy) {
        sendIcon.setImageResource(R.drawable.ic_stop)
        sendButton.alpha = 1f
        return
    }
    val has = inputEdit.text?.isNotEmpty() == true || pendingFiles.isNotEmpty()
    sendButton.alpha = if (has) 1f else 0.4f
    sendIcon.setImageResource(R.drawable.ic_send_arrow)
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
else if (thinkOn) "/think " + fullText
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
    cursorOn = true
    hideEmpty()
    loadingDotsView = showLoadingDots()
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
    uiHandler.removeCallbacks(cursorBlink)
    uiHandler.postDelayed(cursorBlink, 500)
}

private fun appendStream(t: String) {
    if (t.isEmpty()) return
    if (streamingSb.isEmpty()) { hideLoadingDots(loadingDotsView); loadingDotsView = null }
    streamingSb.append(t)
    streamingBubble?.let { renderStreamText(it) }
    outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
}

private fun endStreaming(): Boolean {
    uiHandler.removeCallbacks(cursorBlink)
    val had = streamingSb.isNotEmpty()
    streamingBubble?.let { it.text = streamingSb.toString() }
    streamingBubble = null
    streamingSb = StringBuilder()
    return had
}

private fun sendCommand(cmd: String) {
    if (busy) return
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
                if (!had) {
                    val fb = if (wasStopped) "[已停止]\n" else reply + "\n"
                    appendOutput(fb)
                }
                endStepBar()
                busy = false
                stopRequested = false
                refreshSendButton()
                val failed = wasStopped
                        || reply.contains("[API错误")
                        || reply.contains("[网络错误")
                        || reply.contains("[流读取中断]")
        if (failed) HapticTokens.warn(sendButton) else HapticTokens.success(sendButton)
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
outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
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
else -> {
if (buf.isNotEmpty()) buf.append('\n')
buf.append(line)
}
}
}
flushAi()
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
val time = TextView(this).apply {
text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
.format(java.util.Date())
textSize = 11f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
setPadding(dp(6), dp(2), dp(6), 0)
}
col.addView(time)
col.addView(bubbleRow)
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
val input = EditText(this).apply { hint = "插件名" }
AlertDialog.Builder(this)
.setTitle("存为插件")
.setView(input)
.setPositiveButton("保存") { , _ ->
val nm = input.text.toString().trim().ifBlank { "code" + System.currentTimeMillis() }
sendCommand("/zsave " + b64(nm) + " " + b64(code))
}
.setNegativeButton("取消", null)
.show()
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
val m = Regex("\[退出码 (\d+)\]").find(output)
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
bodyTxt = bodyTxt.replace(Regex("^\[运行\] 开始执行…\n"), "")
bodyTxt = bodyTxt.replace(Regex("\[退出码 \d+\]\n?"), "")
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
    AlertDialog.Builder(this)
        .setTitle("删除消息")
        .setMessage("确定删除这条消息？")
        .setPositiveButton("删除") { _, _ ->
            chatContainer.removeView(target)
            if (chatContainer.childCount <= 1) emptyState.visibility = View.VISIBLE
        }
        .setNegativeButton("取消", null)
        .show()
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
        if (code == "401") return "API Key 无效或已过期"
        if (code == "429") return "请求过于频繁，稍后重试"
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
text = "看详情"
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
detailBtn.text = if (expanded) "看详情" else "收起"
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
outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
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
    outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
}

private fun copyText(t: String) {
val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
cm.setPrimaryClip(ClipData.newPlainText("zagent", t))
toast("已复制")
}

private fun addSystemLine(content: String) {
val tv = TextView(this).apply {
text = maskSecret(content)
textSize = 13f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
gravity = Gravity.CENTER
setPadding(dp(12), dp(4), dp(12), dp(4))
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
)
}
chatContainer.addView(tv)
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
val color = ContextCompat.getColor(this,
if (thinkOn) R.color.accent else R.color.text_secondary)
chipThink2.setTextColor(color)
chipThink2.text = if (thinkOn) "✻ 深度思考 ✓" else "✻ 深度思考"
}

private fun setAppMode(m: String) {
if (appMode == m) return
appMode = m
refreshSegVisual()
}

private fun refreshSegVisual() {
val planOn = appMode == "plan"
segPlan.setTextColor(ContextCompat.getColor(this, if (planOn) R.color.accent else R.color.text_secondary))
segBuild.setTextColor(ContextCompat.getColor(this, if (planOn) R.color.text_secondary else R.color.accent))
layoutSegThumb(true)
}

private fun layoutSegThumb(animate: Boolean) {
val target = if (appMode == "plan") segPlan else segBuild
if (target.width == 0) {
segContainer.post { layoutSegThumb(false) }
return
}
val targetX = segContainer.paddingLeft + target.left.toFloat()
val targetW = target.width
val lp = segThumb.layoutParams
if (lp.width != targetW) { lp.width = targetW; segThumb.layoutParams = lp }
if (!animate) { segThumb.translationX = targetX; return }
    // 滑块位移 spring（可打断，连续点击从当前速度继续）
    segThumb.springX(targetX, MotionTokens.seg())
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
if (model.isNotBlank()) sb.append(" ｜ ").append(model)
tv.text = sb.toString()
} catch (e: Exception) {
tv.text = "状态不可用: " + (e.message ?: "")
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

companion object {
private const val KEY_MODEL_NAME = "model_name"
private const val KEY_LIGHT_THEME = "light_theme"
private const val KEY_DRAFT = "input_draft"
private const val BEG = "===REASONING_BEGIN==="
private const val END = "===REASONING_END==="
}
}