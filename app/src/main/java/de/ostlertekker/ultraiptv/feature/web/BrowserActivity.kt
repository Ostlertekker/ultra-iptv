package de.ostlertekker.ultraiptv.feature.web

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import de.ostlertekker.ultraiptv.feature.web.adblock.FilterEngine.Type as AdType
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import de.ostlertekker.ultraiptv.core.player.PlayerEngine
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Eingebauter Webbrowser für die Fernbedienung: Mauszeiger per Pfeiltasten (OK = Klick, am Rand wird gescrollt),
 * Adressleiste mit Suche, Startseite mit Lesezeichen und Verlauf, Vollbild-Videos, Zoom und abschaltbarer Werbeblocker.
 * Alles bleibt in der App; Downloads und App-Links sind gesperrt.
 */
@AndroidEntryPoint
class BrowserActivity : ComponentActivity() {
    @Inject lateinit var engine: PlayerEngine
    @Inject lateinit var adblocker: de.ostlertekker.ultraiptv.feature.web.adblock.AdblockManager
    /** Host und Ausnahmen der gerade angezeigten Seite (für Drittanbieter-Erkennung und Seiten-Ausnahmen). */
    @Volatile private var pageHost: String? = null
    @Volatile private var pageFlags: de.ostlertekker.ultraiptv.feature.web.adblock.FilterEngine.PageFlags? = null
    @Volatile private var adOn = true
    private var docStartHandler: androidx.webkit.ScriptHandler? = null

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, BrowserActivity::class.java))

        /** Pluto TV: offizielle Weboberfläche (pluto.tv) im App-eigenen Rahmen – ohne Adressleiste, nur Pluto-Seiten. */
        fun openPluto(context: Context) =
            context.startActivity(Intent(context, BrowserActivity::class.java).putExtra(EXTRA_MODE, MODE_PLUTO))
        private const val EXTRA_MODE = "mode"
        private const val MODE_PLUTO = "pluto"
        private const val PLUTO_LIVE = "https://pluto.tv/de/live-tv"
        private const val PLUTO_VOD = "https://pluto.tv/de/on-demand"
        private const val PLUTO_SEARCH = "https://pluto.tv/de/search"
        /** Pluto TV lädt Seiten nur von seinen eigenen Adressen; alles andere bleibt gesperrt (keine fremden Seiten, keine App-Links). */
        private fun isPlutoHost(h: String?) = h != null && (h == "pluto.tv" || h.endsWith(".pluto.tv"))
        private const val START = "https://ultra.start/"
        private const val SEARCH = "https://duckduckgo.com/?q="
        private val DEFAULTS = listOf(
            "ARD Mediathek" to "https://www.ardmediathek.de/",
            "ZDF" to "https://www.zdf.de/",
            "Arte" to "https://www.arte.tv/de/",
            "Tagesschau" to "https://www.tagesschau.de/",
            "Wikipedia" to "https://de.wikipedia.org/",
            "Twitch" to "https://www.twitch.tv/",
            "SoundCloud" to "https://soundcloud.com/",
        )
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var bar: LinearLayout
    private lateinit var url: EditText
    private lateinit var star: TextView
    private lateinit var adBtn: TextView
    private lateinit var cursor: CursorView
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private var backDownAt = 0L
    private val prefs by lazy { getSharedPreferences("browser", MODE_PRIVATE) }
    private val pluto by lazy { intent.getStringExtra(EXTRA_MODE) == MODE_PLUTO }
    private var plutoLastUrl: String? = null
    private val dp get() = resources.displayMetrics.density

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Laufendes IPTV anhalten – sonst spielen zwei Quellen gleichzeitig
        runCatching { engine.setMini(null); engine.stop() }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bar = if (pluto) buildPlutoBar() else buildBar()
        web = buildWeb()
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt()))
        column.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cursor = CursorView(this).apply { setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        root.addView(cursor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        root.post { cursor.x0 = root.width / 2f; cursor.y0 = root.height / 2f; cursor.invalidate() }

        // Werbeblocker: Filterlisten laden/aktualisieren (einmal pro App-Lauf) und die Seitenskripte aktuell halten
        // Pluto TV ist werbefinanziert und kostenlos – dort kein Werbeblocker (Werbung gehört zum Angebot, Blocken stört die Wiedergabe)
        adOn = !pluto && adblock()
        if (!pluto) adblocker.start()
        if (!pluto) runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, de.ostlertekker.ultraiptv.feature.web.adblock.AdblockScripts.ANTI_ADBLOCK, setOf("*"))
        }
        if (!pluto) lifecycleScope.launch { adblocker.engine.collect { e -> registerGenericCss(e) } }
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else if (pluto) web.loadUrl(PLUTO_LIVE) else showStart()
        Toast.makeText(this, if (pluto) "Pluto TV · Pfeiltasten: Zeiger · OK: Klick · Zurück lang: ULTRA IPTV"
            else "Pfeiltasten: Zeiger · OK: Klick · Zurück lang: ULTRA IPTV", Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ Aufbau

    private fun pill(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
        background = GradientDrawable().apply { cornerRadius = 50 * dp; setStroke((1 * dp).toInt(), Color.argb(140, 255, 255, 255)) }
        setOnClickListener { onClick() }
    }

    private fun buildBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.rgb(14, 16, 19))
        setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        fun add(v: View, weight: Float = 0f) = addView(v, LinearLayout.LayoutParams(if (weight > 0) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT, weight).apply { marginEnd = (8 * dp).toInt() })
        add(pill("←") { if (web.canGoBack()) web.goBack() })
        add(pill("→") { if (web.canGoForward()) web.goForward() })
        add(pill("⟳") { web.reload() })
        add(pill("⌂") { showStart() })
        url = EditText(this@BrowserActivity).apply {
            setSingleLine(); textSize = 15f; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            hint = "Adresse oder Suchbegriff"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            background = GradientDrawable().apply { cornerRadius = 50 * dp; setColor(Color.argb(40, 255, 255, 255)) }
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
            setOnFocusChangeListener { _, has -> if (has) { selectAll(); showKeyboard(this) } }
        }
        add(url, 1f)
        star = pill("☆") { toggleBookmark() }; add(star)
        add(pill("A−") { zoom(-10) })
        add(pill("A+") { zoom(10) })
        adBtn = pill("") {
            prefs.edit().putBoolean("adblock", !adblock()).apply(); adOn = adblock(); updateAdBtn()
            registerGenericCss(adblocker.engine.value)
            Toast.makeText(this@BrowserActivity, if (adOn) "Werbeblocker an · ${adblocker.status}" else "Werbeblocker aus", Toast.LENGTH_SHORT).show()
            web.reload()
        }
        add(adBtn)
        updateAdBtn()
    }

    /** Leiste für Pluto TV im Stil von ULTRA IPTV: Bereiche direkt anwählbar, keine Adressleiste. */
    private fun buildPlutoBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.rgb(14, 16, 19))
        setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        fun add(v: View, weight: Float = 0f) = addView(v, LinearLayout.LayoutParams(if (weight > 0) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT, weight).apply { marginEnd = (8 * dp).toInt() })
        add(TextView(this@BrowserActivity).apply {
            text = "Pluto TV"; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER_VERTICAL
            setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding((4 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        })
        add(pill("Live-TV") { web.loadUrl(PLUTO_LIVE) })
        add(pill("Filme & Serien") { web.loadUrl(PLUTO_VOD) })
        add(pill("Suche") { web.loadUrl(PLUTO_SEARCH) })
        add(View(this@BrowserActivity), 1f)
        add(pill("←") { if (web.canGoBack()) web.goBack() })
        add(pill("⟳") { web.reload() })
        add(pill("Vollbild") { bar.visibility = View.GONE; Toast.makeText(this@BrowserActivity, "Zurück: Leiste wieder einblenden", Toast.LENGTH_SHORT).show() })
        add(pill("Zurück zu ULTRA IPTV") { finish() })
        // Unsichtbare Platzhalter (werden in Pluto TV nicht angezeigt)
        url = EditText(this@BrowserActivity); star = TextView(this@BrowserActivity); adBtn = TextView(this@BrowserActivity)
    }

    /** Verständliche Fehlerseite (statt weißer/schwarzer Seite) mit „Erneut versuchen“. */
    private fun showPlutoError(failed: String?) {
        val retry = esc(failed?.takeIf { isPlutoHost(android.net.Uri.parse(it).host) } ?: PLUTO_LIVE)
        val html = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>
            body{background:#0e1013;color:#fff;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0}
            h1{font-size:28px;margin:0 0 12px} p{color:#aaa;font-size:18px;margin:0 0 28px;text-align:center;max-width:640px}
            a{font-size:20px;padding:14px 34px;border-radius:40px;border:1px solid #fff;color:#fff;text-decoration:none}
            </style></head><body><h1>Pluto TV ist gerade nicht erreichbar</h1>
            <p>Bitte Internetverbindung prüfen. Pluto TV ist nur in unterstützten Ländern verfügbar (u. a. Deutschland, Österreich, Schweiz).</p>
            <a href="$retry">Erneut versuchen</a></body></html>"""
        web.loadDataWithBaseURL("https://pluto.tv/", html, "text/html", "utf-8", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWeb(): WebView = WebView(this).apply {
        tag = "ultra_web"
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.textZoom = prefs.getInt("zoom", 100)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Leistung: Seitenbereiche außerhalb des Bildes vorab zeichnen (flüssigeres Scrollen), Renderer mit hoher Priorität
        settings.offscreenPreRaster = true
        if (android.os.Build.VERSION.SDK_INT >= 26) setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        // Desktop-ähnliche Ansicht wie auf großen Bildschirmen (viele Seiten zeigen sonst die Handy-Version)
        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace("Mobile ", "")
        // Pluto TV: Desktop-Kennung, sonst zeigt pluto.tv auf Android nur „App herunterladen“ statt des Web-Players
        if (pluto) {
            val chrome = Regex("Chrome/([0-9.]+)").find(settings.userAgentString)?.groupValues?.get(1) ?: "131.0.0.0"
            settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
            settings.builtInZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        setDownloadListener { _, _, _, _, _ -> Toast.makeText(this@BrowserActivity, "Downloads sind im Browser deaktiviert", Toast.LENGTH_SHORT).show() }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "ultra") { handleUltra(u); return true }
                if (u.host?.endsWith("accounts.google.com") == true) {
                    Toast.makeText(this@BrowserActivity, "Google erlaubt die Anmeldung nicht in eingebetteten Browsern", Toast.LENGTH_LONG).show(); return true
                }
                if (u.scheme != "http" && u.scheme != "https") return true   // keine App-Links (intent:// usw.)
                // Pluto TV: nur Pluto-Seiten; Links nach außen bleiben gesperrt (kein externer Browser, keine fremden Seiten)
                if (pluto && request.isForMainFrame && !isPlutoHost(u.host)) { toastOnce("Externe Seiten sind in Pluto TV gesperrt"); return true }
                // Werbe-Weiterleitungen und Schadseiten gar nicht erst laden
                if (request.isForMainFrame && blockNavigation(u.toString(), request.isRedirect, request.hasGesture())) return true
                return false
            }
            override fun onPageStarted(view: WebView, u: String?, favicon: android.graphics.Bitmap?) {
                showUrl(u)
                val host = runCatching { android.net.Uri.parse(u).host?.lowercase() }.getOrNull()
                pageHost = host
                pageFlags = if (host != null && u != null) runCatching { adblocker.engine.value?.pageFlags(u, host) }.getOrNull() else null
            }
            override fun onPageCommitVisible(view: WebView, u: String?) { injectCosmetics(view) }
            override fun onPageFinished(view: WebView, u: String?) {
                injectCosmetics(view)
                showUrl(u); CookieManager.getInstance().flush()
                if (pluto) {
                    if (u != null && u.startsWith("http")) plutoLastUrl = u
                    android.util.Log.i("UltraPluto", "Seite: $u · WebView ${androidx.webkit.WebViewCompat.getCurrentWebViewPackage(this@BrowserActivity)?.versionName}")
                    return
                }
                if (u != null && !u.startsWith(START) && u.startsWith("http")) addHistory(view.title ?: u, u)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (pluto && request.isForMainFrame) showPlutoError(request.url.toString())
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                runCatching { interceptAd(request) }.getOrNull()   // ein Fehler im Filter darf nie die Seite blockieren
        }
        // Pop-ups/Pop-unders: nur nach echtem Klick, und nie zu Werbe-/Schadseiten; erlaubte Fenster öffnen im selben Browser
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        webChromeClient = object : WebChromeClient() {
            // Kopiergeschützte Wiedergabe (Widevine) für Pluto TV erlauben – nur Medienschutz, keine Kamera/Mikrofon
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                val ok = if (pluto) request.resources.filter { it == android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }.toTypedArray() else emptyArray()
                if (ok.isNotEmpty()) request.grant(ok) else request.deny()
            }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (pluto && !isUserGesture) return false
                if (!isUserGesture && adOn) { toastOnce("Pop-up blockiert"); return false }
                val catcher = WebView(this@BrowserActivity)
                catcher.webViewClient = object : WebViewClient() {
                    private var done = false
                    private fun take(v: WebView, target: String?) {
                        if (done || target == null || target == "about:blank") return
                        done = true; v.stopLoading(); v.post { runCatching { v.destroy() } }
                        if (pluto) { if (isPlutoHost(android.net.Uri.parse(target).host)) web.loadUrl(target) else toastOnce("Externe Seiten sind in Pluto TV gesperrt") }
                        else if (adOn && runCatching { adblocker.engine.value?.let { e ->
                                val h = android.net.Uri.parse(target).host?.lowercase() ?: ""
                                e.shouldBlock(target, h, de.ostlertekker.ultraiptv.feature.web.adblock.FilterEngine.Type.POPUP, pageHost) ||
                                    e.shouldBlock(target, h, de.ostlertekker.ultraiptv.feature.web.adblock.FilterEngine.Type.OTHER, pageHost)
                            } ?: BrowserAdBlock.isAd(android.net.Uri.parse(target)) }.getOrDefault(false)) toastOnce("Werbe-Pop-up blockiert")
                        else web.loadUrl(target)
                    }
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { take(v, r.url.toString()); return true }
                    override fun onPageStarted(v: WebView, u: String?, f: android.graphics.Bitmap?) { take(v, u) }
                }
                (resultMsg.obj as? WebView.WebViewTransport)?.webView = catcher
                resultMsg.sendToTarget()
                return true
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                fullView?.let { onHideCustomView() }
                fullView = view; fullCallback = callback
                root.addView(view, root.indexOfChild(cursor), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                bar.visibility = View.GONE
            }
            override fun onHideCustomView() {
                fullView?.let { root.removeView(it) }
                fullView = null; fullCallback?.onCustomViewHidden(); fullCallback = null
                bar.visibility = View.VISIBLE
            }
            override fun onReceivedTitle(view: WebView, title: String?) {}
            // Pluto TV: Skriptfehler der Seite ins Protokoll (Fehlersuche bei leerer Seite), keine Nutzerdaten
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                if (pluto && m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR)
                    android.util.Log.w("UltraPluto", "JS: ${m.message().take(200)} @${m.sourceId().substringAfterLast('/').take(60)}:${m.lineNumber()}")
                return false
            }
        }
    }

    // ------------------------------------------------------------------ Seiten

    private fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val target = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> SEARCH + android.net.Uri.encode(t)
        }
        hideKeyboard(); url.clearFocus(); web.requestFocus()
        web.loadUrl(target)
    }

    private fun showUrl(u: String?) {
        if (url.hasFocus()) return
        url.setText(if (u == null || u.startsWith(START)) "" else u)
        star.text = if (u != null && bookmarks().any { it.second == u }) "★" else "☆"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun showStart() {
        val tiles = bookmarks().joinToString("") { (title, u) ->
            val host = runCatching { android.net.Uri.parse(u).host ?: "" }.getOrDefault("")
            """<div class="t"><a href="${esc(u)}"><img src="https://icons.duckduckgo.com/ip3/${esc(host)}.ico" onerror="this.style.display='none'">
               <span>${esc(title)}</span></a><a class="x" href="ultra://remove?u=${android.net.Uri.encode(u)}">✕</a></div>"""
        }
        val hist = history().take(8).joinToString("") { (title, u) -> """<a class="h" href="${esc(u)}">${esc(title)}</a>""" }
        val html = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>
            body{background:#000;color:#fff;font-family:sans-serif;margin:0;padding:48px 64px}
            h1{font-size:30px;margin:0 0 24px} h2{font-size:18px;color:#aaa;margin:36px 0 12px}
            form{display:flex;gap:12px;margin-bottom:24px} input{flex:1;font-size:22px;padding:14px 22px;border-radius:40px;border:1px solid #666;background:#111;color:#fff}
            button{font-size:20px;padding:0 28px;border-radius:40px;border:1px solid #fff;background:transparent;color:#fff}
            .g{display:flex;flex-wrap:wrap;gap:18px} .t{position:relative}
            .t a:first-child{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;width:180px;height:110px;border:1px solid #444;border-radius:16px;color:#fff;text-decoration:none;font-size:17px}
            .t img{width:32px;height:32px} .x{position:absolute;top:4px;right:10px;color:#777;text-decoration:none;font-size:14px}
            .h{display:block;color:#9cf;font-size:17px;padding:6px 0;text-decoration:none;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
            </style></head><body><h1>Browser</h1>
            <form action="https://duckduckgo.com/" method="get"><input name="q" placeholder="Suchen oder Adresse eingeben"><button>Suchen</button></form>
            <h2>Lesezeichen</h2><div class="g">$tiles</div>${if (hist.isNotEmpty()) "<h2>Zuletzt besucht</h2>$hist" else ""}
            </body></html>"""
        web.loadDataWithBaseURL(START, html, "text/html", "utf-8", START)
    }

    private fun handleUltra(u: android.net.Uri) {
        if (u.host == "remove") {
            val target = u.getQueryParameter("u") ?: return
            saveBookmarks(bookmarks().filterNot { it.second == target })
            showStart()
        }
    }

    // ------------------------------------------------------------------ Lesezeichen, Verlauf, Einstellungen

    private fun readList(key: String, default: List<Pair<String, String>>): List<Pair<String, String>> {
        val raw = prefs.getString(key, null) ?: return default
        return runCatching {
            val a = JSONArray(raw); (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("t") to o.getString("u") } }
        }.getOrDefault(default)
    }

    private fun writeList(key: String, list: List<Pair<String, String>>) {
        val a = JSONArray(); list.forEach { (t, u) -> a.put(JSONObject().put("t", t).put("u", u)) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    private fun bookmarks() = readList("bookmarks", DEFAULTS)
    private fun saveBookmarks(l: List<Pair<String, String>>) = writeList("bookmarks", l)
    private fun history() = readList("history", emptyList())
    private fun addHistory(title: String, u: String) = writeList("history", (listOf(title to u) + history().filterNot { it.second == u }).take(50))

    private fun toggleBookmark() {
        val u = web.url ?: return
        if (u.startsWith(START)) return
        val list = bookmarks()
        if (list.any { it.second == u }) {
            saveBookmarks(list.filterNot { it.second == u }); star.text = "☆"
            Toast.makeText(this, "Lesezeichen entfernt", Toast.LENGTH_SHORT).show()
        } else {
            saveBookmarks(list + ((web.title?.takeIf { it.isNotBlank() } ?: u) to u)); star.text = "★"
            Toast.makeText(this, "Lesezeichen gespeichert", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ Werbeblocker

    private var lastToast = 0L
    private fun toastOnce(msg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastToast > 4000) { lastToast = now; runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
    }

    /** Netzwerkfilter (läuft im Hintergrund-Thread der WebView). */
    private fun interceptAd(request: WebResourceRequest): WebResourceResponse? {
        if (!adOn || request.isForMainFrame) return null
        if (pageFlags?.allowAll == true) return null
        val u = request.url
        val scheme = u.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = u.host?.lowercase() ?: return null
        val url = u.toString()
        val e = adblocker.engine.value
        val blocked = if (e != null) e.shouldBlock(url, host, typeOf(request, url), pageHost) else BrowserAdBlock.isAd(u)
        if (!blocked) return null
        // Bekannte Werbe-/Statistikskripte durch harmlose Platzhalter ersetzen, damit Seiten (und Videoplayer) weiterlaufen
        de.ostlertekker.ultraiptv.feature.web.adblock.AdblockScripts.surrogate(host, u.path ?: "")?.let { js ->
            return WebResourceResponse("application/javascript", "utf-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"),
                java.io.ByteArrayInputStream(js.toByteArray()))
        }
        return WebResourceResponse("text/plain", "utf-8", 204, "No Content", mapOf("Access-Control-Allow-Origin" to "*"),
            java.io.ByteArrayInputStream(ByteArray(0)))
    }

    /** Ressourcentyp aus Anfrage ableiten (WebView meldet ihn nicht direkt). */
    private fun typeOf(r: WebResourceRequest, url: String): de.ostlertekker.ultraiptv.feature.web.adblock.FilterEngine.Type {
        val path = (r.url.path ?: "").lowercase()
        val accept = (r.requestHeaders["Accept"] ?: r.requestHeaders["accept"] ?: "").lowercase()
        val ext = path.substringAfterLast('.', "")
        return when {
            r.requestHeaders.keys.any { it.equals("X-Requested-With", true) } && r.requestHeaders.values.any { it == "XMLHttpRequest" } -> AdType.XHR
            ext == "js" || ext == "mjs" -> AdType.SCRIPT
            ext == "css" || accept.startsWith("text/css") -> AdType.STYLESHEET
            ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp") || accept.startsWith("image/") -> AdType.IMAGE
            ext in setOf("woff", "woff2", "ttf", "otf", "eot") -> AdType.FONT
            ext in setOf("mp4", "webm", "m3u8", "mpd", "ts", "m4s", "mp3", "aac", "ogg", "m4a") || accept.startsWith("video/") || accept.startsWith("audio/") -> AdType.MEDIA
            accept.startsWith("text/html") -> AdType.SUBDOCUMENT
            accept.contains("javascript") -> AdType.SCRIPT
            url.startsWith("ws") -> AdType.WEBSOCKET
            else -> AdType.OTHER
        }
    }

    /** Hauptfenster: Schadseiten immer sperren; Werbe-Weiterleitungen (ohne Klick oder per Umleitung) ebenfalls. */
    private fun blockNavigation(target: String, isRedirect: Boolean, gesture: Boolean): Boolean {
        if (!adOn) return false
        val e = adblocker.engine.value ?: return false
        val host = runCatching { android.net.Uri.parse(target).host?.lowercase() }.getOrNull() ?: return false
        val bad = runCatching {
            e.shouldBlock(target, host, AdType.DOCUMENT, pageHost) ||
                ((isRedirect || !gesture) && host != pageHost && e.shouldBlock(target, host, AdType.POPUP, pageHost))
        }.getOrDefault(false)
        if (bad) toastOnce("Werbe- bzw. Schadseite blockiert")
        return bad
    }

    /** Allgemeine Werbeflächen-Regeln als Skript, das in jede Seite schon vor dem Laden eingesetzt wird. */
    private fun registerGenericCss(e: de.ostlertekker.ultraiptv.feature.web.adblock.FilterEngine?) {
        runCatching { docStartHandler?.remove() }
        docStartHandler = null
        if (e == null || !adOn) return
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) return
        docStartHandler = runCatching {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, de.ostlertekker.ultraiptv.feature.web.adblock.AdblockScripts.genericCssScript(e.genericCss), setOf("*"))
        }.onFailure { android.util.Log.w("UltraAdblock", "Skript nicht eingesetzt: ${it.message}") }.getOrNull()
    }

    /** Seitenspezifische Werbeflächen ausblenden (und Ausnahmen der Seite beachten). */
    private fun injectCosmetics(view: WebView) {
        if (!adOn) return
        val host = pageHost ?: return
        val e = adblocker.engine.value ?: return
        val flags = pageFlags
        val js = runCatching {
            val noGeneric = flags?.allowAll == true || flags?.noElemHide == true || flags?.noGenericHide == true
            val exceptions = e.genericExceptionsFor(host)
            val specific = if (flags?.allowAll == true || flags?.noElemHide == true) "" else e.specificCss(host)
            // Ohne Dokumentstart-Skript (alte WebView): allgemeine Regeln hier nachreichen
            val fallback = if (docStartHandler == null && !noGeneric) e.genericCss.joinToString("\n") else ""
            de.ostlertekker.ultraiptv.feature.web.adblock.AdblockScripts.cosmeticScript(specific + "\n" + fallback, noGeneric || exceptions.isNotEmpty(),
                if (!noGeneric && exceptions.isNotEmpty()) e.genericCss.joinToString("\n") else "", exceptions)
        }.getOrNull() ?: return
        view.evaluateJavascript(js, null)
    }

    private fun adblock() = prefs.getBoolean("adblock", true)
    private fun updateAdBtn() { adBtn.text = if (adblock()) "Werbeblocker an" else "Werbeblocker aus" }

    private fun zoom(delta: Int) {
        val z = (web.settings.textZoom + delta).coerceIn(60, 250)
        web.settings.textZoom = z; prefs.edit().putInt("zoom", z).apply()
        Toast.makeText(this, "Schriftgröße $z %", Toast.LENGTH_SHORT).show()
    }

    private fun showKeyboard(v: View) {
        v.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
    }

    // ------------------------------------------------------------------ Zeiger

    private fun click() {
        val x = cursor.x0; val y = cursor.y0
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int, time: Long) = MotionEvent.obtain(t, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val down = ev(MotionEvent.ACTION_DOWN, t); val up = ev(MotionEvent.ACTION_UP, t + 60)
        root.dispatchTouchEvent(down); root.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
        // Eingabefeld der Seite angeklickt → Tastatur öffnen
        if (!url.hasFocus()) web.postDelayed({
            web.evaluateJavascript("(function(){var e=document.activeElement;return !!e&&(e.tagName==='INPUT'||e.tagName==='TEXTAREA'||e.isContentEditable);})()") { r ->
                if (r == "true") showKeyboard(web)
            }
        }, 250)
    }

    private fun hover() {
        val loc = IntArray(2); web.getLocationInWindow(loc)
        val rl = IntArray(2); root.getLocationInWindow(rl)
        val x = cursor.x0 - (loc[0] - rl[0]); val y = cursor.y0 - (loc[1] - rl[1])
        if (y < 0) return
        val e = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_HOVER_MOVE, x, y, 0)
        e.source = InputDevice.SOURCE_MOUSE
        web.dispatchGenericMotionEvent(e); e.recycle()
    }

    /** Scrollt das scrollbare Element unter dem Zeiger (oder die ganze Seite). */
    private fun scroll(dy: Int, dx: Int = 0, done: ((Boolean) -> Unit)? = null) {
        val loc = IntArray(2); web.getLocationInWindow(loc)
        val rl = IntArray(2); root.getLocationInWindow(rl)
        val x = cursor.x0 - (loc[0] - rl[0]); val y = (cursor.y0 - (loc[1] - rl[1])).coerceAtLeast(1f)
        web.evaluateJavascript("""(function(x,y,dx,dy){var r=window.devicePixelRatio||1;x/=r;y/=r;dx/=r;dy/=r;
            var e=document.elementFromPoint(x,y);
            while(e&&e!==document.body&&e!==document.documentElement){var s=getComputedStyle(e);
              if((dy&&/(auto|scroll)/.test(s.overflowY)&&e.scrollHeight>e.clientHeight)||(dx&&/(auto|scroll)/.test(s.overflowX)&&e.scrollWidth>e.clientWidth)){
                var a=e.scrollTop,b=e.scrollLeft;e.scrollBy({left:dx,top:dy,behavior:'instant'});if(e.scrollTop!==a||e.scrollLeft!==b)return true;}
              e=e.parentElement;}
            var a=window.scrollY,b=window.scrollX;window.scrollBy({left:dx,top:dy,behavior:'instant'});return window.scrollY!==a||window.scrollX!==b;})($x,$y,$dx,$dy)""") { r -> done?.invoke(r == "true") }
    }

    private fun moveCursor(code: Int, repeat: Int) {
        // Schneller Zeiger: zügiger Start, beschleunigt beim Halten deutlich
        val step = (12 + repeat * 6).coerceAtMost(80) * dp / 2f
        val w = root.width.toFloat(); val h = root.height.toFloat(); val edge = 8 * dp
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (cursor.x0 <= edge) scroll(0, -step.toInt() * 3) else cursor.x0 = (cursor.x0 - step).coerceAtLeast(0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (cursor.x0 >= w - edge) scroll(0, step.toInt() * 3) else cursor.x0 = (cursor.x0 + step).coerceAtMost(w - 1)
            KeyEvent.KEYCODE_DPAD_UP -> {
                val webTop = if (bar.visibility == View.VISIBLE) bar.height.toFloat() else 0f
                when {
                    // In der Seite: höchstens bis an den oberen Seitenrand (nicht in einem Schritt darüber hinweg in die Adressleiste)
                    cursor.y0 > webTop + edge -> cursor.y0 = (cursor.y0 - step).coerceAtLeast(webTop + 1f)
                    // Am oberen Rand: nach oben scrollen; ist die Seite schon ganz oben, geht der Zeiger in die Adressleiste
                    cursor.y0 > webTop - 1 -> scroll(-step.toInt() * 3) { moved ->
                        if (!moved) { cursor.y0 = (webTop - 1f - step / 2).coerceAtLeast(0f); cursor.invalidate() }
                    }
                    else -> cursor.y0 = (cursor.y0 - step).coerceAtLeast(0f)
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (cursor.y0 >= h - edge) scroll(step.toInt() * 3) else cursor.y0 = (cursor.y0 + step).coerceAtMost(h - 1)
        }
        cursor.invalidate()
        // Hover nur alle 80 ms an die Seite melden – spart Neuberechnungen der Webseite beim schnellen Bewegen
        val now = SystemClock.uptimeMillis()
        if (now - lastHover > 80) { lastHover = now; hover() }
        else if (!hoverPending) { hoverPending = true; web.postDelayed({ hoverPending = false; lastHover = SystemClock.uptimeMillis(); hover() }, 90) }
    }
    private var lastHover = 0L
    private var hoverPending = false

    // ------------------------------------------------------------------ Tasten

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Zurück: kurz = Seite zurück / Vollbild verlassen, lang (≥ 0,7 s) = zurück zu ULTRA IPTV
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) backDownAt = SystemClock.uptimeMillis()
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && SystemClock.uptimeMillis() - backDownAt > 700) { finish(); return true }
            if (event.action == KeyEvent.ACTION_UP) {
                when {
                    SystemClock.uptimeMillis() - backDownAt > 700 -> finish()
                    url.hasFocus() -> { hideKeyboard(); url.clearFocus(); web.requestFocus(); showUrl(web.url) }
                    fullView != null -> web.webChromeClient?.onHideCustomView()
                    pluto && bar.visibility != View.VISIBLE -> bar.visibility = View.VISIBLE
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
            return true
        }
        // In der Adressleiste tippt die Tastatur – Pfeiltasten bewegen dort den Textcursor
        if (url.hasFocus()) return super.dispatchKeyEvent(event)
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) moveCursor(code, event.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.action == KeyEvent.ACTION_UP) click()
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (event.action == KeyEvent.ACTION_DOWN) scroll((web.height * 0.8f).toInt()); return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (event.action == KeyEvent.ACTION_DOWN) scroll(-(web.height * 0.8f).toInt()); return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event.action == KeyEvent.ACTION_UP) { if (pluto) bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE else url.requestFocus() }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }
    override fun onPause() { super.onPause(); CookieManager.getInstance().flush(); web.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() {
        runCatching { (web.parent as? ViewGroup)?.removeView(web); web.stopLoading(); web.destroy() }
        super.onDestroy()
    }

    /** Mauszeiger (Pfeil mit Schatten). */
    private class CursorView(context: Context) : View(context) {
        var x0 = 0f; var y0 = 0f
        private val d = context.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0); style = Paint.Style.FILL }
        private val path = Path()
        override fun onDraw(c: Canvas) {
            val s = d * 1.3f
            path.reset()
            path.moveTo(x0, y0); path.lineTo(x0, y0 + 18 * s); path.lineTo(x0 + 4.5f * s, y0 + 14 * s)
            path.lineTo(x0 + 8 * s, y0 + 21 * s); path.lineTo(x0 + 11 * s, y0 + 19.5f * s); path.lineTo(x0 + 7.5f * s, y0 + 12.8f * s)
            path.lineTo(x0 + 13 * s, y0 + 12.8f * s); path.close()
            c.save(); c.translate(2 * d, 2 * d); c.drawPath(path, shadow); c.restore()
            c.drawPath(path, fill); c.drawPath(path, stroke)
        }
    }
}

/** Werbe-/Tracking-Server für den Browser (allgemeine Liste, zusätzlich zu den YouTube-Regeln). */
internal object BrowserAdBlock {
    private val hosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com", "adservice.google.com",
        "google-analytics.com", "googletagmanager.com", "adnxs.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
        "amazon-adsystem.com", "pubmatic.com", "rubiconproject.com", "openx.net", "adform.net", "smartadserver.com",
        "yieldlab.net", "scorecardresearch.com", "moatads.com", "casalemedia.com", "teads.tv", "media.net", "quantserve.com",
        "adsrvr.org", "bidswitch.net", "3lift.com", "sharethrough.com", "zemanta.com", "hotjar.com",
    )
    fun isAd(url: android.net.Uri): Boolean {
        val h = url.host ?: return false
        return hosts.any { h == it || h.endsWith(".$it") } || AdBlock.isAd(url)
    }
}
