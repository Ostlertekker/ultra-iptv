package de.ostlertekker.ultraiptv

import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Vorübergehend: zeigt, was die Kursquellen wirklich antworten (Test schlägt absichtlich fehl). */
class StooqProbeTest {
    @Test fun probe() {
        val urls = listOf(
            "https://stooq.com/q/l/?s=aapl.us+nvda.us&f=sd2t2ohlcvp&h&e=csv",
            "https://stooq.com/q/l/?s=aapl.us&f=sd2t2ohlcv&h&e=csv",
            "https://stooq.pl/q/l/?s=aapl.us&f=sd2t2ohlcv&h&e=csv",
            "https://stooq.com/q/l/?s=aapl.us&f=sd2t2ohlcv&h&e=json",
            "https://query1.finance.yahoo.com/v8/finance/chart/AAPL?range=1d&interval=1d",
            "https://query2.finance.yahoo.com/v8/finance/chart/NVDA?range=1d&interval=1d",
        )
        val sb = StringBuilder("PROBE\n")
        for (u in urls) {
            sb.append("=== $u\n")
            runCatching {
                (URL(u).openConnection() as HttpURLConnection).run {
                    connectTimeout = 15000; readTimeout = 15000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; AFTKA) ULTRA-IPTV/1.0")
                    val code = responseCode
                    val body = (if (code in 200..299) inputStream else errorStream)?.bufferedReader()?.readText().orEmpty()
                    sb.append("HTTP $code ${contentType}\n${body.take(700)}\n")
                }
            }.onFailure { sb.append("FEHLER $it\n") }
        }
        throw AssertionError(sb.toString())
    }
}
