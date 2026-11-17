package de.ostlertekker.ultraiptv.core.discover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Ligen mit frei zugänglichen Live-Daten (öffentliche ESPN-Schnittstelle, ohne Schlüssel). */
enum class League(val sport: Sport, val path: String, val label: String) {
    BUNDESLIGA(Sport.FOOTBALL, "soccer/ger.1", "Bundesliga"),
    BUNDESLIGA2(Sport.FOOTBALL, "soccer/ger.2", "2. Bundesliga"),
    DFB_POKAL(Sport.FOOTBALL, "soccer/ger.dfb_pokal", "DFB-Pokal"),
    CHAMPIONS(Sport.CHAMPIONS, "soccer/uefa.champions", "Champions League"),
    EUROPA(Sport.FOOTBALL, "soccer/uefa.europa", "Europa League"),
    PREMIER(Sport.FOOTBALL, "soccer/eng.1", "Premier League"),
    LALIGA(Sport.FOOTBALL, "soccer/esp.1", "LaLiga"),
    SERIE_A(Sport.FOOTBALL, "soccer/ita.1", "Serie A"),
    NATIONS(Sport.FOOTBALL, "soccer/uefa.nations", "Nations League"),
    F1(Sport.F1, "racing/f1", "Formel 1"),
    ATP(Sport.TENNIS, "tennis/atp", "Tennis ATP"),
    WTA(Sport.TENNIS, "tennis/wta", "Tennis WTA"),
    NBA(Sport.BASKETBALL, "basketball/nba", "NBA");

    companion object {
        val defaults = listOf(BUNDESLIGA, CHAMPIONS, DFB_POKAL, PREMIER, F1, ATP, NBA)
        /** Für diese Sportarten gibt es keine frei zugängliche Live-Datenquelle → wird ehrlich so angezeigt. */
        val unsupported = listOf(Sport.BOXING)
    }
}

data class ScoreCompetitor(val name: String, val short: String?, val score: String?, val logo: String?, val homeAway: String?, val winner: Boolean, val order: Int?)

data class ScoreEvent(
    val id: String,
    val league: League,
    val title: String,
    val startMs: Long,
    /** pre = angesetzt, in = läuft, post = beendet */
    val state: String,
    val detail: String,
    val clock: String?,
    val competitors: List<ScoreCompetitor>,
    val venue: String?,
    /** Übertragende Sender laut ESPN (meist US, teils international). */
    val broadcasters: List<String> = emptyList(),
) {
    val live get() = state == "in"
    val scoreLine: String get() = if (competitors.size == 2 && competitors.all { it.score != null }) {
        val home = competitors.firstOrNull { it.homeAway == "home" } ?: competitors[0]
        val away = competitors.firstOrNull { it !== home } ?: competitors[1]
        if (league.sport == Sport.TENNIS) "${competitors[0].name} (${competitors[0].score}) – ${competitors[1].name} (${competitors[1].score})"
        else "${home.name} ${home.score}:${away.score} ${away.name}"
    } else title
}

data class KeyEvent(val clock: String?, val type: String?, val text: String)
data class RosterPlayer(val name: String, val jersey: String?, val position: String?, val starter: Boolean)
data class TeamRoster(val team: String, val formation: String?, val players: List<RosterPlayer>)
data class StatRow(val label: String, val home: String, val away: String)
data class MatchDetail(val keyEvents: List<KeyEvent>, val rosters: List<TeamRoster>, val stats: List<StatRow>, val fetchedAt: Long)

data class ScoreBoard(val events: List<ScoreEvent>, val fetchedAt: Long, val failedLeagues: List<League>)

/**
 * Universal Sports Mode – echte Live-Daten aus der öffentlich erreichbaren ESPN-Schnittstelle.
 * Es werden ausschließlich gelieferte Werte angezeigt; fehlt etwas, steht dort „keine Daten“.
 */
@Singleton
class SportsRepository @Inject constructor(http: OkHttpClient) {
    // Ohne HTTP-Cache: Live-Spielstände müssen bei jeder Abfrage frisch vom Server kommen
    private val client = http.newBuilder().cache(null).connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private val base = "https://site.api.espn.com/apis/site/v2/sports/"
    @Volatile private var cache: Pair<Set<League>, ScoreBoard>? = null

    suspend fun scoreboard(leagues: Collection<League>, maxAgeMs: Long = 25_000): ScoreBoard = withContext(Dispatchers.IO) {
        val key = leagues.toSet()
        cache?.let { (k, v) -> if (k == key && System.currentTimeMillis() - v.fetchedAt < maxAgeMs) return@withContext v }
        coroutineScope {
            val results = leagues.map { l -> async { l to runCatching { fetchLeague(l) }.getOrNull() } }.awaitAll()
            val board = ScoreBoard(
                events = results.flatMap { it.second ?: emptyList() }.sortedWith(compareBy<ScoreEvent> { when (it.state) { "in" -> 0; "pre" -> 1; else -> 2 } }.thenBy { it.startMs }),
                fetchedAt = System.currentTimeMillis(),
                failedLeagues = results.filter { it.second == null }.map { it.first },
            )
            cache = key to board
            board
        }
    }

    private fun get(url: String): JsonObject? {
        val req = Request.Builder().url(url).header("Accept", "application/json").header("Cache-Control", "no-cache").header("Pragma", "no-cache").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body?.string() ?: return null
            return Json.parseToJsonElement(body) as? JsonObject
        }
    }

    private fun fetchLeague(l: League): List<ScoreEvent> {
        val root = get(base + l.path + "/scoreboard") ?: error("keine Antwort")
        val events = root.arr("events")
        val out = ArrayList<ScoreEvent>()
        for (e in events) {
            val ev = e as? JsonObject ?: continue
            // Tennis: Matches stecken in groupings[].competitions[], sonst competitions[]
            val comps = ev.arr("groupings").flatMap { (it as? JsonObject)?.arr("competitions") ?: JsonArray(emptyList()) }
                .ifEmpty { ev.arr("competitions").toList() }
            val single = comps.size <= 1 || l.sport != Sport.TENNIS
            val list = if (l.sport == Sport.F1) listOfNotNull(comps.lastOrNull { c -> (c as? JsonObject)?.obj("status")?.obj("type")?.s("state") == "in" } ?: comps.lastOrNull())
            else if (single) comps.take(1)
            else {
                // Turniere (Tennis) liefern alle Matches der Woche: laufende zuerst, dann die zeitlich nächsten
                val now = System.currentTimeMillis()
                comps.sortedWith(compareBy<kotlinx.serialization.json.JsonElement> { c ->
                    when ((c as? JsonObject)?.obj("status")?.obj("type")?.s("state")) { "in" -> 0; else -> 1 }
                }.thenBy { c -> kotlin.math.abs(parseDate((c as? JsonObject)?.s("date")) - now) }).take(30)
            }
            for (c in list) {
                val comp = c as? JsonObject ?: continue
                val status = comp.obj("status") ?: ev.obj("status")
                val type = status?.obj("type")
                val competitors = comp.arr("competitors").mapNotNull { x ->
                    val o = x as? JsonObject ?: return@mapNotNull null
                    val team = o.obj("team"); val ath = o.obj("athlete")
                    val name = team?.s("displayName") ?: ath?.s("displayName") ?: ath?.s("shortName") ?: return@mapNotNull null
                    // Tennis & Co. liefern statt „score“ die Sätze in „linescores“ → „6 4 2“
                    val sets = o.arr("linescores").mapNotNull { ls ->
                        val lo = ls as? JsonObject ?: return@mapNotNull null
                        (lo.s("displayValue") ?: lo.s("value"))?.let { v -> v.toDoubleOrNull()?.let { d -> if (d % 1.0 == 0.0) d.toInt().toString() else v } ?: v }
                    }.joinToString(" ").takeIf { it.isNotBlank() }
                    val score = (when (val sc = o["score"]) {
                        is JsonPrimitive -> sc.contentOrNull
                        is JsonObject -> sc.s("displayValue")
                        else -> null
                    }?.takeIf { it.isNotBlank() } ?: sets)
                    ScoreCompetitor(name, team?.s("shortDisplayName") ?: team?.s("abbreviation") ?: ath?.s("shortName"), score,
                        team?.s("logo") ?: team?.arr("logos")?.firstOrNull()?.let { (it as? JsonObject)?.s("href") },
                        o.s("homeAway"), o.s("winner") == "true", o.s("order")?.toIntOrNull())
                }.let { cs -> if (l.sport == Sport.F1) cs.sortedBy { it.order ?: 99 }.take(10) else cs }
                if (competitors.isEmpty()) continue
                val sessionName = comp.obj("type")?.s("abbreviation")
                out += ScoreEvent(
                    id = comp.s("id") ?: ev.s("id") ?: continue,
                    league = l,
                    title = listOfNotNull(ev.s("shortName") ?: ev.s("name"), sessionName?.takeIf { l.sport == Sport.F1 }).joinToString(" · "),
                    startMs = parseDate(comp.s("date") ?: ev.s("date")),
                    state = type?.s("state") ?: "pre",
                    detail = type?.s("shortDetail") ?: type?.s("detail") ?: type?.s("description") ?: "",
                    clock = status?.s("displayClock"),
                    competitors = competitors,
                    venue = comp.obj("venue")?.s("fullName"),
                    broadcasters = (comp.arr("broadcasts").flatMap { b -> (b as? JsonObject)?.arr("names")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty() } +
                        comp.arr("geoBroadcasts").mapNotNull { g -> (g as? JsonObject)?.obj("media")?.s("shortName") }).distinct(),
                )
            }
        }
        return out
    }

    /** Spieldetails: Ereignis-Timeline, Aufstellungen, Statistiken (falls von der Quelle geliefert). */
    suspend fun detail(league: League, eventId: String): MatchDetail? = withContext(Dispatchers.IO) {
        val root = runCatching { get(base + league.path + "/summary?event=" + eventId) }.getOrNull() ?: return@withContext null
        val keyEvents = root.arr("keyEvents").mapNotNull { k ->
            val o = k as? JsonObject ?: return@mapNotNull null
            val text = o.s("text") ?: o.obj("type")?.s("text") ?: return@mapNotNull null
            KeyEvent(o.obj("clock")?.s("displayValue"), o.obj("type")?.s("text"), text)
        }
        val rosters = root.arr("rosters").mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            TeamRoster(o.obj("team")?.s("displayName") ?: "Team", o.s("formation"),
                o.arr("roster").mapNotNull { p ->
                    val po = p as? JsonObject ?: return@mapNotNull null
                    val name = po.obj("athlete")?.s("displayName") ?: return@mapNotNull null
                    RosterPlayer(name, po.s("jersey"), po.obj("position")?.s("abbreviation"), po.s("starter") == "true")
                })
        }
        val teams = root.obj("boxscore")?.arr("teams")?.mapNotNull { it as? JsonObject } ?: emptyList()
        val home = teams.firstOrNull { it.s("homeAway") == "home" } ?: teams.getOrNull(0)
        val away = teams.firstOrNull { it !== home }
        val stats = home?.arr("statistics")?.mapNotNull { st ->
            val so = st as? JsonObject ?: return@mapNotNull null
            val name = so.s("name"); val label = so.s("label") ?: name ?: return@mapNotNull null
            val other = away?.arr("statistics")?.mapNotNull { it as? JsonObject }?.firstOrNull { it.s("name") == name }
            StatRow(label, so.s("displayValue") ?: "–", other?.s("displayValue") ?: "–")
        } ?: emptyList()
        MatchDetail(keyEvents, rosters, stats, System.currentTimeMillis())
    }

    /** Ordnet eine EPG-Sendung einem Live-Spiel zu (Teamnamen im Titel). */
    fun matchProgram(title: String, events: List<ScoreEvent>): ScoreEvent? {
        val t = normalize(title)
        return events.filter { it.competitors.size == 2 }.maxByOrNull { e ->
            e.competitors.count { c -> listOfNotNull(c.name, c.short).any { n -> normalize(n).let { it.length >= 3 && t.contains(it) } } }
        }?.takeIf { e -> e.competitors.count { c -> listOfNotNull(c.name, c.short).any { n -> normalize(n).let { it.length >= 3 && t.contains(it) } } } >= 1 }
    }

    private fun normalize(s: String) = s.lowercase().replace("ü", "u").replace("ö", "o").replace("ä", "a").replace("ß", "ss")
        .replace(Regex("""\b(fc|sc|sv|vfb|vfl|tsg|bv|afc|cf|ac|as|ssc|1\.)\b"""), " ").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun parseDate(s: String?): Long {
        if (s == null) return 0L
        for (p in listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")) {
            runCatching { return SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time }
        }
        return 0L
    }
}

private fun JsonObject.obj(k: String) = this[k] as? JsonObject
private fun JsonObject.arr(k: String): JsonArray = (this[k] as? JsonArray) ?: JsonArray(emptyList())
private fun JsonObject.s(k: String): String? = when (val v: JsonElement? = this[k]) {
    is JsonPrimitive -> v.contentOrNull
    else -> null
}
