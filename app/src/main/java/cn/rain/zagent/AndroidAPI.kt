package cn.rain.zagent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * AndroidAPI —— Python 层（android_api.py）调用的 Android 原生能力。
 *
 * 三个静态入口（与 android_api.py 的 docstring 一一对应）：
 *   getBatteryStatus()  电池状态 JSON（percent / charging）
 *   notify(title, content) 系统通知（频道 zagent_notify）
 *   toast(message)      主线程弹 Toast（Python 线程安全）
 *
 * 由 PythonBridge.ensureBootstrapped() 在引导时 init(context)。
 */
object AndroidAPI {

    private const val CHANNEL_ID = "zagent_notify"
    private const val ICON_RES = android.R.drawable.ic_dialog_info

    @Volatile
    private var appContext: Context? = null

    /** PythonBridge 引导时调用（主线程）。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext?.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID, "Z-Agent 通知", NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }
    }

    /** 电池状态 JSON：{"percent": 87, "charging": false}（异常 → {"error": ...}）。 */
    fun getBatteryStatus(): String {
        return try {
            val ctx = appContext
                ?: return JSONObject().put("error", "appContext 未初始化").toString()
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return JSONObject().put("error", "BatteryManager 不可用").toString()
            JSONObject()
                .put("percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("charging", bm.isCharging)
                .toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.toString()).toString()
        }
    }

    /** 系统通知（无频道设备自动降级为普通通知）。 */
    fun notify(title: String, content: String) {
        try {
            val ctx = appContext ?: return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(ICON_RES)
                .setContentTitle(title)
                .setContentText(content)
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() % 100000L).toInt(), n)
        } catch (e: Exception) {
            // 通知失败静默（Python 层不感知，避免打断会话）
        }
    }

    /** 主线程 Toast（Python 任意线程可安全调用）。 */
    fun toast(message: String) {
        try {
            val ctx = appContext ?: return
            Handler(ctx.mainLooper).post {
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            // Toast 失败静默
        }
    }

// ── v2 扩展：振动 / 剪贴板 / 音量 / 打开链接 / 机型 ──

fun vibrate(ms: Int) {
try {
val ctx = appContext ?: return
val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
val isO = java.lang.Math.max(android.os.Build.VERSION.SDK_INT, android.os.Build.VERSION_CODES.O) == android.os.Build.VERSION.SDK_INT
if (isO) {
v.vibrate(android.os.VibrationEffect.createOneShot(ms.toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE))
} else {
@Suppress("DEPRECATION")
v.vibrate(ms.toLong())
}
} catch (e: Exception) { }
}

fun getDeviceInfo(): String {
return try {
JSONObject()
.put("brand", Build.BRAND)
.put("model", Build.MODEL)
.put("manufacturer", Build.MANUFACTURER)
.put("android", Build.VERSION.RELEASE)
.put("sdk", Build.VERSION.SDK_INT)
.toString()
} catch (e: Exception) {
JSONObject().put("error", e.toString()).toString()
}
}

fun clipboardGet(): String {
return try {
val ctx = appContext ?: return ""
val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return ""
cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
} catch (e: Exception) { "" }
}

fun clipboardSet(text: String) {
try {
val ctx = appContext ?: return
val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
cm.setPrimaryClip(android.content.ClipData.newPlainText("zagent", text))
} catch (e: Exception) { }
}

fun getVolume(): String {
return try {
val ctx = appContext
?: return JSONObject().put("error", "no ctx").toString()
val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
?: return JSONObject().put("error", "no am").toString()
JSONObject()
.put("music", am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
.put("max", am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC))
.toString()
} catch (e: Exception) {
JSONObject().put("error", e.toString()).toString()
}
}

fun setVolume(level: Int) {
try {
val ctx = appContext ?: return
val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, level.coerceIn(0, max), 0)
} catch (e: Exception) { }
}

fun openUrl(url: String) {
try {
val ctx = appContext ?: return
val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
ctx.startActivity(i)
} catch (e: Exception) { }
}

}