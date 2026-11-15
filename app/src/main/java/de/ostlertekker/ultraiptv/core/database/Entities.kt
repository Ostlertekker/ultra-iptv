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
)

@Entity(tableName = "movies", indices = [Index("category"), Index("name"), Index("addedAt")])
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
)

@Entity(tableName = "series", indices = [Index("category"), Index("name")])
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
