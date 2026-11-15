package de.ostlertekker.ultraiptv.core.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ostlertekker.ultraiptv.core.model.BufferProfile
import de.ostlertekker.ultraiptv.di.USER_AGENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class PlaybackStats(
    val videoMime: String? = null,
    val videoCodecs: String? = null,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val sourceFps: Float = -1f,
    val decodedWidth: Int = 0,
    val decodedHeight: Int = 0,
    val videoDecoder: String? = null,
    val videoBitrate: Int = 0,
    val hdr: String? = null,
    val audioMime: String? = null,
    val audioDecoder: String? = null,
    val audioChannels: Int = 0,
    val audioSampleRate: Int = 0,
    val droppedFrames: Long = 0,
    val bandwidthBps: Long = 0,
    val errors: Int = 0,
    val lastError: String? = null,
    val recoveries: Int = 0,
    val rebuffers: Int = 0,
    val stallMs: Long = 0,
    val measuredFps: Float = -1f,
    val httpCode: Int = 0,
)

/** Inhalt, der im Mini-Player weiterläuft. */
data class MiniInfo(val kind: String, val id: String, val title: String, val category: String?)

data class TrackOption(
    val group: Tracks.Group,
    val index: Int,
    val label: String,
    val selected: Boolean,
)

/**
 * Zentrale Media3-Wiedergabe. Der ExoPlayer wird einmal erzeugt und für alle Inhalte
 * wiederverwendet (schnelles Umschalten, keine unnötige Neuerstellung).
 */
@Singleton
class PlayerEngine @Inject constructor(@ApplicationContext private val context: Context) {

    private var exo: ExoPlayer? = null
    private var bufferProfile: BufferProfile? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var retryJob: Job? = null
    private var retries = 0

    private val _stats = MutableStateFlow(PlaybackStats())
    val stats: StateFlow<PlaybackStats> = _stats.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _tracksVersion = MutableStateFlow(0)
    val tracksVersion: StateFlow<Int> = _tracksVersion.asStateFlow()

    private val _state = MutableStateFlow(Player.STATE_IDLE)
    val state: StateFlow<Int> = _state.asStateFlow()

    var lastSessionStats: PlaybackStats? = null
        private set

    private var currentUrl: String? = null
    /** Wer den Player gerade nutzt: "player", "preview" oder "mini". */
    var owner: String? = null
        private set

    private val _fatal = MutableStateFlow(0)
    /** Wird erhöht, wenn ein Fehler nicht automatisch behoben werden konnte (z. B. für Ausweichsender). */
    val fatal: StateFlow<Int> = _fatal.asStateFlow()

    private val _mini = MutableStateFlow<MiniInfo?>(null)
    val mini: StateFlow<MiniInfo?> = _mini.asStateFlow()

    private var stallStart = 0L
    private var wasReady = false
    private var fpsLastCount = -1
    private var fpsLastTime = 0L

    fun setMini(info: MiniInfo?) {
        _mini.value = info
        if (info != null) owner = "mini"
    }

    /** Läuft bereits genau dieser Stream? (Dann kein Neustart – z. B. Vorschau → Vollbild.) */
    fun isPlayingUrl(url: String): Boolean {
        val p = exo ?: return false
        return url == currentUrl && p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED && _error.value == null
    }

    fun claim(newOwner: String) { owner = newOwner }

    /** Beendet die Wiedergabe nur, wenn [who] sie gerade besitzt. */
    fun stopIfOwner(who: String) {
        if (owner == who) { stop(); owner = null }
    }

    /** Misst die tatsächlich gerenderte Bildrate (aufrufen ca. alle 0,5–1 s). */
    fun sampleFps() {
        val p = exo ?: return
        val counters = p.videoDecoderCounters ?: return
        counters.ensureUpdated()
        val count = counters.renderedOutputBufferCount
        val now = System.nanoTime()
        if (fpsLastCount >= 0 && count >= fpsLastCount && now > fpsLastTime) {
            val fps = (count - fpsLastCount) / ((now - fpsLastTime) / 1e9f)
            if (fps in 0f..300f) _stats.update { it.copy(measuredFps = fps) }
        }
        fpsLastCount = count; fpsLastTime = now
    }

    val player: ExoPlayer? get() = exo

    fun obtain(profile: BufferProfile): ExoPlayer {
        val existing = exo
        if (existing != null && bufferProfile == profile) return existing
        existing?.release()
        val created = build(profile)
        exo = created
        bufferProfile = profile
        return created
    }

    private fun build(profile: BufferProfile): ExoPlayer {
        val (minB, maxB, play, rebuffer) = when (profile) {
            BufferProfile.STANDARD -> listOf(15_000, 50_000, 2_000, 4_000)
            BufferProfile.LARGE -> listOf(30_000, 120_000, 2_500, 6_000)
            BufferProfile.LOW_LATENCY -> listOf(4_000, 20_000, 1_000, 2_000)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minB, maxB, play, rebuffer)
            .build()
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val sourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
        val p = ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(sourceFactory)
            .setTrackSelector(DefaultTrackSelector(context))
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .build()
        p.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
        p.addListener(listener)
        p.addAnalyticsListener(analytics)
        return p
    }

    fun play(url: String, startMs: Long, profile: BufferProfile, who: String = "player") {
        owner = who
        if (isPlayingUrl(url) && bufferProfile == profile) {
            if (startMs > 0 && kotlin.math.abs((exo?.currentPosition ?: 0L) - startMs) > 5_000) exo?.seekTo(startMs)
            exo?.playWhenReady = true
            return
        }
        currentUrl = url
        fpsLastCount = -1; wasReady = false
        val p = obtain(profile)
        retryJob?.cancel()
        retries = 0
        _error.value = null
        _stats.value = PlaybackStats()
        val item = MediaItem.Builder().setUri(url).apply { mimeFor(url)?.let { setMimeType(it) } }.build()
        p.setMediaItem(item, startMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
    }

    fun applyConstraints(c: QualityConstraints, audioLang: String, subtitleLang: String) {
        val p = exo ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(c.maxWidth, c.maxHeight)
            .setMaxVideoBitrate(c.maxBitrate)
            .setForceHighestSupportedBitrate(c.forceHighest)
            .setPreferredAudioLanguage(audioLang.ifBlank { null })
            .setPreferredTextLanguage(subtitleLang.ifBlank { null })
            .build()
    }

    fun stop() {
        lastSessionStats = _stats.value
        currentUrl = null
        owner = null
        retryJob?.cancel()
        exo?.stop()
        exo?.clearMediaItems()
    }

    fun release() {
        retryJob?.cancel()
        exo?.release()
        exo = null
        bufferProfile = null
    }

    // ----------------------------------------------------------- Spuren

    fun options(type: Int): List<TrackOption> {
        val p = exo ?: return emptyList()
        val out = ArrayList<TrackOption>()
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out += TrackOption(g, i, describe(g.getTrackFormat(i), type, out.size + 1), g.isTrackSelected(i))
            }
        }
        return out
    }

    fun select(option: TrackOption) {
        val p = exo ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(option.group.type, false)
            .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.index))
            .build()
    }

    fun disableTrackType(type: Int) {
        val p = exo ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type)
            .setTrackTypeDisabled(type, true)
            .build()
    }

    fun isTypeDisabled(type: Int): Boolean = exo?.trackSelectionParameters?.disabledTrackTypes?.contains(type) == true

    private fun describe(f: Format, type: Int, n: Int): String {
        val lang = f.language?.let { runCatching { Locale(it).displayLanguage }.getOrNull()?.takeIf { d -> d.isNotBlank() } ?: it }
        val name = f.label ?: lang ?: "Spur $n"
        return when (type) {
            C.TRACK_TYPE_AUDIO -> buildString {
                append(name)
                codecName(f.sampleMimeType)?.let { append(" · ").append(it) }
                if (f.channelCount > 0) append(" · ").append(channelLabel(f.channelCount))
            }
            C.TRACK_TYPE_VIDEO -> "${f.width}×${f.height}" + (if (f.bitrate > 0) " · ${f.bitrate / 1000} kbit/s" else "")
            else -> name + (f.sampleMimeType?.substringAfter('/')?.let { " · $it" } ?: "")
        }
    }

    private fun channelLabel(c: Int) = when (c) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$c Kanäle" }

    private fun codecName(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos (DD+ JOC)"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_OPUS -> "Opus"
        null -> null
        else -> mime.substringAfter('/')
    }

    private fun mimeFor(url: String): String? {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") || path.contains("/hls/") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    // ------------------------------------------------------- Listener

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = playbackState
            val now = System.currentTimeMillis()
            if (playbackState == Player.STATE_BUFFERING && wasReady) {
                stallStart = now
                _stats.update { it.copy(rebuffers = it.rebuffers + 1) }
            }
            if (playbackState == Player.STATE_READY) {
                if (stallStart > 0) { val d = now - stallStart; _stats.update { it.copy(stallMs = it.stallMs + d) }; stallStart = 0 }
                wasReady = true
                retries = 0
                _error.value = null
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            _tracksVersion.update { it + 1 }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = exo ?: return
            val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
            _stats.update { it.copy(errors = it.errors + 1, lastError = error.errorCodeName, httpCode = http) }
            if (http == 401 || http == 403 || http == 404) {
                _error.value = when (http) {
                    404 -> "Dieser Stream existiert beim Anbieter nicht mehr (404)."
                    else -> "Zugriff verweigert ($http): Abo abgelaufen oder Verbindungslimit erreicht – läuft der Account gerade auf einem anderen Gerät?"
                }
                _fatal.update { it + 1 }
                return
            }
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition(); p.prepare(); return
            }
            val recoverable = error.errorCode in 2000..2999 ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
            if (recoverable && retries < 5) {
                retries++
                _error.value = "Verbindungsproblem – neuer Versuch $retries/5 …"
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(1000L shl (retries - 1))
                    _stats.update { it.copy(recoveries = it.recoveries + 1) }
                    p.prepare()
                    p.playWhenReady = true
                }
                return
            }
            _error.value = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Der Server hat den Stream abgelehnt (HTTP-Fehler). Zugang/Abo prüfen."
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Keine Verbindung zum Stream-Server."
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "Dieses Gerät kann das Videoformat nicht dekodieren."
                PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> "Geschützter Inhalt (DRM) – nicht abspielbar."
                else -> "Wiedergabefehler: ${error.errorCodeName}"
            }
            _fatal.update { it + 1 }
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            _stats.update { it.copy(droppedFrames = it.droppedFrames + droppedFrames) }
        }

        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            val hdr = when {
                format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision (Quelle)"
                format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10 / PQ (Quelle)"
                format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG (Quelle)"
                else -> null
            }
            _stats.update {
                it.copy(
                    videoMime = format.sampleMimeType, videoCodecs = format.codecs,
                    sourceWidth = format.width, sourceHeight = format.height,
                    sourceFps = if (format.frameRate == Format.NO_VALUE.toFloat()) -1f else format.frameRate,
                    videoBitrate = if (format.bitrate == Format.NO_VALUE) 0 else format.bitrate, hdr = hdr,
                )
            }
        }

        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            _stats.update { it.copy(videoDecoder = decoderName) }
        }

        override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
            _stats.update { it.copy(decodedWidth = videoSize.width, decodedHeight = videoSize.height) }
        }

        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            _stats.update { it.copy(audioMime = format.sampleMimeType, audioChannels = maxOf(0, format.channelCount), audioSampleRate = maxOf(0, format.sampleRate)) }
        }

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            _stats.update { it.copy(audioDecoder = decoderName) }
        }

        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
            _stats.update { it.copy(bandwidthBps = bitrateEstimate) }
        }
    }
}
