package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.network.M3uKind
import de.ostlertekker.ultraiptv.core.network.M3uParser
import de.ostlertekker.ultraiptv.core.network.SafeLog
import de.ostlertekker.ultraiptv.core.network.XmltvParser
import de.ostlertekker.ultraiptv.core.network.XmltvProgramme
import de.ostlertekker.ultraiptv.core.network.XtreamUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {
    private val sample = """
        #EXTM3U url-tvg="http://epg.example/guide.xml.gz" x-tvg-url="http://other"
        #EXTINF:-1 tvg-id="das.erste" tvg-name="Das Erste HD" tvg-logo="http://logo/ard.png" group-title="Deutschland",Das Erste HD
        http://srv.example/live/user/pass/1.ts
        #EXTINF:-1 tvg-id="" group-title="Filme, Action",Film, mit Komma (2020)
        #EXTVLCOPT:http-user-agent=Test
        http://srv.example/movie/user/pass/55.mkv
        #EXTINF:-1 group-title="Serien",Dark S02 E05
        http://srv.example/series/user/pass/77.mp4
        #EXTINF:-1,Ohne Gruppe
        #EXTGRP:Sonstiges
        http://srv.example/stream/9
    """.trimIndent()

    @Test fun parsesEntriesAndHeader() {
        val r = M3uParser.parse(sample.lineSequence())
        assertEquals("http://epg.example/guide.xml.gz", r.epgUrl)
        assertEquals(4, r.entries.size)
        val first = r.entries[0]
        assertEquals("Das Erste HD", first.name)
        assertEquals("das.erste", first.tvgId)
        assertEquals("http://logo/ard.png", first.logo)
        assertEquals("Deutschland", first.group)
        assertEquals("Film, mit Komma (2020)", r.entries[1].name)
        assertEquals("Filme, Action", r.entries[1].group)
        assertNull(r.entries[1].tvgId)
        assertEquals("Sonstiges", r.entries[3].group)
    }

    @Test fun classifiesKinds() {
        val r = M3uParser.parse(sample.lineSequence())
        assertEquals(M3uKind.LIVE, M3uParser.classify(r.entries[0]))
        assertEquals(M3uKind.MOVIE, M3uParser.classify(r.entries[1]))
        assertEquals(M3uKind.EPISODE, M3uParser.classify(r.entries[2]))
        assertEquals(M3uKind.LIVE, M3uParser.classify(r.entries[3]))
    }

    @Test fun extractsEpisodeInfo() {
        val e = M3uParser.episodeInfo("Dark S02 E05")!!
        assertEquals("Dark", e.showName); assertEquals(2, e.season); assertEquals(5, e.episode)
        val e2 = M3uParser.episodeInfo("The.Office.s01e12")!!
        assertEquals("The.Office", e2.showName); assertEquals(1, e2.season); assertEquals(12, e2.episode)
        assertNull(M3uParser.episodeInfo("Tagesschau 20 Uhr"))
    }

    @Test fun emptyPlaylist() {
        assertTrue(M3uParser.parse(sequenceOf("#EXTM3U")).entries.isEmpty())
    }
}

class XmltvParserTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv>
          <channel id="das.erste"><display-name>Das Erste</display-name></channel>
          <programme start="20240101120000 +0100" stop="20240101130000 +0100" channel="das.erste">
            <title lang="de">Mittagsmagazin</title><desc lang="de">Nachrichten &amp; mehr</desc>
          </programme>
          <programme start="20240101130000 +0100" stop="20240101140000 +0100" channel="das.erste">
            <title>Tagesschau</title>
          </programme>
          <programme start="20240105130000 +0000" stop="20240105140000 +0000" channel="das.erste"><title>Später</title></programme>
        </tv>"""

    @Test fun parsesTimes() {
        assertEquals(1704106800000L, XmltvParser.parseXmltvTime("20240101120000 +0100"))
        assertEquals(1704110400000L, XmltvParser.parseXmltvTime("20240101120000 +0000"))
        assertEquals(1704110400000L, XmltvParser.parseXmltvTime("202401011200"))
        assertNull(XmltvParser.parseXmltvTime("bad"))
    }

    @Test fun parsesProgrammesInWindow() {
        val out = ArrayList<XmltvProgramme>()
        val from = 1704100000000L; val to = 1704200000000L
        val n = XmltvParser { org.kxml2.io.KXmlParser() }.parse(xml.byteInputStream(), 0, from, to) { out += it }
        assertEquals(2, n)
        assertEquals("Mittagsmagazin", out[0].title)
        assertEquals("Nachrichten & mehr", out[0].description)
        assertEquals("das.erste", out[0].channel)
        assertEquals(1704106800000L, out[0].startMs)
        assertNull(out[1].description)
    }

    @Test fun appliesOffset() {
        val out = ArrayList<XmltvProgramme>()
        XmltvParser { org.kxml2.io.KXmlParser() }.parse(xml.byteInputStream(), 3_600_000L, 0, Long.MAX_VALUE) { out += it }
        assertEquals(1704106800000L + 3_600_000L, out[0].startMs)
        assertEquals(3, out.size)
    }
}

class XtreamUrlsTest {
    @Test fun normalizesServer() {
        assertEquals("http://host:8080", XtreamUrls.normalizeServer(" host:8080/ "))
        assertEquals("https://host", XtreamUrls.normalizeServer("https://host/player_api.php"))
    }

    @Test fun buildsUrlsWithEncoding() {
        assertEquals("http://h/player_api.php?username=a%40b&password=p+w&action=get_live_streams",
            XtreamUrls.api("http://h", "a@b", "p w", "get_live_streams"))
        assertEquals("http://h/live/u/p%20w/12.ts", XtreamUrls.stream("http://h", "u", "p w", "live", "12", "ts"))
        assertEquals("http://h/xmltv.php?username=u&password=p", XtreamUrls.xmltv("http://h", "u", "p"))
    }
}

class SafeLogTest {
    @Test fun redactsCredentials() {
        val s = SafeLog.redact("GET http://h/player_api.php?username=max&password=geheim&action=x failed; http://h/live/max/geheim/1.ts")
        assertFalse(s.contains("geheim"))
        assertFalse(s.contains("max"))
        assertTrue(s.contains("password=***"))
        assertTrue(s.contains("/live/***/***/"))
    }
}
