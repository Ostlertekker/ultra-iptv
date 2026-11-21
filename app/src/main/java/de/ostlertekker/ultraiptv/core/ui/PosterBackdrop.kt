package de.ostlertekker.ultraiptv.core.ui

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Hintergrund für Titelbilder: Gibt es ein Breitbild (Szenenbild), wird es normal gezeigt.
 * Liefert der Anbieter nur ein Hochformat-Poster, wird es nicht verzerrt vergrößert, sondern
 * stark unscharf als Stimmungs-Hintergrund verwendet und – wenn [showPoster] – scharf rechts daneben gestellt.
 * Die Unschärfe entsteht durch Laden in winziger Auflösung (funktioniert auf jeder Android-Version, auch Fire OS).
 */
@Composable
fun PosterBackdrop(backdrop: String?, poster: String?, modifier: Modifier = Modifier, showPoster: Boolean = true,
                   posterPadding: Dp = 28.dp, alpha: Float = 1f, posterOnly: Boolean = false) {
    Box(modifier) {
        // posterOnly: schwarzer Hintergrund, nur das Cover rechts (kein Breitbild, keine Unschärfe)
        if (posterOnly && !poster.isNullOrBlank()) {
            BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                Box(Modifier.align(Alignment.CenterEnd).padding(end = maxWidth * 0.07f, top = posterPadding, bottom = posterPadding).fillMaxHeight()) {
                    val shape = RoundedCornerShape(14.dp)
                    // Ken-Burns: das Cover zoomt ganz langsam heran und zurück (nur Zeichenphase)
                    val kb = if (motionOk()) androidx.compose.animation.core.rememberInfiniteTransition(label = "kb").animateFloat(1f, 1.07f,
                        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(14_000,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "kbS") else null
                    Box(Modifier.fillMaxHeight().aspectRatio(2f / 3f).clip(shape)) {
                        AsyncImage(poster, null, contentScale = ContentScale.Crop, alpha = alpha,
                            modifier = Modifier.fillMaxSize().graphicsLayer { val s = kb?.value ?: 1f; scaleX = s; scaleY = s })
                    }
                }
            }
            return@Box
        }
        if (!backdrop.isNullOrBlank()) {
            AsyncImage(backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = alpha)
            return@Box
        }
        if (poster.isNullOrBlank()) return@Box
        val ctx = LocalContext.current
        // Unscharfer Hintergrund aus dem Poster (24×36 px hochskaliert)
        AsyncImage(
            ImageRequest.Builder(ctx).data(poster).size(24, 36).memoryCacheKey("blur:$poster").build(), null,
            contentScale = ContentScale.Crop, filterQuality = FilterQuality.High, alpha = alpha * 0.85f,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        if (showPoster) BoxWithConstraints(Modifier.fillMaxSize()) {
            // Scharfes Poster rechts, volle Höhe mit etwas Rand, mit Schatten
            Box(Modifier.align(Alignment.CenterEnd).padding(end = maxWidth * 0.07f, top = posterPadding, bottom = posterPadding).fillMaxHeight()) {
                val shape = RoundedCornerShape(14.dp)
                AsyncImage(poster, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxHeight().aspectRatio(2f / 3f).shadow(24.dp, shape).clip(shape))
            }
        }
        // Weicher Übergang am linken Rand, damit Text lesbar bleibt
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.5f to Color.Transparent)))
    }
}
