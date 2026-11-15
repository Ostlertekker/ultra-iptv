package de.ostlertekker.ultraiptv.core.lan

import android.os.Build
import de.ostlertekker.ultraiptv.core.backup.BackupManager
import de.ostlertekker.ultraiptv.core.diagnostics.NetworkDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Was gerade auf dem Host-Gerät läuft (Watch Together). */
data class WatchState(
    val kind: String,
    val id: String,
    val category: String?,
    val title: String,
    val positionMs: Long,
    val playing: Boolean,
    val updatedAt: Long,
)

data class HubStatus(val running: Boolean, val address: String?, val port: Int, val code: String, val hostingWatch: Boolean)

/**
 * Gerätekopplung im Heimnetz (ohne Cloud):
 *  • Geräteübergreifende Synchronisation: anderes Gerät lädt Backup per Kopplungscode
 *  • Backup/Wiederherstellung über den Browser am Handy/PC (http://IP:Port)
 *  • Watch Together: Gäste folgen dem Host-Gerät (Inhalt, Position, Pause)
 * Der Server läuft nur, solange er in den Einstellungen aktiviert ist, und verlangt einen 6-stelligen Code.
 */
@Singleton
class LanHub @Inject constructor(
    private val backup: BackupManager,
    private val net: NetworkDiagnostics,
    http: OkHttpClient,
) {
    private val client = http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var pool = Executors.newFixedThreadPool(3)

    private val _status = MutableStateFlow(HubStatus(false, null, 0, "", false))
    val status: StateFlow<HubStatus> = _status.asStateFlow()

    @Volatile private var watch: WatchState? = null

    // ------------------------------------------------------------------ Server

    fun start(hostWatch: Boolean) {
        if (server != null) { _status.value = _status.value.copy(hostingWatch = hostWatch); return }
        val code = (100000 + SecureRandom().nextInt(900000)).toString()
        var socket: ServerSocket? = null
        for (port in 8765..8775) {
            socket = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }.getOrNull()
            if (socket != null) break
        }
        val s = socket ?: run { _status.value = HubStatus(false, null, 0, "", false); return }
        server = s
        if (pool.isShutdown) pool = Executors.newFixedThreadPool(3)
        _status.value = HubStatus(true, net.localIpv4(), s.localPort, code, hostWatch)
        Thread({
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { handle(c) }
            }
        }, "ultra-lan").start()
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        watch = null
        _status.value = HubStatus(false, null, 0, "", false)
    }

    /** Vom Player aufgerufen, solange Watch Together als Host aktiv ist. */
    fun publishWatch(state: WatchState?) { if (_status.value.hostingWatch) watch = state }

    private fun handle(sock: Socket) {
        sock.use { c ->
            c.soTimeout = 15_000
            val input = BufferedInputStream(c.getInputStream())
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            val query = parts[1].substringAfter('?', "").split('&').filter { '=' in it }
                .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len > 30 * 1024 * 1024) { respond(c.getOutputStream(), 413, "text/plain", "Zu groß"); return }
            val body = if (len > 0) ByteArray(len).also { b -> var off = 0; while (off < len) { val n = input.read(b, off, len - off); if (n < 0) break; off += n } } else ByteArray(0)
            val out = c.getOutputStream()
            val st = _status.value
            val authorized = query["code"] == st.code
            when {
                path == "/ping" -> respond(out, 200, "application/json", buildJsonObject {
                    put("app", "ULTRA IPTV"); put("device", Build.MODEL); put("watch", st.hostingWatch)
                }.toString())
                path == "/" || path == "/panel" -> respond(out, 200, "text/html; charset=utf-8", page(authorized, st.code.takeIf { authorized }))
                !authorized -> respond(out, 403, "application/json", """{"error":"Falscher Kopplungscode"}""")
                path == "/backup" && method == "GET" -> {
                    val json = runBlocking { backup.export(query["accounts"] == "1") }.toString()
                    respond(out, 200, "application/json; charset=utf-8", json, "attachment; filename=\"ultra-backup.json\"")
                }
                path == "/restore" && method == "POST" -> {
                    val r = runCatching { runBlocking { backup.restore(String(body, Charsets.UTF_8)) } }
                    r.onSuccess { res -> respond(out, 200, "application/json", """{"ok":true,"profiles":${res.profiles},"favorites":${res.favorites}}""") }
                        .onFailure { e -> respond(out, 400, "application/json", buildJsonObject { put("error", e.message ?: "Fehler") }.toString()) }
                }
                path == "/watch" -> {
                    val w = watch
                    if (w == null || !st.hostingWatch) respond(out, 204, "application/json", "")
                    else respond(out, 200, "application/json", buildJsonObject {
                        put("kind", w.kind); put("id", w.id); put("category", w.category); put("title", w.title)
                        put("pos", w.positionMs + if (w.playing) System.currentTimeMillis() - w.updatedAt else 0L); put("playing", w.playing)
                    }.toString())
                }
                else -> respond(out, 404, "text/plain", "Nicht gefunden")
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String, disposition: String? = null) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        val head = buildString {
            append("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n")
            if (disposition != null) append("Content-Disposition: $disposition\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8)); out.write(bytes); out.flush()
    }

    private fun page(authorized: Boolean, code: String?): String = """<!DOCTYPE html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>ULTRA IPTV</title>
<style>body{font-family:system-ui,sans-serif;background:#070a1f;color:#f4f1ff;max-width:560px;margin:40px auto;padding:0 16px}
h1{letter-spacing:3px}small{color:#a9a6c9}input,button{font-size:17px;padding:10px;border-radius:8px;border:1px solid #444;margin:6px 0}
button{background:#a77bff;color:#000;border:0;font-weight:700}a{color:#5bc8ff}.card{background:#141a3a;padding:16px;border-radius:12px;margin:14px 0}</style></head><body>
<small>OSTLERTEKKER PRESENTS</small><h1>ULTRA IPTV</h1>
${if (!authorized) """<div class="card"><form action="/panel"><p>Kopplungscode vom Fernseher eingeben:</p>
<input name="code" inputmode="numeric" maxlength="6" autofocus> <button>Verbinden</button></form></div>""" else """
<div class="card"><h3>Backup herunterladen</h3><p><a href="/backup?code=$code">Ohne Zugangsdaten</a> · <a href="/backup?code=$code&accounts=1">Mit Anbieter-Konten</a></p>
<small>Mit Zugangsdaten nur auf vertrauenswürdigen Geräten speichern.</small></div>
<div class="card"><h3>Backup wiederherstellen</h3><input type="file" id="f" accept=".json"><br><button onclick="up()">Hochladen &amp; wiederherstellen</button><p id="r"></p></div>
<script>function up(){const f=document.getElementById('f').files[0];if(!f)return;fetch('/restore?code=$code',{method:'POST',body:f}).then(r=>r.json()).then(j=>document.getElementById('r').textContent=j.ok?'Wiederhergestellt ✓':'Fehler: '+j.error)}</script>"""}
</body></html>"""

    // ------------------------------------------------------------------ Client

    data class Remote(val host: String, val port: Int, val code: String) {
        val base get() = "http://$host:$port"
    }

    suspend fun fetchBackup(remote: Remote, withAccounts: Boolean): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url("${remote.base}/backup?code=${remote.code}&accounts=${if (withAccounts) 1 else 0}").build()).execute().use { r ->
            if (r.code == 403) error("Falscher Kopplungscode")
            if (!r.isSuccessful) error("Gerät antwortet mit HTTP ${r.code}")
            r.body?.string() ?: error("Leere Antwort")
        }
    }

    /** Übernimmt Daten von einem anderen ULTRA-IPTV-Gerät im Heimnetz. */
    suspend fun syncFrom(remote: Remote, withAccounts: Boolean) = backup.restore(fetchBackup(remote, withAccounts))

    private val _guest = MutableStateFlow<WatchState?>(null)
    /** Zustand des Hosts, dem dieses Gerät als Gast folgt. */
    val guestState: StateFlow<WatchState?> = _guest.asStateFlow()
    private val _guestRemote = MutableStateFlow<Remote?>(null)
    val guestRemote: StateFlow<Remote?> = _guestRemote.asStateFlow()
    private var guestJob: Job? = null

    fun joinWatch(remote: Remote) {
        leaveWatch()
        _guestRemote.value = remote
        guestJob = scope.launch {
            while (isActive) {
                runCatching {
                    client.newCall(Request.Builder().url("${remote.base}/watch?code=${remote.code}").build()).execute().use { r ->
                        if (r.code == 204) _guest.value = null
                        else if (r.isSuccessful) {
                            val o = Json.parseToJsonElement(r.body?.string().orEmpty()) as JsonObject
                            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                            _guest.value = WatchState(s("kind") ?: "LIVE", s("id") ?: "", s("category")?.takeIf { it != "null" }, s("title") ?: "",
                                (o["pos"] as? JsonPrimitive)?.longOrNull ?: 0, (o["playing"] as? JsonPrimitive)?.booleanOrNull ?: true, System.currentTimeMillis())
                        }
                        Unit
                    }
                }
                delay(2000)
            }
        }
    }

    fun leaveWatch() {
        guestJob?.cancel(); guestJob = null
        _guest.value = null; _guestRemote.value = null
    }
}
