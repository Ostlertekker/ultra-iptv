@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package de.ostlertekker.ultraiptv.feature.player

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

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
import de.ostlertekker.ultraiptv.core.player.MiniInfo
import de.ostlertekker.ultraiptv.core.search.SmartSearch
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.player.PlayerEngine
import de.ostlertekker.ultraiptv.core.player.ZapMonitor
import de.ostlertekker.ultraiptv.core.player.ZapTuner
import de.ostlertekker.ultraiptv.core.player.UltraQualityEngine
import de.ostlertekker.ultraiptv.core.security.StreamResolver
import de.ostlertekker.ultraiptv.feature.smartskip.SegmentType
import de.ostlertekker.ultraiptv.feature.smartskip.SkipAction
import de.ostlertekker.ultraiptv.feature.smartskip.skipPolicyFrom
import de.ostlertekker.ultraiptv.feature.smartskip.SkipSegment
import de.ostlertekker.ultraiptv.feature.smartskip.SmartSkipEngine
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
    /** Senderlogo bzw. Poster für das Info-Panel. */
    val artwork: String? = null,
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
    /** Zeitpunkt des letzten Senderwechsels (für das Info-Banner). */
    val zapAt: Long = 0,
    val qualityNote: String? = null,
    /** Hinweis während automatischer Wiederverbindung. */
    val notice: String? = null,
    /** Zeitpunkt, an dem der Sleep-Timer endet (0 = aus). */
    val sleepAt: Long = 0,
    /** Eingegebene Sendernummer (Zifferntasten). */
    val numberInput: String = "",
    /** Timeshift: wie weit hinter Live (ms). 0 = live. */
    val shiftBehindMs: Long = 0,
    /** Sender hat ein Archiv bzw. ein zurückspulbares Live-Fenster. */
    val canTimeshift: Boolean = false,
    val livePaused: Boolean = false,
    /** Länge des lokalen Zeitversatz-Puffers (ms) – 0 = keiner. */
    val tsBufferedMs: Long = 0,
    /** Ziel einer gerade eingegebenen Spul-Aktion (ms hinter Live), für die Vorschau-Anzeige. */
    val seekPreviewMs: Long? = null,
    /** Einzelbild-Modus aktiv. */
    val frameStep: Boolean = false,
    /** Live-Spielstand zur laufenden Sendung (echte Daten, falls zuordenbar). */
    val score: de.ostlertekker.ultraiptv.core.discover.ScoreEvent? = null,
    val scoreFlash: String? = null,
)

val aspectModes = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH)
val aspectNames = listOf(tr("Original (Einpassen)"), "Strecken", tr("Zoom (Füllen)"), tr("Volle Breite"))

/** Bis zu diesem Abstand gilt die Wiedergabe als „live“ (Puffer-Vorlauf). */
private const val LIVE_TOLERANCE_MS = 9_000L

private const val JF = "jf:"

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    val engine: PlayerEngine,
    private val content: ContentRepository,
    private val user: UserRepository,
    private val settingsRepo: SettingsRepository,
    private val resolver: StreamResolver,
    private val epg: EpgRepository,
    private val device: DeviceCapabilityManager,
    private val extras: ExtrasDao,
    private val discover: de.ostlertekker.ultraiptv.core.discover.DiscoverRepository,
    private val sports: de.ostlertekker.ultraiptv.core.discover.SportsRepository,
    private val jellyfin: de.ostlertekker.ultraiptv.core.jellyfin.JellyfinRepository,
) : ViewModel() {

    private val kind = runCatching { ContentKind.valueOf(savedState.get<String>(PlayerActivity.EXTRA_KIND) ?: "") }.getOrDefault(ContentKind.MOVIE)
    private var itemId: String = savedState.get<String>(PlayerActivity.EXTRA_ID) ?: ""
    private val startMs: Long = savedState.get<Long>(PlayerActivity.EXTRA_START) ?: 0L
    private val category: String? = savedState.get<String>(PlayerActivity.EXTRA_CATEGORY)
    /** Catch-up direkt aus der TV Time Machine: Start/Ende der vergangenen Sendung. */
    private val catchupStart: Long = savedState.get<Long>(PlayerActivity.EXTRA_CATCHUP_START) ?: 0L
    private val catchupEnd: Long = savedState.get<Long>(PlayerActivity.EXTRA_CATCHUP_END) ?: 0L

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
    // Jellyfin: aktuelle Wiedergabe-Sitzung (für Fortschrittsmeldung an den Server und Rückfall auf Transkodierung)
    private var jfPlayback: de.ostlertekker.ultraiptv.core.jellyfin.JfPlayback? = null
    private var jfId: String? = null
    private val isJellyfin get() = itemId.startsWith(JF)
    private var image: String? = null
    private val skip = SmartSkipEngine()
    private var segments: List<SkipSegment> = emptyList()
    private var ticker: Job? = null
    private var countdownJob: Job? = null
    private var countdownCancelled = false
    private var lastSave = 0L
    private var lastEpg = 0L
    private var lastScoreTitle: String? = null
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
            _ui.update { it.copy(resizeMode = aspectModes.getOrElse(settings.defaultAspect) { aspectModes[0] }) }
            if (settings.sleepTimerMin > 0) setSleep(settings.sleepTimerMin)
            loadAndPlay(startMs)
            started = true
            startTicker()
        }
        viewModelScope.launch {
            engine.error.collect { e -> _ui.update { it.copy(error = e) } }
        }
        viewModelScope.launch {
            engine.notice.collect { n -> _ui.update { it.copy(notice = n) } }
        }
        viewModelScope.launch {
            engine.timeshift.collect { st ->
                if (st != null && shiftStartWall == 0L) _ui.update {
                    it.copy(canTimeshift = true, tsBufferedMs = st.bufferedMs, shiftBehindMs = if (st.behindMs < LIVE_TOLERANCE_MS) 0 else st.behindMs)
                } else if (st == null) _ui.update { it.copy(tsBufferedMs = 0) }
            }
        }
        viewModelScope.launch {
            engine.stats.collect { st -> if (settingsOrNull()?.matchFrameRate == true && st.sourceFps > 0) desiredFps.value = st.sourceFps }
        }
        viewModelScope.launch {
            lastFatal = engine.fatal.value
            engine.fatal.collect { n -> if (n > lastFatal) { lastFatal = n; onFatal() } }
        }
    }

    private fun settingsOrNull(): AppSettings? = if (this::settings.isInitialized) settings else null

    private suspend fun loadAndPlay(start: Long) {
        if (itemId.startsWith(JF)) { loadJellyfin(start); return }
        when (kind) {
            ContentKind.LIVE -> {
                val ch = content.channel(itemId) ?: return fail(tr("Sender nicht gefunden"))
                channels = (category?.let { content.channelsInOnce(it) } ?: emptyList()).ifEmpty { listOf(ch) }
                channelIndex = channels.indexOfFirst { it.id == ch.id }.coerceAtLeast(0)
                playChannel(ch)
                if (catchupStart > 0) startCatchup(catchupStart, catchupEnd.takeIf { it > catchupStart })
            }
            ContentKind.MOVIE -> {
                val m = content.movieOnce(itemId) ?: return fail(tr("Film nicht gefunden"))
                movie = m; image = m.poster
                logName = m.name; logCategory = m.genre ?: m.category
                _ui.update { it.copy(title = m.name, artwork = m.poster, subtitle = listOfNotNull(m.year, m.genre).joinToString(" · ")) }
                play(m.streamUrl, start)
            }
            ContentKind.EPISODE -> {
                val ep = content.episode(itemId) ?: return fail(tr("Episode nicht gefunden"))
                playEpisode(ep, start)
            }
            ContentKind.SERIES -> fail(tr("Bitte eine Episode wählen"))
        }
    }

    // ---------------------------------------------------- Jellyfin (im eigenen Player, ohne externe App)

    private suspend fun loadJellyfin(start: Long) {
        val id = itemId.removePrefix(JF)
        val it = try { jellyfin.item(id) } catch (e: Exception) { return fail(e.message ?: tr("Jellyfin nicht erreichbar")) } ?: return fail(tr("Titel nicht gefunden"))
        val poster = jellyfin.image(it)
        if (it.isEpisode && it.seriesId != null) {
            val all = runCatching { jellyfin.episodes(it.seriesId) }.getOrDefault(listOf(it))
            val list = all.map { e -> jfEpisode(e) }
            val ep = list.firstOrNull { e -> e.id == itemId } ?: jfEpisode(it)
            playJfEpisode(ep, list, start)
        } else {
            movie = MovieEntity(itemId, it.name, "Jellyfin", poster, jellyfin.image(it, "Backdrop"), it.overview, it.genres.firstOrNull(),
                it.year?.toString(), it.rating, it.runtimeMs / 1000, "", 0L, false)
            image = poster
            logName = it.name; logCategory = it.genres.firstOrNull() ?: "Jellyfin"
            _ui.update { u -> u.copy(title = it.name, artwork = poster, subtitle = listOfNotNull(it.year?.toString(), it.genres.firstOrNull()).joinToString(" · ")) }
            playJf(id, start)
        }
    }

    private var jfEpisodes: List<EpisodeEntity> = emptyList()

    private fun jfEpisode(e: de.ostlertekker.ultraiptv.core.jellyfin.JfItem) = EpisodeEntity(
        JF + e.id, JF + (e.seriesId ?: ""), e.parentIndex ?: 0, e.index ?: 0, e.name, e.overview, jellyfin.image(e, width = 480),
        e.runtimeMs / 1000, "")

    private suspend fun playJfEpisode(ep: EpisodeEntity, list: List<EpisodeEntity>, start: Long) {
        logWatch()
        jfEpisodes = list
        episode = ep; itemId = ep.id; image = ep.image
        logName = ep.title; logCategory = "Serie"
        skip.reset(); countdownCancelled = false; countdownJob?.cancel()
        // Intro/Abspann-Marken vom Jellyfin-Server (Plugin „Intro Skipper“)
        segments = runCatching { jellyfin.segments(ep.id.removePrefix(JF)) }.getOrDefault(emptyList()).mapNotNull { s ->
            val type = when (s.type.lowercase()) {
                "intro", "introduction" -> SegmentType.INTRO
                "recap" -> SegmentType.RECAP
                "outro", "credits" -> SegmentType.OUTRO
                "preview" -> SegmentType.CREDITS
                else -> null
            } ?: return@mapNotNull null
            de.ostlertekker.ultraiptv.feature.smartskip.SkipSegment(type, s.startMs, s.endMs,
                // Nie automatisch springen – nur der Knopf „Intro überspringen“ wird angeboten (Sicherheit unter der Auto-Schwelle)
                de.ostlertekker.ultraiptv.feature.smartskip.MarkerSource.PROVIDER, 0.5f)
        }
        val idx = list.indexOfFirst { it.id == ep.id }
        nextEpisode = if (idx >= 0) list.getOrNull(idx + 1) else null
        val seriesName = runCatching { jellyfin.item(ep.seriesId.removePrefix(JF))?.name }.getOrNull()
        _ui.update {
            it.copy(title = seriesName ?: ep.title, artwork = image, subtitle = "S${ep.season} E${ep.episode} · ${ep.title}", hasNext = nextEpisode != null,
                countdown = null, skipOffer = null, ended = false, segments = segments)
        }
        playJf(ep.id.removePrefix(JF), start)
    }

    private suspend fun playJf(id: String, start: Long, forceTranscode: Boolean = false) {
        // Alten Titel beim Server abmelden (Folgenwechsel)
        val prev = jfPlayback; val prevId = jfId
        if (prev != null && prevId != null && prevId != id) jellyfin.reportStopped(prevId, prev, engine.player?.currentPosition ?: 0L)
        val pb = try { jellyfin.playback(id, 0, forceTranscode) } catch (e: Exception) {
            android.util.Log.w("UltraJellyfin", "Jellyfin-Wiedergabe fehlgeschlagen: ${e.message}")
            return fail(e.message ?: tr("Wiedergabe nicht möglich"))
        }
        android.util.Log.i("UltraJellyfin", "Jellyfin-Wiedergabe: ${pb.method}, Untertitel ${pb.subtitles.size}")
        jfPlayback = pb; jfId = id
        currentStreamUrl = pb.url
        engine.play(pb.url, start, settings.bufferProfile, isLive = false,
            subtitles = pb.subtitles.map { de.ostlertekker.ultraiptv.core.player.ExternalSubtitle(it.url, it.language, it.label, it.format) })
        viewModelScope.launch { applyQuality() }
        if (pb.method == "Transcode") _ui.update { it.copy(qualityNote = tr("Jellyfin transkodiert für dieses Gerät")) }
        jellyfin.reportStart(id, pb, start)
    }

    private fun reportJfProgress() {
        val pb = jfPlayback ?: return; val id = jfId ?: return
        val p = engine.player ?: return
        val pos = p.currentPosition; val paused = !p.isPlaying
        viewModelScope.launch { jellyfin.reportProgress(id, pb, pos, paused) }
    }

    private fun stopJf() {
        val pb = jfPlayback ?: return; val id = jfId ?: return
        val pos = engine.player?.currentPosition ?: 0L
        jfPlayback = null; jfId = null
        // Eigener Scope: die Abmeldung beim Server soll auch nach dem Schließen des Players noch rausgehen
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { jellyfin.reportStopped(id, pb, pos) }
    }

    private fun fail(msg: String) { _ui.update { it.copy(error = msg, buffering = false) } }

    private fun play(raw: String, start: Long, live: Boolean = kind == ContentKind.LIVE) {
        currentStreamUrl = raw
        val url = try { resolver.resolve(raw, settings.liveFormat) } catch (e: Exception) { return fail(e.message ?: tr("Stream nicht verfügbar")) }
        engine.play(url, start, settings.bufferProfile, isLive = live)
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
        // Jellyfin: Untertitel-Modus aus Einstellungen › Jellyfin
        val textMode = if (isJellyfin) settingsRepo.current().jfSubtitles else 0
        val subLang = profile?.subtitleLanguage?.ifBlank { null } ?: if (textMode == 2) java.util.Locale.getDefault().language else ""
        engine.applyConstraints(c, audio, subLang, textMode)
    }

    /** Senderinfo sofort zeigen (auch während schnellen Weiterzappens, bevor der Stream startet). */
    private fun showChannelInfo(ch: ChannelEntity) {
        _ui.update { it.copy(zapAt = System.currentTimeMillis(), qualityNote = null) }
        _ui.update { it.copy(title = ch.name, artwork = ch.logo, subtitle = (if (ch.number > 0) "${ch.number} · " else "") + ch.category, nowTitle = null, nextTitle = null, programProgress = null) }
    }

    private fun playChannel(ch: ChannelEntity, measured: Boolean = false) {
        if (!measured) ZapMonitor.begin(ch.name, settings.zapTurbo, ch.id in prepared)
        ZapTuner.countZap(ch.id)
        saveChannelQuality()
        logWatch()
        watchStart = System.currentTimeMillis()
        liveChannel = ch; shiftStartWall = 0L; livePausedAt = 0L
        logName = ch.name; logCategory = ch.category
        _ui.update { it.copy(shiftBehindMs = 0, livePaused = false, canTimeshift = ch.archiveDays > 0, score = null, scoreFlash = null) }
        itemId = ch.id; image = ch.logo
        showChannelInfo(ch)
        play(ch.streamUrl, 0)
        if (settings.zapTurbo) prepareLikelyNext()
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
        if (cur?.title != lastScoreTitle) { lastScoreTitle = cur?.title; watchScores(cur?.title?.let { t -> t + " " + (cur.description ?: "") }) }
    }

    private suspend fun playEpisode(ep: EpisodeEntity, start: Long) {
        logWatch()
        episode = ep; itemId = ep.id; image = ep.image
        logName = ep.title; logCategory = "Serie"
        skip.reset(); countdownCancelled = false; countdownJob?.cancel()
        val all = content.episodesOfOnce(ep.seriesId)
        val idx = all.indexOfFirst { it.id == ep.id }
        nextEpisode = if (idx >= 0) all.getOrNull(idx + 1) else null
        val series = content.seriesOnce(ep.seriesId)
        if (image == null) image = series?.poster
        segments = emptyList() // IPTV: kein Intro-Überspringen (nur Jellyfin liefert Zeitmarken)
        _ui.update {
            it.copy(title = series?.name ?: ep.title, artwork = image, subtitle = "S${ep.season} E${ep.episode} · ${ep.title}", hasNext = nextEpisode != null,
                countdown = null, skipOffer = null, ended = false, segments = segments)
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
        if (kind == ContentKind.LIVE && System.currentTimeMillis() - lastQualitySave > 30_000) saveChannelQuality()
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val state = p.playbackState
        _ui.update {
            it.copy(positionMs = pos, durationMs = dur, bufferedMs = p.totalBufferedDuration, isPlaying = p.isPlaying,
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE && it.error == null)
        }
        // Archiv-Wiedergabe: Abstand zu Live anzeigen; am Archiv-Ende automatisch zurück zu Live
        if (kind == ContentKind.LIVE && shiftStartWall > 0) { updateShift(); if (state == Player.STATE_ENDED) goLive() }
        val now = System.currentTimeMillis()
        when (kind) {
            ContentKind.LIVE -> if (now - lastEpg > 30_000) channels.getOrNull(channelIndex)?.let { refreshEpg(it) }
            ContentKind.EPISODE -> handleSkip(pos, dur, state)
            ContentKind.MOVIE -> if (state == Player.STATE_ENDED) { saveProgress(); _ui.update { it.copy(ended = true) } }
            else -> Unit
        }
        if (kind != ContentKind.LIVE && now - lastSave > 10_000) saveProgress()
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
            if (next.id.startsWith(JF)) { playJfEpisode(next, jfEpisodes, 0); return@launch }
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
    }

    // ---------------------------------------------------- Steuerung

    fun togglePlay() {
        val p = engine.player ?: return
        if (kind == ContentKind.LIVE && engine.timeshiftActive) {
            // Lokaler Puffer: Pause hält nur das Bild an, der Sender wird im Hintergrund weiter aufgezeichnet
            if (p.isPlaying) engine.pauseLive() else engine.resumeLive()
            _ui.update { it.copy(livePaused = !p.playWhenReady) }
            return
        }
        if (kind == ContentKind.LIVE && _ui.value.canTimeshift) {
            // Live pausieren: beim Fortsetzen geht es ab der Pausenstelle weiter (Archiv des Anbieters)
            if (p.isPlaying) { livePausedAt = System.currentTimeMillis(); engine.pauseLive(); _ui.update { it.copy(livePaused = true) }; return }
            val pausedAt = livePausedAt
            livePausedAt = 0L; _ui.update { it.copy(livePaused = false) }
            if (pausedAt > 0 && System.currentTimeMillis() - pausedAt > 8_000) {
                val wall = if (shiftStartWall > 0) shiftStartWall + p.currentPosition else pausedAt
                engine.resumeLive(); startCatchup(wall, null); return
            }
            engine.resumeLive(); return
        }
        if (kind == ContentKind.LIVE) { if (p.isPlaying) { engine.pauseLive(); _ui.update { it.copy(livePaused = true) } } else { engine.resumeLive(); _ui.update { it.copy(livePaused = false) } }; return }
        if (p.isPlaying) p.pause() else p.play()
    }

    // ---------------------------------------------------- TV Time Machine / Timeshift

    private var liveChannel: ChannelEntity? = null
    /** Wanduhrzeit, bei der die laufende Archiv-Wiedergabe beginnt (0 = live). */
    private var shiftStartWall = 0L
    private var livePausedAt = 0L

    /** Aktuelle „Wanduhr“-Position der Wiedergabe. */
    private fun currentWall(): Long {
        val p = engine.player
        return if (shiftStartWall > 0 && p != null) shiftStartWall + p.currentPosition else System.currentTimeMillis()
    }

    /** Zurückspulen im Live-TV (−30 s, −5 min …): erst im Live-Fenster des Streams, sonst über das Archiv. */
    // Spul-Eingaben sammeln: mehrere Tastendrücke → ein einziger Sprung (kein ständiges Neustarten)
    private var pendingShift = 0L
    private var shiftJob: Job? = null

    fun goLive() {
        if (engine.timeshiftActive && shiftStartWall == 0L) {
            shiftJob?.cancel(); pendingShift = 0
            engine.timeshiftToLive(); engine.player?.play()
            _ui.update { it.copy(seekPreviewMs = null, livePaused = false, shiftBehindMs = 0) }
            return
        }
        val ch = liveChannel ?: return
        shiftStartWall = 0L
        playChannel(ch)
    }

    private fun updateShift() { _ui.update { it.copy(shiftBehindMs = (System.currentTimeMillis() - currentWall()).coerceAtLeast(0)) } }

    /** Startet die Archiv-Wiedergabe des aktuellen Senders ab [startWall]. */
    fun startCatchup(startWall: Long, endWall: Long?) {
        val ch = liveChannel ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val end = endWall ?: now
            val raw = discover.catchupUrl(ch.id, startWall, end, now)
            if (raw == null) { toast(trf("Archiv für diese Zeit nicht verfügbar (Anbieter: {0} Tage)", ch.archiveDays)); return@launch }
            shiftStartWall = startWall
            play(raw, 0, live = false)
            _ui.update { it.copy(shiftBehindMs = now - startWall, livePaused = false) }
            toast(trf("⏪ Zeitversetzt ab {0}", (de.ostlertekker.ultraiptv.feature.livetv.formatTime(startWall))))
        }
    }

    // ---------------------------------------------------- Einzelbild-Wiedergabe

    fun toggleFrameStep() {
        val on = !_ui.value.frameStep
        engine.player?.let { p ->
            if (on) { p.pause(); p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT) }
            else p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT)
        }
        _ui.update { it.copy(frameStep = on) }
        if (on) toast("Einzelbild: ◀ ▶ = 1 Bild · ▲ ▼ = 10 Bilder · OK = Ende")
    }

    /** Ein Bild vor/zurück (exakte Suche auf Bildlänge laut Stream-FPS). */
    fun stepFrames(n: Int) {
        val p = engine.player ?: return
        if (!p.isCurrentMediaItemSeekable) { toast(tr("Einzelbild nur bei Filmen, Serien und Archiv möglich")); return }
        val st = engine.stats.value
        val fps = if (st.sourceFps > 1f) st.sourceFps else if (st.measuredFps > 1f) st.measuredFps else 25f
        p.pause()
        p.seekTo((p.currentPosition + (n * 1000f / fps).toLong()).coerceAtLeast(0))
    }

    // ---------------------------------------------------- Sehprotokoll (Mein TV-Universum)

    private var logName: String = ""
    private var logCategory: String? = null
    private fun logWatch() {
        val started = watchStart
        val dur = System.currentTimeMillis() - started
        val name = logName.ifBlank { _ui.value.title }
        val id = itemId
        if (id.isBlank() || name.isBlank()) return
        val cat = logCategory
        watchStart = System.currentTimeMillis()
        viewModelScope.launch { runCatching { discover.logWatch(kind, id, name, cat, started, dur) } }
    }

    // ---------------------------------------------------- Live-Spielstände (Universal Sports Mode / Auto Event Detection)

    private var scoreJob: Job? = null
    private fun watchScores(programTitle: String?) {
        scoreJob?.cancel()
        if (programTitle == null || de.ostlertekker.ultraiptv.core.discover.Sport.detect(programTitle) == null) return
        scoreJob = viewModelScope.launch {
            var lastLine: String? = null
            while (true) {
                val leagues = de.ostlertekker.ultraiptv.core.discover.League.entries
                val board = runCatching { sports.scoreboard(leagues) }.getOrNull()
                val ev = board?.let { sports.matchProgram(programTitle, it.events) }
                if (ev != null) {
                    val line = ev.scoreLine
                    val flash = if (lastLine != null && line != lastLine && settings.scoreAlerts) "${ev.league.sport.emoji} $line" else null
                    lastLine = line
                    _ui.update { it.copy(score = ev, scoreFlash = flash ?: it.scoreFlash) }
                    if (flash != null) { delay(8_000); _ui.update { it.copy(scoreFlash = null) } }
                }
                delay(if (ev?.live == true) 30_000 else 120_000)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val p = engine.player ?: return
        if (!p.isCurrentMediaItemSeekable) return
        p.seekTo(if (p.duration > 0) positionMs.coerceIn(0, p.duration - 1000) else positionMs.coerceAtLeast(0))
    }

    fun seekBy(deltaMs: Long) {
        val p = engine.player ?: return
        if (kind == ContentKind.LIVE && !p.isCurrentMediaItemSeekable) return
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        p.seekTo(if (p.duration > 0) target.coerceAtMost(p.duration - 1000) else target)
    }

    // ------------------------------------------------------------------ Senderwechsel (Zapping)

    private var lastDelta = 1
    private var prepJob: kotlinx.coroutines.Job? = null
    /** Sender, die gerade vorbereitet sind (für die Auswertung „vorhergesagt“). */
    private val prepared = HashSet<String>()

    fun switchChannel(delta: Int) {
        if (kind != ContentKind.LIVE || channels.size < 2) return
        previousIndex = channelIndex
        channelIndex = (channelIndex + delta).mod(channels.size)
        lastDelta = if (delta >= 0) 1 else -1
        val ch = channels[channelIndex]
        ZapMonitor.begin(ch.name, settings.zapTurbo, ch.id in prepared) // Messung ab Tastendruck
        // Jeder Tastendruck startet sofort. (Gemessen: Zusammenfassen schneller Tastendrücke machte den Zielsender langsamer.)
        playChannel(ch, measured = true)
    }

    /**
     * Wahrscheinlich nächste Sender vorbereiten: Nachbar in Zapp-Richtung, Gegenrichtung, zuletzt gesehener Sender und die
     * am häufigsten gewählten. Vorbereitet werden nur leichte Dinge – Stream-Adresse, Namensauflösung (DNS) und Programmdaten.
     * Bewusst KEIN zweiter Stream im Hintergrund: viele Anbieter erlauben nur eine Verbindung gleichzeitig.
     */
    private fun prepareLikelyNext() {
        prepJob?.cancel()
        prepJob = viewModelScope.launch {
            delay(1_500) // erst wenn der aktuelle Sender läuft – der Start hat Vorrang
            if (channels.size < 2) return@launch
            val ids = LinkedHashSet<String>()
            channels.getOrNull((channelIndex + lastDelta).mod(channels.size))?.let { ids += it.id }
            channels.getOrNull((channelIndex - lastDelta).mod(channels.size))?.let { ids += it.id }
            channels.getOrNull(previousIndex)?.let { ids += it.id }
            ZapTuner.topZapped(3).forEach { id -> if (channels.any { it.id == id }) ids += id }
            ids.remove(channels.getOrNull(channelIndex)?.id)
            val list = ids.mapNotNull { id -> channels.firstOrNull { it.id == id } }
            prepared.clear(); prepared.addAll(list.map { it.id })
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val hosts = HashSet<String>()
                for (c in list) {
                    runCatching { resolver.resolve(c.streamUrl, settings.liveFormat) }.getOrNull()
                        ?.let { android.net.Uri.parse(it).host }?.let { hosts += it }
                    runCatching { epg.nowNext(c.epgId) }
                }
                hosts.forEach { h -> runCatching { java.net.InetAddress.getAllByName(h) } }
            }
        }
    }

    fun channelList(): List<ChannelEntity> = channels

    /** Einstellung direkt aus dem Player ändern (Kinomodus, Bildregler, Sync). */
    fun <T> setPref(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        viewModelScope.launch { settingsRepo.set(key, value) }
    }

    // ------------------------------------------------ Sleep-Timer
    private var sleepJob: Job? = null
    fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) { _ui.update { it.copy(sleepAt = 0) }; return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _ui.update { it.copy(sleepAt = at) }
        sleepJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            engine.player?.pause()
            saveProgress()
            toast(tr("Sleep-Timer: Wiedergabe beendet"))
            delay(1500)
            _ui.update { it.copy(ended = true, sleepAt = 0) }
        }
    }
    fun cycleSleep() {
        val opts = listOf(0, 15, 30, 45, 60, 90, 120)
        val cur = _ui.value.sleepAt
        val left = if (cur > 0) ((cur - System.currentTimeMillis()) / 60_000L).toInt() else 0
        val next = opts.firstOrNull { it > left + 1 } ?: 0
        setSleep(next)
        toast(if (next == 0) tr("Sleep-Timer aus") else trf("Sleep-Timer: {0} min", next))
    }

    // ------------------------------------------------ Zifferntasten-Direktwahl
    private var numberJob: Job? = null
    fun typeDigit(d: Int) {
        if (kind != ContentKind.LIVE) return
        val txt = (_ui.value.numberInput + d).takeLast(4)
        _ui.update { it.copy(numberInput = txt) }
        numberJob?.cancel()
        numberJob = viewModelScope.launch {
            delay(1600)
            val n = txt.toIntOrNull() ?: 0
            _ui.update { it.copy(numberInput = "") }
            val idx = channels.indexOfFirst { it.number == n }
            if (idx >= 0) playChannelAt(idx)
            else {
                val ch = runCatching { content.channelByNumber(n) }.getOrNull()
                if (ch != null) { channels = channels + ch; playChannelAt(channels.lastIndex) } else toast(trf("Sender {0} nicht gefunden", n))
            }
        }
    }

    /** Zuletzt gesehener Sender (Zurück-Taste im Live-TV / „Letzter Sender“). */
    private var previousIndex = -1
    fun lastChannel() { if (previousIndex in channels.indices && previousIndex != channelIndex) playChannelAt(previousIndex) }
    val isLive: Boolean get() = kind == ContentKind.LIVE
    /** Wechselt im laufenden Live-Player zu einem bestimmten Sender (z. B. aus einer Erinnerung). */
    fun playChannelById(id: String) {
        val i = channels.indexOfFirst { it.id == id }
        if (i >= 0) { playChannelAt(i); return }
        viewModelScope.launch { content.channel(id)?.let { playChannel(it) } }
    }
    fun playChannelAt(index: Int) { if (index in channels.indices) { previousIndex = channelIndex; channelIndex = index; lastDelta = 1; playChannel(channels[index]) } }

    fun setResize(mode: Int) = _ui.update { it.copy(resizeMode = mode) }
    fun setSpeed(speed: Float) { engine.player?.setPlaybackSpeed(speed.coerceIn(0.25f, 4f)); _ui.update { it.copy(speed = speed.coerceIn(0.25f, 4f)) } }
    fun toggleStats() = _ui.update { it.copy(showStats = !it.showStats) }
    fun retry() { _ui.update { it.copy(error = null) }; play(currentStreamUrl ?: return, engine.player?.currentPosition ?: 0) }
    private var currentStreamUrl: String? = null

    private fun toast(msg: String) {
        _ui.update { it.copy(toast = msg) }
        viewModelScope.launch { delay(2500); _ui.update { if (it.toast == msg) it.copy(toast = null) else it } }
    }

    private suspend fun saveProgress() {
        lastSave = System.currentTimeMillis()
        val p = engine.player ?: return
        // Manche Anbieter liefern Folgen als Stream ohne Längenangabe → Länge aus den Metadaten (sonst 0 = unbekannt),
        // damit die Folge trotzdem unter „Weiterschauen“ landet
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: (episode?.durationSec ?: movie?.durationSec)?.takeIf { it > 0 }?.times(1000) ?: 0L
        val pos = p.currentPosition
        if (pos <= 0) return
        // Jellyfin: Fortschritt an den Server melden UND lokal merken (für „Weiterschauen“ auf der Startseite)
        if (isJellyfin) reportJfProgress()
        when (kind) {
            ContentKind.MOVIE -> movie?.let { user.saveProgress(ContentKind.MOVIE, it.id, pos, dur, it.name, image) }
            ContentKind.EPISODE -> episode?.let { user.saveProgress(ContentKind.EPISODE, it.id, pos, dur, _ui.value.title + " · S${it.season}E${it.episode}", image, it.seriesId) }
            else -> Unit
        }
    }

    fun onStopped(keepPlaying: Boolean) {
        viewModelScope.launch { saveProgress() }
        // Übergabe an den Mini-Player: Stream läuft weiter
        if (keepPlaying || keepEngine) return
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
        stopJf()
        if (!keepEngine) engine.stop()
    }

    override fun onCleared() {
        logWatch()
        stopJf()
        if (!keepEngine) engine.stop()
        super.onCleared()
    }

    // ---------------------------------------------------- Mini-Player

    /**
     * Übergabe an einen neuen Player (z. B. Erinnerung „Zum Sender“ aus Film/Jellyfin): jetzt sauber stoppen und
     * beim späteren Aufräumen dieses Players den gemeinsamen Player NICHT mehr anhalten – sonst bleibt der neue Sender hängen.
     */
    fun handOver() {
        stopJf()
        engine.stop()
        keepEngine = true
    }

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
            _ui.update { it.copy(qualityNote = trf("Qualität automatisch auf max. {0}p gesenkt ({1})", next, reason)) }
            toast(trf("Qualität angepasst: max. {0}p", next))
        }
    }

    // ---------------------------------------------------- Automatische Fehlererkennung / Ausweichsender

    private fun coreName(name: String): String = SmartSearch.normalize(
        name.replace(Regex("^\\s*[\\[|(]?[A-Za-z]{2,3}[\\]|)]?\\s*[:|\\-]\\s*"), "")
            .replace(Regex("(?i)\\b(uhd|fhd|hd|sd|4k|hevc|h265|backup|raw|vip|multi|50fps|60fps)\\b"), "")
            .replace(Regex("[\\[(].*?[\\])]"), ""),
    )

    private fun onFatal() {
        // Jellyfin: Direct Play klappt auf diesem Gerät nicht → automatisch auf Transkodierung (HLS) wechseln
        val pb = jfPlayback; val id = jfId
        if (pb != null && id != null && pb.method != "Transcode") {
            val pos = engine.player?.currentPosition ?: 0L
            toast(tr("Format nicht direkt abspielbar – Jellyfin transkodiert"))
            viewModelScope.launch { playJf(id, pos, forceTranscode = true) }
            return
        }
        if (kind != ContentKind.LIVE || !settings.autoFailover) return
        val cur = channels.getOrNull(channelIndex) ?: return
        triedChannels += cur.id
        viewModelScope.launch {
            val core = coreName(cur.name)
            if (core.length < 2) return@launch
            val alt = content.channelsNamed(core.split(' ').first())
                .firstOrNull { it.id !in triedChannels && coreName(it.name) == core }
            if (alt != null) {
                toast(trf("Sender gestört – weiche auf „{0}“ aus", alt.name))
                val idx = channels.indexOfFirst { it.id == alt.id }
                if (idx >= 0) channelIndex = idx else { channels = channels + alt; channelIndex = channels.lastIndex }
                playChannel(alt)
            }
        }
    }
}
