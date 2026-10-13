package cn.rain.zagent

import android.app.Application
import android.content.Intent
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 全局未捕获异常捕获：把崩溃堆栈写盘并拉起 CrashActivity
class CrashApp : Application() {
override fun onCreate() {
super.onCreate()
 AppStartup.mark()
val prev = Thread.getDefaultUncaughtExceptionHandler()
Thread.setDefaultUncaughtExceptionHandler { t, e ->
try {
val sw = StringWriter()
e.printStackTrace(PrintWriter(sw))
val dir = File(filesDir, "crashes")
dir.mkdirs()
val f = File(dir, "crash_" + System.currentTimeMillis() + ".txt")
val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
val head = "time=" + ts + "\nthread=" + t.name + "\n\n"
f.writeText(head + sw.toString())
CrashBus.last = sw.toString()
startActivity(Intent(this, CrashActivity::class.java)
.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
} catch (x: Throwable) { }
prev?.uncaughtException(t, e)
}
}
}

object CrashBus {
@Volatile var last: String? = null
}