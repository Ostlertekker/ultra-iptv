package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.discover.League
import de.ostlertekker.ultraiptv.core.discover.ScoreCompetitor
import de.ostlertekker.ultraiptv.core.discover.ScoreEvent
import de.ostlertekker.ultraiptv.core.discover.SetScore
import org.junit.Assert.assertEquals
import org.junit.Test

class TennisSetsTest {
    private fun ev(a: List<SetScore>, b: List<SetScore>) = ScoreEvent("1", League.WTA, "", 0, "in", "", null,
        listOf(ScoreCompetitor("A. Vergara Rivera", null, null, null, null, false, null, a), ScoreCompetitor("F. Labrana", null, null, null, null, false, null, b)), null)

    @Test fun saetzeLesbar() {
        val e = ev(listOf(SetScore(1, null), SetScore(6, null), SetScore(0, null)), listOf(SetScore(6, null), SetScore(3, null), SetScore(5, null)))
        assertEquals("1:6  6:3  0:5", e.tennisSets())
    }

    @Test fun tiebreak() {
        val e = ev(listOf(SetScore(7, 7), SetScore(4, null)), listOf(SetScore(6, 5), SetScore(2, null)))
        assertEquals("7:6(5)  4:2", e.tennisSets())
    }
}
