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
) {
    private val adult: Flow<Boolean> = user.filter.map { it.showAdult }.distinctUntilChanged()

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

    fun movieCategories(): Flow<List<String>> = adult.flatMapLatest { dao.movieCategories(it) }
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

    fun seriesCategories(): Flow<List<String>> = adult.flatMapLatest { dao.seriesCategories(it) }
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

    suspend fun search(q: String): SearchResults {
        val query = q.trim().replace("%", "").replace("_", " ")
        if (query.length < 2) return SearchResults()
        val a = adult.first()
        return SearchResults(
            channels = dao.searchChannels(query, a, 40),
            movies = dao.searchMovies(query, a, 60),
            series = dao.searchSeries(query, a, 40),
            episodes = dao.searchEpisodes(query, 40),
        )
    }
}

@Singleton
class EpgRepository @Inject constructor(private val dao: EpgDao) {
    suspend fun nowNext(epgId: String?): List<EpgProgramEntity> =
        if (epgId.isNullOrBlank()) emptyList() else dao.nowNext(epgId, System.currentTimeMillis())

    suspend fun programs(epgIds: List<String>, from: Long, to: Long): Map<String, List<EpgProgramEntity>> {
        if (epgIds.isEmpty()) return emptyMap()
        return epgIds.distinct().chunked(400).flatMap { dao.programs(it, from, to) }.groupBy { it.channelEpgId }
    }

    suspend fun count(): Int = dao.count()
}
