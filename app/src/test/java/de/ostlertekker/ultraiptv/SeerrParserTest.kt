package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.seerr.SeerrParser
import de.ostlertekker.ultraiptv.core.seerr.SeerrStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeerrParserTest {
    private fun j(s: String) = Json.parseToJsonElement(s)

    @Test fun searchResults() {
        val r = SeerrParser.results(j("""{"page":1,"results":[
            {"id":603,"mediaType":"movie","title":"Matrix","releaseDate":"1999-03-30","posterPath":"/a.jpg","mediaInfo":{"status":5}},
            {"id":1399,"mediaType":"tv","name":"Game of Thrones","firstAirDate":"2011-04-17","posterPath":null},
            {"id":287,"mediaType":"person","name":"Brad Pitt"}]}"""))
        assertEquals(2, r.size)
        assertEquals("Matrix", r[0].title); assertEquals("1999", r[0].year); assertEquals(SeerrStatus.AVAILABLE, r[0].status)
        assertEquals("https://image.tmdb.org/t/p/w342/a.jpg", r[0].poster)
        assertTrue(r[1].isTv); assertNull(r[1].poster); assertEquals(SeerrStatus.UNKNOWN, r[1].status)
    }

    @Test fun seasonsSkipSpecials() {
        val s = SeerrParser.seasons(j("""{"id":1,"seasons":[{"seasonNumber":0,"name":"Specials"},{"seasonNumber":1,"name":"Staffel 1"},{"seasonNumber":2,"name":"Staffel 2"}],
            "mediaInfo":{"status":4,"seasons":[{"seasonNumber":1,"status":5}]}}"""))
        assertEquals(listOf(1, 2), s.map { it.number })
        assertEquals(SeerrStatus.AVAILABLE, s[0].status); assertEquals(SeerrStatus.UNKNOWN, s[1].status)
    }

    @Test fun requestsAndUrl() {
        val r = SeerrParser.requests(j("""{"results":[{"id":7,"status":2,"type":"movie","media":{"tmdbId":603,"mediaType":"movie","status":3},"requestedBy":{"id":4}}]}"""))
        assertEquals(603, r.single().tmdbId); assertEquals(4, r.single().by); assertEquals(3, r.single().mediaStatus)
        assertEquals("Wird bearbeitet", SeerrStatus.request(2, 3))
        assertEquals("Wartet auf Freigabe", SeerrStatus.request(1, 2))
        assertEquals("http://s:5055/api/v1/search?query=herr%20der%20ringe&page=1&language=de", SeerrParser.searchUrl("http://s:5055", " herr der ringe ", "de"))
    }
}
