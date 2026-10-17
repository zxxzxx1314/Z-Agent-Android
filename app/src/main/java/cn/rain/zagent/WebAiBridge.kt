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
    var isLoaded = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentLatch: CountDownLatch? = null
    private var currentResult: String? = null
    private var debugLatch: CountDownLatch? = null
    private var debugResult: String? = null

    private const val DEBUG_JS_B64 = "KGZ1bmN0aW9uKCl7CnZhciBvdXQ9W107Cm91dC5wdXNoKCdVUkw9Jytsb2NhdGlvbi5ocmVmKTsKb3V0LnB1c2goJ1RJVExFPScrZG9jdW1lbnQudGl0bGUpOwp2YXIgdGFzPWRvY3VtZW50LnF1ZXJ5U2VsZWN0b3JBbGwoJ3RleHRhcmVhJyk7Cm91dC5wdXNoKCctLS0gVEVYVEFSRUFTIGNvdW50PScrdGFzLmxlbmd0aCsnIC0tLScpOwpmb3IodmFyIGk9MDtpPHRhcy5sZW5ndGg7aSsrKXt2YXIgdD10YXNbaV07b3V0LnB1c2goJ1snK2krJ10gaWQ9Jyt0LmlkKycgY2xzPScrdC5jbGFzc05hbWUrJyB2aXM9JysodC5vZmZzZXRQYXJlbnQhPT1udWxsKSk7fQp2YXIgY2VzPWRvY3VtZW50LnF1ZXJ5U2VsZWN0b3JBbGwoJ1tjb250ZW50ZWRpdGFibGVdJyk7Cm91dC5wdXNoKCctLS0gQ09OVEVOVEVESVRBQkxFIGNvdW50PScrY2VzLmxlbmd0aCsnIC0tLScpOwpmb3IodmFyIGk9MDtpPGNlcy5sZW5ndGg7aSsrKXt2YXIgZT1jZXNbaV07b3V0LnB1c2goJ1snK2krJ10gdGFnPScrZS50YWdOYW1lKycgY2xzPScrZS5jbGFzc05hbWUrJyB2aXM9JysoZS5vZmZzZXRQYXJlbnQhPT1udWxsKSk7fQp2YXIgYnM9ZG9jdW1lbnQucXVlcnlTZWxlY3RvckFsbCgnYnV0dG9uJyk7Cm91dC5wdXNoKCctLS0gQlVUVE9OUyBjb3VudD0nK2JzLmxlbmd0aCsnIC0tLScpOwpmb3IodmFyIGk9MDtpPE1hdGgubWluKGJzLmxlbmd0aCwyMCk7aSsrKXt2YXIgYj1ic1tpXTtvdXQucHVzaCgnWycraSsnXSB0eXBlPScrYi50eXBlKycgY2xzPScrYi5jbGFzc05hbWUrJyBhcmlhPScrYi5nZXRBdHRyaWJ1dGUoJ2FyaWEtbGFiZWwnKSsnIGRpcz0nK2IuZGlzYWJsZWQpO30KdmFyIHJicz1kb2N1bWVudC5xdWVyeVNlbGVjdG9yQWxsKCdkaXZbcm9sZT1idXR0b25dJyk7Cm91dC5wdXNoKCctLS0gRElWX1JPTEVfQlVUVE9OIGNvdW50PScrcmJzLmxlbmd0aCsnIC0tLScpOwpmb3IodmFyIGk9MDtpPE1hdGgubWluKHJicy5sZW5ndGgsMTUpO2krKyl7dmFyIHI9cmJzW2ldO291dC5wdXNoKCdbJytpKyddIGNscz0nK3IuY2xhc3NOYW1lKycgYXJpYT0nK3IuZ2V0QXR0cmlidXRlKCdhcmlhLWxhYmVsJykrJyBkaXM9JytyLmdldEF0dHJpYnV0ZSgnYXJpYS1kaXNhYmxlZCcpKTt9CnZhciBtY3M9ZG9jdW1lbnQucXVlcnlTZWxlY3RvckFsbCgnZGl2W2NsYXNzKj1tYXJrZG93bl0nKTsKdmFyIG1jczI9ZG9jdW1lbnQucXVlcnlTZWxlY3RvckFsbCgnZGl2W2NsYXNzKj1tZXNzYWdlXScpOwpvdXQucHVzaCgnLS0tIE1FU1NBR0VTIG1kPScrbWNzLmxlbmd0aCsnIG1zZz0nK21jczIubGVuZ3RoKycgLS0tJyk7ClJhaW5CcmlkZ2Uub25EZWJ1ZyhvdXQuam9pbihTdHJpbmcuZnJvbUNoYXJDb2RlKDEwKSkpOwp9KSgpOwo="
    private const val BUILD_JS_B64 = "KGZ1bmN0aW9uKCl7CnZhciBQUk9NUFQ9UFJPTVBUOwpmdW5jdGlvbiBmaW5kSW5wdXQoKXsKdmFyIHQ9ZG9jdW1lbnQucXVlcnlTZWxlY3RvcigndGV4dGFyZWEnKTsKaWYodCYmdC5vZmZzZXRQYXJlbnQhPT1udWxsKXJldHVybiB0Owp2YXIgZXM9ZG9jdW1lbnQucXVlcnlTZWxlY3RvckFsbCgnW2NvbnRlbnRlZGl0YWJsZT10cnVlXScpOwpmb3IodmFyIGk9MDtpPGVzLmxlbmd0aDtpKyspe2lmKGVzW2ldLm9mZnNldFBhcmVudCE9PW51bGwpcmV0dXJuIGVzW2ldO30KcmV0dXJuIG51bGw7Cn0KZnVuY3Rpb24gZmluZFNlbmRCdG4oKXsKdmFyIGJzPWRvY3VtZW50LnF1ZXJ5U2VsZWN0b3JBbGwoJ2Rpdltyb2xlPWJ1dHRvbl0sYnV0dG9uJyk7CnZhciBjYW5kPVtdOwpmb3IodmFyIGk9MDtpPGJzLmxlbmd0aDtpKyspewp2YXIgYj1ic1tpXTsKaWYoYi5nZXRBdHRyaWJ1dGUoJ2FyaWEtZGlzYWJsZWQnKT09PSd0cnVlJyljb250aW51ZTsKaWYoYi5kaXNhYmxlZCljb250aW51ZTsKdmFyIHN2Zz1iLnF1ZXJ5U2VsZWN0b3IoJ3N2ZycpOwppZihzdmcpY2FuZC5wdXNoKGIpOwp9CnJldHVybiBjYW5kLmxlbmd0aD4wP2NhbmRbY2FuZC5sZW5ndGgtMV06bnVsbDsKfQp2YXIgdHJpZXM9MDsKdmFyIGl2MD1zZXRJbnRlcnZhbChmdW5jdGlvbigpewp0cmllcysrOwp2YXIgZWw9ZmluZElucHV0KCk7CmlmKCFlbCl7aWYodHJpZXM+NjApe2NsZWFySW50ZXJ2YWwoaXYwKTtSYWluQnJpZGdlLm9uRXJyb3IoJ2lucHV0IG5vdCBmb3VuZCcpO31yZXR1cm47fQpjbGVhckludGVydmFsKGl2MCk7CnRyeXsKaWYoZWwudGFnTmFtZS50b0xvd2VyQ2FzZSgpPT09J3RleHRhcmVhJyl7CnZhciBzPU9iamVjdC5nZXRPd25Qcm9wZXJ0eURlc2NyaXB0b3Iod2luZG93LkhUTUxUZXh0QXJlYUVsZW1lbnQucHJvdG90eXBlLCd2YWx1ZScpLnNldDsKcy5jYWxsKGVsLFBST01QVCk7CmVsLmRpc3BhdGNoRXZlbnQobmV3IEV2ZW50KCdpbnB1dCcse2J1YmJsZXM6dHJ1ZX0pKTsKfWVsc2V7CmVsLmZvY3VzKCk7CmVsLmlubmVyVGV4dD1QUk9NUFQ7CmVsLmRpc3BhdGNoRXZlbnQobmV3IElucHV0RXZlbnQoJ2lucHV0Jyx7YnViYmxlczp0cnVlfSkpOwp9Cn1jYXRjaChlKXtSYWluQnJpZGdlLm9uRXJyb3IoJ3NldCBpbnB1dCBmYWlsOiAnK2UubWVzc2FnZSk7cmV0dXJuO30Kc2V0VGltZW91dChmdW5jdGlvbigpewp2YXIgYnRuPWZpbmRTZW5kQnRuKCk7CmlmKGJ0bil7YnRuLmNsaWNrKCk7fQplbHNle2VsLmRpc3BhdGNoRXZlbnQobmV3IEtleWJvYXJkRXZlbnQoJ2tleWRvd24nLHtrZXk6J0VudGVyJyxjb2RlOidFbnRlcicsa2V5Q29kZToxMyx3aGljaDoxMyxidWJibGVzOnRydWV9KSk7fQp2YXIgYmFzZWxpbmU9ZG9jdW1lbnQucXVlcnlTZWxlY3RvckFsbCgnZGl2W2NsYXNzKj1tYXJrZG93bl0nKS5sZW5ndGg7CnZhciBsYXN0PScnOwp2YXIgc2FtZT0wOwp2YXIgc3RhcnRlZD1mYWxzZTsKdmFyIGl2PXNldEludGVydmFsKGZ1bmN0aW9uKCl7CnZhciBtZHM9ZG9jdW1lbnQucXVlcnlTZWxlY3RvckFsbCgnZGl2W2NsYXNzKj1tYXJrZG93bl0nKTsKaWYoIXN0YXJ0ZWQmJm1kcy5sZW5ndGg+YmFzZWxpbmUpc3RhcnRlZD10cnVlOwppZighc3RhcnRlZClyZXR1cm47CnZhciB0PW1kcy5sZW5ndGg+MD9tZHNbbWRzLmxlbmd0aC0xXS5pbm5lclRleHQ6Jyc7CmlmKHQubGVuZ3RoPjAmJnQ9PT1sYXN0KXtzYW1lKys7aWYoc2FtZT49NSl7Y2xlYXJJbnRlcnZhbChpdik7UmFpbkJyaWRnZS5vblJlc3VsdCh0KTt9fQplbHNle3NhbWU9MDtsYXN0PXQ7fQp9LDUwMCk7Cn0sNDAwKTsKfSw1MDApOwp9KSgpOwo="

    private fun decodeJs(b64: String): String {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return String(bytes, Charset.forName("UTF-8"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun init(context: Context) {
        if (webView != null) return
        mainHandler.post {
            val wv = WebView(context)
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
        val latch = CountDownLatch(1)
        currentLatch = latch
        currentResult = null
        val q = JSONObject.quote(prompt)
        mainHandler.post {
            val js = decodeJs(BUILD_JS_B64).replace("PROMPT", q)
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