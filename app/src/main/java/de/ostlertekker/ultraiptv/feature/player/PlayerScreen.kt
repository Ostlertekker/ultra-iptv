package de.ostlertekker.ultraiptv.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.player.TrackOption
import de.ostlertekker.ultraiptv.core.player.UltraQualityEngine
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.UltraLoader
import de.ostlertekker.ultraiptv.feature.smartskip.SegmentType
import kotlinx.coroutines.delay

private enum class Panel { AUDIO, SUBTITLES, ASPECT, SPEED, SMART_SKIP, CHANNELS }

private fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
fun PlayerScreen(vm: PlayerViewModel, onExit: () -> Unit) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val ui by vm.ui.collectAsStateWithLifecycle()
    val stats by vm.engine.stats.collectAsStateWithLifecycle()
    val tracksVersion by vm.engine.tracksVersion.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var interaction by remember { mutableLongStateOf(0L) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val countdownFocus = remember { FocusRequester() }
    val isLive = ui.kind == ContentKind.LIVE

    LaunchedEffect(overlay, interaction, panel) {
        if (overlay && panel == null) { delay(5000); overlay = false }
    }
    LaunchedEffect(overlay, panel, ui.countdown != null) {
        delay(60)
        runCatching {
            when {
                ui.countdown != null -> countdownFocus.requestFocus()
                panel != null -> panelFocus.requestFocus()
                overlay -> playFocus.requestFocus()
                else -> rootFocus.requestFocus()
            }
        }
    }
    LaunchedEffect(seekHint) { if (seekHint != null) { delay(900); seekHint = null } }
    LaunchedEffect(ui.ended) { if (ui.ended) onExit() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                val down = ev.type == KeyEventType.KeyDown
                interaction = System.currentTimeMillis()
                if (ev.key == Key.Back) {
                    if (!down) {
                        when {
                            ui.countdown != null -> vm.cancelCountdown()
                            panel != null -> panel = null
                            overlay -> overlay = false
                            else -> onExit()
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (ev.key == Key.MediaPlayPause || ev.key == Key.MediaPlay || ev.key == Key.MediaPause) {
                    if (down) vm.togglePlay(); return@onPreviewKeyEvent true
                }
                if (ev.key == Key.ChannelUp || ev.key == Key.ChannelDown) {
                    if (down) vm.switchChannel(if (ev.key == Key.ChannelUp) -1 else 1); return@onPreviewKeyEvent true
                }
                if (ev.key == Key.MediaFastForward || ev.key == Key.MediaRewind) {
                    if (down) { val d = if (ev.key == Key.MediaFastForward) 30_000L else -10_000L; vm.seekBy(d); seekHint = if (d > 0) "+30 s" else "−10 s" }
                    return@onPreviewKeyEvent true
                }
                if (ui.countdown != null || panel != null || overlay) return@onPreviewKeyEvent false
                // Overlay ausgeblendet: Schnellsteuerung
                if (!down) return@onPreviewKeyEvent true
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (ui.skipOffer != null) vm.acceptSkip() else overlay = true
                    Key.DirectionLeft -> if (isLive) overlay = true else { vm.seekBy(-10_000); seekHint = "−10 s" }
                    Key.DirectionRight -> if (isLive) overlay = true else { vm.seekBy(10_000); seekHint = "+10 s" }
                    Key.DirectionUp -> if (isLive) vm.switchChannel(-1) else overlay = true
                    Key.DirectionDown -> if (isLive) vm.switchChannel(1) else overlay = true
                    Key.Menu -> { overlay = true; panel = if (isLive) Panel.CHANNELS else Panel.AUDIO }
                    else -> overlay = true
                }
                true
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    player = vm.engine.player
                }
            },
            update = { view ->
                if (view.player !== vm.engine.player) view.player = vm.engine.player
                view.resizeMode = ui.resizeMode
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (ui.buffering && ui.error == null) UltraLoader(Modifier.align(Alignment.Center))

        ui.error?.let { err ->
            Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(err, color = Color.White, fontSize = 17.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("Erneut versuchen", onClick = { vm.retry() }, primary = true)
                    TvButton("Schließen", onClick = onExit)
                }
            }
        }

        seekHint?.let {
            Text(it, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(18.dp))
        }

        ui.toast?.let {
            Text(it, color = Color.White, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 30.dp)
                .background(p.surfaceFocused, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 8.dp))
        }

        // Smart-Skip-Angebot (auch bei ausgeblendetem Overlay sichtbar – OK drückt es)
        val offer = ui.skipOffer
        if (offer != null && !overlay && panel == null) {
            Text("${offer.segment.type.label} überspringen  ⏎", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(48.dp).background(p.primary, RoundedCornerShape(10.dp)).padding(horizontal = 20.dp, vertical = 12.dp))
        }

        if (ui.showStats) StatsOverlay(stats, ui, Modifier.align(Alignment.TopStart).padding(24.dp))

        AnimatedVisibility(overlay && ui.countdown == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f)))))
                Column(Modifier.padding(36.dp)) {
                    Text(ui.title, color = Color.White, fontSize = 28.sp, fontWeight = theme.spec.titleWeight, fontFamily = theme.spec.fontFamily, maxLines = 1)
                    Text(ui.subtitle, color = p.textDim, fontSize = 15.sp, maxLines = 1)
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 36.dp, vertical = 26.dp)) {
                    if (isLive) {
                        if (ui.nowTitle != null) {
                            Text("JETZT  ${ui.nowTime ?: ""}", color = p.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(ui.nowTitle ?: "", color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ProgressBar(ui.programProgress ?: 0f, null)
                            ui.nextTitle?.let { Text("DANACH  $it", color = p.textDim, fontSize = 13.sp, maxLines = 1) }
                        } else Text("Keine Programminformationen", color = p.textDim, fontSize = 13.sp)
                    } else if (ui.durationMs > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fmt(ui.positionMs), color = Color.White, fontSize = 13.sp, modifier = Modifier.width(70.dp))
                            Box(Modifier.weight(1f)) {
                                ProgressBar(ui.positionMs.toFloat() / ui.durationMs, (ui.positionMs + ui.bufferedMs).toFloat() / ui.durationMs)
                            }
                            Text(fmt(ui.durationMs), color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (offer != null) item { TvButton("${offer.segment.type.label} überspringen", onClick = { vm.acceptSkip() }, primary = true) }
                        item { TvButton(if (ui.isPlaying) "❚❚ Pause" else "▶ Play", onClick = { vm.togglePlay() }, focusRequester = playFocus) }
                        if (!isLive) {
                            item { TvButton("−10 s", onClick = { vm.seekBy(-10_000) }) }
                            item { TvButton("+30 s", onClick = { vm.seekBy(30_000) }) }
                        } else {
                            item { TvButton("Kanal ▲", onClick = { vm.switchChannel(-1) }) }
                            item { TvButton("Kanal ▼", onClick = { vm.switchChannel(1) }) }
                            item { TvButton("Senderliste", onClick = { panel = Panel.CHANNELS }) }
                        }
                        item { TvButton("Audio", onClick = { panel = Panel.AUDIO }) }
                        item { TvButton("Untertitel", onClick = { panel = Panel.SUBTITLES }) }
                        item { TvButton("Bildformat", onClick = { panel = Panel.ASPECT }) }
                        if (!isLive) item { TvButton("Tempo ${ui.speed}×", onClick = { panel = Panel.SPEED }) }
                        if (ui.kind == ContentKind.EPISODE) {
                            item { TvButton("Smart Skip", onClick = { panel = Panel.SMART_SKIP }) }
                            if (ui.hasNext) item { TvButton("Nächste Folge ▶▶", onClick = { vm.playNext() }) }
                        }
                        item { TvButton(if (ui.showStats) "Statistik aus" else "Statistik", onClick = { vm.toggleStats() }) }
                    }
                }
            }
        }

        ui.countdown?.let { sec ->
            Column(Modifier.align(Alignment.BottomEnd).padding(48.dp).width(380.dp).background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp)).padding(20.dp)) {
                Text("Nächste Folge in $sec s", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("Jetzt abspielen", onClick = { vm.playNext() }, primary = true, focusRequester = countdownFocus)
                    TvButton("Abbrechen", onClick = { vm.cancelCountdown() })
                }
            }
        }

        panel?.let { current ->
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(420.dp).background(Color.Black.copy(alpha = 0.9f)).padding(24.dp)) {
                androidx.compose.runtime.key(tracksVersion) {
                    PanelContent(current, vm, ui, panelFocus) { panel = null }
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(progress: Float, buffered: Float?) {
    val p = LocalUltraTheme.current.palette
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(5.dp).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(3.dp))) {
        if (buffered != null) Box(Modifier.fillMaxWidth(buffered.coerceIn(0f, 1f)).height(5.dp).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(3.dp)))
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(5.dp).background(p.primary, RoundedCornerShape(3.dp)))
    }
}

@Composable
private fun PanelContent(panel: Panel, vm: PlayerViewModel, ui: PlayerUi, focus: FocusRequester, close: () -> Unit) {
    val p = LocalUltraTheme.current.palette
    data class Opt(val label: String, val selected: Boolean, val action: () -> Unit)
    val title: String
    val options: List<Opt>
    when (panel) {
        Panel.AUDIO -> {
            title = "Tonspur"
            options = vm.engine.options(C.TRACK_TYPE_AUDIO).map { o: TrackOption -> Opt(o.label, o.selected) { vm.engine.select(o); close() } }
                .ifEmpty { listOf(Opt("Keine Auswahl verfügbar", false) { close() }) }
        }
        Panel.SUBTITLES -> {
            title = "Untertitel"
            val off = vm.engine.isTypeDisabled(C.TRACK_TYPE_TEXT)
            options = listOf(Opt("Aus", off) { vm.engine.disableTrackType(C.TRACK_TYPE_TEXT); close() }) +
                vm.engine.options(C.TRACK_TYPE_TEXT).map { o -> Opt(o.label, o.selected && !off) { vm.engine.select(o); close() } }
        }
        Panel.ASPECT -> {
            title = "Bildformat"
            options = listOf(
                "Einpassen" to AspectRatioFrameLayout.RESIZE_MODE_FIT, "Füllen (zuschneiden)" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                "Strecken" to AspectRatioFrameLayout.RESIZE_MODE_FILL, "Volle Breite" to AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
                "Volle Höhe" to AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT,
            ).map { (l, m) -> Opt(l, ui.resizeMode == m) { vm.setResize(m); close() } }
        }
        Panel.SPEED -> {
            title = "Wiedergabetempo"
            options = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { s -> Opt("$s×", ui.speed == s) { vm.setSpeed(s); close() } }
        }
        Panel.SMART_SKIP -> {
            title = "Smart Skip"
            val pending = ui.pendingMarkStart
            options = buildList {
                if (pending != null) add(Opt("■ ${pending.first.label}-Ende = jetzt", false) { vm.markEnd(); close() })
                add(Opt("Intro-Beginn = jetzt", false) { vm.markStart(SegmentType.INTRO); close() })
                add(Opt("Rückblick-Beginn = jetzt", false) { vm.markStart(SegmentType.RECAP); close() })
                add(Opt("Credits-Beginn = jetzt", false) { vm.markStart(SegmentType.CREDITS); close() })
                add(Opt("Abspann ab hier bis Ende", false) { vm.markOutroFromHere(); close() })
                add(Opt("Markierungen dieser Folge löschen", false) { vm.clearMarkers(); close() })
                ui.segments.forEach { s ->
                    add(Opt("${s.type.label}: ${fmt(s.startMs)}–${fmt(s.endMs)} · ${s.source.label} · ${(s.confidence * 100).toInt()} %", false) {
                        vm.engine.player?.seekTo(s.startMs); close()
                    })
                }
            }
        }
        Panel.CHANNELS -> {
            title = "Senderliste"
            options = vm.channelList().mapIndexed { i, c -> Opt((if (c.number > 0) "${c.number}  " else "") + c.name, c.name == ui.title) { vm.playChannelAt(i); close() } }
        }
    }
    Column {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(options) { i, o ->
                TvButton((if (o.selected) "✓ " else "") + o.label, onClick = o.action, modifier = Modifier.fillMaxWidth(),
                    primary = o.selected, focusRequester = if (i == 0) focus else null)
            }
        }
    }
}

@Composable
private fun StatsOverlay(st: de.ostlertekker.ultraiptv.core.player.PlaybackStats, ui: PlayerUi, modifier: Modifier) {
    val lines = buildList {
        add("Quelle:     " + if (st.sourceWidth > 0) UltraQualityEngine.resolutionLabel(st.sourceWidth, st.sourceHeight) else "–")
        add("Dekodiert:  " + if (st.decodedWidth > 0) "${st.decodedWidth}×${st.decodedHeight}" else "–")
        add("FPS Quelle: " + if (st.sourceFps > 0) "%.3f".format(st.sourceFps) else "unbekannt")
        add("Codec:      " + (st.videoMime ?: "–") + (st.videoCodecs?.let { " ($it)" } ?: ""))
        add("Decoder:    " + (st.videoDecoder ?: "–"))
        add("HDR:        " + (st.hdr ?: "SDR"))
        add("Audio:      " + (st.audioMime ?: "–") + if (st.audioChannels > 0) " · ${st.audioChannels} ch" else "")
        add("Puffer:     ${ui.bufferedMs / 1000} s")
        add("Dropped:    ${st.droppedFrames}")
        add("Netz:       " + if (st.bandwidthBps > 0) "%.1f Mbit/s (geschätzt)".format(st.bandwidthBps / 1e6) else "–")
        add("Fehler:     ${st.errors} · Wiederhergestellt: ${st.recoveries}")
    }
    Column(modifier.background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(10.dp)).padding(14.dp)) {
        lines.forEach { Text(it, color = Color(0xFF9CFFB0), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
    }
}
