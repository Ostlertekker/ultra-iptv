package de.ostlertekker.ultraiptv.feature.intro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ostlertekker.ultraiptv.core.audio.AudioExperienceEngine
import de.ostlertekker.ultraiptv.core.design.AppFonts
import de.ostlertekker.ultraiptv.core.design.ResolvedTheme
import kotlinx.coroutines.isActive

/** Zeitplan des Intros in Sekunden (volle Länge). Die Kurzfassung läuft mit [IntroTiming.SHORT] schneller ab. */
object IntroTiming {
    const val LETTERS_START = 0.35f
    const val LETTER_STEP = 0.07f
    const val LETTER_DUR = 0.55f
    const val SHINE_START = 1.25f
    const val SHINE_DUR = 0.7f
    const val PRO_START = 1.55f
    const val PRO_LAND = 1.95f
    const val END = 3.6f
    const val FADE = 0.45f
    const val SHORT = 0.62f
}

private fun clamp01(v: Float) = v.coerceIn(0f, 1f)
private fun easeOutCubic(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
private fun easeOutBack(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1f; val y = x - 1f; return 1f + c3 * y * y * y + c1 * y * y }
private fun easeInCubic(x: Float) = x * x * x

/**
 * Start-Intro: „ULTRA IPTV“ baut sich Buchstabe für Buchstabe auf, ein Lichtstreif läuft über den Schriftzug,
 * dann fliegt das „PRO“-Abzeichen (nur Rahmen) herein und landet mit einem „Bling“. Jede Taste überspringt.
 */
@Composable
fun IntroScreen(full: Boolean, soundEnabled: Boolean, sounds: AudioExperienceEngine?, theme: ResolvedTheme, onFinished: () -> Unit) {
    val finish by rememberUpdatedState(onFinished)
    val k = if (full) 1f else IntroTiming.SHORT
    val total = IntroTiming.END * k
    val time = remember { mutableFloatStateOf(0f) }
    var done by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val accent = theme.palette.primary

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        // Erst wenn der Ton startet, beginnt die Animation (Bild und „Bling“ synchron)
        if (soundEnabled) runCatching { sounds?.playIntroAligned(short = !full) }
        val start = withFrameNanos { it }
        while (isActive && !done) {
            withFrameNanos { now -> time.floatValue = (now - start) / 1e9f / k }
            if (time.floatValue * k >= total) { done = true; finish() }
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
                // Ausblenden am Ende
                val t = time.floatValue
                alpha = 1f - clamp01((t - (IntroTiming.END - IntroTiming.FADE)) / IntroTiming.FADE)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Maskottchen Ulli springt herein (Federeffekt), schaltet sich mit kurzem Rauschen ein und winkt
            Ulli(Mood.WAVE, switchKey = 1, modifier = Modifier.size(150.dp, 170.dp).graphicsLayer {
                val a = clamp01(time.floatValue / 0.55f)
                val s = easeOutBack(a)
                scaleX = s; scaleY = s; alpha = clamp01(a * 3f)
                translationY = (1f - easeOutCubic(a)) * 60f
            })
            Spacer(Modifier.height(6.dp))
            Text("OstlerTekker&HorstyHaze Presents", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, letterSpacing = 6.sp,
                fontFamily = AppFonts.Inter, fontWeight = FontWeight.Medium,
                modifier = Modifier.graphicsLayer {
                    val a = easeOutCubic(clamp01((time.floatValue - 0.15f) / 0.6f))
                    alpha = a; translationY = (1f - a) * 12f
                })
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.Top) {
                // Schriftzug mit Lichtstreif (nur auf den Buchstaben sichtbar)
                Row(
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val s = clamp01((time.floatValue - IntroTiming.SHINE_START) / IntroTiming.SHINE_DUR)
                            if (s > 0f && s < 1f) {
                                val x = -size.width * 0.4f + s * size.width * 1.8f
                                drawRect(
                                    Brush.linearGradient(
                                        0f to Color.Transparent, 0.45f to accent.copy(alpha = 0.0f), 0.5f to Color.White,
                                        0.55f to accent.copy(alpha = 0.0f), 1f to Color.Transparent,
                                        start = Offset(x - 160f, 0f), end = Offset(x + 160f, size.height),
                                    ),
                                    blendMode = BlendMode.SrcAtop,
                                )
                            }
                        },
                ) {
                    "ULTRA IPTV".forEachIndexed { i, ch ->
                        if (ch == ' ') { Spacer(Modifier.width(22.dp)); return@forEachIndexed }
                        Text(ch.toString(), color = Color.White, fontSize = 76.sp, lineHeight = 80.sp, fontWeight = FontWeight.ExtraBold,
                            fontFamily = AppFonts.Inter, letterSpacing = 2.sp,
                            modifier = Modifier.graphicsLayer {
                                val p = clamp01((time.floatValue - IntroTiming.LETTERS_START - i * IntroTiming.LETTER_STEP) / IntroTiming.LETTER_DUR)
                                val e = easeOutCubic(p)
                                alpha = e
                                translationY = (1f - e) * 40f
                                scaleX = 0.9f + 0.1f * e; scaleY = scaleX
                            })
                    }
                }
                Spacer(Modifier.width(12.dp))
                ProFlyIn(time, accent)
            }
            Spacer(Modifier.height(22.dp))
            // Dezente Linie, die sich nach der Landung ausbreitet
            Box(Modifier.height(1.dp).width(360.dp).graphicsLayer {
                val a = easeOutCubic(clamp01((time.floatValue - IntroTiming.PRO_LAND) / 0.8f))
                scaleX = a; alpha = a * 0.6f
            }.background(Brush.horizontalGradient(listOf(Color.Transparent, accent, Color.Transparent))))
        }
    }
}

/** „PRO“ nur mit Rahmen: fliegt von rechts herein, landet hochgestellt, Glanzpunkt + Ring beim Aufsetzen. */
@Composable
private fun ProFlyIn(time: androidx.compose.runtime.State<Float>, accent: Color) {
    Box(Modifier.offset(y = 6.dp)) {
        // Ring, der sich beim Landen ausbreitet
        Box(Modifier.matchProSize().graphicsLayer {
            val r = clamp01((time.value - IntroTiming.PRO_LAND) / 0.6f)
            alpha = if (r <= 0f || r >= 1f) 0f else (1f - r) * 0.8f
            scaleX = 1f + r * 0.6f; scaleY = 1f + r * 0.9f
        }.border(1.5.dp, accent, RoundedCornerShape(7.dp)))
        Box(
            Modifier.graphicsLayer {
                val p = clamp01((time.value - IntroTiming.PRO_START) / (IntroTiming.PRO_LAND - IntroTiming.PRO_START))
                val e = easeOutBack(p)
                alpha = clamp01(p * 2.5f)
                translationX = (1f - e) * 900f
                translationY = (1f - e) * -60f
                rotationZ = (1f - e) * 18f
                val sc = 1f + (1f - easeOutCubic(p)) * 1.2f
                scaleX = sc; scaleY = sc
            }.border(2.dp, Color.White, RoundedCornerShape(7.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("PRO", color = Color.White, fontSize = 22.sp, lineHeight = 24.sp, fontWeight = FontWeight.Black,
                fontFamily = AppFonts.Inter, letterSpacing = 3.sp)
        }
        // Glanzstern oben rechts beim Aufsetzen
        Canvas(Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-12).dp).size(26.dp)) {
            val g = (time.value - IntroTiming.PRO_LAND) / 0.55f
            if (g <= 0f || g >= 1f) return@Canvas
            val a = if (g < 0.25f) g / 0.25f else 1f - easeInCubic((g - 0.25f) / 0.75f)
            val r = size.minDimension / 2f * (0.4f + 0.6f * easeOutCubic(g))
            val c = Offset(size.width / 2f, size.height / 2f)
            val path = Path().apply {
                moveTo(c.x, c.y - r); lineTo(c.x + r * 0.16f, c.y - r * 0.16f); lineTo(c.x + r, c.y); lineTo(c.x + r * 0.16f, c.y + r * 0.16f)
                lineTo(c.x, c.y + r); lineTo(c.x - r * 0.16f, c.y + r * 0.16f); lineTo(c.x - r, c.y); lineTo(c.x - r * 0.16f, c.y - r * 0.16f); close()
            }
            drawPath(path, Color.White.copy(alpha = a))
            drawCircle(Color.White.copy(alpha = a * 0.35f), radius = r * 0.45f, center = c)
        }
    }
}

/** Gleiche Größe wie das PRO-Abzeichen (Ring-Hintergrund). */
private fun Modifier.matchProSize(): Modifier = this.size(width = 82.dp, height = 34.dp)
