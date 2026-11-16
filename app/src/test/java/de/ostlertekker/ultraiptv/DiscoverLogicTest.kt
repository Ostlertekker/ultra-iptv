package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.catchup.Catchup
import de.ostlertekker.ultraiptv.core.discover.CommandParser
import de.ostlertekker.ultraiptv.core.discover.Genre
import de.ostlertekker.ultraiptv.core.discover.Mood
import de.ostlertekker.ultraiptv.core.discover.MoodEngine
import de.ostlertekker.ultraiptv.core.discover.RemoteCommand
import de.ostlertekker.ultraiptv.core.discover.Section
import de.ostlertekker.ultraiptv.core.discover.SmartGroup
import de.ostlertekker.ultraiptv.core.discover.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverLogicTest {
    private val start = 1_700_000_000_000L
    private val end = start + 3_600_000L
    private val now = start + 7_200_000L

    @Test fun catchupShiftAppendsUtc() {
        assertEquals("http://x/live/1.m3u8?utc=1700000000&lutc=1700007200", Catchup.buildM3u("http://x/live/1.m3u8", "shift", null, start, end, now))
        assertEquals("http://x/a?b=1&utc=1700000000&lutc=1700007200", Catchup.buildM3u("http://x/a?b=1", "shift", null, start, end, now))
    }

    @Test fun catchupDefaultTemplate() {
        val url = Catchup.buildM3u("http://x/live", "default", "http://x/arch?s={utc}&e={utcend}&d={duration}&t=\${start}&f={utc:Y-m-d}", start, end, now)
        assertEquals("http://x/arch?s=1700000000&e=1700003600&d=3600&t=1700000000&f=2023-11-14", url)
    }

    @Test fun catchupAppendAndFlussonic() {
        assertEquals("http://x/ch.ts?utc=1700000000", Catchup.buildM3u("http://x/ch.ts", "append", "?utc={utc}", start, end, now))
        assertEquals("http://x/ch/archive-1700000000-3600.m3u8", Catchup.buildM3u("http://x/ch/index.m3u8", "flussonic", null, start, end, now))
        assertNull(Catchup.buildM3u("http://x", "append", null, start, end, now))
    }

    @Test fun catchupAttrsAndWindow() {
        val i = Catchup.fromM3uAttrs(mapOf("catchup" to "default", "catchup-days" to "3", "catchup-source" to "?u={utc}"))!!
        assertEquals(3, i.days); assertEquals("default", i.mode)
        assertNull(Catchup.fromM3uAttrs(mapOf("tvg-id" to "a")))
        assertTrue(Catchup.available(3, start, now))
        assertTrue(!Catchup.available(3, now - 4 * Catchup.DAY_MS, now))
        assertTrue(!Catchup.available(0, start, now))
    }

    @Test fun xtreamTimeshift() {
        val ref = Catchup.xtreamRef("123", start, end, "Europe/Berlin")
        val p = Catchup.parseXtreamRef(ref.removePrefix("xtream://timeshift/"))!!
        assertEquals("123", p.streamId); assertEquals(60, p.durationMin); assertEquals("Europe/Berlin", p.tz)
        assertEquals("http://s:8080/timeshift/u/p/60/2023-11-14:23-13/123.ts", Catchup.xtreamPath("http://s:8080/", "u", "p", "123", start, 60, "Europe/Berlin"))
    }

    @Test fun sportsAndGenres() {
        assertEquals(Sport.CHAMPIONS, Sport.detect("UEFA Champions League: Bayern – Real"))
        assertEquals(Sport.FOOTBALL, Sport.detect("Fußball: 1. Bundesliga, 12. Spieltag"))
        assertEquals(Sport.F1, Sport.detect("Formel 1: Großer Preis von Brasilien"))
        assertEquals(Sport.TENNIS, Sport.detect("Tennis: ATP Finals"))
        assertNull(Sport.detect("Tatort: Der Fall"))
        assertTrue(Genre.CRIME in Genre.detect("Tatort: Kommissar ermittelt"))
    }

    @Test fun moodAndGroups() {
        assertEquals(listOf(Mood.RELAX, Mood.LAUGH), Mood.fromText("bin müde und will was Lustiges"))
        assertTrue(MoodEngine.score(listOf(Mood.LAUGH), "Comedy-Show am Abend") > MoodEngine.score(listOf(Mood.LAUGH), "Tagesschau"))
        val g = SmartGroup.detect("Sky Sport Bundesliga 1 FHD", "DE Sport")
        assertTrue(SmartGroup.SPORT in g); assertTrue(SmartGroup.FHD in g)
        assertEquals(SmartGroup.baseName("DE: Das Erste HD"), SmartGroup.baseName("Das Erste FHD"))
    }

    @Test fun smartRemoteExamples() {
        assertEquals(RemoteCommand.BestSport, CommandParser.parse("Öffne den besten verfügbaren Sportstream."))
        assertEquals(RemoteCommand.ShowTopic("fußball"), CommandParser.parse("Zeige mir alle Sender mit Fußball."))
        assertEquals(RemoteCommand.PreviousChannel, CommandParser.parse("Wechsle zum vorherigen Sender."))
        assertEquals(RemoteCommand.MultiView, CommandParser.parse("Öffne die Mehrfachansicht."))
        assertEquals(RemoteCommand.Tonight, CommandParser.parse("Zeige mir, was heute Abend läuft."))
        assertEquals(RemoteCommand.OpenChannel("12", 12), CommandParser.parse("Sender 12"))
        assertEquals(RemoteCommand.OpenChannel("pro7", null), CommandParser.parse("Schalte auf Pro7"))
        assertEquals(RemoteCommand.OpenSection(Section.FAVORITES), CommandParser.parse("Öffne meine Favoriten"))
        assertEquals(RemoteCommand.ShowTopic("tatort"), CommandParser.parse("Wo läuft Tatort?"))
        assertTrue(CommandParser.parse("ich will was lustiges") is RemoteCommand.MoodPick)
    }
}
