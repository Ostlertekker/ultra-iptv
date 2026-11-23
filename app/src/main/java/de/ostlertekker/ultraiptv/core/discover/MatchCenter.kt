package de.ostlertekker.ultraiptv.core.discover

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Vollbild-Match-Center: alle Daten, die ESPN zu einem Spiel liefert. Fehlt etwas, bleibt die Liste leer (kein Platzhalter). */
data class McTeam(val id: String?, val name: String, val short: String?, val logo: String?, val score: String?, val color: String?, val form: String?, val record: String?)
enum class IncidentKind(val icon: String) { GOAL("⚽"), OWN_GOAL("⚽ (ET)"), PENALTY("⚽ (E)"), YELLOW("🟨"), RED("🟥"), SUB("🔁"), OTHER("•") }
data class Incident(val clock: String, val home: Boolean, val kind: IncidentKind, val player: String, val assist: String?)
data class TickerLine(val clock: String?, val text: String, val icon: String)
data class LineupPlayer(val name: String, val short: String, val jersey: String?, val pos: String?, val place: Int?, val subbedIn: Boolean, val subbedOut: Boolean)
data class Lineup(val team: String, val home: Boolean, val formation: String?, val starters: List<LineupPlayer>, val bench: List<LineupPlayer>)
data class StandRow(val teamId: String?, val team: String, val values: List<String>)
data class Standings(val title: String?, val columns: List<String>, val rows: List<StandRow>)
data class FormGame(val result: String, val label: String)
data class TeamForm(val team: String, val games: List<FormGame>)
data class PlayerTable(val team: String, val labels: List<String>, val rows: List<Pair<String, List<String>>>)

data class MatchCenter(
    val home: McTeam?, val away: McTeam?,
    val incidents: List<Incident>,
    val ticker: List<TickerLine>,
    val stats: List<StatRow>,
    val lineups: List<Lineup>,
    val standings: Standings?,
    val form: List<TeamForm>,
    val series: String?,
    val info: List<Pair<String, String>>,
    val players: List<PlayerTable>,
    val fetchedAt: Long,
)

private fun JsonObject.o(k: String) = this[k] as? JsonObject
private fun JsonObject.a(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
private fun JsonObject.t(k: String): String? = when (val v: JsonElement? = this[k]) { is JsonPrimitive -> v.contentOrNull; else -> null }
private fun JsonObject.flag(k: String): Boolean = when (val v = this[k]) { is JsonPrimitive -> v.contentOrNull == "true"; is JsonObject -> true; else -> false }
private fun JsonObject.logo(): String? = t("logo") ?: a("logos").firstOrNull()?.t("href")

internal object MatchCenterParser {
    fun parse(root: JsonObject): MatchCenter {
        val comp = root.o("header")?.a("competitions")?.firstOrNull()
        val comps = comp?.a("competitors").orEmpty()
        fun team(c: JsonObject?): McTeam? {
            c ?: return null
            val tm = c.o("team") ?: return null
            val rec = c.a("record").firstOrNull { it.t("type") == "total" }?.t("displayValue")
            return McTeam(tm.t("id") ?: c.t("id"), tm.t("displayName") ?: tm.t("name") ?: "?", tm.t("shortDisplayName") ?: tm.t("abbreviation"), tm.logo(),
                c.t("score"), tm.t("color"), c.t("form") ?: tm.t("form"), rec)
        }
        val home = team(comps.firstOrNull { it.t("homeAway") == "home" } ?: comps.getOrNull(0))
        val away = team(comps.firstOrNull { it.t("homeAway") == "away" } ?: comps.getOrNull(1))

        // Tore & Karten (Fußball)
        val incidents = comp?.a("details").orEmpty().mapNotNull { d ->
            val names = d.a("participants").mapNotNull { it.o("athlete")?.let { a -> a.t("shortName") ?: a.t("displayName") } }
            val typeText = d.o("type")?.t("text").orEmpty().lowercase()
            val kind = when {
                d.flag("ownGoal") -> IncidentKind.OWN_GOAL
                d.flag("penaltyKick") && d.flag("scoringPlay") -> IncidentKind.PENALTY
                d.flag("scoringPlay") -> IncidentKind.GOAL
                d.flag("redCard") || typeText.contains("red") -> IncidentKind.RED
                d.flag("yellowCard") || typeText.contains("yellow") -> IncidentKind.YELLOW
                typeText.contains("sub") -> IncidentKind.SUB
                else -> return@mapNotNull null
            }
            val tid = d.o("team")?.t("id")
            Incident(d.o("clock")?.t("displayValue").orEmpty(), tid == null || tid == home?.id, kind, names.firstOrNull() ?: "", names.getOrNull(1))
        }

        // Ticker: Kommentar > Schlüsselereignisse > Spielzüge (NBA)
        fun icon(s: String): String {
            val x = s.lowercase()
            return when {
                x.contains("goal") && !x.contains("goal kick") && !x.contains("attempt") -> "⚽"
                x.contains("red card") || x.contains("second yellow") -> "🟥"
                x.contains("yellow") -> "🟨"
                x.contains("substitution") -> "🔁"
                x.contains("corner") -> "⛳"
                x.contains("offside") -> "🚩"
                x.contains("penalty") -> "🎯"
                x.contains("var") -> "📺"
                x.contains("half") || x.contains("kickoff") || x.contains("full time") || x.contains("end of") || x.contains("start") -> "⏱"
                x.contains("attempt") || x.contains("shot") || x.contains("saved") -> "🥅"
                x.contains("foul") -> "⚠"
                else -> "•"
            }
        }
        val commentary = root.a("commentary").mapNotNull { c -> c.t("text")?.takeIf { it.isNotBlank() }?.let { TickerLine(c.o("time")?.t("displayValue")?.ifBlank { null }, it, icon(it)) } }
        val keyEv = root.a("keyEvents").mapNotNull { k ->
            val tt = k.o("type")?.t("text")
            val txt = k.t("text") ?: listOfNotNull(tt, k.a("participants").firstOrNull()?.o("athlete")?.t("displayName")).joinToString(" – ").ifBlank { null } ?: return@mapNotNull null
            TickerLine(k.o("clock")?.t("displayValue")?.ifBlank { null }, txt, icon((tt ?: "") + " " + txt))
        }
        val plays = root.a("plays").takeLast(80).mapNotNull { p ->
            val txt = p.t("text") ?: return@mapNotNull null
            val per = p.o("period")?.t("number")?.let { "Q$it " } ?: ""
            TickerLine(per + (p.o("clock")?.t("displayValue") ?: ""), txt + (if (p.flag("scoringPlay")) "  (${p.t("awayScore")}:${p.t("homeScore")})" else ""),
                if (p.flag("scoringPlay")) "🏀" else "•")
        }
        val ticker = (commentary.ifEmpty { keyEv }.ifEmpty { plays }).reversed()

        // Teamstatistik
        val bt = root.o("boxscore")?.a("teams").orEmpty()
        val bh = bt.firstOrNull { it.t("homeAway") == "home" || it.o("team")?.t("id") == home?.id } ?: bt.getOrNull(0)
        val ba = bt.firstOrNull { it !== bh }
        val stats = bh?.a("statistics").orEmpty().mapNotNull { s ->
            val name = s.t("name"); val label = s.t("label") ?: name ?: return@mapNotNull null
            val other = ba?.a("statistics")?.firstOrNull { it.t("name") == name }
            StatRow(StatNames.de(label), s.t("displayValue") ?: "–", other?.t("displayValue") ?: "–")
        }

        // Aufstellungen
        val lineups = root.a("rosters").mapNotNull { r ->
            val players = r.a("roster").mapNotNull { p ->
                val ath = p.o("athlete") ?: return@mapNotNull null
                val name = ath.t("displayName") ?: return@mapNotNull null
                LineupPlayer(name, ath.t("lastName") ?: ath.t("shortName") ?: name, p.t("jersey"), p.o("position")?.t("abbreviation"),
                    p.t("formationPlace")?.toIntOrNull(), p.flag("subbedIn"), p.flag("subbedOut")) to p.flag("starter")
            }
            if (players.isEmpty()) return@mapNotNull null
            Lineup(r.o("team")?.t("displayName") ?: "Team", r.t("homeAway") == "home", r.t("formation"),
                players.filter { it.second }.map { it.first }.sortedBy { it.place ?: 99 }, players.filter { !it.second }.map { it.first })
        }

        // Tabelle: Gruppe mit einem der beiden Teams
        val st = root.o("standings")
        val groups = st?.a("groups").orEmpty()
        val ids = setOfNotNull(home?.id, away?.id)
        val group = groups.firstOrNull { g -> g.o("standings")?.a("entries").orEmpty().any { it.t("id") in ids } } ?: groups.firstOrNull()
        val standings = group?.o("standings")?.a("entries")?.takeIf { it.isNotEmpty() }?.let { entries ->
            val pref = listOf("gamesPlayed" to "Sp", "wins" to "S", "ties" to "U", "losses" to "N", "pointDifferential" to "Diff", "points" to "Pkt",
                "winPercent" to "Quote", "gamesBehind" to tr("Rückst."), "streak" to "Serie")
            val avail = entries.first().a("stats").mapNotNull { it.t("name") }.toSet()
            val cols = pref.filter { it.first in avail }
            val rows = entries.mapIndexed { i, en ->
                val sm = en.a("stats").associate { (it.t("name") ?: "") to (it.t("displayValue") ?: "") }
                StandRow(en.t("id"), (sm["rank"]?.takeIf { it.isNotBlank() } ?: "${i + 1}") + ". " + (en.t("team") ?: en.o("team")?.t("displayName") ?: "?"),
                    cols.map { sm[it.first] ?: "" })
            }
            Standings(group.t("header") ?: group.t("name") ?: st?.t("header"), cols.map { it.second }, rows)
        }

        // Form (letzte 5 Spiele)
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val out = SimpleDateFormat("dd.MM.yy", Locale.GERMAN)
        val form = root.a("lastFiveGames").mapNotNull { f ->
            val tn = f.o("team")?.t("displayName") ?: return@mapNotNull null
            TeamForm(tn, f.a("events").map { e ->
                val date = e.t("gameDate")?.let { runCatching { out.format(df.parse(it)!!) }.getOrNull() } ?: ""
                val opp = e.o("opponent")?.t("displayName") ?: "?"
                FormGame(e.t("gameResult") ?: "", "$date  ${if (e.t("atVs") == "@") "bei" else "gegen"} $opp  ${e.t("score") ?: ""}" +
                    (e.t("leagueAbbreviation")?.let { "  · $it" } ?: ""))
            })
        }
        val series = root.a("seasonseries").firstOrNull()?.let { s -> listOfNotNull(s.t("title"), s.t("summary")).joinToString(": ").ifBlank { null } }

        // Infos
        val gi = root.o("gameInfo")
        val info = buildList {
            gi?.o("venue")?.let { v ->
                v.t("fullName")?.let { add("Stadion" to it) }
                listOfNotNull(v.o("address")?.t("city"), v.o("address")?.t("country") ?: v.o("address")?.t("state")).joinToString(", ").ifBlank { null }?.let { add("Ort" to it) }
            }
            gi?.t("attendance")?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }?.let { add("Zuschauer" to "%,d".format(Locale.GERMAN, it)) }
            gi?.a("officials")?.mapNotNull { it.t("displayName") }?.takeIf { it.isNotEmpty() }?.let { add("Schiedsrichter" to it.joinToString(", ")) }
            comp?.a("notes")?.mapNotNull { it.t("headline") }?.firstOrNull()?.let { add("Hinweis" to it) }
            home?.record?.let { add("Bilanz ${home.short ?: home.name}" to it) }
            away?.record?.let { add("Bilanz ${away.short ?: away.name}" to it) }
        }

        // Spielerstatistik (z. B. NBA)
        val players = root.o("boxscore")?.a("players").orEmpty().mapNotNull { p ->
            val tn = p.o("team")?.t("displayName") ?: return@mapNotNull null
            val s0 = p.a("statistics").firstOrNull() ?: return@mapNotNull null
            val labels = (s0["labels"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().take(8)
            val rows = s0.a("athletes").mapNotNull { at ->
                val n = at.o("athlete")?.let { it.t("shortName") ?: it.t("displayName") } ?: return@mapNotNull null
                val vals = (at["stats"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                if (vals.isEmpty()) null else n to vals.take(labels.size)
            }
            if (rows.isEmpty()) null else PlayerTable(tn, labels, rows)
        }
        return MatchCenter(home, away, incidents, ticker, stats, lineups, standings, form, series, info, players, System.currentTimeMillis())
    }
}

/** Deutsche Bezeichnungen der häufigsten Statistikwerte. */
internal object StatNames {
    private val map = mapOf(
        "possession" to "Ballbesitz", "ball possession" to "Ballbesitz", "shots" to tr("Schüsse"), "shot attempts" to tr("Schüsse"), "on goal" to tr("Schüsse aufs Tor"),
        "shots on target" to tr("Schüsse aufs Tor"), "on target" to tr("Schüsse aufs Tor"), "corner kicks" to "Ecken", "corners" to "Ecken", "fouls" to "Fouls",
        "offsides" to "Abseits", "yellow cards" to tr("Gelbe Karten"), "red cards" to tr("Rote Karten"), "saves" to "Paraden", "passes" to tr("Pässe"),
        "accurate passes" to tr("Angekommene Pässe"), "pass completion %" to "Passquote", "passing %" to "Passquote", "tackles" to tr("Zweikämpfe (Tackles)"),
        "effective tackles" to tr("Gewonnene Tackles"), "crosses" to "Flanken", "accurate crosses" to tr("Angekommene Flanken"), "interceptions" to tr("Abgefangene Bälle"),
        "clearances" to tr("Klärungen"), "blocked shots" to tr("Geblockte Schüsse"), "long balls" to tr("Lange Bälle"), "goal difference" to "Tordifferenz",
        "total goals" to "Tore", "assists" to "Vorlagen", "goals against" to "Gegentore",
        "field goal %" to "Wurfquote", "three point %" to "Dreierquote", "free throw %" to "Freiwurfquote", "rebounds" to "Rebounds",
        "offensive rebounds" to "Offensiv-Rebounds", "defensive rebounds" to "Defensiv-Rebounds", "steals" to "Steals", "blocks" to "Blocks",
        "turnovers" to "Ballverluste", "fast break points" to "Fastbreak-Punkte", "points in paint" to tr("Punkte in der Zone"), "largest lead" to tr("Größte Führung"),
    )
    fun de(label: String) = map[label.lowercase().trim()] ?: label
}
