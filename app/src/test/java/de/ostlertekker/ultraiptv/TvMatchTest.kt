package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.discover.League
import de.ostlertekker.ultraiptv.core.discover.TvMatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvMatchTest {
    private fun hit(names: List<String>, epg: String) = TvMatch.tokens(names).any { TvMatch.containsWord(TvMatch.norm(epg), it) }

    @Test fun englischeTeamnamenFindenDeutschesProgramm() {
        assertTrue(hit(listOf("Bayern Munich", "Bayern"), "Bundesliga: FC Bayern München – Borussia Dortmund"))
        assertTrue(hit(listOf("Inter Milan", "Inter"), "Champions League: Inter Mailand gegen Barcelona"))
        assertTrue(hit(listOf("Germany"), "Fußball-Länderspiel: Deutschland – Italien"))
        assertTrue(hit(listOf("Alexander Zverev", "A. Zverev"), "Tennis: ATP Finals, Halbfinale mit Zverev"))
        assertTrue(hit(listOf("Borussia Dortmund", "Dortmund"), "Live: BVB Dortmund – Leipzig"))
    }

    @Test fun keineFalschenTreffer() {
        assertFalse(hit(listOf("Manchester United", "Man United"), "Tatort: Der Fall Müller"))
        assertFalse(hit(listOf("Real Madrid", "Real"), "Real Life Doku"))
    }

    @Test fun wettbewerbsBegriffe() {
        assertTrue(TvMatch.leagueKeywords(League.F1).any { TvMatch.norm("Formel 1: Großer Preis von Brasilien").contains(it) })
        assertTrue(TvMatch.leagueKeywords(League.BUNDESLIGA).any { TvMatch.norm("Sky Sport Bundesliga").contains(it) })
    }
}
