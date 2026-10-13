package cn.rain.zagent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
}