package de.ostlertekker.ultraiptv.feature.intro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ostlertekker.ultraiptv.core.audio.AudioExperienceEngine
import de.ostlertekker.ultraiptv.core.model.AnimationProfile
import de.ostlertekker.ultraiptv.core.design.AppFonts
import de.ostlertekker.ultraiptv.core.design.ResolvedTheme
import kotlinx.coroutines.isActive

/**
 * Zeitplan des Intros in Sekunden (volle Länge); die Kurzfassung läuft mit [SHORT] schneller ab.
 * Abgestimmt auf den Intro-Klang: Puls bei 0,35 s (Symbol erscheint), Rauschen ab 1,55 s, „Bling“ bei 1,95 s (Logo rastet ein).
 */
object IntroTiming {
    const val BEAM_START = 0.1f
    const val BEAM_DUR = 1.3f
    const val U_DRAW_START = 0.35f
    const val U_DRAW_DUR = 1.2f
    const val U_FILL_START = 1.4f
    const val LOCK = 1.95f            // Logo rastet ein (Bling)
    const val WORD_START = 1.65f
    const val WORD_DUR = 0.9f
    const val SHINE_START = 1.95f
    const val SHINE_DUR = 0.8f
    const val SIGN_START = 2.6f
    const val END = 5.0f
    const val FADE = 0.55f
    const val SHORT = 0.62f
}

private fun clamp01(v: Float) = v.coerceIn(0f, 1f)
private fun easeOutCubic(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
private fun easeInOutCubic(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
private fun phase(t: Float, start: Float, dur: Float) = clamp01((t - start) / dur)

// Farbwelt: Schwarz/Anthrazit mit gebürstetem Silber
private val Silver = listOf(Color(0xFF7D838C), Color(0xFFF4F6F9), Color(0xFFA9AEB6), Color(0xFFE9EBEF), Color(0xFF6F747C))
private val Anthracite = Color(0xFF15171B)

/**
 * Premium-Intro: In dunklem Raum läuft eine Lichtlinie auf, das „U“-Markensymbol zeichnet sich als Lichtkontur und füllt sich
 * mit gebürstetem Silber, rastet mit einem Glanz ein; „ULTRA IPTV“ zieht sich elegant zusammen, darunter die Gründer-Signatur
 * „FOUNDED BY OSTLERTEKKER & HORSTYHAZE“. Weicher Übergang in die App, jede Taste überspringt.
 * Alles wird nur in der Zeichenphase animiert (ein Zeitwert, keine Neukomposition pro Frame); auf schwachen Geräten
 * (Sparprofil/reduzierte Bewegung) entfallen Partikel und Lichtschleier.
 */
@Composable
fun IntroScreen(full: Boolean, soundEnabled: Boolean, sounds: AudioExperienceEngine?, theme: ResolvedTheme, onFinished: () -> Unit) {
    val finish by rememberUpdatedState(onFinished)
    val k = if (full) 1f else IntroTiming.SHORT
    val time = remember { mutableFloatStateOf(0f) }
    var done by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val lite = theme.anim.reduceMotion || theme.anim.profile == AnimationProfile.ECONOMY

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        // Erst wenn der Ton startet, beginnt die Animation (Bild und Klang synchron)
        if (soundEnabled) runCatching { sounds?.playIntroAligned(short = !full) }
        val start = withFrameNanos { it }
        while (isActive && !done) {
            withFrameNanos { now -> time.floatValue = (now - start) / 1e9f / k }
            if (time.floatValue >= IntroTiming.END) { done = true; finish() }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyUp && !done) { done = true; finish() }
                true
            }
            .graphicsLayer {
                // Übergang: sanft ausblenden und minimal heranzoomen
                val f = phase(time.floatValue, IntroTiming.END - IntroTiming.FADE, IntroTiming.FADE)
                alpha = 1f - easeInOutCubic(f)
                val sc = 1f + 0.04f * easeInOutCubic(f); scaleX = sc; scaleY = sc
            },
        contentAlignment = Alignment.Center,
    ) {
        Backdrop(time, lite)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 48.dp)) {
            UMark(time, Modifier.size(width = 104.dp, height = 112.dp))
            Spacer(Modifier.height(26.dp))
            Wordmark(time)
            Spacer(Modifier.height(30.dp))
            Signature(time)
        }
    }
}

/** Szene 1: Anthrazit-Lichtraum, auflaufende Lichtlinie, feine Partikel. */
@Composable
private fun Backdrop(time: State<Float>, lite: Boolean) {
    val dust = remember {
        val r = java.util.Random(7)
        List(if (lite) 0 else 26) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 1.6f, 0.15f + r.nextFloat() * 0.35f, r.nextFloat() * 6.28f) }
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = time.value
        val c = Offset(size.width / 2f, size.height * 0.46f)
        // Raumlicht: Anthrazit-Kern, der sich langsam öffnet
        val open = easeOutCubic(phase(t, 0f, 1.4f))
        drawRect(Brush.radialGradient(listOf(Anthracite.copy(alpha = 0.95f * open), Color.Black), c, size.maxDimension * (0.35f + 0.25f * open)))
        // Lichtlinie: dünner Horizont, der von der Mitte nach außen läuft und als Schleier stehen bleibt
        val b = easeInOutCubic(phase(t, IntroTiming.BEAM_START, IntroTiming.BEAM_DUR))
        if (b > 0f) {
            val half = size.width * 0.42f * b
            val y = c.y + size.height * 0.02f
            val fadeOut = 1f - 0.65f * phase(t, IntroTiming.LOCK, 1.2f)
            drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.55f * fadeOut), Color.Transparent), c.x - half, c.x + half),
                Offset(c.x - half, y), Offset(c.x + half, y), strokeWidth = 1.2.dp.toPx())
            if (!lite) drawOval(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f * b * fadeOut), Color.Transparent), Offset(c.x, y), half.coerceAtLeast(1f)),
                topLeft = Offset(c.x - half, y - size.height * 0.18f), size = androidx.compose.ui.geometry.Size(half * 2, size.height * 0.36f))
        }
        // Feiner Staub im Licht
        if (dust.isNotEmpty()) {
            val a0 = phase(t, 0.6f, 1.2f)
            dust.forEach { d ->
                val x = d[0] * size.width + kotlin.math.sin(t * 0.4f + d[4]) * 14f
                val y = ((d[1] - t * 0.012f * d[2]) % 1f + 1f) % 1f * size.height
                drawCircle(Color.White.copy(alpha = d[3] * a0 * 0.5f), d[2].dp.toPx(), Offset(x, y))
            }
        }
    }
}

/** Stilisiertes „U“: zwei Schenkel mit runder Basis, rechter Schenkel mit diagonalem Anschnitt; Lichtkontur → Silber. */
private fun uPath(w: Float, h: Float, stroke: Float): Path {
    val l = stroke / 2f; val r = w - stroke / 2f
    val radius = (r - l) / 2f
    val bottom = h - stroke / 2f
    return Path().apply {
        moveTo(l, stroke * 0.2f)
        lineTo(l, bottom - radius)
        arcTo(androidx.compose.ui.geometry.Rect(l, bottom - 2 * radius, r, bottom), 180f, -180f, false)
        lineTo(r, h * 0.16f)
    }
}

@Composable
private fun UMark(time: State<Float>, modifier: Modifier) {
    Canvas(modifier.graphicsLayer {
        // leichtes Einrasten
        val lock = phase(time.value, IntroTiming.LOCK - 0.25f, 0.5f)
        val s = 0.96f + 0.04f * easeOutCubic(lock); scaleX = s; scaleY = s
        compositingStrategy = CompositingStrategy.Offscreen // Glanz und Anschnitt nur auf dem Symbol
    }) {
        val t = time.value
        val stroke = size.width * 0.2f
        val path = uPath(size.width, size.height, stroke)
        val draw = easeInOutCubic(phase(t, IntroTiming.U_DRAW_START, IntroTiming.U_DRAW_DUR))
        val fill = easeOutCubic(phase(t, IntroTiming.U_FILL_START, IntroTiming.LOCK - IntroTiming.U_FILL_START + 0.15f))
        if (draw <= 0f) return@Canvas
        // 1) Lichtkontur, die die Form nachzeichnet
        val pm = PathMeasure().apply { setPath(path, false) }
        val seg = Path()
        pm.getSegment(0f, pm.length * draw, seg, true)
        drawPath(seg, Color.White.copy(alpha = 0.9f * (1f - fill)), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
        // Lichtpunkt an der Zeichenspitze
        if (draw < 1f) {
            val tip = pm.getPosition(pm.length * draw)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), Color.Transparent), tip, 14.dp.toPx()), 14.dp.toPx(), tip)
        }
        // 2) Gebürstetes Silber füllt den vollen Strich
        if (fill > 0f) {
            drawPath(path, Brush.linearGradient(Silver, Offset(0f, 0f), Offset(size.width, size.height)),
                alpha = fill, style = Stroke(stroke, cap = StrokeCap.Butt))
            // diagonaler Anschnitt oben rechts (Markenmerkmal)
            val cut = Path().apply {
                moveTo(size.width - stroke - 1f, size.height * 0.16f - stroke * 0.55f)
                lineTo(size.width + 1f, size.height * 0.16f - stroke * 0.55f)
                lineTo(size.width + 1f, size.height * 0.16f + stroke * 0.25f)
                close()
            }
            drawPath(cut, Color.Black, blendMode = BlendMode.Clear)
            shine(t, IntroTiming.LOCK - 0.05f, 0.6f, fill)
        }
        // 3) Glanz beim Einrasten
        val g = phase(t, IntroTiming.LOCK, 0.7f)
        if (g > 0f && g < 1f) {
            val p = Offset(size.width - stroke / 2f, size.height * 0.16f)
            val a = if (g < 0.2f) g / 0.2f else 1f - easeOutCubic((g - 0.2f) / 0.8f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f * a), Color.Transparent), p, stroke * 1.6f), stroke * 1.6f, p)
        }
    }
}

/** Schmaler Glanzstreifen, der diagonal über das bereits Gezeichnete läuft (nur auf vorhandenen Pixeln). */
private fun DrawScope.shine(t: Float, start: Float, dur: Float, strength: Float = 1f) {
    val s = phase(t, start, dur)
    if (s <= 0f || s >= 1f) return
    val x = -size.width * 0.5f + s * size.width * 2f
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent, 0.42f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.85f * strength),
            0.58f to Color.Transparent, 1f to Color.Transparent,
            start = Offset(x - size.height, 0f), end = Offset(x + size.height * 0.4f, size.height),
        ),
        blendMode = BlendMode.SrcAtop,
    )
}

/** Szene 2: „ULTRA IPTV“ – kräftiges ULTRA in Silber, leichtes IPTV; zieht sich beim Erscheinen zusammen, dann Glanz. */
@Composable
private fun Wordmark(time: State<Float>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer {
                val p = easeOutCubic(phase(time.value, IntroTiming.WORD_START, IntroTiming.WORD_DUR))
                alpha = p
                val sx = 1.12f - 0.12f * p; scaleX = sx; scaleY = 1f
                translationY = (1f - p) * 10f
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                // Metallischer Verlauf auf den Buchstaben
                drawRect(Brush.verticalGradient(listOf(Color.White, Color(0xFFD9DCE1), Color(0xFF9DA2AA))), blendMode = BlendMode.SrcAtop)
                shine(time.value, IntroTiming.SHINE_START, IntroTiming.SHINE_DUR)
            },
    ) {
        Text("ULTRA", color = Color.White, fontSize = 58.sp, lineHeight = 62.sp, fontWeight = FontWeight.Bold,
            fontFamily = AppFonts.Inter, letterSpacing = 10.sp)
        Spacer(Modifier.width(18.dp))
        Text("IPTV", color = Color.White, fontSize = 58.sp, lineHeight = 62.sp, fontWeight = FontWeight.Light,
            fontFamily = AppFonts.Inter, letterSpacing = 10.sp)
    }
}

/** Szene 3: Gründer-Signatur unter einer Haarlinie. */
@Composable
private fun Signature(time: State<Float>) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(1.dp).width(300.dp).graphicsLayer {
            val a = easeInOutCubic(phase(time.value, IntroTiming.SIGN_START, 0.7f))
            scaleX = a; alpha = a * 0.7f
        }.background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFFC9CDD3), Color.Transparent))))
        Spacer(Modifier.height(16.dp))
        Text("FOUNDED BY", color = Color(0xFF8D929A), fontSize = 12.sp, letterSpacing = 7.sp, fontWeight = FontWeight.Medium,
            fontFamily = AppFonts.Inter,
            modifier = Modifier.graphicsLayer {
                val a = easeOutCubic(phase(time.value, IntroTiming.SIGN_START + 0.15f, 0.6f))
                alpha = a; translationY = (1f - a) * 8f
            })
        Spacer(Modifier.height(8.dp))
        Text("OSTLERTEKKER & HORSTYHAZE", color = Color(0xFFE4E6EA), fontSize = 19.sp, letterSpacing = 5.sp, fontWeight = FontWeight.Medium,
            fontFamily = AppFonts.Inter,
            modifier = Modifier.graphicsLayer {
                val a = easeOutCubic(phase(time.value, IntroTiming.SIGN_START + 0.3f, 0.7f))
                alpha = a; translationY = (1f - a) * 10f
            })
    }
}
