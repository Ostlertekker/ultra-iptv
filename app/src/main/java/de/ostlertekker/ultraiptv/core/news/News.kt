package de.ostlertekker.ultraiptv.core.news

import de.ostlertekker.ultraiptv.core.data.Keys
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Eine Schlagzeile der Laufschrift. Nur Titel, Quelle und Zeit – keine Artikeltexte. */
data class NewsItem(val title: String, val source: String, val time: Long?, val local: Boolean)

/** Ein RSS-Feed mit Anzeigename. */
data class Feed(val url: String, val label: String)

/**
 * Wählbare Nachrichtenquellen – ausschließlich offene RSS-Feeds, ohne Konto oder Schlüssel.
 * Die passende Region/Sprache des Feeds wird aus dem Land des Wetter-Orts und der Gerätesprache gewählt.
 */
object NewsSources {
    val choices = listOf(
        "auto" to "Automatisch",
        "tagesschau" to "tagesschau",
        "bbc" to "BBC",
        "dw" to "Deutsche Welle",
        "france24" to "France 24",
        "aljazeera" to "Al Jazeera",
        "euronews" to "Euronews",
        "google" to "Google News",
        "off" to "Aus",
    )
    fun label(id: String) = choices.firstOrNull { it.first == id }?.second ?: "Automatisch"

    private val AFRICA = setOf("DZ","AO","BJ","BW","BF","BI","CM","CV","CF","TD","KM","CG","CD","CI","DJ","EG","GQ","ER","SZ","ET","GA","GM","GH","GN","GW","KE","LS","LR","LY","MG","MW","ML","MR","MU","MA","MZ","NA","NE","NG","RW","ST","SN","SC","SL","SO","ZA","SS","SD","TZ","TG","TN","UG","ZM","ZW")
    private val MIDEAST = setOf("AE","BH","IL","IQ","IR","JO","KW","LB","OM","PS","QA","SA","SY","YE","TR")
    private val LATAM = setOf("AR","BO","BR","CL","CO","CR","CU","DO","EC","SV","GT","HN","MX","NI","PA","PY","PE","PR","UY","VE","JM","TT","HT","BS","BB")
    private val NAM = setOf("US","CA")
    private val OCEANIA = setOf("AU","NZ","FJ","PG","WS","TO")
    private val ASIA = setOf("AF","BD","BT","BN","KH","CN","HK","IN","ID","JP","KZ","KG","LA","MO","MY","MV","MN","MM","NP","KP","KR","PK","PH","SG","LK","TW","TJ","TH","TL","TM","UZ","VN")

    enum class Region { AFRICA, MIDEAST, LATAM, NAM, OCEANIA, ASIA, EUROPE }
    fun region(cc: String) = when (cc.uppercase()) {
        in AFRICA -> Region.AFRICA; in MIDEAST -> Region.MIDEAST; in LATAM -> Region.LATAM; in NAM -> Region.NAM
        in OCEANIA -> Region.OCEANIA; in ASIA -> Region.ASIA; else -> Region.EUROPE
    }

    /** Überregionaler Feed einer Quelle für Land [cc] und Sprache [lang]. */
    fun national(id: String, cc: String, lang: String): Feed? {
        val r = region(cc)
        return when (id) {
            "tagesschau" -> Feed("https://www.tagesschau.de/index~rss2.xml", "tagesschau")
            "dw" -> {
                val l = when (lang) { "de" -> "de"; "ar" -> "ar"; "ru" -> "ru"; else -> "en" }
                Feed("https://rss.dw.com/xml/rss-$l-all", "DW")
            }
            "bbc" -> when {
                lang == "es" -> Feed("https://feeds.bbci.co.uk/mundo/rss.xml", "BBC Mundo")
                lang == "pt" -> Feed("https://feeds.bbci.co.uk/portuguese/rss.xml", "BBC Brasil")
                lang == "ar" -> Feed("https://feeds.bbci.co.uk/arabic/rss.xml", "BBC Arabic")
                lang == "ru" -> Feed("https://feeds.bbci.co.uk/russian/rss.xml", "BBC Russian")
                cc.equals("GB", true) -> Feed("https://feeds.bbci.co.uk/news/uk/rss.xml", "BBC News")
                cc.equals("IN", true) -> Feed("https://feeds.bbci.co.uk/news/world/asia/india/rss.xml", "BBC India")
                else -> Feed("https://feeds.bbci.co.uk/news/world/" + when (r) {
                    Region.AFRICA -> "africa/"; Region.MIDEAST -> "middle_east/"; Region.LATAM -> "latin_america/"
                    Region.NAM -> "us_and_canada/"; Region.OCEANIA -> "australia/"; Region.ASIA -> "asia/"; Region.EUROPE -> "europe/"
                } + "rss.xml", "BBC News")
            }
            "france24" -> {
                val l = when (lang) { "fr" -> "fr"; "es" -> "es"; "ar" -> "ar"; else -> "en" }
                val sec = when (l) {
                    "fr" -> when (r) { Region.AFRICA -> "afrique/"; Region.MIDEAST -> "moyen-orient/"; Region.LATAM, Region.NAM -> ""; Region.ASIA, Region.OCEANIA -> "asie-pacifique/"; Region.EUROPE -> "europe/" }
                    "en" -> when (r) { Region.AFRICA -> "africa/"; Region.MIDEAST -> "middle-east/"; Region.LATAM, Region.NAM -> "americas/"; Region.ASIA, Region.OCEANIA -> "asia-pacific/"; Region.EUROPE -> "europe/" }
                    else -> ""
                }
                Feed("https://www.france24.com/$l/${sec}rss", "France 24")
            }
            "aljazeera" -> Feed("https://www.aljazeera.com/xml/rss/all.xml", "Al Jazeera")
            "euronews" -> {
                val host = when (lang) { "de" -> "de"; "fr" -> "fr"; "es" -> "es"; "it" -> "it"; "pt" -> "pt"; "ru" -> "ru"; "tr" -> "tr"; "el" -> "gr"; "hu" -> "hu"; "fa" -> "per"; "ar" -> "arabic"; else -> "www" }
                Feed("https://$host.euronews.com/rss", "Euronews")
            }
            "google" -> googleTop(cc, lang)
            else -> null
        }
    }

    /** Automatische Wahl: Deutschland → tagesschau, sonst ein großer Sender passend zu Sprache und Region. */
    fun autoNational(cc: String, lang: String): Feed? = when {
        cc.equals("DE", true) || (lang == "de" && cc.uppercase() in setOf("AT", "CH", "LI", "LU")) -> national("tagesschau", cc, lang)
        lang == "de" -> national("dw", cc, lang)
        lang == "fr" -> national("france24", cc, lang)
        lang in setOf("it", "tr", "el", "hu", "fa") -> national("euronews", cc, lang)
        else -> national("bbc", cc, lang)
    }

    private val BUNDESLAND = mapOf(
        "badenwuerttemberg" to "Baden-Württemberg", "bayern" to "Bayern", "berlin" to "Berlin", "brandenburg" to "Brandenburg",
        "bremen" to "Bremen", "hamburg" to "Hamburg", "hessen" to "Hessen", "mecklenburgvorpommern" to "Mecklenburg-Vorpommern",
        "niedersachsen" to "Niedersachsen", "nordrheinwestfalen" to "Nordrhein-Westfalen", "rheinlandpfalz" to "Rheinland-Pfalz",
        "saarland" to "Saarland", "sachsen" to "Sachsen", "sachsenanhalt" to "Sachsen-Anhalt", "schleswigholstein" to "Schleswig-Holstein",
        "thueringen" to "Thüringen",
    )
    fun bundeslandSlug(region: String): String? {
        val s = region.lowercase(Locale.GERMAN).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .filter { it in 'a'..'z' }
        return s.takeIf { it in BUNDESLAND }
    }
    fun bundeslandFeeds() = BUNDESLAND.keys.map { tagesschauRegional(it) }
    private fun tagesschauRegional(slug: String) =
        Feed("https://www.tagesschau.de/inland/regional/$slug/index~rss2.xml", "tagesschau ${BUNDESLAND[slug]}")

    private fun googleParams(cc: String, lang: String): String {
        val c = cc.uppercase().ifBlank { "US" }; val l = lang.ifBlank { "en" }
        return "hl=$l-$c&gl=$c&ceid=$c:$l"
    }
    fun googleTop(cc: String, lang: String) = Feed("https://news.google.com/rss?" + googleParams(cc, lang), "Google News")
    fun googleCity(city: String, cc: String, lang: String) =
        Feed("https://news.google.com/rss/search?q=" + URLEncoder.encode("\"$city\"", "UTF-8") + "&" + googleParams(cc, lang), "Google News")

    /** Lokaler Feed: Deutschland → tagesschau-Bundesland, sonst Google-News-Suche nach der Stadt. */
    fun local(city: String, region: String, cc: String, lang: String): Feed? = when {
        cc.equals("DE", true) -> bundeslandSlug(region)?.let { tagesschauRegional(it) }
        city.isNotBlank() -> googleCity(city, cc, lang)
        else -> null
    }
}

/**
 * Nachrichten-Laufschrift. Abfrage-Höflichkeit: höchstens alle 15 Minuten pro Feed (tagesschau erlaubt
 * 60 Anfragen/Stunde), bedingte Anfragen (ETag / If-Modified-Since), bei 429/503 wird die Pause bis 2 Stunden verdoppelt.
 */
@Singleton
class NewsRepository @Inject constructor(private val http: OkHttpClient, private val settings: SettingsRepository) {
    private val _items = MutableStateFlow<List<NewsItem>>(emptyList())
    val items: StateFlow<List<NewsItem>> = _items.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var lastError: String? = null

    private class Cached(val etag: String?, val modified: String?, val items: List<NewsItem>)
    private val cache = HashMap<String, Cached>()

    private data class Cfg(val source: String, val local: Boolean, val place: String, val region: String)

    init {
        scope.launch {
            settings.settings.map { Cfg(it.newsSource, it.newsLocal, it.weatherPlace, it.weatherRegion) }.distinctUntilChanged().collectLatest { c ->
                if (c.source == "off") { _items.value = emptyList(); return@collectLatest }
                val lang = Locale.getDefault().language.ifBlank { "de" }
                val city = c.place.substringBefore(",").trim()
                val cc = c.place.substringAfterLast(",", "").trim().ifBlank { Locale.getDefault().country.ifBlank { "DE" } }
                var region = c.region
                // Ältere Einstellungen ohne Bundesland: einmalig über die Ortssuche nachtragen
                if (region.isBlank() && city.isNotBlank() && cc.equals("DE", true)) {
                    region = runCatching { lookupRegion(city) }.getOrNull().orEmpty()
                    if (region.isNotBlank()) { settings.set(Keys.WEATHER_REGION, region); return@collectLatest }
                }
                val national = if (c.source == "auto") NewsSources.autoNational(cc, lang) else NewsSources.national(c.source, cc, lang)
                val local = if (c.local) NewsSources.local(city, region, cc, lang) else null
                var backoff = 15 * 60_000L
                while (true) {
                    val errs = mutableListOf<String>()
                    var limited = false
                    val nat = national?.let { f -> runCatching { load(f, false) }.onFailure { errs += "${f.label}: ${it.message}"; if (it is RateLimited) limited = true }.getOrNull() }.orEmpty()
                    val loc = local?.let { f -> runCatching { load(f, true) }.onFailure { errs += "${f.label}: ${it.message}"; if (it is RateLimited) limited = true }.getOrNull() }.orEmpty()
                    val mixed = mix(nat.take(15), loc.take(10))
                    if (mixed.isNotEmpty()) _items.value = mixed
                    lastError = errs.takeIf { it.isNotEmpty() }?.let { SimpleDateFormat("HH:mm", Locale.GERMAN).format(Date()) + " " + it.joinToString("; ") }
                    backoff = if (limited) (backoff * 2).coerceAtMost(2 * 60 * 60_000L) else 15 * 60_000L
                    delay(if (errs.isNotEmpty() && !limited && mixed.isEmpty()) 2 * 60_000L else backoff)
                }
            }
        }
    }

    private class RateLimited(code: Int) : Exception("HTTP $code – Quelle bittet um Pause")

    private fun lookupRegion(city: String): String? {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=de&format=json&name=" + URLEncoder.encode(city, "UTF-8")
        val o = http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (!r.isSuccessful) return null; Json.parseToJsonElement(r.body!!.string()) as JsonObject }
        return (((o["results"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("admin1") as? JsonPrimitive)?.contentOrNull
    }

    private fun load(f: Feed, local: Boolean): List<NewsItem> {
        val old = cache[f.url]
        val req = Request.Builder().url(f.url).header("User-Agent", "ULTRA-IPTV/1.0 (Android TV; RSS-Laufschrift)")
        old?.etag?.let { req.header("If-None-Match", it) }
        old?.modified?.let { req.header("If-Modified-Since", it) }
        return http.newCall(req.build()).execute().use { r ->
            when {
                r.code == 304 && old != null -> old.items
                r.code == 429 || r.code == 503 -> throw RateLimited(r.code)
                !r.isSuccessful -> error("HTTP ${r.code}")
                else -> {
                    val items = RssParser.parse(r.body!!.string(), f.label, local)
                    if (items.isEmpty()) error("keine Meldungen")
                    cache[f.url] = Cached(r.header("ETag"), r.header("Last-Modified"), items)
                    items
                }
            }
        }
    }

    /** Abwechselnd zwei überregionale und eine lokale Meldung. */
    private fun mix(nat: List<NewsItem>, loc: List<NewsItem>): List<NewsItem> {
        val out = ArrayList<NewsItem>(nat.size + loc.size)
        val a = nat.iterator(); val b = loc.iterator()
        while (a.hasNext() || b.hasNext()) {
            repeat(2) { if (a.hasNext()) out += a.next() }
            if (b.hasNext()) out += b.next()
        }
        return out
    }
}

/** Schlanker RSS-/Atom-Parser (nur Titel, Quelle, Zeit). */
internal object RssParser {
    private val itemRe = Regex("<(item|entry)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private fun tag(body: String, name: String): String? =
        Regex("<$name\\b[^>]*>(.*?)</$name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(body)?.groupValues?.get(1)

    fun clean(s: String): String {
        var t = s.trim()
        t = t.replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
        t = t.replace(Regex("<[^>]+>"), " ")
        t = t.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        t = Regex("&#(x?[0-9a-fA-F]+);").replace(t) { m ->
            val v = m.groupValues[1]
            runCatching { String(Character.toChars(if (v.startsWith("x")) v.drop(1).toInt(16) else v.toInt())) }.getOrDefault("")
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private val rfc822 = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "dd MMM yyyy HH:mm:ss Z")
    private val iso = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'")
    fun parseDate(s: String?): Long? {
        val v = s?.let { clean(it) }?.takeIf { it.isNotBlank() } ?: return null
        for (p in rfc822 + iso) {
            val f = SimpleDateFormat(p, Locale.US)
            if (p.endsWith("'Z'")) f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            runCatching { return f.parse(v)!!.time }
        }
        return null
    }

    fun parse(xml: String, label: String, local: Boolean): List<NewsItem> =
        itemRe.findAll(xml).mapNotNull { m ->
            val body = m.groupValues[2]
            var title = clean(tag(body, "title") ?: return@mapNotNull null)
            if (title.isBlank()) return@mapNotNull null
            // Sendungs-Videos („tagesschau in 100 Sekunden“ usw.) sind keine Schlagzeilen
            if (title.startsWith("tagesschau", ignoreCase = true)) return@mapNotNull null
            // Google News: „Schlagzeile - Zeitung“ → Zeitung als Quelle anzeigen
            val src = tag(body, "source")?.let { clean(it) }?.takeIf { it.isNotBlank() }
            if (src != null && title.endsWith(" - $src")) title = title.removeSuffix(" - $src")
            NewsItem(title, src ?: label, parseDate(tag(body, "pubDate") ?: tag(body, "dc:date") ?: tag(body, "updated") ?: tag(body, "published")), local)
        }.distinctBy { it.title }.sortedByDescending { it.time ?: 0L }.toList()
}
