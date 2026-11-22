package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.news.Feed
import de.ostlertekker.ultraiptv.core.news.NewsSources
import de.ostlertekker.ultraiptv.core.news.RssParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class NewsParserTest {
    @Test fun rssAndGoogleSuffix() {
        val xml = """<rss><channel><title>X</title>
            <item><title><![CDATA[Erste &amp; beste Meldung]]></title><pubDate>Wed, 18 Nov 2026 18:42:00 +0100</pubDate></item>
            <item><title>Stau auf der A9 - Nordkurier</title><source url="x">Nordkurier</source><pubDate>Wed, 18 Nov 2026 19:00:00 GMT</pubDate></item>
            </channel></rss>"""
        val items = RssParser.parse(xml, "tagesschau", false)
        assertEquals(2, items.size)
        assertEquals("Stau auf der A9", items[0].title)
        assertEquals("Nordkurier", items[0].source)
        assertEquals("Erste & beste Meldung", items[1].title)
        assertNotNull(items[1].time)
    }

    @Test fun atom() {
        val xml = """<feed><entry><title type="html">Hallo &#252;ber</title><updated>2026-11-18T17:00:00Z</updated></entry></feed>"""
        val items = RssParser.parse(xml, "X", true)
        assertEquals("Hallo über", items.single().title)
        assertTrue(items.single().local)
        assertNotNull(items.single().time)
    }

    @Test fun bundesland() {
        assertEquals("badenwuerttemberg", NewsSources.bundeslandSlug("Baden-Württemberg"))
        assertEquals("thueringen", NewsSources.bundeslandSlug("Thüringen"))
        assertEquals(null, NewsSources.bundeslandSlug("Wien"))
    }
}

/** Echter Abruf aller RSS-Feeds, die die Laufschrift nutzen kann – findet tote Adressen. */
class NewsFeedLiveTest {
    private fun fetch(f: Feed): String = (URL(f.url).openConnection() as HttpURLConnection).run {
        instanceFollowRedirects = true; connectTimeout = 15000; readTimeout = 15000
        setRequestProperty("User-Agent", "ULTRA-IPTV/1.0 (Android TV; RSS-Laufschrift)")
        if (responseCode !in 200..299) error("HTTP $responseCode")
        inputStream.bufferedReader().readText()
    }

    @Test fun allFeeds() {
        val feeds = linkedSetOf<Feed>()
        val combos = listOf("DE" to "de", "KE" to "en", "NG" to "en", "SN" to "fr", "EG" to "ar", "MX" to "es", "BR" to "pt",
            "US" to "en", "GB" to "en", "IN" to "en", "JP" to "en", "AU" to "en", "AE" to "ar", "FR" to "fr", "RU" to "ru",
            "TR" to "tr", "IT" to "it", "AT" to "de", "CA" to "fr", "GR" to "el")
        for ((cc, l) in combos) {
            NewsSources.autoNational(cc, l)?.let { feeds += it }
            for (id in listOf("tagesschau", "bbc", "dw", "france24", "aljazeera", "euronews")) NewsSources.national(id, cc, l)?.let { feeds += it }
        }
        feeds += NewsSources.bundeslandFeeds()
        val report = StringBuilder(); var bad = 0
        for (f in feeds) {
            val r = runCatching { RssParser.parse(fetch(f), f.label, false) }
            val line = r.fold({ "OK ${it.size.toString().padStart(3)}  ${f.url}  » ${it.firstOrNull()?.title?.take(60)}" }, { "FEHLER ${f.url}  $it" })
            // Reine Verbindungsprobleme (Zeitüberschreitung o. ä.) zählen nicht als tote Adresse
            val err = r.exceptionOrNull()
            // HTTP 403/429: Anbieter sperrt Rechenzentrums-Adressen des Testservers – keine tote Adresse
            val netGlitch = (err is java.io.IOException && err !is java.net.ProtocolException) ||
                (err is IllegalStateException && (err.message?.contains("HTTP 403") == true || err.message?.contains("HTTP 429") == true))
            if (!netGlitch && (r.isFailure || r.getOrNull().isNullOrEmpty())) bad++
            report.appendLine(line)
        }
        // Google News nur zur Info (Rechenzentrums-IPs werden dort oft geblockt)
        for (g in listOf(NewsSources.googleTop("KE", "en"), NewsSources.googleCity("Nairobi", "KE", "en"), NewsSources.googleCity("Berlin", "DE", "de")))
            report.appendLine("INFO " + runCatching { "OK ${RssParser.parse(fetch(g), g.label, true).size} ${g.url}" }.getOrElse { "FEHLER ${g.url} $it" })
        println(report)
        assertEquals("Fehlerhafte Feeds:\n$report", 0, bad)
    }
}
