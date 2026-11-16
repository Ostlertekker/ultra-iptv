package de.ostlertekker.ultraiptv.core.catchup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Catch-up / Timeshift über die Archiv-Funktion des Anbieters (TV Time Machine, Live-Pause, Zurückspulen).
 * Es wird NICHTS lokal aufgezeichnet oder kopiert – nur die vom Anbieter bereitgestellte Archiv-URL gebaut.
 * Funktioniert ausschließlich bei Sendern, für die der Anbieter ein Archiv meldet.
 */
data class CatchupInfo(val days: Int, val mode: String?, val source: String?)

object Catchup {
    const val DAY_MS = 86_400_000L

    /** Liest M3U-Attribute (catchup, catchup-days, catchup-source, tvg-rec, timeshift). */
    fun fromM3uAttrs(attrs: Map<String, String>): CatchupInfo? {
        val mode = (attrs["catchup"] ?: attrs["catchup-type"])?.trim()?.lowercase()
        val days = (attrs["catchup-days"] ?: attrs["tvg-rec"] ?: attrs["timeshift"])?.trim()?.toIntOrNull()
        val source = attrs["catchup-source"]?.trim()?.takeIf { it.isNotEmpty() }
        if (mode == null && days == null && source == null) return null
        if (mode == "disabled" || mode == "none" || days == 0) return null
        return CatchupInfo((days ?: 7).coerceIn(1, 30), mode ?: if (source != null) "default" else "shift", source)
    }

    /** Liegt die Sendung im Archivfenster des Senders? */
    fun available(archiveDays: Int, startMs: Long, nowMs: Long): Boolean =
        archiveDays > 0 && startMs < nowMs - 60_000 && startMs >= nowMs - archiveDays * DAY_MS

    /** Archiv-URL für M3U-Quellen. null = nicht möglich. */
    fun buildM3u(liveUrl: String, mode: String?, source: String?, startMs: Long, endMs: Long, nowMs: Long): String? {
        val s = startMs / 1000
        val dur = ((endMs - startMs) / 1000).coerceAtLeast(60)
        val sep = if ('?' in liveUrl) "&" else "?"
        return when (mode) {
            "append" -> liveUrl + fill(source ?: return null, startMs, endMs, nowMs)
            "shift", "timeshift" -> "$liveUrl${sep}utc=$s&lutc=${nowMs / 1000}"
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(liveUrl, s, dur)
            "default", null -> if (source.isNullOrBlank()) "$liveUrl${sep}utc=$s&lutc=${nowMs / 1000}" else fill(source, startMs, endMs, nowMs)
            else -> if (source.isNullOrBlank()) null else fill(source, startMs, endMs, nowMs)
        }
    }

    private fun flussonic(url: String, s: Long, dur: Long): String {
        val path = url.substringBefore('?')
        val query = url.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val base = path.substringBeforeLast('/')
        val file = path.substringAfterLast('/')
        return when {
            file.endsWith(".m3u8") -> "$base/archive-$s-$dur.m3u8$query"
            file.endsWith(".ts") || file == "mpegts" -> "$base/timeshift_abs-$s.ts$query"
            else -> "$path/archive-$s-$dur.m3u8$query"
        }
    }

    private val token = Regex("""\$?\{(utc|start|utcend|end|lutc|now|timestamp|duration|offset|Y|m|d|H|M|S)(?::([^}]*))?\}""")

    /** Ersetzt die üblichen Platzhalter ({utc}, {start}, {duration}, {Y}{m}{d}, {utc:YmdHMS} …). */
    fun fill(template: String, startMs: Long, endMs: Long, nowMs: Long): String {
        val start = startMs / 1000; val end = endMs / 1000; val now = nowMs / 1000
        return token.replace(template) { m ->
            val key = m.groupValues[1]; val fmt = m.groupValues[2]
            val epoch = when (key) { "utc", "start" -> start; "utcend", "end" -> end; "lutc", "now", "timestamp" -> now; else -> start }
            when {
                fmt.isNotEmpty() -> format(epoch * 1000, fmt)
                key == "duration" -> ((endMs - startMs) / 1000).coerceAtLeast(60).toString()
                key == "offset" -> (now - start).coerceAtLeast(0).toString()
                key in listOf("Y", "m", "d", "H", "M", "S") -> format(startMs, key)
                else -> epoch.toString()
            }
        }
    }

    /** Format wie in Kodi/TiviMate: Y m d H M S (UTC). */
    fun format(ms: Long, pattern: String): String {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        val sb = StringBuilder()
        for (ch in pattern) {
            when (ch) {
                'Y' -> sb.append("%04d".format(cal.get(java.util.Calendar.YEAR)))
                'm' -> sb.append("%02d".format(cal.get(java.util.Calendar.MONTH) + 1))
                'd' -> sb.append("%02d".format(cal.get(java.util.Calendar.DAY_OF_MONTH)))
                'H' -> sb.append("%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY)))
                'M' -> sb.append("%02d".format(cal.get(java.util.Calendar.MINUTE)))
                'S' -> sb.append("%02d".format(cal.get(java.util.Calendar.SECOND)))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Xtream-Codes Archiv: /timeshift/{user}/{pass}/{dauer-min}/{yyyy-MM-dd:HH-mm}/{streamId}.ts (Server-Zeitzone). */
    fun xtreamPath(server: String, user: String, pass: String, streamId: String, startMs: Long, durationMin: Int, tz: String?): String {
        val f = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply {
            timeZone = if (tz.isNullOrBlank()) TimeZone.getDefault() else TimeZone.getTimeZone(tz)
        }
        return "${server.trimEnd('/')}/timeshift/$user/$pass/${durationMin.coerceAtLeast(1)}/${f.format(Date(startMs))}/$streamId.ts"
    }

    /** Referenz für Xtream (ohne Zugangsdaten, wird erst beim Abspielen aufgelöst). */
    fun xtreamRef(streamId: String, startMs: Long, endMs: Long, tz: String?): String {
        val dur = ((endMs - startMs) / 60_000L).toInt().coerceAtLeast(1)
        return "xtream://timeshift/$streamId@$startMs@$dur@${(tz ?: "").replace('/', '|')}.ts"
    }

    data class XtreamRef(val streamId: String, val startMs: Long, val durationMin: Int, val tz: String?)

    fun parseXtreamRef(file: String): XtreamRef? {
        val parts = file.removeSuffix(".ts").split('@')
        if (parts.size < 3) return null
        return XtreamRef(parts[0], parts[1].toLongOrNull() ?: return null, parts[2].toIntOrNull() ?: return null,
            parts.getOrNull(3)?.replace('|', '/')?.takeIf { it.isNotBlank() })
    }
}
