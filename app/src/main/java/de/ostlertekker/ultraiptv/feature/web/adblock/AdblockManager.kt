package de.ostlertekker.ultraiptv.feature.web.adblock

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lädt, aktualisiert und kompiliert die Filterlisten des Browsers.
 *
 * - Beim ersten Start: mitgelieferte Listen (beim Bauen der App frisch geladen) → sofort einsatzbereit, auch offline.
 * - Danach alle 3 Tage im Hintergrund aktualisieren (ETag/Last-Modified, Spiegel als Rückfall).
 *   Eine neue Liste wird nur übernommen, wenn sie gültig aussieht; sonst bleibt die alte.
 * - Kompiliert wird im Hintergrund; bis dahin blockt die eingebaute Grundliste.
 */
@Singleton
class AdblockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    baseClient: OkHttpClient,
) {
    data class FilterList(val id: String, val title: String, val urls: List<String>, val malware: Boolean = false)

    companion object {
        private const val TAG = "UltraAdblock"
        private val MALWARE_HOST = Regex("""\|\|[A-Za-z0-9.-]+\^(\$.*)?""")
        private const val UPDATE_EVERY_MS = 3L * 24 * 60 * 60 * 1000
        val LISTS = listOf(
            FilterList("easylist", "EasyList", listOf("https://easylist.to/easylist/easylist.txt",
                "https://easylist-downloads.adblockplus.org/easylist.txt")),
            FilterList("easyprivacy", "EasyPrivacy", listOf("https://easylist.to/easylist/easyprivacy.txt",
                "https://easylist-downloads.adblockplus.org/easyprivacy.txt")),
            FilterList("easylistgermany", "EasyList Germany", listOf("https://easylist.to/easylistgermany/easylistgermany.txt",
                "https://easylist-downloads.adblockplus.org/easylistgermany.txt")),
            FilterList("urlhaus", "URLhaus (Schadsoftware)", listOf("https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-agh-online.txt",
                "https://curbengh.github.io/malware-filter/urlhaus-filter-agh-online.txt",
                "https://cdn.jsdelivr.net/gh/curbengh/urlhaus-filter/urlhaus-filter-agh-online.txt"), malware = true),
        )
    }

    private val http = baseClient.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val dir = File(context.filesDir, "adblock").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("adblock", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val buildLock = Mutex()

    private val _engine = MutableStateFlow<FilterEngine?>(null)
    /** Aktuelle Engine (null, solange noch kompiliert wird). */
    val engine: StateFlow<FilterEngine?> = _engine
    @Volatile var status: String = "Filter werden geladen …"
        private set

    @Volatile private var started = false

    /** Einmal pro App-Lauf: Listen laden/kompilieren und bei Bedarf aktualisieren. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            runCatching { rebuild() }.onFailure { Log.w(TAG, "Filter kompilieren fehlgeschlagen", it); status = "Filter: Fehler beim Laden" }
            if (System.currentTimeMillis() - prefs.getLong("lastUpdate", 0) > UPDATE_EVERY_MS || LISTS.any { !file(it).exists() }) update()
        }
    }

    /** Sofort aktualisieren (z. B. aus dem Browser-Menü). */
    fun updateNow() { scope.launch { update() } }

    private fun file(l: FilterList) = File(dir, l.id + ".txt")

    private suspend fun update() {
        var changed = false
        for (l in LISTS) if (runCatching { download(l) }.onFailure { Log.w(TAG, "${l.id}: ${it.message}") }.getOrDefault(false)) changed = true
        prefs.edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
        if (changed) runCatching { rebuild() }.onFailure { Log.w(TAG, "Neu kompilieren fehlgeschlagen", it) }
    }

    /** Lädt eine Liste (mit ETag), prüft sie und ersetzt die alte erst danach. true = neue Fassung. */
    private fun download(l: FilterList): Boolean {
        val f = file(l)
        for (url in l.urls) {
            val req = Request.Builder().url(url).header("User-Agent", "UltraIPTV-Browser/1.0")
                .apply { if (f.exists()) prefs.getString("etag_${l.id}_$url", null)?.let { header("If-None-Match", it) } }
                .build()
            val ok = runCatching {
                http.newCall(req).execute().use { r ->
                    if (r.code == 304) return false
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val tmp = File(dir, l.id + ".tmp")
                    r.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: error("leer")
                    if (!looksValid(tmp)) { tmp.delete(); error("ungültige Liste") }
                    if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) error("Speichern fehlgeschlagen") }
                    r.header("ETag")?.let { prefs.edit().putString("etag_${l.id}_$url", it).apply() }
                    true
                }
            }.onFailure { Log.w(TAG, "${l.id} von $url: ${it.message}") }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun looksValid(f: File): Boolean {
        if (f.length() < 2_000) return false
        val head = f.bufferedReader().use { r -> (0 until 30).mapNotNull { r.readLine() } }
        return head.any { it.startsWith("[Adblock") || it.startsWith("! Title") || it.startsWith("! Version") || it.startsWith("||") }
    }

    /** Zeilen einer Liste: heruntergeladene Fassung, sonst die mitgelieferte. */
    private fun lines(l: FilterList): Sequence<String>? {
        val f = file(l)
        val reader = when {
            f.exists() && f.length() > 0 -> f.bufferedReader()
            else -> runCatching { context.assets.open("adblock/${l.id}.txt").bufferedReader() }.getOrNull() ?: return null
        }
        // Datei wird beim Durchlaufen gelesen und danach sicher geschlossen
        val seq = sequence { reader.use { r -> r.lineSequence().forEach { yield(it) } } }
        // Schadsoftware-Liste: auch ganze Seiten (Hauptfenster) und Pop-ups sperren
        // Nur ganze Schad-Domains übernehmen (einzelne Pfade würden viel Speicher kosten); diese auch als Hauptfenster/Pop-up sperren
        return if (l.malware) seq.filter { MALWARE_HOST.matches(it) }.map { if (!it.contains('$')) "$it\$all" else it } else seq
    }

    private suspend fun rebuild() = buildLock.withLock {
        val t0 = System.currentTimeMillis()
        // Eigene Ausnahmen: Videoplayer brauchen das Google-IMA-Grundgerüst (die Werbung selbst bleibt blockiert)
        val own = sequenceOf(
            "@@||imasdk.googleapis.com/js/sdkloader/ima3.js",
            "@@||imasdk.googleapis.com/js/sdkloader/ima3_dai.js",
            "@@||imasdk.googleapis.com/pal/sdkloader/pal.js",
        )
        val readers = LISTS.mapNotNull { lines(it) }.let { if (it.isEmpty()) it else it + listOf(own) }
        if (readers.isEmpty()) { status = "Filterlisten fehlen – werden geladen"; return@withLock }
        val e = FilterEngine.build(readers)
        _engine.value = e
        status = "${"%,d".format(e.ruleCount).replace(',', '.')} Filterregeln aktiv"
        Log.i(TAG, "Filter kompiliert: ${e.ruleCount} Regeln in ${System.currentTimeMillis() - t0} ms")
    }
}
