package de.ostlertekker.ultraiptv.core.stocks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Ein Börsenwert in der Laufschrift. */
data class StockQuote(val name: String, val symbol: String, val price: Double, val changePct: Double?, val marketCap: Double)

/**
 * Die ~20 wertvollsten börsennotierten Firmen der Welt (US-Börse, Kurse in US-Dollar). Die Top 10 werden
 * bei jedem Abruf neu aus Kurs × ausstehenden Aktien berechnet – Reihenfolge und Zusammensetzung passen sich
 * also automatisch an. Aktienanzahl: Näherungswerte (Stand Herbst 2026), ändern sich nur langsam.
 */
internal object StockUniverse {
    data class Co(val name: String, val symbol: String, val sharesBn: Double)
    val companies = listOf(
        Co("Nvidia", "nvda.us", 24.3), Co("Alphabet", "googl.us", 12.1), Co("Apple", "aapl.us", 14.8),
        Co("Microsoft", "msft.us", 7.43), Co("Amazon", "amzn.us", 10.7), Co("Broadcom", "avgo.us", 4.77),
        Co("Meta", "meta.us", 2.56), Co("TSMC", "tsm.us", 5.19), Co("Tesla", "tsla.us", 3.22),
        Co("SpaceX", "spcx.us", 13.4), Co("Berkshire Hathaway", "brk-b.us", 2.16), Co("Eli Lilly", "lly.us", 0.897),
        Co("Walmart", "wmt.us", 7.98), Co("JPMorgan", "jpm.us", 2.73), Co("Visa", "v.us", 1.93),
        Co("Oracle", "orcl.us", 2.84), Co("Netflix", "nflx.us", 4.25), Co("Exxon Mobil", "xom.us", 4.25),
        Co("Palantir", "pltr.us", 2.37), Co("Mastercard", "ma.us", 0.90),
    )
    fun url() = "https://stooq.com/q/l/?s=" + companies.joinToString("+") { it.symbol } + "&f=sd2t2ohlcvp&h&e=csv"

    /** Stooq-CSV (mit Kopfzeile) → Kurse; Spalten werden über die Kopfzeile gefunden. */
    fun parse(csv: String): List<StockQuote> {
        val lines = csv.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val head = lines[0].split(',').map { it.trim().lowercase() }
        val iSym = head.indexOfFirst { it.startsWith("symbol") }.takeIf { it >= 0 } ?: return emptyList()
        val iClose = head.indexOfFirst { it == "close" }.takeIf { it >= 0 } ?: return emptyList()
        val iPrev = head.indexOfFirst { it.startsWith("prev") }
        val bySym = companies.associateBy { it.symbol.uppercase() }
        return lines.drop(1).mapNotNull { l ->
            val c = l.split(',')
            val co = bySym[c.getOrNull(iSym)?.trim()?.uppercase()] ?: return@mapNotNull null
            val price = c.getOrNull(iClose)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            val prev = if (iPrev >= 0) c.getOrNull(iPrev)?.trim()?.toDoubleOrNull() else null
            StockQuote(co.name, co.symbol.substringBefore('.').uppercase(), price,
                prev?.takeIf { it > 0 }?.let { (price - it) / it * 100 }, price * co.sharesBn * 1e9)
        }
    }

    fun top10(q: List<StockQuote>) = q.sortedByDescending { it.marketCap }.take(10)
}

/** Top-10-Börsenwerte über Stooq (kostenlos, ohne Schlüssel, Kurse verzögert). Ein Abruf alle 5 Minuten. */
@Singleton
class StocksRepository @Inject constructor(private val http: OkHttpClient) {
    private val _top = MutableStateFlow<List<StockQuote>>(emptyList())
    val top: StateFlow<List<StockQuote>> = _top.asStateFlow()
    @Volatile var lastError: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            while (true) {
                runCatching {
                    http.newCall(Request.Builder().url(StockUniverse.url()).header("User-Agent", "ULTRA-IPTV/1.0 (Android TV)").build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        StockUniverse.parse(r.body!!.string()).also { if (it.isEmpty()) error("keine Kurse in der Antwort") }
                    }
                }.onSuccess { _top.value = StockUniverse.top10(it); lastError = null }
                    .onFailure { lastError = SimpleDateFormat("HH:mm", Locale.GERMAN).format(Date()) + " Stooq: " + it.message }
                delay(if (lastError != null && _top.value.isEmpty()) 2 * 60_000L else 5 * 60_000L)
            }
        }
    }
}
