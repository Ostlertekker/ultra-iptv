@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package de.ostlertekker.ultraiptv.core.player

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

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
import kotlinx.coroutines.isActive
import android.os.SystemClock
import de.ostlertekker.ultraiptv.core.data.AppSettings
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
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
class PlayerEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
) {

    private var exo: ExoPlayer? = null
    private var bufferProfile: BufferProfile? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var retryJob: Job? = null
    private var retries = 0
    /** Live vom Nutzer pausiert: keine automatische Wiederverbindung/Wiedergabe, bis er selbst fortsetzt. */
    @Volatile var userPaused = false
        private set
    private var pausedConnectionLost = false
    /** Wird vor jeder Video-Wiedergabe aufgerufen (Radio beendet sich darüber). */
    var beforePlay: (() -> Unit)? = null

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

    // ------------------------------------------------ Live-Wächter (Stream bleibt stehen → automatisch neu verbinden)
    @Volatile private var cfg = AppSettings()
    private var live = false
    private var watchdogJob: Job? = null
    private var lastPos = Long.MIN_VALUE
    private var lastProgressAt = 0L
    private var graceUntil = 0L
    private var consecutiveReconnects = 0
    private var loudness: android.media.audiofx.LoudnessEnhancer? = null
    private var focusApplied: Boolean? = null

    private val _notice = MutableStateFlow<String?>(null)
    /** Kurzer Hinweis während einer automatischen Wiederverbindung (kein Fehlerdialog). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _playing = MutableStateFlow(false)
    /** true, solange Video läuft – hält den Bildschirm wach (auch für Vorschau/Mini-Player). */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /** Start-Puffer für den laufenden Live-Start (µs, 0 = Standardwerte); gesetzt vom Schnellstart. */
    @Volatile private var liveStartUs = 0L
    private var readyAt = 0L
    private var earlyStallSeen = false

    init {
        ZapMonitor.init(context); ZapTuner.init(context)
        de.ostlertekker.ultraiptv.core.sysupdate.RemotePlayerConfig.load(context)
        scope.launch {
            settingsRepo.settings.collect { s ->
                cfg = s
                applyAudioFocus(); applyBoost(); applyCinemaSound()
            }
        }
    }

    private fun applyAudioFocus() {
        val p = exo ?: return
        val handle = !cfg.ignoreAudioFocus
        if (focusApplied == handle) return
        focusApplied = handle
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), handle)
    }

    /** Lautstärke-Boost über LoudnessEnhancer (echte Verstärkung, in mB). */
    private fun applyBoost() {
        val p = exo ?: return
        val db = cfg.volumeBoostDb
        try {
            if (db <= 0) { loudness?.enabled = false; return }
            val session = p.audioSessionId
            if (session == C.AUDIO_SESSION_ID_UNSET) return
            val le = loudness ?: android.media.audiofx.LoudnessEnhancer(session).also { loudness = it }
            le.setTargetGain(db * 100)
            le.enabled = true
        } catch (_: Exception) { loudness = null }
    }

    // ------------------------------------------------ Kinoklang (Cinema Mode): Nachtmodus, Dialog, Bass – echte DSP
    private var dynamics: android.media.audiofx.DynamicsProcessing? = null
    private var dynamicsMode = -1
    private var dynamicsSession = -1

    private fun applyCinemaSound() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
        val p = exo ?: return
        val mode = cfg.cinemaSound
        val session = p.audioSessionId
        if (mode == dynamicsMode && session == dynamicsSession) return
        runCatching { dynamics?.release() }; dynamics = null
        dynamicsMode = mode; dynamicsSession = session
        if (mode <= 0 || session == C.AUDIO_SESSION_ID_UNSET) return
        try {
            val config = android.media.audiofx.DynamicsProcessing.Config.Builder(
                android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, false, 0, true, 1, true, 3, true,
            ).build()
            val dp = android.media.audiofx.DynamicsProcessing(0, session, config)
            for (ch in 0 until 2) {
                val mbc = dp.getMbcBandByChannelIndex(ch, 0)
                mbc.isEnabled = mode == 1
                if (mode == 1) { mbc.threshold = -32f; mbc.ratio = 4f; mbc.attackTime = 5f; mbc.releaseTime = 120f; mbc.postGain = 7f }
                dp.setMbcBandByChannelIndex(ch, 0, mbc)
                // Post-EQ: <250 Hz, 250 Hz–4 kHz, >4 kHz
                val gains = when (mode) { 2 -> floatArrayOf(-4f, 5f, 2f); 3 -> floatArrayOf(7f, 0f, 1f); else -> floatArrayOf(-2f, 2f, 0f) }
                val cuts = floatArrayOf(250f, 4000f, 20000f)
                for (b in 0 until 3) {
                    val eq = dp.getPostEqBandByChannelIndex(ch, b)
                    eq.isEnabled = true; eq.cutoffFrequency = cuts[b]; eq.gain = gains[b]
                    dp.setPostEqBandByChannelIndex(ch, b, eq)
                }
                val lim = dp.getLimiterByChannelIndex(ch)
                lim.isEnabled = true; lim.threshold = -2f
                dp.setLimiterByChannelIndex(ch, lim)
            }
            dp.enabled = true
            dynamics = dp
        } catch (_: Throwable) { dynamics = null }
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(1000)
                checkLive()
            }
        }
    }

    private fun checkLive() {
        val p = exo ?: return
        if (userPaused) { lastProgressAt = SystemClock.elapsedRealtime(); return }
        updateTimeshiftState()
        val now = SystemClock.elapsedRealtime()
        if (!live || !cfg.liveReconnect || currentUrl == null || !p.playWhenReady ||
            p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) { lastProgressAt = now; return }
        val pos = p.currentPosition
        val state = p.playbackState
        if (state == Player.STATE_READY && p.isPlaying && pos != lastPos) {
            lastProgressAt = now
            if (now - graceUntil > 30_000) consecutiveReconnects = 0
        }
        lastPos = pos
        if (now < graceUntil) return
        // Server hat die Verbindung beendet (MPEG-TS) → sofort weiter
        if (state == Player.STATE_ENDED) { reconnect(tr("Stream wurde vom Server beendet")); return }
        if (state == Player.STATE_IDLE && _error.value == null) { reconnect(tr("Stream angehalten")); return }
        if (state == Player.STATE_IDLE) return // Fehlerbehandlung läuft
        val limit = cfg.stallTimeoutSec.coerceIn(3, 60) * 1000L * (1 + consecutiveReconnects.coerceAtMost(3))
        if (now - lastProgressAt > limit) reconnect(tr("Bild steht"))
    }

    /** Live pausieren (Nutzer). Der Wächter hält still, Verbindungsabbrüche lösen keine Wiedergabe aus. */
    fun pauseLive() { userPaused = true; exo?.pause() }

    /** Live fortsetzen: Ist die Verbindung in der Pause abgerissen, wird sauber neu verbunden. */
    fun resumeLive() {
        val p = exo ?: return
        userPaused = false
        val lost = pausedConnectionLost || p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED
        pausedConnectionLost = false
        retries = 0
        lastProgressAt = SystemClock.elapsedRealtime(); graceUntil = SystemClock.elapsedRealtime() + 8_000
        if (lost && tsBuffer == null) { currentUrl?.let { u -> p.setMediaItem(mediaItem(u)); p.prepare() } }
        p.play()
    }

    private fun reconnect(reason: String) {
        ZapMonitor.retry()
        val p = exo ?: return
        val url = currentUrl ?: return
        if (tsBuffer != null) {
            // Zeitversatz aktiv: die Anbieter-Verbindung neu aufbauen, der Player liest weiter aus dem Puffer
            consecutiveReconnects++
            _notice.value = trf("{0} – verbinde neu …", reason)
            _stats.update { it.copy(recoveries = it.recoveries + 1, lastError = reason) }
            graceUntil = SystemClock.elapsedRealtime() + 8_000
            lastProgressAt = SystemClock.elapsedRealtime()
            tsBuffer?.kick()
            if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) timeshiftTo(timeshiftWall())
            return
        }
        consecutiveReconnects++
        retryJob?.cancel()
        _stats.update { it.copy(recoveries = it.recoveries + 1, lastError = reason) }
        _notice.value = trf("{0} – verbinde neu …", reason)
        val now = SystemClock.elapsedRealtime()
        graceUntil = now + 6_000 + 2_000L * consecutiveReconnects.coerceAtMost(5)
        lastProgressAt = now
        lastPos = Long.MIN_VALUE
        p.setMediaItem(mediaItem(url))
        p.prepare()
        p.playWhenReady = true
    }

    // ------------------------------------------------ Lokaler Live-Zeitversatz (Pause / Zurückspulen / Vorspulen)
    private val LIVE_MARGIN_MS = 3_000L
    @Volatile private var tsBuffer: TimeshiftBuffer? = null
    /** Wanduhrzeit, die dem Beginn des aktuell gespielten Puffer-Abschnitts entspricht. */
    private var tsStartWall = 0L
    private var tsSession = 0

    data class TimeshiftState(val behindMs: Long, val bufferedMs: Long, val reconnects: Int)
    private val _timeshift = MutableStateFlow<TimeshiftState?>(null)
    /** null = kein lokaler Zeitversatz aktiv. */
    val timeshift: StateFlow<TimeshiftState?> = _timeshift.asStateFlow()

    val timeshiftActive: Boolean get() = tsBuffer != null

    private fun canTimeshift(url: String, who: String, isLive: Boolean) =
        isLive && who == "player" && cfg.timeshiftMin > 0 && mimeFor(url) == null

    private fun startTimeshift(url: String): MediaItem {
        val previousGone = tsBuffer?.disconnected
        stopTimeshift()
        val free = context.cacheDir.usableSpace
        val maxBytes = (free * 0.35).toLong().coerceIn(64L * 1024 * 1024, 4L * 1024 * 1024 * 1024)
        val rc = de.ostlertekker.ultraiptv.core.sysupdate.RemotePlayerConfig.current
        val b = TimeshiftBuffer(java.io.File(context.cacheDir, "timeshift"), url, rc.userAgent.ifBlank { USER_AGENT }, cfg.timeshiftMin * 60_000L, maxBytes,
            connectTimeoutMs = rc.connectTimeoutMs, readTimeoutMs = rc.readTimeoutMs)
        b.onConnected = { ZapMonitor.connected() }
        b.onFirstData = { ZapMonitor.firstData() }
        b.start(after = previousGone)
        tsBuffer = b
        tsStartWall = System.currentTimeMillis()
        return tsItem(0L)
    }

    private fun tsItem(offset: Long) = MediaItem.Builder()
        .setUri("timeshift://live/${++tsSession}?o=$offset")
        .setMimeType(MimeTypes.VIDEO_MP2T)
        .build()

    private fun stopTimeshift() {
        tsBuffer?.close(); tsBuffer = null
        _timeshift.value = null
    }

    /** Wanduhrzeit des gerade gezeigten Bildes. */
    fun timeshiftWall(): Long {
        val p = exo ?: return System.currentTimeMillis()
        return (tsStartWall + p.currentPosition).coerceAtMost(System.currentTimeMillis())
    }

    /** Springt im Puffer zu [wall] (ms). Ältere Zeiten werden auf den ältesten Stand begrenzt. */
    fun timeshiftTo(wall: Long) {
        val b = tsBuffer ?: return
        val p = exo ?: return
        val now = System.currentTimeMillis()
        // Puffer kann jünger als der Sicherheitsabstand sein (direkt nach dem Umschalten) → Bereich nie leer
        val oldest = b.oldestWall()
        val target = wall.coerceIn(oldest, maxOf(oldest, now - LIVE_MARGIN_MS))
        val offset = b.offsetForWall(target)
        tsStartWall = b.wallForOffset(offset)
        val keepPlaying = p.playWhenReady
        p.setMediaItem(tsItem(offset))
        p.prepare()
        p.playWhenReady = keepPlaying || true
        graceUntil = SystemClock.elapsedRealtime() + 6_000
        updateTimeshiftState()
    }

    fun timeshiftBy(deltaMs: Long) {
        if (tsBuffer == null) return
        val target = timeshiftWall() + deltaMs
        if (deltaMs > 0 && target >= System.currentTimeMillis() - LIVE_MARGIN_MS - 500) timeshiftToLive() else timeshiftTo(target)
    }

    fun timeshiftToLive() = timeshiftTo(System.currentTimeMillis() - LIVE_MARGIN_MS)

    private fun updateTimeshiftState() {
        val b = tsBuffer ?: return
        val now = System.currentTimeMillis()
        _timeshift.value = TimeshiftState(
            behindMs = (now - timeshiftWall()).coerceAtLeast(0),
            bufferedMs = (now - b.oldestWall()).coerceAtLeast(0),
            reconnects = b.reconnects,
        )
    }

    private fun mediaItem(url: String, subtitles: List<ExternalSubtitle> = emptyList()) = MediaItem.Builder().setUri(url).apply {
        mimeFor(url)?.let { setMimeType(it) }
        // Externe Untertitel (z. B. aus Jellyfin) als eigene Spuren – wählbar wie eingebettete
        if (subtitles.isNotEmpty()) setSubtitleConfigurations(subtitles.map { st ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(st.url))
                .setMimeType(if (st.format == "vtt") androidx.media3.common.MimeTypes.TEXT_VTT else androidx.media3.common.MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(st.language).setLabel(st.label).build()
        })
    }.build()
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
        val loadControl = VodStartLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(minB, maxB, play, rebuffer)
            .build(), { if (live) 0L else VOD_START_BUFFER_US }, { if (live) liveStartUs else 0L })
        // Größerer Tonpuffer: Decoder-Spitzen beim Anlaufen (z. B. DTS-HD MA in Software) führen nicht zu Knistern
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(ctx: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean):
                androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(ctx)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioTrackBufferSizeProvider(androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(500_000).setMaxPcmBufferDurationUs(1_000_000)
                        .setPassthroughBufferDurationUs(500_000).build())
                    .build()
        }
            .setEnableDecoderFallback(true)
            // Eigener FFmpeg-Tondecoder als Rückfallebene: zuerst Gerätedecoder bzw. Durchreichen an den Receiver,
            // nur wenn das Gerät ein Tonformat (z. B. DTS-HD MA, TrueHD) nicht kann, dekodiert die App selbst
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(de.ostlertekker.ultraiptv.core.sysupdate.RemotePlayerConfig.current.userAgent.ifBlank { USER_AGENT })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(de.ostlertekker.ultraiptv.core.sysupdate.RemotePlayerConfig.current.connectTimeoutMs)
            .setReadTimeoutMs(de.ostlertekker.ultraiptv.core.sysupdate.RemotePlayerConfig.current.readTimeoutMs)
            // Messpunkte Senderwechsel: Server antwortet / erste Daten (HLS, direkte Streams)
            .setTransferListener(object : androidx.media3.datasource.TransferListener {
                override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
                override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) { if (isNetwork) ZapMonitor.connected() }
                override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) { if (isNetwork && bytesTransferred > 0) ZapMonitor.firstData() }
                override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
            })
        val sourceFactory = DefaultMediaSourceFactory(RoutingDataSourceFactory(DefaultDataSource.Factory(context, http)) { tsBuffer })
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
        val p = ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(sourceFactory)
            .setTrackSelector(DefaultTrackSelector(context))
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                !cfg.ignoreAudioFocus,
            )
            // WLAN bleibt während der Wiedergabe wach – sonst reißt der Stream nach einigen Minuten ab
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        focusApplied = !cfg.ignoreAudioFocus
        p.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
        p.addListener(listener)
        p.addAnalyticsListener(analytics)
        return p
    }

    fun play(url: String, startMs: Long, profile: BufferProfile, who: String = "player", isLive: Boolean = false,
             subtitles: List<ExternalSubtitle> = emptyList()) {
        beforePlay?.invoke() // z. B. Radio stoppen
        owner = who
        live = isLive
        userPaused = false; pausedConnectionLost = false
        startWatchdog()
        // Vorschau → Vollbild: denselben Stream weiterverwenden – außer der Zeitversatz-Puffer muss erst starten
        if (isPlayingUrl(url) && bufferProfile == profile && !(canTimeshift(url, who, isLive) && tsBuffer == null)) {
            if (startMs > 0 && kotlin.math.abs((exo?.currentPosition ?: 0L) - startMs) > 5_000) exo?.seekTo(startMs)
            exo?.playWhenReady = true
            return
        }
        currentUrl = url
        fpsLastCount = -1; wasReady = false
        val p = obtain(profile)
        retryJob?.cancel()
        retries = 0
        consecutiveReconnects = 0
        _error.value = null
        _notice.value = null
        _stats.value = PlaybackStats()
        // Schnellstart (Live, Vollbild-Player): Wiedergabe beginnt mit dem für diesen Sender gelernten Vorrat statt pauschal 2 s
        liveStartUs = if (isLive && who == "player" && cfg.zapTurbo) ZapTuner.startMs(url) * 1000 else 0L
        ZapMonitor.startBuffer(if (liveStartUs > 0) liveStartUs / 1000 else profileStartMs(profile))
        readyAt = 0L; earlyStallSeen = false
        val now = SystemClock.elapsedRealtime()
        graceUntil = now + de.ostlertekker.ultraiptv.core.sysupdate.RemotePlayerConfig.current.firstConnectGraceMs // erster Verbindungsaufbau darf länger dauern
        lastProgressAt = now; lastPos = Long.MIN_VALUE
        if (canTimeshift(url, who, isLive)) p.setMediaItem(startTimeshift(url))
        else { stopTimeshift(); p.setMediaItem(mediaItem(url, subtitles), startMs.coerceAtLeast(0)) }
        p.prepare()
        p.playWhenReady = true
        ZapMonitor.request()
    }

    private fun profileStartMs(p: BufferProfile) = when (p) { BufferProfile.STANDARD -> 2_000L; BufferProfile.LARGE -> 2_500L; BufferProfile.LOW_LATENCY -> 1_000L }

    /** textMode: 0 = normal, 1 = Untertitel aus, 2 = immer einblenden (auch ohne Sprach-Treffer). */
    fun applyConstraints(c: QualityConstraints, audioLang: String, subtitleLang: String, textMode: Int = 0) {
        val p = exo ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, textMode == 1)
            .setSelectUndeterminedTextLanguage(textMode == 2)
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
        live = false
        _notice.value = null
        stopTimeshift()
        exo?.stop()
        exo?.clearMediaItems()
    }

    fun release() {
        retryJob?.cancel()
        stopTimeshift()
        runCatching { loudness?.release() }; loudness = null
        runCatching { dynamics?.release() }; dynamics = null; dynamicsMode = -1
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

    private fun channelLabel(c: Int) = when (c) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> trf("{0} Kanäle", c) }

    private fun codecName(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AC3 -> tr("Dolby Digital")
        MimeTypes.AUDIO_E_AC3 -> tr("Dolby Digital Plus")
        MimeTypes.AUDIO_E_AC3_JOC -> tr("Dolby Atmos (DD+ JOC)")
        MimeTypes.AUDIO_TRUEHD -> tr("Dolby TrueHD")
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
                // Schnellstart-Lernen: Stocken in den ersten 15 s → dieser Sender braucht mehr Start-Vorrat
                if (liveStartUs > 0 && !earlyStallSeen && readyAt > 0 && SystemClock.elapsedRealtime() - readyAt < 15_000) {
                    earlyStallSeen = true; currentUrl?.let { ZapTuner.onEarlyStall(it) }
                }
                stallStart = now
                _stats.update { it.copy(rebuffers = it.rebuffers + 1) }
            }
            if (playbackState == Player.STATE_READY) {
                if (readyAt == 0L) {
                    readyAt = SystemClock.elapsedRealtime()
                    val u = currentUrl
                    if (liveStartUs > 0 && u != null) scope.launch {
                        delay(20_000)
                        if (currentUrl == u && !earlyStallSeen && exo?.isPlaying == true) ZapTuner.onCleanStart(u)
                    }
                }
                if (stallStart > 0) { val d = now - stallStart; _stats.update { it.copy(stallMs = it.stallMs + d) }; stallStart = 0 }
                wasReady = true
                retries = 0
                _error.value = null
                _notice.value = null
            }
            // Live-Stream zu Ende (Server schließt Verbindung) → sofort neu verbinden statt anzuhalten
            if (playbackState == Player.STATE_ENDED && live && userPaused) { pausedConnectionLost = true; return }
            if (playbackState == Player.STATE_ENDED && live && cfg.liveReconnect && currentUrl != null) {
                scope.launch { delay(300); if (exo?.playbackState == Player.STATE_ENDED) reconnect(tr("Stream wurde vom Server beendet")) }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) { _playing.value = isPlaying }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            runCatching { loudness?.release() }; loudness = null
            applyBoost(); applyCinemaSound()
        }

        override fun onTracksChanged(tracks: Tracks) {
            _tracksVersion.update { it + 1 }
        }

        override fun onPlayerError(error: PlaybackException) {
            ZapMonitor.error()
            val p = exo ?: return
            val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
            _stats.update { it.copy(errors = it.errors + 1, lastError = error.errorCodeName, httpCode = http) }
            // Pausiertes Live-TV: Der Anbieter trennt die ruhende Verbindung – das ist kein Hänger. Erst beim Fortsetzen neu verbinden.
            if (live && userPaused) { pausedConnectionLost = true; _notice.value = null; return }
            // Live: 401/403 entstehen oft kurz nach dem Umschalten (Verbindungslimit, alte Verbindung noch offen) → erst erneut versuchen
            if (live && (http == 401 || http == 403) && retries < 3) {
                retries++
                _notice.value = "Anbieter blockiert kurz ($http) – neuer Versuch $retries/3 …"
                retryJob?.cancel()
                retryJob = scope.launch { delay(2500L * retries); currentUrl?.let { u -> p.setMediaItem(mediaItem(u)); p.prepare(); p.playWhenReady = true } }
                return
            }
            if (http == 401 || http == 403 || http == 404) {
                _error.value = when (http) {
                    404 -> tr("Dieser Stream existiert beim Anbieter nicht mehr (404).")
                    else -> trf("Zugriff verweigert ({0}): Abo abgelaufen oder Verbindungslimit erreicht – läuft der Account gerade auf einem anderen Gerät?", http)
                }
                _fatal.update { it + 1 }
                return
            }
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition(); p.prepare(); p.playWhenReady = true; return
            }
            val hardFail = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                error.errorCode in 6000..6999 // DRM
            // Live-TV gibt nie auf: Netzwerk-/Parserfehler werden mit wachsendem Abstand endlos neu versucht
            if (live && cfg.liveReconnect && !hardFail) {
                retries++
                if (retries == 4) _fatal.update { it + 1 } // Ausweichsender anbieten (falls aktiviert), trotzdem weiter versuchen
                _notice.value = trf("Verbindung unterbrochen – neuer Versuch {0} …", retries)
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay((1000L shl (retries - 1).coerceAtMost(4)).coerceAtMost(15_000))
                    _stats.update { it.copy(recoveries = it.recoveries + 1) }
                    if (tsBuffer != null) timeshiftTo(timeshiftWall())
                    else currentUrl?.let { u -> p.setMediaItem(mediaItem(u)); p.prepare(); p.playWhenReady = true }
                    graceUntil = SystemClock.elapsedRealtime() + 8_000
                }
                return
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
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> tr("Keine Verbindung zum Stream-Server.")
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> tr("Dieses Gerät kann das Videoformat nicht dekodieren.")
                PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> tr("Geschützter Inhalt (DRM) – nicht abspielbar.")
                else -> trf("Wiedergabefehler: {0}", error.errorCodeName)
            }
            _fatal.update { it + 1 }
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            _stats.update { it.copy(droppedFrames = it.droppedFrames + droppedFrames) }
        }

        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            ZapMonitor.format()
            val hdr = when {
                format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> tr("Dolby Vision (Quelle)")
                format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10 / PQ (Quelle)"
                format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> tr("HLG (Quelle)")
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
            ZapMonitor.decoderInit(initializationDurationMs)
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

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) { ZapMonitor.firstFrame() }
        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) { ZapMonitor.audioStarted() }

        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
            _stats.update { it.copy(bandwidthBps = bitrateEstimate) }
        }
    }
}


/** Externe Untertiteldatei (SRT/VTT), die zusammen mit dem Video geladen wird. */
data class ExternalSubtitle(val url: String, val language: String?, val label: String, val format: String)

/** Filme/Serien: erst nach 4 s Vorrat loslegen (Live-TV unverändert schnell). */
private const val VOD_START_BUFFER_US = 4_000_000L

/**
 * Startet die Wiedergabe von Filmen und Folgen erst mit etwas mehr Vorrat, damit Bild und Ton nicht beim Anlaufen stocken.
 * Nur der erste Start (und nach dem Spulen) – beim Nachladen gelten die normalen Werte. Live-TV: unverändert.
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
private class VodStartLoadControl(
    private val d: DefaultLoadControl,
    private val minStartUs: () -> Long,
    /** Live-Schnellstart: Wiedergabe beginnt, sobald so viel Vorrat da ist (0 = Standardwerte). Nachladen nach Stocken bleibt unverändert. */
    private val liveStartUs: () -> Long = { 0L },
) : androidx.media3.exoplayer.LoadControl {
    // Alle Varianten ausdrücklich an DefaultLoadControl weiterreichen (die Schnittstelle besteht fast nur aus Default-Methoden)
    override fun shouldStartPlayback(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean {
        val need = minStartUs()
        if (need > 0 && !parameters.rebuffering && parameters.bufferedDurationUs < need) return false
        val fast = liveStartUs()
        if (fast > 0 && !parameters.rebuffering && parameters.bufferedDurationUs >= fast) return true
        return d.shouldStartPlayback(parameters)
    }
    override fun onPrepared(playerId: androidx.media3.exoplayer.analytics.PlayerId) = d.onPrepared(playerId)
    override fun onPrepared() = d.onPrepared()
    override fun onTracksSelected(parameters: androidx.media3.exoplayer.LoadControl.Parameters, trackGroups: androidx.media3.exoplayer.source.TrackGroupArray,
                                  trackSelections: Array<out androidx.media3.exoplayer.trackselection.ExoTrackSelection?>) =
        d.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onTracksSelected(playerId: androidx.media3.exoplayer.analytics.PlayerId, timeline: androidx.media3.common.Timeline,
                                  mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId, renderers: Array<out androidx.media3.exoplayer.Renderer>,
                                  trackGroups: androidx.media3.exoplayer.source.TrackGroupArray,
                                  trackSelections: Array<out androidx.media3.exoplayer.trackselection.ExoTrackSelection?>) =
        d.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
    override fun onTracksSelected(timeline: androidx.media3.common.Timeline, mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId,
                                  renderers: Array<out androidx.media3.exoplayer.Renderer>, trackGroups: androidx.media3.exoplayer.source.TrackGroupArray,
                                  trackSelections: Array<out androidx.media3.exoplayer.trackselection.ExoTrackSelection?>) =
        d.onTracksSelected(timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
    override fun onTracksSelected(renderers: Array<out androidx.media3.exoplayer.Renderer>, trackGroups: androidx.media3.exoplayer.source.TrackGroupArray,
                                  trackSelections: Array<out androidx.media3.exoplayer.trackselection.ExoTrackSelection?>) =
        d.onTracksSelected(renderers, trackGroups, trackSelections)
    override fun onStopped(playerId: androidx.media3.exoplayer.analytics.PlayerId) = d.onStopped(playerId)
    override fun onStopped() = d.onStopped()
    override fun onReleased(playerId: androidx.media3.exoplayer.analytics.PlayerId) = d.onReleased(playerId)
    override fun onReleased() = d.onReleased()
    override fun getAllocator(): androidx.media3.exoplayer.upstream.Allocator = d.allocator
    override fun getBackBufferDurationUs(playerId: androidx.media3.exoplayer.analytics.PlayerId) = d.getBackBufferDurationUs(playerId)
    override fun getBackBufferDurationUs() = d.backBufferDurationUs
    override fun retainBackBufferFromKeyframe(playerId: androidx.media3.exoplayer.analytics.PlayerId) = d.retainBackBufferFromKeyframe(playerId)
    override fun retainBackBufferFromKeyframe() = d.retainBackBufferFromKeyframe()
    override fun shouldContinueLoading(parameters: androidx.media3.exoplayer.LoadControl.Parameters) = d.shouldContinueLoading(parameters)
    override fun shouldContinueLoading(playbackPositionUs: Long, bufferedDurationUs: Long, playbackSpeed: Float) =
        d.shouldContinueLoading(playbackPositionUs, bufferedDurationUs, playbackSpeed)
    override fun shouldContinuePreloading(timeline: androidx.media3.common.Timeline, mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId,
                                          bufferedDurationUs: Long) = d.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)
    override fun shouldStartPlayback(timeline: androidx.media3.common.Timeline, mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId,
                                     bufferedDurationUs: Long, playbackSpeed: Float, rebuffering: Boolean, targetLiveOffsetUs: Long) =
        d.shouldStartPlayback(timeline, mediaPeriodId, bufferedDurationUs, playbackSpeed, rebuffering, targetLiveOffsetUs)
    override fun shouldStartPlayback(bufferedDurationUs: Long, playbackSpeed: Float, rebuffering: Boolean, targetLiveOffsetUs: Long) =
        d.shouldStartPlayback(bufferedDurationUs, playbackSpeed, rebuffering, targetLiveOffsetUs)
}
