package de.ostlertekker.ultraiptv.core.discover

import android.content.Context
import android.util.Xml
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Eine Sport-Sendung aus einer öffentlichen TV-Zeitschrift (XMLTV), unabhängig vom EPG der Playlist. */
data class GuideProgram(val channel: String, val country: String, val startMs: Long, val endMs: Long, val title: String, val desc: String)

/**
 * Öffentliche TV-Zeitschriften je Land (XMLTV von epgshare01, gesammelt aus den TV-Programm-Seiten des jeweiligen Landes).
 * Es werden nur Sport-Sendungen der nächsten Tage behalten. Die Länder ergeben sich aus der Senderliste des Nutzers.
 */
@Singleton
class SportGuide @Inject constructor(@ApplicationContext private val ctx: Context, http: OkHttpClient) {
    private val client = http.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private val loaded = HashMap<String, Pair<Long, List<GuideProgram>>>()
    /** Länder, für die eine TV-Zeitschrift erfolgreich geladen wurde. */
    @Volatile var available: Set<String> = emptySet(); private set

    /** Dateikürzel bei epgshare01 je Ländercode der App. */
    private val files = mapOf(
        "DE" to "DE1", "AT" to "AT1", "CH" to "CH1", "UK" to "UK1", "IE" to "IE1", "PL" to "PL1", "NL" to "NL1", "BE" to "BE2", "FR" to "FR1",
        "IT" to "IT1", "ES" to "ES1", "PT" to "PT1", "TR" to "TR1", "GR" to "GR1", "RO" to "RO1", "HU" to "HU1", "CZ" to "CZ1", "SK" to "SK1",
        "HR" to "HR1", "RS" to "RS1", "BA" to "BA1", "SI" to "SI1", "BG" to "BG1", "DK" to "DK1", "SE" to "SE1", "NO" to "NO1", "FI" to "FI1",
        "AL" to "AL1", "MK" to "MK1", "CY" to "CY1", "MT" to "MT1", "IL" to "IL1", "US" to "US_SPORTS1", "CA" to "CA2", "AU" to "AU1", "NZ" to "NZ1",
        "BR" to "BR1", "MX" to "MX1", "LATAM" to "AR1", "IN" to "IN1", "MENA" to "BEIN1", "UA" to "UA1", "LT" to "LT1", "LV" to "LV1", "EE" to "EE1",
    )

    /** Lädt (max. alle 12 h neu) die TV-Zeitschriften der wichtigsten Länder der Senderliste. */
    suspend fun programs(countries: List<String>): List<GuideProgram> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val wanted = countries.filter { it in files }.distinct().take(4)
            coroutineScope {
                wanted.map { c -> async { c to runCatching { load(c) }.getOrNull() } }.awaitAll()
            }.forEach { (c, list) -> if (list != null) loaded[c] = System.currentTimeMillis() to list }
            available = loaded.keys.toSet()
            wanted.flatMap { loaded[it]?.second.orEmpty() }
        }
    }

    private fun load(country: String): List<GuideProgram> {
        loaded[country]?.takeIf { System.currentTimeMillis() - it.first < 12 * 3_600_000L }?.let { return it.second }
        val dir = File(ctx.cacheDir, "sportguide").apply { mkdirs() }
        val file = File(dir, "$country.xml.gz")
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > 12 * 3_600_000L) {
            val req = Request.Builder().url("https://epgshare01.online/epgshare01/epg_ripper_${files.getValue(country)}.xml.gz").build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val tmp = File(dir, "$country.tmp")
                r.body!!.byteStream().use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                tmp.renameTo(file)
            }
        }
        return parse(file, country)
    }

    private fun parse(file: File, country: String): List<GuideProgram> {
        val now = System.currentTimeMillis()
        val from = now - 6 * 3_600_000L
        val to = now + 3 * 86_400_000L
        val names = HashMap<String, String>()
        val out = ArrayList<GuideProgram>()
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        fun time(s: String?): Long = s?.let { runCatching { fmt.parse(it.trim())!!.time }.getOrNull() } ?: 0L
        val stream = file.inputStream().buffered().let { if (file.name.endsWith(".gz")) GZIPInputStream(it, 64 * 1024) else it }
        stream.use { input ->
            val p = Xml.newPullParser()
            p.setInput(input, "UTF-8")
            var chId: String? = null
            var prog: Array<Any?>? = null // channel, start, stop, title, desc
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (p.name) {
                    "channel" -> chId = p.getAttributeValue(null, "id")
                    "display-name" -> if (chId != null) { val n = p.nextText(); names.putIfAbsent(chId!!, n) }
                    "programme" -> {
                        val st = time(p.getAttributeValue(null, "start")); val en = time(p.getAttributeValue(null, "stop"))
                        prog = if (en > from && st < to) arrayOf(p.getAttributeValue(null, "channel"), st, en, "", "") else null
                    }
                    "title" -> prog?.let { if ((it[3] as String).isEmpty()) it[3] = p.nextText() }
                    "sub-title", "desc", "category" -> prog?.let { it[4] = (it[4] as String) + " " + p.nextText() }
                } else if (ev == XmlPullParser.END_TAG) when (p.name) {
                    "channel" -> chId = null
                    "programme" -> {
                        prog?.let { a ->
                            val title = a[3] as String; val desc = a[4] as String
                            if (Sport.detect("$title $desc") != null) {
                                val id = a[0] as String? ?: ""
                                out += GuideProgram(names[id] ?: id.substringBeforeLast('.').replace('.', ' '), country, a[1] as Long, a[2] as Long, title, desc.trim())
                            }
                        }
                        prog = null
                    }
                }
                ev = p.next()
            }
        }
        return out
    }
}
