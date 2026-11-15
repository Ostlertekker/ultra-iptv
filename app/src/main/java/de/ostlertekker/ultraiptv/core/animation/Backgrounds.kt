package de.ostlertekker.ultraiptv.core.animation

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil.compose.AsyncImage
import de.ostlertekker.ultraiptv.core.design.AnimationConfig
import de.ostlertekker.ultraiptv.core.design.BackgroundEffect
import de.ostlertekker.ultraiptv.core.design.ResolvedTheme
import de.ostlertekker.ultraiptv.core.design.UltraPalette
import de.ostlertekker.ultraiptv.core.model.AnimationProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Vorab berechnete Partikel – keine Allokation pro Frame. */
class ParticleField(val count: Int, seed: Int) {
    val x = FloatArray(count)
    val y = FloatArray(count)
    val speed = FloatArray(count)
    val size = FloatArray(count)
    val phase = FloatArray(count)

    init {
        val r = Random(seed)
        for (i in 0 until count) {
            x[i] = r.nextFloat(); y[i] = r.nextFloat(); speed[i] = r.nextFloat()
            size[i] = r.nextFloat(); phase[i] = r.nextFloat() * 6.2832f
        }
    }
}

fun fract(v: Float): Float = v - floor(v)

fun hash01(a: Int, b: Int): Float {
    var h = a * 374761393 + b * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0x7fffffff) / 2147483647f
}

/**
 * Lifecycle-bewusste Animationsuhr: läuft nur, wenn die Activity sichtbar ist.
 * Im Economy-Profil wird auf ca. 30 fps gedrosselt.
 */
@Composable
fun rememberAnimationClock(running: Boolean, throttle: Boolean = false, fps: Int = 60): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val active = running && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val base = time.floatValue
        val start = withFrameNanos { it }
        // Bildratenbegrenzung: nur bei Bedarf neu zeichnen (spart GPU/CPU auf Fire TV)
        val minFrameNs = 1_000_000_000L / (if (throttle) minOf(fps, 20) else fps).coerceIn(10, 60)
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (now - last >= minFrameNs - 2_000_000L) { last = now; time.floatValue = base + (now - start) / 1_000_000_000f }
            }
        }
    }
    return time
}

@Composable
fun ThemeBackground(theme: ResolvedTheme, modifier: Modifier = Modifier, animate: Boolean = true, customImage: String = "") {
    val anim = theme.anim
    // Langsame Hintergrundbewegung braucht keine 60 fps – 30 fps sehen gleich aus und halbieren die Last
    val clock = rememberAnimationClock(animate && anim.backgroundMotion, throttle = anim.profile == AnimationProfile.ECONOMY,
        fps = if (anim.profile == AnimationProfile.ULTRA) 40 else 30)
    val count = (theme.spec.baseParticles * anim.particleFactor).toInt().coerceIn(0, 420)
    val field = remember(count, theme.spec.id) { ParticleField(count, theme.spec.id.hashCode()) }
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = 20f }
    }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val t = clock.value * (0.4f + 0.6f * anim.intensity)
            drawThemeEffect(theme.spec.effect, theme.palette, field, t, anim, paint)
        }
        if (customImage.isNotBlank()) {
            AsyncImage(model = customImage, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.45f))
        }
    }
}

fun DrawScope.drawThemeEffect(effect: BackgroundEffect, p: UltraPalette, f: ParticleField, t: Float, a: AnimationConfig, paint: Paint) {
    drawRect(Brush.verticalGradient(listOf(p.bgTop, p.bgBottom)))
    when (effect) {
        BackgroundEffect.GALAXY -> drawGalaxy(p, f, t, a)
        BackgroundEffect.GOLD -> drawGold(p, f, t, a)
        BackgroundEffect.CYBER_GRID -> drawCyber(p, f, t, a)
        BackgroundEffect.GLASS -> drawGlass(p, f, t, a)
        BackgroundEffect.AURORA -> drawAurora(p, f, t, a)
        BackgroundEffect.OCEAN -> drawOcean(p, f, t, a)
        BackgroundEffect.MATRIX -> drawMatrix(p, t, a, paint)
        BackgroundEffect.SPACE -> drawSpace(p, f, t, a)
        BackgroundEffect.DIAMOND -> drawDiamond(p, f, t, a)
        BackgroundEffect.INFERNO -> drawInferno(p, f, t, a)
    }
    // Leichte Vignette für Lesbarkeit
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
        center = Offset(size.width * 0.5f, size.height * 0.5f), radius = max(size.width, size.height) * 0.75f))
}

private val nebulaSpots = floatArrayOf(0.22f, 0.30f, 0.78f, 0.62f, 0.55f, 0.12f, 0.35f, 0.85f)

private fun DrawScope.drawNebula(colors: List<Color>, t: Float, strength: Float) {
    val w = size.width; val h = size.height
    for (i in 0 until 4) {
        val cx = nebulaSpots[i * 2] * w + sin(t * 0.05f + i * 2f) * w * 0.05f
        val cy = nebulaSpots[i * 2 + 1] * h + cos(t * 0.04f + i) * h * 0.05f
        val r = w * (0.32f + 0.05f * sin(t * 0.1f + i))
        val c = colors[i % colors.size]
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.26f * strength), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
    }
}

private fun DrawScope.drawStars(p: UltraPalette, f: ParticleField, t: Float, drift: Float, maxRadius: Float) {
    val w = size.width; val h = size.height
    for (i in 0 until f.count) {
        val depth = f.size[i]
        val x = fract(f.x[i] + t * drift * (0.3f + depth)) * w
        val y = f.y[i] * h
        val tw = 0.55f + 0.45f * sin(t * (0.8f + f.speed[i] * 2.2f) + f.phase[i])
        val c = if (i % 7 == 0) p.particle2 else p.particle
        drawCircle(c.copy(alpha = ((0.3f + 0.7f * depth) * tw).coerceIn(0f, 1f)), 0.7f + depth * maxRadius, Offset(x, y))
    }
}

/** Sternschnuppen in unregelmäßigen Abständen auf natürlichen, leicht gekrümmten Bahnen. */
fun DrawScope.drawShootingStars(color: Color, t: Float, slots: Int, areaWidth: Float = size.width, areaHeight: Float = size.height) {
    for (s in 0 until slots) {
        val period = 5.5f + s * 3.3f + hash01(s, 7) * 3f
        val shifted = t + s * 1.7f
        val cycle = floor(shifted / period).toInt()
        val local = shifted - cycle * period
        if (hash01(cycle, s + 11) < 0.3f) continue
        val dur = 1.0f + hash01(cycle, s) * 0.7f
        if (local > dur) continue
        val prog = local / dur
        val sx = (0.05f + hash01(cycle, s + 3) * 0.75f) * areaWidth
        val sy = hash01(cycle, s + 5) * 0.45f * areaHeight
        val angle = (14f + hash01(cycle, s + 9) * 26f) * (PI.toFloat() / 180f)
        val len = areaWidth * (0.22f + 0.16f * hash01(cycle, s + 13))
        val dx = cos(angle); val dy = sin(angle)
        val hx = sx + dx * len * prog
        val hy = sy + dy * len * prog + prog * prog * areaHeight * 0.03f
        val tail = len * 0.22f
        val head = Offset(hx, hy)
        val tailP = Offset(hx - dx * tail, hy - dy * tail)
        val alpha = sin(prog * PI.toFloat()).coerceIn(0f, 1f)
        drawLine(Brush.linearGradient(listOf(Color.Transparent, color.copy(alpha = alpha)), tailP, head), tailP, head, strokeWidth = 2.4f, cap = StrokeCap.Round)
        drawCircle(color.copy(alpha = alpha), 2.6f, head)
        drawCircle(color.copy(alpha = alpha * 0.25f), 7f, head)
    }
}

private fun DrawScope.drawGalaxy(p: UltraPalette, f: ParticleField, t: Float, a: AnimationConfig) {
    drawNebula(listOf(p.secondary, p.primary, p.glow, p.particle2), t, a.intensity)
    drawStars(p, f, t, 0.004f, 2.3f)
    // Kosmischer Staub
    val w = size.width; val h = size.height
    for (i in 0 until f.count / 3) {
        val x = fract(f.y[i] + t * 0.01f) * w
        val y = fract(f.x[i] + sin(t * 0.1f + f.phase[i]) * 0.02f) * h
        drawCircle(p.glow.copy(alpha = 0.06f), 6f + f.size[i] * 10f, Offset(x, y))
    }
    if (a.profile != AnimationProfile.ECONOMY && !a.reduceMotion) drawShootingStars(p.particle, t, if (a.profile == AnimationProfile.ULTRA) 3 else 2)
}

private fun DrawScope.drawGold(p: UltraPalette, f: ParticleField, t: Float, a: AnimationConfig) {
    val w = size.width; val h = size.height
    val bx = fract(t * 0.04f) * w * 1.8f - w * 0.4f
    drawRect(Brush.linearGradient(listOf(Color.Transparent, p.primary.copy(alpha = 0.10f * a.intensity), Color.Transparent),
        start = Offset(bx, 0f), end = Offset(bx + w * 0.35f, h)))
    for (i in 0 until f.count) {
        val y = fract(f.y[i] - t * 0.015f * (0.4f + f.speed[i])) * h
        val x = f.x[i] * w + sin(t * 0.6f + f.phase[i]) * 12f
        val tw = 0.5f + 0.5f * sin(t * 1.4f + f.phase[i])
        drawCircle(p.particle.copy(alpha = 0.25f + 0.55f * tw * f.size[i]), 0.8f + f.size[i] * 2.6f, Offset(x, y))
    }
}

private fun DrawScope.drawCyber(p: UltraPalette, f: ParticleField, t: Float, a: AnimationConfig) {
    val w = size.width; val h = size.height
    val horizon = h * 0.56f
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, p.primary.copy(alpha = 0.18f), Color.Transparent), startY = horizon - 80f, endY = horizon + 80f))
    val vp = Offset(w / 2, horizon)
    for (k in -14..14) {
        drawLine(p.secondary.copy(alpha = 0.18f), vp, Offset(w / 2 + k * w / 9f, h), strokeWidth = 1.2f)
    }
    val n = 14
    for (k in 0 until n) {
        val z = fract(k / n.toFloat() + t * 0.12f)
        val y = horizon + (h - horizon) * z * z
        drawLine(p.primary.copy(alpha = 0.08f + 0.4f * z), Offset(0f, y), Offset(w, y), strokeWidth = 1f + z * 1.5f)
    }
    val scan = fract(t * 0.07f) * h
    drawRect(p.secondary.copy(alpha = 0.06f), topLeft = Offset(0f, scan), size = Size(w, 3f))
    for (i in 0 until f.count) {
        val x = f.x[i] * w; val y = fract(f.y[i] - t * 0.02f * f.speed[i]) * horizon
        drawCircle((if (i % 2 == 0) p.primary else p.secondary).copy(alpha = 0.5f * f.size[i]), 1.2f + f.size[i] * 1.8f, Offset(x, y))
    }
}

private fun DrawScope.drawGlass(p: UltraPalette, f: ParticleField, t: Float, a: AnimationConfig) {
    val w = size.width; val h = size.height
    val blobs = listOf(p.secondary, p.primary, p.glow, p.particle2)
    for (i in blobs.indices) {
        val cx = w * (0.5f + 0.38f * sin(t * 0.07f * (i + 1) + i))
        val cy = h * (0.5f + 0.34f * cos(t * 0.05f * (i + 1) + i * 2))
        val r = w * 0.28f
        drawCircle(Brush.radialGradient(listOf(blobs[i].copy(alpha = 0.22f * a.intensity), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
    }
    drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Transparent, Color.White.copy(alpha = 0.03f)), Offset.Zero, Offset(w, h)))
    for (i in 0 until f.count) {
        val x = fract(f.x[i] + t * 0.003f) * w; val y = f.y[i] * h
        drawCircle(Color.White.copy(alpha = 0.05f + 0.08f * f.size[i]), 10f + f.size[i] * 30f, Offset(x, y))
    }
}

private fun DrawScope.drawAurora(p: UltraPalette, f: ParticleField, t: Float, a: AnimationConfig) {
    val w = size.width; val h = size.height
    for (i in 0 until f.count / 2) {
        drawCircle(Color.White.copy(alpha = 0.25f + 0.4f * f.size[i] * (0.5f + 0.5f * sin(t + f.phase[i]))), 0.6f + f.size[i] * 1.4f,
            Offset(f.x[i] * w, f.y[i] * h * 0.6f))
    }
    val bands = listOf(p.primary, p.secondary, p.glow)
    val step = w / 48f
    for (b in bands.indices) {
        val path = Path()
        val baseY = h * (0.22f + 0.09f * b)
        var x = 0f
        path.moveTo(0f, baseY)
        while (x <= w + step) {
            val y = baseY + sin(x * 0.004f + t * 0.3f + b) * h * 0.06f + sin(x * 0.011f - t * 0.2f) * h * 0.02f
            path.lineTo(x, y)
            x += step
        }
        path.lineTo(w, baseY + h * 0.35f); path.lineTo(0f, baseY + h * 0.35f); path.close()
        drawPath(path, Brush.verticalGradient(listOf(bands[b].copy(alpha = 0.30f * a.intensity), Color.Transparent), startY = baseY - h * 0.06f, endY = baseY + h * 0.33f))
    }
}

private fun DrawScope.drawOcean(p: UltraPalette, f: ParticleField, t: Float, a: AnimationConfig) {
    val w = size.width; val h = size.height
    for (i in 0 until 6) {
        val x0 = w * (0.08f + 0.17f * i) + sin(t * 0.2f + i) * w * 0.02f
        val alpha = (0.05f + 0.035f * sin(t * 0.5f + i * 1.3f)) * a.intensity
        val path = Path().apply { moveTo(x0, 0f); lineTo(x0 + w * 0.05f, 0f); lineTo(x0 + w * 0.16f, h); lineTo(x0 - w * 0.06f, h); close() }
        drawPath(path, Brush.verticalGradient(listOf(p.particle.copy(alpha = alpha.coerceIn(0f, 1f)), Color.Transparent)))
    }
    for (i in 0 until f.count) {
        val y = fract(f.y[i] - t * 0.03f * (0.3f + f.speed[i])) * h
        val x = f.x[i] * w + sin(t * 1.1f + f.phase[i]) * 8f
        drawCircle(p.particle2.copy(alpha = 0.18f + 0.3f * f.size[i]), 1.5f + f.size[i] * 4f, Offset(x, y), style = Stroke(1.2f))
    }
}

private val matrixGlyphs = "アイウエオカキクケコサシスセソタチツテトナニヌネノ0123456789ABCDEFZ".toCharArray()

private fun DrawScope.drawMatrix(p: UltraPalette, t: Float, a: AnimationConfig, paint: Paint) {
    val w = size.width; val h = size.height
    val colW = when (a.profile) { AnimationProfile.ECONOMY -> 44f; AnimationProfile.BALANCED -> 32f; AnimationProfile.ULTRA -> 26f }
    val trail = if (a.profile == AnimationProfile.ECONOMY) 8 else 14
    val glyphH = 24f
    val cols = (w / colW).toInt() + 1
    val head = p.particle2.toArgb(); val body = p.primary
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        for (c in 0 until cols) {
            val speed = 70f + hash01(c, 1) * 170f
            val span = h + trail * glyphH
            val headY = fract((t * speed + hash01(c, 2) * span * 3f) / span) * span
            for (r in 0 until trail) {
                val y = headY - r * glyphH
                if (y < -glyphH || y > h + glyphH) continue
                val g = matrixGlyphs[(hash01(c * 31 + r, (t * 5f).toInt() + r) * (matrixGlyphs.size - 1)).toInt()]
                paint.color = if (r == 0) head else body.copy(alpha = (1f - r / trail.toFloat()) * 0.55f * a.intensity.coerceAtMost(1f)).toArgb()
                nc.drawText(charArrayOf(g), 0, 1, c * colW, y, paint)
            }
        }
    }
}

private fun DrawScope.drawSpace(p: UltraPalette, f: ParticleField, t: Float, a: AnimationConfig) {
    val w = size.width; val h = size.height
    drawStars(p, f, t, 0.0015f, 1.8f)
    val pc = Offset(w * 0.82f + sin(t * 0.02f) * 10f, h * 0.78f)
    val pr = h * 0.24f
    drawCircle(Brush.radialGradient(listOf(p.primary.copy(alpha = 0.25f), Color.Transparent), pc, pr * 1.6f), pr * 1.6f, pc)
    drawCircle(Brush.radialGradient(listOf(p.primary, p.bgBottom), Offset(pc.x - pr * 0.4f, pc.y - pr * 0.4f), pr * 1.3f), pr, pc)
    rotate(-18f, pc) {
        drawOval(p.particle2.copy(alpha = 0.35f), topLeft = Offset(pc.x - pr * 1.7f, pc.y - pr * 0.28f), size = Size(pr * 3.4f, pr * 0.56f), style = Stroke(3f))
    }
    val ang = t * 0.12f
    val moon = Offset(pc.x + cos(ang) * pr * 2.1f, pc.y + sin(ang) * pr * 0.6f)
    drawCircle(Brush.radialGradient(listOf(Color.White, p.secondary.copy(alpha = 0.6f)), moon, pr * 0.16f), pr * 0.12f, moon)
    val p2 = Offset(w * 0.12f, h * 0.2f + sin(t * 0.03f) * 6f)
    drawCircle(Brush.radialGradient(listOf(p.secondary, p.bgTop), Offset(p2.x - 10f, p2.y - 10f), h * 0.08f), h * 0.06f, p2)
    if (a.profile == AnimationProfile.ULTRA && !a.reduceMotion) drawShootingStars(p.particle, t, 2)
}

private fun DrawScope.drawDiamond(p: UltraPalette, f: ParticleField, t: Float, a: AnimationConfig) {
    val w = size.width; val h = size.height
    for (i in 0 until 7) {
        val cx = w * hash01(i, 21); val cy = h * hash01(i, 22); val r = h * (0.12f + 0.2f * hash01(i, 23))
        val rot = t * (4f + i) * (if (i % 2 == 0) 1 else -1)
        rotate(rot, Offset(cx, cy)) {
            val path = Path()
            for (k in 0 until 6) {
                val ang = k * (PI.toFloat() / 3f)
                val px = cx + cos(ang) * r; val py = cy + sin(ang) * r
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            drawPath(path, Brush.linearGradient(listOf(p.primary.copy(alpha = 0.05f), p.secondary.copy(alpha = 0.02f)), Offset(cx - r, cy - r), Offset(cx + r, cy + r)))
            drawPath(path, Color.White.copy(alpha = 0.07f), style = Stroke(1.2f))
        }
    }
    for (i in 0 until f.count) {
        val tw = sin(t * (0.6f + f.speed[i]) + f.phase[i])
        val g = (tw * tw * tw).coerceAtLeast(0f)
        if (g < 0.05f) continue
        val c = Offset(f.x[i] * w, f.y[i] * h); val l = (3f + f.size[i] * 9f) * g
        drawLine(Color.White.copy(alpha = g), Offset(c.x - l, c.y), Offset(c.x + l, c.y), 1.2f)
        drawLine(Color.White.copy(alpha = g), Offset(c.x, c.y - l), Offset(c.x, c.y + l), 1.2f)
        drawCircle(p.glow.copy(alpha = 0.3f * g), l * 0.6f, c)
    }
}

private fun DrawScope.drawInferno(p: UltraPalette, f: ParticleField, t: Float, a: AnimationConfig) {
    val w = size.width; val h = size.height
    val flicker = 0.32f + 0.04f * (sin(t * 1.3f) + sin(t * 2.1f + 1f)) // kontrolliert, kein Stroboskop
    val gc = Offset(w / 2, h * 1.1f)
    drawCircle(Brush.radialGradient(listOf(p.primary.copy(alpha = flicker * a.intensity), Color.Transparent), gc, w * 0.75f), w * 0.75f, gc)
    for (i in 0 until f.count) {
        val prog = fract(f.y[i] - t * 0.045f * (0.5f + f.speed[i]))
        val y = prog * h
        val x = f.x[i] * w + sin(t * 1.5f + f.phase[i]) * 16f
        val fade = (prog * 1.4f).coerceIn(0f, 1f)
        val c = if (f.size[i] > 0.6f) p.particle2 else p.particle
        drawCircle(c.copy(alpha = 0.75f * fade), 1f + f.size[i] * 2.4f, Offset(x, y))
        drawCircle(c.copy(alpha = 0.12f * fade), 5f + f.size[i] * 6f, Offset(x, y))
    }
}
