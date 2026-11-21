package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.jellyfin.JfSegment
import de.ostlertekker.ultraiptv.core.jellyfin.JfSegments
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class JfSegmentsTest {
    private fun j(s: String) = Json.parseToJsonElement(s)

    @Test fun mediaSegments() {
        val r = JfSegments.mediaSegments(j("""{"Items":[{"Type":"Intro","StartTicks":120000000,"EndTicks":900000000},
            {"Type":"Outro","StartTicks":24000000000,"EndTicks":25200000000}],"TotalRecordCount":2}"""))
        assertEquals(listOf(JfSegment("Intro", 12_000, 90_000), JfSegment("Outro", 2_400_000, 2_520_000)), r)
    }

    @Test fun oldIntroSkipper() {
        val r = JfSegments.introSkipper(j("""{"Introduction":{"Valid":true,"IntroStart":12.5,"IntroEnd":80.0},"Credits":{"Valid":false,"IntroStart":0,"IntroEnd":0}}"""))
        assertEquals(listOf(JfSegment("Intro", 12_500, 80_000)), r)
        assertEquals(listOf(JfSegment("Intro", 1_000, 60_000)), JfSegments.introSkipper(j("""{"Valid":true,"IntroStart":1,"IntroEnd":60}""")))
    }
}
