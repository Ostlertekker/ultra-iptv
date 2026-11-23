package de.ostlertekker.ultraiptv.feature.tools

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import de.ostlertekker.ultraiptv.core.ui.fadeOnChange
import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import de.ostlertekker.ultraiptv.core.ui.PulsingDot
import de.ostlertekker.ultraiptv.core.ui.motionOk
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.ui.ScreenTitle
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.TvChip
import de.ostlertekker.ultraiptv.core.ui.tvFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

// =====================================================================================================
// Modelle
// =====================================================================================================

data class SpeedState(
    val running: Boolean = false,
    val phase: String = "",
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    val live: Double? = null,
    val server: String? = null,
    val error: String? = null,
    /** Live-Messwerte der laufenden Phase (für die Verlaufskurve). */
    val samples: List<Double> = emptyList(),
    /** Durchsatz eines Anbieter-Streams (nur „Mit Anbieter-Stream“; die Stream-Bitrate begrenzt den Wert). */
    val providerMbps: Double? = null,
    val providerNote: String? = null,
)

/** Balken in der App-Diagnose (Anteil 0..1). */
data class DiagBar(val id: String, val label: String, val fraction: Float, val text: String)

data class CheckRow(val name: String, val ok: Boolean?, val ms: Long? = null, val detail: String = "")

/** Bewertung der Download-Rate für Streaming (Richtwerte für einen gleichzeitigen Stream). */
fun streamRating(mbps: Double): String = when {
    mbps >= 25 -> "Reicht für 4K / UHD"
    mbps >= 10 -> tr("Reicht für Full HD, für 4K knapp")
    mbps >= 5 -> tr("Reicht für HD")
    mbps >= 2.5 -> tr("Reicht für SD, HD kann ruckeln")
    else -> tr("Zu langsam für flüssiges Streaming")
}

// =====================================================================================================
// ViewModel
// =====================================================================================================

@HiltViewModel
class NetworkToolsViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    baseClient: OkHttpClient,
    private val creds: de.ostlertekker.ultraiptv.core.security.CredentialStore,
    private val jellyfin: de.ostlertekker.ultraiptv.core.jellyfin.JellyfinRepository,
    private val seerr: de.ostlertekker.ultraiptv.core.seerr.SeerrRepository,
    private val content: de.ostlertekker.ultraiptv.core.data.ContentRepository,
    private val resolver: de.ostlertekker.ultraiptv.core.security.StreamResolver,
    private val settingsRepo: de.ostlertekker.ultraiptv.core.data.SettingsRepository,
) : ViewModel() {
    // Eigener Client: kein Cache, getrennte Verbindungen für parallele Messungen
    private val http = baseClient.newBuilder().cache(null).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).build()
    private val base = "https://speed.cloudflare.com"
    /** Messserver Download/Ping: Hetzner Falkenstein (Deutschland), offizielle Testdateien. */
    private val hetzner = "https://fsn1-speed.hetzner.com"

    val speed = MutableStateFlow(SpeedState())
    val checks = MutableStateFlow<List<CheckRow>>(emptyList())
    val checking = MutableStateFlow(false)
    /** Gerade laufende Prüfung (für die Dreh-Anzeige) und Gesamtzahl der Prüfungen. */
    val checkCurrent = MutableStateFlow<String?>(null)
    val checkTotal = 6
    val wifiRssi = MutableStateFlow<Int?>(null)
    val info = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    private var speedJob: Job? = null

    // ---------------------------------------------------------------- Speedtest (Cloudflare-Messserver)

    /**
     * Speedtest wie früher: eine Verbindung, Download ca. 8 s, Upload ein 4-MB-Paket – Download und Ping jetzt gegen
     * Hetzner Falkenstein (DE). Upload über Cloudflare, da Hetzner keinen Upload-Test anbietet.
     * Optional: Durchsatz eines echten Streams deines IPTV-Anbieters (5 s).
     */
    fun startSpeedtest(withProvider: Boolean = false) {
        speedJob?.cancel()
        speedJob = viewModelScope.launch(Dispatchers.IO) {
            speed.value = SpeedState(running = true, phase = tr("Ping wird gemessen …"), server = tr("Hetzner Falkenstein (DE)"))
            try {
                val (ping, jitter) = pingHetzner()
                speed.value = speed.value.copy(pingMs = ping, jitterMs = jitter, phase = tr("Download wird gemessen …"))
                val down = measureDownload("$hetzner/1GB.bin", 8_000) { live -> speed.value = speed.value.copy(live = live, samples = speed.value.samples + live) }
                speed.value = speed.value.copy(downMbps = down, live = null, samples = emptyList(), phase = tr("Upload wird gemessen …"))
                val up = measureUpload { live -> speed.value = speed.value.copy(live = live, samples = speed.value.samples + live) }
                speed.value = speed.value.copy(upMbps = up, live = null, samples = emptyList())
                if (withProvider) {
                    speed.value = speed.value.copy(phase = tr("Anbieter-Stream wird gemessen …"))
                    val ch = runCatching { content.channelsAllOnce(1).firstOrNull() }.getOrNull()
                    val url = ch?.let { runCatching { resolver.resolve(it.streamUrl, settingsRepo.current().liveFormat) }.getOrNull() }
                    if (url == null) speed.value = speed.value.copy(providerNote = tr("Kein Live-Sender vorhanden"))
                    else {
                        val r = runCatching { measureDownload(url, 5_000) { live -> speed.value = speed.value.copy(live = live, samples = speed.value.samples + live) } }
                        speed.value = speed.value.copy(providerMbps = r.getOrNull(), live = null, samples = emptyList(),
                            providerNote = r.exceptionOrNull()?.let { "Anbieter-Stream nicht messbar: ${de.ostlertekker.ultraiptv.core.network.SafeLog.redact(it.message ?: "Fehler")}" })
                    }
                }
                speed.value = speed.value.copy(running = false, phase = "Fertig")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                speed.value = speed.value.copy(running = false, live = null, phase = "", error = trf("Test fehlgeschlagen: {0}", (e.message ?: "keine Verbindung")))
            }
        }
    }

    /** 6 kleine Anfragen (1 Byte) über dieselbe Verbindung; die erste (Verbindungsaufbau) zählt nicht. Ping = schnellste. */
    private fun pingHetzner(): Pair<Double, Double> {
        val times = ArrayList<Double>()
        repeat(6) { i ->
            val t0 = System.nanoTime()
            http.newCall(Request.Builder().url("$hetzner/100MB.bin").header("Range", "bytes=0-0").build()).execute().use { it.body?.bytes() }
            if (i > 0) times += (System.nanoTime() - t0) / 1e6
        }
        val jitter = times.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average()
        return times.min() to jitter
    }

    /** Lädt höchstens [maxMs] lang über eine Verbindung und liefert Mbit/s (mit Live-Wert alle 250 ms). */
    private suspend fun measureDownload(url: String, maxMs: Long, onLive: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        var bytes = 0L
        var lastTick = 0L
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val input = r.body?.byteStream() ?: error(tr("Leere Antwort"))
            val buf = ByteArray(64 * 1024)
            while (isActive) {
                val n = input.read(buf); if (n < 0) break
                bytes += n
                val el = (System.nanoTime() - start) / 1_000_000
                if (el - lastTick >= 250) { lastTick = el; onLive(bytes * 8.0 / 1e6 / (el / 1000.0).coerceAtLeast(0.001)) }
                if (el >= maxMs) break
            }
        }
        if (bytes <= 0) error("keine Daten übertragen")
        bytes * 8.0 / 1e6 / ((System.nanoTime() - start) / 1e9).coerceAtLeast(0.001)
    }

    /** Ein 4-MB-Paket hochladen (Cloudflare) und Mbit/s berechnen. */
    private suspend fun measureUpload(onLive: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
        val size = 4_000_000L
        val sent = AtomicLong(0)
        val start = System.nanoTime()
        val ticker = launch {
            while (isActive) { delay(250); val el = (System.nanoTime() - start) / 1e9; if (el > 0.3) onLive(sent.get() * 8.0 / 1e6 / el) }
        }
        try {
            http.newCall(Request.Builder().url("$base/__up").post(CountingBody(size, sent) { true }).build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}"); r.body?.bytes()
            }
        } finally { ticker.cancel() }
        size * 8.0 / 1e6 / ((System.nanoTime() - start) / 1e9).coerceAtLeast(0.001)
    }

    private fun meta(): JSONObject = http.newCall(Request.Builder().url("$base/meta").build()).execute().use { r ->
        if (!r.isSuccessful) error("HTTP ${r.code}"); JSONObject(r.body!!.string())
    }

    /** 12 kleine Anfragen über dieselbe Verbindung; die erste (Verbindungsaufbau) zählt nicht. */
    private fun ping(): Pair<Double, Double> {
        val times = ArrayList<Double>()
        repeat(12) { i ->
            val t0 = System.nanoTime()
            http.newCall(Request.Builder().url("$base/__down?bytes=0").build()).execute().use { it.body?.bytes() }
            val ms = (System.nanoTime() - t0) / 1e6
            if (i > 0) times += ms
        }
        val sorted = times.sorted()
        val median = sorted[sorted.size / 2]
        val jitter = times.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average()
        return median to jitter
    }

    /** Parallel laden/hochladen (4 bzw. 3 Verbindungen) für ~10 s; die erste Sekunde (Anlaufen) wird nicht gewertet. */
    private suspend fun transfer(upload: Boolean, onLive: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
        val bytes = AtomicLong(0)
        val durationMs = if (upload) 8_000L else 10_000L
        val warmupMs = 1_000L
        val start = System.currentTimeMillis()
        var warmBytes = -1L
        val streams = if (upload) 3 else 4
        val workers = (0 until streams).map {
            async {
                while (isActive && System.currentTimeMillis() - start < durationMs) {
                    runCatching {
                        if (upload) {
                            val body = CountingBody(4_000_000, bytes) { System.currentTimeMillis() - start < durationMs }
                            http.newCall(Request.Builder().url("$base/__up").post(body).build()).execute().close()
                        } else {
                            http.newCall(Request.Builder().url("$base/__down?bytes=25000000").build()).execute().use { r ->
                                val src = r.body?.byteStream() ?: return@use
                                val buf = ByteArray(64 * 1024)
                                while (isActive && System.currentTimeMillis() - start < durationMs) {
                                    val n = src.read(buf); if (n < 0) break; bytes.addAndGet(n.toLong())
                                }
                            }
                        }
                    }
                }
            }
        }
        // Live-Anzeige
        val ticker = launch {
            while (isActive) {
                delay(250)
                val t = System.currentTimeMillis() - start
                if (t >= warmupMs && warmBytes < 0) warmBytes = bytes.get()
                if (t > warmupMs + 300) onLive((bytes.get() - warmBytes) * 8.0 / ((t - warmupMs) / 1000.0) / 1e6)
            }
        }
        workers.awaitAll()
        ticker.cancel()
        val total = System.currentTimeMillis() - start
        val counted = bytes.get() - warmBytes.coerceAtLeast(0)
        if (counted <= 0 || total <= warmupMs) error("keine Daten übertragen")
        counted * 8.0 / ((total - warmupMs) / 1000.0) / 1e6
    }

    /** Upload-Daten, die beim Schreiben gezählt werden (bricht nach Ablauf der Messzeit ab). */
    private class CountingBody(private val size: Long, private val counter: AtomicLong, private val go: () -> Boolean) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = size
        override fun writeTo(sink: BufferedSink) {
            val chunk = ByteArray(64 * 1024) { (it * 31).toByte() }
            var left = size
            while (left > 0) {
                if (!go()) throw java.io.IOException(tr("Messzeit vorbei"))
                val n = minOf(left, chunk.size.toLong()).toInt()
                sink.write(chunk, 0, n); counter.addAndGet(n.toLong()); left -= n
            }
        }
    }

    // ---------------------------------------------------------------- Verbindungs-Check

    fun runChecks() {
        if (checking.value) return
        viewModelScope.launch(Dispatchers.IO) {
            checking.value = true
            val rows = ArrayList<CheckRow>()
            checks.value = emptyList()
            // Nacheinander prüfen; jede Zeile bleibt kurz sichtbar „in Arbeit“ (nur Darstellung, Ergebnis bleibt echt)
            fun step(name: String, block: () -> CheckRow) {
                checkCurrent.value = name
                val t0 = System.currentTimeMillis()
                val r = block()
                val rest = 280 - (System.currentTimeMillis() - t0)
                if (rest > 0) Thread.sleep(rest)
                rows += r; checks.value = rows.toList()
            }
            // Internet: Android-Standardprüfung (Google), bei Fehlschlag Hetzner als zweiter Anbieter
            step("Internet") {
                val g = probe("Internet", "https://connectivitycheck.gstatic.com/generate_204")
                if (g.ok == true) g.copy(detail = listOf(g.detail, "Google").filter { it.isNotBlank() }.joinToString(" · "))
                else probe("Internet", "https://fsn1-speed.hetzner.com/", anyHttpIsOk = true)
                    .let { h -> h.copy(detail = listOf(h.detail, "Hetzner").filter { it.isNotBlank() }.joinToString(" · ")) }
            }
            step(tr("Namensauflösung (DNS)")) {
                val t0 = System.nanoTime()
                runCatching { java.net.InetAddress.getByName("example.org") }.fold(
                    { CheckRow(tr("Namensauflösung (DNS)"), true, (System.nanoTime() - t0) / 1_000_000, it.hostAddress ?: "") },
                    { CheckRow(tr("Namensauflösung (DNS)"), false, detail = it.message ?: tr("Fehler")) })
            }
            // IPTV-Anbieter (nur Serveradresse, keine Zugangsdaten)
            step("IPTV-Anbieter") {
                val cfg = runCatching { creds.load() }.getOrNull()
                val providerUrl = cfg?.let { c -> (c.server.ifBlank { c.m3uUrl }).takeIf { it.isNotBlank() } }
                if (providerUrl == null) CheckRow("IPTV-Anbieter", null, detail = tr("Keine Quelle eingerichtet"))
                else probe("IPTV-Anbieter", originOf(providerUrl), anyHttpIsOk = true)
            }
            step("Jellyfin") {
                val jf = jellyfin.session.value ?: runCatching { jellyfin.restore() }.getOrNull()
                if (jf == null) CheckRow("Jellyfin", null, detail = tr("Nicht angemeldet"))
                else probe("Jellyfin", jf.server.trimEnd('/') + "/System/Info/Public")
            }
            step("Seerr") {
                val st = seerr.state.value
                if (!st.loggedIn) CheckRow("Seerr", null, detail = tr("Nicht verbunden"))
                else probe("Seerr", st.server.trimEnd('/') + "/api/v1/status")
            }
            // Filterlisten (Browser-Werbeblocker)
            step(tr("Filterlisten (Werbeblocker)")) { probe(tr("Filterlisten (Werbeblocker)"), "https://easylist.to/", anyHttpIsOk = true) }
            checkCurrent.value = null
            checking.value = false
        }
    }

    private fun originOf(url: String): String = runCatching {
        val u = java.net.URI(url.trim()); "${u.scheme ?: "http"}://${u.host}${if (u.port > 0) ":" + u.port else ""}/"
    }.getOrDefault(url)

    /** Erreichbarkeit + Antwortzeit. [anyHttpIsOk]: jede HTTP-Antwort zählt (Server antwortet), z. B. 401/404 auf „/“. */
    private fun probe(name: String, url: String, anyHttpIsOk: Boolean = false): CheckRow {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        val t0 = System.nanoTime()
        return runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "UltraIPTV").build()).execute().use { r ->
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (r.isSuccessful || r.code == 204 || anyHttpIsOk) CheckRow(name, true, ms, trf("{0} · Antwort {1}", host, r.code))
                else CheckRow(name, false, ms, trf("{0} · Fehler {1}", host, r.code))
            }
        }.getOrElse { CheckRow(name, false, detail = "$host · " + (it.message ?: it.javaClass.simpleName)) }
    }

    // ---------------------------------------------------------------- App-Diagnose

    val diag = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val diagBars = MutableStateFlow<List<DiagBar>>(emptyList())
    val lastErrors = MutableStateFlow<String?>(null)

    private fun sizeOf(f: java.io.File?): Long = when {
        f == null || !f.exists() -> 0L
        f.isFile -> f.length()
        else -> f.listFiles()?.sumOf { sizeOf(it) } ?: 0L
    }

    private fun frac(a: Long, b: Long): Float = if (b <= 0) 0f else (a.toDouble() / b).toFloat().coerceIn(0f, 1f)

    private fun mb(b: Long) = "%.1f MB".format(java.util.Locale.GERMANY, b / 1_048_576.0)

    fun loadDiag() {
        viewModelScope.launch(Dispatchers.IO) {
            val out = ArrayList<Pair<String, String>>()
            val bars = ArrayList<DiagBar>()
            out += "App-Version" to "${de.ostlertekker.ultraiptv.BuildConfig.VERSION_NAME} (${de.ostlertekker.ultraiptv.BuildConfig.VERSION_CODE})"
            out += tr("Gerät") to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            out += "Android" to trf("{0} (API {1})", android.os.Build.VERSION.RELEASE, android.os.Build.VERSION.SDK_INT)
            val rt = Runtime.getRuntime()
            out += tr("Arbeitsspeicher der App") to "${mb(rt.totalMemory() - rt.freeMemory())} von max. ${mb(rt.maxMemory())}"
            bars += DiagBar("ram_app", tr("Arbeitsspeicher der App"), frac(rt.totalMemory() - rt.freeMemory(), rt.maxMemory()), mb(rt.totalMemory() - rt.freeMemory()))
            ctx.getSystemService(android.app.ActivityManager::class.java)?.let { am ->
                val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
                out += tr("Arbeitsspeicher des Geräts") to "${mb(mi.availMem)} frei von ${mb(mi.totalMem)}" + if (mi.lowMemory) tr(" · knapp!") else ""
                bars += DiagBar("ram_dev", tr("Arbeitsspeicher des Geräts"), frac(mi.totalMem - mi.availMem, mi.totalMem), "${mb(mi.totalMem - mi.availMem)} belegt")
            }
            val cacheB = sizeOf(ctx.cacheDir)
            out += tr("Cache (Bilder, Web …)") to mb(cacheB)
            val dbDir = ctx.getDatabasePath("x").parentFile
            val appTotal = sizeOf(ctx.filesDir) + sizeOf(dbDir) + cacheB
            out += tr("Datenbank (Sender, Filme, EPG)") to mb(sizeOf(dbDir))
            out += tr("App-Daten gesamt") to mb(appTotal)
            bars += DiagBar("cache", tr("Cache (Anteil an App-Daten)"), frac(cacheB, appTotal), mb(cacheB))
            runCatching { android.os.StatFs(ctx.filesDir.path) }.getOrNull()?.let { st ->
                out += tr("Freier Gerätespeicher") to "${mb(st.availableBytes)} von ${mb(st.totalBytes)}"
                bars += DiagBar("disk", tr("Gerätespeicher"), frac(st.totalBytes - st.availableBytes, st.totalBytes), "${mb(st.totalBytes - st.availableBytes)} belegt")
            }
            out += "Software-Tondecoder" to (if (runCatching { androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable() }.getOrDefault(false)) "aktiv" else "nicht verfügbar")
            diag.value = out
            diagBars.value = bars.sortedBy { listOf("ram_app", "ram_dev", "disk", "cache").indexOf(it.id) }
            lastErrors.value = listOfNotNull(
                de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard.lastCrash(ctx)?.let { "ABSTURZ\n$it" },
                de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard.lastFreeze(ctx)?.let { trf("HÄNGER\n{0}", it) },
            ).joinToString("\n\n").lines().filter { it.isNotBlank() }.take(14).joinToString("\n").ifBlank { null }
        }
    }

    /** Cache leeren: Bild-Zwischenspeicher, Web-Cache und temporäre Dateien. Favoriten, Einstellungen, Profile bleiben. */
    fun clearCache(onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val before = sizeOf(ctx.cacheDir)
            // Cache-Balken sofort auf 0 laufen lassen
            diagBars.value = diagBars.value.map { if (it.id == "cache") it.copy(fraction = 0f, text = "wird geleert …") else it }
            ctx.cacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
            withContext(Dispatchers.Main) {
                runCatching { coil.Coil.imageLoader(ctx).memoryCache?.clear() }
                runCatching { android.webkit.WebView(ctx).apply { clearCache(true); destroy() } }
            }
            val freed = (before - sizeOf(ctx.cacheDir)).coerceAtLeast(0)
            withContext(Dispatchers.Main) { onDone("${mb(freed)} freigegeben") }
            loadDiag()
        }
    }

    fun clearErrors() {
        de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard.clear(ctx); loadDiag()
    }

    // ---------------------------------------------------------------- Netzwerk-Infos

    @SuppressLint("MissingPermission")
    fun loadInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val out = ArrayList<Pair<String, String>>()
            wifiRssi.value = null
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val type = when {
                caps == null -> tr("Keine Verbindung")
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> tr("LAN (Kabel)")
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WLAN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> tr("Mobilfunk")
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> tr("Sonstige")
            }
            out += "Verbindung" to type
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) out += "VPN" to "aktiv"
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                @Suppress("DEPRECATION") val wi = runCatching { ctx.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo }.getOrNull()
                if (wi != null) {
                    val f = wi.frequency
                    if (f > 0) out += "WLAN-Band" to when { f >= 5925 -> tr("6 GHz"); f >= 4900 -> tr("5 GHz"); else -> tr("2,4 GHz") } + trf(" (Kanal-Frequenz {0} MHz)", f)
                    val rssi = wi.rssi
                    if (rssi > -127 && rssi < 0) wifiRssi.value = rssi
                    out += tr("Signalstärke") to "$rssi dBm · " + when { rssi >= -55 -> "sehr gut"; rssi >= -67 -> "gut"; rssi >= -75 -> "mittel"; else -> "schwach" }
                    if (wi.linkSpeed > 0) out += "Verbindungsrate" to "${wi.linkSpeed} Mbit/s"
                }
            }
            caps?.let { c ->
                if (c.linkDownstreamBandwidthKbps > 0) out += tr("Geschätzte Bandbreite") to "↓ ${c.linkDownstreamBandwidthKbps / 1000} / ↑ ${c.linkUpstreamBandwidthKbps / 1000} Mbit/s"
            }
            net?.let { cm.getLinkProperties(it) }?.let { lp ->
                val ips = lp.linkAddresses.map { it.address.hostAddress ?: "" }.filter { it.isNotBlank() }
                ips.filter { !it.contains(':') }.forEach { out += "IP-Adresse (lokal)" to it }
                ips.firstOrNull { it.contains(':') && !it.startsWith("fe80") }?.let { out += "IPv6" to it.substringBefore('%') }
                lp.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress?.let { out += "Router" to it }
                lp.dnsServers.mapNotNull { it.hostAddress }.take(3).takeIf { it.isNotEmpty() }?.let { out += "DNS-Server" to it.joinToString(", ") }
            }
            info.value = out.toList()
            // Öffentliche Adresse + Internetanbieter
            runCatching { meta() }.onSuccess { m ->
                m.optString("clientIp").ifBlank { null }?.let { out += tr("Öffentliche IP") to it }
                m.optString("asOrganization").ifBlank { null }?.let { out += "Internetanbieter" to it }
                listOfNotNull(m.optString("city").ifBlank { null }, m.optString("country").ifBlank { null }).takeIf { it.isNotEmpty() }
                    ?.let { out += tr("Standort (laut IP)") to it.joinToString(", ") }
            }.onFailure { out += tr("Öffentliche IP") to "nicht ermittelbar (kein Internet?)" }
            info.value = out.toList()
        }
    }
}

// =====================================================================================================
// Oberfläche
// =====================================================================================================

private enum class Tool(val label: String) { SPEED("Speedtest"), CHECK("Verbindungs-Check"), INFO("Netzwerk-Infos"), DIAG("App-Diagnose") }

private val Good = Color(0xFF4ADE80)
private val Warn = Color(0xFFFFC857)
private val Bad = Color(0xFFFF6B6B)

/** „Tools“: Netzwerk-Werkzeuge und App-Diagnose. */
@Composable
fun ToolsScreen(vm: NetworkToolsViewModel = hiltViewModel()) {
    var tool by remember { mutableStateOf(Tool.SPEED) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
        ScreenTitle(tr("Tools"), subtitle = tr("Netzwerk prüfen und App-Zustand – Geschwindigkeit, Erreichbarkeit, Verbindungsdaten, Diagnose"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tool.entries.forEach { t -> TvChip(t.label, tool == t, { tool = t }) }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fadeOnChange(tool)) { when (tool) {
            Tool.SPEED -> SpeedPane(vm)
            Tool.CHECK -> CheckPane(vm)
            Tool.INFO -> InfoPane(vm)
            Tool.DIAG -> DiagPane(vm)
        } }
    }
}

// ---------------------------------------------------------------- Animations-Bausteine

/** Zählt beim Eintreffen eines Endwerts kurz von 0 hoch (4). */
@Composable
private fun countUp(final: Double?): Double? {
    val ok = motionOk()
    val a = remember { Animatable(0f) }
    LaunchedEffect(final) {
        if (final == null) return@LaunchedEffect
        if (!ok) { a.snapTo(final.toFloat()); return@LaunchedEffect }
        a.snapTo(0f); a.animateTo(final.toFloat(), tween(700, easing = FastOutSlowInEasing))
    }
    return final?.let { a.value.toDouble() }
}

/** Wert läuft beim ersten Anzeigen von 0 auf das Ziel (Balken 8/10/11). */
@Composable
private fun growTo(target: Float, durationMs: Int = 650, delayMs: Long = 0): Float {
    val ok = motionOk()
    val a = remember { Animatable(if (ok) 0f else target) }
    LaunchedEffect(target) {
        if (!ok) { a.snapTo(target); return@LaunchedEffect }
        if (delayMs > 0 && a.value == 0f) delay(delayMs)
        a.animateTo(target, tween(durationMs, easing = FastOutSlowInEasing))
    }
    return a.value
}

/** Weiches Einblenden mit kleinem Versatz, gestaffelt nach Index (9). */
@Composable
private fun Modifier.staggerIn(index: Int): Modifier {
    val ok = motionOk()
    val a = remember { Animatable(if (ok) 0f else 1f) }
    LaunchedEffect(Unit) { if (ok) { delay(index.coerceAtMost(12) * 45L); a.animateTo(1f, tween(260)) } }
    return this.graphicsLayer { alpha = a.value; translationX = (1f - a.value) * 24.dp.toPx() }
}

/** Kurzes Aufploppen beim Erscheinen (6, 11). */
@Composable
private fun Modifier.popIn(): Modifier {
    val ok = motionOk()
    val a = remember { Animatable(if (ok) 0.4f else 1f) }
    LaunchedEffect(Unit) { if (ok) a.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) }
    return this.graphicsLayer { scaleX = a.value; scaleY = a.value; alpha = ((a.value - 0.4f) / 0.6f).coerceIn(0f, 1f) }
}

/** Logarithmische Tacho-Skala 0 … 1000 Mbit/s. */
private fun gaugeFrac(v: Double): Float = (kotlin.math.ln(1 + v.coerceAtLeast(0.0)) / kotlin.math.ln(1001.0)).toFloat().coerceIn(0f, 1f)

/** (1) Tacho mit nachfedernder Nadel. */
@Composable
private fun SpeedGauge(value: Double, label: String, modifier: Modifier = Modifier) {
    val p = LocalUltraTheme.current.palette
    val ok = motionOk()
    val target = gaugeFrac(value)
    val a = remember { Animatable(0f) }
    LaunchedEffect(target) {
        if (ok) a.animateTo(target, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) else a.snapTo(target)
    }
    Box(modifier.width(250.dp).height(150.dp), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val r = minOf(size.width / 2f, size.height) - stroke
            val c = Offset(size.width / 2f, size.height - stroke / 2f)
            val tl = Offset(c.x - r, c.y - r); val sz = Size(2 * r, 2 * r)
            drawArc(p.surface.copy(alpha = 0.8f), 180f, 180f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            val f = a.value.coerceIn(0f, 1.04f)
            if (f > 0.002f) drawArc(Brush.sweepGradient(listOf(p.primary.copy(alpha = 0.6f), p.primary, p.primary.copy(alpha = 0.6f)), c),
                180f, 180f * f.coerceAtMost(1f), false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            // Skalenstriche bei 1, 10, 100, 1000
            listOf(1.0, 10.0, 100.0, 1000.0).forEach { m ->
                val ang = Math.toRadians(180.0 + 180.0 * gaugeFrac(m))
                val o = Offset(c.x + (r + stroke).times(kotlin.math.cos(ang)).toFloat(), c.y + (r + stroke).times(kotlin.math.sin(ang)).toFloat())
                val i2 = Offset(c.x + (r + stroke * 0.6f).times(kotlin.math.cos(ang)).toFloat(), c.y + (r + stroke * 0.6f).times(kotlin.math.sin(ang)).toFloat())
                drawLine(p.textDim.copy(alpha = 0.6f), i2, o, 2.dp.toPx())
            }
            val ang = Math.toRadians(180.0 + 180.0 * f)
            val len = r - stroke * 1.2f
            val end = Offset(c.x + (len * kotlin.math.cos(ang)).toFloat(), c.y + (len * kotlin.math.sin(ang)).toFloat())
            drawLine(p.text, c, end, 4.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(p.text, 7.dp.toPx(), c)
        }
        Text(label, color = p.textDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 22.dp))
    }
}

/** (2) Live-Verlaufskurve der laufenden Messphase. */
@Composable
private fun Sparkline(samples: List<Double>, modifier: Modifier = Modifier) {
    val p = LocalUltraTheme.current.palette
    Canvas(modifier.width(420.dp).height(64.dp).clip(RoundedCornerShape(10.dp)).background(p.surface.copy(alpha = 0.35f))) {
        if (samples.size < 2) return@Canvas
        val max = (samples.maxOrNull() ?: 1.0).coerceAtLeast(1.0) * 1.15
        val n = maxOf(samples.size - 1, 36) // ~9 s bei 250 ms: Kurve wächst von links nach rechts
        val pad = 6.dp.toPx()
        fun pt(i: Int) = Offset(pad + (size.width - 2 * pad) * i / n, size.height - pad - (size.height - 2 * pad) * (samples[i] / max).toFloat())
        val line = Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1 until samples.size) lineTo(pt(i).x, pt(i).y) }
        val fill = Path().apply { addPath(line); lineTo(pt(samples.size - 1).x, size.height - pad); lineTo(pt(0).x, size.height - pad); close() }
        drawPath(fill, Brush.verticalGradient(listOf(p.primary.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, p.primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(p.primary, 3.5.dp.toPx(), pt(samples.size - 1))
    }
}

/** (3) Phasen-Anzeige Ping → Download → Upload. */
@Composable
private fun PhaseSteps(s: SpeedState) {
    val p = LocalUltraTheme.current.palette
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("Ping" to s.pingMs, "Download" to s.downMbps, "Upload" to s.upMbps).forEach { (name, v) ->
            val active = s.running && s.phase.startsWith(name)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                    when {
                        v != null -> Text("✓", color = Good, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.popIn())
                        active -> PulsingDot(p.primary, 10.dp)
                        else -> Box(Modifier.size(8.dp).background(p.textDim.copy(alpha = 0.4f), CircleShape))
                    }
                }
                Text(" $name", color = if (active || v != null) p.text else p.textDim, fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

private fun ratingColor(mbps: Double): Color = when {
    mbps >= 25 -> Good; mbps >= 10 -> Color(0xFFA3E635); mbps >= 5 -> Warn; mbps >= 2.5 -> Color(0xFFFF9F43); else -> Bad
}

@Composable
private fun Stat(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val p = LocalUltraTheme.current.palette
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(p.surface.copy(alpha = 0.6f)).padding(horizontal = 22.dp, vertical = 16.dp)) {
        Text(label, color = p.textDim, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = p.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(" $unit", color = p.textDim, fontSize = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

private fun fmt(v: Double?, digits: Int = 1): String = v?.let { "%.${digits}f".format(java.util.Locale.GERMANY, it) } ?: "–"

@Composable
private fun SpeedPane(vm: NetworkToolsViewModel) {
    val p = LocalUltraTheme.current.palette
    val s by vm.speed.collectAsStateWithLifecycle()
    val ok = motionOk()
    val pingC = countUp(s.pingMs)
    val downC = countUp(s.downMbps)
    val upC = countUp(s.upMbps)
    val inDown = s.downMbps == null && s.phase.startsWith("Download")
    val inUp = s.upMbps == null && s.phase.startsWith("Upload")
    Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 40.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat(tr("Ping"), fmt(pingC, 0), "ms", Modifier.width(200.dp))
            Stat("Download", fmt(if (inDown) s.live else downC), "Mbit/s", Modifier.width(260.dp))
            Stat("Upload", fmt(if (inUp) s.live else upC), "Mbit/s", Modifier.width(260.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            val gaugeVal = when { inDown || inUp -> s.live ?: 0.0; s.running -> 0.0; else -> s.upMbps?.let { s.downMbps } ?: s.downMbps ?: 0.0 }
            SpeedGauge(gaugeVal, when { inDown -> tr("Download"); inUp -> tr("Upload"); s.downMbps != null -> tr("Download"); else -> "Mbit/s" })
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PhaseSteps(s)
                if (s.samples.size >= 2 || inDown || inUp) Sparkline(s.samples)
                val sub = listOfNotNull(s.phase.ifBlank { null }, s.jitterMs?.let { trf("Jitter {0} ms", (fmt(it, 0))) }, s.server?.let { trf("Messserver: {0}", it) }).joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, color = p.textDim, fontSize = 14.sp)
            }
        }
        // (5) Bewertung gleitet mit farbigem Rand ein
        AnimatedVisibility(visible = s.downMbps != null,
            enter = if (ok) fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 2 } else EnterTransition.None,
            exit = if (ok) fadeOut(tween(200)) else ExitTransition.None) {
            val d = s.downMbps ?: 0.0
            Text(streamRating(d), color = p.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp))
                    .border(2.dp, ratingColor(d), RoundedCornerShape(12.dp))
                    .background(ratingColor(d).copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 8.dp))
        }
        s.error?.let { Text("⚠ $it", color = Color(0xFFFF8A80), fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(14.dp))
        s.providerMbps?.let { Text("Durchsatz eines Anbieter-Streams: ${fmt(it)} Mbit/s (die Stream-Bitrate begrenzt den Wert)", color = p.text, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        s.providerNote?.let { Text(it, color = p.textDim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(if (s.running) tr("Test läuft …") else if (s.downMbps != null) tr("Erneut testen") else tr("Test starten"),
                onClick = { if (!s.running) vm.startSpeedtest(false) }, primary = true)
            TvButton(tr("Mit Anbieter-Stream"), onClick = { if (!s.running) vm.startSpeedtest(true) })
        }
        Text(tr("Download und Ping: Hetzner Falkenstein (Deutschland), eine Verbindung, ca. 8 Sekunden. Upload: 4 MB über Cloudflare (Hetzner bietet keinen Upload-Test). „Mit Anbieter-Stream“ misst zusätzlich 5 Sekunden einen Sender deines Anbieters."),
            color = p.textDim.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

/** Dünner Fortschritts-/Füllbalken mit weich laufendem Wert. */
@Composable
private fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val p = LocalUltraTheme.current.palette
    Box(modifier.height(height).clip(RoundedCornerShape(height)).background(p.surface.copy(alpha = 0.7f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(height)).background(color))
    }
}

@Composable
private fun CheckPane(vm: NetworkToolsViewModel) {
    val p = LocalUltraTheme.current.palette
    val rows by vm.checks.collectAsStateWithLifecycle()
    val busy by vm.checking.collectAsStateWithLifecycle()
    val current by vm.checkCurrent.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (rows.isEmpty()) vm.runChecks() }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton(if (busy) tr("Prüfe …") else tr("Erneut prüfen"), onClick = { vm.runChecks() }, primary = true, enabled = !busy)
            Text("${rows.size} / ${vm.checkTotal}", color = p.textDim, fontSize = 14.sp)
        }
        Spacer(Modifier.height(10.dp))
        // (7) Fortschrittsbalken
        val prog by animateFloatAsState(rows.size / vm.checkTotal.toFloat(), if (motionOk()) tween(300) else snap(), label = "chk")
        Bar(prog, if (!busy && rows.any { it.ok == false }) Warn else p.primary, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 60.dp)) {
            items(rows, key = { it.name }) { r ->
                // Fokussierbar, damit die Liste per Fernbedienung bis zum Ende scrollt
                Row(Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(12.dp), {}, scaleOnFocus = false)
                    .clip(RoundedCornerShape(12.dp)).background(p.surface.copy(alpha = 0.5f)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val (sym, col) = when (r.ok) { true -> "✓" to Good; false -> "✕" to Bad; null -> "–" to p.textDim }
                    // (6) Ergebnis ploppt auf
                    Box(Modifier.width(36.dp)) { Text(sym, color = col, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.popIn()) }
                    Column(Modifier.weight(1f)) {
                        Text(r.name, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        if (r.detail.isNotBlank()) Text(r.detail, color = p.textDim, fontSize = 13.sp, maxLines = 1)
                    }
                    r.ms?.let { Text("$it ms", color = if (it < 150) p.text else Warn, fontSize = 16.sp) }
                }
            }
            // (6) laufende Prüfung mit drehendem Ring
            val cur = current
            if (busy && cur != null && rows.none { it.name == cur }) item(key = "läuft:$cur") {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface.copy(alpha = 0.3f)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(36.dp)) {
                        if (motionOk()) CircularProgressIndicator(Modifier.size(20.dp), color = p.primary, strokeWidth = 2.5.dp)
                        else Text("…", color = p.textDim, fontSize = 20.sp)
                    }
                    Text(cur, color = p.textDim, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Text("prüfe …", color = p.textDim, fontSize = 14.sp)
                }
            }
        }
    }
}

/** (8) WLAN-Signalbalken, füllen sich nacheinander. */
@Composable
private fun WifiBars(rssi: Int) {
    val p = LocalUltraTheme.current.palette
    val level = when { rssi >= -55 -> 4; rssi >= -67 -> 3; rssi >= -75 -> 2; else -> 1 }
    val col = when (level) { 4, 3 -> Good; 2 -> Warn; else -> Bad }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 12.dp)) {
        (1..4).forEach { b ->
            val on = growTo(if (b <= level) 1f else 0f, 220, delayMs = 120L + b * 110L)
            Box(Modifier.width(5.dp).height((4 + b * 4).dp).clip(RoundedCornerShape(2.dp))
                .background(androidx.compose.ui.graphics.lerp(p.textDim.copy(alpha = 0.3f), col, on)))
        }
    }
}

@Composable
private fun InfoPane(vm: NetworkToolsViewModel) {
    val p = LocalUltraTheme.current.palette
    val rows by vm.info.collectAsStateWithLifecycle()
    val rssi by vm.wifiRssi.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadInfo() }
    Column {
        TvButton(tr("Aktualisieren"), onClick = { vm.loadInfo() }, primary = true)
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 60.dp)) {
            items(rows.size, key = { i -> rows[i].first + "#" + i }) { i ->
                val (k, v) = rows[i]
                // (9) gestaffeltes Einblenden
                Row(Modifier.fillMaxWidth().staggerIn(i).tvFocusable(RoundedCornerShape(8.dp), {}, scaleOnFocus = false).padding(vertical = 6.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(k, color = p.textDim, fontSize = 15.sp, modifier = Modifier.width(240.dp))
                    Text(v, color = p.text, fontSize = 15.sp)
                    if (k == tr("Signalstärke")) rssi?.let { WifiBars(it) }
                }
            }
        }
    }
}

/** (10) Speicherbalken, wachsen beim Öffnen auf ihren Wert; (11) Cache-Balken schrumpft beim Leeren. */
@Composable
private fun DiagBarRow(b: DiagBar, index: Int) {
    val p = LocalUltraTheme.current.palette
    val f = growTo(b.fraction, 700, delayMs = index * 90L)
    val col = when { b.id == "cache" -> p.primary; b.fraction > 0.85f -> Bad; b.fraction > 0.65f -> Warn; else -> p.primary }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(b.label, color = p.textDim, fontSize = 15.sp, modifier = Modifier.width(280.dp))
        Bar(f, col, Modifier.width(320.dp), height = 10.dp)
        Text("  ${b.text} · ${(b.fraction * 100).toInt()} %", color = p.text, fontSize = 14.sp)
    }
}

@Composable
private fun DiagPane(vm: NetworkToolsViewModel) {
    val p = LocalUltraTheme.current.palette
    val rows by vm.diag.collectAsStateWithLifecycle()
    val bars by vm.diagBars.collectAsStateWithLifecycle()
    val errors by vm.lastErrors.collectAsStateWithLifecycle()
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadDiag() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 60.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton(tr("Cache leeren"), onClick = { msg = null; vm.clearCache { msg = it } }, primary = true)
                TvButton(tr("Aktualisieren"), onClick = { vm.loadDiag() })
                if (errors != null) TvButton(tr("Fehler löschen"), onClick = { vm.clearErrors() })
                // (11) Bestätigung ploppt auf
                msg?.let { m -> androidx.compose.runtime.key(m) {
                    Text("✓ $m", color = Good, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.popIn())
                } }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (bars.isNotEmpty()) item {
            Column(Modifier.padding(bottom = 8.dp)) { bars.forEachIndexed { i, b -> androidx.compose.runtime.key(b.id) { DiagBarRow(b, i) } } }
        }
        items(rows.size) { i ->
            val (k, v) = rows[i]
            Row(Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(8.dp), {}, scaleOnFocus = false).padding(vertical = 6.dp, horizontal = 6.dp)) {
                Text(k, color = p.textDim, fontSize = 15.sp, modifier = Modifier.width(280.dp))
                Text(v, color = p.text, fontSize = 15.sp)
            }
        }
        item { ZapStats() }
        item {
            Text(tr("Letzte Fehler"), color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp).tvFocusable(RoundedCornerShape(8.dp), {}, scaleOnFocus = false))
            Text(errors ?: tr("Keine Abstürze oder Hänger aufgezeichnet."), color = if (errors != null) Warn else p.textDim, fontSize = 13.sp)
            Text(tr("Cache leeren löscht nur Zwischenspeicher (Bilder, Web). Favoriten, Einstellungen, Profile und Fortschritte bleiben erhalten."),
                color = p.textDim.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}


/** Auswertung der gemessenen Senderwechsel: Median, P95, P99 – getrennt nach Schnellstart an/aus. */
@Composable
private fun ZapStats() {
    val p = LocalUltraTheme.current.palette
    var recs by remember { mutableStateOf(de.ostlertekker.ultraiptv.core.player.ZapMonitor.all()) }
    val M = de.ostlertekker.ultraiptv.core.player.ZapMonitor
    Column(Modifier.padding(top = 14.dp)) {
        Text(tr("Senderwechsel-Messung"), color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.tvFocusable(RoundedCornerShape(8.dp), { recs = M.all() }, scaleOnFocus = false).padding(vertical = 4.dp))
        if (recs.isEmpty()) {
            Text(tr("Noch keine Messwerte – einfach im Live-TV ein paar Mal umschalten."), color = p.textDim, fontSize = 13.sp)
            return@Column
        }
        val groups = listOf(tr("Schnellstart an") to recs.filter { it.turbo }, tr("Schnellstart aus") to recs.filter { !it.turbo }).filter { it.second.isNotEmpty() }
        Row(Modifier.padding(top = 6.dp)) {
            Text(tr("Wert (ms)"), color = p.textDim, fontSize = 13.sp, modifier = Modifier.width(220.dp))
            groups.forEach { (g, l) -> Text(trf("{0} ({1}×)  Median · P95 · P99", g, l.size), color = p.textDim, fontSize = 13.sp, modifier = Modifier.width(300.dp)) }
        }
        M.METRICS.forEach { (name, sel) ->
            Row(Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(8.dp), {}, scaleOnFocus = false).padding(vertical = 3.dp)) {
                Text(name, color = p.textDim, fontSize = 14.sp, modifier = Modifier.width(220.dp))
                groups.forEach { (_, l) ->
                    val q = M.pct(l.map(sel))
                    Text(q?.let { "${it.median} · ${it.p95} · ${it.p99}" } ?: "–", color = p.text, fontSize = 14.sp, modifier = Modifier.width(300.dp))
                }
            }
        }
        groups.forEach { (g, l) ->
            val pred = l.filter { it.predicted }.map { it.total }
            val err = l.count { it.errors > 0 }
            Text(trf("{0}: Ø CPU {1} % · RAM ~{2} MB · Ø Daten {3} KB pro Wechsel · ", g, (l.map { it.cpuPct }.average().toInt()), (l.last().ramMb), (l.map { it.netKb }.average().toLong())) +
                trf("Fehler {0} · Wiederholungen {1} · Start-Puffer Median {2} ms", err, (l.sumOf { it.retries }), (M.pct(l.map { it.startBufferMs })?.median ?: "–")) +
                (M.pct(pred)?.let { trf(" · vorbereitete Sender: Median {0} ms ({1}×)", it.median, it.n) } ?: ""),
                color = p.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            TvButton(tr("Aktualisieren"), onClick = { recs = M.all() })
            TvButton(tr("Messwerte löschen"), onClick = { M.clear(); recs = emptyList() })
        }
    }
}
