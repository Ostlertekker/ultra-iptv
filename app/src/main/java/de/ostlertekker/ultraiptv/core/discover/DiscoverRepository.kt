package de.ostlertekker.ultraiptv.core.discover

import de.ostlertekker.ultraiptv.core.catchup.Catchup
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.database.AiringProgram
import de.ostlertekker.ultraiptv.core.database.ChannelEntity
import de.ostlertekker.ultraiptv.core.database.ContentDao
import de.ostlertekker.ultraiptv.core.database.DiscoverDao
import de.ostlertekker.ultraiptv.core.database.EpgDao
import de.ostlertekker.ultraiptv.core.database.ExtrasDao
import de.ostlertekker.ultraiptv.core.database.MovieEntity
import de.ostlertekker.ultraiptv.core.database.VirtualListEntity
import de.ostlertekker.ultraiptv.core.database.WatchLogEntity
import de.ostlertekker.ultraiptv.core.model.ContentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Regeln einer eigenen Senderliste. */
data class ListRules(
    val categories: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val groups: List<SmartGroup> = emptyList(),
    val epgTopic: String = "",
    val favoritesOnly: Boolean = false,
) {
    fun toJson(): String = buildJsonObject {
        put("categories", JsonArray(categories.map { JsonPrimitive(it) }))
        put("keywords", JsonArray(keywords.map { JsonPrimitive(it) }))
        put("groups", JsonArray(groups.map { JsonPrimitive(it.name) }))
        put("epgTopic", epgTopic)
        put("favoritesOnly", favoritesOnly)
    }.toString()

    fun describe(): String = buildList {
        if (categories.isNotEmpty()) add("${categories.size} Kategorien")
        if (groups.isNotEmpty()) add(groups.joinToString { it.label })
        if (keywords.isNotEmpty()) add("Name: " + keywords.joinToString())
        if (epgTopic.isNotBlank()) add("läuft gerade: $epgTopic")
        if (favoritesOnly) add("nur Favoriten")
    }.joinToString(" · ").ifEmpty { "alle Sender" }

    companion object {
        fun fromJson(s: String): ListRules = runCatching {
            val o = Json.parseToJsonElement(s).jsonObject
            fun arr(k: String) = o[k]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            ListRules(arr("categories"), arr("keywords"), arr("groups").mapNotNull { n -> SmartGroup.entries.firstOrNull { it.name == n } },
                o["epgTopic"]?.jsonPrimitive?.content ?: "", o["favoritesOnly"]?.jsonPrimitive?.content == "true")
        }.getOrDefault(ListRules())
    }
}

data class SportEvent(val sport: Sport, val program: AiringProgram)

data class WatchStats(
    val totalMs7d: Long,
    val totalMs30d: Long,
    val perDay: List<Pair<String, Long>>,
    val topChannels: List<Pair<String, Long>>,
    val topCategories: List<Pair<String, Long>>,
    val topGenres: List<Pair<Genre, Long>>,
    val sessions: Int,
    val favoriteCount: Int,
    val history: List<WatchLogEntity>,
    val busiestHour: Int?,
)

/**
 * Zentrale Datenquelle für die Entdecken-Funktionen (Channel Radar, Jetzt läuft, Zeitreise, Event Tracker,
 * Mood Mode, Smart Groups, eigene Listen, Mein TV). Alles basiert auf echten EPG- und Senderdaten.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
/** Ein Sender-Vorschlag für ein Spiel: woher er stammt und wie sicher er ist. */
data class TvHit(val program: AiringProgram, val source: Source, val country: String?, val score: Int) {
    enum class Source(val label: String) { VERY("sehr wahrscheinlich"), LIKELY("wahrscheinlich"), POSSIBLE("möglich") }
}

class DiscoverRepository @Inject constructor(
    private val epg: EpgDao,
    private val content: ContentDao,
    private val dao: DiscoverDao,
    private val extras: ExtrasDao,
    private val user: UserRepository,
    private val settings: SettingsRepository,
) {
    private suspend fun adult() = user.filter.first().showAdult
    private suspend fun profileId() = user.filter.first().profileId

    // ------------------------------------------------------------------ EPG-basierte Abfragen
    suspend fun airingAt(at: Long, limit: Int = 3000): List<AiringProgram> = withContext(Dispatchers.IO) { epg.airingAt(at, adult(), limit) }

    suspend fun search(q: String, from: Long, to: Long, limit: Int = 300): List<AiringProgram> = withContext(Dispatchers.IO) {
        if (q.isBlank()) emptyList() else epg.searchPrograms(q.trim(), from, to, adult(), limit)
    }

    /**
     * „Wo läuft das im TV?“ für ein Spiel aus den Live-Spielständen. Drei Quellen, zusammengeführt:
     * 1. Rechte-Tabelle ([TvRights]) + ESPN-Senderangaben → Sender der Playlist per Namensabgleich (Länderpräfix beachtet)
     * 2. TV-Programm-Scan aller Sender: Team-/Spielernamen (mehrsprachig) + Wettbewerb + Sportart
     * 3. Bestätigung: Rechteinhaber-Sender, deren aktuelles Programm zum Spiel passt, stehen ganz oben
     */
    suspend fun whereOnTv(e: ScoreEvent): List<TvHit> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val (from, to) = when (e.state) {
            "in" -> now to now + 60_000L
            "post" -> e.startMs to e.startMs + 3 * 3_600_000L
            else -> (e.startMs - 45 * 60_000L) to (e.startMs + 30 * 60_000L)
        }
        val programs = epg.window(from, to, adult(), 60_000)
        val sport = e.league.sport
        val football = sport == Sport.FOOTBALL || sport == Sport.CHAMPIONS
        val teams = when (sport) {
            Sport.F1 -> emptyList()
            Sport.TENNIS -> e.competitors.map { c -> TvMatch.surname(c.name) }.filter { it.isNotEmpty() }
            else -> e.competitors.map { c -> TvMatch.tokens(listOfNotNull(c.name, c.short)) }.filter { it.isNotEmpty() }
        }
        val leagueKw = TvMatch.leagueKeywords(e.league)
        fun matchScore(p: AiringProgram): Int {
            val raw = p.title + " " + (p.description ?: "")
            val text = TvMatch.norm(raw)
            val detected = Sport.detect(raw)
            if (detected != null && detected != sport && !(football && (detected == Sport.FOOTBALL || detected == Sport.CHAMPIONS))) return -1
            val teamHits = teams.count { toks -> toks.any { TvMatch.containsWord(text, it) } }
            val leagueHit = leagueKw.any { text.contains(it) }
            val sportHit = detected != null
            val ok = when (sport) {
                Sport.F1 -> leagueHit
                Sport.TENNIS -> teamHits >= 1 && (teamHits >= 2 || leagueHit || sportHit)
                else -> teamHits >= 2 || (teamHits == 1 && (leagueHit || sportHit))
            }
            return if (!ok) 0 else teamHits * 10 + (if (leagueHit) 4 else 0) + (if (sportHit) 1 else 0)
        }
        val epgByChannel = programs.groupBy { it.channelId }

        val allChannels = content.channelsAllOnce(adult(), 60_000)

        // Wahrscheinlichkeit je Sender – weltweit: Rechteinhaber aller Länder (Land des Senders muss passen),
        // Wettbewerb im Sendernamen, Hauptkanal, EPG (Teams > Wettbewerb > Sportart). Ausgegeben werden die 5 besten.
        val holders = TvRights.holders(e.league).map { (c, b) -> c to ChannelCountry.brandKey(b) } +
            e.broadcasters.map { "*" to ChannelCountry.brandKey(it) }
        val countryOf = allChannels.associate { it.id to ChannelCountry.detect(it.name, it.category) }
        val mainCountry = countryOf.values.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val nameKw = (TvMatch.leagueKeywords(e.league) + when (e.league) {
            League.F1 -> listOf(" f1 ", " formula ", " formel ")
            League.PREMIER -> listOf(" premier ", " pl ")
            League.SERIE_A -> listOf(" calcio ", " serie a ")
            League.CHAMPIONS -> listOf(" champions ", " ucl ")
            League.ATP, League.WTA -> listOf(" tennis ")
            League.NBA -> listOf(" nba ")
            League.BUNDESLIGA, League.BUNDESLIGA2 -> listOf(" bundesliga ")
            League.LALIGA -> listOf(" laliga ", " la liga ")
            else -> emptyList()
        }).distinct()
        fun current(chId: String) = epgByChannel[chId]?.firstOrNull { it.startMs <= maxOf(now, from) && it.endMs > maxOf(now, from) }
            ?: epgByChannel[chId]?.firstOrNull()

        val epgHitIds = programs.filter { matchScore(it) > 0 }.map { it.channelId }.toSet()
        val hits = allChannels.mapNotNull { ch ->
            val clean = ChannelCountry.cleanName(ch.name)
            val country = countryOf[ch.id]
            val right = holders.firstOrNull { (c, brand) ->
                brand.length > 2 && ChannelCountry.matches(clean, brand) && (c == "*" || country == null || country == c)
            }
            if (right == null && ch.id !in epgHitIds) return@mapNotNull null
            val cur = current(ch.id)
            val epgScore = cur?.let { matchScore(it) } ?: 0
            if (epgScore < 0) return@mapNotNull null // andere Sportart läuft
            val curSport = cur?.let { Sport.detect(it.title + " " + (it.description ?: "")) }
            if (cur != null && epgScore == 0 && curSport == null) return@mapNotNull null // Film/Show/Nachrichten laut EPG
            var p = 0
            if (right != null) p += 30 + (if (country != null && country == right.first) 10 else 0)
            if (country != null && country == mainCountry) p += 5
            if (nameKw.any { clean.contains(it) }) p += 20
            val num = Regex("""\b(\d{1,2})\b""").find(clean)?.groupValues?.get(1)?.toIntOrNull()
            p += when { num == null || num == 1 -> 4; num == 2 -> 2; else -> 0 }
            p += when { epgScore >= 10 -> 50 + epgScore; epgScore > 0 -> 35; curSport != null -> 12; else -> 0 }
            // Bildqualität: Full HD und HD bevorzugen
            val q = " " + ch.name.lowercase().replace(Regex("[^a-z0-9]+"), " ") + " "
            p += when {
                q.contains(" fhd ") || q.contains(" full hd ") || q.contains(" fullhd ") || q.contains(" 1080p ") || q.contains(" 1080 ") -> 8
                q.contains(" hd ") || q.contains(" 720p ") -> 6
                q.contains(" uhd ") || q.contains(" 4k ") -> 2
                q.contains(" sd ") || q.contains(" low ") -> -4
                else -> 0
            }
            val level = when { epgScore >= 10 -> TvHit.Source.VERY; p >= 45 -> TvHit.Source.LIKELY; else -> TvHit.Source.POSSIBLE }
            val prog = cur ?: AiringProgram(ch.id, ch.name, ch.logo, ch.category, ch.number, ch.archiveDays,
                "${e.league.label} – Rechteinhaber", null, minOf(now, e.startMs), maxOf(now, e.startMs) + 3 * 3_600_000L)
            TvHit(prog, level, right?.first?.let { if (it == "*") "ESPN" else it } ?: country, p)
        }
        // Gleicher Sender in mehreren Qualitäten → nur die beste Variante zeigen
        hits.sortedWith(compareByDescending<TvHit> { it.score }.thenBy { it.program.number })
            .distinctBy { (countryOf[it.program.channelId] ?: "") + ChannelCountry.cleanName(it.program.channelName) }
            .take(5)
    }

    /** Sportereignisse im Zeitfenster (Live Event Tracker) – aus EPG-Titeln/Beschreibungen erkannt. */
    suspend fun sportEvents(from: Long, to: Long): List<SportEvent> = withContext(Dispatchers.IO) {
        epg.window(from, to, adult(), 40_000).mapNotNull { p ->
            Sport.detect(p.title + " " + (p.description ?: ""))?.let { SportEvent(it, p) }
        }.distinctBy { it.program.channelId + it.program.startMs }
    }

    // ------------------------------------------------------------------ Sender
    suspend fun allChannels(): List<ChannelEntity> = withContext(Dispatchers.IO) { content.channelsAllOnce(adult(), 60_000) }

    /** Smart Channel Groups: alle Sender automatisch gruppiert. */
    @Volatile private var groupCache: Pair<Long, Map<SmartGroup, List<ChannelEntity>>>? = null

    suspend fun smartGroups(): Map<SmartGroup, List<ChannelEntity>> = withContext(Dispatchers.IO) {
        groupCache?.let { (t, v) -> if (System.currentTimeMillis() - t < 300_000) return@withContext v }
        val out = LinkedHashMap<SmartGroup, MutableList<ChannelEntity>>()
        SmartGroup.entries.forEach { out[it] = ArrayList() }
        allChannels().forEach { c -> SmartGroup.detect(c.name, c.category).forEach { g -> out[g]!!.add(c) } }
        out.filterValues { it.isNotEmpty() }.also { groupCache = System.currentTimeMillis() to it }
    }

    /** Archiv-URL (roh, wird vom StreamResolver aufgelöst) für eine vergangene Sendung – null wenn nicht möglich. */
    suspend fun catchupUrl(channelId: String, startMs: Long, endMs: Long, now: Long = System.currentTimeMillis()): String? = withContext(Dispatchers.IO) {
        val ch = content.channel(channelId) ?: return@withContext null
        if (!Catchup.available(ch.archiveDays, startMs, now)) return@withContext null
        val end = endMs.coerceAtMost(now)
        when (ch.catchupMode) {
            "xc" -> ch.catchupSource?.let { Catchup.xtreamRef(it, startMs, end.coerceAtLeast(startMs + 60_000), settings.current().serverTz) }
            else -> Catchup.buildM3u(ch.streamUrl, ch.catchupMode, ch.catchupSource, startMs, end.coerceAtLeast(startMs + 60_000), now)
        }
    }

    // ------------------------------------------------------------------ Eigene Senderlisten (AI Channel Cloning)
    fun lists(): Flow<List<VirtualListEntity>> = user.filter.map { it.profileId }.flatMapLatest { dao.lists(it) }
    suspend fun saveList(id: Long, name: String, rules: ListRules): Long =
        dao.saveList(VirtualListEntity(id = id, profileId = profileId(), name = name.ifBlank { "Meine Liste" }, rulesJson = rules.toJson(), createdAt = System.currentTimeMillis()))
    suspend fun list(id: Long) = dao.list(id)

    /** Wendet die Regeln auf die echten Sender an. */
    suspend fun resolveList(rules: ListRules, now: Long = System.currentTimeMillis()): List<ChannelEntity> = withContext(Dispatchers.IO) {
        var chans = allChannels()
        if (rules.favoritesOnly) {
            val fav = user.favoriteIds(ContentKind.LIVE).first().toSet()
            chans = chans.filter { it.id in fav }
        }
        if (rules.categories.isNotEmpty()) chans = chans.filter { it.category in rules.categories }
        if (rules.groups.isNotEmpty()) chans = chans.filter { c -> SmartGroup.detect(c.name, c.category).any { it in rules.groups } }
        if (rules.keywords.isNotEmpty()) chans = chans.filter { c -> rules.keywords.any { k -> c.name.contains(k, true) } }
        if (rules.epgTopic.isNotBlank()) {
            val hits = epg.searchPrograms(rules.epgTopic, now, now + 60_000, adult(), 2000).map { it.channelId }.toSet()
            chans = chans.filter { it.id in hits }
        }
        chans
    }

    // ------------------------------------------------------------------ Mein TV (Sehstatistik)
    suspend fun logWatch(kind: ContentKind, itemId: String, name: String, category: String?, startedAt: Long, durationMs: Long) {
        if (durationMs < 30_000) return // kurzes Durchzappen zählt nicht
        withContext(Dispatchers.IO) {
            dao.insertWatch(WatchLogEntity(profileId = profileId(), kind = kind.name, itemId = itemId, name = name, category = category, startedAt = startedAt, durationMs = durationMs))
            dao.purgeWatch(System.currentTimeMillis() - 180L * Catchup.DAY_MS)
        }
    }

    suspend fun stats(now: Long = System.currentTimeMillis()): WatchStats = withContext(Dispatchers.IO) {
        val log = dao.watchSince(profileId(), now - 30L * Catchup.DAY_MS)
        val last7 = log.filter { it.startedAt >= now - 7L * Catchup.DAY_MS }
        val dayFmt = java.text.SimpleDateFormat("EE", java.util.Locale.GERMANY)
        val perDay = (6 downTo 0).map { d ->
            val dayStart = startOfDay(now) - d * Catchup.DAY_MS
            dayFmt.format(java.util.Date(dayStart)) to log.filter { it.startedAt in dayStart until dayStart + Catchup.DAY_MS }.sumOf { it.durationMs }
        }
        val hours = log.groupBy { java.util.Calendar.getInstance().apply { timeInMillis = it.startedAt }.get(java.util.Calendar.HOUR_OF_DAY) }
            .mapValues { e -> e.value.sumOf { it.durationMs } }
        WatchStats(
            totalMs7d = last7.sumOf { it.durationMs },
            totalMs30d = log.sumOf { it.durationMs },
            perDay = perDay,
            topChannels = log.groupBy { it.name }.mapValues { e -> e.value.sumOf { it.durationMs } }.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value },
            topCategories = log.groupBy { it.category ?: "—" }.mapValues { e -> e.value.sumOf { it.durationMs } }.entries.sortedByDescending { it.value }.take(6).map { it.key to it.value },
            topGenres = log.flatMap { w -> Genre.detect(w.name + " " + (w.category ?: "")).map { it to w.durationMs } }
                .groupBy { it.first }.mapValues { e -> e.value.sumOf { it.second } }.entries.sortedByDescending { it.value }.take(6).map { it.key to it.value },
            sessions = log.size,
            favoriteCount = user.favoriteIds(ContentKind.LIVE).first().size + user.favoriteIds(ContentKind.MOVIE).first().size + user.favoriteIds(ContentKind.SERIES).first().size,
            history = log.take(30),
            busiestHour = hours.maxByOrNull { it.value }?.key,
        )
    }

    /** Persönliche Empfehlungen: Was läuft gerade auf den meistgesehenen Sendern/Kategorien. */
    suspend fun forYou(stats: WatchStats, now: Long): List<AiringProgram> = withContext(Dispatchers.IO) {
        val airing = epg.airingAt(now, adult(), 5000)
        val topNames = stats.topChannels.map { it.first }.toSet()
        val topCats = stats.topCategories.map { it.first }.toSet()
        val genres = stats.topGenres.map { it.first }.toSet()
        airing.map { a ->
            var s = 0f
            if (a.channelName in topNames) s += 3f
            if (a.category in topCats) s += 1.5f
            s += Genre.detect(a.title + " " + (a.description ?: "")).count { it in genres }
            a to s
        }.filter { it.second > 0f }.sortedByDescending { it.second }.take(20).map { it.first }
    }

    private fun startOfDay(ms: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = ms; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}
