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
    private var appContext: android.content.Context? = null
    @Volatile private var currentUrl: String = "https://chat.deepseek.com/"
    @Volatile var isLoaded = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentLatch: CountDownLatch? = null
    private var currentResult: String? = null
    private var debugLatch: CountDownLatch? = null
    private var debugResult: String? = null

    private const val DEBUG_JS_B64 = "KGZ1bmN0aW9uKCl7CnZhciBvdXQ9W107Cm91dC5wdXNoKCdVUkw9Jytsb2NhdGlvbi5ocmVmKTsKb3V0LnB1c2goJ1RJVExFPScrZG9jdW1lbnQudGl0bGUpOwp2YXIgdGFzPWRvY3VtZW50LnF1ZXJ5U2VsZWN0b3JBbGwoJ3RleHRhcmVhJyk7Cm91dC5wdXNoKCctLS0gVEVYVEFSRUFTIGNvdW50PScrdGFzLmxlbmd0aCsnIC0tLScpOwpmb3IodmFyIGk9MDtpPHRhcy5sZW5ndGg7aSsrKXt2YXIgdD10YXNbaV07b3V0LnB1c2goJ1snK2krJ10gdmlzPScrKHQub2Zmc2V0UGFyZW50IT09bnVsbCkrJyB2YWxfbGVuPScrdC52YWx1ZS5sZW5ndGgpO30KdmFyIGRzYj1kb2N1bWVudC5xdWVyeVNlbGVjdG9yQWxsKCdkaXZbY2xhc3MqPWRzLWJ1dHRvbl0nKTsKb3V0LnB1c2goJy0tLSBEU19CVVRUT05TIGNvdW50PScrZHNiLmxlbmd0aCsnIC0tLScpOwpmb3IodmFyIGk9MDtpPE1hdGgubWluKGRzYi5sZW5ndGgsMjApO2krKyl7dmFyIGI9ZHNiW2ldO3ZhciBjbHM9Yi5jbGFzc05hbWU7b3V0LnB1c2goJ1snK2krJ10gY2lyY2xlPScrKGNscy5pbmRleE9mKCdjaXJjbGUnKT49MCkrJyBkaXNhYmxlZD0nKyhjbHMuaW5kZXhPZignZGlzYWJsZWQnKT49MCkrJyBwcmltYXJ5PScrKGNscy5pbmRleE9mKCdwcmltYXJ5Jyk+PTApKTt9CnZhciBtZHM9ZG9jdW1lbnQucXVlcnlTZWxlY3RvckFsbCgnZGl2W2NsYXNzKj1kcy1tYXJrZG93bl0nKTsKb3V0LnB1c2goJy0tLSBEU19NQVJLRE9XTiBjb3VudD0nK21kcy5sZW5ndGgrJyAtLS0nKTsKb3V0LnB1c2goJ0JPRFlfVEVYVF9MRU49Jytkb2N1bWVudC5ib2R5LmlubmVyVGV4dC5sZW5ndGgpOwpSYWluQnJpZGdlLm9uRGVidWcob3V0LmpvaW4oU3RyaW5nLmZyb21DaGFyQ29kZSgxMCkpKTsKfSkoKTsK"
    private const val BUILD_JS_B64 = "KGZ1bmN0aW9uKCl7CnZhciBQUk9NUFQ9UkFJTl9QUk9NUFRfVE9LRU47CnZhciBUSU1FT1VUTVM9MTIwMDAwOwp2YXIgSURMRURPTkVNUz00MDAwOwoKZnVuY3Rpb24gaXNWaXNpYmxlKGUpewppZighZSkgcmV0dXJuIGZhbHNlOwp2YXIgciA9IGUuZ2V0Qm91bmRpbmdDbGllbnRSZWN0KCk7CnJldHVybiByLndpZHRoID4gMCAmJiByLmhlaWdodCA+IDAgJiYgZS5vZmZzZXRQYXJlbnQgIT09IG51bGw7Cn0KCmZ1bmN0aW9uIGZpbmRJbnB1dCgpewp2YXIgdGFzID0gZG9jdW1lbnQucXVlcnlTZWxlY3RvckFsbCgndGV4dGFyZWEnKTsKZm9yKHZhciBpPTA7aTx0YXMubGVuZ3RoO2krKyl7IGlmKGlzVmlzaWJsZSh0YXNbaV0pKSByZXR1cm4ge2VsOnRhc1tpXSx0eXBlOid0ZXh0YXJlYSd9OyB9CnZhciBjZXMgPSBkb2N1bWVudC5xdWVyeVNlbGVjdG9yQWxsKCdkaXZbY29udGVudGVkaXRhYmxlXScpOwpmb3IodmFyIGo9MDtqPGNlcy5sZW5ndGg7aisrKXsgaWYoaXNWaXNpYmxlKGNlc1tqXSkpIHJldHVybiB7ZWw6Y2VzW2pdLHR5cGU6J2NvbnRlbnRlZGl0YWJsZSd9OyB9CnJldHVybiBudWxsOwp9CgpmdW5jdGlvbiBzY29yZVNlbmRDYW5kaWRhdGUoZSwgaW5wdXRFbCl7CnZhciBzY29yZSA9IDA7CnZhciBjbHMgPSAoZS5jbGFzc05hbWV8fCcnKSArICcgJzsKdmFyIHR4dCA9IChlLmlubmVyVGV4dHx8ZS5nZXRBdHRyaWJ1dGUoJ2FyaWEtbGFiZWwnKXx8JycpLnRvTG93ZXJDYXNlKCk7CmlmKGNscy5pbmRleE9mKCdkcy1idXR0b24tLWNpcmNsZScpPj0wKSBzY29yZSArPSAxMDA7CmlmKGNscy5pbmRleE9mKCdjaXJjbGUnKT49MCkgc2NvcmUgKz0gNTA7CmlmKGNscy5pbmRleE9mKCdwcmltYXJ5Jyk+PTApIHNjb3JlICs9IDMwOwppZihjbHMuaW5kZXhPZignc2VuZCcpPj0wKSBzY29yZSArPSA4MDsKaWYodHh0LmluZGV4T2YoJ3NlbmQnKT49MCB8fCB0eHQuaW5kZXhPZign5Y+R6YCBJyk+PTApIHNjb3JlICs9IDgwOwp2YXIgZGlzID0gY2xzLmluZGV4T2YoJ2Rpc2FibGVkJyk+PTAgfHwgZS5kaXNhYmxlZDsKaWYoZGlzKSBzY29yZSAtPSAyMDA7CmlmKGlucHV0RWwpewp2YXIgaXIgPSBpbnB1dEVsLmdldEJvdW5kaW5nQ2xpZW50UmVjdCgpOwp2YXIgZXIgPSBlLmdldEJvdW5kaW5nQ2xpZW50UmVjdCgpOwp2YXIgZHggPSBNYXRoLmFicyhpci5yaWdodCAtIGVyLmxlZnQpOwp2YXIgZHkgPSBNYXRoLmFicygoaXIudG9wK2lyLmJvdHRvbSkvMiAtIChlci50b3ArZXIuYm90dG9tKS8yKTsKaWYoZHkgPCA2MCkgc2NvcmUgKz0gNDA7CmlmKGR4IDwgMTAwKSBzY29yZSArPSAyMDsKfQpyZXR1cm4gc2NvcmU7Cn0KCmZ1bmN0aW9uIGZpbmRTZW5kQnRuKGlucHV0RWwpewp2YXIgZXhhY3QgPSBkb2N1bWVudC5xdWVyeVNlbGVjdG9yKCdkaXYuZHMtYnV0dG9uLS1jaXJjbGU6bm90KC5kcy1idXR0b24tLWRpc2FibGVkKScpOwppZihleGFjdCAmJiBpc1Zpc2libGUoZXhhY3QpKSByZXR1cm4gZXhhY3Q7CnZhciBjYW5kcyA9IGRvY3VtZW50LnF1ZXJ5U2VsZWN0b3JBbGwoJ2J1dHRvbiwgZGl2W3JvbGU9ImJ1dHRvbiJdLCBkaXZbY2xhc3MqPWJ1dHRvbl0sIGRpdltjbGFzcyo9YnRuXScpOwp2YXIgYmVzdCA9IG51bGwsIGJlc3RTY29yZSA9IDA7CmZvcih2YXIgaT0wO2k8Y2FuZHMubGVuZ3RoO2krKyl7CnZhciBjID0gY2FuZHNbaV07CmlmKCFpc1Zpc2libGUoYykpIGNvbnRpbnVlOwp2YXIgcyA9IHNjb3JlU2VuZENhbmRpZGF0ZShjLCBpbnB1dEVsKTsKaWYocyA+IGJlc3RTY29yZSl7IGJlc3RTY29yZSA9IHM7IGJlc3QgPSBjOyB9Cn0KcmV0dXJuIGJlc3RTY29yZSA+PSA0MCA/IGJlc3QgOiBudWxsOwp9CgpmdW5jdGlvbiBjb2xsZWN0QW5zd2VyQ29udGFpbmVycygpewp2YXIgb3V0ID0gW107CnZhciBtZHMgPSBkb2N1bWVudC5xdWVyeVNlbGVjdG9yQWxsKCdkaXZbY2xhc3MqPWRzLW1hcmtkb3duXSwgZGl2W2NsYXNzKj1tYXJrZG93bi1ib2R5XSwgZGl2W2NsYXNzKj1tYXJrZG93bl0sIGRpdltjbGFzcyo9bWVzc2FnZS1jb250ZW50XSwgZGl2W2NsYXNzKj1hc3Npc3RhbnRdJyk7CmZvcih2YXIgaT0wO2k8bWRzLmxlbmd0aDtpKyspewppZihpc1Zpc2libGUobWRzW2ldKSkgb3V0LnB1c2gobWRzW2ldKTsKfQpyZXR1cm4gb3V0Owp9CgpmdW5jdGlvbiBjb3VudE1hcmtkb3duKCl7IHJldHVybiBjb2xsZWN0QW5zd2VyQ29udGFpbmVycygpLmxlbmd0aDsgfQoKZnVuY3Rpb24gbGFzdE1hcmtkb3duVGV4dCgpewp2YXIgY3MgPSBjb2xsZWN0QW5zd2VyQ29udGFpbmVycygpOwppZihjcy5sZW5ndGggPiAwKSByZXR1cm4gY3NbY3MubGVuZ3RoLTFdLmlubmVyVGV4dDsKcmV0dXJuICcnOwp9Cgp2YXIgaW5wID0gZmluZElucHV0KCk7CmlmKCFpbnApeyBSYWluQnJpZGdlLm9uRXJyb3IoJ2lucHV0IG5vdCBmb3VuZCcpOyByZXR1cm47IH0KdmFyIGVsID0gaW5wLmVsOwoKaWYoaW5wLnR5cGUgPT09ICd0ZXh0YXJlYScpewp2YXIgc2V0dGVyID0gT2JqZWN0LmdldE93blByb3BlcnR5RGVzY3JpcHRvcih3aW5kb3cuSFRNTFRleHRBcmVhRWxlbWVudC5wcm90b3R5cGUsJ3ZhbHVlJykuc2V0OwpzZXR0ZXIuY2FsbChlbCwgUFJPTVBUKTsKfSBlbHNlIHsKZWwuZm9jdXMoKTsKZWwuaW5uZXJUZXh0ID0gUFJPTVBUOwp9CgplbC5kaXNwYXRjaEV2ZW50KG5ldyBFdmVudCgnaW5wdXQnLHtidWJibGVzOnRydWV9KSk7CmVsLmRpc3BhdGNoRXZlbnQobmV3IEV2ZW50KCdjaGFuZ2UnLHtidWJibGVzOnRydWV9KSk7CmVsLmRpc3BhdGNoRXZlbnQobmV3IEV2ZW50KCdrZXl1cCcse2J1YmJsZXM6dHJ1ZX0pKTsKCnZhciBiYXNlbGluZSA9IGNvdW50TWFya2Rvd24oKTsKdmFyIHN0YXJ0VHMgPSBEYXRlLm5vdygpOwp2YXIgYnRuVHJpZXMgPSAwOwoKZnVuY3Rpb24gc3RhcnRPYnNlcnZlKCl7CnZhciBsYXN0VGV4dCA9ICcnOwp2YXIgbGFzdENoYW5nZVRzID0gRGF0ZS5ub3coKTsKdmFyIHN0YXJ0ZWQgPSBmYWxzZTsKdmFyIGZpbmFsaXplZCA9IGZhbHNlOwpmdW5jdGlvbiBmaW5pc2godGV4dCl7CmlmKGZpbmFsaXplZCkgcmV0dXJuOwpmaW5hbGl6ZWQgPSB0cnVlOwp0cnl7IG1vLmRpc2Nvbm5lY3QoKTsgfWNhdGNoKGUpe30KdHJ5eyBjbGVhckludGVydmFsKGl2KTsgfWNhdGNoKGUpe30KUmFpbkJyaWRnZS5vblJlc3VsdCh0ZXh0KTsKfQp2YXIgbW8gPSBuZXcgTXV0YXRpb25PYnNlcnZlcihmdW5jdGlvbigpewppZihmaW5hbGl6ZWQpIHJldHVybjsKdmFyIG4gPSBjb3VudE1hcmtkb3duKCk7CmlmKCFzdGFydGVkICYmIG4gPiBiYXNlbGluZSkgc3RhcnRlZCA9IHRydWU7CmlmKCFzdGFydGVkKSByZXR1cm47CnZhciB0ID0gbGFzdE1hcmtkb3duVGV4dCgpOwppZih0ICYmIHQgIT09IGxhc3RUZXh0KXsgbGFzdFRleHQgPSB0OyBsYXN0Q2hhbmdlVHMgPSBEYXRlLm5vdygpOyB9Cn0pOwp0cnl7IG1vLm9ic2VydmUoZG9jdW1lbnQuYm9keSx7Y2hpbGRMaXN0OnRydWUsc3VidHJlZTp0cnVlLGNoYXJhY3RlckRhdGE6dHJ1ZX0pOyB9Y2F0Y2goZSl7fQp2YXIgaXYgPSBzZXRJbnRlcnZhbChmdW5jdGlvbigpewppZihmaW5hbGl6ZWQpIHJldHVybjsKaWYoRGF0ZS5ub3coKS1zdGFydFRzID4gVElNRU9VVE1TKXsKY2xlYXJJbnRlcnZhbChpdik7CnRyeXsgbW8uZGlzY29ubmVjdCgpOyB9Y2F0Y2goZSl7fQpSYWluQnJpZGdlLm9uRXJyb3IoJ3JlcGx5IHRpbWVvdXQnKTsKcmV0dXJuOwp9CnZhciBuID0gY291bnRNYXJrZG93bigpOwppZighc3RhcnRlZCAmJiBuID4gYmFzZWxpbmUpIHN0YXJ0ZWQgPSB0cnVlOwppZighc3RhcnRlZCkgcmV0dXJuOwp2YXIgdCA9IGxhc3RNYXJrZG93blRleHQoKTsKaWYodCAmJiB0ICE9PSBsYXN0VGV4dCl7IGxhc3RUZXh0ID0gdDsgbGFzdENoYW5nZVRzID0gRGF0ZS5ub3coKTsgfQppZihsYXN0VGV4dCAmJiBEYXRlLm5vdygpLWxhc3RDaGFuZ2VUcyA+IElETEVET05FTVMpeyBmaW5pc2gobGFzdFRleHQpOyB9Cn0sIDMwMCk7Cn0KCnZhciBpdkJ0biA9IHNldEludGVydmFsKGZ1bmN0aW9uKCl7CmJ0blRyaWVzKys7CmlmKERhdGUubm93KCktc3RhcnRUcyA+IFRJTUVPVVRNUyl7CmNsZWFySW50ZXJ2YWwoaXZCdG4pOwpSYWluQnJpZGdlLm9uRXJyb3IoJ3NlbmQgYnRuIHRpbWVvdXQnKTsKcmV0dXJuOwp9CnZhciBidG4gPSBmaW5kU2VuZEJ0bihlbCk7CmlmKGJ0bil7CmNsZWFySW50ZXJ2YWwoaXZCdG4pOwp0cnl7IGJ0bi5jbGljaygpOyB9Y2F0Y2goZSl7IFJhaW5CcmlkZ2Uub25FcnJvcignY2xpY2sgZmFpbDogJyArIGUubWVzc2FnZSk7IHJldHVybjsgfQpzdGFydE9ic2VydmUoKTsKcmV0dXJuOwp9CmlmKGJ0blRyaWVzID49IDYwKXsKY2xlYXJJbnRlcnZhbChpdkJ0bik7CmVsLmRpc3BhdGNoRXZlbnQobmV3IEtleWJvYXJkRXZlbnQoJ2tleWRvd24nLHtrZXk6J0VudGVyJyxjb2RlOidFbnRlcicsa2V5Q29kZToxMyx3aGljaDoxMyxidWJibGVzOnRydWV9KSk7CnN0YXJ0T2JzZXJ2ZSgpOwp9Cn0sIDEwMCk7Cn0pKCk7Cg=="KGZ1bmN0aW9uKCl7CnZhciBQUk9NUFQ9UkFJTl9QUk9NUFRfVE9LRU47CnZhciBUSU1FT1VUTVM9MTIwMDAwOwp2YXIgSURMRURPTkVNUz00MDAwOwpmdW5jdGlvbiBmaW5kSW5wdXQoKXsKdmFyIHQ9ZG9jdW1lbnQucXVlcnlTZWxlY3RvcigndGV4dGFyZWEnKTsKaWYodCYmdC5vZmZzZXRQYXJlbnQhPT1udWxsKXJldHVybiB0OwpyZXR1cm4gbnVsbDsKfQpmdW5jdGlvbiBmaW5kU2VuZEJ0bigpewp2YXIgYnRuPWRvY3VtZW50LnF1ZXJ5U2VsZWN0b3IoJ2Rpdi5kcy1idXR0b24tLWNpcmNsZTpub3QoLmRzLWJ1dHRvbi0tZGlzYWJsZWQpJyk7CmlmKGJ0bilyZXR1cm4gYnRuOwp2YXIgYWxsPWRvY3VtZW50LnF1ZXJ5U2VsZWN0b3JBbGwoJ2RpdltjbGFzcyo9ZHMtYnV0dG9uXScpOwpmb3IodmFyIGk9YWxsLmxlbmd0aC0xO2k+PTA7aS0tKXsKdmFyIGI9YWxsW2ldOwp2YXIgY2xzPWIuY2xhc3NOYW1lfHwnJzsKaWYoY2xzLmluZGV4T2YoJ2NpcmNsZScpPj0wJiZjbHMuaW5kZXhPZignZGlzYWJsZWQnKTwwKXJldHVybiBiOwp9CnJldHVybiBudWxsOwp9CmZ1bmN0aW9uIGNvdW50TWFya2Rvd24oKXsKcmV0dXJuIGRvY3VtZW50LnF1ZXJ5U2VsZWN0b3JBbGwoJ2RpdltjbGFzcyo9ZHMtbWFya2Rvd25dJykubGVuZ3RoOwp9CmZ1bmN0aW9uIGxhc3RNYXJrZG93blRleHQoKXsKdmFyIG1kcz1kb2N1bWVudC5xdWVyeVNlbGVjdG9yQWxsKCdkaXZbY2xhc3MqPWRzLW1hcmtkb3duXScpOwppZihtZHMubGVuZ3RoPjApcmV0dXJuIG1kc1ttZHMubGVuZ3RoLTFdLmlubmVyVGV4dDsKcmV0dXJuICcnOwp9CnZhciBlbD1maW5kSW5wdXQoKTsKaWYoIWVsKXtSYWluQnJpZGdlLm9uRXJyb3IoJ3RleHRhcmVhIG5vdCBmb3VuZCcpO3JldHVybjt9CnZhciBzZXR0ZXI9T2JqZWN0LmdldE93blByb3BlcnR5RGVzY3JpcHRvcih3aW5kb3cuSFRNTFRleHRBcmVhRWxlbWVudC5wcm90b3R5cGUsJ3ZhbHVlJykuc2V0OwpzZXR0ZXIuY2FsbChlbCxQUk9NUFQpOwplbC5kaXNwYXRjaEV2ZW50KG5ldyBFdmVudCgnaW5wdXQnLHtidWJibGVzOnRydWV9KSk7CmVsLmRpc3BhdGNoRXZlbnQobmV3IEV2ZW50KCdjaGFuZ2UnLHtidWJibGVzOnRydWV9KSk7CnZhciBiYXNlbGluZT1jb3VudE1hcmtkb3duKCk7CnZhciBzdGFydFRzPURhdGUubm93KCk7CnZhciBidG5Ucmllcz0wOwpmdW5jdGlvbiBzdGFydE9ic2VydmUoKXsKdmFyIGxhc3RUZXh0PScnOwp2YXIgbGFzdENoYW5nZVRzPURhdGUubm93KCk7CnZhciBzdGFydGVkPWZhbHNlOwp2YXIgZmluYWxpemVkPWZhbHNlOwpmdW5jdGlvbiBmaW5pc2godGV4dCl7CmlmKGZpbmFsaXplZClyZXR1cm47CmZpbmFsaXplZD10cnVlOwp0cnl7bW8uZGlzY29ubmVjdCgpO31jYXRjaChlKXt9CnRyeXtjbGVhckludGVydmFsKGl2KTt9Y2F0Y2goZSl7fQpSYWluQnJpZGdlLm9uUmVzdWx0KHRleHQpOwp9CnZhciBtbz1uZXcgTXV0YXRpb25PYnNlcnZlcihmdW5jdGlvbigpewppZihmaW5hbGl6ZWQpcmV0dXJuOwp2YXIgbj1jb3VudE1hcmtkb3duKCk7CmlmKCFzdGFydGVkJiZuPmJhc2VsaW5lKXN0YXJ0ZWQ9dHJ1ZTsKaWYoIXN0YXJ0ZWQpcmV0dXJuOwp2YXIgdD1sYXN0TWFya2Rvd25UZXh0KCk7CmlmKHQmJnQhPT1sYXN0VGV4dCl7Cmxhc3RUZXh0PXQ7Cmxhc3RDaGFuZ2VUcz1EYXRlLm5vdygpOwp9Cn0pOwp0cnl7Cm1vLm9ic2VydmUoZG9jdW1lbnQuYm9keSx7Y2hpbGRMaXN0OnRydWUsc3VidHJlZTp0cnVlLGNoYXJhY3RlckRhdGE6dHJ1ZX0pOwp9Y2F0Y2goZSl7fQp2YXIgaXY9c2V0SW50ZXJ2YWwoZnVuY3Rpb24oKXsKaWYoZmluYWxpemVkKXJldHVybjsKaWYoRGF0ZS5ub3coKS1zdGFydFRzPlRJTUVPVVRNUyl7CmNsZWFySW50ZXJ2YWwoaXYpOwp0cnl7bW8uZGlzY29ubmVjdCgpO31jYXRjaChlKXt9ClJhaW5CcmlkZ2Uub25FcnJvcigncmVwbHkgdGltZW91dCcpOwpyZXR1cm47Cn0KdmFyIG49Y291bnRNYXJrZG93bigpOwppZighc3RhcnRlZCYmbj5iYXNlbGluZSlzdGFydGVkPXRydWU7CmlmKCFzdGFydGVkKXJldHVybjsKdmFyIHQ9bGFzdE1hcmtkb3duVGV4dCgpOwppZih0JiZ0IT09bGFzdFRleHQpewpsYXN0VGV4dD10OwpsYXN0Q2hhbmdlVHM9RGF0ZS5ub3coKTsKfQppZihsYXN0VGV4dCYmRGF0ZS5ub3coKS1sYXN0Q2hhbmdlVHM+SURMRURPTkVNUyl7CmZpbmlzaChsYXN0VGV4dCk7Cn0KfSwzMDApOwp9CnZhciBpdkJ0bj1zZXRJbnRlcnZhbChmdW5jdGlvbigpewpidG5UcmllcysrOwppZihEYXRlLm5vdygpLXN0YXJ0VHM+VElNRU9VVE1TKXsKY2xlYXJJbnRlcnZhbChpdkJ0bik7ClJhaW5CcmlkZ2Uub25FcnJvcignc2VuZCBidG4gdGltZW91dCcpOwpyZXR1cm47Cn0KdmFyIGJ0bj1maW5kU2VuZEJ0bigpOwppZihidG4pewpjbGVhckludGVydmFsKGl2QnRuKTsKdHJ5e2J0bi5jbGljaygpO31jYXRjaChlKXtSYWluQnJpZGdlLm9uRXJyb3IoJ2NsaWNrIGZhaWw6ICcrZS5tZXNzYWdlKTtyZXR1cm47fQpzdGFydE9ic2VydmUoKTsKcmV0dXJuOwp9CmlmKGJ0blRyaWVzPj02MCl7CmNsZWFySW50ZXJ2YWwoaXZCdG4pOwplbC5kaXNwYXRjaEV2ZW50KG5ldyBLZXlib2FyZEV2ZW50KCdrZXlkb3duJyx7a2V5OidFbnRlcicsY29kZTonRW50ZXInLGtleUNvZGU6MTMsd2hpY2g6MTMsYnViYmxlczp0cnVlfSkpOwpzdGFydE9ic2VydmUoKTsKfQp9LDEwMCk7Cn0pKCk7"

    private fun decodeJs(b64: String): String {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return String(bytes, Charset.forName("UTF-8"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun init(context: Context) {
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
        if (webView == null && appContext != null) {
            init(appContext!!)
            var waitInit = 0L
            while (webView == null && waitInit < 3000L) {
                Thread.sleep(100L)
                waitInit += 100L
            }
        }
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