package com.example.zagent

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
import android.view.HapticFeedbackConstants
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
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

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
private var thinkOn = false
private var appMode = "build"

private lateinit var sp: SharedPreferences

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
setContentView(R.layout.activity_main)
WindowCompat.setDecorFitsSystemWindows(window, false)

sp = getSharedPreferences("zagent", Context.MODE_PRIVATE)

statusText = findViewById(R.id.statusText)
outputScroll = findViewById(R.id.outputScroll)
chatContainer = findViewById(R.id.chatContainer)
emptyState = findViewById(R.id.emptyState)
inputEdit = findViewById(R.id.inputEdit)
inputCard = findViewById(R.id.inputCard)
sendButton = findViewById(R.id.sendButton)
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

findViewById<View>(R.id.settingsButton).setOnClickListener { haptic(it); showSettings() }
findViewById<View>(R.id.btnClear).setOnClickListener { haptic(it); sendCommand("/clear") }
findViewById<View>(R.id.btnContract).setOnClickListener { haptic(it); sendCommand("/contract") }
findViewById<View>(R.id.btnHelp).setOnClickListener { haptic(it); sendCommand("/help") }
findViewById<View>(R.id.btnIdle).setOnClickListener { haptic(it); sendCommand("/idle") }
findViewById<View>(R.id.btnVersions).setOnClickListener { haptic(it); sendCommand("/versions") }
findViewById<View>(R.id.btnPlugins).setOnClickListener {
haptic(it); startActivity(Intent(this, PluginActivity::class.java))
}
findViewById<View>(R.id.btnSavePlugin).setOnClickListener { haptic(it); promptSavePlugin() }

findViewById<View>(R.id.emptyChip1).setOnClickListener { haptic(it); fillInput("解释这段代码") }
findViewById<View>(R.id.emptyChip2).setOnClickListener { haptic(it); fillInput("写个单元测试") }
findViewById<View>(R.id.emptyChip3).setOnClickListener { haptic(it); fillInput("重构这个函数") }
findViewById<View>(R.id.emptyChip4).setOnClickListener { haptic(it); fillInput("排查这个报错") }

themeButton.setOnClickListener { haptic(it); toggleTheme() }

 autoSwitch.setOnCheckedChangeListener { v, checked ->
 v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
 refreshAutoSwitch(checked)
 }
 refreshAutoSwitch(autoSwitch.isChecked)

autoSwitch.setOnCheckedChangeListener { v, checked ->
v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
threadRun {
safeBridge { PythonBridge.setAuto(this@MainActivity, checked) }
}
}

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
inputCard.setOnClickListener { inputEdit.requestFocus() }

segContainer.post { layoutSegThumb(false) }

appendOutput("Z-Agent APK 启动中……\n")
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
appendOutput("输入消息开始对话；点下方按钮直达功能。\n")
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

private fun haptic(v: View) {
v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
val sys = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
val pad = maxOf(ime.bottom, sys.bottom)
inputBar.setPadding(inputBar.paddingLeft, inputBar.paddingTop,
inputBar.paddingRight, pad)
insets
}
ViewCompat.requestApplyInsets(topBar)
ViewCompat.requestApplyInsets(root)
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

private fun showSettings() {
val sheet = BottomSheetDialog(this)
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
textSize = 12f
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
threadRun {
safeBridge {
val r = PythonBridge.setApiKey(this@MainActivity, key)
runOnUiThread {
appendOutput("[设置] API Key " + r + "\n")
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
if (name.isEmpty()) toast("名称不能为空")
else sendCommand("/save " + name)
}
.setNegativeButton("取消", null)
.show()
}

private fun onSend() {
if (busy) { toast("上一轮还在进行中……"); return }
val text = inputEdit.text.toString().trim()
if (text.isEmpty() && pendingFiles.isEmpty()) return
inputEdit.setText("")
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

private fun sendCommand(cmd: String) {
if (busy) { toast("上一轮还在进行中……"); return }
busy = true
sendButton.isClickable = false; sendButton.alpha = 0.5f
threadRun {
safeBridge {
val reply = PythonBridge.runAgent(this@MainActivity, cmd)
runOnUiThread {
appendOutput(reply + "\n")
busy = false
sendButton.isClickable = true; sendButton.alpha = 1f
sendButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
textSize = 10f
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
text = content
textSize = 14f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setBackgroundResource(R.drawable.bg_bubble_user)
setPadding(dp(14), dp(10), dp(14), dp(10))
maxWidth = dp(280)
setTextIsSelectable(true)
isClickable = true
isLongClickable = true
}
bubble.setOnLongClickListener { v ->
v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
showBubbleMenu(v, row, content, true)
true
}
row.addView(bubble)
 val wrapped = wrapWithTime(row, true)
 chatContainer.addView(wrapped)
 wrapped.post { bounceIn(wrapped) }
}

private fun addAiBubble(content: String) {
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
text = content
textSize = 14f
setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
setBackgroundResource(R.drawable.bg_bubble_ai)
setPadding(dp(14), dp(10), dp(14), dp(10))
maxWidth = dp(300)
setTextIsSelectable(true)
isClickable = true
isLongClickable = true
}
bubble.setOnLongClickListener { v ->
v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
(row.parent as? View)?.let { chatContainer.removeView(it) } ?: chatContainer.removeView(row)
if (chatContainer.childCount <= 1) emptyState.visibility = View.VISIBLE
true
}
else -> false
}
}
popup.show()
}

private fun copyText(t: String) {
val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
cm.setPrimaryClip(ClipData.newPlainText("zagent", t))
toast("已复制")
}

private fun addSystemLine(content: String) {
val tv = TextView(this).apply {
text = content
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
textSize = 12f
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
it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
val expanded = body.visibility == View.VISIBLE
body.visibility = if (expanded) View.GONE else View.VISIBLE
header.text = if (expanded) "▸ 思考过程（点击展开）" else "▾ 思考过程（点击收起）"
}
box.addView(header)
box.addView(body)
chatContainer.addView(box)
box.post { bounceIn(box) }
}

private fun bounceIn(view: View) {
view.cameraDistance = 8000f * resources.displayMetrics.density
view.rotationX = 8f
view.scaleX = 0.94f
view.scaleY = 0.94f
view.alpha = 0f
view.animate()
.rotationX(0f)
.scaleX(1f)
.scaleY(1f)
.alpha(1f)
.setDuration(360)
.setInterpolator(android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f))
.start()
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
segThumb.animate()
.translationX(targetX)
.setDuration(180)
.setInterpolator(android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f))
.start()
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
textSize = 12f
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
v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
.append(" ｜ 会话 ").append(turns).append(" 条")
if (model.isNotBlank()) sb.append(" ｜ 模型 ").append(model)
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