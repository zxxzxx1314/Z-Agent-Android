package cn.rain.zagent

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object WebAiBridge {
private var webView: WebView? = null
var isLoaded = false
private val mainHandler = Handler(Looper.getMainLooper())
private var currentLatch: CountDownLatch? = null
private var currentResult: String? = null

@SuppressLint("SetJavaScriptEnabled")
fun init(context: Context) {
if (webView != null) return
mainHandler.post {
val wv = WebView(context)
val st = wv.settings
st.javaScriptEnabled = true
st.domStorageEnabled = true
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

fun sendAndWait(prompt: String): String {
val wv = webView ?: return "ERROR: not initialized"
val latch = CountDownLatch(1)
currentLatch = latch
currentResult = null
val q = JSONObject.quote(prompt)

mainHandler.post {
val js = buildJs(q)
wv.evaluateJavascript(js, null)
}

val ok = latch.await(180, TimeUnit.SECONDS)
if (!ok) {
mainHandler.post { currentLatch = null }
return "ERROR: timeout"
}
return currentResult ?: "ERROR: null"
}

private fun buildJs(qPrompt: String): String {
val sb = StringBuilder()
sb.append("(function(){")
sb.append("var el=document.querySelector('textarea');")
sb.append("if(!el){var a=document.querySelectorAll('[contenteditable=true]');if(a.length>0)el=a[0];}")
sb.append("if(!el){RainBridge.onError('input not found');return;}")
sb.append("if(el.tagName.toLowerCase()==='textarea'){")
sb.append("var s=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;")
sb.append("s.call(el,").append(qPrompt).append(");")
sb.append("el.dispatchEvent(new Event('input',{bubbles:true}));")
sb.append("}else{el.innerText=").append(qPrompt).append(";el.dispatchEvent(new Event('input',{bubbles:true}));}")
sb.append("setTimeout(function(){")
sb.append("var b=document.querySelector('div[role=button]:not([aria-disabled=true])');")
sb.append("if(b&&b.querySelector('svg')){b.click();}")
sb.append("else{")
sb.append("el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));")
sb.append("el.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));")
sb.append("}},300);")
sb.append("var last='',same=0;")
sb.append("var iv=setInterval(function(){")
sb.append("var md=document.querySelectorAll('.ds-markdown');")
sb.append("var t='';")
sb.append("if(md.length>0){t=md[md.length-1].innerText;}")
sb.append("else{var m=document.querySelectorAll('div[class*=message]');if(m.length>0)t=m[m.length-1].innerText;}")
sb.append("if(t===last&&t.length>0){same++;if(same>=3){clearInterval(iv);RainBridge.onResult(t);}}")
sb.append("else{same=0;last=t;}")
sb.append("},600);")
sb.append("})();")
return sb.toString()
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
}
}
