package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.discover.IncidentKind
import de.ostlertekker.ultraiptv.core.discover.MatchCenterParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Struktur wie die echten ESPN-Spieldaten (Nations League, Nov. 2026), gekürzt. */
class MatchCenterParserTest {
    private val json = """
    {"header":{"competitions":[{"competitors":[
      {"id":"452","homeAway":"home","team":{"id":"452","displayName":"Bosnia-Herzegovina","abbreviation":"BIH","color":"112855","logos":[{"href":"x.png"}]},"score":"2","form":"WLWDW","record":[{"type":"total","displayValue":"3-2-1"}]},
      {"id":"473","homeAway":"away","team":{"id":"473","displayName":"Romania","abbreviation":"ROU"},"score":"1","form":"LLDWW"}],
     "details":[{"clock":{"displayValue":"6'"},"scoringPlay":true,"team":{"id":"473"},"participants":[{"athlete":{"displayName":"Daniel Birligea","shortName":"D. Birligea"}},{"athlete":{"shortName":"D. Man"}}],"redCard":false,"penaltyKick":false,"ownGoal":false},
                {"clock":{"displayValue":"44'"},"scoringPlay":true,"team":{"id":"452"},"participants":[{"athlete":{"shortName":"B. Tahirovic"}}]},
                {"clock":{"displayValue":"70'"},"scoringPlay":false,"yellowCard":true,"team":{"id":"452"},"participants":[{"athlete":{"shortName":"X. Y"}}]}]}]},
     "commentary":[{"time":{"displayValue":""},"text":"Lineups are announced."},{"time":{"displayValue":"6'"},"text":"Goal! Romania 1, Bosnia 0."}],
     "rosters":[{"homeAway":"home","team":{"displayName":"Bosnia-Herzegovina"},"formation":"4-2-3-1","roster":[
        {"starter":true,"jersey":"22","athlete":{"displayName":"Martin Zlomislic","lastName":"Zlomislic"},"position":{"abbreviation":"G"},"formationPlace":"1","subbedIn":false,"subbedOut":false},
        {"starter":false,"jersey":"9","athlete":{"displayName":"Edin Dzeko","lastName":"Dzeko"},"subbedIn":true}]}],
     "boxscore":{"teams":[{"homeAway":"home","team":{"id":"452"},"statistics":[{"name":"possessionPct","label":"Possession","displayValue":"55.4"}]},
                          {"homeAway":"away","team":{"id":"473"},"statistics":[{"name":"possessionPct","label":"Possession","displayValue":"44.6"}]}]},
     "gameInfo":{"venue":{"fullName":"Bilino Polje Stadium","address":{"city":"Zenica","country":"Bosnia and Herzegovina"}},"attendance":11460},
     "standings":{"groups":[{"header":"Group B4","standings":{"entries":[{"team":"Romania","id":"473","stats":[{"name":"rank","displayValue":"1"},{"name":"gamesPlayed","displayValue":"6"},{"name":"points","displayValue":"12"}]},
        {"team":"Bosnia-Herzegovina","id":"452","stats":[{"name":"rank","displayValue":"2"},{"name":"gamesPlayed","displayValue":"6"},{"name":"points","displayValue":"11"}]}]}}]},
     "lastFiveGames":[{"team":{"displayName":"Bosnia-Herzegovina"},"events":[{"gameDate":"2026-09-25T18:45Z","atVs":"@","score":"0-0","gameResult":"D","opponent":{"displayName":"Poland"},"leagueAbbreviation":"UNL"}]}],
     "seasonseries":[{"title":"Bosnia vs. Romania","summary":"Series tied 1-1"}]}
    """.trimIndent()

    @Test fun parse() {
        val m = MatchCenterParser.parse(Json.parseToJsonElement(json).jsonObject)
        assertEquals("Bosnia-Herzegovina", m.home?.name)
        assertEquals(3, m.incidents.size)
        assertTrue(!m.incidents[0].home && m.incidents[0].kind == IncidentKind.GOAL && m.incidents[0].assist == "D. Man")
        assertEquals(IncidentKind.YELLOW, m.incidents[2].kind)
        assertEquals("Goal! Romania 1, Bosnia 0.", m.ticker.first().text) // neueste oben
        assertEquals("Ballbesitz", m.stats.first().label)
        assertEquals("4-2-3-1", m.lineups.first().formation)
        assertEquals(1, m.lineups.first().bench.size)
        assertEquals(listOf("Sp", "Pkt"), m.standings?.columns)
        assertEquals("1. Romania", m.standings?.rows?.first()?.team)
        assertTrue(m.info.any { it.first == "Zuschauer" })
        assertEquals("D", m.form.first().games.first().result)
        assertEquals("Bosnia vs. Romania: Series tied 1-1", m.series)
    }
}
