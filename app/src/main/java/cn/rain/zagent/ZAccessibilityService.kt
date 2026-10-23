package cn.rain.zagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// 无障碍服务：窗口树读取 + 手势点击。
//
// 点击一律走 dispatchGesture 的真实触摸，而不是 performAction(ACTION_CLICK)，
// 因为后者在自定义控件、Compose、游戏、WebView 上大量失效且不报错。
//
// 手势结果四态（大写），与 zcore.actions 的小写四态分开命名，由插件层做映射：
// COMPLETED 手势已完整执行
// CANCELLED 手势被系统取消，通常未生效，可重试
// NOT_DISPATCHED 确定没有发出（参数非法、服务未连、找不到节点），可安全换其它路径
// UNKNOWN 无法判定（Binder 抛异常、回调超时），禁止直接重试
class ZAccessibilityService : AccessibilityService() {

    companion object {
        const val OUTCOME_COMPLETED = "COMPLETED"
        const val OUTCOME_CANCELLED = "CANCELLED"
        const val OUTCOME_NOT_DISPATCHED = "NOT_DISPATCHED"
        const val OUTCOME_UNKNOWN = "UNKNOWN"

        // 手势回调最长等待；超时按 UNKNOWN 处理，不做重试建议。
        private const val GESTURE_TIMEOUT_MS = 5000L

        @Volatile
        var instance: ZAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun getWindowTree(): String {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            return JSONObject().put("error", "No active window").toString()
        }
        return try {
            nodeToJson(rootNode).toString()
        } catch (e: Throwable) {
            JSONObject().put("error", e.message ?: "Unknown error").toString()
        }
    }

    fun gestureTap(x: Float, y: Float, durationMs: Long = 50L): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return gestureResult(OUTCOME_NOT_DISPATCHED, x, y, "must not be called on main thread")
        }
        val path = Path().apply { moveTo(x, y) }
        val gesture = try {
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
                )
                .build()
        } catch (e: Throwable) {
            return gestureResult(OUTCOME_NOT_DISPATCHED, x, y, e.message)
        }

        val latch = CountDownLatch(1)
        val outcome = AtomicReference<String>()
        val dispatched = try {
            dispatchGesture(
                gesture,
                object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        outcome.set(OUTCOME_COMPLETED)
                        latch.countDown()
                    }

                    override fun onCancelled(g: GestureDescription?) {
                        outcome.set(OUTCOME_CANCELLED)
                        latch.countDown()
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Throwable) {
            // Binder 事务可能已经送达系统，不能声称未执行。
            return gestureResult(OUTCOME_UNKNOWN, x, y, e.message)
        }

        if (!dispatched) {
            return gestureResult(OUTCOME_NOT_DISPATCHED, x, y, "dispatchGesture returned false")
        }

        val finished = try {
            latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            return gestureResult(OUTCOME_UNKNOWN, x, y, "gesture callback timeout")
        }
        return gestureResult(outcome.get() ?: OUTCOME_UNKNOWN, x, y, null)
    }

    // 按文本找可点击祖先，再用真实手势点它。
    // 返回 JSON（含 outcome），不再返回布尔值。
    fun clickNodeByText(targetText: String): String {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            return gestureResult(OUTCOME_NOT_DISPATCHED, null, null, "no active window")
        }
        val nodes = try {
            rootNode.findAccessibilityNodeInfosByText(targetText)
        } catch (e: Throwable) {
            return gestureResult(OUTCOME_UNKNOWN, null, null, e.message)
        }
        if (nodes.isNullOrEmpty()) {
            return gestureResult(OUTCOME_NOT_DISPATCHED, null, null, "node not found")
        }
        for (node in nodes) {
            var curr: AccessibilityNodeInfo? = node
            while (curr != null) {
                if (curr.isClickable) {
                    val rect = Rect()
                    curr.getBoundsInScreen(rect)
                    if (rect.width() <= 0 || rect.height() <= 0) {
                        // 可点击但无面积（0x0 占位节点），手势无从下手，退回节点动作。
                        val ok = try {
                            curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        } catch (e: Throwable) {
                            return gestureResult(OUTCOME_UNKNOWN, null, null, e.message)
                        }
                        val oc = if (ok) OUTCOME_COMPLETED else OUTCOME_NOT_DISPATCHED
                        val err = if (ok) null else "ACTION_CLICK returned false"
                        return gestureResult(oc, null, null, err)
                    }
                    return gestureTap(rect.exactCenterX(), rect.exactCenterY())
                }
                curr = curr.parent
            }
        }
        return gestureResult(OUTCOME_NOT_DISPATCHED, null, null, "no clickable ancestor")
    }

    // 统一的手势结果 JSON，字段与 android_api.gesture_tap 的返回约定一致。
    private fun gestureResult(outcome: String, x: Float?, y: Float?, err: String?): String {
        val o = JSONObject()
        o.put("ok", outcome == OUTCOME_COMPLETED)
        o.put("outcome", outcome)
        if (x != null) o.put("x", x.toDouble())
        if (y != null) o.put("y", y.toDouble())
        if (err != null) o.put("err", err)
        return o.toString()
    }

    private fun nodeToJson(node: AccessibilityNodeInfo): JSONObject {
        val obj = JSONObject()
        obj.put("text", node.text?.toString() ?: "")
        obj.put("desc", node.contentDescription?.toString() ?: "")
        obj.put("class", node.className?.toString() ?: "")
        obj.put("clickable", node.isClickable)
        obj.put("scrollable", node.isScrollable)
        obj.put("editable", node.isEditable)

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val bounds = JSONObject()
        bounds.put("x1", rect.left)
        bounds.put("y1", rect.top)
        bounds.put("x2", rect.right)
        bounds.put("y2", rect.bottom)
        obj.put("bounds", bounds)

        val children = JSONArray()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                children.put(nodeToJson(child))
            }
        }
        obj.put("children", children)
        return obj
    }

    // 全屏截图（API 30+）。多窗口按 z 序，只取 ScreenshotWindowPolicy.decide 判定为 CAPTURE 的窗口。
    // excludedPackagesJson 是 JSON 数组字符串，例如 ["com.example.voiceoverlay"]。
    // 返回 JSON：ok / outcome / png_base64 / window_id / bytes / err。
    // 不能在主线程调用；截到 BLOCK_UNKNOWN 直接整单失败，不做部分截取。
    fun screenshot(excludedPackagesJson: String): String {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            return JSONObject().put("ok", false).put("outcome", "NOT_DISPATCHED")
                .put("err", "screenshot requires API 30+").toString()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return JSONObject().put("ok", false).put("outcome", "NOT_DISPATCHED")
                .put("err", "must not be called on main thread").toString()
        }
        val excluded = mutableSetOf<String>()
        try {
            val arr = JSONArray(excludedPackagesJson)
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotBlank()) excluded.add(v)
            }
        } catch (e: Throwable) {
            // 传入非法 JSON 按空排除列表处理；不能因为参数错就整单失败。
        }
        val wins = try {
            windows
        } catch (e: Throwable) {
            return JSONObject().put("ok", false).put("outcome", "UNKNOWN")
                .put("err", "windows unavailable: " + (e.message ?: "?")).toString()
        }
        if (wins == null || wins.isEmpty()) {
            return JSONObject().put("ok", false).put("outcome", "NOT_DISPATCHED")
                .put("err", "no windows").toString()
        }
        val captureIds = mutableListOf<Int>()
        for (w in wins) {
            val root = w.root
            val pkg = try {
                root?.packageName?.toString()
            } catch (e: Throwable) {
                null
            }
            val d = ScreenshotWindowPolicy.decide(
                isAccessibilityOverlay = w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                isApplicationWindow = w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION,
                active = w.isActive,
                focused = w.isFocused,
                resolvedPackage = pkg,
                ownPackage = packageName,
                excludedPackages = excluded,
            )
            when (d) {
                ScreenshotWindowPolicy.Decision.BLOCK_UNKNOWN -> {
                    return JSONObject().put("ok", false).put("outcome", "UNKNOWN")
                        .put("err", "window package unresolved: id=" + w.id).toString()
                }
                ScreenshotWindowPolicy.Decision.EXCLUDE -> {
                    // skip
                }
                ScreenshotWindowPolicy.Decision.CAPTURE -> captureIds.add(w.id)
            }
        }
        if (captureIds.isEmpty()) {
            return JSONObject().put("ok", false).put("outcome", "NOT_DISPATCHED")
                .put("err", "no capturable window").toString()
        }
        val wid = captureIds.last()
        val latch = CountDownLatch(1)
        val outRef = AtomicReference<android.graphics.Bitmap?>()
        val errRef = AtomicReference<String?>()
        val executor = java.util.concurrent.Executor { command ->
            Handler(Looper.getMainLooper()).post(command)
        }
        try {
            takeScreenshotOfWindow(
                wid,
                executor,
                object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        val hb = result.hardwareBuffer
                        try {
                            val bmp = android.graphics.Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            if (bmp != null) {
                                outRef.set(bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false))
                            } else {
                                errRef.set("wrapHardwareBuffer returned null")
                            }
                        } catch (e: Throwable) {
                            errRef.set("decode: " + (e.message ?: "?"))
                        } finally {
                            try { hb.close() } catch (e: Throwable) { }
                        }
                        latch.countDown()
                    }
                    override fun onFailure(errorCode: Int) {
                        errRef.set("screenshot failed code=" + errorCode)
                        latch.countDown()
                    }
                }
            )
        } catch (e: Throwable) {
            // Binder 可能已经受理，不能声称未执行。
            return JSONObject().put("ok", false).put("outcome", "UNKNOWN")
                .put("err", "takeScreenshotOfWindow threw: " + (e.message ?: "?")).toString()
        }
        val finished = try {
            latch.await(10L, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            return JSONObject().put("ok", false).put("outcome", "UNKNOWN")
                .put("err", "screenshot timeout").toString()
        }
        val bmp = outRef.get()
        if (bmp == null) {
            return JSONObject().put("ok", false).put("outcome", "UNKNOWN")
                .put("err", errRef.get() ?: "unknown").toString()
        }
        val bos = java.io.ByteArrayOutputStream()
        val ok = bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        if (!ok) {
            return JSONObject().put("ok", false).put("outcome", "UNKNOWN")
                .put("err", "PNG compress failed").toString()
        }
        val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        return JSONObject().put("ok", true).put("outcome", "COMPLETED")
            .put("png_base64", b64).put("window_id", wid).put("bytes", bos.size()).toString()
    }
}
