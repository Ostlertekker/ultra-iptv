package de.ostlertekker.ultraiptv.core.model

/** Land mit Kartenposition (Hauptstadt-/Schwerpunktnähe) und Hauptsprache. */
data class Country(val code: String, val name: String, val lat: Float, val lon: Float, val language: String) {
    /** Flaggen-Emoji aus dem ISO-Code (z. B. DE → 🇩🇪). */
    val flag: String
        get() = if (code.length == 2 && code.all { it in 'A'..'Z' })
            String(Character.toChars(0x1F1E6 + (code[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (code[1] - 'A')))
        else "🌐"
}

object Countries {
    val all: List<Country> = listOf(
        Country("DE", "Deutschland", 51.2f, 10.4f, "de"), Country("AT", "Österreich", 47.5f, 14.5f, "de"),
        Country("CH", "Schweiz", 46.8f, 8.2f, "de"), Country("GB", "Großbritannien", 53.5f, -1.5f, "en"),
        Country("IE", "Irland", 53.2f, -8.0f, "en"), Country("US", "USA", 39.5f, -98.0f, "en"),
        Country("CA", "Kanada", 56.0f, -106.0f, "en"), Country("AU", "Australien", -25.0f, 134.0f, "en"),
        Country("FR", "Frankreich", 46.5f, 2.5f, "fr"), Country("BE", "Belgien", 50.6f, 4.6f, "fr"),
        Country("NL", "Niederlande", 52.2f, 5.5f, "nl"), Country("IT", "Italien", 42.8f, 12.5f, "it"),
        Country("ES", "Spanien", 40.2f, -3.7f, "es"), Country("PT", "Portugal", 39.6f, -8.0f, "pt"),
        Country("PL", "Polen", 52.0f, 19.4f, "pl"), Country("CZ", "Tschechien", 49.8f, 15.5f, "cs"),
        Country("HU", "Ungarn", 47.2f, 19.5f, "hu"), Country("RO", "Rumänien", 45.9f, 24.9f, "ro"),
        Country("BG", "Bulgarien", 42.7f, 25.5f, "bg"), Country("GR", "Griechenland", 39.1f, 22.0f, "el"),
        Country("TR", "Türkei", 39.0f, 35.2f, "tr"), Country("RU", "Russland", 58.0f, 45.0f, "ru"),
        Country("UA", "Ukraine", 49.0f, 31.4f, "uk"), Country("RS", "Serbien", 44.0f, 20.9f, "sr"),
        Country("HR", "Kroatien", 45.1f, 15.2f, "hr"), Country("BA", "Bosnien", 44.0f, 17.8f, "bs"),
        Country("AL", "Albanien", 41.1f, 20.0f, "sq"), Country("SE", "Schweden", 62.0f, 15.0f, "sv"),
        Country("NO", "Norwegen", 61.0f, 9.0f, "no"), Country("DK", "Dänemark", 56.0f, 9.5f, "da"),
        Country("FI", "Finnland", 63.0f, 26.0f, "fi"), Country("SA", "Arabische Welt", 24.0f, 45.0f, "ar"),
        Country("IR", "Iran", 32.4f, 53.7f, "fa"), Country("IN", "Indien", 21.0f, 78.0f, "hi"),
        Country("PK", "Pakistan", 30.0f, 70.0f, "ur"), Country("CN", "China", 35.0f, 104.0f, "zh"),
        Country("JP", "Japan", 36.2f, 138.3f, "ja"), Country("KR", "Südkorea", 36.5f, 127.8f, "ko"),
        Country("BR", "Brasilien", -10.0f, -52.0f, "pt"), Country("MX", "Mexiko", 23.6f, -102.5f, "es"),
        Country("AR", "Argentinien", -34.0f, -64.0f, "es"), Country("ZA", "Südafrika", -29.0f, 24.0f, "en"),
        Country("NG", "Nigeria", 9.1f, 8.7f, "en"), Country("EG", "Ägypten", 26.8f, 30.8f, "ar"),
        Country("MA", "Marokko", 31.8f, -7.1f, "ar"), Country("IL", "Israel", 31.0f, 34.9f, "he"),
        Country("KU", "Kurdistan", 36.5f, 44.0f, "ku"), Country("XY", "Ex-Jugoslawien", 44.5f, 18.5f, "sr"),
    )

    private val byCode = all.associateBy { it.code }
    fun byCode(code: String?): Country? = code?.let { byCode[it] }

    /** Begriffe, die in Kategorie- oder Sendernamen auf ein Land hindeuten. */
    private val aliases: Map<String, String> = buildMap {
        all.forEach { put(it.code.lowercase(), it.code) }
        listOf("ger", "germany", "deutschland", "deutsch", "german", "dach").forEach { put(it, "DE") }
        listOf("uk", "gb", "britain", "england", "united kingdom", "british").forEach { put(it, "GB") }
        listOf("usa", "us", "united states", "america", "american").forEach { put(it, "US") }
        listOf("france", "french", "francais", "français", "fra").forEach { put(it, "FR") }
        listOf("italy", "italia", "italian", "ita").forEach { put(it, "IT") }
        listOf("spain", "espana", "españa", "spanish", "esp").forEach { put(it, "ES") }
        listOf("turkey", "turkiye", "türkiye", "turkish", "türk", "turk", "tur").forEach { put(it, "TR") }
        listOf("poland", "polska", "polish", "pol").forEach { put(it, "PL") }
        listOf("netherlands", "holland", "dutch", "nederland").forEach { put(it, "NL") }
        listOf("austria", "österreich", "oesterreich").forEach { put(it, "AT") }
        listOf("switzerland", "schweiz", "suisse", "swiss").forEach { put(it, "CH") }
        listOf("portugal", "portuguese").forEach { put(it, "PT") }
        listOf("brazil", "brasil").forEach { put(it, "BR") }
        listOf("arab", "arabic", "arabia", "ar", "mena").forEach { put(it, "SA") }
        listOf("russia", "russian", "rus").forEach { put(it, "RU") }
        listOf("ukraine", "ukrainian").forEach { put(it, "UA") }
        listOf("greece", "greek").forEach { put(it, "GR") }
        listOf("romania", "romanian").forEach { put(it, "RO") }
        listOf("albania", "albanian", "shqip").forEach { put(it, "AL") }
        listOf("ex-yu", "exyu", "ex yu", "balkan", "yu").forEach { put(it, "XY") }
        listOf("kurdish", "kurdistan", "kurd").forEach { put(it, "KU") }
        listOf("india", "indian", "hindi").forEach { put(it, "IN") }
        listOf("canada", "canadian").forEach { put(it, "CA") }
        listOf("australia", "aussie").forEach { put(it, "AU") }
        listOf("sweden", "swedish", "nordic", "scandinavia").forEach { put(it, "SE") }
        listOf("denmark", "danish").forEach { put(it, "DK") }
        listOf("norway", "norwegian").forEach { put(it, "NO") }
        listOf("finland", "finnish").forEach { put(it, "FI") }
        listOf("belgium", "belgian").forEach { put(it, "BE") }
        listOf("mexico", "latino", "latam").forEach { put(it, "MX") }
        listOf("africa", "african").forEach { put(it, "ZA") }
        listOf("iran", "persian", "farsi").forEach { put(it, "IR") }
        listOf("pakistan", "urdu").forEach { put(it, "PK") }
        listOf("china", "chinese").forEach { put(it, "CN") }
        listOf("japan", "japanese").forEach { put(it, "JP") }
        listOf("korea", "korean").forEach { put(it, "KR") }
        listOf("israel", "hebrew").forEach { put(it, "IL") }
        listOf("egypt").forEach { put(it, "EG") }
        listOf("morocco", "maroc").forEach { put(it, "MA") }
        listOf("hungary", "hungarian").forEach { put(it, "HU") }
        listOf("czech").forEach { put(it, "CZ") }
        listOf("bulgaria").forEach { put(it, "BG") }
        listOf("croatia", "hrvatska").forEach { put(it, "HR") }
        listOf("serbia", "srbija").forEach { put(it, "RS") }
        listOf("bosnia").forEach { put(it, "BA") }
    }

    /** Kurze Codes sind nur als abgegrenztes Präfix-Token zuverlässig (z. B. "DE: ARD", "|TR| TRT"). */
    private val prefixRegex = Regex("""^\s*[\[|(]?\s*([A-Za-z]{2,3}|EX-YU)\s*[\]|):\-–]""")
    private val wordRegex = Regex("""[\p{L}]+(?:-[\p{L}]+)?""")

    fun detect(vararg texts: String?): String? {
        for (t in texts) {
            if (t.isNullOrBlank()) continue
            prefixRegex.find(t)?.let { m ->
                aliases[m.groupValues[1].lowercase()]?.let { return it }
            }
        }
        for (t in texts) {
            if (t.isNullOrBlank()) continue
            val lower = t.lowercase()
            // mehrteilige Begriffe zuerst
            for (phrase in listOf("united kingdom", "united states", "ex yu")) if (phrase in lower) return aliases[phrase]
            for (w in wordRegex.findAll(lower)) {
                val word = w.value
                if (word.length >= 4 || word in setOf("uk", "usa", "ger", "fra", "ita", "esp", "tur", "pol", "rus")) {
                    aliases[word]?.let { return it }
                }
            }
        }
        return null
    }

    fun languageOf(code: String?): String? = byCode(code)?.language

    val languageNames: Map<String, String> = mapOf(
        "de" to "Deutsch", "en" to "Englisch", "fr" to "Französisch", "it" to "Italienisch", "es" to "Spanisch",
        "tr" to "Türkisch", "pl" to "Polnisch", "nl" to "Niederländisch", "pt" to "Portugiesisch", "ru" to "Russisch",
        "ar" to "Arabisch", "el" to "Griechisch", "ro" to "Rumänisch", "sq" to "Albanisch", "sr" to "Serbisch",
        "hi" to "Hindi", "ku" to "Kurdisch", "fa" to "Persisch", "uk" to "Ukrainisch", "sv" to "Schwedisch",
    )
}
