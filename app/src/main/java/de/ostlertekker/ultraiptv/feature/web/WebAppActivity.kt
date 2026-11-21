package de.ostlertekker.ultraiptv.feature.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import de.ostlertekker.ultraiptv.core.player.PlayerEngine
import javax.inject.Inject

/**
 * Eingebettete Web-Dienste (YouTube) direkt in ULTRA IPTV – keine externe App.
 * Es werden die offiziellen Weboberflächen der Anbieter geladen; Anmeldung und Kopierschutz bleiben unverändert; bei YouTube ist ein Werbeblocker aktiv.
 */
enum class WebApp(val title: String, val url: String, val userAgent: String) {
    // Offizielle YouTube-Fernsehoberfläche (wie auf Smart-TVs): komplett per Fernbedienung bedienbar
    YOUTUBE("YouTube", "https://www.youtube.com/tv",
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) 76.0.3809.146/6.0 TV Safari/537.36"),
}

@AndroidEntryPoint
class WebAppActivity : ComponentActivity() {
    @Inject lateinit var engine: PlayerEngine
    @Inject lateinit var http: okhttp3.OkHttpClient

    private lateinit var web: WebView
    private lateinit var app: WebApp
    private lateinit var hint: TextView
    private var backDownAt = 0L

    companion object {
        const val EXTRA_APP = "app"
        const val EXTRA_URL = "url"
        fun open(context: Context, app: WebApp) =
            context.startActivity(Intent(context, WebAppActivity::class.java).putExtra(EXTRA_APP, app.name))

        /**
         * Trailer in der eingebetteten YouTube-Oberfläche: mit Video-ID des Anbieters direkt abspielen,
         * sonst YouTube-Suche nach „<Titel> Trailer“. Zurück führt direkt in die Details.
         */
        fun openTrailer(context: Context, trailer: String?, title: String) {
            val id = trailer?.let { t ->
                Regex("""(?:v=|youtu\.be/|embed/|shorts/)([A-Za-z0-9_-]{11})""").find(t)?.groupValues?.get(1)
                    ?: t.takeIf { Regex("""[A-Za-z0-9_-]{11}""").matches(it) }
            }
            val url = if (id != null) "https://www.youtube.com/tv#/watch?v=$id"
                else "https://www.youtube.com/tv#/search?q=" + android.net.Uri.encode("$title Trailer")
            context.startActivity(Intent(context, WebAppActivity::class.java).putExtra(EXTRA_APP, WebApp.YOUTUBE.name).putExtra(EXTRA_URL, url))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = runCatching { WebApp.valueOf(intent.getStringExtra(EXTRA_APP) ?: "") }.getOrDefault(WebApp.YOUTUBE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Laufendes IPTV (Mini-Player/Vorschau) anhalten – sonst spielen zwei Quellen gleichzeitig
        runCatching { engine.setMini(null); engine.stop() }

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = app.userAgent
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                // Alles bleibt in der App: nur http(s) laden, App-Links (intent:// usw.) ignorieren
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val s = request.url.scheme ?: return true
                    // Google sperrt Anmeldungen in eingebetteten Browsern („nicht sichere App“) – statt der Fehlerseite den richtigen Weg zeigen
                    if (request.url.host?.endsWith("accounts.google.com") == true) { showLoginHelp(); return true }
                    return s != "http" && s != "https"
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    CookieManager.getInstance().flush()
                }
                // Werbeblocker (YouTube): Anfragen an Werbe-/Tracking-Server gar nicht erst laden
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                    if (app != WebApp.YOUTUBE) return null
                    return if (AdBlock.isAd(request.url)) android.webkit.WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                        emptyMap(), java.io.ByteArrayInputStream(ByteArray(0))) else null
                }
            }
            webChromeClient = object : WebChromeClient() {
                // Kopiergeschützte Wiedergabe (Widevine) für YouTube erlauben – nur Medienschutz, keine Kamera/Mikrofon
                override fun onPermissionRequest(request: PermissionRequest) {
                    val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }.toTypedArray()
                    if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()
                }
                override fun onCloseWindow(window: WebView?) { finish() }
            }
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        hint = TextView(this).apply {
            text = "${app.title} · Zurück lang drücken: zurück zu ULTRA IPTV"
            setTextColor(Color.WHITE); textSize = 13f
            setBackgroundColor(Color.argb(170, 0, 0, 0)); setPadding(28, 12, 28, 12)
        }
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 36 })
        hint.postDelayed({ hint.animate().alpha(0f).setDuration(600).start() }, 7000)
        setContentView(root)

        // YouTube-TV bietet 4K nur an, wenn (1) der Bildschirm 4K meldet und (2) die Abfrage
        // MediaSource.isTypeSupported('…; width=3840; height=2160; framerate=60') mit „ja“ beantwortet wird.
        // Die Android-WebView meldet 1920×1080 und kennt die TV-Parameter width/height/framerate nicht → nur bis 1080p.
        // Wir melden deshalb die echte Fähigkeit des Hardware-Decoders (gemessen über MediaCodecList).
        val dec = decoderMax()
        val dispH = maxDisplayHeight()
        // Diagnose direkt im Bild: zeigt, ob 4K angeboten werden kann und woran es ggf. scheitert
        // Ausschlaggebend ist der Decoder: viele Google-TV-Fernseher zeichnen die Oberfläche in 1080p,
        // obwohl das Panel 4K hat – deshalb blockiert ein 1080p-Bildmodus 4K nicht mehr.
        if (app == WebApp.YOUTUBE) hint.text = hint.text.toString() + "\n" + (if (dec.first >= 3840) "4K aktiv" else "4K aus") +
            " · Decoder: " + (decoderInfo.joinToString(", ").ifBlank { "kein VP9/AV1" }) + " · Bildmodus max. ${dispH}p"
        if (app == WebApp.YOUTUBE && dec.first >= 3840 &&
            androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val js = """
                (function(){try{
                  var w=3840,h=2160, MW=${dec.first}, MH=${dec.second}, MF=${dec.third};
                  ['width','availWidth'].forEach(function(k){Object.defineProperty(screen,k,{get:function(){return w}})});
                  ['height','availHeight'].forEach(function(k){Object.defineProperty(screen,k,{get:function(){return h}})});
                  Object.defineProperty(window,'devicePixelRatio',{get:function(){return w/Math.max(1,window.innerWidth)}});
                  // Wichtig: Original mit dem richtigen „this“ aufrufen – canPlayType ist eine Methode des Video-Elements,
                  // ein bind() auf den Prototyp warf „Illegal invocation“ und YouTube wertete das als „nicht unterstützt“.
                  function wrap(obj,name){ if(!obj||!obj[name]) return; var orig0=obj[name];
                    obj[name]=function(t){ var self=this; var orig=function(x){ return orig0.call(self,x); }; try{
                      var s=String(t), g=function(k){var m=new RegExp(k+'=([0-9.]+)').exec(s); return m?parseFloat(m[1]):0;};
                      if(g('width')>MW||g('height')>MH||g('framerate')>MF) return name==='canPlayType'?'':false;
                      var base=s.replace(/;\s*(width|height|framerate|bitrate|eotf|channels|decode-to-texture|cryptoblockformat|features)=[^;]*/gi,'');
                      return orig(base);
                    }catch(e){ return orig(t); } }; }
                  // YouTube fragt zusätzlich mediaCapabilities.decodingInfo – die WebView meldet 4K dort oft als „nicht flüssig“,
                  // dann deckelt YouTube (je nach Messung) auf 720p/1080p. Die echte Decoder-Grenze ist oben gemessen.
                  try{ if(navigator.mediaCapabilities&&navigator.mediaCapabilities.decodingInfo){
                    var di=navigator.mediaCapabilities.decodingInfo.bind(navigator.mediaCapabilities);
                    navigator.mediaCapabilities.decodingInfo=function(cfg){
                      return di(cfg).then(function(r){ try{ var v=cfg&&cfg.video;
                        if(r&&r.supported&&v&&v.width<=MW&&v.height<=MH&&(!v.framerate||v.framerate<=MF))
                          return {supported:true,smooth:true,powerEfficient:true,configuration:r.configuration||cfg};
                      }catch(e){} return r; });
                    }; } }catch(e){}
                  if(window.MediaSource) wrap(window.MediaSource,'isTypeSupported');
                  if(window.ManagedMediaSource) wrap(window.ManagedMediaSource,'isTypeSupported');
                  if(window.HTMLMediaElement) wrap(HTMLMediaElement.prototype,'canPlayType');
                }catch(e){}})();
            """.trimIndent()
            runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, js, setOf("https://www.youtube.com")) }
        }
        // Werbeblocker: Werbedaten aus den Antworten des YouTube-Players entfernen (Pre-/Mid-Rolls, Banner)
        if (app == WebApp.YOUTUBE && androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, AdBlock.SCRIPT, setOf("https://www.youtube.com")) }
            // SponsorBlock: Sponsoren-/Eigenwerbe-Abschnitte automatisch überspringen (Daten von sponsor.ajay.app)
            web.addJavascriptInterface(SponsorBlock(http, web) { msg -> runOnUiThread { showHint(msg) } }, "UltraSB")
            runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, SponsorBlock.SCRIPT, setOf("https://www.youtube.com")) }
        }
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(intent.getStringExtra(EXTRA_URL) ?: app.url)
        web.requestFocus()
    }

    private var helpView: TextView? = null

    private fun showLoginHelp() {
        if (helpView != null) return
        val root = web.parent as? FrameLayout ?: return
        helpView = TextView(this).apply {
            text =
                "Google erlaubt die Anmeldung nicht in eingebetteten Apps.\n\nSo geht es: In YouTube „Anmelden“ → „Mit dem Telefon anmelden“ wählen.\n" +
                    "Dann am Handy yt.be/activate öffnen und den Code vom Fernseher eingeben.\n\n(OK oder Zurück schließt diesen Hinweis)"
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(235, 10, 12, 16)); setPadding(80, 60, 80, 60)
        }
        root.addView(helpView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun hideLoginHelp(): Boolean {
        val h = helpView ?: return false
        (h.parent as? ViewGroup)?.removeView(h); helpView = null
        return true
    }

    /** Höchste Auflösung, die der Fernseher über HDMI ausgeben kann (Fire TV läuft in der Oberfläche oft nur mit 1080p). */
    /** Größte vom Hardware-Decoder unterstützte Auflösung/Bildrate für VP9 bzw. AV1 (YouTube-4K-Codecs). */
    /** Gefundene YouTube-Decoder (VP9/AV1) mit ihrer Höchstauflösung – für Entscheidung und Diagnose. */
    private val decoderInfo: List<String> by lazy {
        runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }.flatMap { info ->
                info.supportedTypes.filter { it.equals("video/x-vnd.on2.vp9", true) || it.equals("video/av01", true) }.mapNotNull { type ->
                    val v = runCatching { info.getCapabilitiesForType(type).videoCapabilities }.getOrNull() ?: return@mapNotNull null
                    (if (type.contains("vp9", true)) "VP9 " else "AV1 ") + "${v.supportedWidths.upper}×${v.supportedHeights.upper}"
                }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    private fun decoderMax(): Triple<Int, Int, Int> = runCatching {
        var best = Triple(1920, 1080, 30)
        for (info in android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (!type.equals("video/x-vnd.on2.vp9", true) && !type.equals("video/av01", true)) continue
                val v = runCatching { info.getCapabilitiesForType(type).videoCapabilities }.getOrNull() ?: continue
                // Manche TV-Chips melden 4K nur über die Grenzwerte (supportedWidths/Heights), nicht über isSizeSupported
                val can4k = v.isSizeSupported(3840, 2160) || (v.supportedWidths.upper >= 3840 && v.supportedHeights.upper >= 2160)
                if (can4k) {
                    val fps = if (runCatching { v.areSizeAndRateSupported(3840, 2160, 60.0) }.getOrDefault(false) ||
                        (v.supportedFrameRates.upper >= 60)) 60 else 30
                    if (fps >= best.third || best.first < 3840) best = Triple(3840, 2160, fps)
                }
            }
        }
        best
    }.getOrDefault(Triple(1920, 1080, 30))

    private fun maxDisplayHeight(): Int = runCatching {
        @Suppress("DEPRECATION") val d = windowManager.defaultDisplay
        d.supportedModes.maxOf { maxOf(it.physicalHeight, it.physicalWidth * 9 / 16) }
    }.getOrDefault(1080)

    private fun showHint(text: String) {
        hint.text = text
        hint.animate().cancel(); hint.alpha = 1f
        hint.postDelayed({ hint.animate().alpha(0f).setDuration(600).start() }, 2500)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        // YouTube pausiert beim Verlassen
        web.onPause()
    }

    override fun onResume() { super.onResume(); web.onResume() }

    override fun onDestroy() {
        runCatching { (web.parent as? ViewGroup)?.removeView(web); web.stopLoading(); web.destroy() }
        super.onDestroy()
    }

    private fun sendKey(code: Int) {
        val t = SystemClock.uptimeMillis()
        web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        web.dispatchKeyEvent(KeyEvent(t, t + 20, KeyEvent.ACTION_UP, code, 0))
    }

    // Lint-Fehlalarm: Überschreiben von ComponentActivity.dispatchKeyEvent ist erlaubt (bekanntes androidx-Problem)
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (helpView != null && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            if (event.action == KeyEvent.ACTION_UP) { hideLoginHelp(); if (web.canGoBack()) web.goBack() }
            return true
        }
        // Zurück: kurz = Zurück im Dienst, lang (≥ 0,7 s) = zurück zu ULTRA IPTV
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) backDownAt = SystemClock.uptimeMillis()
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && SystemClock.uptimeMillis() - backDownAt > 700) { finish(); return true }
            if (event.action == KeyEvent.ACTION_UP) {
                if (SystemClock.uptimeMillis() - backDownAt > 700) { finish(); return true }
                when {
                    intent.hasExtra(EXTRA_URL) -> finish()   // Trailer: Zurück = zurück zu den Details
                    app == WebApp.YOUTUBE -> sendKey(KeyEvent.KEYCODE_ESCAPE)   // YouTube-TV versteht Escape als „Zurück“
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/**
 * Werbeblocker für die eingebettete YouTube-Oberfläche.
 * 1. Netzwerk: Werbe-/Tracking-Server werden mit „204 No Content“ beantwortet.
 * 2. Player-Daten: Werbefelder (adPlacements, playerAds, adSlots) werden aus JSON-Antworten entfernt,
 *    bevor der YouTube-Player sie liest – dadurch werden keine Werbeclips eingeplant.
 * YouTube ändert sein Werbesystem regelmäßig; einzelne Werbungen können deshalb trotzdem durchrutschen.
 */
internal object AdBlock {
    private val hosts = listOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
        "imasdk.googleapis.com", "google-analytics.com", "adservice.google.com")
    private val paths = listOf("/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_", "/youtubei/v1/log_event", "/api/stats/qoe?adformat")

    fun isAd(url: android.net.Uri): Boolean {
        val h = url.host ?: return false
        if (hosts.any { h == it || h.endsWith(".$it") }) return true
        val p = (url.path ?: "") + (url.query?.let { "?$it" } ?: "")
        return (h.endsWith("youtube.com") || h.endsWith("google.com")) && paths.any { p.contains(it) }
    }

    const val SCRIPT = """
        (function(){try{
          var KEYS=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams','adBreakParams'];
          function strip(o,d){ if(!o||typeof o!=='object'||d>4) return o;
            for(var i=0;i<KEYS.length;i++){ if(KEYS[i] in o){ try{ delete o[KEYS[i]]; }catch(e){} } }
            try{ if(o.videoDetails&&o.videoDetails.videoId) window.__ultraVid=o.videoDetails.videoId; }catch(e){}
            if(o.playerResponse) strip(o.playerResponse,d+1);
            if(o.response) strip(o.response,d+1);
            if(Array.isArray(o)) for(var j=0;j<o.length&&j<8;j++) strip(o[j],d+1);
            return o; }
          var jp=JSON.parse; JSON.parse=function(){ var r=jp.apply(this,arguments); try{ strip(r,0); }catch(e){} return r; };
          var RX=/"(adPlacements|playerAds|adSlots|adBreakHeartbeatParams)"/;
          function fixText(t){ if(typeof t!=='string'||!RX.test(t)) return t;
            try{ var pre=''; var body=t; if(body.indexOf(")]}'")===0){ pre=body.substring(0,body.indexOf('\n')+1); body=body.substring(pre.length); }
              return pre+JSON.stringify(strip(jp(body),0)); }catch(e){ return t; } }
          // fetch(): .json() und .text()
          if(window.Response){ var rj=Response.prototype.json, rt=Response.prototype.text;
            Response.prototype.json=function(){ return rj.apply(this,arguments).then(function(r){ try{ strip(r,0); }catch(e){} return r; }); };
            Response.prototype.text=function(){ return rt.apply(this,arguments).then(fixText); }; }
          // XMLHttpRequest: die TV-Oberfläche lädt Player-Daten per XHR (responseText bzw. responseType 'json')
          try{ var X=XMLHttpRequest.prototype, dT=Object.getOwnPropertyDescriptor(X,'responseText'), dR=Object.getOwnPropertyDescriptor(X,'response');
            var cache=new WeakMap();
            Object.defineProperty(X,'responseText',{configurable:true,get:function(){ var t=dT.get.call(this);
              if(this.readyState!==4) return t; var c=cache.get(this); if(c&&c.src===t) return c.out; var o=fixText(t); cache.set(this,{src:t,out:o}); return o; }});
            Object.defineProperty(X,'response',{configurable:true,get:function(){ var r=dR.get.call(this);
              if(this.readyState!==4||r==null) return r;
              if(typeof r==='string'){ var c=cache.get(this); if(c&&c.src===r) return c.out; var o=fixText(r); cache.set(this,{src:r,out:o}); return o; }
              if(typeof r==='object'&&!(r instanceof ArrayBuffer)&&!(window.Blob&&r instanceof Blob)&&!(window.Document&&r instanceof Document)) strip(r,0);
              return r; }});
          }catch(e){}
          ['ytInitialPlayerResponse','ytInitialData'].forEach(function(n){ var v;
            try{ Object.defineProperty(window,n,{configurable:true,get:function(){return v;},set:function(x){v=strip(x,0);}}); }catch(e){} });
        }catch(e){}})();
    """
}


/**
 * SponsorBlock (Community-Datenbank sponsor.ajay.app, wie in SmartTube): überspringt gesponserte Abschnitte,
 * Eigenwerbung, Abo-Aufforderungen, Intros/Abspann und musikfremde Teile in Musikvideos.
 * Abfrage läuft im Hintergrund (blockiert die YouTube-Oberfläche nicht); Ergebnis wird per JavaScript zurückgegeben.
 */
internal class SponsorBlock(
    private val http: okhttp3.OkHttpClient,
    private val web: WebView,
    private val onMessage: (String) -> Unit,
) {
    private val client = http.newBuilder().connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS).readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
    private val pool = java.util.concurrent.Executors.newSingleThreadExecutor()

    @android.webkit.JavascriptInterface
    fun request(videoId: String) {
        if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) return
        pool.execute {
            val cats = android.net.Uri.encode("[\"sponsor\",\"selfpromo\",\"interaction\",\"intro\",\"outro\",\"music_offtopic\"]")
            val json = runCatching {
                client.newCall(okhttp3.Request.Builder().url("https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=$cats").build())
                    .execute().use { r -> when { r.isSuccessful -> r.body?.string() ?: "[]"; r.code == 404 -> "[]"; else -> null } }
            }.getOrNull()
            val n = json?.let { Regex("\"segment\"").findAll(it).count() }
            onMessage(when { json == null -> "SponsorBlock: Server nicht erreichbar"; n == 0 -> "SponsorBlock: keine Abschnitte in diesem Video"
                else -> "SponsorBlock: $n Abschnitt(e) werden übersprungen" })
            val payload = org.json.JSONObject.quote(json ?: "[]")
            web.post { web.evaluateJavascript("window.__ultraSBSet && window.__ultraSBSet('$videoId', $payload)", null) }
        }
    }

    @android.webkit.JavascriptInterface
    fun skipped(category: String) {
        onMessage(when (category) {
            "sponsor" -> "Sponsor übersprungen"; "selfpromo" -> "Eigenwerbung übersprungen"
            "interaction" -> "Abo-Aufforderung übersprungen"; "intro" -> "Intro übersprungen"; "outro" -> "Abspann übersprungen"
            "music_offtopic" -> "Nicht-Musik-Teil übersprungen"
            else -> "Abschnitt übersprungen"
        })
    }

    companion object {
        const val SCRIPT = """
            (function(){ if(window.__ultraSB) return; window.__ultraSB=true;
              var vid=null, segs=[], done={};
              window.__ultraSBSet=function(id,txt){ if(id!==vid) return; try{ var r=JSON.parse(txt);
                segs=Array.isArray(r)?r.filter(function(s){return s&&s.segment&&s.actionType!=='mute'&&s.actionType!=='full';}):[]; }catch(e){ segs=[]; } };
              function currentId(){
                var m=/[?&#/]v=([A-Za-z0-9_-]{11})/.exec(location.href); if(m) return m[1];
                return window.__ultraVid||null; }
              setInterval(function(){ try{
                var v=document.querySelector('video');
                var id=currentId();
                if(id&&id!==vid){ vid=id; segs=[]; done={}; if(window.UltraSB) UltraSB.request(id); }
                if(!segs.length||!v) return;
                var t=v.currentTime;
                for(var i=0;i<segs.length;i++){ var s=segs[i].segment;
                  if(t>=s[0]&&t<s[1]-0.5&&!done[i]){ done[i]=true; v.currentTime=s[1]; try{UltraSB.skipped(segs[i].category);}catch(e){} break; }
                  if(t<s[0]-1) done[i]=false; }
              }catch(e){} },400);
            })();
        """
    }
}
