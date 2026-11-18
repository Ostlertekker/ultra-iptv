package de.ostlertekker.ultraiptv

import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Vorübergehend: zeigt, was die Kursquellen wirklich antworten (Test schlägt absichtlich fehl). */
class StooqProbeTest {
    @Test fun probe() {
        val urls = listOf(
            "https://api.nasdaq.com/api/quote/AAPL/info?assetclass=stocks",
            "https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol?symbols=AAPL%7CNVDA&requestMethod=itv&noform=1&partnerId=2&fund=1&exthrs=1&output=json",
            "https://www.google.com/finance/quote/AAPL:NASDAQ",
            "https://financialmodelingprep.com/api/v3/quote-short/AAPL",
            "https://www.marketwatch.com/investing/stock/aapl",
            "https://api.boerse-frankfurt.de/v1/data/quote_box/single?isin=US0378331005&mic=XFRA",
            "https://www.tradegate.de/refresh.php?isin=US0378331005",
            "https://query1.finance.yahoo.com/v7/finance/spark?symbols=AAPL,NVDA&range=1d&interval=1d",
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
