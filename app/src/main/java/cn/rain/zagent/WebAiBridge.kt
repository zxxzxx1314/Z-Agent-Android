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
    private var debugLatch: CountDownLatch? = null
    private var debugResult: String? = null

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
            val js = debugJs()
            wv.evaluateJavascript(js, null)
        }
        val ok = latch.await(15, TimeUnit.SECONDS)
        if (!ok) return "ERROR: debug timeout"
        return debugResult ?: "ERROR: null"
    }

    private fun debugJs(): String {
        val sb = StringBuilder()
        sb.append("(function(){")
        sb.append("var out=[];")
        sb.append("out.push('URL='+location.href);")
        sb.append("out.push('TITLE='+document.title);")
        out.push('--- TEXTAREAS ---')
        sb.append("var tas=document.querySelectorAll('textarea');out.push('count='+tas.length);for(var i=0;i<tas.length;i++){var t=tas[i];out.push('['+i+'] id='+t.id+' cls='+t.className+' ph='+(t.placeholder||'')+' vis='+(t.offsetParent!==null));}")
        sb.append("out.push('--- CONTENTEDITABLE ---');var ces=document.querySelectorAll('[contenteditable]');out.push('count='+ces.length);for(var i=0;i<ces.length;i++){var e=ces[i];out.push('['+i+'] tag='+e.tagName+' cls='+e.className+' ce='+e.getAttribute('contenteditable')+' vis='+(e.offsetParent!==null));}")
        sb.append("out.push('--- BUTTONS ---');var bs=document.querySelectorAll('button');out.push('count='+bs.length);for(var i=0;i<Math.min(bs.length,20);i++){var b=bs[i];out.push('['+i+'] type='+b.type+' cls='+b.className+' aria='+b.getAttribute('aria-label')+' txt='+(b.innerText||'').substring(0,30)+' dis='+b.disabled);}")
        sb.append("out.push('--- DIV role=button ---');var rbs=document.querySelectorAll('div[role=button]');out.push('count='+rbs.length);for(var i=0;i<Math.min(rbs.length,15);i++){var r=rbs[i];out.push('['+i+'] cls='+r.className+' aria='+r.getAttribute('aria-label')+' dis='+r.getAttribute('aria-disabled'));}")
        sb.append("out.push('--- MESSAGE CONTAINERS ---');var mcs=document.querySelectorAll('div[class*=markdown]');out.push('md_count='+mcs.length);var mcs2=document.querySelectorAll('div[class*=message]');out.push('msg_count='+mcs2.length);")
        sb.append("RainBridge.onDebug(out.join('
'));")
        sb.append("})();")
        return sb.toString()
    }

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
        sb.append("function findInput(){")
        sb.append("var t=document.querySelector('textarea');if(t&&t.offsetParent!==null)return t;")
        sb.append("var es=document.querySelectorAll('[contenteditable=true]');for(var i=0;i<es.length;i++){if(es[i].offsetParent!==null)return es[i];}return null;}")
        sb.append("function findSendBtn(){")
        sb.append("var bs=document.querySelectorAll('div[role=button],button');var cand=[];for(var i=0;i<bs.length;i++){var b=bs[i];if(b.getAttribute('aria-disabled')==='true')continue;if(b.disabled)continue;var svg=b.querySelector('svg');if(svg)cand.push(b);}return cand.length>0?cand[cand.length-1]:null;}")
        sb.append("var tries=0;var maxTries=60;var iv0=setInterval(function(){")
        sb.append("tries++;var el=findInput();if(!el){if(tries>maxTries){clearInterval(iv0);RainBridge.onError('input not found after 30s');}return;}clearInterval(iv0);")
        sb.append("try{")
        sb.append("if(el.tagName.toLowerCase()==='textarea'){var s=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;s.call(el,");el.dispatchEvent(new Event('input',{bubbles:true}));}else{el.focus();el.innerText=";el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:"}));}catch(e){RainBridge.onError('set input fail: '+e.message);return;}")
        sb.append("setTimeout(function(){")
        sb.append("var btn=findSendBtn();if(btn){btn.click();}else{el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));}")
        sb.append("var baseline=document.querySelectorAll('div[class*=markdown]').length;var last='';var same=0;var started=false;")
        sb.append("var iv=setInterval(function(){")
        sb.append("var mds=document.querySelectorAll('div[class*=markdown]');")
        sb.append("if(!started&&mds.length>baseline)started=true;")
        sb.append("if(!started)return;var t=mds.length>0?mds[mds.length-1].innerText:'';if(t.length>0&&t===last){same++;if(same>=5){clearInterval(iv);RainBridge.onResult(t);}}else{same=0;last=t;}")
        sb.append("},500);")
        sb.append("},400);")
        sb.append("},500);")
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
        @JavascriptInterface
        fun onDebug(text: String) {
            debugResult = text
            debugLatch?.countDown()
        }
    }
}