@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package de.ostlertekker.ultraiptv.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import de.ostlertekker.ultraiptv.core.animation.rememberAnimationClock
import de.ostlertekker.ultraiptv.core.data.AppSettings
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.player.PlaybackStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

/** Bildregler (Cinema Mode) als Farbmatrix – wird auf die TextureView des Videos angewendet. */
fun pictureMatrix(brightness: Int, contrast: Int, saturation: Int): android.graphics.ColorMatrix {
    val b = brightness / 100f; val c = contrast / 100f
    val m = android.graphics.ColorMatrix().apply { setSaturation(saturation / 100f) }
    val t = (1f - c) * 128f + (b - 1f) * 255f
    m.postConcat(android.graphics.ColorMatrix(floatArrayOf(
        c, 0f, 0f, 0f, t,
        0f, c, 0f, 0f, t,
        0f, 0f, c, 0f, t,
        0f, 0f, 0f, 1f, 0f,
    )))
    return m
}

fun pictureAdjusted(s: AppSettings) = s.cinemaMode && (s.picBrightness != 100 || s.picContrast != 100 || s.picSaturation != 100)

/** Untertitel mit einstellbarer Verzögerung (Audio-/Untertitel-Synchronisation). */
@Composable
fun DelayedSubtitleLayer(player: Player?, delayMs: Int, settings: AppSettings, modifier: Modifier) {
    if (player == null) return
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    val scope = rememberCoroutineScope()
    DisposableEffect(player, delayMs) {
        val l = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                val c = cueGroup.cues
                scope.launch { delay(delayMs.toLong()); cues = c }
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    AndroidView(factory = { SubtitleView(it) }, update = { sv ->
        val bg = when (settings.subtitleBackground) { 0 -> android.graphics.Color.TRANSPARENT; 1 -> 0x99000000.toInt(); else -> android.graphics.Color.BLACK }
        val colors = listOf(android.graphics.Color.WHITE, android.graphics.Color.YELLOW, android.graphics.Color.CYAN)
        sv.setApplyEmbeddedStyles(false)
        sv.setStyle(CaptionStyleCompat(colors[settings.subtitleColor.coerceIn(0, 2)], bg, android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, android.graphics.Color.BLACK, null))
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * settings.subtitleScale)
        sv.setCues(cues)
    }, modifier = modifier)
}

/** Kinomodus-Atmosphäre: Vignette, sanft wanderndes Randlicht, einfahrende Kinobalken beim Start. */
@Composable
fun CinemaAmbience(modifier: Modifier) {
    val p = LocalUltraTheme.current.palette
    val clock = rememberAnimationClock(true, fps = 20, pauseOnInput = true)
    Canvas(modifier) {
        val t = clock.value
        val w = size.width; val h = size.height
        // Vignette
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.55f)), Offset(w / 2, h / 2), w * 0.75f))
        // Randlicht (sehr dezent, damit das Bild nicht gestört wird)
        val glow = 0.05f + 0.03f * sin(t * 0.6f)
        drawRect(Brush.horizontalGradient(listOf(p.primary.copy(alpha = glow), Color.Transparent), endX = w * 0.08f))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, p.secondary.copy(alpha = glow)), startX = w * 0.92f))
        // Kinobalken fahren in den ersten Sekunden herein und wieder heraus
        val bars = when { t < 1.2f -> t / 1.2f; t < 3.5f -> 1f; t < 4.5f -> 1f - (t - 3.5f); else -> 0f }.coerceIn(0f, 1f)
        if (bars > 0f) {
            val bh = h * 0.12f * bars
            drawRect(Color.Black, Offset(0f, 0f), Size(w, bh))
            drawRect(Color.Black, Offset(0f, h - bh), Size(w, bh))
        }
    }
}

/** Professioneller Stream Health Monitor – alle Werte gemessen oder als „nicht messbar“ gekennzeichnet. */
@Composable
fun HealthMonitor(vm: PlayerViewModel, modifier: Modifier) {
    val p = LocalUltraTheme.current.palette
    val bufferHist = remember { mutableStateListOf<Float>() }
    val rateHist = remember { mutableStateListOf<Float>() }
    var snap by remember { mutableStateOf<HealthSnapshot?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val pl = vm.engine.player
            val st = vm.engine.stats.value
            if (pl != null) {
                val buf = pl.totalBufferedDuration
                val offset = pl.currentLiveOffset
                bufferHist.add(buf / 1000f); if (bufferHist.size > 90) bufferHist.removeAt(0)
                rateHist.add(st.bandwidthBps / 1_000_000f); if (rateHist.size > 90) rateHist.removeAt(0)
                snap = HealthSnapshot(st, buf, if (offset == C.TIME_UNSET) null else offset, pl.playbackState, pl.isPlaying, vm.engine.notice.value, bufferHist.toList(), rateHist.toList())
            }
            delay(1000)
        }
    }
    val s = snap
    Column(modifier.background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(16.dp)).padding(18.dp)) {
        Text2("STREAM-ZUSTAND", p.primary, 14, true)
        Spacer(Modifier.height(8.dp))
        if (s == null) Text2("Messung läuft …", p.textDim, 13, false) else HealthBody(s)
    }
}

@Composable
private fun HealthBody(s: HealthSnapshot) {
    val p = LocalUltraTheme.current.palette
    Column {
        val st = s.stats
        val score = stability(st)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Stabilität", "$score / 100", if (score >= 80) Color(0xFF2EE59D) else if (score >= 50) Color(0xFFFFC94D) else Color(0xFFFF5A5A))
            Tile("Verbindung", when (s.state) { Player.STATE_READY -> if (s.playing) "Wiedergabe" else "Bereit"; Player.STATE_BUFFERING -> "Puffert"; Player.STATE_ENDED -> "Beendet"; else -> "Getrennt" }, Color.White)
            Tile("Auflösung", if (st.decodedWidth > 0) "${st.decodedWidth}×${st.decodedHeight}" else "–", Color.White)
            Tile("FPS", buildString { if (st.measuredFps > 0) append("%.1f".format(st.measuredFps)) else append("–"); if (st.sourceFps > 0) append(" / Quelle %.0f".format(st.sourceFps)) }, Color.White)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Video-Codec", listOfNotNull(st.videoMime?.substringAfter('/'), st.videoCodecs).joinToString(" · ").ifEmpty { "–" }, Color.White)
            Tile("Audio-Codec", listOfNotNull(st.audioMime?.substringAfter('/'), st.audioChannels.takeIf { it > 0 }?.let { "$it ch" }, st.audioSampleRate.takeIf { it > 0 }?.let { "${it / 1000} kHz" }).joinToString(" · ").ifEmpty { "–" }, Color.White)
            Tile("Bitrate", buildString {
                if (st.videoBitrate > 0) append("Video ${st.videoBitrate / 1000} kbit/s") else append("Video –")
                if (st.bandwidthBps > 0) append(" · Netz %.1f Mbit/s".format(st.bandwidthBps / 1_000_000f))
            }, Color.White)
            Tile("Latenz", s.liveOffset?.let { "%.1f s hinter Live".format(it / 1000f) } ?: "nicht messbar", Color.White)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Puffer", "%.1f s".format(s.bufferMs / 1000f), if (s.bufferMs < 2000) Color(0xFFFFC94D) else Color.White)
            Tile("Nachladen", "${st.rebuffers}× · ${st.stallMs / 1000} s", Color.White)
            Tile("Verworfene Bilder", "${st.droppedFrames}", Color.White)
            Tile("Paketverlust", "nicht messbar (HTTP/TCP)", p.textDim)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text2("Puffer (s), letzte 90 s", p.textDim, 11, false); Spark(s.bufferHist, p.primary) }
            Column(Modifier.weight(1f)) { Text2("Netzdurchsatz (Mbit/s)", p.textDim, 11, false); Spark(s.rateHist, p.secondary) }
        }
        Spacer(Modifier.height(6.dp))
        Text2(listOfNotNull("Fehler: ${st.errors}", "Wiederherstellungen: ${st.recoveries}", st.httpCode.takeIf { it > 0 }?.let { "HTTP $it" }, st.lastError?.let { "Letzter: $it" }, s.notice).joinToString("  ·  "), p.textDim, 11, false)
        Text2("Zuschauerzahl: keine Nutzungsdaten verfügbar – IPTV-Anbieter liefern sie nicht", p.textDim, 11, false)
    }
}

private data class HealthSnapshot(val stats: PlaybackStats, val bufferMs: Long, val liveOffset: Long?, val state: Int, val playing: Boolean, val notice: String?, val bufferHist: List<Float> = emptyList(), val rateHist: List<Float> = emptyList())

/** Stabilitätswert 0–100 aus echten Messwerten (Nachladen, Abbrüche, verworfene Bilder). */
fun stability(st: PlaybackStats): Int {
    var s = 100
    s -= (st.rebuffers * 6).coerceAtMost(40)
    s -= (st.errors * 8).coerceAtMost(30)
    s -= (st.recoveries * 4).coerceAtMost(20)
    s -= (st.droppedFrames / 50).toInt().coerceAtMost(15)
    return s.coerceIn(0, 100)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tile(label: String, value: String, color: Color) {
    val p = LocalUltraTheme.current.palette
    Column(Modifier.weight(1f).background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text2(label.uppercase(), p.textDim, 10, true)
        Text2(value, color, 14, true)
    }
}

@Composable
private fun Spark(values: List<Float>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(46.dp)) {
        if (values.size < 2) return@Canvas
        val max = (values.maxOrNull() ?: 1f).coerceAtLeast(0.1f)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i / (values.size - 1f) * size.width
            val y = size.height - v / max * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.5f))
    }
}

@Composable
private fun Text2(text: String, color: Color, size: Int, bold: Boolean) {
    androidx.compose.material3.Text(text, color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 2)
}

/** Kleine Spielstand-Anzeige (echte Daten) + Torblitz bei Änderungen. */
@Composable
fun ScoreChip(ui: PlayerUi, modifier: Modifier) {
    val ev = ui.score ?: return
    val p = LocalUltraTheme.current.palette
    Column(modifier) {
        Row(Modifier.background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (ev.live) androidx.compose.material3.Text("● ", color = Color(0xFFFF4040), fontSize = 12.sp)
            androidx.compose.material3.Text(ev.scoreLine, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            androidx.compose.material3.Text("  ${ev.clock?.takeIf { ev.live } ?: ev.detail}", color = p.textDim, fontSize = 12.sp)
        }
        androidx.compose.material3.Text("Daten: ESPN", color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
        ui.scoreFlash?.let {
            androidx.compose.material3.Text(it, color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif,
                modifier = Modifier.padding(top = 8.dp).background(p.primary, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

/** Einzelbild-Hinweis mit aktueller Position. */
@Composable
fun FrameStepHint(ui: PlayerUi, modifier: Modifier) {
    if (!ui.frameStep) return
    val p = LocalUltraTheme.current.palette
    Box(modifier.background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
        androidx.compose.material3.Text("EINZELBILD  ·  %d:%02d.%03d  ·  ◀ ▶ 1 Bild  ▲ ▼ 10 Bilder  ·  OK = Ende".format(
            ui.positionMs / 60000, (ui.positionMs / 1000) % 60, ui.positionMs % 1000), color = p.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Suppress("unused") private val keepWidth = Modifier.width(1.dp)
