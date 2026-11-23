package de.ostlertekker.ultraiptv.ui

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.player.PlayerEngine
import de.ostlertekker.ultraiptv.core.ui.tvFocusable
import de.ostlertekker.ultraiptv.openPlayer

/**
 * In-App-Mini-Player: läuft beim Stöbern weiter (Alternative zu Bild-in-Bild, das viele Fire-TV-Geräte nicht anbieten).
 * OK = zurück in den Vollbild-Player · lange OK = schließen.
 */
@Composable
fun MiniPlayerOverlay(engine: PlayerEngine, modifier: Modifier = Modifier) {
    val mini by engine.mini.collectAsStateWithLifecycle()
    val info = mini ?: return
    val context = LocalContext.current
    val theme = LocalUltraTheme.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> if (engine.owner == "mini") engine.player?.pause()
                Lifecycle.Event.ON_START -> if (engine.owner == "mini") engine.player?.play()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val shape = RoundedCornerShape(theme.spec.corner.coerceAtMost(16.dp))
    Box(
        modifier
            .size(384.dp, 216.dp)
            .tvFocusable(shape, onClick = {
                engine.claim("player")
                engine.setMini(null)
                val kind = runCatching { ContentKind.valueOf(info.kind) }.getOrDefault(ContentKind.LIVE)
                context.openPlayer(kind, info.id, engine.player?.currentPosition ?: 0L, info.category)
            }, onLongClick = {
                engine.setMini(null)
                engine.stop()
            }, onFocus = { focused = it })
            .background(Color.Black, shape),
    ) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = false; isFocusable = false; player = engine.player } },
            update = { v -> if (v.player !== engine.player) v.player = engine.player },
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            if (focused) tr("OK: Vollbild · lange OK: schließen") else info.title,
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
