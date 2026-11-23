package de.ostlertekker.ultraiptv.core.player

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.net.TrafficStats

/**
 * Misst jeden Senderwechsel (Live-TV) von der Tastendruck-Zeit bis Bild und Ton laufen.
 *
 * Zeitpunkte (ms ab Tastendruck):
 *  request  – Stream-Anfrage abgeschickt (Player vorbereitet bzw. Zeitversatz-Puffer startet)
 *  connect  – Server hat geantwortet (Antwortkopf)
 *  data     – erste Stream-Daten empfangen
 *  format   – Videoformat erkannt (Stream-Kopf/Keyframe gefunden, Decoder kann loslegen)
 *  frame    – erstes sichtbares Videobild
 *  audio    – Ton läuft hörbar
 *  total    – Bild und Ton laufen (Umschaltzeit gesamt)
 * Dazu CPU-Anteil der App während des Wechsels, Arbeitsspeicher, empfangene Daten, Fehler/Wiederholungen.
 *
 * Nur Zeitstempel und Zahlen – keine Adressen, Namen nur gekürzt für die Auswertung. Die letzten 300 Wechsel bleiben gespeichert.
 */
object ZapMonitor {
    data class Record(
        val label: String,
        val turbo: Boolean,
        val predicted: Boolean,
        val request: Long, val connect: Long, val data: Long, val format: Long,
        val decoderInitMs: Long, val frame: Long, val audio: Long, val total: Long,
        val cpuPct: Int, val ramMb: Int, val netKb: Long, val errors: Int, val retries: Int, val startBufferMs: Long,
    )

    private class Active(val label: String, val turbo: Boolean, val predicted: Boolean, val t0: Long, val cpu0: Long, val rx0: Long) {
        var request = -1L; var connect = -1L; var data = -1L; var format = -1L
        var decoderInit = -1L; var frame = -1L; var audio = -1L
        var errors = 0; var retries = 0; var startBufferMs = -1L
        var done = false
    }

    private const val MAX = 300
    private val lock = Any()
    @Volatile private var cur: Active? = null
    private val records = ArrayDeque<Record>()
    private var ctx: Context? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        load()
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun rx() = TrafficStats.getUidRxBytes(Process.myUid()).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it }

    /** Tastendruck/Wahl eines Senders. Ein noch laufender Wechsel wird verworfen (z. B. schnelles Weiterzappen). */
    fun begin(label: String, turbo: Boolean, predicted: Boolean, atMs: Long = now()) {
        synchronized(lock) { cur = Active(label.take(40), turbo, predicted, atMs, Process.getElapsedCpuTime(), rx()) }
        main.removeCallbacks(timeout); main.postDelayed(timeout, 20_000)
    }

    private val timeout = Runnable { finish(force = true) }

    private inline fun mark(block: Active.(Long) -> Unit) {
        val a = cur ?: return
        val t = now() - a.t0
        synchronized(lock) { if (!a.done) a.block(t) }
    }

    fun request() = mark { if (request < 0) request = it }
    fun startBuffer(ms: Long) = mark { if (startBufferMs < 0) startBufferMs = ms }
    // Ereignisse zählen erst nach der Stream-Anfrage dieses Wechsels – vorher stammen sie noch vom alten Sender
    // (z. B. beim schnellen Weiterzappen, wenn der Zielsender erst kurz nach dem Tastendruck startet)
    fun connected() = mark { if (request >= 0 && connect < 0) connect = it }
    fun firstData() = mark { if (request >= 0) { if (data < 0) data = it; if (connect < 0) connect = it } }
    fun format() = mark { if (request >= 0 && format < 0) format = it }
    fun decoderInit(ms: Long) = mark { if (request >= 0 && decoderInit < 0) decoderInit = ms }
    fun error() = mark { if (request >= 0) errors++ }
    fun retry() = mark { if (request >= 0) retries++ }
    fun firstFrame() { var ok = false; mark { if (request >= 0) { ok = true; if (frame < 0) frame = it } }; if (ok) maybeFinish() }
    fun audioStarted() { var ok = false; mark { if (request >= 0) { ok = true; if (audio < 0) audio = it } }; if (ok) maybeFinish() }

    /** Stream ohne Tonspur bzw. ohne Bild: nach dem einen Ereignis noch kurz warten, dann abschließen. */
    private fun maybeFinish() {
        val a = cur ?: return
        if (a.frame >= 0 && a.audio >= 0) finish()
        else main.postDelayed({ if (cur === a) finish() }, 2_500)
    }

    private fun finish(force: Boolean = false) {
        val a: Active
        synchronized(lock) {
            a = cur ?: return
            if (a.done) return
            if (force && a.frame < 0 && a.audio < 0) { a.errors = maxOf(a.errors, 1) }
            a.done = true
            cur = null
        }
        main.removeCallbacks(timeout)
        val wall = (now() - a.t0).coerceAtLeast(1)
        val total = listOf(a.frame, a.audio).filter { it >= 0 }.maxOrNull() ?: -1L
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cpu = ((Process.getElapsedCpuTime() - a.cpu0) * 100 / (wall * cores)).toInt().coerceIn(0, 100)
        val rt = Runtime.getRuntime()
        val ram = ((rt.totalMemory() - rt.freeMemory() + android.os.Debug.getNativeHeapAllocatedSize()) / (1024 * 1024)).toInt()
        val net = ((rx() - a.rx0) / 1024).coerceAtLeast(0)
        val r = Record(a.label, a.turbo, a.predicted, a.request, a.connect, a.data, a.format, a.decoderInit, a.frame, a.audio, total,
            cpu, ram, net, a.errors, a.retries, a.startBufferMs)
        synchronized(lock) {
            records.addLast(r)
            while (records.size > MAX) records.removeFirst()
        }
        save()
        android.util.Log.i("UltraZap", "Wechsel ${r.label}: Anfrage ${r.request} · Antwort ${r.connect} · Daten ${r.data} · Format ${r.format} · Bild ${r.frame} · Ton ${r.audio} · gesamt ${r.total} ms · Start-Puffer ${r.startBufferMs} ms · Schnellstart ${r.turbo} · CPU ${r.cpuPct} % · ${r.netKb} KB · Fehler ${r.errors}/${r.retries}")
    }

    fun all(): List<Record> = synchronized(lock) { records.toList() }
    fun clear() { synchronized(lock) { records.clear() }; save() }

    // ----------------------------------------------------------------------------- Auswertung

    data class Pct(val n: Int, val median: Long, val p95: Long, val p99: Long)

    /** Nächster-Rang-Perzentile; nur gültige (≥ 0) Werte. */
    fun pct(values: List<Long>): Pct? {
        val v = values.filter { it >= 0 }.sorted()
        if (v.isEmpty()) return null
        fun at(p: Double) = v[(kotlin.math.ceil(p * v.size).toInt() - 1).coerceIn(0, v.lastIndex)]
        return Pct(v.size, at(0.5), at(0.95), at(0.99))
    }

    val METRICS: List<Pair<String, (Record) -> Long>> = listOf(
        tr("Bis zur Anfrage") to { r -> r.request },
        tr("Bis Server antwortet") to { r -> r.connect },
        tr("Bis erste Daten") to { r -> r.data },
        tr("Bis Format erkannt") to { r -> r.format },
        "Decoder-Start" to { r -> r.decoderInitMs },
        tr("Bis erstes Bild") to { r -> r.frame },
        tr("Bis Ton hörbar") to { r -> r.audio },
        tr("Umschaltzeit gesamt") to { r -> r.total },
    )

    // ----------------------------------------------------------------------------- Speicher

    private fun prefs() = ctx?.getSharedPreferences("zap_monitor", Context.MODE_PRIVATE)

    private fun save() {
        val p = prefs() ?: return
        val lines = all().joinToString("\n") { r ->
            listOf(r.label.replace('|', ' ').replace('\n', ' '), if (r.turbo) 1 else 0, if (r.predicted) 1 else 0, r.request, r.connect, r.data, r.format,
                r.decoderInitMs, r.frame, r.audio, r.total, r.cpuPct, r.ramMb, r.netKb, r.errors, r.retries, r.startBufferMs).joinToString("|")
        }
        p.edit().putString("v1", lines).apply()
    }

    private fun load() {
        val s = prefs()?.getString("v1", null) ?: return
        val out = s.lines().mapNotNull { l ->
            val f = l.split('|')
            if (f.size < 17) return@mapNotNull null
            runCatching {
                Record(f[0], f[1] == "1", f[2] == "1", f[3].toLong(), f[4].toLong(), f[5].toLong(), f[6].toLong(), f[7].toLong(),
                    f[8].toLong(), f[9].toLong(), f[10].toLong(), f[11].toInt(), f[12].toInt(), f[13].toLong(), f[14].toInt(), f[15].toInt(), f[16].toLong())
            }.getOrNull()
        }
        synchronized(lock) { records.clear(); records.addAll(out.takeLast(MAX)) }
    }
}

/**
 * Start-Puffer pro Sender (Schnellstart): Live-TV beginnt, sobald [DEFAULT_MS] Vorrat da ist – statt pauschal 2 s.
 * Stockt ein Sender kurz nach dem Start, braucht er offenbar mehr Vorrat: sein Wert verdoppelt sich (bis 2,5 s).
 * Läuft er wiederholt sauber, sinkt der Wert wieder schrittweise (bis 0,5 s). So bekommt jeder Stream seine eigene Strategie.
 */
object ZapTuner {
    // Werte aus der (per System-Update aktualisierbaren) Player-Konfiguration; Standard 700 / 500 / 2500 ms
    private val DEFAULT_MS get() = de.ostlertekker.ultraiptv.core.sysupdate.RemotePlayerConfig.current.liveStartDefaultMs
    private val MIN_MS get() = de.ostlertekker.ultraiptv.core.sysupdate.RemotePlayerConfig.current.liveStartMinMs
    private val MAX_MS get() = de.ostlertekker.ultraiptv.core.sysupdate.RemotePlayerConfig.current.liveStartMaxMs
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) { if (prefs == null) prefs = context.applicationContext.getSharedPreferences("zap_tuner", Context.MODE_PRIVATE) }

    private fun key(url: String) = "s" + (url.substringBefore('?').hashCode().toLong() and 0xffffffffL).toString(36)

    fun startMs(url: String): Long = (prefs?.getLong(key(url), DEFAULT_MS) ?: DEFAULT_MS).coerceIn(MIN_MS, MAX_MS)

    /** Stocken kurz nach dem Start → mehr Vorrat für diesen Sender. */
    fun onEarlyStall(url: String) {
        val p = prefs ?: return
        val v = (startMs(url) * 2).coerceAtMost(MAX_MS)
        p.edit().putLong(key(url), v).putInt(key(url) + "_ok", 0).apply()
        android.util.Log.i("UltraZap", "Sender stockte kurz nach dem Start → Start-Puffer jetzt $v ms")
    }

    // ------------------------------------------------ Zapping-Muster: wie oft ein Sender gewählt wurde (nur Zähler je Sender-ID)

    fun countZap(channelId: String) {
        val p = prefs ?: return
        val k = "z_" + channelId.take(60)
        p.edit().putInt(k, p.getInt(k, 0) + 1).apply()
    }

    /** Am häufigsten gewählte Sender (IDs). */
    fun topZapped(n: Int): List<String> = prefs?.all.orEmpty()
        .filter { it.key.startsWith("z_") && it.value is Int }
        .entries.sortedByDescending { it.value as Int }.take(n).map { it.key.removePrefix("z_") }

    /** Sauberer Start (mind. 20 s ohne Stocken) → nach 3 guten Starts wieder etwas weniger Vorrat. */
    fun onCleanStart(url: String) {
        val p = prefs ?: return
        val k = key(url)
        val cur = startMs(url)
        if (cur <= MIN_MS) return
        val ok = p.getInt(k + "_ok", 0) + 1
        if (ok >= 3) p.edit().putLong(k, (cur - 200).coerceAtLeast(MIN_MS)).putInt(k + "_ok", 0).apply()
        else p.edit().putInt(k + "_ok", ok).apply()
    }
}
