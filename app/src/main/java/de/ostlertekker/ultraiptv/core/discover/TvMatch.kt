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

    /** Ländernamen in weiteren Sprachen (PL, FR, IT, ES, NL, PT, TR) für Länderspiele in fremdsprachigen TV-Zeitschriften. */
    private val multi = mapOf(
        "germany" to listOf("niemcy", "allemagne", "germania", "alemania", "duitsland", "almanya", "alemanha"),
        "poland" to listOf("polska", "pologne", "polonia", "polen", "polonya", "polonia"),
        "netherlands" to listOf("holandia", "pays bas", "olanda", "paises bajos", "nederland", "hollanda", "holanda"),
        "spain" to listOf("hiszpania", "espagne", "spagna", "espana", "spanje", "ispanya", "espanha"),
        "france" to listOf("francja", "francia", "frankrijk", "fransa", "franca"),
        "italy" to listOf("wlochy", "italie", "italia", "itali", "italya"),
        "portugal" to listOf("portugalia", "portogallo", "portekiz"),
        "england" to listOf("anglia", "angleterre", "inghilterra", "inglaterra", "engeland", "ingiltere"),
        "croatia" to listOf("chorwacja", "croatie", "croazia", "croacia", "kroatie", "hirvatistan", "hrvatska"),
        "belgium" to listOf("belgia", "belgique", "belgio", "belgica", "belgie", "belcika"),
        "austria" to listOf("autriche", "oostenrijk", "avusturya"),
        "switzerland" to listOf("szwajcaria", "suisse", "svizzera", "suiza", "zwitserland", "isvicre"),
        "denmark" to listOf("dania", "danemark", "danimarca", "dinamarca", "denemarken", "danimarka"),
        "sweden" to listOf("szwecja", "suede", "svezia", "suecia", "zweden", "isvec"),
        "norway" to listOf("norwegia", "norvege", "norvegia", "noruega", "noorwegen", "norvec"),
        "scotland" to listOf("szkocja", "ecosse", "scozia", "escocia", "schotland", "iskocya"),
        "wales" to listOf("walia", "pays de galles", "galles", "gales"),
        "turkey" to listOf("turcja", "turquie", "turchia", "turquia", "turkije", "turkiye"),
        "turkiye" to listOf("turcja", "turquie", "turchia", "turquia", "turkije", "turkiye"),
        "serbia" to listOf("serbie", "servie", "sirbistan", "srbija"),
        "hungary" to listOf("wegry", "hongrie", "ungheria", "hungria", "hongarije", "macaristan", "magyarorszag"),
        "czechia" to listOf("czechy", "tchequie", "cechia", "chequia", "tsjechie", "cekya"),
        "ukraine" to listOf("ukraina", "ucraina", "ucrania", "oekraine", "ukrayna"),
        "greece" to listOf("grecja", "grece", "grecia", "griekenland", "yunanistan"),
        "romania" to listOf("rumunia", "roumanie", "roemenie"),
        "slovakia" to listOf("slowacja", "slovaquie", "slovacchia", "eslovaquia", "slowakije"),
        "slovenia" to listOf("slowenia", "slovenie", "eslovenia"),
        "ireland" to listOf("irlandia", "irlande", "irlanda", "ierland"),
        "israel" to listOf("izrael", "israele"),
        "bosnia" to listOf("bosnia i hercegowina", "bosnie", "bosna"),
        "albania" to listOf("albanie", "arnavutluk", "shqiperia"),
        "georgia" to listOf("gruzja", "georgie", "gurcistan"),
        "finland" to listOf("finlandia", "finlande", "finlandiya"),
        "iceland" to listOf("islandia", "islande", "islanda", "ijsland", "izlanda"),
        "montenegro" to listOf("czarnogora", "karadag"),
        "kosovo" to listOf("kosowo"),
        "estonia" to listOf("estonie", "estland"),
        "latvia" to listOf("lotwa", "lettonie", "letland"),
        "lithuania" to listOf("litwa", "lituanie", "lituania", "litouwen"),
        "northern" to listOf("irlandia polnocna", "irlande du nord"),
        "macedonia" to listOf("macedonia polnocna", "macedoine"),
        "cyprus" to listOf("cypr", "chypre", "cipro", "chipre", "kibris"),
        "malta" to listOf("malte"),
        "moldova" to listOf("moldawia", "moldavie"),
        "kazakhstan" to listOf("kazachstan", "kazakistan"),
        "armenia" to listOf("armenie", "ermenistan"),
        "azerbaijan" to listOf("azerbejdzan", "azerbaidjan", "azerbaycan"),
        "belarus" to listOf("bialorus", "bielorussie"),
        "bulgaria" to listOf("bulgarie", "bulgarije", "bulgaristan"),
        "luxembourg" to listOf("luksemburg", "lussemburgo", "luxemburgo", "luxemburg"),
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
            words.forEach { w -> out += w; german[w]?.let { out += it }; multi[w]?.forEach { t -> out += t } }
        }
        return out
    }

    /** Nachname eines Spielers (Tennis), z. B. „Alexander Zverev“ → zverev. */
    fun surname(name: String): Set<String> =
        norm(name).trim().split(' ').lastOrNull()?.takeIf { it.length >= 3 }?.let { setOf(it) } ?: emptySet()

    fun leagueKeywords(l: League): List<String> = when (l) {
        League.BUNDESLIGA -> listOf("bundesliga")
        League.BUNDESLIGA2 -> listOf("2 bundesliga", "zweite liga")
        League.DFB_POKAL -> listOf("dfb pokal")
        League.CHAMPIONS -> listOf("champions league", "konigsklasse")
        League.EUROPA -> listOf("europa league")
        League.PREMIER -> listOf("premier league")
        League.LALIGA -> listOf("laliga", "la liga", "primera division")
        League.SERIE_A -> listOf("serie a")
        League.NATIONS -> listOf("nations league", "liga narodow", "ligue des nations", "nations league uefa", "lega delle nazioni", "liga de naciones", "uluslar ligi", "liga das nacoes", "landerspiel")
        League.F1 -> listOf("formel 1", "formula 1", "formel1", "f1 ", "grand prix", "grosser preis")
        League.ATP -> listOf("tennis", "atp ")
        League.WTA -> listOf("tennis", "wta ")
        League.NBA -> listOf("nba", "basketball")
    }.map { " " + it }
}
