package de.ostlertekker.ultraiptv.core.discover

/** Hilfen, um Spiele der Live-Spielstände (englische Namen) im deutschen TV-Programm zu finden. */
object TvMatch {
    private val generic = setOf("united", "city", "real", "borussia", "club", "sporting", "athletic", "atletico", "olympique", "saint", "the",
        "town", "rovers", "wanderers", "hotspur", "albion", "county", "calcio", "sport", "sports", "team", "national")
    /** Englische ESPN-Schreibweisen → zusätzlich deutsche Begriffe. */
    private val german = mapOf(
        "munich" to "munchen", "cologne" to "koln", "nuremberg" to "nurnberg", "milan" to "mailand", "rome" to "rom", "naples" to "neapel",
        "napoli" to "neapel", "lisbon" to "lissabon", "prague" to "prag", "brussels" to "brussel", "moscow" to "moskau", "belgrade" to "belgrad",
        "germany" to "deutschland", "spain" to "spanien", "france" to "frankreich", "italy" to "italien", "netherlands" to "niederlande",
        "austria" to "osterreich", "switzerland" to "schweiz", "croatia" to "kroatien", "denmark" to "danemark", "sweden" to "schweden",
        "norway" to "norwegen", "poland" to "polen", "turkey" to "turkei", "turkiye" to "turkei", "greece" to "griechenland", "scotland" to "schottland",
        "ireland" to "irland", "czechia" to "tschechien", "czech" to "tschechien", "hungary" to "ungarn", "serbia" to "serbien", "belgium" to "belgien",
        "romania" to "rumanien", "slovakia" to "slowakei", "slovenia" to "slowenien", "finland" to "finnland", "iceland" to "island",
        "gladbach" to "monchengladbach", "leverkusen" to "leverkusen",
    )

    fun norm(s: String): String = " " + s.lowercase().replace("ü", "u").replace("ö", "o").replace("ä", "a").replace("ß", "ss")
        .replace("é", "e").replace("á", "a").replace("í", "i").replace("ó", "o").replace("ñ", "n").replace("ç", "c")
        .replace(Regex("[^a-z0-9]+"), " ").trim() + " "

    fun containsWord(text: String, word: String) = text.contains(" $word ") || (word.length >= 6 && text.contains(word))

    /** Aussagekräftige Wörter eines Teams/Spielers (z. B. „bayern“, „munchen“; bei Spielern der Nachname). */
    fun tokens(names: List<String>): Set<String> {
        val out = mutableSetOf<String>()
        names.forEach { n ->
            val words = norm(n).trim().split(' ').filter { it.length >= 4 && it !in generic }
            words.forEach { w -> out += w; german[w]?.let { out += it } }
        }
        return out
    }

    fun leagueKeywords(l: League): List<String> = when (l) {
        League.BUNDESLIGA -> listOf("bundesliga")
        League.BUNDESLIGA2 -> listOf("2 bundesliga", "zweite liga")
        League.DFB_POKAL -> listOf("dfb pokal")
        League.CHAMPIONS -> listOf("champions league", "konigsklasse")
        League.EUROPA -> listOf("europa league")
        League.PREMIER -> listOf("premier league")
        League.LALIGA -> listOf("laliga", "la liga", "primera division")
        League.SERIE_A -> listOf("serie a")
        League.NATIONS -> listOf("nations league")
        League.F1 -> listOf("formel 1", "formula 1", "formel1", "f1 ", "grand prix", "grosser preis")
        League.ATP -> listOf("tennis", "atp ")
        League.WTA -> listOf("tennis", "wta ")
        League.NBA -> listOf("nba", "basketball")
    }.map { " " + it }
}
