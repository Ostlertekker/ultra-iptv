package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.data.ContinueWatching
import de.ostlertekker.ultraiptv.core.database.EpisodeEntity
import de.ostlertekker.ultraiptv.core.database.ProgressEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingTest {
    private fun pr(id: String, pos: Long, dur: Long, t: Long, kind: String = "EPISODE", series: String? = "s1") =
        ProgressEntity(1, kind, id, pos, dur, t, "Serie · S1E1", null, series)
    private fun ep(id: String, n: Int) = EpisodeEntity(id, "s1", 1, n, "Folge $n", null, null, null, "http://x/$id")

    @Test fun finishedEpisodeShowsNext() = runBlocking {
        val res = ContinueWatching.build(emptyList(), listOf(pr("e1", 2_900_000, 3_000_000, 10))) { _ ->
            listOf(ep("e1", 1), ep("e2", 2))
        }
        assertEquals(listOf("e2"), res.map { it.itemId })
        assertEquals("Serie · S1E2", res[0].title); assertEquals(0L, res[0].positionMs)
    }

    @Test fun onlyLatestPerSeriesAndMoviesKept() = runBlocking {
        val cw = listOf(pr("e1", 600_000, 3_000_000, 5), pr("m1", 100_000, 6_000_000, 7, "MOVIE", null))
        val recent = listOf(pr("e2", 5_000, 3_000_000, 9)) + cw
        val res = ContinueWatching.build(cw, recent) { emptyList() }
        assertEquals(listOf("e2", "m1"), res.map { it.itemId })
    }
}
