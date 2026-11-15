package de.ostlertekker.ultraiptv.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ContentDao {
    @Upsert suspend fun upsertChannels(items: List<ChannelEntity>)
    @Upsert suspend fun upsertMovies(items: List<MovieEntity>)
    @Upsert suspend fun upsertSeries(items: List<SeriesEntity>)
    @Upsert suspend fun upsertEpisodes(items: List<EpisodeEntity>)

    @Query("DELETE FROM channels") suspend fun clearChannels()
    @Query("DELETE FROM movies") suspend fun clearMovies()
    @Query("DELETE FROM series") suspend fun clearSeries()
    @Query("DELETE FROM episodes") suspend fun clearEpisodes()
    @Query("DELETE FROM episodes WHERE seriesId = :seriesId") suspend fun clearEpisodesOf(seriesId: String)

    // ---------- Live ----------
    @Query("SELECT category FROM channels WHERE (adult = 0 OR :showAdult = 1) GROUP BY category ORDER BY MIN(number), category")
    fun channelCategories(showAdult: Boolean): Flow<List<String>>

    @Query("SELECT * FROM channels WHERE category = :category AND (adult = 0 OR :showAdult = 1) ORDER BY number, name")
    fun channelsIn(category: String, showAdult: Boolean): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE category = :category AND (adult = 0 OR :showAdult = 1) ORDER BY number, name")
    suspend fun channelsInOnce(category: String, showAdult: Boolean): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE (adult = 0 OR :showAdult = 1) ORDER BY number, name LIMIT :limit")
    suspend fun channelsAllOnce(showAdult: Boolean, limit: Int): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :id") suspend fun channel(id: String): ChannelEntity?
    @Query("SELECT * FROM channels WHERE id IN (:ids)") suspend fun channels(ids: List<String>): List<ChannelEntity>
    @Query("SELECT * FROM channels WHERE id IN (:ids)") fun channelsFlow(ids: List<String>): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE name LIKE '%' || :q || '%' AND (adult = 0 OR :showAdult = 1) ORDER BY name LIMIT :limit")
    suspend fun searchChannels(q: String, showAdult: Boolean, limit: Int): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels") fun channelCount(): Flow<Int>

    // ---------- Filme ----------
    @Query("SELECT category FROM movies WHERE (adult = 0 OR :showAdult = 1) GROUP BY category ORDER BY category")
    fun movieCategories(showAdult: Boolean): Flow<List<String>>

    @Query("SELECT genre FROM movies WHERE genre IS NOT NULL AND genre != '' GROUP BY genre ORDER BY COUNT(*) DESC LIMIT 40")
    fun movieGenres(): Flow<List<String>>

    @Query("""SELECT * FROM movies WHERE (:category IS NULL OR category = :category) AND (adult = 0 OR :showAdult = 1)
        AND (:genre IS NULL OR genre LIKE '%' || :genre || '%') ORDER BY addedAt DESC LIMIT :limit""")
    fun moviesByNewest(category: String?, genre: String?, showAdult: Boolean, limit: Int): Flow<List<MovieEntity>>

    @Query("""SELECT * FROM movies WHERE (:category IS NULL OR category = :category) AND (adult = 0 OR :showAdult = 1)
        AND (:genre IS NULL OR genre LIKE '%' || :genre || '%') ORDER BY name COLLATE NOCASE LIMIT :limit""")
    fun moviesByName(category: String?, genre: String?, showAdult: Boolean, limit: Int): Flow<List<MovieEntity>>

    @Query("""SELECT * FROM movies WHERE (:category IS NULL OR category = :category) AND (adult = 0 OR :showAdult = 1)
        AND (:genre IS NULL OR genre LIKE '%' || :genre || '%') ORDER BY rating DESC LIMIT :limit""")
    fun moviesByRating(category: String?, genre: String?, showAdult: Boolean, limit: Int): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE id = :id") suspend fun movie(id: String): MovieEntity?
    @Query("SELECT * FROM movies WHERE id = :id") fun movieFlow(id: String): Flow<MovieEntity?>
    @Query("SELECT * FROM movies WHERE id IN (:ids)") fun moviesFlow(ids: List<String>): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE name LIKE '%' || :q || '%' AND (adult = 0 OR :showAdult = 1) ORDER BY name LIMIT :limit")
    suspend fun searchMovies(q: String, showAdult: Boolean, limit: Int): List<MovieEntity>

    @Query("UPDATE movies SET plot = :plot, genre = :genre, backdrop = :backdrop, durationSec = :durationSec WHERE id = :id")
    suspend fun updateMovieDetails(id: String, plot: String?, genre: String?, backdrop: String?, durationSec: Long?)

    @Query("SELECT COUNT(*) FROM movies") fun movieCount(): Flow<Int>

    // ---------- Serien ----------
    @Query("SELECT category FROM series WHERE (adult = 0 OR :showAdult = 1) GROUP BY category ORDER BY category")
    fun seriesCategories(showAdult: Boolean): Flow<List<String>>

    @Query("""SELECT * FROM series WHERE (:category IS NULL OR category = :category) AND (adult = 0 OR :showAdult = 1)
        ORDER BY addedAt DESC LIMIT :limit""")
    fun seriesByNewest(category: String?, showAdult: Boolean, limit: Int): Flow<List<SeriesEntity>>

    @Query("""SELECT * FROM series WHERE (:category IS NULL OR category = :category) AND (adult = 0 OR :showAdult = 1)
        ORDER BY name COLLATE NOCASE LIMIT :limit""")
    fun seriesByName(category: String?, showAdult: Boolean, limit: Int): Flow<List<SeriesEntity>>

    @Query("""SELECT * FROM series WHERE (:category IS NULL OR category = :category) AND (adult = 0 OR :showAdult = 1)
        ORDER BY rating DESC LIMIT :limit""")
    fun seriesByRating(category: String?, showAdult: Boolean, limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE id = :id") suspend fun series(id: String): SeriesEntity?
    @Query("SELECT * FROM series WHERE id = :id") fun seriesFlow(id: String): Flow<SeriesEntity?>
    @Query("SELECT * FROM series WHERE id IN (:ids)") fun seriesListFlow(ids: List<String>): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE name LIKE '%' || :q || '%' AND (adult = 0 OR :showAdult = 1) ORDER BY name LIMIT :limit")
    suspend fun searchSeries(q: String, showAdult: Boolean, limit: Int): List<SeriesEntity>

    // ---------- Episoden ----------
    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY season, episode")
    fun episodesOf(seriesId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY season, episode")
    suspend fun episodesOfOnce(seriesId: String): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :id") suspend fun episode(id: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE title LIKE '%' || :q || '%' ORDER BY title LIMIT :limit")
    suspend fun searchEpisodes(q: String, limit: Int): List<EpisodeEntity>

    // ---------- Intelligente Suche / dynamische Kategorien ----------
    @RawQuery suspend fun rawChannels(q: SupportSQLiteQuery): List<ChannelEntity>
    @RawQuery suspend fun rawMovies(q: SupportSQLiteQuery): List<MovieEntity>
    @RawQuery suspend fun rawSeries(q: SupportSQLiteQuery): List<SeriesEntity>

    @Query("SELECT country AS code, COUNT(*) AS count FROM channels WHERE country IS NOT NULL AND (adult = 0 OR :showAdult = 1) GROUP BY country ORDER BY count DESC")
    fun countryCounts(showAdult: Boolean): Flow<List<CountryCount>>

    @Query("SELECT * FROM channels WHERE country = :code AND (adult = 0 OR :showAdult = 1) ORDER BY number, name LIMIT 500")
    suspend fun channelsOfCountry(code: String, showAdult: Boolean): List<ChannelEntity>

    @Query("UPDATE movies SET actors = :actors, director = :director WHERE id = :id")
    suspend fun updateMovieCredits(id: String, actors: String?, director: String?)

    @Query("SELECT id, addedAt FROM series WHERE id IN (:ids)")
    suspend fun seriesStamps(ids: List<String>): List<SeriesStamp>
}

data class CountryCount(val code: String, val count: Int)
data class SeriesStamp(val id: String, val addedAt: Long)

@Dao
interface EpgDao {
    @Insert suspend fun insertPrograms(items: List<EpgProgramEntity>)
    @Query("DELETE FROM epg") suspend fun clear()
    @Query("SELECT COUNT(*) FROM epg") suspend fun count(): Int

    @Query("SELECT * FROM epg WHERE channelEpgId = :epgId AND endMs > :now ORDER BY startMs LIMIT 2")
    suspend fun nowNext(epgId: String, now: Long): List<EpgProgramEntity>

    @Query("SELECT * FROM epg WHERE channelEpgId IN (:ids) AND endMs > :from AND startMs < :to ORDER BY startMs")
    suspend fun programs(ids: List<String>, from: Long, to: Long): List<EpgProgramEntity>
}

@Dao
interface UserDao {
    // Profile
    @Query("SELECT * FROM profiles ORDER BY id") fun profiles(): Flow<List<ProfileEntity>>
    @Query("SELECT * FROM profiles ORDER BY id") suspend fun profilesOnce(): List<ProfileEntity>
    @Query("SELECT * FROM profiles WHERE id = :id") suspend fun profile(id: Long): ProfileEntity?
    @Query("SELECT * FROM profiles WHERE id = :id") fun profileFlow(id: Long): Flow<ProfileEntity?>
    @Insert suspend fun insertProfile(p: ProfileEntity): Long
    @Update suspend fun updateProfile(p: ProfileEntity)
    @Delete suspend fun deleteProfile(p: ProfileEntity)

    // Favoriten
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addFavorite(f: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE profileId = :profileId AND kind = :kind AND itemId = :itemId")
    suspend fun removeFavorite(profileId: Long, kind: String, itemId: String)
    @Query("SELECT itemId FROM favorites WHERE profileId = :profileId AND kind = :kind ORDER BY addedAt DESC")
    fun favoriteIds(profileId: Long, kind: String): Flow<List<String>>
    @Query("SELECT COUNT(*) FROM favorites WHERE profileId = :profileId AND kind = :kind AND itemId = :itemId")
    fun favoriteCount(profileId: Long, kind: String, itemId: String): Flow<Int>
    @Query("DELETE FROM favorites WHERE profileId = :profileId") suspend fun clearFavorites(profileId: Long)

    // Fortschritt / Verlauf
    @Upsert suspend fun upsertProgress(p: ProgressEntity)
    @Query("SELECT * FROM progress WHERE profileId = :profileId AND kind = :kind AND itemId = :itemId")
    suspend fun progress(profileId: Long, kind: String, itemId: String): ProgressEntity?
    @Query("""SELECT * FROM progress WHERE profileId = :profileId AND kind != 'LIVE' AND positionMs > 30000
        AND (durationMs = 0 OR positionMs < durationMs * 0.95) ORDER BY updatedAt DESC LIMIT 30""")
    fun continueWatching(profileId: Long): Flow<List<ProgressEntity>>
    @Query("SELECT * FROM progress WHERE profileId = :profileId AND kind = 'LIVE' ORDER BY updatedAt DESC LIMIT 25")
    fun recentChannels(profileId: Long): Flow<List<ProgressEntity>>
    @Query("SELECT * FROM progress WHERE profileId = :profileId AND kind != 'LIVE' ORDER BY updatedAt DESC LIMIT 30")
    fun recentlyWatched(profileId: Long): Flow<List<ProgressEntity>>
    @Query("SELECT * FROM progress WHERE profileId = :profileId AND kind = :kind AND seriesId = :seriesId")
    fun progressForSeries(profileId: Long, kind: String, seriesId: String): Flow<List<ProgressEntity>>
    @Query("DELETE FROM progress WHERE profileId = :profileId") suspend fun clearProgress(profileId: Long)

    // Smart-Skip-Marker
    @Insert suspend fun insertMarker(m: SkipMarkerEntity): Long
    @Query("SELECT * FROM skip_markers WHERE episodeId = :episodeId") suspend fun markersFor(episodeId: String): List<SkipMarkerEntity>
    @Query("SELECT * FROM skip_markers WHERE episodeId = '' AND seriesId = :seriesId AND season = :season")
    suspend fun templatesFor(seriesId: String, season: Int): List<SkipMarkerEntity>
    @Query("DELETE FROM skip_markers WHERE episodeId = :episodeId AND type = :type")
    suspend fun deleteMarkers(episodeId: String, type: String)
    @Query("DELETE FROM skip_markers WHERE episodeId = '' AND seriesId = :seriesId AND season = :season AND type = :type")
    suspend fun deleteTemplates(seriesId: String, season: Int, type: String)
    @Query("DELETE FROM skip_markers WHERE episodeId = :episodeId") suspend fun deleteAllMarkers(episodeId: String)

    // Backup / Wiederherstellung
    @Query("SELECT * FROM favorites") suspend fun allFavorites(): List<FavoriteEntity>
    @Query("SELECT * FROM progress") suspend fun allProgress(): List<ProgressEntity>
    @Query("SELECT * FROM skip_markers") suspend fun allMarkers(): List<SkipMarkerEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertProfiles(items: List<ProfileEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertFavorites(items: List<FavoriteEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertProgress(items: List<ProgressEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMarkers(items: List<SkipMarkerEntity>)
    @Query("DELETE FROM profiles") suspend fun clearProfiles()
    @Query("DELETE FROM favorites") suspend fun clearAllFavorites()
    @Query("DELETE FROM progress") suspend fun clearAllProgress()
    @Query("DELETE FROM skip_markers") suspend fun clearAllMarkers()
}

@Dao
interface ExtrasDao {
    // Sender-Gesundheit
    @Upsert suspend fun upsertHealth(h: ChannelHealthEntity)
    @Query("SELECT * FROM channel_health WHERE channelId IN (:ids)") fun healthFor(ids: List<String>): Flow<List<ChannelHealthEntity>>
    @Query("SELECT * FROM channel_health ORDER BY checkedAt DESC LIMIT 200") fun recentHealth(): Flow<List<ChannelHealthEntity>>

    // Sender-Qualität (aus echter Wiedergabe)
    @Upsert suspend fun upsertQuality(q: ChannelQualityEntity)
    @Query("SELECT * FROM channel_quality WHERE channelId = :id") suspend fun quality(id: String): ChannelQualityEntity?
    @Query("SELECT * FROM channel_quality ORDER BY lastPlayed DESC LIMIT 100") fun recentQuality(): Flow<List<ChannelQualityEntity>>

    // Benachrichtigungen
    @Insert suspend fun insertNotification(n: NotificationEntity): Long
    @Query("SELECT * FROM notifications ORDER BY createdAt DESC LIMIT 100") fun notifications(): Flow<List<NotificationEntity>>
    @Query("SELECT COUNT(*) FROM notifications WHERE isRead = 0") fun unreadCount(): Flow<Int>
    @Query("UPDATE notifications SET isRead = 1") suspend fun markAllRead()
    @Query("DELETE FROM notifications") suspend fun clearNotifications()
    @Query("SELECT COUNT(*) FROM notifications WHERE kind = :kind AND targetId = :targetId AND createdAt > :since")
    suspend fun countRecent(kind: String, targetId: String, since: Long): Int

    // Programm-Erinnerungen
    @Insert suspend fun insertReminder(r: ReminderEntity): Long
    @Query("SELECT * FROM reminders WHERE startMs > :now ORDER BY startMs") fun upcomingReminders(now: Long): Flow<List<ReminderEntity>>
    @Query("SELECT * FROM reminders") suspend fun allReminders(): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE id = :id") suspend fun reminder(id: Long): ReminderEntity?
    @Query("SELECT * FROM reminders WHERE channelId = :channelId AND startMs = :startMs") suspend fun findReminder(channelId: String, startMs: Long): ReminderEntity?
    @Query("DELETE FROM reminders WHERE id = :id") suspend fun deleteReminder(id: Long)
    @Query("DELETE FROM reminders WHERE startMs < :before") suspend fun purgeReminders(before: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertReminders(items: List<ReminderEntity>)
}

/** Migration v1 → v2: neue Spalten (Land, Darsteller, Sprache, Profil-Konto) und Tabellen. Nutzerdaten bleiben erhalten. */
object Migrations {
    val SQL_1_2 = listOf(
        "ALTER TABLE `channels` ADD COLUMN `country` TEXT",
        "ALTER TABLE `movies` ADD COLUMN `actors` TEXT",
        "ALTER TABLE `movies` ADD COLUMN `director` TEXT",
        "ALTER TABLE `movies` ADD COLUMN `language` TEXT",
        "ALTER TABLE `series` ADD COLUMN `actors` TEXT",
        "ALTER TABLE `series` ADD COLUMN `language` TEXT",
        "ALTER TABLE `profiles` ADD COLUMN `providerId` TEXT",
        "CREATE TABLE IF NOT EXISTS `channel_health` (`channelId` TEXT NOT NULL, `checkedAt` INTEGER NOT NULL, `ok` INTEGER NOT NULL, `httpCode` INTEGER NOT NULL, `latencyMs` INTEGER NOT NULL, `bitrateKbps` INTEGER NOT NULL, `resolution` TEXT, `error` TEXT, PRIMARY KEY(`channelId`))",
        "CREATE TABLE IF NOT EXISTS `channel_quality` (`channelId` TEXT NOT NULL, `lastPlayed` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `fps` REAL NOT NULL, `codec` TEXT, `droppedFrames` INTEGER NOT NULL, `rebuffers` INTEGER NOT NULL, `watchMs` INTEGER NOT NULL, `errors` INTEGER NOT NULL, PRIMARY KEY(`channelId`))",
        "CREATE TABLE IF NOT EXISTS `notifications` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `createdAt` INTEGER NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `kind` TEXT NOT NULL, `targetKind` TEXT, `targetId` TEXT, `isRead` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `reminders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `channelId` TEXT NOT NULL, `channelName` TEXT NOT NULL, `title` TEXT NOT NULL, `startMs` INTEGER NOT NULL)",
    )

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_1_2.forEach { db.execSQL(it) }
        }
    }
}

@Database(
    entities = [
        ChannelEntity::class, MovieEntity::class, SeriesEntity::class, EpisodeEntity::class,
        EpgProgramEntity::class, ProfileEntity::class, FavoriteEntity::class, ProgressEntity::class,
        SkipMarkerEntity::class, ChannelHealthEntity::class, ChannelQualityEntity::class,
        NotificationEntity::class, ReminderEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class UltraDatabase : RoomDatabase() {
    abstract fun content(): ContentDao
    abstract fun epg(): EpgDao
    abstract fun user(): UserDao
    abstract fun extras(): ExtrasDao
}
