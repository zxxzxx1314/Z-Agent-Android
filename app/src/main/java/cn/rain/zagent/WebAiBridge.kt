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

object WebAiBridge {
    private var webView: WebView? = null
    @Volatile var isLoaded = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentLatch: CountDownLatch? = null
    private var currentResult: String? = null
    private var debugLatch: CountDownLatch? = null
    private var debugResult: String? = null

    private const val DEBUG_JS_B64 = "KGZ1bmN0aW9uKCl7CnZhciBvdXQ9W107Cm91dC5wdXNoKCdVUkw9Jytsb2NhdGlvbi5ocmVmKTsKb3V0LnB1c2goJ1RJVExFPScrZG9jdW1lbnQudGl0bGUpOwp2YXIgdGFzPWRvY3VtZW50LnF1ZXJ5U2VsZWN0b3JBbGwoJ3RleHRhcmVhJyk7Cm91dC5wdXNoKCctLS0gVEVYVEFSRUFTIGNvdW50PScrdGFzLmxlbmd0aCsnIC0tLScpOwpmb3IodmFyIGk9MDtpPHRhcy5sZW5ndGg7aSsrKXt2YXIgdD10YXNbaV07b3V0LnB1c2goJ1snK2krJ10gdmlzPScrKHQub2Zmc2V0UGFyZW50IT09bnVsbCkrJyB2YWxfbGVuPScrdC52YWx1ZS5sZW5ndGgpO30KdmFyIGRzYj1kb2N1bWVudC5xdWVyeVNlbGVjdG9yQWxsKCdkaXZbY2xhc3MqPWRzLWJ1dHRvbl0nKTsKb3V0LnB1c2goJy0tLSBEU19CVVRUT05TIGNvdW50PScrZHNiLmxlbmd0aCsnIC0tLScpOwpmb3IodmFyIGk9MDtpPE1hdGgubWluKGRzYi5sZW5ndGgsMjApO2krKyl7dmFyIGI9ZHNiW2ldO3ZhciBjbHM9Yi5jbGFzc05hbWU7b3V0LnB1c2goJ1snK2krJ10gY2lyY2xlPScrKGNscy5pbmRleE9mKCdjaXJjbGUnKT49MCkrJyBkaXNhYmxlZD0nKyhjbHMuaW5kZXhPZignZGlzYWJsZWQnKT49MCkrJyBwcmltYXJ5PScrKGNscy5pbmRleE9mKCdwcmltYXJ5Jyk+PTApKTt9CnZhciBtZHM9ZG9jdW1lbnQucXVlcnlTZWxlY3RvckFsbCgnZGl2W2NsYXNzKj1kcy1tYXJrZG93bl0nKTsKb3V0LnB1c2goJy0tLSBEU19NQVJLRE9XTiBjb3VudD0nK21kcy5sZW5ndGgrJyAtLS0nKTsKb3V0LnB1c2goJ0JPRFlfVEVYVF9MRU49Jytkb2N1bWVudC5ib2R5LmlubmVyVGV4dC5sZW5ndGgpOwpSYWluQnJpZGdlLm9uRGVidWcob3V0LmpvaW4oU3RyaW5nLmZyb21DaGFyQ29kZSgxMCkpKTsKfSkoKTsK"
    private const val BUILD_JS_B64 = "KGZ1bmN0aW9uKCl7CnZhciBQUk9NUFQ9UkFJTl9QUk9NUFRfVE9LRU47CnZhciBUSU1FT1VUTVM9MTIwMDAwOwp2YXIgSURMRURPTkVNUz00MDAwOwpmdW5jdGlvbiBmaW5kSW5wdXQoKXsKdmFyIHQ9ZG9jdW1lbnQucXVlcnlTZWxlY3RvcigndGV4dGFyZWEnKTsKaWYodCYmdC5vZmZzZXRQYXJlbnQhPT1udWxsKXJldHVybiB0OwpyZXR1cm4gbnVsbDsKfQpmdW5jdGlvbiBmaW5kU2VuZEJ0bigpewp2YXIgYnRuPWRvY3VtZW50LnF1ZXJ5U2VsZWN0b3IoJ2Rpdi5kcy1idXR0b24tLWNpcmNsZTpub3QoLmRzLWJ1dHRvbi0tZGlzYWJsZWQpJyk7CmlmKGJ0bilyZXR1cm4gYnRuOwp2YXIgYWxsPWRvY3VtZW50LnF1ZXJ5U2VsZWN0b3JBbGwoJ2RpdltjbGFzcyo9ZHMtYnV0dG9uXScpOwpmb3IodmFyIGk9YWxsLmxlbmd0aC0xO2k+PTA7aS0tKXsKdmFyIGI9YWxsW2ldOwp2YXIgY2xzPWIuY2xhc3NOYW1lfHwnJzsKaWYoY2xzLmluZGV4T2YoJ2NpcmNsZScpPj0wJiZjbHMuaW5kZXhPZignZGlzYWJsZWQnKTwwKXJldHVybiBiOwp9CnJldHVybiBudWxsOwp9CmZ1bmN0aW9uIGNvdW50TWFya2Rvd24oKXsKcmV0dXJuIGRvY3VtZW50LnF1ZXJ5U2VsZWN0b3JBbGwoJ2RpdltjbGFzcyo9ZHMtbWFya2Rvd25dJykubGVuZ3RoOwp9CmZ1bmN0aW9uIGxhc3RNYXJrZG93blRleHQoKXsKdmFyIG1kcz1kb2N1bWVudC5xdWVyeVNlbGVjdG9yQWxsKCdkaXZbY2xhc3MqPWRzLW1hcmtkb3duXScpOwppZihtZHMubGVuZ3RoPjApcmV0dXJuIG1kc1ttZHMubGVuZ3RoLTFdLmlubmVyVGV4dDsKcmV0dXJuICcnOwp9CnZhciBlbD1maW5kSW5wdXQoKTsKaWYoIWVsKXtSYWluQnJpZGdlLm9uRXJyb3IoJ3RleHRhcmVhIG5vdCBmb3VuZCcpO3JldHVybjt9CnZhciBzZXR0ZXI9T2JqZWN0LmdldE93blByb3BlcnR5RGVzY3JpcHRvcih3aW5kb3cuSFRNTFRleHRBcmVhRWxlbWVudC5wcm90b3R5cGUsJ3ZhbHVlJykuc2V0OwpzZXR0ZXIuY2FsbChlbCxQUk9NUFQpOwplbC5kaXNwYXRjaEV2ZW50KG5ldyBFdmVudCgnaW5wdXQnLHtidWJibGVzOnRydWV9KSk7CmVsLmRpc3BhdGNoRXZlbnQobmV3IEV2ZW50KCdjaGFuZ2UnLHtidWJibGVzOnRydWV9KSk7CnZhciBiYXNlbGluZT1jb3VudE1hcmtkb3duKCk7CnZhciBzdGFydFRzPURhdGUubm93KCk7CnZhciBidG5Ucmllcz0wOwpmdW5jdGlvbiBzdGFydE9ic2VydmUoKXsKdmFyIGxhc3RUZXh0PScnOwp2YXIgbGFzdENoYW5nZVRzPURhdGUubm93KCk7CnZhciBzdGFydGVkPWZhbHNlOwp2YXIgZmluYWxpemVkPWZhbHNlOwpmdW5jdGlvbiBmaW5pc2godGV4dCl7CmlmKGZpbmFsaXplZClyZXR1cm47CmZpbmFsaXplZD10cnVlOwp0cnl7bW8uZGlzY29ubmVjdCgpO31jYXRjaChlKXt9CnRyeXtjbGVhckludGVydmFsKGl2KTt9Y2F0Y2goZSl7fQpSYWluQnJpZGdlLm9uUmVzdWx0KHRleHQpOwp9CnZhciBtbz1uZXcgTXV0YXRpb25PYnNlcnZlcihmdW5jdGlvbigpewppZihmaW5hbGl6ZWQpcmV0dXJuOwp2YXIgbj1jb3VudE1hcmtkb3duKCk7CmlmKCFzdGFydGVkJiZuPmJhc2VsaW5lKXN0YXJ0ZWQ9dHJ1ZTsKaWYoIXN0YXJ0ZWQpcmV0dXJuOwp2YXIgdD1sYXN0TWFya2Rvd25UZXh0KCk7CmlmKHQmJnQhPT1sYXN0VGV4dCl7Cmxhc3RUZXh0PXQ7Cmxhc3RDaGFuZ2VUcz1EYXRlLm5vdygpOwp9Cn0pOwp0cnl7Cm1vLm9ic2VydmUoZG9jdW1lbnQuYm9keSx7Y2hpbGRMaXN0OnRydWUsc3VidHJlZTp0cnVlLGNoYXJhY3RlckRhdGE6dHJ1ZX0pOwp9Y2F0Y2goZSl7fQp2YXIgaXY9c2V0SW50ZXJ2YWwoZnVuY3Rpb24oKXsKaWYoZmluYWxpemVkKXJldHVybjsKaWYoRGF0ZS5ub3coKS1zdGFydFRzPlRJTUVPVVRNUyl7CmNsZWFySW50ZXJ2YWwoaXYpOwp0cnl7bW8uZGlzY29ubmVjdCgpO31jYXRjaChlKXt9ClJhaW5CcmlkZ2Uub25FcnJvcigncmVwbHkgdGltZW91dCcpOwpyZXR1cm47Cn0KdmFyIG49Y291bnRNYXJrZG93bigpOwppZighc3RhcnRlZCYmbj5iYXNlbGluZSlzdGFydGVkPXRydWU7CmlmKCFzdGFydGVkKXJldHVybjsKdmFyIHQ9bGFzdE1hcmtkb3duVGV4dCgpOwppZih0JiZ0IT09bGFzdFRleHQpewpsYXN0VGV4dD10OwpsYXN0Q2hhbmdlVHM9RGF0ZS5ub3coKTsKfQppZihsYXN0VGV4dCYmRGF0ZS5ub3coKS1sYXN0Q2hhbmdlVHM+SURMRURPTkVNUyl7CmZpbmlzaChsYXN0VGV4dCk7Cn0KfSwzMDApOwp9CnZhciBpdkJ0bj1zZXRJbnRlcnZhbChmdW5jdGlvbigpewpidG5UcmllcysrOwppZihEYXRlLm5vdygpLXN0YXJ0VHM+VElNRU9VVE1TKXsKY2xlYXJJbnRlcnZhbChpdkJ0bik7ClJhaW5CcmlkZ2Uub25FcnJvcignc2VuZCBidG4gdGltZW91dCcpOwpyZXR1cm47Cn0KdmFyIGJ0bj1maW5kU2VuZEJ0bigpOwppZihidG4pewpjbGVhckludGVydmFsKGl2QnRuKTsKdHJ5e2J0bi5jbGljaygpO31jYXRjaChlKXtSYWluQnJpZGdlLm9uRXJyb3IoJ2NsaWNrIGZhaWw6ICcrZS5tZXNzYWdlKTtyZXR1cm47fQpzdGFydE9ic2VydmUoKTsKcmV0dXJuOwp9CmlmKGJ0blRyaWVzPj02MCl7CmNsZWFySW50ZXJ2YWwoaXZCdG4pOwplbC5kaXNwYXRjaEV2ZW50KG5ldyBLZXlib2FyZEV2ZW50KCdrZXlkb3duJyx7a2V5OidFbnRlcicsY29kZTonRW50ZXInLGtleUNvZGU6MTMsd2hpY2g6MTMsYnViYmxlczp0cnVlfSkpOwpzdGFydE9ic2VydmUoKTsKfQp9LDEwMCk7Cn0pKCk7"

    private fun decodeJs(b64: String): String {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return String(bytes, Charset.forName("UTF-8"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun init(context: Context) {
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
            wv.loadUrl("https://chat.deepseek.com/")
            webView = wv
        }
    }

    fun getWebView(): WebView? = webView

    fun reset() {
 val wv = webView
 webView = null
 isLoaded = false
 mainHandler.post {
 try { wv?.stopLoading() } catch (e: Exception) {}
 try { wv?.destroy() } catch (e: Exception) {}
 }
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

    fun sendAndWait(prompt: String): String {
        val wv = webView ?: return "ERROR: not initialized"
        var waited = 0L
        while (!isLoaded && waited < 15000L) {
            Thread.sleep(150L)
            waited += 150L
        }
        if (!isLoaded) return "ERROR: webview not loaded after 15s"
        val latch = CountDownLatch(1)
        currentLatch = latch
        currentResult = null
        val q = JSONObject.quote(prompt)
        mainHandler.post {
            val js = decodeJs(BUILD_JS_B64).replace("RAIN_PROMPT_TOKEN", q)
            wv.evaluateJavascript(js, null)
        }
        val ok = latch.await(180, TimeUnit.SECONDS)
        if (!ok) {
            mainHandler.post { currentLatch = null }
            return "ERROR: timeout"
        }
        return currentResult ?: "ERROR: null"
}

    class JSInterface {
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