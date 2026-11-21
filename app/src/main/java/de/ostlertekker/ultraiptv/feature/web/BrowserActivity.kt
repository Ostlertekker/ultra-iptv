package de.ostlertekker.ultraiptv.feature.web

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

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, BrowserActivity::class.java))
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
    private val dp get() = resources.displayMetrics.density

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Laufendes IPTV anhalten – sonst spielen zwei Quellen gleichzeitig
        runCatching { engine.setMini(null); engine.stop() }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bar = buildBar()
        web = buildWeb()
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt()))
        column.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cursor = CursorView(this)
        root.addView(cursor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        root.post { cursor.x0 = root.width / 2f; cursor.y0 = root.height / 2f; cursor.invalidate() }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else showStart()
        Toast.makeText(this, "Pfeiltasten: Zeiger · OK: Klick · Zurück lang: ULTRA IPTV", Toast.LENGTH_LONG).show()
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
        adBtn = pill("") { prefs.edit().putBoolean("adblock", !adblock()).apply(); updateAdBtn(); web.reload() }
        add(adBtn)
        updateAdBtn()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWeb(): WebView = WebView(this).apply {
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
        // Desktop-ähnliche Ansicht wie auf großen Bildschirmen (viele Seiten zeigen sonst die Handy-Version)
        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace("Mobile ", "")
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
                return u.scheme != "http" && u.scheme != "https"   // keine App-Links (intent:// usw.)
            }
            override fun onPageStarted(view: WebView, u: String?, favicon: android.graphics.Bitmap?) { showUrl(u) }
            override fun onPageFinished(view: WebView, u: String?) {
                showUrl(u); CookieManager.getInstance().flush()
                if (u != null && !u.startsWith(START) && u.startsWith("http")) addHistory(view.title ?: u, u)
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (adblock() && BrowserAdBlock.isAd(request.url))
                    WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                else null
        }
        webChromeClient = object : WebChromeClient() {
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
                var a=e.scrollTop,b=e.scrollLeft;e.scrollBy(dx,dy);if(e.scrollTop!==a||e.scrollLeft!==b)return true;}
              e=e.parentElement;}
            var a=window.scrollY,b=window.scrollX;window.scrollBy(dx,dy);return window.scrollY!==a||window.scrollX!==b;})($x,$y,$dx,$dy)""") { r -> done?.invoke(r == "true") }
    }

    private fun moveCursor(code: Int, repeat: Int) {
        val step = (7 + repeat * 3).coerceAtMost(46) * dp / 2f
        val w = root.width.toFloat(); val h = root.height.toFloat(); val edge = 8 * dp
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (cursor.x0 <= edge) scroll(0, -step.toInt() * 3) else cursor.x0 = (cursor.x0 - step).coerceAtLeast(0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (cursor.x0 >= w - edge) scroll(0, step.toInt() * 3) else cursor.x0 = (cursor.x0 + step).coerceAtMost(w - 1)
            KeyEvent.KEYCODE_DPAD_UP -> {
                val webTop = if (bar.visibility == View.VISIBLE) bar.height.toFloat() else 0f
                // Am oberen Rand der Seite: erst nach oben scrollen; ist die Seite schon oben, geht der Zeiger in die Adressleiste
                if (cursor.y0 <= webTop + edge && cursor.y0 > webTop - 1) scroll(-step.toInt() * 3) { moved ->
                    if (!moved) { cursor.y0 = (cursor.y0 - step).coerceAtLeast(0f); cursor.invalidate() }
                }
                else cursor.y0 = (cursor.y0 - step).coerceAtLeast(0f)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (cursor.y0 >= h - edge) scroll(step.toInt() * 3) else cursor.y0 = (cursor.y0 + step).coerceAtMost(h - 1)
        }
        cursor.invalidate(); hover()
    }

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
                if (event.action == KeyEvent.ACTION_UP) url.requestFocus()
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
