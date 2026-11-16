package de.ostlertekker.ultraiptv.feature.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
 * Eingebettete Web-Dienste (YouTube, Spotify) direkt in ULTRA IPTV – keine externe App.
 * Es werden die offiziellen Weboberflächen der Anbieter geladen; Anmeldung, Werbung und Kopierschutz bleiben unverändert.
 */
enum class WebApp(val title: String, val url: String, val userAgent: String, val cursor: Boolean, val keepAudioInBackground: Boolean) {
    // Offizielle YouTube-Fernsehoberfläche (wie auf Smart-TVs): komplett per Fernbedienung bedienbar
    YOUTUBE("YouTube", "https://www.youtube.com/tv",
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) 76.0.3809.146/6.0 TV Safari/537.36",
        cursor = false, keepAudioInBackground = false),

    // Offizieller Spotify-Web-Player (Desktop-Ansicht) mit Fernbedienungs-Zeiger
    SPOTIFY("Spotify", "https://open.spotify.com",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        cursor = true, keepAudioInBackground = true),
}

@AndroidEntryPoint
class WebAppActivity : ComponentActivity() {
    @Inject lateinit var engine: PlayerEngine

    private lateinit var web: WebView
    private lateinit var app: WebApp
    private var cursor: CursorView? = null
    private lateinit var hint: TextView
    private var backDownAt = 0L

    companion object {
        const val EXTRA_APP = "app"
        fun open(context: Context, app: WebApp) =
            context.startActivity(Intent(context, WebAppActivity::class.java).putExtra(EXTRA_APP, app.name))
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
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = app.userAgent
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (app == WebApp.SPOTIFY) { settings.textZoom = 125 }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                // Alles bleibt in der App: nur http(s) laden, App-Links (intent://, spotify:) ignorieren
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val s = request.url.scheme ?: return true
                    return s != "http" && s != "https"
                }
                override fun onPageFinished(view: WebView, url: String?) { CookieManager.getInstance().flush() }
            }
            webChromeClient = object : WebChromeClient() {
                // Kopiergeschützte Wiedergabe (Widevine) für Spotify/YouTube erlauben – nur Medienschutz, keine Kamera/Mikrofon
                override fun onPermissionRequest(request: PermissionRequest) {
                    val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }.toTypedArray()
                    if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()
                }
                override fun onCloseWindow(window: WebView?) { finish() }
            }
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (app.cursor) {
            cursor = CursorView(this).also { root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        }
        hint = TextView(this).apply {
            text = if (app.cursor) "${app.title} · Pfeiltasten: Zeiger · OK: klicken · ⏯: Wiedergabe · Zurück lang drücken: zurück zu ULTRA IPTV"
                   else "${app.title} · Zurück lang drücken: zurück zu ULTRA IPTV"
            setTextColor(Color.WHITE); textSize = 13f
            setBackgroundColor(Color.argb(170, 0, 0, 0)); setPadding(28, 12, 28, 12)
        }
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 36 })
        hint.postDelayed({ hint.animate().alpha(0f).setDuration(600).start() }, 7000)
        setContentView(root)

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(app.url)
        web.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        // YouTube pausiert beim Verlassen; Spotify darf als Hintergrundmusik weiterlaufen
        if (!app.keepAudioInBackground) web.onPause()
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Zurück: kurz = Zurück im Dienst, lang (≥ 0,7 s) = zurück zu ULTRA IPTV
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) backDownAt = SystemClock.uptimeMillis()
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && SystemClock.uptimeMillis() - backDownAt > 700) { finish(); return true }
            if (event.action == KeyEvent.ACTION_UP) {
                if (SystemClock.uptimeMillis() - backDownAt > 700) { finish(); return true }
                when {
                    app == WebApp.YOUTUBE -> sendKey(KeyEvent.KEYCODE_ESCAPE)   // YouTube-TV versteht Escape als „Zurück“
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
            return true
        }
        val c = cursor
        if (c != null) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) c.move(code, event.repeatCount, web)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP) c.click(web)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (event.action == KeyEvent.ACTION_UP) sendKey(KeyEvent.KEYCODE_SPACE) // Spotify: Leertaste = Wiedergabe/Pause
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (event.action == KeyEvent.ACTION_UP) web.evaluateJavascript(
                    "document.querySelector('[data-testid=control-button-skip-forward]')?.click()", null); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { if (event.action == KeyEvent.ACTION_UP) web.evaluateJavascript(
                    "document.querySelector('[data-testid=control-button-skip-back]')?.click()", null); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

/** Fernbedienungs-Zeiger für Weboberflächen, die für Maus gebaut sind (Spotify-Web-Player). */
class CursorView(context: Context) : View(context) {
    private var x = -1f
    private var y = -1f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val arrow = Path()

    init { isFocusable = false; isClickable = false }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { if (x < 0) { x = w / 2f; y = h / 2f } }

    fun move(code: Int, repeat: Int, web: WebView) {
        // Beschleunigung beim Gedrückthalten
        val step = (18f + repeat * 9f).coerceAtMost(90f) * resources.displayMetrics.density / 2f
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> x -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> x += step
            KeyEvent.KEYCODE_DPAD_UP -> y -= step
            KeyEvent.KEYCODE_DPAD_DOWN -> y += step
        }
        // Am Rand scrollen statt anschlagen
        val margin = 24f
        if (y < margin) { web.dispatchWheel(this.x, margin, -1f); y = margin }
        if (y > height - margin) { web.dispatchWheel(this.x, height - margin, 1f); y = height - margin }
        x = x.coerceIn(margin, width - margin)
        // Hover für Menüs/Tooltips
        val t = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, x, y, 0).apply { source = android.view.InputDevice.SOURCE_MOUSE }
        web.dispatchGenericMotionEvent(ev); ev.recycle()
        invalidate()
    }

    fun click(web: WebView) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        web.dispatchTouchEvent(down); web.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
        animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(80).start() }.start()
    }

    private fun WebView.dispatchWheel(px: Float, py: Float, dir: Float) {
        val t = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = px; this.y = py; setAxisValue(MotionEvent.AXIS_VSCROLL, -dir) })
        val ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_MOUSE, 0)
        dispatchGenericMotionEvent(ev); ev.recycle()
    }

    override fun onDraw(canvas: Canvas) {
        val s = resources.displayMetrics.density * 1.4f
        arrow.reset()
        arrow.moveTo(x, y); arrow.lineTo(x, y + 18 * s); arrow.lineTo(x + 5 * s, y + 13.5f * s)
        arrow.lineTo(x + 9 * s, y + 21 * s); arrow.lineTo(x + 12 * s, y + 19.5f * s); arrow.lineTo(x + 8 * s, y + 12.5f * s)
        arrow.lineTo(x + 14 * s, y + 12.5f * s); arrow.close()
        canvas.drawPath(arrow, fill); canvas.drawPath(arrow, stroke)
    }
}
