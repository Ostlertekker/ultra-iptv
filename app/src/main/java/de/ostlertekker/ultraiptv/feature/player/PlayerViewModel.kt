package de.ostlertekker.ultraiptv.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.data.AppSettings
import de.ostlertekker.ultraiptv.core.data.ContentRepository
import de.ostlertekker.ultraiptv.core.data.EpgRepository
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.database.ChannelEntity
import de.ostlertekker.ultraiptv.core.database.EpisodeEntity
import de.ostlertekker.ultraiptv.core.database.MovieEntity
import de.ostlertekker.ultraiptv.core.database.ChannelQualityEntity
import de.ostlertekker.ultraiptv.core.database.ExtrasDao
import de.ostlertekker.ultraiptv.core.device.DeviceCapabilityManager
import de.ostlertekker.ultraiptv.core.lan.LanHub
import de.ostlertekker.ultraiptv.core.lan.WatchState
import de.ostlertekker.ultraiptv.core.player.MiniInfo
import de.ostlertekker.ultraiptv.core.search.SmartSearch
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.player.PlayerEngine
import de.ostlertekker.ultraiptv.core.player.UltraQualityEngine
import de.ostlertekker.ultraiptv.core.security.StreamResolver
import de.ostlertekker.ultraiptv.feature.smartskip.SegmentType
import de.ostlertekker.ultraiptv.feature.smartskip.SkipAction
import de.ostlertekker.ultraiptv.feature.smartskip.skipPolicyFrom
import de.ostlertekker.ultraiptv.feature.smartskip.SkipSegment
import de.ostlertekker.ultraiptv.feature.smartskip.SmartSkipEngine
import de.ostlertekker.ultraiptv.feature.smartskip.SmartSkipRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUi(
    val title: String = "",
    val subtitle: String = "",
    val kind: ContentKind = ContentKind.MOVIE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val isPlaying: Boolean = false,
    val buffering: Boolean = true,
    val error: String? = null,
    val nowTitle: String? = null,
    val nowTime: String? = null,
    val nextTitle: String? = null,
    val programProgress: Float? = null,
    val skipOffer: SkipAction.Offer? = null,
    val countdown: Int? = null,
    val hasNext: Boolean = false,
    val resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    val speed: Float = 1f,
    val showStats: Boolean = false,
    val toast: String? = null,
    val ended: Boolean = false,
    val segments: List<SkipSegment> = emptyList(),
    val pendingMarkStart: Pair<SegmentType, Long>? = null,
    /** Zeitpunkt des letzten Senderwechsels (für das Info-Banner). */
    val zapAt: Long = 0,
    val guest: Boolean = false,
    val qualityNote: String? = null,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    val engine: PlayerEngine,
    private val content: ContentRepository,
    private val user: UserRepository,
    private val settingsRepo: SettingsRepository,
    private val resolver: StreamResolver,
    private val epg: EpgRepository,
    private val skipRepo: SmartSkipRepository,
    private val device: DeviceCapabilityManager,
    private val lan: LanHub,
    private val extras: ExtrasDao,
) : ViewModel() {

    private val kind = runCatching { ContentKind.valueOf(savedState.get<String>(PlayerActivity.EXTRA_KIND) ?: "") }.getOrDefault(ContentKind.MOVIE)
    private var itemId: String = savedState.get<String>(PlayerActivity.EXTRA_ID) ?: ""
    private val startMs: Long = savedState.get<Long>(PlayerActivity.EXTRA_START) ?: 0L
    private val category: String? = savedState.get<String>(PlayerActivity.EXTRA_CATEGORY)

    private val _ui = MutableStateFlow(PlayerUi(kind = kind))
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    /** Quell-Bildrate für die Anpassung des Anzeigemodus (null = nichts tun). */
    val desiredFps = MutableStateFlow<Float?>(null)

    private lateinit var settings: AppSettings
    private var channels: List<ChannelEntity> = emptyList()
    private var channelIndex = 0
    private var movie: MovieEntity? = null
    private var episode: EpisodeEntity? = null
    private var nextEpisode: EpisodeEntity? = null
    private var image: String? = null
    private val skip = SmartSkipEngine()
    private var segments: List<SkipSegment> = emptyList()
    private var ticker: Job? = null
    private var countdownJob: Job? = null
    private var countdownCancelled = false
    private var lastSave = 0L
    private var lastEpg = 0L
    private var started = false

    /** true = Engine nach dem Schließen weiterlaufen lassen (Mini-Player / Hintergrund-Audio). */
    var keepEngine = false
    private var qualityCap: Int? = null
    private val triedChannels = HashSet<String>()
    private var rebufferBase = 0
    private var rebufferWindowStart = 0L
    private var lastQualitySave = 0L
    private var watchStart = System.currentTimeMillis()
    private var lastFatal = 0

    init {
        engine.setMini(null)
        viewModelScope.launch {
            settings = settingsRepo.current()
            loadAndPlay(startMs)
            started = true
            startTicker()
        }
        viewModelScope.launch {
            engine.error.collect { e -> _ui.update { it.copy(error = e) } }
        }
        viewModelScope.launch {
            engine.stats.collect { st -> if (settingsOrNull()?.matchFrameRate == true && st.sourceFps > 0) desiredFps.value = st.sourceFps }
        }
        viewModelScope.launch {
            lastFatal = engine.fatal.value
            engine.fatal.collect { n -> if (n > lastFatal) { lastFatal = n; onFatal() } }
        }
        // Watch Together als Gast: Inhalt, Position und Pause des Host-Geräts übernehmen
        viewModelScope.launch {
            lan.guestState.collect { w -> if (w != null && started) followHost(w) }
        }
        _ui.update { it.copy(guest = lan.guestRemote.value != null) }
    }

    private fun settingsOrNull(): AppSettings? = if (this::settings.isInitialized) settings else null

    private suspend fun loadAndPlay(start: Long) {
        when (kind) {
            ContentKind.LIVE -> {
                val ch = content.channel(itemId) ?: return fail("Sender nicht gefunden")
                channels = (category?.let { content.channelsInOnce(it) } ?: emptyList()).ifEmpty { listOf(ch) }
                channelIndex = channels.indexOfFirst { it.id == ch.id }.coerceAtLeast(0)
                playChannel(ch)
            }
            ContentKind.MOVIE -> {
                val m = content.movieOnce(itemId) ?: return fail("Film nicht gefunden")
                movie = m; image = m.poster
                _ui.update { it.copy(title = m.name, subtitle = listOfNotNull(m.year, m.genre).joinToString(" · ")) }
                play(m.streamUrl, start)
            }
            ContentKind.EPISODE -> {
                val ep = content.episode(itemId) ?: return fail("Episode nicht gefunden")
                playEpisode(ep, start)
            }
            ContentKind.SERIES -> fail("Bitte eine Episode wählen")
        }
    }

    private fun fail(msg: String) { _ui.update { it.copy(error = msg, buffering = false) } }

    private fun play(raw: String, start: Long) {
        val url = try { resolver.resolve(raw, settings.liveFormat) } catch (e: Exception) { return fail(e.message ?: "Stream nicht verfügbar") }
        engine.play(url, start, settings.bufferProfile)
        viewModelScope.launch { applyQuality() }
    }

    private suspend fun applyQuality() {
        val profile = user.activeProfileOnce()
        val displayMax = device.displayModes().maxOfOrNull { it.height } ?: 0
        var c = UltraQualityEngine.constraints(settings.qualityMode, settings.manualMaxHeight, displayMax, device.maxDecoderHeight())
        val cap = qualityCap
        if (cap != null && cap < c.maxHeight) c = c.copy(maxHeight = cap, maxWidth = UltraQualityEngine.widthFor(cap))
        // Automatische Spracherkennung: ohne Profilvorgabe die Gerätesprache bevorzugen
        val audio = profile?.audioLanguage?.ifBlank { null } ?: java.util.Locale.getDefault().language
        engine.applyConstraints(c, audio, profile?.subtitleLanguage ?: "")
    }

    private fun playChannel(ch: ChannelEntity) {
        saveChannelQuality()
        watchStart = System.currentTimeMillis()
        itemId = ch.id; image = ch.logo
        _ui.update { it.copy(zapAt = System.currentTimeMillis(), qualityNote = null) }
        _ui.update { it.copy(title = ch.name, subtitle = (if (ch.number > 0) "${ch.number} · " else "") + ch.category, nowTitle = null, nextTitle = null, programProgress = null) }
        play(ch.streamUrl, 0)
        viewModelScope.launch {
            user.saveProgress(ContentKind.LIVE, ch.id, 0, 0, ch.name, ch.logo)
            refreshEpg(ch)
        }
    }

    private suspend fun refreshEpg(ch: ChannelEntity) {
        lastEpg = System.currentTimeMillis()
        val nn = epg.nowNext(ch.epgId)
        val now = System.currentTimeMillis()
        val cur = nn.firstOrNull { it.startMs <= now }
        val next = nn.firstOrNull { it.startMs > now } ?: nn.getOrNull(1)
        _ui.update {
            it.copy(
                nowTitle = cur?.title, nextTitle = next?.let { n -> "${de.ostlertekker.ultraiptv.feature.livetv.formatTime(n.startMs)}  ${n.title}" },
                nowTime = cur?.let { c -> "${de.ostlertekker.ultraiptv.feature.livetv.formatTime(c.startMs)}–${de.ostlertekker.ultraiptv.feature.livetv.formatTime(c.endMs)}" },
                programProgress = cur?.let { c -> ((now - c.startMs).toFloat() / (c.endMs - c.startMs)).coerceIn(0f, 1f) },
            )
        }
    }

    private suspend fun playEpisode(ep: EpisodeEntity, start: Long) {
        episode = ep; itemId = ep.id; image = ep.image
        skip.reset(); countdownCancelled = false; countdownJob?.cancel()
        val all = content.episodesOfOnce(ep.seriesId)
        val idx = all.indexOfFirst { it.id == ep.id }
        nextEpisode = if (idx >= 0) all.getOrNull(idx + 1) else null
        val series = content.seriesOnce(ep.seriesId)
        if (image == null) image = series?.poster
        segments = skipRepo.segmentsFor(ep)
        _ui.update {
            it.copy(title = series?.name ?: ep.title, subtitle = "S${ep.season} E${ep.episode} · ${ep.title}", hasNext = nextEpisode != null,
                countdown = null, skipOffer = null, ended = false, segments = segments, pendingMarkStart = null)
        }
        play(ep.streamUrl, start)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                tick()
                delay(500)
            }
        }
    }

    private suspend fun tick() {
        val p = engine.player ?: return
        engine.sampleFps()
        autoQualityCheck()
        publishWatch()
        if (kind == ContentKind.LIVE && System.currentTimeMillis() - lastQualitySave > 30_000) saveChannelQuality()
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val state = p.playbackState
        _ui.update {
            it.copy(positionMs = pos, durationMs = dur, bufferedMs = p.totalBufferedDuration, isPlaying = p.isPlaying,
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE && it.error == null)
        }
        val now = System.currentTimeMillis()
        when (kind) {
            ContentKind.LIVE -> if (now - lastEpg > 30_000) channels.getOrNull(channelIndex)?.let { refreshEpg(it) }
            ContentKind.EPISODE -> handleSkip(pos, dur, state)
            ContentKind.MOVIE -> if (state == Player.STATE_ENDED) { saveProgress(); _ui.update { it.copy(ended = true) } }
            else -> Unit
        }
        if (kind != ContentKind.LIVE && dur > 0 && now - lastSave > 10_000) saveProgress()
    }

    private suspend fun handleSkip(pos: Long, dur: Long, state: Int) {
        val ep = episode ?: return
        if (state == Player.STATE_ENDED) {
            saveProgress()
            if (nextEpisode != null && settings.autoNextEpisode && !countdownCancelled) playNext()
            else _ui.update { it.copy(ended = nextEpisode == null) }
            return
        }
        val action = skip.evaluate(segments, pos, dur, skipPolicyFrom(settings), hasNext = nextEpisode != null)
        when (action) {
            is SkipAction.AutoSkip -> {
                skip.markHandled(action.segment)
                engine.player?.seekTo(action.targetMs)
                toast("${action.segment.type.label} übersprungen")
                _ui.update { it.copy(skipOffer = null) }
            }
            is SkipAction.Offer -> _ui.update { it.copy(skipOffer = action) }
            SkipAction.NextEpisode -> {
                _ui.update { it.copy(skipOffer = null) }
                if (countdownJob == null && !countdownCancelled) startCountdown()
            }
            SkipAction.None -> if (_ui.value.skipOffer != null) _ui.update { it.copy(skipOffer = null) }
        }
    }

    private fun startCountdown() {
        countdownJob = viewModelScope.launch {
            var left = settings.nextCountdownSec
            while (left > 0 && isActive) {
                _ui.update { it.copy(countdown = left) }
                delay(1000)
                left--
            }
            if (isActive) playNext()
        }
    }

    fun cancelCountdown() {
        countdownCancelled = true
        countdownJob?.cancel(); countdownJob = null
        _ui.update { it.copy(countdown = null) }
    }

    fun playNext() {
        val next = nextEpisode ?: return
        countdownJob?.cancel(); countdownJob = null
        viewModelScope.launch {
            saveProgress()
            val pr = user.progress(ContentKind.EPISODE, next.id)
            val resume = if (pr != null && pr.durationMs > 0 && pr.positionMs < pr.durationMs * 0.95) pr.positionMs else 0L
            playEpisode(next, resume)
        }
    }

    /** Nutzer drückt "Intro überspringen". */
    fun acceptSkip() {
        val offer = _ui.value.skipOffer ?: return
        skip.markHandled(offer.segment)
        val p = engine.player ?: return
        if (offer.targetMs >= _ui.value.durationMs - 1000 && nextEpisode != null) playNext() else p.seekTo(offer.targetMs)
        _ui.update { it.copy(skipOffer = null) }
        val ep = episode
        if (ep != null) viewModelScope.launch { skipRepo.confirm(ep, offer.segment); segments = skipRepo.segmentsFor(ep); _ui.update { it.copy(segments = segments) } }
    }

    // ---------------------------------------------------- Smart-Skip-Markierungen

    fun markStart(type: SegmentType) {
        _ui.update { it.copy(pendingMarkStart = type to (engine.player?.currentPosition ?: 0L)) }
        toast("${type.label}-Beginn gesetzt – jetzt Ende markieren")
    }

    fun markEnd() {
        val ep = episode ?: return
        val (type, start) = _ui.value.pendingMarkStart ?: return
        val end = engine.player?.currentPosition ?: return
        if (end <= start + 3000) { toast("Ende muss mindestens 3 s nach dem Beginn liegen"); return }
        viewModelScope.launch {
            skipRepo.saveUserMarker(ep, type, start, end)
            segments = skipRepo.segmentsFor(ep)
            skip.reset()
            _ui.update { it.copy(pendingMarkStart = null, segments = segments) }
            toast("${type.label} gespeichert (${start / 1000}s–${end / 1000}s)")
        }
    }

    fun markOutroFromHere() {
        val ep = episode ?: return
        val pos = engine.player?.currentPosition ?: return
        val dur = _ui.value.durationMs
        if (dur <= 0) return
        viewModelScope.launch {
            skipRepo.saveUserMarker(ep, SegmentType.OUTRO, pos, dur)
            segments = skipRepo.segmentsFor(ep)
            _ui.update { it.copy(segments = segments) }
            toast("Abspann ab ${pos / 1000}s gespeichert")
        }
    }

    fun clearMarkers() {
        val ep = episode ?: return
        viewModelScope.launch {
            skipRepo.clear(ep); segments = skipRepo.segmentsFor(ep); skip.reset()
            _ui.update { it.copy(segments = segments) }
            toast("Markierungen dieser Folge gelöscht")
        }
    }

    // ---------------------------------------------------- Steuerung

    fun togglePlay() { engine.player?.let { if (it.isPlaying) it.pause() else it.play() } }

    fun seekBy(deltaMs: Long) {
        val p = engine.player ?: return
        if (kind == ContentKind.LIVE && !p.isCurrentMediaItemSeekable) return
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        p.seekTo(if (p.duration > 0) target.coerceAtMost(p.duration - 1000) else target)
    }

    fun switchChannel(delta: Int) {
        if (kind != ContentKind.LIVE || channels.size < 2) return
        channelIndex = (channelIndex + delta).mod(channels.size)
        playChannel(channels[channelIndex])
    }

    fun channelList(): List<ChannelEntity> = channels
    fun playChannelAt(index: Int) { if (index in channels.indices) { channelIndex = index; playChannel(channels[index]) } }

    fun setResize(mode: Int) = _ui.update { it.copy(resizeMode = mode) }
    fun setSpeed(speed: Float) { engine.player?.setPlaybackSpeed(speed); _ui.update { it.copy(speed = speed) } }
    fun toggleStats() = _ui.update { it.copy(showStats = !it.showStats) }
    fun retry() { engine.player?.let { it.prepare(); it.playWhenReady = true }; _ui.update { it.copy(error = null) } }

    private fun toast(msg: String) {
        _ui.update { it.copy(toast = msg) }
        viewModelScope.launch { delay(2500); _ui.update { if (it.toast == msg) it.copy(toast = null) else it } }
    }

    private suspend fun saveProgress() {
        lastSave = System.currentTimeMillis()
        val p = engine.player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val pos = p.currentPosition
        when (kind) {
            ContentKind.MOVIE -> movie?.let { user.saveProgress(ContentKind.MOVIE, it.id, pos, dur, it.name, image) }
            ContentKind.EPISODE -> episode?.let { user.saveProgress(ContentKind.EPISODE, it.id, pos, dur, _ui.value.title + " · S${it.season}E${it.episode}", image, it.seriesId) }
            else -> Unit
        }
    }

    fun onStopped(keepPlaying: Boolean) {
        viewModelScope.launch { saveProgress() }
        if (keepPlaying) return
        ticker?.cancel()
        engine.player?.pause()
    }

    fun cycleSubtitleScale(cur: Float) = viewModelScope.launch {
        val list = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        settingsRepo.set(de.ostlertekker.ultraiptv.core.data.Keys.SUB_SCALE, list[(list.indexOf(cur) + 1).mod(list.size)])
    }
    fun cycleSubtitleColor(cur: Int) = viewModelScope.launch { settingsRepo.set(de.ostlertekker.ultraiptv.core.data.Keys.SUB_COLOR, (cur + 1) % 3) }
    fun cycleSubtitleBg(cur: Int) = viewModelScope.launch { settingsRepo.set(de.ostlertekker.ultraiptv.core.data.Keys.SUB_BG, (cur + 1) % 3) }

    val backgroundAudioEnabled: Boolean get() = settingsOrNull()?.backgroundAudio == true
    val isPlayingNow: Boolean get() = engine.player?.isPlaying == true

    fun onStarted() {
        if (!started) return
        engine.player?.play()
        startTicker()
    }

    fun onExit() {
        saveChannelQuality()
        lan.publishWatch(null)
        if (!keepEngine) engine.stop()
    }

    override fun onCleared() {
        if (!keepEngine) engine.stop()
        super.onCleared()
    }

    // ---------------------------------------------------- Mini-Player

    fun toMini() {
        keepEngine = true
        val cat = if (kind == ContentKind.LIVE) channels.getOrNull(channelIndex)?.category ?: category else category
        engine.setMini(MiniInfo(kind.name, itemId, _ui.value.title, cat))
    }

    // ---------------------------------------------------- Sender-Qualität & Auto-Optimierung

    private fun saveChannelQuality() {
        if (kind != ContentKind.LIVE || itemId.isBlank()) return
        lastQualitySave = System.currentTimeMillis()
        val st = engine.stats.value
        if (st.decodedWidth <= 0 && st.errors == 0) return
        val id = itemId
        val watched = System.currentTimeMillis() - watchStart
        viewModelScope.launch {
            val old = extras.quality(id)
            extras.upsertQuality(ChannelQualityEntity(
                channelId = id, lastPlayed = System.currentTimeMillis(),
                width = st.decodedWidth.takeIf { it > 0 } ?: old?.width ?: 0, height = st.decodedHeight.takeIf { it > 0 } ?: old?.height ?: 0,
                fps = if (st.measuredFps > 0) st.measuredFps else if (st.sourceFps > 0) st.sourceFps else old?.fps ?: 0f,
                codec = st.videoMime ?: old?.codec, droppedFrames = st.droppedFrames, rebuffers = st.rebuffers,
                watchMs = (old?.watchMs ?: 0L) + watched, errors = st.errors,
            ))
        }
    }

    /** Automatische Qualitätsoptimierung: bei häufigem Nachladen oder vielen verworfenen Frames eine Stufe runter. */
    private suspend fun autoQualityCheck() {
        if (!settings.autoQuality) return
        val st = engine.stats.value
        val now = System.currentTimeMillis()
        if (rebufferWindowStart == 0L || now - rebufferWindowStart > 120_000) { rebufferWindowStart = now; rebufferBase = st.rebuffers; return }
        val recent = st.rebuffers - rebufferBase
        val tooManyDrops = st.measuredFps in 1f..20f && st.sourceFps > 24f
        if (recent >= 3 || tooManyDrops) {
            val currentH = qualityCap ?: st.decodedHeight.takeIf { it > 0 } ?: return
            val next = listOf(2160, 1440, 1080, 720, 540, 360).firstOrNull { it < currentH } ?: return
            qualityCap = next
            applyQuality()
            rebufferWindowStart = now; rebufferBase = st.rebuffers
            val reason = if (recent >= 3) "häufiges Nachladen" else "Bildaussetzer"
            _ui.update { it.copy(qualityNote = "Qualität automatisch auf max. ${next}p gesenkt ($reason)") }
            toast("Qualität angepasst: max. ${next}p")
        }
    }

    // ---------------------------------------------------- Automatische Fehlererkennung / Ausweichsender

    private fun coreName(name: String): String = SmartSearch.normalize(
        name.replace(Regex("^\\s*[\\[|(]?[A-Za-z]{2,3}[\\]|)]?\\s*[:|\\-]\\s*"), "")
            .replace(Regex("(?i)\\b(uhd|fhd|hd|sd|4k|hevc|h265|backup|raw|vip|multi|50fps|60fps)\\b"), "")
            .replace(Regex("[\\[(].*?[\\])]"), ""),
    )

    private fun onFatal() {
        if (kind != ContentKind.LIVE || !settings.autoFailover) return
        val cur = channels.getOrNull(channelIndex) ?: return
        triedChannels += cur.id
        viewModelScope.launch {
            val core = coreName(cur.name)
            if (core.length < 2) return@launch
            val alt = content.channelsNamed(core.split(' ').first())
                .firstOrNull { it.id !in triedChannels && coreName(it.name) == core }
            if (alt != null) {
                toast("Sender gestört – weiche auf „${alt.name}“ aus")
                val idx = channels.indexOfFirst { it.id == alt.id }
                if (idx >= 0) channelIndex = idx else { channels = channels + alt; channelIndex = channels.lastIndex }
                playChannel(alt)
            }
        }
    }

    // ---------------------------------------------------- Watch Together

    private fun publishWatch() {
        if (lan.status.value.hostingWatch) {
            val p = engine.player
            lan.publishWatch(WatchState(kind.name, itemId, if (kind == ContentKind.LIVE) channels.getOrNull(channelIndex)?.category else category,
                _ui.value.title, p?.currentPosition ?: 0L, p?.isPlaying == true, System.currentTimeMillis()))
        }
    }

    private fun followHost(w: WatchState) {
        val p = engine.player ?: return
        if (w.kind != kind.name) return
        if (w.id != itemId) {
            if (kind == ContentKind.LIVE) {
                viewModelScope.launch {
                    val ch = content.channel(w.id) ?: return@launch
                    val idx = channels.indexOfFirst { it.id == ch.id }
                    if (idx >= 0) channelIndex = idx
                    playChannel(ch)
                }
            } else if (kind == ContentKind.EPISODE) {
                viewModelScope.launch { content.episode(w.id)?.let { playEpisode(it, w.positionMs) } }
            }
            return
        }
        if (kind != ContentKind.LIVE && kotlin.math.abs(p.currentPosition - w.positionMs) > 2_500) p.seekTo(w.positionMs + 300)
        if (w.playing && !p.isPlaying) p.play()
        if (!w.playing && p.isPlaying) p.pause()
    }
}
