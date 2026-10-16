package cn.rain.zagent

import android.app.Application
import android.content.Intent
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashApp : Application() {
override fun onCreate() {
super.onCreate()
AppStartup.mark()
 if (isCrashProcess()) return
val prev = Thread.getDefaultUncaughtExceptionHandler()
Thread.setDefaultUncaughtExceptionHandler { t, e ->
try {
val sw = StringWriter()
e.printStackTrace(PrintWriter(sw))
val stack = sw.toString()
val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
val summary = (e.javaClass.name + ": " + (e.message ?: "")).take(240)
val dir = File(filesDir, "crashes")
dir.mkdirs()
val f = File(dir, "crash_" + System.currentTimeMillis() + ".txt")
val head = "time=" + ts + "\n" +
"thread=" + t.name + "\n" +
"sdk=" + Build.VERSION.SDK_INT + "\n" +
"model=" + Build.MODEL + "\n" +
"app=" + packageName + "\n" +
"summary=" + summary + "\n\n"
f.writeText(head + stack)
} catch (x: Throwable) { }

try {
val it = Intent(this, CrashActivity::class.java)
it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
startActivity(it)
} catch (x: Throwable) { }

try { Thread.sleep(800) } catch (x: Throwable) { }
prev?.uncaughtException(t, e)
}
}

private fun isCrashProcess(): Boolean {
return try {
val pn: String? = if (Build.VERSION.SDK_INT >= 28) {
Application.getProcessName()
} else {
val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
var cur: String? = null
val pids = am.runningAppProcesses
if (pids != null) {
for (pi in pids) {
if (pi.pid == android.os.Process.myPid()) { cur = pi.processName; break }
}
}
cur
}
pn != null && pn.endsWith(":crash")
} catch (e: Throwable) { false }
}
}