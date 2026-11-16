package de.ostlertekker.ultraiptv.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import de.ostlertekker.ultraiptv.core.animation.fract
import de.ostlertekker.ultraiptv.core.animation.hash01
import de.ostlertekker.ultraiptv.core.animation.rememberAnimationClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Das animierte App-Logo: ein giftiger Planet, der Risse bekommt, explodiert und sich neu formt.
 * Alles wird pro Frame aus vorberechneten Tabellen gezeichnet – keine Allokationen außer zwei Path-Objekten.
 */
object ToxicPlanet {
    val acid = Color(0xFFB6FF1A)
    val toxic = Color(0xFF6BE01A)
    val deep = Color(0xFF0E2A05)
    val swamp = Color(0xFF2F7A0C)
    val purple = Color(0xFF6A2A8C)
    val hot = Color(0xFFF4FFC8)

    private const val FRAGMENTS = 34
    private const val CRACKS = 9
    private const val CRACK_SEGS = 7

    // Bruchstücke: Richtung, Geschwindigkeit, Größe, Drehung
    private val fragDir = FloatArray(FRAGMENTS) { (hash01(it, 41) * 2f * PI.toFloat()) }
    private val fragSpeed = FloatArray(FRAGMENTS) { 0.55f + hash01(it, 42) * 1.25f }
    private val fragSize = FloatArray(FRAGMENTS) { 0.05f + hash01(it, 43) * 0.11f }
    private val fragSpin = FloatArray(FRAGMENTS) { (hash01(it, 44) - 0.5f) * 720f }

    // Risse: gezackte Linien vom Explosionszentrum (normiert auf Radius 1)
    private val crackPts = FloatArray(CRACKS * (CRACK_SEGS + 1) * 2).also { a ->
        for (c in 0 until CRACKS) {
            var ang = c / CRACKS.toFloat() * 2f * PI.toFloat() + hash01(c, 51)
            var x = 0.32f; var y = -0.25f
            val len = 0.7f + hash01(c, 52) * 0.6f
            for (s in 0..CRACK_SEGS) {
                val i = (c * (CRACK_SEGS + 1) + s) * 2
                a[i] = x; a[i + 1] = y
                ang += (hash01(c * 13 + s, 53) - 0.5f) * 0.9f
                x += cos(ang) * len / CRACK_SEGS; y += sin(ang) * len / CRACK_SEGS
            }
        }
    }

    // Giftige Seen auf der Oberfläche (x-Phase, y, Radius, Farbe 0=hell 1=lila)
    private val spots = Array(9) { i -> floatArrayOf(hash01(i, 61), hash01(i, 62) * 1.6f - 0.8f, 0.12f + hash01(i, 63) * 0.22f, if (i % 4 == 3) 1f else 0f) }

    private val explodeAt = Offset(0.32f, -0.25f)

    /**
     * Zeichnet den Planeten.
     * @param spin Oberflächenrotation (Sekunden)
     * @param crack 0..1 Risswachstum
     * @param boom 0..1 Explosionsfortschritt (0 = intakt)
     * @param reform 0..1 Neuformung nach der Explosion (1 = komplett)
     */
    fun DrawScope.drawToxicPlanet(center: Offset, radius: Float, spin: Float, crack: Float, boom: Float, reform: Float = 1f, detail: Boolean = true) {
        val r = radius
        val shake = if (crack > 0f && boom == 0f) crack * crack * r * 0.035f else 0f
        val c = Offset(center.x + sin(spin * 71f) * shake, center.y + cos(spin * 53f) * shake)
        val ex = Offset(c.x + explodeAt.x * r, c.y + explodeAt.y * r)

        // Leuchthof
        val haloA = (0.35f + 0.35f * crack) * (1f - boom * 0.6f)
        drawCircle(Brush.radialGradient(listOf(toxic.copy(alpha = haloA), Color.Transparent), c, r * 2.1f), r * 2.1f, c)

        // Planetenkörper (verschwindet in der Explosion, wächst danach elastisch nach)
        val bodyScale = when {
            boom > 0f && boom < 1f -> (1f - boom * 2.2f).coerceAtLeast(0f)
            else -> reform
        }
        if (bodyScale > 0.01f) {
            val br = r * bodyScale
            drawCircle(Brush.radialGradient(listOf(toxic, swamp, deep), Offset(c.x - br * 0.35f, c.y - br * 0.4f), br * 1.5f), br, c)
            if (detail) {
                val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, br)) }
                clipPath(clip) {
                    for (sp in spots) {
                        val px = c.x + (fract(sp[0] + spin * 0.035f) * 2.6f - 1.3f) * br
                        val py = c.y + sp[1] * br
                        val sr = sp[2] * br
                        val col = if (sp[3] > 0.5f) purple.copy(alpha = 0.55f) else acid.copy(alpha = 0.5f)
                        drawCircle(Brush.radialGradient(listOf(col, Color.Transparent), Offset(px, py), sr), sr, Offset(px, py))
                    }
                }
            }
            // Atmosphäre
            drawCircle(acid.copy(alpha = 0.55f), br, c, style = Stroke(br * 0.045f))
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, toxic.copy(alpha = 0.45f)), c, br), br, c)
        }

        // Risse
        if (crack > 0f && boom < 0.25f) {
            val alpha = (1f - boom * 4f).coerceIn(0f, 1f)
            val visibleSegs = crack * CRACK_SEGS
            for (k in 0 until CRACKS) {
                for (s in 0 until CRACK_SEGS) {
                    val part = (visibleSegs - s).coerceIn(0f, 1f)
                    if (part <= 0f) break
                    val i = (k * (CRACK_SEGS + 1) + s) * 2
                    val a = Offset(c.x + crackPts[i] * r, c.y + crackPts[i + 1] * r)
                    val bx = crackPts[i + 2]; val by = crackPts[i + 3]
                    val b = Offset(c.x + (crackPts[i] + (bx - crackPts[i]) * part) * r, c.y + (crackPts[i + 1] + (by - crackPts[i + 1]) * part) * r)
                    drawLine(acid.copy(alpha = 0.35f * alpha), a, b, r * 0.09f, cap = StrokeCap.Round)
                    drawLine(hot.copy(alpha = alpha), a, b, r * 0.028f, cap = StrokeCap.Round)
                }
            }
            // glühender Kern
            val coreR = r * (0.12f + 0.35f * crack * crack)
            drawCircle(Brush.radialGradient(listOf(hot.copy(alpha = crack * alpha), acid.copy(alpha = 0.5f * crack * alpha), Color.Transparent), ex, coreR), coreR, ex)
        }

        if (boom > 0f && boom < 1f) {
            val e = 1f - (1f - boom) * (1f - boom) * (1f - boom)
            val fade = (1f - boom).coerceIn(0f, 1f)
            // Lichtblitz
            val flashR = r * (0.4f + 2.6f * e)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = fade * fade), hot.copy(alpha = 0.6f * fade), acid.copy(alpha = 0.25f * fade), Color.Transparent), ex, flashR), flashR, ex)
            // Strahlen
            if (detail) for (k in 0 until 16) {
                val a = k / 16f * 2f * PI.toFloat() + hash01(k, 71)
                val len = r * (0.6f + 2.2f * e) * (0.6f + 0.4f * hash01(k, 72))
                drawLine(hot.copy(alpha = 0.5f * fade), ex, Offset(ex.x + cos(a) * len, ex.y + sin(a) * len), r * 0.02f)
            }
            // Schockwelle (geneigte Ellipse)
            val ringR = r * (0.5f + 3.0f * e)
            rotate(-18f, ex) {
                drawOval(acid.copy(alpha = 0.85f * fade), Offset(ex.x - ringR, ex.y - ringR * 0.36f), Size(ringR * 2, ringR * 0.72f), style = Stroke(r * 0.06f * fade + 1f))
            }
            // Bruchstücke
            val frag = Path()
            for (i in 0 until FRAGMENTS) {
                val d = r * (0.2f + fragSpeed[i] * 2.6f * e)
                val fx = c.x + cos(fragDir[i]) * d
                val fy = c.y + sin(fragDir[i]) * d + e * e * r * 0.25f
                val s = r * fragSize[i] * (1f - 0.4f * e)
                rotate(fragSpin[i] * e, Offset(fx, fy)) {
                    frag.reset()
                    frag.moveTo(fx - s, fy - s * 0.4f); frag.lineTo(fx + s * 0.2f, fy - s); frag.lineTo(fx + s, fy + s * 0.1f)
                    frag.lineTo(fx + s * 0.1f, fy + s * 0.9f); frag.lineTo(fx - s * 0.8f, fy + s * 0.6f); frag.close()
                    drawPath(frag, (if (i % 3 == 0) swamp else toxic).copy(alpha = fade))
                    drawPath(frag, hot.copy(alpha = fade * 0.9f), style = Stroke(s * 0.18f + 0.5f))
                }
            }
        }
    }

    /** Endlosschleife: Ruhe → Risse → Explosion → Neuformung. Liefert (crack, boom, reform). */
    fun loopPhases(t: Float, cycle: Float): Triple<Float, Float, Float> {
        val u = fract(t / cycle) * cycle
        val calm = cycle * 0.45f; val crackEnd = cycle * 0.62f; val boomEnd = cycle * 0.80f
        return when {
            u < calm -> Triple(0f, 0f, 1f)
            u < crackEnd -> Triple((u - calm) / (crackEnd - calm), 0f, 1f)
            u < boomEnd -> Triple(1f, (u - crackEnd) / (boomEnd - crackEnd), 0f)
            else -> {
                val x = ((u - boomEnd) / (cycle - boomEnd)).coerceIn(0f, 1f)
                // elastisches Nachwachsen
                val el = if (x >= 1f) 1f else 1f - (2f.toDouble().let { Math.pow(it, -10.0 * x) }).toFloat() * cos((x * 10f - 0.75f) * (2f * PI.toFloat() / 3f))
                Triple(0f, 0f, el.coerceIn(0f, 1.15f))
            }
        }
    }
}

/** Animiertes Logo (Endlosschleife), z. B. oben in der Menüleiste. */
@Composable
fun ToxicPlanetLogo(modifier: Modifier, running: Boolean, cycleSeconds: Float = 9f, detail: Boolean = true) {
    val clock = rememberAnimationClock(running, fps = 30, pauseOnInput = true)
    Canvas(modifier) {
        val t = clock.value
        val (crack, boom, reform) = ToxicPlanet.loopPhases(t, cycleSeconds)
        with(ToxicPlanet) {
            drawToxicPlanet(Offset(size.width / 2, size.height / 2), size.minDimension * 0.30f, t, crack, boom, reform, detail)
        }
    }
}
