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
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import de.ostlertekker.ultraiptv.core.data.AppSettings
import de.ostlertekker.ultraiptv.core.motion.CurtainOverlay
import de.ostlertekker.ultraiptv.core.motion.LocalMotion
import de.ostlertekker.ultraiptv.core.motion.crazyEntrance
import androidx.compose.runtime.mutableIntStateOf
import androidx.media3.ui.PlayerView
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.player.TrackOption
import de.ostlertekker.ultraiptv.core.player.UltraQualityEngine
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.UltraLoader
import de.ostlertekker.ultraiptv.feature.smartskip.SegmentType
import kotlinx.coroutines.delay

private enum class Panel { AUDIO, SUBTITLES, ASPECT, SPEED, SMART_SKIP, CHANNELS, CINEMA, SYNC }

private val subtitleColors = listOf(android.graphics.Color.WHITE, android.graphics.Color.YELLOW, android.graphics.Color.CYAN)
private val subtitleColorNames = listOf("Weiß", "Gelb", "Cyan")
private val subtitleBgNames = listOf("Kein Hintergrund", "Halbtransparent", "Schwarz")

private fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    settings: AppSettings,
    inPip: Boolean,
    pipSupported: Boolean,
    onExit: () -> Unit,
    onPip: () -> Boolean,
    onMini: () -> Unit,
) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val ui by vm.ui.collectAsStateWithLifecycle()
    val stats by vm.engine.stats.collectAsStateWithLifecycle()
    val tracksVersion by vm.engine.tracksVersion.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var health by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var interaction by remember { mutableLongStateOf(0L) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val countdownFocus = remember { FocusRequester() }
    val isLive = ui.kind == ContentKind.LIVE

    // CRAZY MOTION ENGINE im Player
    val motion = LocalMotion.current
    var overlayShown by remember { mutableIntStateOf(0) }
    LaunchedEffect(overlay) { if (overlay) overlayShown++ }
    var zapKey by remember { mutableLongStateOf(0L) }
    var zapSeen by remember { mutableStateOf(false) }
    LaunchedEffect(ui.zapAt) {
        if (ui.zapAt > 0) { if (zapSeen) zapKey = ui.zapAt; zapSeen = true }
    }

    LaunchedEffect(overlay, interaction, panel) {
        if (overlay && panel == null) { delay(settings.osdSeconds.coerceIn(2, 30) * 1000L); overlay = false }
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
                            ui.frameStep -> vm.toggleFrameStep()
                            health -> health = false
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
                val zapDir = if (settings.invertChannelKeys) -1 else 1
                if (ev.key == Key.ChannelUp || ev.key == Key.ChannelDown) {
                    if (down) vm.switchChannel((if (ev.key == Key.ChannelUp) -1 else 1) * zapDir); return@onPreviewKeyEvent true
                }
                // Zifferntasten: Sendernummer direkt eingeben
                val digit = digitOf(ev.key)
                if (isLive && settings.numberZap && digit != null && panel == null) {
                    if (down) vm.typeDigit(digit); return@onPreviewKeyEvent true
                }
                if (isLive && ev.key == Key.MediaPrevious) { if (down) vm.lastChannel(); return@onPreviewKeyEvent true }
                if (!isLive && (ev.key == Key.MediaFastForward || ev.key == Key.MediaRewind)) {
                    if (down) { val d = if (ev.key == Key.MediaFastForward) 30_000L else -10_000L; vm.seekBy(d); seekHint = if (d > 0) "+30 s" else "−10 s" }
                    return@onPreviewKeyEvent true
                }
                // Einzelbild-Modus: Pfeiltasten steuern Bild für Bild
                if (ui.frameStep && panel == null) {
                    if (down) when (ev.key) {
                        Key.DirectionLeft -> vm.stepFrames(-1); Key.DirectionRight -> vm.stepFrames(1)
                        Key.DirectionUp -> vm.stepFrames(10); Key.DirectionDown -> vm.stepFrames(-10)
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> vm.toggleFrameStep()
                        else -> Unit
                    }
                    return@onPreviewKeyEvent true
                }
                if (isLive && (ev.key == Key.MediaRewind || ev.key == Key.MediaFastForward)) {
                    if (down) { if (ev.key == Key.MediaRewind) vm.rewindLive(30_000) else vm.forwardShift(30_000) }
                    return@onPreviewKeyEvent true
                }
                if (ui.countdown != null || panel != null || overlay) return@onPreviewKeyEvent false
                // Overlay ausgeblendet: Schnellsteuerung
                if (!down) return@onPreviewKeyEvent true
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (ui.skipOffer != null) vm.acceptSkip() else overlay = true
                    Key.DirectionLeft -> if (isLive) { if (ui.canTimeshift) vm.rewindLive(10_000) else overlay = true } else { vm.seekBy(-10_000); seekHint = "−10 s" }
                    Key.DirectionRight -> if (isLive) { if (ui.canTimeshift) vm.forwardShift(10_000) else overlay = true } else { vm.seekBy(10_000); seekHint = "+10 s" }
                    Key.DirectionUp -> if (isLive) vm.switchChannel(-1 * zapDir) else overlay = true
                    Key.DirectionDown -> if (isLive) vm.switchChannel(1 * zapDir) else overlay = true
                    Key.Menu -> { overlay = true; panel = if (isLive) Panel.CHANNELS else Panel.AUDIO }
                    else -> overlay = true
                }
                true
            },
    ) {
        val picAdjust = pictureAdjusted(settings)
        val delayedSubs = settings.subDelayMs > 0
        androidx.compose.runtime.key(picAdjust) { AndroidView(
            factory = { ctx ->
                // Bildregler brauchen eine TextureView (Farbmatrix); sonst die schnellere SurfaceView
                (if (picAdjust) android.view.LayoutInflater.from(ctx).inflate(de.ostlertekker.ultraiptv.R.layout.ultra_player_texture, null) as PlayerView
                else PlayerView(ctx)).apply {
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
                val picKey = Triple(settings.picBrightness, settings.picContrast, settings.picSaturation)
                if (picAdjust && view.getTag(de.ostlertekker.ultraiptv.R.id.ultra_pic) != picKey) view.setTag(de.ostlertekker.ultraiptv.R.id.ultra_pic, picKey)
                    .also { (view.videoSurfaceView as? android.view.TextureView)?.setLayerType(android.view.View.LAYER_TYPE_HARDWARE,
                    android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(pictureMatrix(settings.picBrightness, settings.picContrast, settings.picSaturation)) }) }
                view.subtitleView?.visibility = if (delayedSubs) android.view.View.GONE else android.view.View.VISIBLE
                // Untertitel-Stil nur bei Änderung anwenden (nicht bei jedem Fortschritts-Update)
                val styleKey = Triple(settings.subtitleColor, settings.subtitleBackground, settings.subtitleScale)
                if (view.getTag(de.ostlertekker.ultraiptv.R.id.ultra_sub_style) != styleKey) {
                    view.setTag(de.ostlertekker.ultraiptv.R.id.ultra_sub_style, styleKey)
                view.subtitleView?.let { sv ->
                    val bg = when (settings.subtitleBackground) { 0 -> android.graphics.Color.TRANSPARENT; 1 -> 0x99000000.toInt(); else -> android.graphics.Color.BLACK }
                    sv.setApplyEmbeddedStyles(false)
                    sv.setStyle(CaptionStyleCompat(subtitleColors[settings.subtitleColor.coerceIn(0, subtitleColors.lastIndex)], bg,
                        android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, android.graphics.Color.BLACK, null))
                    sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * settings.subtitleScale)
                }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) }
        if (delayedSubs) DelayedSubtitleLayer(vm.engine.player, settings.subDelayMs, settings, Modifier.fillMaxSize().padding(bottom = 24.dp))
        if (settings.cinemaMode && !inPip) CinemaAmbience(Modifier.fillMaxSize())

        // Effekte liegen ÜBER dem Video (SurfaceView) – der Stream startet ohne Verzögerung darunter
        if (!inPip) CurtainOverlay(trigger = Unit, type = motion.playerEnter)
        if (!inPip) CurtainOverlay(trigger = zapKey, type = motion.zapEffect, skipFirst = true)

        if (ui.buffering && ui.error == null) UltraLoader(Modifier.align(Alignment.Center))

        // Automatische Wiederverbindung: kleiner Hinweis statt Fehlerdialog – der Sender läuft von selbst weiter
        ui.notice?.let { n ->
            if (ui.error == null && !inPip) Text("⟳ $n", color = Color.White, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 70.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 7.dp))
        }
        // Eingegebene Sendernummer
        if (ui.numberInput.isNotEmpty() && !inPip) {
            Text(ui.numberInput, color = Color.White, fontSize = 54.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.TopEnd).padding(40.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 24.dp, vertical = 8.dp))
        }
        // Spul-Vorschau (während Tasten gedrückt werden)
        ui.seekPreviewMs?.let { b ->
            if (!inPip) Text(if (b <= 0) "● LIVE" else "⏪ −%d:%02d".format(b / 60_000, (b / 1000) % 60), color = Color.White, fontSize = 40.sp,
                fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 26.dp, vertical = 10.dp))
        }
        if (!inPip) {
            // Bei geöffneter Info-Leiste zeigt die Zeitleiste den Versatz – Abzeichen würden den Titel überdecken
            if (!overlay) TimeshiftBadge(ui, Modifier.align(Alignment.TopStart).padding(top = 30.dp, start = 36.dp))
            if (!overlay) ScoreChip(ui, Modifier.align(Alignment.TopStart).padding(top = if (ui.shiftBehindMs >= 5_000 || ui.livePaused) 66.dp else 30.dp, start = 36.dp))
            FrameStepHint(ui, Modifier.align(Alignment.TopCenter).padding(top = 30.dp))
            if (health) HealthMonitor(vm, Modifier.align(Alignment.Center).fillMaxWidth(0.86f))
        }
        // Uhr im Player
        if (settings.playerClock && !inPip && overlay && !settings.cinemaMode) {
            PlayerClock(settings.clock24h, Modifier.align(Alignment.TopCenter).padding(top = 36.dp))
        }

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

        if (ui.showStats && !inPip) StatsOverlay(stats, ui, Modifier.align(Alignment.TopStart).padding(24.dp))

        // Info-Banner beim Senderwechsel (Name, Nummer, laufende Sendung)
        var bannerVisible by remember { mutableStateOf(false) }
        LaunchedEffect(ui.zapAt) {
            if (ui.zapAt > 0 && settings.zapBanner) { bannerVisible = true; delay(settings.osdSeconds.coerceIn(2, 30) * 800L); bannerVisible = false }
        }
        if (bannerVisible && !overlay && !inPip && isLive) {
            Column(Modifier.align(Alignment.BottomStart).padding(36.dp).width(560.dp)
                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(14.dp)).padding(18.dp)) {
                Text(ui.subtitle, color = p.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(ui.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (ui.nowTitle != null) {
                    Text("${ui.nowTime ?: ""}  ${ui.nowTitle}", color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ProgressBar(ui.programProgress ?: 0f, null)
                }
                ui.nextTitle?.let { Text("Danach: $it", color = p.textDim, fontSize = 13.sp, maxLines = 1) }
            }
        }

        AnimatedVisibility(overlay && ui.countdown == null && !inPip, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f)))))
                Column(Modifier.padding(36.dp).crazyEntrance(0, overlayShown)) {
                    Text(ui.title, color = Color.White, fontSize = 28.sp, fontWeight = theme.spec.titleWeight, fontFamily = theme.spec.fontFamily, maxLines = 1)
                    Text(ui.subtitle, color = p.textDim, fontSize = 15.sp, maxLines = 1)
                }
                // Auflösung / Bildrate / HDR des laufenden Videos (gemessen)
                val badge = buildList {
                    if (stats.decodedHeight > 0) add(when {
                        stats.decodedHeight >= 2100 -> "4K"; stats.decodedHeight >= 1060 -> "FULL HD"; stats.decodedHeight >= 700 -> "HD"; else -> "SD"
                    } + " ${stats.decodedWidth}×${stats.decodedHeight}")
                    if (stats.measuredFps > 0) add("%.0f fps".format(stats.measuredFps)) else if (stats.sourceFps > 0) add("%.0f fps".format(stats.sourceFps))
                    stats.hdr?.let { add(it.substringBefore(" (")) }
                }.joinToString("  ·  ")
                if (badge.isNotEmpty()) Text(badge, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(36.dp).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp))
                ui.qualityNote?.let { Text(it, color = p.primary, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 36.dp)) }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 36.dp, vertical = 26.dp)) {
                    if (isLive && ui.tsBufferedMs > 0) LiveTimeline(ui)
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
                    LazyRow(Modifier.crazyEntrance(1, overlayShown), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (offer != null) item { TvButton("${offer.segment.type.label} überspringen", onClick = { vm.acceptSkip() }, primary = true) }
                        item { TvButton(if (ui.isPlaying) "❚❚ Pause" else "▶ Play", onClick = { vm.togglePlay() }, focusRequester = playFocus) }
                        if (!isLive) {
                            item { TvButton("−10 s", onClick = { vm.seekBy(-10_000) }) }
                            item { TvButton("+30 s", onClick = { vm.seekBy(30_000) }) }
                        } else {
                            item { TvButton("Kanal ▲", onClick = { vm.switchChannel(-1) }) }
                            item { TvButton("Kanal ▼", onClick = { vm.switchChannel(1) }) }
                            item { TvButton("Senderliste", onClick = { panel = Panel.CHANNELS }) }
                            item { TvButton("↺ Letzter Sender", onClick = { vm.lastChannel() }) }
                            if (ui.canTimeshift || ui.shiftBehindMs > 0) {
                                item { TvButton("⏪ −10 s", onClick = { vm.rewindLive(10_000) }) }
                                item { TvButton("⏪ −30 s", onClick = { vm.rewindLive(30_000) }) }
                                item { TvButton("⏪ −5 min", onClick = { vm.rewindLive(300_000) }) }
                                if (ui.shiftBehindMs > 0) {
                                    item { TvButton("+10 s ⏩", onClick = { vm.forwardShift(10_000) }) }
                                    item { TvButton("+30 s ⏩", onClick = { vm.forwardShift(30_000) }) }
                                    item { TvButton("● Zurück zu LIVE", onClick = { vm.goLive() }, primary = true) }
                                }
                            }
                            item { TvButton("⊞ Mehrfachansicht", onClick = { de.ostlertekker.ultraiptv.feature.multiview.MultiViewActivity.openWithComparison(context, vm.currentItemId()); onExit() }) }
                        }
                        item { TvButton("Audio", onClick = { panel = Panel.AUDIO }) }
                        item { TvButton("Untertitel", onClick = { panel = Panel.SUBTITLES }) }
                        item { TvButton("Bildformat", onClick = { panel = Panel.ASPECT }) }
                        if (!isLive || ui.shiftBehindMs > 0) item { TvButton("Tempo ${ui.speed}×", onClick = { panel = Panel.SPEED }) }
                        if (!isLive || ui.shiftBehindMs > 0) item { TvButton("Einzelbild", onClick = { overlay = false; vm.toggleFrameStep() }) }
                        item { TvButton("Sync", onClick = { panel = Panel.SYNC }) }
                        item { TvButton(if (settings.cinemaMode) "🎬 Kino: AN" else "🎬 Kinomodus", onClick = { panel = Panel.CINEMA }) }
                        item { TvButton(if (health) "Zustand aus" else "Stream-Zustand", onClick = { health = !health }) }
                        if (ui.kind == ContentKind.EPISODE) {
                            item { TvButton("Intro überspringen", onClick = { panel = Panel.SMART_SKIP }) }
                            if (ui.hasNext) item { TvButton("Nächste Folge ▶▶", onClick = { vm.playNext() }) }
                        }
                        item {
                            val left = if (ui.sleepAt > 0) ((ui.sleepAt - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1) else 0
                            TvButton(if (left > 0) "☾ Sleep $left min" else "☾ Sleep-Timer", onClick = { vm.cycleSleep() })
                        }
                        item { TvButton(if (ui.showStats) "Statistik aus" else "Statistik", onClick = { vm.toggleStats() }) }
                        item { TvButton("Mini-Player", onClick = onMini) }
                        if (pipSupported) item { TvButton("Bild-in-Bild", onClick = { if (!onPip()) onMini() }) }
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
                    PanelContent(current, vm, ui, settings, panelFocus) { panel = null }
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
private fun PanelContent(panel: Panel, vm: PlayerViewModel, ui: PlayerUi, settings: AppSettings, focus: FocusRequester, close: () -> Unit) {
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
                vm.engine.options(C.TRACK_TYPE_TEXT).map { o -> Opt(o.label, o.selected && !off) { vm.engine.select(o); close() } } +
                listOf(
                    Opt("Schriftgröße: ${(settings.subtitleScale * 100).toInt()} %", false) { vm.cycleSubtitleScale(settings.subtitleScale) },
                    Opt("Farbe: ${subtitleColorNames[settings.subtitleColor.coerceIn(0, 2)]}", false) { vm.cycleSubtitleColor(settings.subtitleColor) },
                    Opt("Hintergrund: ${subtitleBgNames[settings.subtitleBackground.coerceIn(0, 2)]}", false) { vm.cycleSubtitleBg(settings.subtitleBackground) },
                )
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
            options = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f).map { s -> Opt("$s×", ui.speed == s) { vm.setSpeed(s); close() } }
        }
        Panel.CINEMA -> {
            title = "Kinomodus"
            val soundNames = listOf("Normal", "Nachtmodus (leise Szenen lauter, laute leiser)", "Dialog klar", "Bass+")
            options = listOf(
                Opt(if (settings.cinemaMode) "Kinomodus ausschalten" else "Kinomodus einschalten", settings.cinemaMode) { vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.CINEMA_MODE, !settings.cinemaMode) },
                Opt("Kinoklang: ${soundNames[settings.cinemaSound.coerceIn(0, 3)]}", settings.cinemaSound > 0) { vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.CINEMA_SOUND, (settings.cinemaSound + 1) % 4) },
                Opt("Helligkeit: ${settings.picBrightness} %", settings.picBrightness != 100) { vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.PIC_BRIGHTNESS, cyclePct(settings.picBrightness)) },
                Opt("Kontrast: ${settings.picContrast} %", settings.picContrast != 100) { vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.PIC_CONTRAST, cyclePct(settings.picContrast)) },
                Opt("Farbsättigung: ${settings.picSaturation} %", settings.picSaturation != 100) { vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.PIC_SATURATION, cyclePct(settings.picSaturation)) },
                Opt("Bildregler zurücksetzen", false) {
                    vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.PIC_BRIGHTNESS, 100); vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.PIC_CONTRAST, 100)
                    vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.PIC_SATURATION, 100)
                },
            )
        }
        Panel.SYNC -> {
            title = "Synchronisation"
            options = listOf(0, 250, 500, 1000, 1500, 2000, 3000, 5000).map { d ->
                Opt(if (d == 0) "Untertitel: ohne Verzögerung" else "Untertitel: +${d} ms später", settings.subDelayMs == d) { vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.SUB_DELAY, d); close() }
            } + listOf(Opt("Ton-Verschiebung: vom Player (Media3) auf diesem Gerät nicht unterstützt", false) { })
        }
        Panel.SMART_SKIP -> {
            title = "Intro überspringen"
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
        add("Fehler:     ${st.errors} · Wiederhergestellt: ${st.recoveries}" + if (st.httpCode > 0) " · HTTP ${st.httpCode}" else "")
        add("FPS real:   " + if (st.measuredFps > 0) "%.1f (gemessen)".format(st.measuredFps) else "–")
        add("Nachladen:  ${st.rebuffers}× · ${st.stallMs / 1000.0} s Stillstand")
        add("Bitrate:    " + if (st.videoBitrate > 0) "${st.videoBitrate / 1000} kbit/s (Manifest)" else "–")
    }
    Column(modifier.background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(10.dp)).padding(14.dp)) {
        lines.forEach { Text(it, color = Color(0xFF9CFFB0), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
    }
}


private fun digitOf(k: Key): Int? = when (k) {
    Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2; Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4; Key.Five, Key.NumPad5 -> 5; Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8; Key.Nine, Key.NumPad9 -> 9; else -> null
}

@Composable
private fun PlayerClock(h24: Boolean, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    Text(de.ostlertekker.ultraiptv.core.ui.formatClock(now, h24), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = modifier)
}


private fun cyclePct(v: Int): Int = listOf(70, 80, 90, 100, 110, 120, 130).let { l -> l[(l.indexOf(v).coerceAtLeast(0) + 1) % l.size] }


/** Zeitleiste des Live-Puffers: links ältester Stand, rechts LIVE, Markierung = aktuelle Position. */
@Composable
private fun LiveTimeline(ui: PlayerUi) {
    val p = LocalUltraTheme.current.palette
    val buffered = ui.tsBufferedMs.coerceAtLeast(1)
    val behind = (ui.seekPreviewMs ?: ui.shiftBehindMs).coerceIn(0, buffered)
    val pos = 1f - behind.toFloat() / buffered
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(pos.coerceIn(0.01f, 1f)).height(6.dp).background(p.primary, RoundedCornerShape(3.dp)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
            Text("−%d:%02d".format(buffered / 60_000, (buffered / 1000) % 60), color = p.textDim, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text(if (behind < 1000) "● LIVE" else "−%d:%02d hinter Live".format(behind / 60_000, (behind / 1000) % 60),
                color = if (behind < 1000) Color(0xFFFF5050) else p.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("◀ ▶ = 10 s · ⏪ ⏩ = 30 s", color = p.textDim, fontSize = 11.sp)
        }
    }
}
