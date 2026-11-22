package de.ostlertekker.ultraiptv.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lokaler Zeitversatz-Puffer für Live-TV (Pause, Zurückspulen, Vorspulen – bei jedem MPEG-TS-Sender).
 *
 * Eine einzige Verbindung zum Anbieter lädt den Live-Stream fortlaufend in einen Ringpuffer aus Dateiblöcken
 * im App-Cache. Der Player liest ausschließlich aus diesem Puffer. Zurückspulen = an eine frühere Byte-Position
 * springen; „Live“ = ans Pufferende. Ältestes wird automatisch gelöscht (Zeit- und Speichergrenze).
 */
class TimeshiftBuffer(
    private val root: File,
    private val url: String,
    private val userAgent: String,
    private val maxMs: Long,
    private val maxBytes: Long,
) {
    private class Chunk(val start: Long, val file: File) {
        var length = 0L
        var raf: RandomAccessFile? = null
    }

    /** Eigener Unterordner pro Puffer: das Aufräumen eines alten Puffers (im Hintergrund) kann den neuen nie treffen. */
    private val dir = File(root, "s" + System.nanoTime())
    /** Messpunkte für den Senderwechsel (Server antwortet / erste Daten). */
    @Volatile var onConnected: (() -> Unit)? = null
    @Volatile var onFirstData: (() -> Unit)? = null
    private var firstDataSent = false
    /** Wird ausgelöst, sobald die Anbieter-Verbindung dieses Puffers wirklich getrennt ist. */
    val disconnected = java.util.concurrent.CountDownLatch(1)
    /** Wird ausgelöst, wenn nach close() alle Dateien dieses Puffers gelöscht sind. */
    val cleaned = java.util.concurrent.CountDownLatch(1)

    private val lock = Object()
    private val chunks = ArrayDeque<Chunk>()
    /** Stützpunkte Zeit → Byte-Position (alle ~250 ms). */
    private val wallIdx = ArrayList<Long>()
    private val offIdx = ArrayList<Long>()

    @Volatile var endOffset = 0L; private set
    @Volatile var oldestOffset = 0L; private set
    @Volatile var closed = false; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var reconnects = 0; private set
    val startedAt = System.currentTimeMillis()

    private var writer: RandomAccessFile? = null
    private var thread: Thread? = null
    @Volatile private var conn: HttpURLConnection? = null

    /**
     * Startet den Download. [after] = Trennung des vorherigen Puffers: erst danach verbinden (viele Anbieter erlauben nur
     * eine Verbindung gleichzeitig), aber höchstens 500 ms warten.
     */
    fun start(after: java.util.concurrent.CountDownLatch? = null) {
        dir.mkdirs()
        ACTIVE.add(dir.name)
        // Reste früherer Puffer (z. B. nach Absturz) im Hintergrund löschen – nicht im Bedien-Thread
        CLEANUP.execute { root.listFiles()?.forEach { f -> if (f.name !in ACTIVE) f.deleteRecursively() } }
        thread = Thread({ runCatching { after?.await(500, java.util.concurrent.TimeUnit.MILLISECONDS) }; downloadLoop() }, "ultra-timeshift").apply { priority = Thread.NORM_PRIORITY; isDaemon = true; start() }
    }

    private fun downloadLoop() {
        val buf = ByteArray(64 * 1024)
        var backoff = 500L
        while (!closed) {
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000; readTimeout = 12_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent)
                }
                conn = c
                val code = c.responseCode
                if (code !in 200..299) { lastError = "HTTP $code"; c.disconnect(); sleepQuiet(backoff); backoff = (backoff * 2).coerceAtMost(8_000); continue }
                onConnected?.invoke()
                c.inputStream.use { input ->
                    backoff = 500L; lastError = null
                    while (!closed) {
                        val n = input.read(buf)
                        if (n < 0) break // Server hat beendet → neu verbinden, Puffer läuft nahtlos weiter
                        if (n > 0) append(buf, n)
                    }
                }
                c.disconnect()
            } catch (e: Exception) {
                if (closed) break
                lastError = e.javaClass.simpleName
            }
            if (!closed) { reconnects++; sleepQuiet(backoff); backoff = (backoff * 2).coerceAtMost(8_000) }
        }
    }

    private fun sleepQuiet(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    private fun append(b: ByteArray, n: Int) {
        synchronized(lock) {
            var cur = chunks.lastOrNull()
            if (cur == null || cur.length >= CHUNK) {
                runCatching { writer?.close() }
                cur = Chunk(endOffset, File(dir, "ts_${endOffset}.bin"))
                chunks.addLast(cur)
                writer = RandomAccessFile(cur.file, "rw")
            }
            val w = writer!!
            w.seek(cur.length)
            w.write(b, 0, n)
            cur.length += n
            endOffset += n
            val now = System.currentTimeMillis()
            if (wallIdx.isEmpty() || now - wallIdx.last() >= 250) { wallIdx.add(now); offIdx.add(endOffset) }
            trim(now)
            lock.notifyAll()
        }
        if (!firstDataSent) { firstDataSent = true; onFirstData?.invoke() }
    }

    /** Älteste Blöcke löschen (Zeit- und Speichergrenze). Ein Block bleibt immer. */
    private fun trim(now: Long) {
        while (chunks.size > 1) {
            val first = chunks.first()
            val firstEnd = first.start + first.length
            val tooOld = wallForOffsetLocked(firstEnd) < now - maxMs
            val tooBig = endOffset - first.start > maxBytes
            if (!tooOld && !tooBig) break
            chunks.removeFirst()
            runCatching { first.raf?.close() }
            first.file.delete()
            oldestOffset = chunks.first().start
            while (offIdx.size > 2 && offIdx[1] <= oldestOffset) { offIdx.removeAt(0); wallIdx.removeAt(0) }
        }
    }

    /** Liest ab [pos]; blockiert, bis Daten da sind. -1 = Puffer geschlossen. */
    fun read(pos: Long, out: ByteArray, off: Int, len: Int): Int {
        synchronized(lock) {
            while (!closed && pos >= endOffset) {
                try { lock.wait(250) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException() }
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            }
            if (closed) return -1
            val p = pos.coerceAtLeast(oldestOffset)
            val ch = chunks.lastOrNull { it.start <= p } ?: return -1
            val inChunk = p - ch.start
            val avail = (ch.length - inChunk).coerceAtMost(len.toLong()).toInt()
            if (avail <= 0) return 0
            val r = ch.raf ?: RandomAccessFile(ch.file, "r").also { ch.raf = it }
            r.seek(inChunk)
            return r.read(out, off, avail)
        }
    }

    /** Byte-Position für eine Wanduhrzeit (auf TS-Paketgrenze ausgerichtet). */
    fun offsetForWall(wall: Long): Long = synchronized(lock) {
        if (offIdx.isEmpty()) return 0L
        var i = wallIdx.binarySearch(wall)
        if (i < 0) i = (-i - 1).coerceAtMost(wallIdx.lastIndex)
        val o = offIdx[i].coerceIn(oldestOffset, endOffset)
        o - (o % 188)
    }

    fun wallForOffset(offset: Long): Long = synchronized(lock) { wallForOffsetLocked(offset) }

    private fun wallForOffsetLocked(offset: Long): Long {
        if (offIdx.isEmpty()) return System.currentTimeMillis()
        var i = offIdx.binarySearch(offset)
        if (i < 0) i = (-i - 1).coerceAtMost(offIdx.lastIndex)
        return wallIdx[i]
    }

    /** Ältester verfügbarer Zeitpunkt. */
    fun oldestWall(): Long = wallForOffset(oldestOffset)

    /** Verbindung neu aufbauen (vom Wächter, wenn nichts mehr ankommt). */
    fun kick() { runCatching { conn?.disconnect() } }

    /**
     * Beendet den Puffer sofort für den Player; Verbindung trennen und Dateien löschen laufen im Hintergrund
     * (vorher im Bedien-Thread – das Löschen eines großen Zeitversatz-Puffers verzögerte den Senderwechsel).
     */
    fun close() {
        closed = true
        thread?.interrupt()
        onConnected = null; onFirstData = null
        synchronized(lock) { lock.notifyAll() }
        // Trennen sofort auf eigenem Thread (der nächste Sender wartet darauf), Löschen danach in der Aufräum-Warteschlange
        DISCONNECT.execute { runCatching { conn?.disconnect() }; disconnected.countDown() }
        CLEANUP.execute {
            runCatching { disconnected.await(2, java.util.concurrent.TimeUnit.SECONDS) }
            synchronized(lock) {
                lock.notifyAll()
                runCatching { writer?.close() }
                chunks.forEach { runCatching { it.raf?.close() }; it.file.delete() }
                chunks.clear()
            }
            dir.deleteRecursively()
            ACTIVE.remove(dir.name)
            cleaned.countDown()
        }
    }

    companion object {
        const val CHUNK = 8L * 1024 * 1024
        private val CLEANUP = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ultra-timeshift-cleanup").apply { isDaemon = true } }
        private val DISCONNECT = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "ultra-timeshift-disconnect").apply { isDaemon = true } }
        private val ACTIVE: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    }
}

/** Liest aus dem Zeitversatz-Puffer: timeshift://live?o=<Byte-Position>. */
@UnstableApi
class TimeshiftDataSource(private val buffer: () -> TimeshiftBuffer?) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var pos = 0L
    private var buf: TimeshiftBuffer? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        buf = buffer() ?: throw java.io.IOException("Zeitversatz-Puffer nicht aktiv")
        transferInitializing(dataSpec)
        val base = dataSpec.uri.getQueryParameter("o")?.toLongOrNull() ?: 0L
        pos = (base + dataSpec.position).coerceAtLeast(buf!!.oldestOffset)
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val b = buf ?: return C.RESULT_END_OF_INPUT
        while (true) {
            if (pos < b.oldestOffset) pos = b.oldestOffset // zu weit zurück: beim ältesten Stand weiter
            val n = b.read(pos, buffer, offset, length)
            if (n < 0) return C.RESULT_END_OF_INPUT
            if (n > 0) { pos += n; bytesTransferred(n); return n }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (uri != null) { uri = null; transferEnded() }
        buf = null
    }
}

/** Leitet timeshift://-Adressen an den Puffer, alles andere an die normale Quelle. */
@UnstableApi
class RoutingDataSourceFactory(private val upstream: DataSource.Factory, private val buffer: () -> TimeshiftBuffer?) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingDataSource(upstream.createDataSource(), TimeshiftDataSource(buffer))
}

@UnstableApi
private class RoutingDataSource(private val normal: DataSource, private val ts: TimeshiftDataSource) : DataSource {
    private var active: DataSource? = null
    override fun addTransferListener(transferListener: TransferListener) { normal.addTransferListener(transferListener); ts.addTransferListener(transferListener) }
    override fun open(dataSpec: DataSpec): Long {
        val s = if (dataSpec.uri.scheme == "timeshift") ts else normal
        active = s
        return s.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active!!.read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
    override fun close() { active?.close(); active = null }
}
