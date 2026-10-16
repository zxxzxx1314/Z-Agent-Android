package cn.rain.zagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class CrashActivity : AppCompatActivity() {

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
setContentView(R.layout.activity_crash)

val sub = findViewById<TextView>(R.id.crashSub)
val body = findViewById<TextView>(R.id.crashText)
val btnCopy = findViewById<TextView>(R.id.crashCopy)
val btnShare = findViewById<TextView>(R.id.crashShare)
val btnRestart = findViewById<TextView>(R.id.crashRestart)
val btnExit = findViewById<TextView>(R.id.crashExit)

val raw = readLatestCrash() ?: "(无崩溃日志)"
val summary = grabField(raw, "summary")
val ts = grabField(raw, "time")
sub.text = summary.ifBlank { "未捕获异常" }
body.text = raw
val full = (if (ts.isNotBlank()) "time=" + ts + "\n\n" else "") + raw

btnCopy.setOnClickListener {
val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
cm.setPrimaryClip(ClipData.newPlainText("crash", full))
Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
}
btnShare.setOnClickListener {
val it = Intent(Intent.ACTION_SEND)
it.type = "text/plain"
it.putExtra(Intent.EXTRA_TEXT, full)
try { startActivity(Intent.createChooser(it, "分享崩溃日志")) } catch (e: Exception) { }
}
btnRestart.setOnClickListener {
val it = packageManager.getLaunchIntentForPackage(packageName)
if (it != null) {
it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
startActivity(it)
}
finish()
}
btnExit.setOnClickListener { finishAffinity() }
}

private fun grabField(text: String, key: String): String {
val prefix = key + "="
for (line in text.lineSequence()) {
if (line.startsWith(prefix)) return line.substring(prefix.length).trim()
}
return ""
}

private fun readLatestCrash(): String? {
val dir = File(filesDir, "crashes")
if (!dir.isDirectory) return null
val f = dir.listFiles()?.maxByOrNull { it.lastModified() } ?: return null
return try { f.readText() } catch (e: Exception) { null }
}
}
