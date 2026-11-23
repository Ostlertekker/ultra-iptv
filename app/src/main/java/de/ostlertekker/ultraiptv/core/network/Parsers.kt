package de.ostlertekker.ultraiptv.core.network

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone

// =====================================================================
// M3U / M3U8 Playlist-Parser
// =====================================================================

data class M3uEntry(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val tvgId: String?,
    val tvgName: String?,
    /** Catch-up-Angaben aus der Playlist (catchup, catchup-days, catchup-source). */
    val catchup: de.ostlertekker.ultraiptv.core.catchup.CatchupInfo? = null,
)

data class M3uResult(val entries: List<M3uEntry>, val epgUrl: String?)

enum class M3uKind { LIVE, MOVIE, EPISODE }

data class EpisodeInfo(val showName: String, val season: Int, val episode: Int)

object M3uParser {
    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val episodeRegex = Regex("""^(.*?)[\s._\-|]*[Ss](\d{1,2})\s*[Ee](\d{1,3})""")

    fun parse(lines: Sequence<String>): M3uResult {
        val out = ArrayList<M3uEntry>()
        var epgUrl: String? = null
        var pendingAttrs: Map<String, String>? = null
        var pendingName: String? = null
        var pendingGroup: String? = null
        for (raw in lines) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = parseAttributes(line)
                    epgUrl = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"]
                    epgUrl = epgUrl?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingAttrs = parseAttributes(line)
                    pendingName = nameAfterComma(line)
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit // #EXTVLCOPT u. a. ignorieren
                else -> {
                    val attrs = pendingAttrs ?: emptyMap()
                    val name = pendingName?.takeIf { it.isNotBlank() } ?: attrs["tvg-name"] ?: line.substringAfterLast('/')
                    val group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: pendingGroup ?: tr("Allgemein")
                    out += M3uEntry(
                        name = name.trim(),
                        url = line,
                        group = group.trim(),
                        logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                        tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() },
                        catchup = de.ostlertekker.ultraiptv.core.catchup.Catchup.fromM3uAttrs(attrs),
                    )
                    pendingAttrs = null; pendingName = null; pendingGroup = null
                }
            }
        }
        return M3uResult(out, epgUrl)
    }

    fun parseAttributes(line: String): Map<String, String> =
        attrRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** Name steht hinter dem ersten Komma außerhalb von Anführungszeichen. */
    fun nameAfterComma(line: String): String {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return line.substring(i + 1).trim()
        }
        return ""
    }

    fun classify(entry: M3uEntry): M3uKind {
        val path = entry.url.substringBefore('?').lowercase()
        return when {
            "/series/" in path -> M3uKind.EPISODE
            "/movie/" in path || "/movies/" in path || "/vod/" in path -> M3uKind.MOVIE
            path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi") ->
                if (episodeInfo(entry.name) != null) M3uKind.EPISODE else M3uKind.MOVIE
            else -> M3uKind.LIVE
        }
    }

    fun episodeInfo(name: String): EpisodeInfo? {
        val m = episodeRegex.find(name) ?: return null
        val show = m.groupValues[1].trim().trimEnd('-', '|', '.', '_').trim()
        if (show.isEmpty()) return null
        return EpisodeInfo(show, m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }
}

// =====================================================================
// XMLTV-EPG-Parser (streamend, speicherschonend)
// =====================================================================

data class XmltvProgramme(
    val channel: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String?,
)

class XmltvParser(private val newParser: () -> XmlPullParser) {

    /**
     * Liest Programme aus einem XMLTV-Stream. Nur Programme im Zeitfenster [fromMs, toMs)
     * werden gemeldet. [offsetMs] korrigiert falsch deklarierte Anbieter-Zeitzonen.
     */
    fun parse(
        input: InputStream,
        offsetMs: Long,
        fromMs: Long,
        toMs: Long,
        onProgramme: (XmltvProgramme) -> Unit,
    ): Int {
        val p = newParser()
        p.setInput(input, null)
        var count = 0
        var inProgramme = false
        var channel: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var desc: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "programme" -> {
                        inProgramme = true
                        channel = p.getAttributeValue(null, "channel")
                        start = parseXmltvTime(p.getAttributeValue(null, "start")) ?: 0L
                        stop = parseXmltvTime(p.getAttributeValue(null, "stop")) ?: 0L
                        title = null
                        desc = null
                    }
                    "title" -> if (inProgramme && title == null) title = p.nextText().trim()
                    "desc" -> if (inProgramme && desc == null) desc = p.nextText().trim()
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "programme") {
                inProgramme = false
                val c = channel
                val t = title
                if (c != null && t != null && start > 0 && stop > start) {
                    val s = start + offsetMs
                    val e = stop + offsetMs
                    if (e > fromMs && s < toMs) {
                        onProgramme(XmltvProgramme(c, s, e, t, desc?.takeIf { it.isNotEmpty() }))
                        count++
                    }
                }
            }
            ev = p.next()
        }
        return count
    }

    companion object {
        /** Format: "yyyyMMddHHmmss +hhmm" (Sekunden und Offset optional). Ergebnis in UTC-Millisekunden. */
        fun parseXmltvTime(value: String?): Long? {
            if (value == null) return null
            val t = value.trim()
            val digits = t.takeWhile { it.isDigit() }
            if (digits.length < 12) return null
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(
                digits.substring(0, 4).toInt(),
                digits.substring(4, 6).toInt() - 1,
                digits.substring(6, 8).toInt(),
                digits.substring(8, 10).toInt(),
                digits.substring(10, 12).toInt(),
                if (digits.length >= 14) digits.substring(12, 14).toInt() else 0,
            )
            var ms = cal.timeInMillis
            val rest = t.substring(digits.length).trim()
            if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                val sign = if (rest[0] == '+') 1 else -1
                val oh = rest.substring(1, 3).toIntOrNull() ?: 0
                val om = rest.substring(3, 5).toIntOrNull() ?: 0
                ms -= sign * (oh * 3_600_000L + om * 60_000L)
            }
            return ms
        }
    }
}

// =====================================================================
// Xtream-Codes-kompatible URLs
// =====================================================================

object XtreamUrls {
    fun normalizeServer(server: String): String {
        var s = server.trim().trimEnd('/')
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        s = s.removeSuffix("/player_api.php").removeSuffix("/get.php")
        return s
    }

    fun encQuery(s: String): String = URLEncoder.encode(s, "UTF-8")
    fun encPath(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun api(base: String, user: String, pass: String, action: String?, extra: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder("$base/player_api.php?username=${encQuery(user)}&password=${encQuery(pass)}")
        if (action != null) sb.append("&action=").append(encQuery(action))
        extra.forEach { (k, v) -> sb.append('&').append(encQuery(k)).append('=').append(encQuery(v)) }
        return sb.toString()
    }

    fun stream(base: String, user: String, pass: String, type: String, id: String, ext: String): String {
        val suffix = if (ext.isBlank()) "" else ".$ext"
        return "$base/$type/${encPath(user)}/${encPath(pass)}/$id$suffix"
    }

    fun xmltv(base: String, user: String, pass: String): String =
        "$base/xmltv.php?username=${encQuery(user)}&password=${encQuery(pass)}"
}

// =====================================================================
// Log-Schutz: Zugangsdaten niemals im Klartext protokollieren
// =====================================================================

object SafeLog {
    private val queryCred = Regex("""(?i)(password|pass|pwd|username|user|token)=([^&\s]+)""")
    private val pathCred = Regex("""/(live|movie|series)/([^/\s]+)/([^/\s]+)/""")

    fun redact(text: String): String =
        text.replace(queryCred) { "${it.groupValues[1]}=***" }
            .replace(pathCred) { "/${it.groupValues[1]}/***/***/" }
}
