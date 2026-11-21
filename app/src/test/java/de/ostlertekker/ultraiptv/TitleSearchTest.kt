package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.data.TitleSearch
import org.junit.Assert.assertEquals
import org.junit.Test

class TitleSearchTest {
    @Test fun matchesWordsIgnoringCasePrefixAndAccents() {
        val titles = listOf("DE: Der Herr der Ringe - Die Gefährten", "|EN| The Lord of the Rings", "Herr der Ringe: Die zwei Türme (2002)",
            "Ringe des Saturn", "DE: Pokémon – Der Film")
        val r = TitleSearch.rank(titles, TitleSearch.words("herr ringe")) { it }
        assertEquals(listOf("Herr der Ringe: Die zwei Türme (2002)", "DE: Der Herr der Ringe - Die Gefährten"), r)
        assertEquals(listOf("DE: Pokémon – Der Film"), TitleSearch.rank(titles, TitleSearch.words("pokemon")) { it })
        assertEquals(listOf("DE: Der Herr der Ringe - Die Gefährten"), TitleSearch.rank(titles, TitleSearch.words("Gefahrten")) { it })
    }

    @Test fun startsWithRanksFirst() {
        val titles = listOf("DE: Das Boot", "Boot Camp", "Ein Boot")
        assertEquals("Boot Camp", TitleSearch.rank(titles, TitleSearch.words("boot")) { it }.first())
        assertEquals("ringe", TitleSearch.dbKey("Herr Ringe"))
        assertEquals("gefährten", TitleSearch.dbKey("Gefährten"))
    }
}
