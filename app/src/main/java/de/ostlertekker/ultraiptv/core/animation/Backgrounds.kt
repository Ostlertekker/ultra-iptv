package de.ostlertekker.ultraiptv.core.animation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import de.ostlertekker.ultraiptv.core.design.ResolvedTheme
import de.ostlertekker.ultraiptv.core.motion.InputActivity
import kotlinx.coroutines.isActive
import kotlin.math.floor

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
fun rememberAnimationClock(running: Boolean, throttle: Boolean = false, fps: Int = 60, pauseOnInput: Boolean = false): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val active = running && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        // Bildratenbegrenzung: nur bei Bedarf neu zeichnen (spart GPU/CPU auf Fire TV).
        // Zeit wird aufsummiert → nach einer Pause (Scrollen) läuft die Animation ohne Sprung weiter.
        val minFrameNs = 1_000_000_000L / (if (throttle) minOf(fps, 20) else fps).coerceIn(10, 60)
        var last = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                if (now - last >= minFrameNs - 2_000_000L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                    last = now
                    if (!(pauseOnInput && InputActivity.isBusy(now))) time.floatValue += dt
                }
            }
        }
    }
    return time
}

/** App-Hintergrund: komplett schwarz (keine Designs/Effekte mehr). */
@Composable
@Suppress("UNUSED_PARAMETER")
fun ThemeBackground(theme: ResolvedTheme, modifier: Modifier = Modifier, animate: Boolean = true) {
    Box(modifier.background(Color.Black))
}
