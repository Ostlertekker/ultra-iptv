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
    // Gezieltes Entfernen (System-Updates: nur weggefallene Einträge löschen, alles andere bleibt)
    @Query("DELETE FROM channels WHERE id IN (:ids)") suspend fun deleteChannels(ids: List<String>)
    @Query("DELETE FROM movies WHERE id IN (:ids)") suspend fun deleteMovies(ids: List<String>)
    @Query("DELETE FROM series WHERE id IN (:ids)") suspend fun deleteSeries(ids: List<String>)
    @Query("DELETE FROM episodes WHERE id IN (:ids)") suspend fun deleteEpisodes(ids: List<String>)
    @Query("DELETE FROM episodes WHERE seriesId IN (:seriesIds)") suspend fun deleteEpisodesOfSeries(seriesIds: List<String>)
    @Query("SELECT * FROM channels") suspend fun allChannels(): List<ChannelEntity>
    @Query("SELECT * FROM movies") suspend fun allMovies(): List<MovieEntity>
    @Query("SELECT * FROM series") suspend fun allSeries(): List<SeriesEntity>
    @Query("SELECT * FROM episodes WHERE id LIKE 'm3u:%'") suspend fun allM3uEpisodes(): List<EpisodeEntity>

    // ---------- Live ----------
    @Query("SELECT category FROM channels WHERE (adult = 0 OR :showAdult = 1) GROUP BY category ORDER BY MIN(number), category")
    fun channelCategories(showAdult: Boolean): Flow<List<String>>

    @Query("SELECT * FROM channels WHERE category = :category AND (adult = 0 OR :showAdult = 1) ORDER BY number, name")
    fun channelsIn(category: String, showAdult: Boolean): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE category = :category AND (adult = 0 OR :showAdult = 1) ORDER BY number, name")
    suspend fun channelsInOnce(category: String, showAdult: Boolean): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE (adult = 0 OR :showAdult = 1) ORDER BY number, name LIMIT :limit")
    suspend fun channelsAllOnce(showAdult: Boolean, limit: Int): List<ChannelEntity>
    @Query("SELECT * FROM movies WHERE (adult = 0 OR :showAdult = 1) ORDER BY addedAt DESC LIMIT :limit")
    suspend fun moviesAllOnce(showAdult: Boolean, limit: Int): List<MovieEntity>

    @Query("SELECT * FROM channels WHERE id = :id") suspend fun channel(id: String): ChannelEntity?
    @Query("SELECT * FROM channels WHERE number = :number AND (adult = 0 OR :showAdult = 1) ORDER BY name LIMIT 1") suspend fun channelByNumber(number: Int, showAdult: Boolean): ChannelEntity?
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

    @Query("SELECT MAX(endMs) FROM epg")
    suspend fun maxEnd(): Long?

    @Query("SELECT * FROM epg WHERE channelEpgId = :epgId AND endMs > :now ORDER BY startMs LIMIT 2")
    suspend fun nowNext(epgId: String, now: Long): List<EpgProgramEntity>

    @Query("SELECT * FROM epg WHERE channelEpgId IN (:ids) AND endMs > :from AND startMs < :to ORDER BY startMs")
    suspend fun programs(ids: List<String>, from: Long, to: Long): List<EpgProgramEntity>

    /** Was läuft zum Zeitpunkt :at auf allen Sendern (What's Playing Now, TV Time Machine). */
    @Query("""SELECT c.id AS channelId, c.name AS channelName, c.logo AS logo, c.category AS category, c.number AS number,
        c.archiveDays AS archiveDays, e.title AS title, e.description AS description, e.startMs AS startMs, e.endMs AS endMs
        FROM epg e INNER JOIN channels c ON c.epgId = e.channelEpgId
        WHERE e.startMs <= :at AND e.endMs > :at AND (c.adult = 0 OR :adult = 1)
        GROUP BY c.id ORDER BY CASE WHEN c.number > 0 THEN c.number ELSE 999999 END, c.name LIMIT :limit""")
    suspend fun airingAt(at: Long, adult: Boolean, limit: Int): List<AiringProgram>

    /** Programmsuche über alle Sender (Channel Radar, Global EPG, Live Event Tracker). */
    @Query("""SELECT c.id AS channelId, c.name AS channelName, c.logo AS logo, c.category AS category, c.number AS number,
        c.archiveDays AS archiveDays, e.title AS title, e.description AS description, e.startMs AS startMs, e.endMs AS endMs
        FROM epg e INNER JOIN channels c ON c.epgId = e.channelEpgId
        WHERE e.endMs > :from AND e.startMs < :to AND (c.adult = 0 OR :adult = 1)
          AND (e.title LIKE '%' || :q || '%' OR e.description LIKE '%' || :q || '%' OR c.name LIKE '%' || :q || '%')
        ORDER BY e.startMs, c.number LIMIT :limit""")
    suspend fun searchPrograms(q: String, from: Long, to: Long, adult: Boolean, limit: Int): List<AiringProgram>

    /** Alle Sendungen in einem Zeitfenster (für Stichwort-Klassifikation im Speicher). */
    @Query("""SELECT c.id AS channelId, c.name AS channelName, c.logo AS logo, c.category AS category, c.number AS number,
        c.archiveDays AS archiveDays, e.title AS title, e.description AS description, e.startMs AS startMs, e.endMs AS endMs
        FROM epg e INNER JOIN channels c ON c.epgId = e.channelEpgId
        WHERE e.endMs > :from AND e.startMs < :to AND (c.adult = 0 OR :adult = 1)
        ORDER BY e.startMs LIMIT :limit""")
    suspend fun window(from: Long, to: Long, adult: Boolean, limit: Int): List<AiringProgram>
}

/** Sendung + Sender in einem (Ergebnis von JOIN-Abfragen). */
data class AiringProgram(
    val channelId: String,
    val channelName: String,
    val logo: String?,
    val category: String,
    val number: Int,
    val archiveDays: Int,
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
)

@Dao
interface DiscoverDao {
    @Insert suspend fun insertWatch(w: WatchLogEntity)
    @Query("SELECT * FROM watch_log WHERE profileId = :profileId AND startedAt >= :since ORDER BY startedAt DESC")
    suspend fun watchSince(profileId: Long, since: Long): List<WatchLogEntity>
    @Query("DELETE FROM watch_log WHERE startedAt < :before") suspend fun purgeWatch(before: Long)
    @Query("DELETE FROM watch_log WHERE profileId = :profileId") suspend fun clearWatch(profileId: Long)

    @Query("SELECT * FROM virtual_lists WHERE profileId = :profileId ORDER BY name") fun lists(profileId: Long): Flow<List<VirtualListEntity>>
    @Query("SELECT * FROM virtual_lists WHERE profileId = :profileId ORDER BY name") suspend fun listsOnce(profileId: Long): List<VirtualListEntity>
    @Query("SELECT * FROM virtual_lists WHERE id = :id") suspend fun list(id: Long): VirtualListEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveList(l: VirtualListEntity): Long
    @Query("DELETE FROM virtual_lists WHERE id = :id") suspend fun deleteList(id: Long)
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
    @Query("SELECT * FROM favorites WHERE profileId = :profileId AND kind = :kind ORDER BY addedAt DESC")
    suspend fun favoritesOrdered(profileId: Long, kind: String): List<FavoriteEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFavorites(f: List<FavoriteEntity>)
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

    val SQL_2_3 = listOf(
        "ALTER TABLE `channels` ADD COLUMN `archiveDays` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `channels` ADD COLUMN `catchupMode` TEXT",
        "ALTER TABLE `channels` ADD COLUMN `catchupSource` TEXT",
        "CREATE TABLE IF NOT EXISTS `watch_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profileId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `itemId` TEXT NOT NULL, `name` TEXT NOT NULL, `category` TEXT, `startedAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_watch_log_startedAt` ON `watch_log` (`startedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_watch_log_itemId` ON `watch_log` (`itemId`)",
        "CREATE TABLE IF NOT EXISTS `virtual_lists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profileId` INTEGER NOT NULL, `name` TEXT NOT NULL, `rulesJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
    )

    /**
     * Kürzt übergroße Einträge mancher Anbieter (eingebettete Bilder, riesige Beschreibungen).
     * Eine einzelne Zeile über ~2 MB lässt sonst das Laden ganzer Film-/Serienseiten abstürzen.
     */
    val SANITIZE = listOf(
        "UPDATE movies SET poster = NULL WHERE length(poster) > 2048",
        "UPDATE movies SET backdrop = NULL WHERE length(backdrop) > 2048",
        "UPDATE movies SET plot = substr(plot, 1, 4000) WHERE length(plot) > 4000",
        "UPDATE movies SET actors = substr(actors, 1, 1000) WHERE length(actors) > 1000",
        "UPDATE movies SET director = substr(director, 1, 300) WHERE length(director) > 300",
        "UPDATE movies SET genre = substr(genre, 1, 200) WHERE length(genre) > 200",
        "UPDATE movies SET name = substr(name, 1, 300) WHERE length(name) > 300",
        "UPDATE series SET poster = NULL WHERE length(poster) > 2048",
        "UPDATE series SET backdrop = NULL WHERE length(backdrop) > 2048",
        "UPDATE series SET plot = substr(plot, 1, 4000) WHERE length(plot) > 4000",
        "UPDATE series SET actors = substr(actors, 1, 1000) WHERE length(actors) > 1000",
        "UPDATE series SET genre = substr(genre, 1, 200) WHERE length(genre) > 200",
        "UPDATE series SET name = substr(name, 1, 300) WHERE length(name) > 300",
        "UPDATE episodes SET image = NULL WHERE length(image) > 2048",
        "UPDATE episodes SET plot = substr(plot, 1, 4000) WHERE length(plot) > 4000",
        "UPDATE episodes SET title = substr(title, 1, 300) WHERE length(title) > 300",
        "UPDATE channels SET logo = NULL WHERE length(logo) > 2048",
        "UPDATE channels SET name = substr(name, 1, 300) WHERE length(name) > 300",
        "UPDATE epg SET description = substr(description, 1, 2000) WHERE length(description) > 2000",
    )

    /** v4: Indizes für „Neu“ und „Beliebt“ – Reihen mit 20.000+ Titeln müssen nicht mehr komplett sortiert werden. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_rating` ON `movies` (`rating`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_addedAt` ON `series` (`addedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_rating` ON `series` (`rating`)")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) { SQL_2_3.forEach { db.execSQL(it) } }
    }

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
        NotificationEntity::class, ReminderEntity::class, WatchLogEntity::class, VirtualListEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class UltraDatabase : RoomDatabase() {
    abstract fun content(): ContentDao
    abstract fun epg(): EpgDao
    abstract fun user(): UserDao
    abstract fun extras(): ExtrasDao
    abstract fun discover(): DiscoverDao
}
