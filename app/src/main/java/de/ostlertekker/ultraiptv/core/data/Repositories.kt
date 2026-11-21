package de.ostlertekker.ultraiptv.core.data

import de.ostlertekker.ultraiptv.core.database.ChannelEntity
import de.ostlertekker.ultraiptv.core.database.ContentDao
import de.ostlertekker.ultraiptv.core.database.EpgDao
import de.ostlertekker.ultraiptv.core.database.EpgProgramEntity
import de.ostlertekker.ultraiptv.core.database.EpisodeEntity
import de.ostlertekker.ultraiptv.core.database.FavoriteEntity
import de.ostlertekker.ultraiptv.core.database.MovieEntity
import de.ostlertekker.ultraiptv.core.database.ProfileEntity
import de.ostlertekker.ultraiptv.core.database.ProgressEntity
import de.ostlertekker.ultraiptv.core.database.SeriesEntity
import de.ostlertekker.ultraiptv.core.database.UserDao
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.security.PinHasher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class ContentFilter(val profileId: Long, val showAdult: Boolean)

enum class SortOrder(val label: String) { NEWEST("Neu"), NAME("A–Z"), RATING("Bewertung") }

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class UserRepository @Inject constructor(
    private val dao: UserDao,
    private val settings: SettingsRepository,
) {
    val profiles: Flow<List<ProfileEntity>> = dao.profiles()

    val activeProfile: Flow<ProfileEntity?> = settings.settings
        .map { it.activeProfileId }
        .distinctUntilChanged()
        .flatMapLatest { dao.profileFlow(it) }

    val filter: Flow<ContentFilter> = combine(settings.settings, activeProfile) { s, p ->
        val ageAllowsAdult = p == null || p.maxAge == 0 || p.maxAge >= 18
        ContentFilter(p?.id ?: s.activeProfileId, s.showAdult && ageAllowsAdult)
    }.distinctUntilChanged()

    suspend fun ensureDefaultProfile(): ProfileEntity {
        val all = dao.profilesOnce()
        val activeId = settings.current().activeProfileId
        val active = all.firstOrNull { it.id == activeId }
        if (active != null) return active
        val p = all.firstOrNull() ?: run {
            val id = dao.insertProfile(ProfileEntity(name = "Standard", colorIndex = 0))
            ProfileEntity(id = id, name = "Standard", colorIndex = 0)
        }
        settings.set(Keys.ACTIVE_PROFILE, p.id)
        return p
    }

    suspend fun profileCount(): Int = dao.profilesOnce().size

    suspend fun createProfile(name: String, colorIndex: Int): Long =
        dao.insertProfile(ProfileEntity(name = name.trim(), colorIndex = colorIndex))

    suspend fun updateProfile(p: ProfileEntity) = dao.updateProfile(p)

    suspend fun deleteProfile(p: ProfileEntity) {
        dao.clearFavorites(p.id); dao.clearProgress(p.id); dao.deleteProfile(p)
        if (settings.current().activeProfileId == p.id) {
            settings.set(Keys.ACTIVE_PROFILE, 0L)
            ensureDefaultProfile()
        }
    }

    suspend fun setPin(p: ProfileEntity, pin: String?) {
        if (pin == null) dao.updateProfile(p.copy(pinHash = null, pinSalt = null))
        else {
            val salt = PinHasher.newSalt()
            dao.updateProfile(p.copy(pinSalt = salt, pinHash = PinHasher.hash(pin, salt)))
        }
    }

    fun checkPin(p: ProfileEntity, pin: String): Boolean = PinHasher.verify(pin, p.pinSalt, p.pinHash)

    suspend fun selectProfile(p: ProfileEntity) {
        settings.edit { prefs ->
            prefs[Keys.ACTIVE_PROFILE] = p.id
            p.themeId?.let { prefs[Keys.THEME] = it }
            p.animationProfile?.let { prefs[Keys.ANIM_PROFILE] = it; prefs[Keys.ANIM_AUTO] = false }
        }
    }

    suspend fun activeProfileOnce(): ProfileEntity? = dao.profile(settings.current().activeProfileId)

    // Favoriten
    fun favoriteIds(kind: ContentKind): Flow<List<String>> =
        filter.map { it.profileId }.distinctUntilChanged().flatMapLatest { dao.favoriteIds(it, kind.name) }

    fun isFavorite(kind: ContentKind, id: String): Flow<Boolean> =
        filter.map { it.profileId }.distinctUntilChanged().flatMapLatest { dao.favoriteCount(it, kind.name, id) }.map { it > 0 }

    suspend fun toggleFavorite(kind: ContentKind, id: String) {
        val pid = filter.first().profileId
        val fav = dao.favoriteCount(pid, kind.name, id).first() > 0
        if (fav) dao.removeFavorite(pid, kind.name, id)
        else dao.addFavorite(FavoriteEntity(pid, kind.name, id, System.currentTimeMillis()))
    }

    /** Favoriten-Reihenfolge ändern: um [delta] Plätze nach vorne (−) bzw. hinten (+) verschieben. */
    suspend fun moveFavorite(kind: ContentKind, id: String, delta: Int) {
        val pid = filter.first().profileId
        val list = dao.favoritesOrdered(pid, kind.name).toMutableList()
        val from = list.indexOfFirst { it.itemId == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return
        list.add(to, list.removeAt(from))
        // Neu durchnummerieren (absteigend, da nach addedAt DESC sortiert wird)
        val base = System.currentTimeMillis()
        dao.putFavorites(list.mapIndexed { i, f -> f.copy(addedAt = base - i) })
    }

    // Fortschritt
    suspend fun saveProgress(kind: ContentKind, id: String, positionMs: Long, durationMs: Long, title: String, image: String?, seriesId: String? = null) {
        val pid = filter.first().profileId
        dao.upsertProgress(ProgressEntity(pid, kind.name, id, positionMs, durationMs, System.currentTimeMillis(), title, image, seriesId))
    }

    suspend fun progress(kind: ContentKind, id: String): ProgressEntity? = dao.progress(filter.first().profileId, kind.name, id)

    val continueWatching: Flow<List<ProgressEntity>> =
        filter.map { it.profileId }.distinctUntilChanged().flatMapLatest { dao.continueWatching(it) }
    val recentChannels: Flow<List<ProgressEntity>> =
        filter.map { it.profileId }.distinctUntilChanged().flatMapLatest { dao.recentChannels(it) }
    val recentlyWatched: Flow<List<ProgressEntity>> =
        filter.map { it.profileId }.distinctUntilChanged().flatMapLatest { dao.recentlyWatched(it) }

    fun seriesProgress(seriesId: String): Flow<List<ProgressEntity>> =
        filter.map { it.profileId }.distinctUntilChanged()
            .flatMapLatest { dao.progressForSeries(it, ContentKind.EPISODE.name, seriesId) }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ContentRepository @Inject constructor(
    private val dao: ContentDao,
    private val user: UserRepository,
    private val settingsRepo: SettingsRepository,
) {
    private val adult: Flow<Boolean> = user.filter.map { it.showAdult }.distinctUntilChanged()

    /** Ausgeblendete Gruppen (Einstellungen › Playlists › Gruppen). Live-TV ohne Präfix, Filme „M:“, Serien „S:“. */
    private val hiddenGroups: Flow<Set<String>> =
        settingsRepo.settings.map { s -> s.hiddenGroups.split('\n').filter { it.isNotBlank() }.toSet() }.distinctUntilChanged()

    val channelCount = dao.channelCount()
    val movieCount = dao.movieCount()

    fun channelCategories(): Flow<List<String>> = adult.flatMapLatest { dao.channelCategories(it) }
    fun channelsIn(category: String): Flow<List<ChannelEntity>> = adult.flatMapLatest { dao.channelsIn(category, it) }
    suspend fun channelsInOnce(category: String): List<ChannelEntity> = dao.channelsInOnce(category, adult.first())
    suspend fun channelsAllOnce(limit: Int): List<ChannelEntity> = dao.channelsAllOnce(adult.first(), limit)
    suspend fun channel(id: String) = dao.channel(id)
    suspend fun channelByNumber(n: Int): ChannelEntity? = dao.channelByNumber(n, adult.first())
    suspend fun channelsNamed(q: String): List<ChannelEntity> = dao.searchChannels(q, adult.first(), 80)
    fun channelsByIds(ids: List<String>): Flow<List<ChannelEntity>> =
        if (ids.isEmpty()) flowOf(emptyList()) else dao.channelsFlow(ids.take(500)).map { list ->
            val byId = list.associateBy { it.id }; ids.mapNotNull { byId[it] }
        }

    fun allMovieCategories(): Flow<List<String>> = adult.flatMapLatest { dao.movieCategories(it) }
    fun movieCategories(): Flow<List<String>> =
        kotlinx.coroutines.flow.combine(allMovieCategories(), hiddenGroups) { c, h -> c.filter { "M:$it" !in h } }
    fun movieGenres(): Flow<List<String>> = dao.movieGenres().map { raw ->
        raw.flatMap { it.split(',', '/', '|') }.map { it.trim() }.filter { it.length in 2..24 }.distinct().take(16)
    }

    fun movies(category: String?, genre: String?, sort: SortOrder, limit: Int): Flow<List<MovieEntity>> = adult.flatMapLatest { a ->
        when (sort) {
            SortOrder.NEWEST -> dao.moviesByNewest(category, genre, a, limit)
            SortOrder.NAME -> dao.moviesByName(category, genre, a, limit)
            SortOrder.RATING -> dao.moviesByRating(category, genre, a, limit)
        }
    }

    fun movie(id: String): Flow<MovieEntity?> = dao.movieFlow(id)
    suspend fun movieOnce(id: String) = dao.movie(id)
    fun moviesByIds(ids: List<String>): Flow<List<MovieEntity>> =
        if (ids.isEmpty()) flowOf(emptyList()) else dao.moviesFlow(ids.take(500)).map { list ->
            val byId = list.associateBy { it.id }; ids.mapNotNull { byId[it] }
        }

    fun allSeriesCategories(): Flow<List<String>> = adult.flatMapLatest { dao.seriesCategories(it) }
    fun seriesCategories(): Flow<List<String>> =
        kotlinx.coroutines.flow.combine(allSeriesCategories(), hiddenGroups) { c, h -> c.filter { "S:$it" !in h } }
    fun series(category: String?, sort: SortOrder, limit: Int): Flow<List<SeriesEntity>> = adult.flatMapLatest { a ->
        when (sort) {
            SortOrder.NEWEST -> dao.seriesByNewest(category, a, limit)
            SortOrder.NAME -> dao.seriesByName(category, a, limit)
            SortOrder.RATING -> dao.seriesByRating(category, a, limit)
        }
    }
    fun seriesItem(id: String): Flow<SeriesEntity?> = dao.seriesFlow(id)
    suspend fun seriesOnce(id: String) = dao.series(id)
    fun seriesByIds(ids: List<String>): Flow<List<SeriesEntity>> =
        if (ids.isEmpty()) flowOf(emptyList()) else dao.seriesListFlow(ids.take(500)).map { list ->
            val byId = list.associateBy { it.id }; ids.mapNotNull { byId[it] }
        }

    fun episodesOf(seriesId: String): Flow<List<EpisodeEntity>> = dao.episodesOf(seriesId)
    suspend fun episodesOfOnce(seriesId: String) = dao.episodesOfOnce(seriesId)
    suspend fun episode(id: String) = dao.episode(id)

    data class SearchResults(
        val channels: List<ChannelEntity> = emptyList(),
        val movies: List<MovieEntity> = emptyList(),
        val series: List<SeriesEntity> = emptyList(),
        val episodes: List<EpisodeEntity> = emptyList(),
    ) {
        val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty() && episodes.isEmpty()
    }

    /**
     * Reine Titelsuche: alle eingegebenen Wörter müssen im Namen vorkommen (Groß-/Kleinschreibung und Akzente egal,
     * Anbieter-Präfixe wie „DE:“ oder „|EN|“ werden ignoriert). Treffer, deren Titel mit dem Suchbegriff beginnt, stehen vorne.
     */
    suspend fun search(q: String): SearchResults {
        val words = TitleSearch.words(q)
        if (words.isEmpty() || words.joinToString("").length < 2) return SearchResults()
        val a = adult.first()
        // Datenbank-Vorauswahl über das aussagekräftigste Wort, Feinfilter + Sortierung danach in Kotlin
        val key = TitleSearch.dbKey(q)
        return SearchResults(
            channels = TitleSearch.rank(dao.searchChannels(key, a, 400), words) { it.name }.take(60),
            movies = TitleSearch.rank(dao.searchMovies(key, a, 600), words) { it.name }.take(120),
            series = TitleSearch.rank(dao.searchSeries(key, a, 400), words) { it.name }.take(80),
            episodes = TitleSearch.rank(dao.searchEpisodes(key, 300), words) { it.title }.take(60),
        )
    }
}

@Singleton
class EpgRepository @Inject constructor(private val dao: EpgDao) {
    /** Bis wann das gespeicherte TV-Programm reicht (0 = keins). */
    suspend fun coverageEnd(): Long = dao.maxEnd() ?: 0L

    suspend fun nowNext(epgId: String?): List<EpgProgramEntity> =
        if (epgId.isNullOrBlank()) emptyList() else dao.nowNext(epgId, System.currentTimeMillis())

    suspend fun programs(epgIds: List<String>, from: Long, to: Long): Map<String, List<EpgProgramEntity>> {
        if (epgIds.isEmpty()) return emptyMap()
        return epgIds.distinct().chunked(400).flatMap { dao.programs(it, from, to) }.groupBy { it.channelEpgId }
    }

    suspend fun count(): Int = dao.count()
}

/** Hilfsfunktionen der Titelsuche (ohne Android-Abhängigkeiten, damit testbar). */
object TitleSearch {
    private val diacritics = Regex("\\p{Mn}+")
    /** Typische Anbieter-Präfixe: „DE:“, „DE -“, „|DE|“, „[DE]“, „4K:“ … */
    private val prefix = Regex("^\\s*(?:[|\\[(]?\\s*[A-Za-z0-9]{1,4}\\s*[|\\])]\\s*[:\\-]?|[A-Za-z0-9]{1,4}\\s*[:\\-])\\s*")

    fun fold(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(java.util.Locale.ROOT).replace("ß", "ss"), java.text.Normalizer.Form.NFD).replace(diacritics, "")

    fun words(q: String): List<String> =
        fold(q.replace('%', ' ').replace('_', ' ')).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    /** Titel ohne Anbieter-Präfix, normalisiert. */
    fun clean(title: String): String = fold(prefix.replace(title, ""))

    /**
     * Wort für die Datenbank-Vorauswahl: das längste Wort ohne Umlaute/Akzente (SQLite-LIKE ignoriert die
     * Groß-/Kleinschreibung nur bei ASCII-Buchstaben), sonst das längste Wort.
     */
    fun dbKey(q: String): String {
        val raw = q.lowercase(java.util.Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        return raw.filter { w -> w.all { it.code < 128 } }.maxByOrNull { it.length } ?: raw.maxByOrNull { it.length } ?: q.trim()
    }

    fun <T> rank(items: List<T>, words: List<String>, title: (T) -> String): List<T> {
        val phrase = words.joinToString(" ")
        return items.mapNotNull { item ->
            val t = clean(title(item))
            val full = fold(title(item))
            if (!words.all { w -> t.contains(w) || full.contains(w) }) return@mapNotNull null
            val score = when {
                t == phrase -> 0
                t.startsWith(phrase) -> 1
                t.startsWith(words.first()) -> 2
                else -> 3
            }
            Triple(item, score, t)
        }.sortedWith(compareBy({ it.second }, { it.third.length }, { it.third })).map { it.first }
    }
}
