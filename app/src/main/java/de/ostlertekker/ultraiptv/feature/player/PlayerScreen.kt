@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package de.ostlertekker.ultraiptv.feature.player

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import androidx.compose.foundation.layout.PaddingValues

import de.ostlertekker.ultraiptv.core.ui.FocusBleedV

import de.ostlertekker.ultraiptv.core.ui.FocusBleedH

import de.ostlertekker.ultraiptv.core.ui.focusBleed

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
import androidx.compose.ui.focus.onFocusChanged
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
import de.ostlertekker.ultraiptv.core.ui.tvFocusable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import de.ostlertekker.ultraiptv.core.ui.UltraLoader
import de.ostlertekker.ultraiptv.feature.smartskip.SegmentType
import kotlinx.coroutines.delay

private enum class Panel { AUDIO, SUBTITLES, ASPECT, SPEED, CHANNELS, CINEMA, SYNC }

private val subtitleColors = listOf(android.graphics.Color.WHITE, android.graphics.Color.YELLOW, android.graphics.Color.CYAN)
private val subtitleColorNames = listOf(tr("Weiß"), "Gelb", "Cyan")
private val subtitleBgNames = listOf(tr("Kein Hintergrund"), "Halbtransparent", "Schwarz")

private fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    settings: AppSettings,
    inPip: Boolean,
    pipSupported: Boolean,
    onExit: () -> Unit,
    onPip: () -> Boolean,
    onMini: () -> Unit,
    popupActive: Boolean = false,
) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val ui by vm.ui.collectAsStateWithLifecycle()
    val stats by vm.engine.stats.collectAsStateWithLifecycle()
    val tracksVersion by vm.engine.tracksVersion.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var health by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var interaction by remember { mutableLongStateOf(0L) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
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
    LaunchedEffect(overlay, panel, ui.countdown != null, popupActive) {
        // Eine Erinnerungs-Einblendung hat den Fokus → nicht wegnehmen
        if (popupActive) return@LaunchedEffect
        delay(60)
        runCatching {
            when {
                ui.countdown != null -> countdownFocus.requestFocus()
                panel != null -> panelFocus.requestFocus()
                // Filme/Serien: wie bei TiviMate steht der Fokus zuerst auf der Zeitleiste (◀ ▶ spulen)
                overlay && !isLive && ui.durationMs > 0 -> seekFocus.requestFocus()
                overlay -> playFocus.requestFocus()
                else -> rootFocus.requestFocus()
            }
        }
    }
    LaunchedEffect(overlay) { if (!overlay) moreOpen = false }
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
                            overlay && moreOpen -> moreOpen = false
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
                // Live-TV: kein Zurück-/Vorspulen
                if (isLive && (ev.key == Key.MediaRewind || ev.key == Key.MediaFastForward)) return@onPreviewKeyEvent true
                // Live-TV: ▲ ▼ zappen sofort – auch wenn die Steuerleiste gerade eingeblendet ist
                if (isLive && overlay && panel == null && !moreOpen && ui.countdown == null && settings.upDownAction != 1 &&
                    (ev.key == Key.DirectionUp || ev.key == Key.DirectionDown)) {
                    if (down) { overlay = false; vm.switchChannel((if (ev.key == Key.DirectionUp) -1 else 1) * zapDir) }
                    return@onPreviewKeyEvent true
                }
                if (ui.countdown != null || panel != null || overlay) return@onPreviewKeyEvent false
                // Overlay ausgeblendet: Schnellsteuerung
                if (!down) return@onPreviewKeyEvent true
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (ui.skipOffer != null) vm.acceptSkip() else if (isLive && settings.okAction == 1) { overlay = true; panel = Panel.CHANNELS } else overlay = true
                    // Filme/Serien: ◀ ▶ spult sofort 10 s und blendet die Zeitleiste ein (dort weiter spulen, gedrückt halten = schneller)
                    Key.DirectionLeft -> if (isLive) overlay = true else { vm.seekBy(-10_000); seekHint = "−10 s"; overlay = true }
                    Key.DirectionRight -> if (isLive) overlay = true else { vm.seekBy(10_000); seekHint = "+10 s"; overlay = true }
                    Key.DirectionUp -> if (isLive && settings.upDownAction == 1) { overlay = true; panel = Panel.CHANNELS } else if (isLive) vm.switchChannel(-1 * zapDir) else overlay = true
                    Key.DirectionDown -> if (isLive && settings.upDownAction == 1) { overlay = true; panel = Panel.CHANNELS } else if (isLive) vm.switchChannel(1 * zapDir) else overlay = true
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
            if (!inPip) Text(if (b <= 0) tr("● LIVE") else "⏪ −%d:%02d".format(b / 60_000, (b / 1000) % 60), color = Color.White, fontSize = 40.sp,
                fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 26.dp, vertical = 10.dp))
        }
        if (!inPip) {
            // Bei geöffneter Info-Leiste zeigt die Zeitleiste den Versatz – Abzeichen würden den Titel überdecken
            if (!overlay) ScoreChip(ui, Modifier.align(Alignment.TopStart).padding(top = 30.dp, start = 36.dp))
            FrameStepHint(ui, Modifier.align(Alignment.TopCenter).padding(top = 30.dp))
            if (health) HealthMonitor(vm, Modifier.align(Alignment.Center).fillMaxWidth(0.86f))
        }
        // Uhr im Player

        ui.error?.let { err ->
            Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(err, color = Color.White, fontSize = 17.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton(tr("Erneut versuchen"), onClick = { vm.retry() }, primary = true)
                    TvButton(tr("Schließen"), onClick = onExit)
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
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(260.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.80f), Color.Black.copy(alpha = 0.95f)))))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 36.dp)) { InfoPanel(ui, stats, settings, true) }
        }

        AnimatedVisibility(overlay && ui.countdown == null && !inPip, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            // TiviMate-Stil: alles in einem Info-Panel am unteren Rand, darunter eine Reihe runder Symbol-Knöpfe
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(if (moreOpen) 400.dp else 330.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.80f), Color.Black.copy(alpha = 0.95f)))))
                var ctlLabel by remember { mutableStateOf<String?>(null) }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 18.dp).crazyEntrance(0, overlayShown)) {
                    InfoPanel(ui, stats, settings, isLive, seekFocus, onSeekTo = { vm.seekTo(it) })
                    Spacer(Modifier.height(12.dp))
                    if (moreOpen) {
                        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (isLive) PlayerCtl(tr("Letzter Sender"), onClick = { vm.lastChannel() })
                            PlayerCtl("Bildformat", onClick = { panel = Panel.ASPECT })
                            if (!isLive || ui.shiftBehindMs > 0) PlayerCtl(trf("Tempo {0}×", ui.speed), onClick = { panel = Panel.SPEED })
                            PlayerCtl("Sync", onClick = { panel = Panel.SYNC })
                            PlayerCtl(if (health) tr("Zustand aus") else "Stream-Zustand", onClick = { health = !health }, active = health)
                            val left = if (ui.sleepAt > 0) ((ui.sleepAt - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1) else 0
                            PlayerCtl(if (left > 0) trf("Sleep {0} min", left) else "Sleep-Timer", onClick = { vm.cycleSleep() }, active = left > 0)
                            PlayerCtl(if (ui.showStats) tr("Statistik aus") else "Statistik", onClick = { vm.toggleStats() }, active = ui.showStats)
                            PlayerCtl("Mini-Player", onClick = onMini)
                            if (pipSupported) PlayerCtl("Bild-in-Bild", onClick = { if (!onPip()) onMini() })
                        }
                    }
                    val lbl: (String) -> (Boolean) -> Unit = { name -> { f -> if (f) ctlLabel = name else if (ctlLabel == name) ctlLabel = null } }
                    Row(Modifier.fillMaxWidth().crazyEntrance(1, overlayShown), verticalAlignment = Alignment.CenterVertically) {
                        LazyRow(Modifier.weight(1f).focusBleed(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, contentPadding = PaddingValues(horizontal = FocusBleedH, vertical = FocusBleedV)) {
                            item {
                                TmIcon(if (ui.isPlaying) "Pause" else "Wiedergabe", { vm.togglePlay() }, lbl(if (ui.isPlaying) "Pause" else "Wiedergabe"), playFocus) { c ->
                                    if (ui.isPlaying) PauseGlyph(c) else Icon(Icons.Default.PlayArrow, null, tint = c, modifier = Modifier.size(24.dp))
                                }
                            }
                            if (offer != null) item { PlayerCtl("${offer.segment.type.label} überspringen", onClick = { vm.acceptSkip() }, primary = true) }
                            if (!isLive) {
                                item { TmIcon(tr("10 s zurück"), { vm.seekBy(-10_000) }, lbl(tr("10 s zurück"))) { c -> TmText("−10", c) } }
                                item { TmIcon(tr("30 s vor"), { vm.seekBy(30_000) }, lbl(tr("30 s vor"))) { c -> TmText("+30", c) } }
                                if (ui.kind == ContentKind.EPISODE && ui.hasNext) item { TmIcon(tr("Nächste Folge"), { vm.playNext() }, lbl(tr("Nächste Folge"))) { c -> NextGlyph(c) } }
                            } else {
                                if (ui.shiftBehindMs > 0) item { PlayerCtl(tr("● LIVE"), onClick = { vm.goLive() }, primary = true) }
                                item { TmIcon(tr("Sender hoch"), { vm.switchChannel(-1) }, lbl(tr("Sender hoch"))) { c -> Icon(Icons.Default.KeyboardArrowUp, null, tint = c, modifier = Modifier.size(26.dp)) } }
                                item { TmIcon(tr("Sender runter"), { vm.switchChannel(1) }, lbl(tr("Sender runter"))) { c -> Icon(Icons.Default.KeyboardArrowDown, null, tint = c, modifier = Modifier.size(26.dp)) } }
                                item { TmIcon("Senderliste", { panel = Panel.CHANNELS }, lbl("Senderliste")) { c -> Icon(Icons.AutoMirrored.Filled.List, null, tint = c, modifier = Modifier.size(22.dp)) } }
                            }
                        }
                        Row(Modifier.padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TmIcon("Audiospur", { panel = Panel.AUDIO }, lbl("Audiospur")) { c -> TmText("♫\uFE0E", c, 18) }
                            TmIcon("Untertitel", { panel = Panel.SUBTITLES }, lbl("Untertitel")) { c -> CcGlyph(c) }
                            TmIcon(if (moreOpen) "Weniger" else tr("Weitere Optionen"), { moreOpen = !moreOpen }, lbl(if (moreOpen) "Weniger" else tr("Weitere Optionen")), active = moreOpen) { c ->
                                Icon(Icons.Default.MoreVert, null, tint = c, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    Text(ctlLabel ?: "", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1,
                        modifier = Modifier.padding(top = 6.dp).height(20.dp))
                }
            }
        }

        ui.countdown?.let { sec ->
            Column(Modifier.align(Alignment.BottomEnd).padding(48.dp).width(380.dp).background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp)).padding(20.dp)) {
                Text(trf("Nächste Folge in {0} s", sec), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton(tr("Jetzt abspielen"), onClick = { vm.playNext() }, primary = true, focusRequester = countdownFocus)
                    TvButton(tr("Abbrechen"), onClick = { vm.cancelCountdown() })
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
            title = tr("Tonspur")
            options = vm.engine.options(C.TRACK_TYPE_AUDIO).map { o: TrackOption -> Opt(o.label, o.selected) { vm.engine.select(o); close() } }
                .ifEmpty { listOf(Opt(tr("Keine Auswahl verfügbar"), false) { close() }) }
        }
        Panel.SUBTITLES -> {
            title = tr("Untertitel")
            val off = vm.engine.isTypeDisabled(C.TRACK_TYPE_TEXT)
            options = listOf(Opt(tr("Aus"), off) { vm.engine.disableTrackType(C.TRACK_TYPE_TEXT); close() }) +
                vm.engine.options(C.TRACK_TYPE_TEXT).map { o -> Opt(o.label, o.selected && !off) { vm.engine.select(o); close() } } +
                listOf(
                    Opt(trf("Schriftgröße: {0} %", ((settings.subtitleScale * 100).toInt())), false) { vm.cycleSubtitleScale(settings.subtitleScale) },
                    Opt(trf("Farbe: {0}", (subtitleColorNames[settings.subtitleColor.coerceIn(0, 2)])), false) { vm.cycleSubtitleColor(settings.subtitleColor) },
                    Opt(trf("Hintergrund: {0}", (subtitleBgNames[settings.subtitleBackground.coerceIn(0, 2)])), false) { vm.cycleSubtitleBg(settings.subtitleBackground) },
                )
        }
        Panel.ASPECT -> {
            title = tr("Bildformat")
            options = listOf(
                "Einpassen" to AspectRatioFrameLayout.RESIZE_MODE_FIT, tr("Füllen (zuschneiden)") to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                "Strecken" to AspectRatioFrameLayout.RESIZE_MODE_FILL, tr("Volle Breite") to AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
                tr("Volle Höhe") to AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT,
            ).map { (l, m) -> Opt(l, ui.resizeMode == m) { vm.setResize(m); close() } }
        }
        Panel.SPEED -> {
            title = tr("Wiedergabetempo")
            options = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f).map { s -> Opt("$s×", ui.speed == s) { vm.setSpeed(s); close() } }
        }
        Panel.CINEMA -> {
            title = tr("Kinomodus")
            val soundNames = listOf("Normal", tr("Nachtmodus (leise Szenen lauter, laute leiser)"), tr("Dialog klar"), "Bass+")
            options = listOf(
                Opt(if (settings.cinemaMode) tr("Kinomodus ausschalten") else tr("Kinomodus einschalten"), settings.cinemaMode) { vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.CINEMA_MODE, !settings.cinemaMode) },
                Opt(trf("Kinoklang: {0}", (soundNames[settings.cinemaSound.coerceIn(0, 3)])), settings.cinemaSound > 0) { vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.CINEMA_SOUND, (settings.cinemaSound + 1) % 4) },
                Opt(trf("Helligkeit: {0} %", settings.picBrightness), settings.picBrightness != 100) { vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.PIC_BRIGHTNESS, cyclePct(settings.picBrightness)) },
                Opt(trf("Kontrast: {0} %", settings.picContrast), settings.picContrast != 100) { vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.PIC_CONTRAST, cyclePct(settings.picContrast)) },
                Opt(trf("Farbsättigung: {0} %", settings.picSaturation), settings.picSaturation != 100) { vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.PIC_SATURATION, cyclePct(settings.picSaturation)) },
                Opt(tr("Bildregler zurücksetzen"), false) {
                    vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.PIC_BRIGHTNESS, 100); vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.PIC_CONTRAST, 100)
                    vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.PIC_SATURATION, 100)
                },
            )
        }
        Panel.SYNC -> {
            title = tr("Synchronisation")
            options = listOf(0, 250, 500, 1000, 1500, 2000, 3000, 5000).map { d ->
                Opt(if (d == 0) tr("Untertitel: ohne Verzögerung") else trf("Untertitel: +{0} ms später", d), settings.subDelayMs == d) { vm.setPref(de.ostlertekker.ultraiptv.core.data.Keys.SUB_DELAY, d); close() }
            } + listOf(Opt(tr("Ton-Verschiebung: vom Player (Media3) auf diesem Gerät nicht unterstützt"), false) { })
        }
        Panel.CHANNELS -> {
            title = tr("Senderliste")
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
        add(tr("Quelle:     ") + if (st.sourceWidth > 0) UltraQualityEngine.resolutionLabel(st.sourceWidth, st.sourceHeight) else "–")
        add(tr("Dekodiert:  ") + if (st.decodedWidth > 0) "${st.decodedWidth}×${st.decodedHeight}" else "–")
        add(tr("FPS Quelle: ") + if (st.sourceFps > 0) "%.3f".format(st.sourceFps) else "unbekannt")
        add(tr("Codec:      ") + (st.videoMime ?: "–") + (st.videoCodecs?.let { " ($it)" } ?: ""))
        add(tr("Decoder:    ") + (st.videoDecoder ?: "–"))
        add(tr("HDR:        ") + (st.hdr ?: "SDR"))
        add(tr("Audio:      ") + (st.audioMime ?: "–") + if (st.audioChannels > 0) " · ${st.audioChannels} ch" else "")
        add(trf("Puffer:     {0} s", (ui.bufferedMs / 1000)))
        add(trf("Dropped:    {0}", st.droppedFrames))
        add(tr("Netz:       ") + if (st.bandwidthBps > 0) "%.1f Mbit/s (geschätzt)".format(st.bandwidthBps / 1e6) else "–")
        add(trf("Fehler:     {0} · Wiederhergestellt: {1}", st.errors, st.recoveries) + if (st.httpCode > 0) trf(" · HTTP {0}", st.httpCode) else "")
        add(tr("FPS real:   ") + if (st.measuredFps > 0) tr("%.1f (gemessen)").format(st.measuredFps) else "–")
        add(trf("Nachladen:  {0}× · {1} s Stillstand", st.rebuffers, (st.stallMs / 1000.0)))
        add(tr("Bitrate:    ") + if (st.videoBitrate > 0) "${st.videoBitrate / 1000} kbit/s (Manifest)" else "–")
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


/** Kompakter Player-Knopf im Stil der Navigationspillen: dezent, flach, Fokus = weiße Pille. */
@Composable
private fun PlayerCtl(label: String, onClick: () -> Unit, focusRequester: FocusRequester? = null,
                      primary: Boolean = false, active: Boolean = false, icon: Boolean = false) {
    val p = LocalUltraTheme.current.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .then(if (icon) Modifier.width(40.dp) else Modifier)
            .height(32.dp)
            .tvFocusable(shape, onClick, focusRequester = focusRequester, scaleOnFocus = false, onFocus = { focused = it })
            .background(when { focused -> Color.White; primary -> p.primary.copy(alpha = 0.35f); active -> Color.White.copy(alpha = 0.22f); else -> Color.White.copy(alpha = 0.10f) }, shape)
            .padding(horizontal = if (icon) 0.dp else 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) Color(0xFF111111) else Color.White, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}


/** Info-Panel im TiviMate-Stil: Logo/Poster links, Titel + Sendung + Fortschritt in der Mitte, Uhr und Bildinfo rechts. */
@Composable
private fun InfoPanel(ui: PlayerUi, stats: de.ostlertekker.ultraiptv.core.player.PlaybackStats, settings: AppSettings, isLive: Boolean,
                      seekFocus: FocusRequester? = null, onSeekTo: ((Long) -> Unit)? = null) {
    val p = LocalUltraTheme.current.palette
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        val artW = if (isLive) 132.dp else 84.dp
        val artH = if (isLive) 88.dp else 124.dp
        Box(Modifier.size(artW, artH).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            var ok by remember(ui.artwork) { mutableStateOf(false) }
            if (!ok) Text(de.ostlertekker.ultraiptv.core.ui.initialsOf(ui.title), color = Color.White.copy(alpha = 0.6f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (ui.artwork != null) AsyncImage(ui.artwork, null, contentScale = if (isLive) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(if (isLive) 10.dp else 0.dp).then(if (isLive) Modifier else Modifier.background(Color.Transparent, RoundedCornerShape(10.dp))),
                onSuccess = { ok = true }, onError = { ok = false })
        }
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text(ui.subtitle, color = p.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ui.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isLive) {
                if (ui.nowTitle != null) {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ui.nowTime ?: "", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(end = 10.dp))
                        Text(ui.nowTitle, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ProgressBar(ui.programProgress ?: 0f, null)
                    ui.nextTitle?.let { Text(trf("Danach:  {0}", it), color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                } else Text(tr("Keine Programminformationen"), color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            } else if (ui.durationMs > 0) {
                Spacer(Modifier.height(8.dp))
                if (onSeekTo != null) SeekBar(ui, seekFocus, onSeekTo)
                else {
                    ProgressBar(ui.positionMs.toFloat() / ui.durationMs, (ui.positionMs + ui.bufferedMs).toFloat() / ui.durationMs)
                    Row(Modifier.fillMaxWidth()) {
                        Text(fmt(ui.positionMs), color = Color.White, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text("−" + fmt((ui.durationMs - ui.positionMs).coerceAtLeast(0)) + "  ·  " + fmt(ui.durationMs), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.width(22.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (settings.playerClock && !settings.cinemaMode) PlayerClock(settings.clock24h, Modifier)
            // Auflösung / Bildrate / HDR des laufenden Videos (gemessen)
            val badges = buildList {
                if (stats.decodedHeight > 0) add(when {
                    stats.decodedHeight >= 2100 -> "4K"; stats.decodedHeight >= 1060 -> "FHD"; stats.decodedHeight >= 700 -> "HD"; else -> "SD"
                })
                if (stats.decodedHeight > 0) add("${stats.decodedWidth}×${stats.decodedHeight}")
                if (stats.measuredFps > 0) add(tr("%.0f fps").format(stats.measuredFps)) else if (stats.sourceFps > 0) add(tr("%.0f fps").format(stats.sourceFps))
                stats.hdr?.let { add(it.substringBefore(" (")) }
            }
            if (badges.isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                badges.forEach { b ->
                    Text(b, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
            ui.qualityNote?.let { Text(it, color = p.primary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

/** Runder Symbol-Knopf (TiviMate-Stil). Die Beschriftung erscheint unter der Knopfreihe, solange der Knopf fokussiert ist. */
@Composable
private fun TmIcon(label: String, onClick: () -> Unit, onFocusLabel: (Boolean) -> Unit, focusRequester: FocusRequester? = null,
                   active: Boolean = false, content: @Composable (Color) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.size(44.dp)
            .semantics { contentDescription = label }
            .tvFocusable(shape, onClick, focusRequester = focusRequester, scaleOnFocus = false, onFocus = { focused = it; onFocusLabel(it) })
            .background(when { focused -> Color.White; active -> Color.White.copy(alpha = 0.24f); else -> Color.White.copy(alpha = 0.10f) }, shape),
        contentAlignment = Alignment.Center,
    ) { content(if (focused) Color(0xFF111111) else Color.White) }
}

@Composable
private fun TmText(t: String, c: Color, size: Int = 13) = Text(t, color = c, fontSize = size.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)

@Composable
private fun PauseGlyph(c: Color) = Canvas(Modifier.size(18.dp)) {
    val w = size.width * 0.28f
    drawRoundRect(c, Offset(size.width * 0.14f, 0f), Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(2f))
    drawRoundRect(c, Offset(size.width * 0.58f, 0f), Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(2f))
}

@Composable
private fun NextGlyph(c: Color) = Canvas(Modifier.size(18.dp)) {
    val path = Path().apply { moveTo(0f, 0f); lineTo(size.width * 0.72f, size.height / 2); lineTo(0f, size.height); close() }
    drawPath(path, c)
    drawRect(c, Offset(size.width * 0.80f, 0f), Size(size.width * 0.18f, size.height))
}

@Composable
private fun CcGlyph(c: Color) = Box(Modifier.border(1.6.dp, c, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp)) {
    Text("CC", color = c, fontSize = 10.sp, fontWeight = FontWeight.Black)
}


/**
 * Fokussierbare Zeitleiste (Filme/Serien): ◀ ▶ verschiebt die Zielposition – gedrückt halten wird schneller
 * (10 s → 30 s → 1 min → 2 min). Nach kurzer Pause oder mit OK wird dorthin gesprungen.
 */
@Composable
private fun SeekBar(ui: PlayerUi, focusRequester: FocusRequester?, onSeekTo: (Long) -> Unit) {
    val p = LocalUltraTheme.current.palette
    var focused by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(scrub) {
        val t = scrub ?: return@LaunchedEffect
        kotlinx.coroutines.delay(700)
        onSeekTo(t)
        kotlinx.coroutines.delay(500)
        scrub = null
    }
    val dur = ui.durationMs.coerceAtLeast(1)
    val pos = scrub ?: ui.positionMs
    Column(
        Modifier.fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val rep = ev.nativeKeyEvent.repeatCount
                        val step = when { rep > 24 -> 120_000L; rep > 10 -> 60_000L; rep > 3 -> 30_000L; else -> 10_000L }
                        val sign = if (ev.key == Key.DirectionLeft) -1 else 1
                        scrub = ((scrub ?: ui.positionMs) + sign * step).coerceIn(0, (dur - 1000).coerceAtLeast(0))
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        val t = scrub
                        if (t != null) { onSeekTo(t); scrub = null; true } else false
                    }
                    else -> false
                }
            }
            .focusable()
            .padding(vertical = 4.dp),
    ) {
        val h = if (focused) 8.dp else 5.dp
        Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(h).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth(((ui.positionMs + ui.bufferedMs).toFloat() / dur).coerceIn(0f, 1f)).height(h)
                .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f)).height(h).background(p.primary, RoundedCornerShape(4.dp)))
            if (focused) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                val x = maxWidth * (pos.toFloat() / dur).coerceIn(0f, 1f)
                Box(Modifier.padding(start = (x - 7.dp).coerceAtLeast(0.dp)).size(14.dp).background(Color.White, RoundedCornerShape(50)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(fmt(pos), color = if (scrub != null) p.primary else Color.White, fontSize = if (scrub != null) 15.sp else 13.sp, fontWeight = FontWeight.Bold)
            if (focused) Text("   ◀ ▶ spulen · gedrückt halten = schneller", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("−" + fmt((ui.durationMs - pos).coerceAtLeast(0)) + "  ·  " + fmt(ui.durationMs), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
}
