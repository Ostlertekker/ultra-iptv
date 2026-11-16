package de.ostlertekker.ultraiptv.core.search

import de.ostlertekker.ultraiptv.core.model.Countries
import java.text.Normalizer

enum class SearchKind { LIVE, MOVIE, SERIES, EPISODE }

data class ParsedQuery(
    val raw: String,
    /** Übrige Suchwörter (normalisiert), nach Entfernen erkannter Filter. */
    val terms: List<String>,
    val kinds: Set<SearchKind>,
    /** Genre-Schlüssel mit Synonymen, die in genre/category gesucht werden. */
    val genres: Map<String, List<String>>,
    val language: String?,
    val country: String?,
    val yearFrom: Int?,
    val yearTo: Int?,
    val actor: String?,
) {
    val hasFilters: Boolean get() = kinds.isNotEmpty() || genres.isNotEmpty() || language != null || yearFrom != null || actor != null
    val text: String get() = terms.joinToString(" ")
}

/**
 * Lokale "intelligente" Suche: versteht natürliche Anfragen wie
 * "deutsche horrorfilme 80er", "filme mit tom hanks", "sport sender türkei" oder Tippfehler.
 * Läuft vollständig auf dem Gerät – keine Cloud, keine Datenweitergabe.
 */
object SmartSearch {

    val genreSynonyms: Map<String, List<String>> = linkedMapOf(
        "action" to listOf("action"),
        "komödie" to listOf("komödie", "komoedie", "comedy", "komödie", "lustig"),
        "horror" to listOf("horror", "grusel"),
        "thriller" to listOf("thriller", "spannung"),
        "krimi" to listOf("krimi", "crime", "kriminal"),
        "drama" to listOf("drama"),
        "doku" to listOf("doku", "documentary", "dokumentation", "documentaire"),
        "kinder" to listOf("kinder", "kids", "family", "familie", "animation", "zeichentrick", "cartoon"),
        "sci-fi" to listOf("sci-fi", "science fiction", "scifi", "science-fiction"),
        "fantasy" to listOf("fantasy"),
        "romantik" to listOf("romance", "romantik", "romanze", "liebe"),
        "abenteuer" to listOf("abenteuer", "adventure"),
        "western" to listOf("western"),
        "krieg" to listOf("krieg", "war"),
        "musik" to listOf("musik", "music", "musical"),
        "sport" to listOf("sport", "fussball", "fußball", "football", "soccer", "bundesliga", "sky sport", "dazn"),
        "news" to listOf("news", "nachrichten", "info"),
        "anime" to listOf("anime"),
    )

    /** Wörter, die auf einen Genre-Schlüssel verweisen (inkl. zusammengesetzter Formen wie "horrorfilme"). */
    private val genreTriggers: Map<String, String> = buildMap {
        genreSynonyms.forEach { (key, syns) -> syns.forEach { put(normalize(it), key) }; put(normalize(key), key) }
        put("lustige", "komödie"); put("gruselig", "horror"); put("kinderfilme", "kinder")
    }

    private val kindWords: Map<String, SearchKind> = mapOf(
        "film" to SearchKind.MOVIE, "filme" to SearchKind.MOVIE, "movie" to SearchKind.MOVIE, "movies" to SearchKind.MOVIE, "kino" to SearchKind.MOVIE,
        "serie" to SearchKind.SERIES, "serien" to SearchKind.SERIES, "series" to SearchKind.SERIES, "show" to SearchKind.SERIES, "staffel" to SearchKind.SERIES,
        "sender" to SearchKind.LIVE, "kanal" to SearchKind.LIVE, "kanaele" to SearchKind.LIVE, "live" to SearchKind.LIVE, "tv" to SearchKind.LIVE, "channel" to SearchKind.LIVE,
        "episode" to SearchKind.EPISODE, "folge" to SearchKind.EPISODE, "folgen" to SearchKind.EPISODE,
    )

    private val languageWords: Map<String, String> = mapOf(
        "deutsch" to "de", "deutsche" to "de", "deutschen" to "de", "deutscher" to "de", "german" to "de",
        "englisch" to "en", "englische" to "en", "english" to "en", "turkisch" to "tr", "tuerkisch" to "tr", "turkische" to "tr", "tuerkische" to "tr", "turkish" to "tr",
        "franzosisch" to "fr", "franzoesisch" to "fr", "french" to "fr", "italienisch" to "it", "italienische" to "it", "italian" to "it",
        "spanisch" to "es", "spanische" to "es", "spanish" to "es", "polnisch" to "pl", "polish" to "pl", "arabisch" to "ar", "arabic" to "ar",
        "russisch" to "ru", "russian" to "ru", "griechisch" to "el", "greek" to "el", "albanisch" to "sq", "kurdisch" to "ku",
    )

    private val stopWords = setOf("mit", "with", "und", "and", "der", "die", "das", "den", "ein", "eine", "von", "the", "a", "aus", "in", "im", "zeig", "zeige", "mir", "suche", "alle", "beste", "besten", "gute")

    fun normalize(s: String): String {
        val lower = s.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        val noMarks = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(NormRx.MARKS, "")
        return noMarks.replace(NormRx.NON_ALNUM, " ").trim().replace(NormRx.SPACES, " ")
    }

    fun parse(query: String): ParsedQuery {
        val raw = query.trim()
        var work = normalize(raw)
        var actor: String? = null
        // "mit <Name>" / "actor:<Name>" → Schauspieler
        Regex("""(?:^|\s)(?:mit|with|actor|schauspieler|darsteller)\s+([a-z][a-z .'-]{2,40})$""").find(work)?.let { m ->
            actor = m.groupValues[1].trim()
            work = work.substring(0, m.range.first).trim()
        }
        var yearFrom: Int? = null
        var yearTo: Int? = null
        val tokens = work.split(' ').filter { it.isNotBlank() }
        val rest = ArrayList<String>()
        val kinds = LinkedHashSet<SearchKind>()
        val genres = LinkedHashMap<String, List<String>>()
        var language: String? = null
        var country: String? = null
        for (tok in tokens) {
            val decade = Regex("""^(?:19|20)?(\d)0(?:er|s)$""").find(tok)
            val year = Regex("""^(19[2-9]\d|20[0-4]\d)$""").find(tok)
            when {
                decade != null -> {
                    val d = decade.groupValues[1].toInt()
                    val century = if (tok.startsWith("20") || d <= 2 && !tok.startsWith("19")) 2000 else 1900
                    yearFrom = century + d * 10; yearTo = century + d * 10 + 9
                }
                year != null -> { yearFrom = tok.toInt(); yearTo = tok.toInt() }
                tok in kindWords -> kinds += kindWords.getValue(tok)
                tok in languageWords -> language = languageWords.getValue(tok)
                tok in stopWords -> Unit
                else -> {
                    val g = genreTriggers[tok] ?: genreTriggers.entries.firstOrNull { (trigger, _) ->
                        trigger.length >= 5 && tok.length > trigger.length && tok.startsWith(trigger) &&
                            tok.removePrefix(trigger) in setOf("film", "filme", "serie", "serien", "sender", "s", "e")
                    }?.value
                    if (g != null) {
                        genres[g] = genreSynonyms.getValue(g)
                        val suffix = tok.removePrefix(genreTriggers.entries.first { it.value == g && tok.startsWith(it.key) }.key)
                        kindWords[suffix]?.let { kinds += it }
                    } else {
                        val c = Countries.detect(tok)?.takeIf { tok.length >= 4 }
                        if (c != null && language == null) { country = c; language = Countries.languageOf(c) } else rest += tok
                    }
                }
            }
        }
        return ParsedQuery(raw, rest, kinds, genres, language, country, yearFrom, yearTo, actor)
    }

    /** Levenshtein-Distanz mit Abbruch ab [max]. */
    fun distance(a: String, b: String, max: Int = 2): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > max) return max + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /**
     * Relevanz 0–100: exakter Treffer > Wortanfang > alle Wörter enthalten > Tippfehler-Toleranz.
     * 0 bedeutet: kein Treffer.
     */
    fun score(title: String, terms: List<String>): Int {
        if (terms.isEmpty()) return 50
        val t = normalize(title)
        val q = terms.joinToString(" ")
        if (t == q) return 100
        if (t.startsWith(q)) return 90
        val words = t.split(' ')
        if (words.any { it.startsWith(q) }) return 80
        if (q in t) return 70
        var matched = 0
        var fuzzy = 0
        for (term in terms) {
            when {
                words.any { it.startsWith(term) } || term in t -> matched++
                term.length >= 4 && words.any { w -> distance(w.take(term.length + 1), term, if (term.length >= 7) 2 else 1) <= (if (term.length >= 7) 2 else 1) } -> fuzzy++
            }
        }
        if (matched + fuzzy < terms.size) return 0
        return 40 + 15 * matched / terms.size - 5 * fuzzy
    }

    /** Gemeinsame Genres/Darsteller als Ähnlichkeitsmaß (0–1). */
    /** Genre/Darsteller-Teile einmalig aufbereiten (begrenzt, damit riesige Anbieter-Felder nichts blockieren). */
    fun parts(s: String?): Set<String> = s?.take(600)?.split(',', '/', '|')?.asSequence()?.take(20)
        ?.map { normalize(it) }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    fun similarity(genreA: String?, genreB: String?, castA: String?, castB: String?, categoryA: String?, categoryB: String?): Float =
        similarity(parts(genreA), parts(genreB), parts(castA), parts(castB), categoryA, categoryB)

    fun similarity(gA: Set<String>, gB: Set<String>, cA: Set<String>, cB: Set<String>, categoryA: String?, categoryB: String?): Float {
        val g = gA.count { it in gB }
        val c = cA.count { it in cB }
        val cat = if (categoryA != null && categoryA == categoryB) 1 else 0
        return (g * 0.25f + c * 0.35f + cat * 0.15f).coerceAtMost(1f)
    }
}

/** Vorkompilierte Ausdrücke – eigenes Objekt, damit sie vor SmartSearchs Initialisierung bereitstehen. */
private object NormRx {
    val MARKS = Regex("\\p{Mn}+")
    val NON_ALNUM = Regex("[^a-z0-9+]+")
    val SPACES = Regex("\\s+")
}
