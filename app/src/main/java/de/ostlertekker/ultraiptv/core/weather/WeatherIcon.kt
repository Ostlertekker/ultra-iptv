package de.ostlertekker.ultraiptv.core.weather

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private val Sun = Color(0xFFFFC93C)
private val Cloud = Color(0xFFE8EDF5)
private val Drop = Color(0xFF7FD4FF)
private val Bolt = Color(0xFFFFE066)

/** Animiertes Wettersymbol (Sonne dreht sich, Wolken ziehen, Regen/Schnee fällt, Blitz flackert). */
@Composable
fun AnimatedWeatherIcon(code: Int, size: Dp = 30.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "wx")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(12_000, easing = LinearEasing)), label = "spin")
    val drift by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(3_000), RepeatMode.Reverse), label = "drift")
    val fall by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "fall")
    val flash by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "flash")
    Canvas(modifier.size(size)) {
        val w = this.size.width
        when (code) {
            0, 1 -> sun(Offset(w * 0.5f, w * 0.5f), w * 0.22f, spin)
            2 -> { sun(Offset(w * 0.36f, w * 0.36f), w * 0.15f, spin); cloud(Offset(w * 0.58f + drift * w * 0.04f, w * 0.62f), w * 0.36f) }
            3, 45, 48 -> { cloud(Offset(w * 0.42f - drift * w * 0.04f, w * 0.44f), w * 0.26f, Cloud.copy(alpha = 0.6f)); cloud(Offset(w * 0.56f + drift * w * 0.05f, w * 0.6f), w * 0.36f) }
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> { cloud(Offset(w * 0.5f + drift * w * 0.03f, w * 0.38f), w * 0.4f); drops(w, fall, snow = false) }
            71, 73, 75, 77, 85, 86 -> { cloud(Offset(w * 0.5f + drift * w * 0.03f, w * 0.38f), w * 0.4f); drops(w, fall, snow = true) }
            95, 96, 99 -> { cloud(Offset(w * 0.5f, w * 0.38f), w * 0.4f, Cloud.copy(alpha = 0.85f)); if (flash in 0.05f..0.18f || flash in 0.24f..0.3f) bolt(w) }
            else -> sun(Offset(w * 0.5f, w * 0.5f), w * 0.22f, spin)
        }
    }
}

private fun DrawScope.sun(c: Offset, r: Float, spin: Float) {
    rotate(spin, c) {
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val s = Offset(c.x + cos(a).toFloat() * r * 1.45f, c.y + sin(a).toFloat() * r * 1.45f)
            val e = Offset(c.x + cos(a).toFloat() * r * 1.95f, c.y + sin(a).toFloat() * r * 1.95f)
            drawLine(Sun, s, e, strokeWidth = r * 0.28f, cap = StrokeCap.Round)
        }
    }
    drawCircle(Sun, r, c)
}

/** Wolke aus drei Kreisen und flachem Boden, Mittelpunkt [c], Breite ~2·[r]. */
private fun DrawScope.cloud(c: Offset, r: Float, color: Color = Cloud) {
    drawCircle(color, r * 0.42f, Offset(c.x - r * 0.45f, c.y + r * 0.08f))
    drawCircle(color, r * 0.55f, Offset(c.x, c.y - r * 0.12f))
    drawCircle(color, r * 0.4f, Offset(c.x + r * 0.5f, c.y + r * 0.1f))
    drawRoundRect(color, Offset(c.x - r * 0.85f, c.y + r * 0.02f), androidx.compose.ui.geometry.Size(r * 1.75f, r * 0.48f),
        androidx.compose.ui.geometry.CornerRadius(r * 0.24f))
}

private fun DrawScope.drops(w: Float, fall: Float, snow: Boolean) {
    listOf(0.32f to 0f, 0.5f to 0.33f, 0.68f to 0.66f).forEach { (x, off) ->
        val p = (fall + off) % 1f
        val y = w * (0.62f + p * 0.32f)
        val alpha = 1f - p * 0.7f
        if (snow) drawCircle(Color.White.copy(alpha = alpha), w * 0.045f, Offset(w * x, y))
        else drawLine(Drop.copy(alpha = alpha), Offset(w * x, y), Offset(w * x - w * 0.03f, y + w * 0.1f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.bolt(w: Float) {
    val p = Path().apply {
        moveTo(w * 0.54f, w * 0.56f); lineTo(w * 0.42f, w * 0.78f); lineTo(w * 0.52f, w * 0.78f)
        lineTo(w * 0.44f, w * 0.98f); lineTo(w * 0.64f, w * 0.7f); lineTo(w * 0.53f, w * 0.7f); close()
    }
    drawPath(p, Bolt)
}
