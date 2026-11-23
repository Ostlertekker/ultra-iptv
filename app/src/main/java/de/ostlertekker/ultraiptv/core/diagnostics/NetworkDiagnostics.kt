package de.ostlertekker.ultraiptv.core.diagnostics

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ostlertekker.ultraiptv.core.data.ContentRepository
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.data.SyncRepository
import de.ostlertekker.ultraiptv.core.database.ChannelEntity
import de.ostlertekker.ultraiptv.core.database.ChannelHealthEntity
import de.ostlertekker.ultraiptv.core.database.ExtrasDao
import de.ostlertekker.ultraiptv.core.model.Accuracy
import de.ostlertekker.ultraiptv.core.model.InfoEntry
import de.ostlertekker.ultraiptv.core.model.ProviderType
import de.ostlertekker.ultraiptv.core.network.SafeLog
import de.ostlertekker.ultraiptv.core.network.XtreamUrls
import de.ostlertekker.ultraiptv.core.security.CredentialStore
import de.ostlertekker.ultraiptv.core.security.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class SpeedResult(
    val pingMs: Long?,
    val jitterMs: Long?,
    val downloadMbps: Double?,
    val uploadMbps: Double?,
    val providerMbps: Double?,
    val server: String,
    val location: String?,
    val error: String? = null,
) {
    /** Empfehlung, welche Qualität die Leitung trägt. */
    val verdict: String
        get() {
            val d = downloadMbps ?: return tr("Keine Messung")
            return when {
                d >= 40 -> tr("Sehr gut – 4K-Streams flüssig möglich")
                d >= 20 -> tr("Gut – 4K meist möglich, Full HD problemlos")
                d >= 8 -> tr("Ausreichend für Full HD")
                d >= 4 -> "Nur HD/720p stabil – Puffer „Groß“ empfohlen"
                else -> tr("Zu langsam für stabiles Streaming")
            }
        }
}

sealed interface SpeedTestState {
    data object Idle : SpeedTestState
    data class Running(val phase: String, val progress: Float) : SpeedTestState
    data class Done(val result: SpeedResult) : SpeedTestState
}

/**
 * Netzwerk-Diagnose: Verbindungsart, lokale IP, DNS-/TCP-/HTTP-Zeiten zum Anbieter, Kontostatus
 * und ein echter Geschwindigkeitstest (Cloudflare-Speedtest-Endpunkte + optional Anbieter-Durchsatz).
 */
@Singleton
class NetworkDiagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
    http: OkHttpClient,
    private val creds: CredentialStore,
    private val sync: SyncRepository,
) {
    private val client = http.newBuilder().readTimeout(20, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()

    private val _speed = MutableStateFlow<SpeedTestState>(SpeedTestState.Idle)
    val speed: StateFlow<SpeedTestState> = _speed.asStateFlow()

    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun timed(block: () -> Unit): Long { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000 }

    suspend fun connectionReport(): List<InfoEntry> = withContext(Dispatchers.IO) {
        val out = ArrayList<InfoEntry>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val type = when {
            caps == null -> tr("Keine Verbindung")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> tr("Ethernet (LAN)")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WLAN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> tr("Mobilfunk")
            else -> tr("Andere")
        }
        out += InfoEntry("Verbindungsart", type, Accuracy.MEASURED)
        if (caps != null) {
            out += InfoEntry(tr("Bandbreite laut System"), "↓ ${caps.linkDownstreamBandwidthKbps / 1000} Mbit/s · ↑ ${caps.linkUpstreamBandwidthKbps / 1000} Mbit/s", Accuracy.ESTIMATED)
            out += InfoEntry(tr("Internet bestätigt"), if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "Ja" else tr("Nein"), Accuracy.MEASURED)
        }
        out += InfoEntry(tr("Lokale IP-Adresse"), localIpv4() ?: "unbekannt", if (localIpv4() != null) Accuracy.MEASURED else Accuracy.UNAVAILABLE)
        // Internet + Standort (Cloudflare-Trace)
        try {
            var trace = ""
            val ms = timed { client.newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build()).execute().use { trace = it.body?.string().orEmpty() } }
            val loc = trace.lines().firstOrNull { it.startsWith("loc=") }?.substringAfter('=')
            out += InfoEntry("Internet-Erreichbarkeit", "OK ($ms ms)", Accuracy.MEASURED)
            if (loc != null) out += InfoEntry(tr("Erkannter Standort (öffentliche IP)"), loc, Accuracy.MEASURED)
        } catch (e: Exception) {
            out += InfoEntry("Internet-Erreichbarkeit", trf("Fehler: {0}", e.message), Accuracy.MEASURED)
        }
        // Anbieter-Server
        val cfg = creds.load()
        if (cfg == null) {
            out += InfoEntry("Anbieter", tr("Keine Quelle eingerichtet"), Accuracy.UNAVAILABLE)
            return@withContext out
        }
        val base = if (cfg.type == ProviderType.XTREAM) XtreamUrls.normalizeServer(cfg.server) else cfg.m3uUrl
        val uri = runCatching { URI(base) }.getOrNull()
        val host = uri?.host
        if (host != null) {
            try {
                var addrs: Array<InetAddress> = emptyArray()
                val dnsMs = timed { addrs = InetAddress.getAllByName(host) }
                out += InfoEntry(tr("DNS-Auflösung Anbieter"), "$dnsMs ms → ${addrs.firstOrNull()?.hostAddress ?: "?"}", Accuracy.MEASURED)
                val port = uri?.port?.takeIf { it > 0 } ?: if (uri?.scheme == "https") 443 else 80
                val tcpMs = timed { Socket().use { it.connect(InetSocketAddress(addrs.first(), port), 8000) } }
                out += InfoEntry(trf("TCP-Verbindung Anbieter (Port {0})", port), "$tcpMs ms", Accuracy.MEASURED)
            } catch (e: Exception) {
                out += InfoEntry("Anbieter-Server", "Nicht erreichbar: ${SafeLog.redact(e.message ?: e.javaClass.simpleName)}", Accuracy.MEASURED)
            }
        }
        if (cfg.type == ProviderType.XTREAM) {
            val t0 = System.currentTimeMillis()
            val info = sync.refreshAccountInfo()
            val apiMs = System.currentTimeMillis() - t0
            if (info == null) out += InfoEntry("Xtream-Anmeldung", trf("Fehlgeschlagen ({0} ms)", apiMs), Accuracy.MEASURED)
            else {
                out += InfoEntry("Xtream-Anmeldung", trf("OK ({0} ms) · Status: {1}", apiMs, (info.status ?: "?")), Accuracy.MEASURED)
                out += InfoEntry(tr("Konto läuft ab"), info.expiresAt?.let { DateFormat.getDateInstance().format(Date(it)) } ?: "unbegrenzt / nicht angegeben",
                    if (info.expiresAt != null) Accuracy.MEASURED else Accuracy.UNAVAILABLE)
                out += InfoEntry("Verbindungen (aktiv / max.)", "${info.activeConnections ?: "?"} / ${info.maxConnections ?: "?"}", Accuracy.MEASURED)
                if (info.isTrial) out += InfoEntry("Testzugang", "Ja", Accuracy.MEASURED)
            }
        }
        out
    }

    /** Geschwindigkeitstest: Ping/Jitter, Download, Upload (Cloudflare) und optional Durchsatz eines Anbieter-Streams. */
    suspend fun runSpeedTest(providerStreamUrl: String?): SpeedResult = withContext(Dispatchers.IO) {
        val base = "https://speed.cloudflare.com"
        try {
            _speed.value = SpeedTestState.Running("Latenz", 0.05f)
            val pings = (0 until 6).map {
                timed { client.newCall(Request.Builder().url("$base/__down?bytes=0").build()).execute().use { r -> r.body?.bytes() } }
            }.drop(1)
            val ping = pings.minOrNull()
            val jitter = if (pings.size > 1) pings.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average().toLong() else null
            var loc: String? = null
            runCatching { client.newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build()).execute().use { r ->
                loc = r.body?.string()?.lines()?.firstOrNull { it.startsWith("loc=") }?.substringAfter('=') } }

            _speed.value = SpeedTestState.Running("Download", 0.2f)
            val down = measureDownload("$base/__down?bytes=50000000", 8_000) { p -> _speed.value = SpeedTestState.Running("Download", 0.2f + 0.45f * p) }

            _speed.value = SpeedTestState.Running("Upload", 0.7f)
            val payload = ByteArray(4 * 1024 * 1024)
            val upMs = timed {
                client.newCall(Request.Builder().url("$base/__up").post(payload.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use { it.body?.bytes() }
            }
            val up = payload.size * 8.0 / 1_000_000 / (upMs / 1000.0)

            var provider: Double? = null
            if (providerStreamUrl != null) {
                _speed.value = SpeedTestState.Running("Anbieter-Stream", 0.85f)
                provider = runCatching { measureDownload(providerStreamUrl, 5_000) { } }.getOrNull()
            }
            SpeedResult(ping, jitter, down, up, provider, "Cloudflare", loc).also { _speed.value = SpeedTestState.Done(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SpeedResult(null, null, null, null, null, "Cloudflare", null, e.message).also { _speed.value = SpeedTestState.Done(it) }
        }
    }

    /** Lädt höchstens [maxMs] lang und liefert Mbit/s. */
    private suspend fun measureDownload(url: String, maxMs: Long, onProgress: (Float) -> Unit): Double {
        val start = System.nanoTime()
        var bytes = 0L
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val input = resp.body?.byteStream() ?: error(tr("Leere Antwort"))
            val buf = ByteArray(64 * 1024)
            while (coroutineContext.isActive) {
                val n = input.read(buf)
                if (n < 0) break
                bytes += n
                val el = (System.nanoTime() - start) / 1_000_000
                onProgress((el.toFloat() / maxMs).coerceIn(0f, 1f))
                if (el >= maxMs) break
            }
        }
        val sec = (System.nanoTime() - start) / 1e9
        return bytes * 8.0 / 1_000_000 / sec.coerceAtLeast(0.001)
    }
}
