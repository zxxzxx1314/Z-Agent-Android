package cn.rain.zagent

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object DynamicUiRenderer {
private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

fun show(context: Context, jsonStr: String): String {
val latch = CountDownLatch(1)
var resultStr = "{}"
val density = context.resources.displayMetrics.density

mainHandler.post {
val sheet = BottomSheetDialog(context)
val root = LinearLayout(context).apply {
orientation = LinearLayout.VERTICAL
setPadding((22 * density).toInt(), (16 * density).toInt(), (22 * density).toInt(), (24 * density).toInt())
}
val resMap = mutableMapOf<String, View>()

try {
val config = JSONObject(jsonStr)
val title = config.optString("title", "界面")

root.addView(MaterialTextView(context).apply {
text = title
textSize = 18f
setTextColor(ContextCompat.getColor(context, R.color.text_primary))
setTypeface(null, android.graphics.Typeface.BOLD)
setPadding(0, 0, 0, (14 * density).toInt())
})

val components = config.optJSONArray("components")
if (components != null) {
for (i in 0 until components.length()) {
val comp = components.getJSONObject(i)
when (comp.optString("type")) {
"text" -> {
root.addView(MaterialTextView(context).apply {
text = comp.optString("text")
textSize = 14f
setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
setPadding(0, 0, 0, (10 * density).toInt())
})
}
"input" -> {
val til = TextInputLayout(context, null, com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox)
til.hint = comp.optString("label")
val et = TextInputEditText(til.context)
et.setText(comp.optString("value"))
til.addView(et)
til.setPadding(0, 0, 0, (10 * density).toInt())
resMap[comp.optString("id")] = et
root.addView(til)
}
"switch" -> {
val sw = MaterialSwitch(context)
sw.text = comp.optString("label")
sw.isChecked = comp.optBoolean("value")
sw.setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
resMap[comp.optString("id")] = sw
root.addView(sw)
}
"button" -> {
val btn = MaterialButton(context)
btn.text = comp.optString("text")
btn.setOnClickListener {
val result = JSONObject()
for ((id, view) in resMap) {
when (view) {
is TextInputEditText -> result.put(id, view.text.toString())
is MaterialSwitch -> result.put(id, view.isChecked)
}
}
resultStr = result.toString()
sheet.dismiss()
latch.countDown()
}
root.addView(btn)
}
}
}
}
} catch (e: Exception) {
root.addView(MaterialTextView(context).apply {
text = "JSON 解析失败: " + e.message
setTextColor(ContextCompat.getColor(context, R.color.danger))
})
}

val scroll = ScrollView(context)
scroll.addView(root)
sheet.setContentView(scroll)
sheet.show()
sheet.setOnDismissListener {
latch.countDown()
}
}

if (!latch.await(10, TimeUnit.SECONDS)) {
return "{\"error\":\"ui_timeout\"}"
}
return resultStr
}
}