package de.ostlertekker.ultraiptv.feature.tools

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
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
)

data class CheckRow(val name: String, val ok: Boolean?, val ms: Long? = null, val detail: String = "")

/** Bewertung der Download-Rate für Streaming (Richtwerte für einen gleichzeitigen Stream). */
fun streamRating(mbps: Double): String = when {
    mbps >= 25 -> "Reicht für 4K / UHD"
    mbps >= 10 -> "Reicht für Full HD, für 4K knapp"
    mbps >= 5 -> "Reicht für HD"
    mbps >= 2.5 -> "Reicht für SD, HD kann ruckeln"
    else -> "Zu langsam für flüssiges Streaming"
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
) : ViewModel() {
    // Eigener Client: kein Cache, getrennte Verbindungen für parallele Messungen
    private val http = baseClient.newBuilder().cache(null).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).build()
    private val base = "https://speed.cloudflare.com"

    val speed = MutableStateFlow(SpeedState())
    val checks = MutableStateFlow<List<CheckRow>>(emptyList())
    val checking = MutableStateFlow(false)
    val info = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    private var speedJob: Job? = null

    // ---------------------------------------------------------------- Speedtest (Cloudflare-Messserver)

    fun startSpeedtest() {
        speedJob?.cancel()
        speedJob = viewModelScope.launch(Dispatchers.IO) {
            speed.value = SpeedState(running = true, phase = "Verbinde …")
            try {
                val meta = runCatching { meta() }.getOrNull()
                val server = meta?.let { m -> listOfNotNull(m.optString("colo").ifBlank { null }?.let { "Cloudflare $it" },
                    m.optString("city").ifBlank { null }).joinToString(" · ") }
                speed.value = speed.value.copy(server = server, phase = "Ping wird gemessen …")
                val (ping, jitter) = ping()
                speed.value = speed.value.copy(pingMs = ping, jitterMs = jitter, phase = "Download wird gemessen …")
                val down = transfer(upload = false) { live -> speed.value = speed.value.copy(live = live) }
                speed.value = speed.value.copy(downMbps = down, live = null, phase = "Upload wird gemessen …")
                val up = transfer(upload = true) { live -> speed.value = speed.value.copy(live = live) }
                speed.value = speed.value.copy(upMbps = up, live = null, running = false, phase = "Fertig")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                speed.value = speed.value.copy(running = false, live = null, phase = "", error = "Test fehlgeschlagen: ${e.message ?: "keine Verbindung"}")
            }
        }
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
                if (!go()) throw java.io.IOException("Messzeit vorbei")
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
            fun publish(r: CheckRow) { rows += r; checks.value = rows.toList() }
            checks.value = emptyList()
            // Internet
            publish(probe("Internet", "https://cp.cloudflare.com/generate_204"))
            // Namensauflösung (DNS)
            publish(run {
                val t0 = System.nanoTime()
                runCatching { java.net.InetAddress.getByName("example.org") }.fold(
                    { CheckRow("Namensauflösung (DNS)", true, (System.nanoTime() - t0) / 1_000_000, it.hostAddress ?: "") },
                    { CheckRow("Namensauflösung (DNS)", false, detail = it.message ?: "Fehler") })
            })
            // IPTV-Anbieter (nur Serveradresse, keine Zugangsdaten)
            val cfg = runCatching { creds.load() }.getOrNull()
            val providerUrl = cfg?.let { c -> (c.server.ifBlank { c.m3uUrl }).takeIf { it.isNotBlank() } }
            publish(if (providerUrl == null) CheckRow("IPTV-Anbieter", null, detail = "Keine Quelle eingerichtet")
                else probe("IPTV-Anbieter", originOf(providerUrl), anyHttpIsOk = true))
            // Jellyfin
            val jf = jellyfin.session.value ?: runCatching { jellyfin.restore() }.getOrNull()
            publish(if (jf == null) CheckRow("Jellyfin", null, detail = "Nicht angemeldet")
                else probe("Jellyfin", jf.server.trimEnd('/') + "/System/Info/Public"))
            // Seerr
            val st = seerr.state.value
            publish(if (!st.loggedIn) CheckRow("Seerr", null, detail = "Nicht verbunden")
                else probe("Seerr", st.server.trimEnd('/') + "/api/v1/status"))
            // Filterlisten (Browser-Werbeblocker)
            publish(probe("Filterlisten (Werbeblocker)", "https://easylist.to/", anyHttpIsOk = true))
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
                if (r.isSuccessful || r.code == 204 || anyHttpIsOk) CheckRow(name, true, ms, "$host · Antwort ${r.code}")
                else CheckRow(name, false, ms, "$host · Fehler ${r.code}")
            }
        }.getOrElse { CheckRow(name, false, detail = "$host · " + (it.message ?: it.javaClass.simpleName)) }
    }

    // ---------------------------------------------------------------- App-Diagnose

    val diag = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val lastErrors = MutableStateFlow<String?>(null)

    private fun sizeOf(f: java.io.File?): Long = when {
        f == null || !f.exists() -> 0L
        f.isFile -> f.length()
        else -> f.listFiles()?.sumOf { sizeOf(it) } ?: 0L
    }

    private fun mb(b: Long) = "%.1f MB".format(java.util.Locale.GERMANY, b / 1_048_576.0)

    fun loadDiag() {
        viewModelScope.launch(Dispatchers.IO) {
            val out = ArrayList<Pair<String, String>>()
            out += "App-Version" to "${de.ostlertekker.ultraiptv.BuildConfig.VERSION_NAME} (${de.ostlertekker.ultraiptv.BuildConfig.VERSION_CODE})"
            out += "Gerät" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            out += "Android" to "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
            val rt = Runtime.getRuntime()
            out += "Arbeitsspeicher der App" to "${mb(rt.totalMemory() - rt.freeMemory())} von max. ${mb(rt.maxMemory())}"
            ctx.getSystemService(android.app.ActivityManager::class.java)?.let { am ->
                val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
                out += "Arbeitsspeicher des Geräts" to "${mb(mi.availMem)} frei von ${mb(mi.totalMem)}" + if (mi.lowMemory) " · knapp!" else ""
            }
            out += "Cache (Bilder, Web …)" to mb(sizeOf(ctx.cacheDir))
            val dbDir = ctx.getDatabasePath("x").parentFile
            out += "Datenbank (Sender, Filme, EPG)" to mb(sizeOf(dbDir))
            out += "App-Daten gesamt" to mb(sizeOf(ctx.filesDir) + sizeOf(dbDir) + sizeOf(ctx.cacheDir))
            runCatching { android.os.StatFs(ctx.filesDir.path) }.getOrNull()?.let { st ->
                out += "Freier Gerätespeicher" to "${mb(st.availableBytes)} von ${mb(st.totalBytes)}"
            }
            out += "Software-Tondecoder" to (if (runCatching { androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable() }.getOrDefault(false)) "aktiv" else "nicht verfügbar")
            diag.value = out
            lastErrors.value = listOfNotNull(
                de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard.lastCrash(ctx)?.let { "ABSTURZ\n$it" },
                de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard.lastFreeze(ctx)?.let { "HÄNGER\n$it" },
            ).joinToString("\n\n").lines().filter { it.isNotBlank() }.take(14).joinToString("\n").ifBlank { null }
        }
    }

    /** Cache leeren: Bild-Zwischenspeicher, Web-Cache und temporäre Dateien. Favoriten, Einstellungen, Profile bleiben. */
    fun clearCache(onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val before = sizeOf(ctx.cacheDir)
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
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val type = when {
                caps == null -> "Keine Verbindung"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "LAN (Kabel)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WLAN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobilfunk"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Sonstige"
            }
            out += "Verbindung" to type
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) out += "VPN" to "aktiv"
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                @Suppress("DEPRECATION") val wi = runCatching { ctx.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo }.getOrNull()
                if (wi != null) {
                    val f = wi.frequency
                    if (f > 0) out += "WLAN-Band" to when { f >= 5925 -> "6 GHz"; f >= 4900 -> "5 GHz"; else -> "2,4 GHz" } + " (Kanal-Frequenz $f MHz)"
                    val rssi = wi.rssi
                    out += "Signalstärke" to "$rssi dBm · " + when { rssi >= -55 -> "sehr gut"; rssi >= -67 -> "gut"; rssi >= -75 -> "mittel"; else -> "schwach" }
                    if (wi.linkSpeed > 0) out += "Verbindungsrate" to "${wi.linkSpeed} Mbit/s"
                }
            }
            caps?.let { c ->
                if (c.linkDownstreamBandwidthKbps > 0) out += "Geschätzte Bandbreite" to "↓ ${c.linkDownstreamBandwidthKbps / 1000} / ↑ ${c.linkUpstreamBandwidthKbps / 1000} Mbit/s"
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
                m.optString("clientIp").ifBlank { null }?.let { out += "Öffentliche IP" to it }
                m.optString("asOrganization").ifBlank { null }?.let { out += "Internetanbieter" to it }
                listOfNotNull(m.optString("city").ifBlank { null }, m.optString("country").ifBlank { null }).takeIf { it.isNotEmpty() }
                    ?.let { out += "Standort (laut IP)" to it.joinToString(", ") }
            }.onFailure { out += "Öffentliche IP" to "nicht ermittelbar (kein Internet?)" }
            info.value = out.toList()
        }
    }
}

// =====================================================================================================
// Oberfläche
// =====================================================================================================

private enum class Tool(val label: String) { SPEED("Speedtest"), CHECK("Verbindungs-Check"), INFO("Netzwerk-Infos"), DIAG("App-Diagnose") }

/** „Mehr“: Netzwerk-Werkzeuge. */
@Composable
fun ToolsScreen(vm: NetworkToolsViewModel = hiltViewModel()) {
    val p = LocalUltraTheme.current.palette
    var tool by remember { mutableStateOf(Tool.SPEED) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
        ScreenTitle("Tools", subtitle = "Netzwerk prüfen und App-Zustand – Geschwindigkeit, Erreichbarkeit, Verbindungsdaten, Diagnose")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tool.entries.forEach { t -> TvChip(t.label, tool == t, { tool = t }) }
        }
        Spacer(Modifier.height(18.dp))
        when (tool) {
            Tool.SPEED -> SpeedPane(vm)
            Tool.CHECK -> CheckPane(vm)
            Tool.INFO -> InfoPane(vm)
            Tool.DIAG -> DiagPane(vm)
        }
    }
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
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat("Ping", fmt(s.pingMs, 0), "ms", Modifier.width(200.dp))
            Stat("Download", fmt(if (s.downMbps == null && s.phase.startsWith("Download")) s.live else s.downMbps), "Mbit/s", Modifier.width(260.dp))
            Stat("Upload", fmt(if (s.upMbps == null && s.phase.startsWith("Upload")) s.live else s.upMbps), "Mbit/s", Modifier.width(260.dp))
        }
        Spacer(Modifier.height(14.dp))
        val sub = listOfNotNull(s.phase.ifBlank { null }, s.jitterMs?.let { "Jitter ${fmt(it, 0)} ms" }, s.server?.let { "Messserver: $it" }).joinToString(" · ")
        if (sub.isNotBlank()) Text(sub, color = p.textDim, fontSize = 15.sp)
        s.downMbps?.let { Text(streamRating(it), color = p.primary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
        s.error?.let { Text("⚠ $it", color = Color(0xFFFF8A80), fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(18.dp))
        TvButton(if (s.running) "Test läuft …" else if (s.downMbps != null) "Erneut testen" else "Test starten",
            onClick = { if (!s.running) vm.startSpeedtest() }, primary = true, enabled = !s.running)
        Text("Gemessen wird zum nächstgelegenen Cloudflare-Server (etwa 20 Sekunden, bis zu einige 100 MB Datenverbrauch bei schnellen Leitungen).",
            color = p.textDim.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun CheckPane(vm: NetworkToolsViewModel) {
    val p = LocalUltraTheme.current.palette
    val rows by vm.checks.collectAsStateWithLifecycle()
    val busy by vm.checking.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (rows.isEmpty()) vm.runChecks() }
    Column {
        TvButton(if (busy) "Prüfe …" else "Erneut prüfen", onClick = { vm.runChecks() }, primary = true, enabled = !busy)
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 60.dp)) {
            items(rows, key = { it.name }) { r ->
                // Fokussierbar, damit die Liste per Fernbedienung bis zum Ende scrollt
                Row(Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(12.dp), {}, scaleOnFocus = false)
                    .clip(RoundedCornerShape(12.dp)).background(p.surface.copy(alpha = 0.5f)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val (sym, col) = when (r.ok) { true -> "✓" to Color(0xFF4ADE80); false -> "✕" to Color(0xFFFF6B6B); null -> "–" to p.textDim }
                    Box(Modifier.width(36.dp)) { Text(sym, color = col, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    Column(Modifier.weight(1f)) {
                        Text(r.name, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        if (r.detail.isNotBlank()) Text(r.detail, color = p.textDim, fontSize = 13.sp, maxLines = 1)
                    }
                    r.ms?.let { Text("$it ms", color = if (it < 150) p.text else Color(0xFFFFC857), fontSize = 16.sp) }
                }
            }
        }
    }
}

@Composable
private fun InfoPane(vm: NetworkToolsViewModel) {
    val p = LocalUltraTheme.current.palette
    val rows by vm.info.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadInfo() }
    Column {
        TvButton("Aktualisieren", onClick = { vm.loadInfo() }, primary = true)
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 60.dp)) {
            items(rows.size) { i ->
                val (k, v) = rows[i]
                Row(Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(8.dp), {}, scaleOnFocus = false).padding(vertical = 6.dp, horizontal = 6.dp)) {
                    Text(k, color = p.textDim, fontSize = 15.sp, modifier = Modifier.width(240.dp))
                    Text(v, color = p.text, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun DiagPane(vm: NetworkToolsViewModel) {
    val p = LocalUltraTheme.current.palette
    val rows by vm.diag.collectAsStateWithLifecycle()
    val errors by vm.lastErrors.collectAsStateWithLifecycle()
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadDiag() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 60.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton("Cache leeren", onClick = { vm.clearCache { msg = it } }, primary = true)
                TvButton("Aktualisieren", onClick = { vm.loadDiag() })
                if (errors != null) TvButton("Fehler löschen", onClick = { vm.clearErrors() })
                msg?.let { Text(it, color = p.primary, fontSize = 15.sp) }
            }
            Spacer(Modifier.height(10.dp))
        }
        items(rows.size) { i ->
            val (k, v) = rows[i]
            Row(Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(8.dp), {}, scaleOnFocus = false).padding(vertical = 6.dp, horizontal = 6.dp)) {
                Text(k, color = p.textDim, fontSize = 15.sp, modifier = Modifier.width(280.dp))
                Text(v, color = p.text, fontSize = 15.sp)
            }
        }
        item {
            Text("Letzte Fehler", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp).tvFocusable(RoundedCornerShape(8.dp), {}, scaleOnFocus = false))
            Text(errors ?: "Keine Abstürze oder Hänger aufgezeichnet.", color = if (errors != null) Color(0xFFFFC857) else p.textDim, fontSize = 13.sp)
            Text("Cache leeren löscht nur Zwischenspeicher (Bilder, Web). Favoriten, Einstellungen, Profile und Fortschritte bleiben erhalten.",
                color = p.textDim.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}
