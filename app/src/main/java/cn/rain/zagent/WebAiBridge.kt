package cn.rain.zagent

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.lang.ref.WeakReference

object WebAiBridge {
 private var mainActivity: WeakReference<MainActivity>? = null
private var webView: WebView? = null
private var appContext: android.content.Context? = null
@Volatile private var currentUrl: String = "https://chat.deepseek.com/"
@Volatile var isLoaded = false
private val mainHandler = Handler(Looper.getMainLooper())
private var currentLatch: CountDownLatch? = null
private var currentResult: String? = null
private var debugLatch: CountDownLatch? = null
private var debugResult: String? = null

private const val DEBUG_JS_B64 = "KGZ1bmN0aW9uKCl7CnZhciBvdXQ9W107Cm91dC5wdXNoKCdVUkw9Jytsb2NhdGlvbi5ocmVmKTsKb3V0LnB1c2goJ1RJVExFPScrZG9jdW1lbnQudGl0bGUpOwp2YXIgdGFzPWRvY3VtZW50LnF1ZXJ5U2VsZWN0b3JBbGwoJ3RleHRhcmVhJyk7Cm91dC5wdXNoKCctLS0gVEVYVEFSRUFTIGNvdW50PScrdGFzLmxlbmd0aCsnIC0tLScpOwpmb3IodmFyIGk9MDtpPHRhcy5sZW5ndGg7aSsrKXt2YXIgdD10YXNbaV07b3V0LnB1c2goJ1snK2krJ10gdmlzPScrKHQub2Zmc2V0UGFyZW50IT09bnVsbCkrJyB2YWxfbGVuPScrdC52YWx1ZS5sZW5ndGgpO30KdmFyIGRzYj1kb2N1bWVudC5xdWVyeVNlbGVjdG9yQWxsKCdkaXZbY2xhc3MqPWRzLWJ1dHRvbl0nKTsKb3V0LnB1c2goJy0tLSBEU19CVVRUT05TIGNvdW50PScrZHNiLmxlbmd0aCsnIC0tLScpOwpmb3IodmFyIGk9MDtpPE1hdGgubWluKGRzYi5sZW5ndGgsMjApO2krKyl7dmFyIGI9ZHNiW2ldO3ZhciBjbHM9Yi5jbGFzc05hbWU7b3V0LnB1c2goJ1snK2krJ10gY2lyY2xlPScrKGNscy5pbmRleE9mKCdjaXJjbGUnKT49MCkrJyBkaXNhYmxlZD0nKyhjbHMuaW5kZXhPZignZGlzYWJsZWQnKT49MCkrJyBwcmltYXJ5PScrKGNscy5pbmRleE9mKCdwcmltYXJ5Jyk+PTApKTt9CnZhciBtZHM9ZG9jdW1lbnQucXVlcnlTZWxlY3RvckFsbCgnZGl2W2NsYXNzKj1kcy1tYXJrZG93bl0nKTsKb3V0LnB1c2goJy0tLSBEU19NQVJLRE9XTiBjb3VudD0nK21kcy5sZW5ndGgrJyAtLS0nKTsKb3V0LnB1c2goJ0JPRFlfVEVYVF9MRU49Jytkb2N1bWVudC5ib2R5LmlubmVyVGV4dC5sZW5ndGgpOwpSYWluQnJpZGdlLm9uRGVidWcob3V0LmpvaW4oU3RyaW5nLmZyb21DaGFyQ29kZSgxMCkpKTsKfSkoKTsK"

private fun decodeJs(b64: String): String {
val bytes = Base64.decode(b64, Base64.DEFAULT)
return String(bytes, Charset.forName("UTF-8"))
}

@Volatile private var cachedBuildJs: String? = null

private fun readBuildJs(ctx: Context): String {
cachedBuildJs?.let { return it }
val t = try {
ctx.assets.open("build.js").bufferedReader().use { it.readText() }
} catch (e: Exception) {
""
}
cachedBuildJs = t
return t
}

@SuppressLint("SetJavaScriptEnabled")
fun init(context: Context) {
 if (context is MainActivity) mainActivity = WeakReference(context)
appContext = context.applicationContext
if (webView != null) return
mainHandler.post {
val wv = WebView(context.applicationContext)
val st = wv.settings
st.javaScriptEnabled = true
st.domStorageEnabled = true
st.databaseEnabled = true
st.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
wv.addJavascriptInterface(JSInterface(), "RainBridge")
wv.webViewClient = object : WebViewClient() {
override fun onPageFinished(view: WebView?, url: String?) {
super.onPageFinished(view, url)
isLoaded = true
}
}
// 共享系统 Cookie，实现无感登录
val cookieManager = android.webkit.CookieManager.getInstance()
cookieManager.setAcceptCookie(true)
cookieManager.setAcceptThirdPartyCookies(wv, true)

 wv.loadUrl(currentUrl)
webView = wv
}
}

fun getWebView(): WebView? = webView

fun setUrl(url: String) {
if (url == currentUrl && webView != null) return
currentUrl = url
reset()
appContext?.let { init(it) }
}

fun getUrl(): String = currentUrl

fun sites(): Map<String, String> = linkedMapOf(
"DeepSeek" to "https://chat.deepseek.com/",
"智谱清言" to "https://chatglm.cn/main/alltoolsdetail",
"Kimi" to "https://kimi.moonshot.cn/"
)

fun stop() {
mainHandler.post {
try { webView?.evaluateJavascript("window.__rainSeq = 0;", null) } catch (e: Exception) {}
}
currentLatch?.countDown()
 currentLatch = null
}


fun reset() {
 currentLatch?.countDown()
 currentLatch = null
 currentResult = "ERROR: reset"
 debugLatch?.countDown()
 debugLatch = null
 debugResult = "ERROR: reset"
 val wv = webView
 webView = null
 isLoaded = false
 mainHandler.post {
 try { wv?.stopLoading() } catch (e: Exception) {}
 try { wv?.destroy() } catch (e: Exception) {}
 }
}

fun cleanupPending(reason: String) {
 currentLatch?.countDown()
 currentLatch = null
 currentResult = "ERROR: " + reason
 debugLatch?.countDown()
 debugLatch = null
 debugResult = "ERROR: " + reason
}

fun debugDom(): String {
val wv = webView ?: return "ERROR: no webview"
val latch = CountDownLatch(1)
debugLatch = latch
debugResult = null
mainHandler.post {
wv.evaluateJavascript(decodeJs(DEBUG_JS_B64), null)
}
val ok = latch.await(15, TimeUnit.SECONDS)
if (!ok) return "ERROR: debug timeout"
return debugResult ?: "ERROR: null"
}

@Synchronized
fun sendAndWait(prompt: String): String {
if (webView == null && appContext != null) {
init(appContext!!)
var waitInit = 0L
while (webView == null && waitInit < 3000L) {
Thread.sleep(100L)
waitInit += 100L
}
}
val wv = webView ?: return "ERROR: not initialized"

// 强制唤醒 WebView 引擎
mainHandler.post {
try { wv.onResume() } catch (e: Exception) {}
try { wv.resumeTimers() } catch (e: Exception) {}
}

var waited = 0L
while (!isLoaded && waited < 15000L) {
Thread.sleep(150L)
waited += 150L
}
if (!isLoaded) {
// 如果 15 秒还没加载完，尝试重新加载当前 URL
mainHandler.post {
try { wv.loadUrl(currentUrl) } catch (e: Exception) {}
}
waited = 0L
while (!isLoaded && waited < 5000L) {
Thread.sleep(150L)
waited += 150L
}
if (!isLoaded) return "ERROR: webview 加载超时，请检查网络或点击右上角刷新网页"
}

val latch = CountDownLatch(1)
currentLatch = latch
currentResult = null
val q = JSONObject.quote(prompt)

mainHandler.post {
val js = readBuildJs(appContext!!).replace("RAIN_PROMPT_TOKEN", q)
try {
wv.evaluateJavascript(js) { ret ->
if (ret == null) {
currentResult = "ERROR: JS eval returned null"
currentLatch?.countDown()
}
}
} catch (e: Exception) {
currentResult = "ERROR: WebView crashed"
currentLatch?.countDown()
}
}

val ok = latch.await(300, TimeUnit.SECONDS)
if (!ok) {
mainHandler.post { currentLatch = null }
return "ERROR: timeout"
}
return currentResult ?: "ERROR: null"
}

class JSInterface {
@JavascriptInterface
fun onStream(type: String, text: String) {
if (text.isEmpty()) return
val act = mainActivity?.get() ?: return
act.runOnUiThread {
if (type == "content") act.appendStream(text)
else if (type == "reason") act.appendReason(text)
}
}

@JavascriptInterface
fun onResult(text: String) {
currentResult = text
currentLatch?.countDown()
}
@JavascriptInterface
fun onError(err: String) {
currentResult = "ERROR: " + err
currentLatch?.countDown()
}
@JavascriptInterface
fun onDebug(text: String) {
debugResult = text
debugLatch?.countDown()
}
}
}
