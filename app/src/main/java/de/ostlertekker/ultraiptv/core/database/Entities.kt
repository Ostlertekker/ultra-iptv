package de.ostlertekker.ultraiptv.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "channels", indices = [Index("category"), Index("name")])
data class ChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val logo: String?,
    val streamUrl: String,
    val epgId: String?,
    val number: Int,
    val adult: Boolean,
    /** Automatisch erkanntes Land (ISO-Code) aus Kategorie/Name. */
    val country: String? = null,
    /** Archivtage laut Anbieter (Catch-up/Timeshift). 0 = kein Archiv. */
    @androidx.room.ColumnInfo(defaultValue = "0") val archiveDays: Int = 0,
    /** M3U: catchup-Modus (default/append/shift/flussonic); Xtream: "xc". */
    val catchupMode: String? = null,
    /** M3U: catchup-source-Vorlage; Xtream: stream_id. */
    val catchupSource: String? = null,
)

/** Sehprotokoll für „Mein TV-Universum“ (nur lokal gespeichert). */
@Entity(tableName = "watch_log", indices = [Index("startedAt"), Index("itemId")])
data class WatchLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val kind: String,
    val itemId: String,
    val name: String,
    val category: String?,
    val startedAt: Long,
    val durationMs: Long,
)

/** Eigene, virtuelle Senderliste (AI Channel Cloning): Regeln, keine kopierten Streams. */
@Entity(tableName = "virtual_lists")
data class VirtualListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val name: String,
    /** JSON-Regeln (Kategorien, Stichwörter, Smart-Gruppen, EPG-Thema, nur Favoriten). */
    val rulesJson: String,
    val createdAt: Long,
)

@Entity(tableName = "movies", indices = [Index("category"), Index("name"), Index("addedAt"), Index("rating")])
data class MovieEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val poster: String?,
    val backdrop: String?,
    val plot: String?,
    val genre: String?,
    val year: String?,
    val rating: Double?,
    val durationSec: Long?,
    val streamUrl: String,
    val addedAt: Long,
    val adult: Boolean,
    val actors: String? = null,
    val director: String? = null,
    val language: String? = null,
)

@Entity(tableName = "series", indices = [Index("category"), Index("name"), Index("addedAt"), Index("rating")])
data class SeriesEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val poster: String?,
    val backdrop: String?,
    val plot: String?,
    val genre: String?,
    val year: String?,
    val rating: Double?,
    val addedAt: Long,
    val adult: Boolean,
    val actors: String? = null,
    val language: String? = null,
)

@Entity(tableName = "episodes", indices = [Index("seriesId")])
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val seriesId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val plot: String?,
    val image: String?,
    val durationSec: Long?,
    val streamUrl: String,
    /** Vom Anbieter gelieferte Zeitmarken (falls vorhanden). */
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val outroStartMs: Long? = null,
)

@Entity(tableName = "epg", indices = [Index(value = ["channelEpgId", "startMs"])])
data class EpgProgramEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val channelEpgId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String?,
)

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorIndex: Int,
    val pinHash: String? = null,
    val pinSalt: String? = null,
    /** 0 = keine Altersbeschränkung, sonst FSK-Grenze (6/12/16/18). */
    val maxAge: Int = 0,
    val themeId: String? = null,
    val animationProfile: String? = null,
    val audioLanguage: String = "",
    val subtitleLanguage: String = "",
    /** Bevorzugtes Anbieter-Konto dieses Profils (Multi-Account). */
    val providerId: String? = null,
)

@Entity(tableName = "favorites", primaryKeys = ["profileId", "kind", "itemId"])
data class FavoriteEntity(
    val profileId: Long,
    val kind: String,
    val itemId: String,
    val addedAt: Long,
)

@Entity(tableName = "progress", primaryKeys = ["profileId", "kind", "itemId"])
data class ProgressEntity(
    val profileId: Long,
    val kind: String,
    val itemId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val title: String,
    val image: String?,
    val seriesId: String? = null,
)

@Entity(tableName = "skip_markers", indices = [Index("episodeId"), Index(value = ["seriesId", "season"])])
data class SkipMarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Leer = Vorlage für eine ganze Staffel (nur manuell angeboten). */
    val episodeId: String,
    val seriesId: String,
    val season: Int,
    val type: String,
    val startMs: Long,
    val endMs: Long,
    val source: String,
    val confidence: Float,
    val createdAt: Long,
)

@Entity(tableName = "channel_health")
data class ChannelHealthEntity(
    @PrimaryKey val channelId: String,
    val checkedAt: Long,
    val ok: Boolean,
    val httpCode: Int,
    val latencyMs: Long,
    val bitrateKbps: Int,
    val resolution: String?,
    val error: String?,
)

@Entity(tableName = "channel_quality")
data class ChannelQualityEntity(
    @PrimaryKey val channelId: String,
    val lastPlayed: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val codec: String?,
    val droppedFrames: Long,
    val rebuffers: Int,
    val watchMs: Long,
    val errors: Int,
)

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val title: String,
    val body: String,
    val kind: String,
    val targetKind: String?,
    val targetId: String?,
    val isRead: Boolean,
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: String,
    val channelName: String,
    val title: String,
    val startMs: Long,
)
