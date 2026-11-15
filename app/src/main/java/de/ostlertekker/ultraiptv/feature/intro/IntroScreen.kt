package de.ostlertekker.ultraiptv.feature.intro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import de.ostlertekker.ultraiptv.core.animation.hash01
import de.ostlertekker.ultraiptv.core.audio.AudioExperienceEngine
import de.ostlertekker.ultraiptv.core.design.ResolvedTheme
import de.ostlertekker.ultraiptv.core.model.AnimationProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Logo-Quelle für das Intro. Liegt eine Datei res/drawable(-nodpi)/ostlertekker_logo.(png|xml) im
 * Projekt, wird automatisch das echte Logo verwendet. Sonst dient ein typografischer Platzhalter.
 */
class LogoAsset(val alpha: Bitmap, val color: Bitmap?, val points: FloatArray, val aspect: Float, val isCustom: Boolean)

private fun loadLogo(context: Context, maxPoints: Int): LogoAsset {
    @Suppress("DiscouragedApi")
    val id = context.resources.getIdentifier("ostlertekker_logo", "drawable", context.packageName)
    var color: Bitmap? = null
    val alpha: Bitmap
    if (id != 0) {
        val d = ContextCompat.getDrawable(context, id)!!
        val w = 1600
        val h = (w.toFloat() * d.intrinsicHeight.coerceAtLeast(1) / d.intrinsicWidth.coerceAtLeast(1)).toInt().coerceIn(100, 1600)
        color = d.toBitmap(w, h, Bitmap.Config.ARGB_8888)
        alpha = color.extractAlpha()
    } else {
        val text = "OSTLERTEKKER"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 220f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.06f
            this.color = android.graphics.Color.WHITE
        }
        val w = (paint.measureText(text) + 40).toInt()
        val fm = paint.fontMetrics
        val h = (fm.descent - fm.ascent + 20).toInt()
        alpha = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(alpha).drawText(text, 20f, -fm.ascent + 10f, paint)
    }
    // Punkte für die Partikel-Montage abtasten
    val w = alpha.width; val h = alpha.height
    var step = 4
    var pts: ArrayList<Float>
    while (true) {
        pts = ArrayList()
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if ((alpha.getPixel(x, y) ushr 24) > 140) { pts.add(x.toFloat() / w); pts.add(y.toFloat() / h) }
                x += step
            }
            y += step
        }
        if (pts.size / 2 <= maxPoints || step > 40) break
        step += 2
    }
    return LogoAsset(alpha, color, pts.toFloatArray(), w.toFloat() / h, id != 0)
}

@Composable
fun IntroScreen(full: Boolean, soundEnabled: Boolean, sounds: AudioExperienceEngine?, theme: ResolvedTheme, onFinished: () -> Unit) {
    val context = LocalContext.current
    val finish by rememberUpdatedState(onFinished)
    val k = if (full) 1f else 0.36f
    val total = 7.2f * k
    val maxPoints = when (theme.anim.profile) { AnimationProfile.ECONOMY -> 450; AnimationProfile.BALANCED -> 900; AnimationProfile.ULTRA -> 1400 }
    val logo by produceState<LogoAsset?>(null) { value = withContext(Dispatchers.Default) { loadLogo(context, maxPoints) } }
    val time = remember { mutableFloatStateOf(0f) }
    var done by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val p = theme.palette

    LaunchedEffect(logo) {
        if (logo == null) return@LaunchedEffect
        if (soundEnabled) sounds?.playIntro(short = !full)
        val start = withFrameNanos { it }
        while (isActive && !done) {
            withFrameNanos { now -> time.floatValue = (now - start) / 1e9f }
            if (time.floatValue >= total) { done = true; finish() }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val pointPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND } }
    val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) } }
    val glowPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(28f, BlurMaskFilter.Blur.NORMAL) } }
    val buffer = remember { FloatArray(2 * 1500) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyUp && !done) { done = true; finish() }
                true
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val asset = logo ?: return@Canvas
            val t = time.floatValue
            val w = size.width; val h = size.height
            fun T(sec: Float) = sec * k
            fun ramp(a: Float, b: Float) = ((t - T(a)) / (T(b) - T(a))).coerceIn(0f, 1f)

            // Logo-Box
            val boxW = min(w * 0.72f, h * 0.9f * asset.aspect)
            val boxH = boxW / asset.aspect
            val left = (w - boxW) / 2; val top = h * 0.40f - boxH / 2
            val dst = RectF(left, top, left + boxW, top + boxH)

            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                // 2. Atmosphärische Partikel + 4. Montage zum Logo
                val n = min(asset.points.size / 2, 1500)
                val appear = ramp(0.6f, 1.6f)
                val logoSolid = ramp(3.7f, 4.5f)
                if (appear > 0f && logoSolid < 1f) {
                    for (i in 0 until n) {
                        val sx = hash01(i, 1) * w; val sy = hash01(i, 2) * h
                        val tx = left + asset.points[i * 2] * boxW; val ty = top + asset.points[i * 2 + 1] * boxH
                        val delay = hash01(i, 3) * 0.7f
                        val raw = ((t - T(2.0f + delay)) / T(1.5f)).coerceIn(0f, 1f)
                        val e = 1f - (1f - raw) * (1f - raw) * (1f - raw)
                        val drift = (1f - e) * 18f
                        buffer[i * 2] = sx + (tx - sx) * e + sin(t * 1.3f + i) * drift
                        buffer[i * 2 + 1] = sy + (ty - sy) * e + sin(t * 1.1f + i * 0.7f) * drift
                    }
                    pointPaint.strokeWidth = max(2f, boxW / 520f)
                    pointPaint.color = p.particle.copy(alpha = appear * (1f - logoSolid)).toArgb()
                    nc.drawPoints(buffer, 0, n * 2, pointPaint)
                }

                // 3. Kinematische Lichtspuren
                for (s in 0 until 3) {
                    val prog = ((t - T(1.2f + s * 0.25f)) / T(1.4f))
                    if (prog <= 0f || prog >= 1.3f) continue
                    val segs = 26
                    for (j in 0 until segs) {
                        val u0 = (prog - 0.3f) + j * 0.3f / segs
                        val u1 = u0 + 0.3f / segs
                        if (u0 < 0f || u1 > 1f) continue
                        fun px(u: Float) = -0.1f * w + u * 1.2f * w
                        fun py(u: Float) = h * (0.25f + 0.18f * s) + sin(u * 3.2f + s) * h * 0.08f
                        paint.shader = null
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 3f
                        paint.strokeCap = Paint.Cap.ROUND
                        val col = if (s % 2 == 0) p.primary else p.secondary
                        paint.color = col.copy(alpha = (j.toFloat() / segs) * 0.85f).toArgb()
                        nc.drawLine(px(u0), py(u0), px(u1), py(u1), paint)
                    }
                }
                paint.style = Paint.Style.FILL

                // 5./6. Dreidimensionales Logo mit metallischer Reflexion
                if (logoSolid > 0f) {
                    val src = Rect(0, 0, asset.alpha.width, asset.alpha.height)
                    val depth = (boxH * 0.06f).coerceAtLeast(4f)
                    val layers = if (theme.anim.profile == AnimationProfile.ECONOMY) 4 else 9
                    paint.shader = null
                    paint.colorFilter = null
                    for (l in layers downTo 1) {
                        val off = depth * l / layers
                        val shade = 0.10f + 0.25f * (1f - l / layers.toFloat())
                        paint.color = Color(shade * 0.7f, shade * 0.6f, shade, logoSolid).toArgb()
                        nc.drawBitmap(asset.alpha, src, RectF(dst.left + off * 0.6f, dst.top + off, dst.right + off * 0.6f, dst.bottom + off), paint)
                    }
                    // Dimensionales Licht (Glow)
                    glowPaint.color = p.glow.copy(alpha = 0.55f * logoSolid).toArgb()
                    nc.drawBitmap(asset.alpha, src, dst, glowPaint)
                    // Metallische Oberfläche
                    val sweep = ramp(4.0f, 5.4f)
                    val hx = dst.left - boxW * 0.3f + sweep * boxW * 1.6f
                    paint.shader = LinearGradient(dst.left, dst.top, dst.left, dst.bottom,
                        intArrayOf(0xFFF4F7FF.toInt(), 0xFF9AA3B8.toInt(), 0xFFE9EEF9.toInt(), 0xFF6D7487.toInt(), 0xFFD7DCE8.toInt()),
                        floatArrayOf(0f, 0.38f, 0.5f, 0.62f, 1f), Shader.TileMode.CLAMP)
                    paint.alpha = (255 * logoSolid).toInt()
                    if (asset.color != null) {
                        paint.shader = null
                        nc.drawBitmap(asset.color, src, dst, paint)
                    } else {
                        nc.drawBitmap(asset.alpha, src, dst, paint)
                    }
                    // Wandernder Glanzstreifen
                    if (sweep in 0.001f..0.999f) {
                        paint.shader = LinearGradient(hx - boxW * 0.12f, dst.top, hx + boxW * 0.12f, dst.bottom,
                            intArrayOf(0x00FFFFFF, 0xCCFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                        paint.alpha = 255
                        nc.drawBitmap(asset.alpha, src, dst, paint)
                    }
                    paint.shader = null
                    paint.colorFilter = null
                }

                // PRESENTS
                val presents = ramp(4.6f, 5.3f)
                if (presents > 0f) {
                    textPaint.shader = null
                    textPaint.textSize = boxH * 0.24f
                    textPaint.letterSpacing = 0.6f
                    textPaint.color = p.textDim.copy(alpha = presents).toArgb()
                    textPaint.textAlign = Paint.Align.CENTER
                    nc.drawText("PRESENTS", w / 2, dst.bottom + boxH * 0.42f, textPaint)
                }

                // 8. ULTRA IPTV
                val ultra = ramp(5.2f, 6.2f)
                if (ultra > 0f) {
                    val scale = 0.92f + 0.08f * ultra
                    textPaint.textSize = h * 0.12f * scale
                    textPaint.letterSpacing = 0.12f
                    textPaint.textAlign = Paint.Align.CENTER
                    val y = h * 0.80f
                    textPaint.shader = null
                    textPaint.color = p.glow.copy(alpha = 0.6f * ultra).toArgb()
                    textPaint.maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
                    nc.drawText("ULTRA IPTV", w / 2, y, textPaint)
                    textPaint.maskFilter = null
                    textPaint.shader = LinearGradient(w * 0.3f, 0f, w * 0.7f, 0f,
                        intArrayOf(p.primary.toArgb(), android.graphics.Color.WHITE, p.secondary.toArgb()), null, Shader.TileMode.CLAMP)
                    textPaint.alpha = (255 * ultra).toInt()
                    nc.drawText("ULTRA IPTV", w / 2, y, textPaint)
                    textPaint.shader = null
                    textPaint.textSize = h * 0.025f
                    textPaint.letterSpacing = 0.5f
                    textPaint.color = p.textDim.copy(alpha = ultra).toArgb()
                    nc.drawText("THE ULTIMATE STREAMING EXPERIENCE", w / 2, y + h * 0.06f, textPaint)
                }
            }
            // 9. Übergang
            val fadeOut = ramp(6.7f, 7.2f)
            if (fadeOut > 0f) drawRect(Color.Black.copy(alpha = fadeOut))
        }
    }
}
