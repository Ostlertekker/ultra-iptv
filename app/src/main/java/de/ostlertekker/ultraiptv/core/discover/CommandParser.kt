package de.ostlertekker.ultraiptv.core.discover

/** Ergebnis der Smart-Remote-Befehlserkennung. */
sealed class RemoteCommand {
    data object BestSport : RemoteCommand()
    data class ShowTopic(val topic: String) : RemoteCommand()
    data object PreviousChannel : RemoteCommand()
    data object MultiView : RemoteCommand()
    data object Tonight : RemoteCommand()
    data object WhatsOn : RemoteCommand()
    data class OpenChannel(val query: String, val number: Int?) : RemoteCommand()
    data class OpenSection(val section: Section) : RemoteCommand()
    data class MoodPick(val moods: List<Mood>) : RemoteCommand()
    data class Search(val query: String) : RemoteCommand()
}

enum class Section(val words: List<String>) {
    FAVORITES(listOf("favoriten", "lieblings")), SETTINGS(listOf("einstellung", "settings", "optionen")), MOVIES(listOf("filme", "film-bereich")),
    SERIES(listOf("serien")), GUIDE(listOf("tv-guide", "tv guide", "programmführer", "epg", "fernsehprogramm")), WORLD(listOf("weltkarte", "länder")),
    SPORTS(listOf("sport-dashboard", "sportmodus", "sport modus", "spielstände", "live-ergebnisse", "ergebnisse")),
    STATS(listOf("statistik", "mein tv", "sehgewohnheiten")), TIME_MACHINE(listOf("zeitreise", "time machine", "catch-up", "verpasst", "mediathek")),
    UNIVERSE(listOf("universum", "channel universe")), LIVE(listOf("live-tv", "live tv", "senderliste")), HOME(listOf("startseite", "home"));
}

/**
 * Smart Remote: versteht natürliche deutsche Befehle (Text oder Sprache) – lokal, ohne Cloud.
 * Beispiele: „Öffne den besten verfügbaren Sportstream“, „Zeige mir alle Sender mit Fußball“,
 * „Wechsle zum vorherigen Sender“, „Öffne die Mehrfachansicht“, „Zeige mir, was heute Abend läuft“.
 */
object CommandParser {
    fun parse(input: String): RemoteCommand {
        val t = input.lowercase().replace(Regex("""[.!?,„“"]"""), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return RemoteCommand.Search("")

        if (listOf("mehrfachansicht", "multiview", "multi-view", "multi view", "splitscreen", "split screen", "mehrere sender gleichzeitig").any { t.contains(it) })
            return RemoteCommand.MultiView
        if (Regex("""(vorherig|letzt|vorig)\w*\s+(sender|kanal|programm)""").containsMatchIn(t) || t.contains("zurück zum letzten") || t.contains("zap zurück"))
            return RemoteCommand.PreviousChannel
        if (listOf("heute abend", "heut abend", "heute nacht", "primetime", "20:15", "20 15").any { t.contains(it) })
            return RemoteCommand.Tonight
        if (Regex("""\bbeste\w*\b.*\bsport""").containsMatchIn(t) || t.contains("sportstream"))
            return RemoteCommand.BestSport

        Section.entries.firstOrNull { s -> s.words.any { t.contains(it) } }?.let { return RemoteCommand.OpenSection(it) }

        // „Zeige mir alle Sender mit Fußball“ / „Wo läuft Tatort?“
        Regex("""(?:sender|kanäle|kanaele|programme)\s+(?:mit|über|zu|zum thema)\s+(.+)""").find(t)?.let { return RemoteCommand.ShowTopic(clean(it.groupValues[1])) }
        Regex("""wo läuft\s+(?:gerade\s+|jetzt\s+)?(.+)""").find(t)?.let { return RemoteCommand.ShowTopic(clean(it.groupValues[1])) }
        if (listOf("was läuft", "läuft gerade", "jetzt im tv", "gerade im fernsehen", "was kommt").any { t.contains(it) }) return RemoteCommand.WhatsOn

        // Sender direkt
        Regex("""(?:sender|kanal|programm)\s+(?:nummer\s+)?(\d{1,4})\b""").find(t)?.let { return RemoteCommand.OpenChannel(it.groupValues[1], it.groupValues[1].toInt()) }
        Regex("""^(?:schalte|schalt|wechsle|wechsel|geh|gehe|öffne|oeffne|spiele|spiel|starte|mach)\s+(?:auf|zu|zum|zur|den|die|das)?\s*(?:sender\s+)?(.+?)(?:\s+(?:an|ein|auf))?$""").find(t)?.let {
            val q = clean(it.groupValues[1])
            if (q.isNotBlank()) return RemoteCommand.OpenChannel(q, q.toIntOrNull())
        }

        Mood.fromText(t).takeIf { it.isNotEmpty() }?.let { return RemoteCommand.MoodPick(it) }
        return RemoteCommand.Search(clean(t.removePrefix("zeige mir ").removePrefix("zeig mir ").removePrefix("suche ").removePrefix("finde ")))
    }

    private fun clean(s: String) = s.trim().removePrefix("dem ").removePrefix("den ").removePrefix("der ").removePrefix("die ").removePrefix("das ")
        .removeSuffix(" an").removeSuffix(" ein").trim()
}
