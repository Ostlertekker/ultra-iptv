package de.ostlertekker.ultraiptv.core.discover

/**
 * Lokale, regelbasierte Erkennung anhand von EPG-Titeln, Beschreibungen, Sendernamen und Kategorien.
 * Keine Cloud-KI, keine erfundenen Daten: was nicht im EPG/den Metadaten steht, wird nicht erkannt.
 */
enum class Sport(val label: String, val emoji: String, val keywords: List<String>) {
    CHAMPIONS("Champions League", "🏆", listOf("champions league", "uefa champions", "königsklasse")),
    FOOTBALL("Fußball", "⚽", listOf("fußball", "fussball", "bundesliga", "dfb-pokal", "dfb pokal", "premier league", "la liga", "laliga",
        "serie a", "ligue 1", "länderspiel", "europa league", "conference league", "3. liga", "wm-qualifikation", "em-qualifikation",
        "nations league", "soccer", "football:", "fc bayern", "borussia dortmund")),
    F1("Formel 1", "🏎", listOf("formel 1", "formula 1", "formel-1", "grand prix", "großer preis", "f1:", "f1 -", "f1 –")),
    TENNIS("Tennis", "🎾", listOf("tennis", "atp", "wta", "wimbledon", "us open", "roland garros", "french open", "australian open", "davis cup", "billie jean king cup")),
    BASKETBALL("Basketball", "🏀", listOf("basketball", "nba", "euroleague", "easycredit bbl", "bbl:")),
    BOXING("Boxen & Kampfsport", "🥊", listOf("boxen", "boxing", "box-wm", "boxkampf", "schwergewicht", "ufc", "fight night", "kickboxen", "mma"));

    companion object {
        fun detect(text: String): Sport? {
            val t = " " + text.lowercase() + " "
            return entries.firstOrNull { s -> s.keywords.any { t.contains(it) } }
        }
    }
}

enum class Genre(val label: String, val keywords: List<String>) {
    SPORT("Sport", listOf("sport", "fußball", "fussball", "bundesliga", "tennis", "formel 1", "boxen", "basketball", "handball", "eishockey", "golf", "darts", "ski", "biathlon", "nba", "nfl", "ufc", "radsport")),
    NEWS("Nachrichten", listOf("nachrichten", "news", "tagesschau", "heute journal", "tagesthemen", "brennpunkt", "politik", "wetter", "börse", "n-tv", "welt ", "cnn", "bbc news", "sondersendung", "talk", "maischberger", "lanz", "illner")),
    KIDS("Kinder", listOf("kinder", "kids", "cartoon", "zeichentrick", "disney", "nick", "kika", "toggo", "junior", "peppa", "paw patrol", "sesamstraße", "sendung mit der maus", "anime")),
    MOVIE("Spielfilm", listOf("spielfilm", "film ", "movie", "kino", "blockbuster", "cinema")),
    SERIES("Serie", listOf("serie", "staffel", "folge", "episode", "season")),
    MUSIC("Musik", listOf("musik", "music", "konzert", "concert", "hits", "charts", "mtv", "vh1", "schlager", "festival", "live in", "radio")),
    DOCU("Doku", listOf("doku", "dokumentation", "documentary", "reportage", "terra x", "history", "geschichte", "wissen", "galileo", "discovery", "national geographic", "spiegel tv", "arte")),
    NATURE("Natur", listOf("natur", "tiere", "tier", "wildlife", "planet", "ozean", "wildnis", "nature", "safari", "zoo")),
    COMEDY("Comedy", listOf("comedy", "komödie", "sitcom", "kabarett", "satire", "lustig", "witz", "simpsons", "family guy", "big bang", "heute-show", "stand-up")),
    CRIME("Krimi", listOf("krimi", "tatort", "polizeiruf", "soko", "thriller", "mord", "kommissar", "ermittl", "csi", "ncis", "crime", "detektiv", "verbrechen")),
    ACTION("Action", listOf("action", "explosiv", "agent", "mission", "kampf", "martial", "stirb langsam", "fast & furious", "james bond", "007")),
    ROMANCE("Romantik", listOf("romantik", "romanze", "liebe", "verliebt", "hochzeit", "romance", "rosamunde pilcher", "inga lindström", "herz")),
    SCIFI("Sci-Fi & Fantasy", listOf("science fiction", "sci-fi", "star trek", "star wars", "fantasy", "zukunft", "außerirdisch", "weltraum", "raumschiff", "superheld", "marvel")),
    HORROR("Horror", listOf("horror", "grusel", "zombie", "vampir", "geister", "exorzis", "slasher")),
    SHOW("Show & Unterhaltung", listOf("show", "quiz", "gameshow", "castingshow", "realität", "reality", "dschungel", "big brother", "wer wird millionär", "unterhaltung", "late night")),
    COOKING("Kochen & Lifestyle", listOf("koch", "küche", "rezept", "backen", "lifestyle", "garten", "wohnen", "shopping", "mode"));

    companion object {
        fun detect(text: String): Set<Genre> {
            val t = " " + text.lowercase() + " "
            return entries.filter { g -> g.keywords.any { t.contains(it) } }.toSet()
        }
    }
}

/** Stimmung → passende Genres mit Gewicht (AI Mood Mode, lokal und nachvollziehbar). */
enum class Mood(val label: String, val emoji: String, val weights: Map<Genre, Float>, val words: List<String>) {
    RELAX("Entspannt", "😌", mapOf(Genre.NATURE to 1f, Genre.DOCU to 0.7f, Genre.MUSIC to 0.6f, Genre.COOKING to 0.8f), listOf("entspann", "chill", "müde", "ruhig", "abschalten", "gemütlich", "relax")),
    ACTION("Action", "💥", mapOf(Genre.ACTION to 1f, Genre.SCIFI to 0.6f, Genre.SPORT to 0.4f, Genre.MOVIE to 0.3f), listOf("action", "adrenalin", "spannung pur", "krach", "explosion", "power")),
    LAUGH("Lachen", "😂", mapOf(Genre.COMEDY to 1f, Genre.SHOW to 0.6f), listOf("lachen", "lustig", "witzig", "spaß", "comedy", "gute laune", "aufheitern")),
    THRILL("Spannung", "🕵️", mapOf(Genre.CRIME to 1f, Genre.HORROR to 0.7f, Genre.SCIFI to 0.4f), listOf("spannend", "krimi", "gruselig", "nervenkitzel", "thriller", "mörder", "horror")),
    ROMANCE("Romantik", "❤️", mapOf(Genre.ROMANCE to 1f, Genre.MOVIE to 0.3f), listOf("romantisch", "verliebt", "liebe", "kuschel", "date")),
    KNOWLEDGE("Wissen", "🧠", mapOf(Genre.DOCU to 1f, Genre.NEWS to 0.6f, Genre.NATURE to 0.6f), listOf("lernen", "wissen", "doku", "neugierig", "informieren", "nachrichten", "schlau")),
    FAMILY("Familie", "👨‍👩‍👧", mapOf(Genre.KIDS to 1f, Genre.SHOW to 0.5f, Genre.COMEDY to 0.4f, Genre.NATURE to 0.4f), listOf("kinder", "familie", "kids", "zusammen", "zeichentrick")),
    SPORT("Sport", "🏟", mapOf(Genre.SPORT to 1f), listOf("sport", "fußball", "fussball", "spiel", "match", "rennen", "tennis", "boxen")),
    CINEMA("Kino", "🎬", mapOf(Genre.MOVIE to 1f, Genre.ACTION to 0.4f, Genre.SCIFI to 0.4f), listOf("film", "kino", "movie", "blockbuster")),
    PARTY("Party", "🎉", mapOf(Genre.MUSIC to 1f, Genre.SHOW to 0.5f), listOf("party", "musik", "tanzen", "feiern", "konzert", "hardtekk", "techno"));

    companion object {
        /** Freitext („bin müde, will was Lustiges“) → Stimmungen. */
        fun fromText(text: String): List<Mood> {
            val t = text.lowercase()
            return entries.filter { m -> m.words.any { t.contains(it) } || t.contains(m.label.lowercase()) }
        }
    }
}

object MoodEngine {
    /** Punktzahl eines Inhalts für die gewählten Stimmungen (0 = passt nicht). */
    fun score(moods: Collection<Mood>, text: String): Float {
        if (moods.isEmpty()) return 0f
        val genres = Genre.detect(text)
        if (genres.isEmpty()) return 0f
        return moods.sumOf { m -> genres.sumOf { g -> (m.weights[g] ?: 0f).toDouble() } }.toFloat()
    }
}

/** Automatische Sendergruppen aus Name + Kategorie (Smart Channel Groups). */
enum class SmartGroup(val label: String) {
    SPORT("Sport"), NEWS("Nachrichten"), KIDS("Kinder"), MOVIES("Filme & Serien"), MUSIC("Musik"), DOCU("Doku & Wissen"),
    ENTERTAINMENT("Unterhaltung"), REGIONAL("Regional"), UHD("4K / UHD"), FHD("Full HD"), HD("HD"), SD("SD");

    companion object {
        private val content = mapOf(
            SPORT to listOf("sport", "dazn", "sky sport", "eurosport", "espn", "bein", "arena", "tennis", "golf", "f1", "motor", "football", "fussball", "fußball", "nba", "nfl", "ufc", "fight"),
            NEWS to listOf("news", "nachrichten", "n-tv", "ntv", "welt", "tagesschau", "phoenix", "cnn", "bbc news", "euronews", "al jazeera", "bloomberg", "cnbc", "info"),
            KIDS to listOf("kids", "kinder", "kika", "nick", "disney", "cartoon", "toggo", "junior", "boomerang", "baby", "super rtl"),
            MOVIES to listOf("cinema", "kino", "movie", "film", "sky ", "hbo", "fox", "warner", "universal", "paramount", "axn", "tnt", "syfy", "13th street", "serie", "series", "netflix"),
            MUSIC to listOf("music", "musik", "mtv", "vh1", "deluxe", "radio", "hits", "schlager", "jukebox", "dance", "rock"),
            DOCU to listOf("doku", "docu", "discovery", "national geographic", "nat geo", "history", "spiegel", "planet", "animal", "terra", "arte", "wissen", "science", "curiosity"),
            ENTERTAINMENT to listOf("rtl", "sat.1", "sat1", "pro7", "prosieben", "vox", "kabel", "comedy", "tlc", "sixx", "zdf", "ard", "das erste", "one", "entertainment"),
            REGIONAL to listOf("regional", "lokal", "local", "mdr", "ndr", "wdr", "br ", "swr", "hr ", "rbb", "sr ", "stadt", "region"),
        )

        fun detect(name: String, category: String): Set<SmartGroup> {
            val t = " " + (name + " " + category).lowercase() + " "
            val out = LinkedHashSet<SmartGroup>()
            for ((g, words) in content) if (words.any { t.contains(it) }) out += g
            val n = " " + name.lowercase().replace(Regex("[^a-z0-9 ]"), " ") + " "
            out += when {
                Regex(" (4k|uhd|2160p?) ").containsMatchIn(n) -> UHD
                Regex(" (fhd|1080p?|full hd) ").containsMatchIn(n) -> FHD
                Regex(" (hd|720p?|hevc|h265) ").containsMatchIn(n) -> HD
                Regex(" (sd|576p?|480p?) ").containsMatchIn(n) -> SD
                else -> return out
            }
            return out
        }

        /** Sendername ohne Qualitäts-/Länderzusätze – für Channel Comparison („Das Erste HD“ = „DE: Das Erste FHD“). */
        fun baseName(name: String): String = name.lowercase()
            .replace(Regex("""^[a-z]{2,3}\s*[:|\-]\s*"""), "")
            .replace(Regex("""\b(4k|uhd|fhd|hd|sd|hevc|h\.?265|h\.?264|1080p?|720p?|2160p?|50fps|60fps|backup|alt|\+1)\b"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
    }
}
