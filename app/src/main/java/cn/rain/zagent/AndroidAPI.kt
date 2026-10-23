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
    
@Volatile
private var currentActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

fun setActivity(act: android.app.Activity) {
currentActivity = java.lang.ref.WeakReference(act)
}

fun getActivity(): android.app.Activity? = currentActivity?.get()


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


// ---- Linux shell（proot + Termux bootstrap） ----

// 在 proot 环境执行一条 Linux 命令，返回 JSON 字符串
// 参数 cmd 会被交给 bash -lc 执行，timeoutSec 默认 60 秒
fun execShell(cmd: String, timeoutSec: Int): String {
 return try {
 cn.rain.zagent.linux.LinuxShellBridge.execJson(cmd, timeoutSec)
 } catch (ex: Throwable) {
 JSONObject().put("ok", false).put("code", -1)
 .put("stdout", "").put("stderr", ex.message ?: "").toString()
 }
}

// 查看 LinuxShellBridge 是否已解压完毕
fun linuxStatus(): String {
 return try {
 cn.rain.zagent.linux.LinuxShellBridge.statusJson()
 } catch (ex: Throwable) {
 JSONObject().put("ready", false).put("err", ex.message ?: "").toString()
 }
}

// 主动触发 bootstrap（首次会解压约 90MB rootfs，耗时约 30 秒）
fun linuxBootstrap(): String {
 return try {
 val ctx = appContext ?: return "fail: appContext 未初始化"
 val ok = cn.rain.zagent.linux.LinuxShellBridge.ensureBootstrapped(ctx)
 if (ok) "ready" else "fail: " + cn.rain.zagent.linux.LinuxShellBridge.getLastError()
 } catch (ex: Throwable) {
 "fail: " + (ex.message ?: "")
}
}
    fun evalJs(url: String, js: String, timeoutMs: Int = 15000): String {
        val ctx = appContext ?: return "{\"ok\":false, \"error\":\"appContext 未劊始期\"}"
        var result = "{\"ok\":false, \"error\":\"timeout\"}"
        val latch = java.util.concurrent.CountDownLatch(1)
        
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val wv = android.webkit.WebView(ctx)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, u: String?) {
                        view?.evaluateJavascript(js) { value ->
                            try {
                                val resObj = JSONObject().put("ok", true).put("result", if (value == "null") "" else value?.trim('"'))
                                result = resObj.toString()
                            } catch (e: Throwable) {
                                result = "{\"ok\":false, \"error\":\"JSON解析太败\"}"
                            }
                            latch.countDown()
                            wv.destroy()
                        }
                    }
                }
                wv.loadUrl(url)
            } catch (e: Throwable) {
                result = "{\"ok\":false, \"error\":" + JSONObject.quote(e.message ?: "") + "}"
                latch.countDown()
            }
        }
        
        try {
            if (!latch.await(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return "{\"ok\":false, \"error\":\"操作超曶\"}"
            }
        } catch (e: InterruptedException) {
            return "{\"ok\":false, \"error\":\"被中断\"}"
        }
        return result
    }

// --- UI 自动化接口 (ZAccessibilityService) ---
fun getWindowTree(): String {
return ZAccessibilityService.instance?.getWindowTree() ?: org.json.JSONObject().put("error", "无障碍服务未启动").toString()
}

fun gestureTap(x: Float, y: Float, durationMs: Int): String {
 val svc = ZAccessibilityService.instance
 if (svc == null) {
 return org.json.JSONObject().put("ok", false).put("outcome", "NOT_DISPATCHED").put("err", "accessibility service not connected").toString()
 }
 return svc.gestureTap(x, y, durationMs.toLong())
}

fun clickNodeByText(text: String): String {
 val svc = ZAccessibilityService.instance
 if (svc == null) {
 return org.json.JSONObject().put("ok", false).put("outcome", "NOT_DISPATCHED").put("err", "accessibility service not connected").toString()
 }
 return svc.clickNodeByText(text)
}
fun screenshot(excludedPackagesJson: String): String {
    val svc = ZAccessibilityService.instance
    if (svc == null) {
        return org.json.JSONObject().put("ok", false).put("outcome", "NOT_DISPATCHED")
            .put("err", "accessibility service not connected").toString()
    }
    return svc.screenshot(excludedPackagesJson)
}

// --- 无障碍健康检查 (task 17) ---
fun accessibilityStatus(): String {
 val svc = ZAccessibilityService.instance
 if (svc == null) {
 return JSONObject().put("ready", false).put("health", "DISCONNECTED")
 .put("msg", "accessibility service not connected").toString()
 }
 val hasWindow = try { svc.rootInActiveWindow != null } catch (e: Throwable) { false }
 if (!hasWindow) {
 return JSONObject().put("ready", false).put("health", "STALE")
 .put("msg", "no readable window").toString()
 }
 return JSONObject().put("ready", true).put("health", "READY").toString()
}
fun accessibilityReady(): Boolean {
 return ZAccessibilityService.instance != null
}

// --- OCR 接口 (OcrHelper) ---
fun ocrImage(imagePath: String): String {
return OcrHelper.recognizeText(imagePath)
}

    // --- 能力快照（供动作注册表按设备条件过滤） ---

    @Volatile private var rootState: String = "unknown"
    @Volatile private var rootProbedAt: Long = 0L

    private fun probeRoot(): String {
        val now = System.currentTimeMillis()
        if (rootState != "unknown" && now - rootProbedAt < 5 * 60_000L) return rootState
        rootProbedAt = now
        rootState = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
            val done = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) {
                p.destroyForcibly()
                "unknown"
            } else {
                val out = p.inputStream.bufferedReader().readText().trim()
                if (out == "0") "granted" else "denied"
            }
        } catch (e: Throwable) {
            "denied"
        }
        return rootState
    }

    // task 21: user scope
 fun appUserId(): Int {
 return android.os.Process.myUid() / 100000
 }


 fun capabilitySnapshot(): String {
        val api = org.json.JSONArray()
        val names = arrayOf(
            "battery", "notify", "toast", "vibrate", "device_info",
            "clipboard_get", "clipboard_set", "volume", "set_volume",
            "open_url", "shell_exec", "linux_status", "linux_bootstrap",
            "eval_js", "window_tree", "click_text", "screenshot", "ocr", "dynamic_ui"
        )
        for (n in names) api.put(n)
        return JSONObject()
            .put("sdk", Build.VERSION.SDK_INT)
            .put("root", probeRoot())
            .put("accessibility", ZAccessibilityService.instance != null)
            .put("android_api", api)
            .toString()
    }
}