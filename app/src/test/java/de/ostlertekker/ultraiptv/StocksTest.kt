package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.stocks.StockUniverse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class StocksParserTest {
    @Test fun parseAndRank() {
        val csv = "Symbol,Date,Time,Open,High,Low,Close,Volume,Prev\nAAPL.US,2026-11-18,22:00:00,1,1,1,300,1,290\nNVDA.US,2026-11-18,22:00:00,1,1,1,200,1,210\n"
        val q = StockUniverse.parse(csv)
        assertEquals(2, q.size)
        val top = StockUniverse.top10(q)
        assertEquals("Nvidia", top[0].name) // 200 × 24,3 Mrd > 300 × 14,8 Mrd
        assertTrue(top[1].changePct!! > 3.4 && top[1].changePct!! < 3.5)
    }
}

/** Echter Abruf bei Stooq – zeigt die Rohantwort, falls Format oder Zugang sich ändern. */
class StocksLiveTest {
    @Test fun stooq() {
        val txt = try {
            (URL(StockUniverse.url()).openConnection() as HttpURLConnection).run {
                connectTimeout = 15000; readTimeout = 15000
                setRequestProperty("User-Agent", "ULTRA-IPTV/1.0 (Android TV)")
                inputStream.bufferedReader().readText()
            }
        } catch (e: java.io.IOException) { org.junit.Assume.assumeNoException("Stooq nicht erreichbar", e); return }
        val q = StockUniverse.parse(txt)
        println("STOOQ-ANTWORT:\n" + txt.take(3000))
        println("TOP10: " + StockUniverse.top10(q).joinToString { "${it.name} ${it.price} ${it.changePct}" })
        assertTrue("Zu wenige Kurse (${q.size}). Antwort:\n" + txt.take(2000), q.size >= 15)
        assertTrue("Keine Tagesveränderung. Antwort:\n" + txt.take(800), q.count { it.changePct != null } >= 15)
    }
}
