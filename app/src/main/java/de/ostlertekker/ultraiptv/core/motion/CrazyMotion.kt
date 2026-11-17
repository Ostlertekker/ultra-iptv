package de.ostlertekker.ultraiptv.core.motion

import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.design.UltraPalette
import de.ostlertekker.ultraiptv.core.motion.MotionMath.clamp01
import de.ostlertekker.ultraiptv.core.motion.MotionMath.easeOutCubic
import de.ostlertekker.ultraiptv.core.motion.MotionMath.hash
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * CRAZY MOTION ENGINE – Laufzeit.
 *
 * Alle Effekte arbeiten über `graphicsLayer` (GPU-RenderNode-Transformationen: Translation, Skalierung,
 * 3D-Rotation, Alpha, Schatten) und `drawWithContent` (Overlays, Clip-Reveals, Splitter).
 * Sie verändern weder Layout noch Fokus-Reihenfolge und konsumieren keine Tasten –
 * die Fernbedienungsnavigation bleibt jederzeit sofort bedienbar.
 */
val LocalMotion = compositionLocalOf { MotionSpec.OFF }

/** Zeitpunkt (nanoTime), an dem die aktuelle Seite geöffnet wurde. 0 = kein Seitenkontext (z. B. Menüleiste). */
val LocalPageStart = compositionLocalOf { 0L }

/** Eingangsanimationen nur direkt nach dem Öffnen einer Seite – beim Scrollen erscheinen neue Karten sofort. */
private const val ENTRANCE_WINDOW_NS = 1_400_000_000L

// --------------------------------------------------------------------------------------------
// Motion-Bus: Fokus-Tracking + kurzlebige Overlay-Effekte (Trail, Hover-Explosion)
// --------------------------------------------------------------------------------------------

class MotionEvent(val kind: Int, val from: Offset, val to: Offset, val start: Long, val durationMs: Int, val seed: Int) {
    companion object { const val TRAIL = 1; const val EXPLOSION = 2 }
}

object MotionBus {
    val events = mutableStateListOf<MotionEvent>()
    /** Mittelpunkt (Root-Koordinaten) des zuletzt fokussierten Elements. */
    var lastFocusCenter: Offset? = null
    /** Zeitpunkt des letzten Fokuswechsels (zum Erkennen von Dauer-Scrollen). */
    var lastFocusAt: Long = 0L
    private var seed = 0
    fun emit(kind: Int, from: Offset, to: Offset, durationMs: Int) {
        if (events.size > 12) events.removeAt(0)               // Ressourcen begrenzen
        events += MotionEvent(kind, from, to, System.nanoTime(), durationMs, seed++)
    }
}

// --------------------------------------------------------------------------------------------
// MENU ENTRANCE
// --------------------------------------------------------------------------------------------

/**
 * Eingangsanimation für ein Element (Menüpunkt, Karte, Button).
 * [index] steuert die Staffelung (Domino-Effekt), [trigger] startet die Animation neu.
 */
fun Modifier.crazyEntrance(index: Int = 0, trigger: Any? = Unit, override: EntranceAnim? = null, forceEnabled: Boolean = false): Modifier = composed {
    val spec = LocalMotion.current
    val anim = override ?: spec.entranceFor(index * 31 + (trigger?.hashCode() ?: 0))
    if (anim == null || (!spec.enabled && !forceEnabled)) return@composed this
    val pageStart = LocalPageStart.current
    // Einmal beim Erscheinen entscheiden: Karten, die erst durch Scrollen ins Bild kommen, werden nicht animiert
    val allowed = remember(trigger) {
        override != null || pageStart == 0L || System.nanoTime() - pageStart < ENTRANCE_WINDOW_NS
    }
    if (!allowed) return@composed this
    val p = LocalUltraTheme.current.palette
    val density = LocalDensity.current.density
    val progress = remember(trigger, anim) { Animatable(0f) }
    LaunchedEffect(trigger, anim) {
        val stagger = (if (anim == EntranceAnim.DOMINO) 70 else 40) * index.coerceAtMost(12)
        delay((stagger / spec.speed).toLong())
        progress.animateTo(1f, tween(spec.durationMs(anim.baseMs), easing = LinearEasing))
    }
    val k = spec.intensity
    this
        .graphicsLayer {
            val t = progress.value
            if (t >= 1f) return@graphicsLayer
            applyEntrance(anim, t, k, index, density)
        }
        .drawWithContent { drawEntrance(anim, progress.value, k, index, p, spec) }
}

private fun androidx.compose.ui.graphics.GraphicsLayerScope.applyEntrance(a: EntranceAnim, t: Float, k: Float, index: Int, density: Float) {
    val e = easeOutCubic(t)
    when (a) {
        EntranceAnim.GRAVITY_DROP -> {
            translationY = (MotionMath.gravityBounce(t) - 1f) * 320f * density * k
            alpha = min(1f, t * 5f)
        }
        EntranceAnim.EXPLOSION -> {
            val s = MotionMath.easeOutBack(t, 2.6f); scaleX = s; scaleY = s
            rotationZ = (1f - e) * (if (index % 2 == 0) -28f else 28f) * k
            alpha = min(1f, t * 4f)
        }
        EntranceAnim.HOLOGRAM -> {
            scaleY = 0.02f + 0.98f * MotionMath.easeOutExpo(t)
            alpha = if (t < 0.7f) (if (sin(t * 70f) > -0.3f) 0.85f else 0.35f) else 1f
        }
        EntranceAnim.FLIP_3D -> {
            rotationY = 90f * (1f - MotionMath.easeOutBack(t, 1.2f)) * k.coerceAtMost(1.2f)
            cameraDistance = 14f * density
            alpha = if (t < 0.04f) 0f else 1f
        }
        EntranceAnim.CYBER_GLITCH -> {
            val frame = (t * 40).toInt()
            translationX = if (t < 0.8f) (hash(frame, index) - 0.5f) * 40f * density * (1f - t) * k else 0f
            alpha = if (t < 0.6f && hash(frame, index + 7) < 0.25f) 0.3f else 1f
        }
        EntranceAnim.LIQUID_MORPH -> {
            val wobble = exp(-4f * t) * sin(t * 18f) * 0.35f * k
            scaleX = MotionMath.elastic(t, 5f, 10f) * (1f + wobble)
            scaleY = MotionMath.elastic(t, 5f, 10f) * (1f - wobble)
        }
        EntranceAnim.BLACK_HOLE -> {
            scaleX = e; scaleY = e
            rotationZ = (1f - e) * 540f * k
            alpha = min(1f, t * 2f)
        }
        EntranceAnim.METEOR_SHOWER -> {
            translationX = (1f - e) * 260f * density * k
            translationY = -(1f - e) * 320f * density * k
            rotationZ = (1f - e) * -12f
            alpha = min(1f, t * 3f)
        }
        EntranceAnim.DOMINO -> {
            transformOrigin = TransformOrigin(0.5f, 0f)
            rotationX = -90f * (1f - MotionMath.bounceOut(t))
            cameraDistance = 16f * density
        }
        EntranceAnim.MAGNETIC_PULL -> {
            val sp = MotionMath.elastic(t, 5f, 9f)
            translationX = -(1f - sp) * 240f * density * k
            val s = 0.85f + 0.15f * sp; scaleX = s; scaleY = s
        }
        EntranceAnim.SHATTER_GLASS -> alpha = min(1f, t * 3f)
        EntranceAnim.PORTAL -> { val s = 0.9f + 0.1f * e; scaleX = s; scaleY = s }
        EntranceAnim.MATRIX_RAIN -> Unit
        EntranceAnim.ELASTIC_BOUNCE -> {
            val s = MotionMath.elastic(t, 4.5f, 14f); scaleX = s; scaleY = s
            alpha = min(1f, t * 4f)
        }
    }
}

private fun ContentDrawScope.drawEntrance(a: EntranceAnim, t: Float, k: Float, index: Int, p: UltraPalette, spec: MotionSpec) {
    if (t >= 1f) { drawContent(); return }
    val w = size.width; val h = size.height
    val e = easeOutCubic(t)
    when (a) {
        EntranceAnim.EXPLOSION -> {
            val n = spec.particleCount(12)
            for (i in 0 until n) {
                val ang = hash(index, i) * 2f * PI.toFloat()
                val d = max(w, h) * (0.3f + 0.9f * e)
                drawCircle(p.particle.copy(alpha = 1f - t), 3f, Offset(w / 2 + cos(ang) * d, h / 2 + sin(ang) * d))
            }
            drawContent()
        }
        EntranceAnim.HOLOGRAM -> {
            drawContent()
            val a2 = 1f - t
            drawRect(Color(0xFF39E6FF).copy(alpha = 0.35f * a2), blendMode = BlendMode.SrcAtop)
            var y = 0f
            while (y < h) { drawRect(Color.White.copy(alpha = 0.18f * a2), Offset(0f, y), Size(w, 1.5f)); y += 5f }
        }
        EntranceAnim.CYBER_GLITCH -> {
            if (t < 0.85f && !spec.lite) {
                val frame = (t * 40).toInt()
                val dx = (hash(frame, 3) - 0.5f) * 18f * k
                // RGB-Versatz: Kanäle als getönte Kopien versetzt zeichnen (GPU-Layer)
                drawIntoCanvas { c ->
                    val paint = Paint().apply { alpha = 0.55f; colorFilter = ColorFilter.tint(Color.Red, BlendMode.Modulate) }
                    c.saveLayer(Rect(0f, 0f, w, h), paint)
                    translate(dx, 0f) { this@drawEntrance.drawContent() }
                    c.restore()
                    val paint2 = Paint().apply { alpha = 0.55f; colorFilter = ColorFilter.tint(Color.Cyan, BlendMode.Modulate) }
                    c.saveLayer(Rect(0f, 0f, w, h), paint2)
                    translate(-dx, 0f) { this@drawEntrance.drawContent() }
                    c.restore()
                }
            }
            drawContent()
            val frame = (t * 30).toInt()
            for (i in 0 until 3) {
                if (hash(frame, i + 20) < 0.5f) continue
                val y = hash(frame, i) * h
                drawRect((if (i % 2 == 0) p.primary else p.secondary).copy(alpha = 0.7f * (1f - t)), Offset(0f, y), Size(w, 3f + 8f * hash(frame, i + 9)))
            }
        }
        EntranceAnim.LIQUID_MORPH -> {
            val r = min(w, h) / 2f * (1f - e)
            val path = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(r, r))) }
            clipPath(path) { this@drawEntrance.drawContent() }
        }
        EntranceAnim.BLACK_HOLE -> {
            drawContent()
            val r = max(w, h) * (1f - e)
            if (r > 1f) drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.9f * (1f - t)), Color.Transparent), center, r), r, center)
            val n = spec.particleCount(10)
            for (i in 0 until n) {
                val ang = hash(index, i) * 6.28f + t * 12f
                val d = (1f - e) * max(w, h) * (0.4f + hash(i, index))
                drawCircle(p.glow.copy(alpha = 1f - t), 2.5f, Offset(center.x + cos(ang) * d, center.y + sin(ang) * d))
            }
        }
        EntranceAnim.METEOR_SHOWER -> {
            val n = spec.particleCount(4).coerceAtLeast(1)
            for (i in 0 until n) {
                val off = Offset(w * (0.2f + 0.6f * hash(index, i)), h * hash(i, index))
                val len = (1f - t) * (140f + 100f * hash(i, 9)) * k
                drawLine(Brush.linearGradient(listOf(p.particle.copy(alpha = 1f - t), Color.Transparent), off, Offset(off.x + len, off.y - len * 1.2f)),
                    off, Offset(off.x + len, off.y - len * 1.2f), strokeWidth = 3f, cap = StrokeCap.Round)
            }
            drawContent()
        }
        EntranceAnim.MAGNETIC_PULL -> {
            for (i in 1..3) {
                drawArc(p.primary.copy(alpha = 0.5f * (1f - t)), -60f, 120f, false, topLeft = Offset(-i * 18f * (1f - t), h / 2 - i * 14f),
                    size = Size(i * 28f, i * 28f), style = Stroke(2f))
            }
            drawContent()
        }
        EntranceAnim.SHATTER_GLASS -> {
            // Inhalt wird in Splitter zerlegt, die zusammenfliegen
            val cols = if (spec.lite) 2 else 3
            val rows = if (spec.lite) 2 else 3
            val cw = w / cols; val rh = h / rows
            for (cx in 0 until cols) for (ry in 0 until rows) for (tri in 0..1) {
                val i = (cx * rows + ry) * 2 + tri
                val x0 = cx * cw; val y0 = ry * rh
                val path = Path().apply {
                    if (tri == 0) { moveTo(x0, y0); lineTo(x0 + cw, y0); lineTo(x0, y0 + rh) }
                    else { moveTo(x0 + cw, y0); lineTo(x0 + cw, y0 + rh); lineTo(x0, y0 + rh) }
                    close()
                }
                val dirX = (hash(i, index) - 0.5f) * 2f; val dirY = (hash(index, i) - 0.5f) * 2f
                val f = 1f - e
                withTransform({
                    translate(dirX * 160f * f * k, dirY * 160f * f * k + 80f * f * f)
                    rotate((hash(i, 3) - 0.5f) * 120f * f, Offset(x0 + cw / 2, y0 + rh / 2))
                }) {
                    clipPath(path) { this@drawEntrance.drawContent() }
                }
            }
        }
        EntranceAnim.PORTAL -> {
            val r = hypot(w, h) / 2f * e
            val path = Path().apply { addOval(Rect(center, r)) }
            clipPath(path) { this@drawEntrance.drawContent() }
            drawCircle(p.glow.copy(alpha = 1f - t), r, center, style = Stroke(4f + 6f * (1f - t)))
        }
        EntranceAnim.MATRIX_RAIN -> {
            val reveal = h * e
            clipRect(0f, 0f, w, reveal) { this@drawEntrance.drawContent() }
            val cols = if (spec.lite) 5 else 10
            for (c in 0 until cols) {
                val x = (c + 0.5f) * w / cols
                for (r in 0 until 6) {
                    val y = reveal + (r * 14f) + ((t * 400f + hash(c, index) * 60f) % 14f)
                    if (y > h) continue
                    drawRect(Color(0xFF29FF5C).copy(alpha = (1f - r / 6f) * (1f - t)), Offset(x, y), Size(4f, 8f))
                }
            }
        }
        else -> drawContent()
    }
}

// --------------------------------------------------------------------------------------------
// NAVIGATION EFFECTS
// --------------------------------------------------------------------------------------------

/**
 * Fokus-Effekt eines Elements. Wird in `tvFocusable` angewendet → wirkt auf alle Menüs, Karten,
 * Listen, Buttons und die Player-Steuerung.
 */
fun Modifier.crazyFocus(focused: Boolean, baseScale: Float, scaleOnFocus: Boolean, override: FocusEffect? = null): Modifier = composed {
    val spec = LocalMotion.current
    val effect = override ?: spec.focus
    val p = LocalUltraTheme.current.palette
    val density = LocalDensity.current.density
    val scale = remember { Animatable(1f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }
    // Kein Compose-State: Positionsänderungen beim Scrollen lösen so keine Snapshot-Schreibvorgänge aus
    val boundsHolder = remember { arrayOf(Rect.Zero) }
    val target = if (focused && scaleOnFocus) baseScale else 1f

    LaunchedEffect(focused, effect) {
        // Neu erscheinende, nicht fokussierte Elemente (beim Scrollen) brauchen keinerlei Arbeit
        if (!focused && scale.value == 1f && offset.value == Offset.Zero && tiltX.value == 0f && tiltY.value == 0f && lift.value == 0f) return@LaunchedEffect
        val nowN = System.nanoTime()
        val rapid = nowN - MotionBus.lastFocusAt < 170_000_000L
        if (focused) MotionBus.lastFocusAt = nowN
        // Gedrückt gehaltene Pfeiltaste: nur ein schneller, leichter Skalierungsschritt – keine Partikel/Federn
        if (rapid && spec.enabled && effect != null) {
            if (focused) MotionBus.lastFocusCenter = boundsHolder[0].center
            offset.snapTo(Offset.Zero); tiltX.snapTo(0f); tiltY.snapTo(0f); lift.snapTo(0f)
            scale.animateTo(target, tween(90))
            return@LaunchedEffect
        }
        if (!spec.enabled || effect == null) { scale.snapTo(target); offset.snapTo(Offset.Zero); tiltX.snapTo(0f); tiltY.snapTo(0f); lift.snapTo(0f); return@LaunchedEffect }
        val bounds = boundsHolder[0]
        val center = bounds.center
        val prev = MotionBus.lastFocusCenter
        if (focused) MotionBus.lastFocusCenter = center
        val delta = if (focused && prev != null) prev - center else Offset.Zero
        val k = spec.intensity
        val dur = spec.durationMs(260)
        when (effect) {
            FocusEffect.MAGNETIC -> {
                if (focused && delta != Offset.Zero) offset.snapTo(Offset(delta.x.coerceIn(-300f, 300f), delta.y.coerceIn(-300f, 300f)) * 0.22f * k)
                launch { offset.animateTo(Offset.Zero, spring(dampingRatio = 0.5f, stiffness = 420f)) }
                scale.animateTo(target, spring(dampingRatio = 0.55f, stiffness = 500f))
            }
            FocusEffect.TILT_3D -> {
                val ty = if (focused) (delta.x / max(bounds.width, 1f) * 14f).coerceIn(-18f, 18f) * k else 0f
                val tx = if (focused) (-delta.y / max(bounds.height, 1f) * 14f).coerceIn(-18f, 18f) * k else 0f
                tiltY.snapTo(ty); tiltX.snapTo(tx)
                launch { tiltY.animateTo(if (focused) ty * 0.25f else 0f, spring(0.4f, 250f)) }
                launch { tiltX.animateTo(if (focused) tx * 0.25f else 0f, spring(0.4f, 250f)) }
                scale.animateTo(target, tween(dur))
            }
            FocusEffect.HOVER_EXPLOSION -> {
                if (focused && spec.particles) MotionBus.emit(MotionEvent.EXPLOSION, center, center, spec.durationMs(650))
                scale.animateTo(target, spring(0.45f, 380f))
            }
            FocusEffect.ELASTIC -> scale.animateTo(target, spring(dampingRatio = 0.28f, stiffness = 320f))
            FocusEffect.DEPTH_SHIFT -> {
                launch { lift.animateTo(if (focused) 1f else 0f, tween(dur)) }
                scale.animateTo(if (focused && scaleOnFocus) baseScale + 0.04f * k else 1f, tween(dur))
            }
            FocusEffect.PULSE_GLOW -> scale.animateTo(target, tween(dur))
            FocusEffect.PERSPECTIVE_ZOOM -> {
                launch { tiltX.animateTo(if (focused) -10f * k else 0f, tween(dur)) }
                scale.animateTo(if (focused && scaleOnFocus) baseScale + 0.07f * k else 1f, spring(0.6f, 300f))
            }
            FocusEffect.TRAIL -> {
                if (focused && prev != null && spec.particles) MotionBus.emit(MotionEvent.TRAIL, prev, center, spec.durationMs(450))
                scale.animateTo(target, tween(dur))
            }
        }
    }
    // Nur das fokussierte Element pulsiert (begrenzter Ressourcenverbrauch)
    val pulse = if (focused && effect == FocusEffect.PULSE_GLOW && spec.enabled) {
        val inf = rememberInfiniteTransition(label = "pulse")
        inf.animateFloat(0f, 1f, infiniteRepeatable(tween((900 / spec.speed).toInt()), RepeatMode.Reverse), label = "pulseV").value
    } else 0f

    this
        .onGloballyPositioned { boundsHolder[0] = it.boundsInRoot() }
        .graphicsLayer {
            scaleX = scale.value; scaleY = scale.value
            translationX = offset.value.x; translationY = offset.value.y - lift.value * 8f * density
            rotationX = tiltX.value; rotationY = tiltY.value
            if (tiltX.value != 0f || tiltY.value != 0f) cameraDistance = 12f * density
            shadowElevation = lift.value * 18f * density
        }
        .drawBehind {
            if (pulse > 0f) {
                val grow = 4f + 10f * pulse
                drawRoundRect(p.glow.copy(alpha = 0.18f + 0.35f * pulse), topLeft = Offset(-grow, -grow),
                    size = Size(size.width + grow * 2, size.height + grow * 2), cornerRadius = CornerRadius(18f, 18f), style = Stroke(3f + 3f * pulse))
            }
        }
}

// --------------------------------------------------------------------------------------------
// PAGE TRANSITIONS
// --------------------------------------------------------------------------------------------

/**
 * Seitenübergang: die neue Seite wird sofort komponiert (Fokus/Eingaben sofort möglich), während
 * eine GPU-Transformation plus Vorhang-Overlay sie enthüllt.
 */
@Composable
fun PageTransitionHost(key: Any?, modifier: Modifier = Modifier, override: PageTransition? = null, forceEnabled: Boolean = false, content: @Composable () -> Unit) {
    val spec = LocalMotion.current
    val type = override ?: spec.pageEnter
    val progress = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(key, type) {
        if (first && !forceEnabled) { first = false; return@LaunchedEffect }
        first = false
        if (type == null || (!spec.enabled && !forceEnabled)) { progress.snapTo(1f); return@LaunchedEffect }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(spec.durationMs(type.baseMs), easing = LinearEasing))
    }
    val density = LocalDensity.current.density
    val pageStart = remember(key) { System.nanoTime() }
    Box(modifier) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val t = progress.value
            if (t >= 1f || type == null) return@graphicsLayer
            val e = easeOutCubic(t)
            when (type) {
                PageTransition.PORTAL -> { val s = 0.85f + 0.15f * e; scaleX = s; scaleY = s }
                PageTransition.WARP_SPEED -> { val s = 1.35f - 0.35f * e; scaleX = s; scaleY = s; alpha = min(1f, t * 2f) }
                PageTransition.ZOOM_TUNNEL -> { val s = 0.55f + 0.45f * e; scaleX = s; scaleY = s }
                PageTransition.LIQUID -> translationY = (1f - e) * 60f * density
                PageTransition.GLITCH -> translationX = if (t < 0.85f) (hash((t * 40).toInt(), 1) - 0.5f) * 36f * density * (1f - t) else 0f
                PageTransition.CINEMATIC -> alpha = min(1f, t * 1.6f)
                PageTransition.SHATTER -> Unit
            }
            Unit
        }) { androidx.compose.runtime.CompositionLocalProvider(LocalPageStart provides pageStart) { content() } }
        if (type != null && progress.value < 1f) CurtainCanvas(type, progress.value, Modifier.fillMaxSize())
    }
}

/** Reiner Overlay-Effekt (z. B. über dem Video: SurfaceView lässt sich nicht transformieren). */
@Composable
fun CurtainOverlay(trigger: Any?, type: PageTransition?, modifier: Modifier = Modifier, skipFirst: Boolean = false) {
    val spec = LocalMotion.current
    if (type == null || !spec.enabled) return
    val progress = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(trigger, type) {
        if (first && skipFirst) { first = false; return@LaunchedEffect }
        first = false
        progress.snapTo(0f)
        // Senderwechsel: Effekt ist kurz und läuft parallel zum bereits startenden Stream
        progress.animateTo(1f, tween(spec.durationMs(type.baseMs), easing = LinearEasing))
    }
    if (progress.value < 1f) CurtainCanvas(type, progress.value, modifier.fillMaxSize())
}

@Composable
private fun CurtainCanvas(type: PageTransition, t: Float, modifier: Modifier) {
    val spec = LocalMotion.current
    val p = LocalUltraTheme.current.palette
    Canvas(modifier) { drawCurtain(type, t, p, spec) }
}

/** Vorhang: bei t=0 deckend, bei t=1 vollständig enthüllt. */
fun DrawScope.drawCurtain(type: PageTransition, t: Float, p: UltraPalette, spec: MotionSpec) {
    val w = size.width; val h = size.height
    val e = easeOutCubic(t)
    val bg = p.bgTop
    when (type) {
        PageTransition.PORTAL -> {
            val r = hypot(w, h) / 2f * e
            val path = Path().apply { fillType = PathFillType.EvenOdd; addRect(Rect(0f, 0f, w, h)); addOval(Rect(center, r)) }
            drawPath(path, bg)
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, p.glow.copy(alpha = 0.8f * (1f - t)), Color.Transparent), center, r + 40f), r + 40f, center)
            drawCircle(p.primary.copy(alpha = 1f - t), r, center, style = Stroke(6f))
        }
        PageTransition.SHATTER -> {
            val cols = if (spec.lite) 4 else 7
            val rows = if (spec.lite) 3 else 5
            val cw = w / cols; val rh = h / rows
            for (cx in 0 until cols) for (ry in 0 until rows) for (tri in 0..1) {
                val i = (cx * rows + ry) * 2 + tri
                val delayT = hash(i, 5) * 0.35f
                val lt = clamp01((t - delayT) / (1f - delayT))
                if (lt >= 1f) continue
                val x0 = cx * cw; val y0 = ry * rh
                val path = Path().apply {
                    if (tri == 0) { moveTo(x0, y0); lineTo(x0 + cw, y0); lineTo(x0, y0 + rh) } else { moveTo(x0 + cw, y0); lineTo(x0 + cw, y0 + rh); lineTo(x0, y0 + rh) }
                    close()
                }
                val dx = (hash(i, 1) - 0.5f) * 500f * lt * spec.intensity
                val dy = 900f * lt * lt                              // Splitter fallen mit Gravitation
                withTransform({ translate(dx, dy); rotate((hash(i, 2) - 0.5f) * 220f * lt, Offset(x0 + cw / 2, y0 + rh / 2)) }) {
                    drawPath(path, bg.copy(alpha = 1f - lt * 0.3f))
                    drawPath(path, Color.White.copy(alpha = 0.25f * (1f - lt)), style = Stroke(1.5f))
                }
            }
        }
        PageTransition.WARP_SPEED -> {
            drawRect(Color.Black.copy(alpha = (1f - e).coerceIn(0f, 1f)))
            val n = if (spec.lite) 50 else 140
            for (i in 0 until n) {
                val ang = hash(i, 11) * 2f * PI.toFloat()
                val start = 20f + hash(i, 12) * 200f
                val len = 60f + (max(w, h) * 0.9f) * MotionMath.sinPi(t) * (0.4f + hash(i, 13))
                val a = MotionMath.sinPi(t)
                val from = Offset(center.x + cos(ang) * start, center.y + sin(ang) * start)
                val to = Offset(center.x + cos(ang) * (start + len), center.y + sin(ang) * (start + len))
                drawLine((if (i % 4 == 0) p.secondary else Color.White).copy(alpha = a), from, to, strokeWidth = 1.5f + hash(i, 14) * 2f)
            }
        }
        PageTransition.LIQUID -> {
            // Welle läuft nach unten und gibt die Seite frei
            val level = h * (e * 1.25f - 0.1f)
            val path = Path().apply {
                moveTo(0f, h)
                var x = 0f
                val step = w / 40f
                lineTo(0f, level)
                while (x <= w) {
                    lineTo(x, level + sin(x / w * 6f * PI.toFloat() + t * 10f) * 36f * (1f - t) * spec.intensity)
                    x += step
                }
                lineTo(w, h); close()
            }
            drawPath(path, Brush.verticalGradient(listOf(p.primary.copy(alpha = 0.9f), bg), startY = level - 40f, endY = h))
        }
        PageTransition.GLITCH -> {
            val frame = (t * 35).toInt()
            val bars = if (spec.lite) 8 else 18
            for (i in 0 until bars) {
                if (hash(frame, i) < t) continue                      // Dichte nimmt ab
                val y = hash(i, frame + 3) * h
                val bh = 6f + hash(frame, i + 40) * 60f
                val dx = (hash(i, frame) - 0.5f) * 80f
                val c = when (i % 3) { 0 -> bg; 1 -> p.primary; else -> p.secondary }
                drawRect(c.copy(alpha = 0.95f * (1f - t)), Offset(dx, y), Size(w, bh))
            }
            if (t < 0.25f) drawRect(bg.copy(alpha = 1f - t * 4f))
        }
        PageTransition.ZOOM_TUNNEL -> {
            drawRect(bg.copy(alpha = (1f - e)))
            val rings = if (spec.lite) 6 else 12
            for (i in 0 until rings) {
                val f = ((i / rings.toFloat()) + t * 1.6f) % 1f
                val sw = w * f * 1.2f; val sh = h * f * 1.2f
                drawRect(p.primary.copy(alpha = (1f - f) * (1f - t)), Offset(center.x - sw / 2, center.y - sh / 2), Size(sw, sh), style = Stroke(2f + 4f * f))
            }
        }
        PageTransition.CINEMATIC -> {
            val bar = h / 2f * (1f - e)
            drawRect(Color.Black, Offset.Zero, Size(w, bar))
            drawRect(Color.Black, Offset(0f, h - bar), Size(w, bar))
            if (bar > 2f) {
                drawRect(p.glow.copy(alpha = 0.6f * (1f - t)), Offset(0f, bar - 2f), Size(w, 2f))
                drawRect(p.glow.copy(alpha = 0.6f * (1f - t)), Offset(0f, h - bar), Size(w, 2f))
            }
        }
    }
}

/** Ausgangsanimation der verlassenen Seite (Navigation). */
fun exitTransitionFor(spec: MotionSpec): ExitTransition {
    if (!spec.enabled) return ExitTransition.None
    val d = spec.durationMs(260)
    return when (spec.pageExit) {
        ExitAnim.FADE -> fadeOut(tween(d))
        ExitAnim.SHRINK -> scaleOut(tween(d), targetScale = 0.85f) + fadeOut(tween(d))
        ExitAnim.ZOOM_OUT -> scaleOut(tween(d), targetScale = 1.25f) + fadeOut(tween(d))
        ExitAnim.SLIDE_LEFT -> slideOutHorizontally(tween(d)) { -it / 4 } + fadeOut(tween(d))
        ExitAnim.GRAVITY_FALL -> slideOutVertically(tween(d + 120, easing = FastOutLinearInEasing)) { it } + fadeOut(tween(d + 120))
        ExitAnim.DISSOLVE -> fadeOut(tween(d * 2))
        ExitAnim.NONE -> ExitTransition.None
    }
}
