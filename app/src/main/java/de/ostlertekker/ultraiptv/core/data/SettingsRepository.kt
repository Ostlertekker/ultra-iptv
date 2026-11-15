package de.ostlertekker.ultraiptv.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ostlertekker.ultraiptv.core.model.AnimationProfile
import de.ostlertekker.ultraiptv.core.model.BufferProfile
import de.ostlertekker.ultraiptv.core.model.OutroMode
import de.ostlertekker.ultraiptv.core.model.QualityMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ultraDataStore: DataStore<Preferences> by preferencesDataStore(name = "ultra_settings")

data class AppSettings(
    val themeId: String = "galaxy",
    val accentArgb: Long = 0L,
    val customBackgroundUrl: String = "",
    val animationAuto: Boolean = true,
    val animationProfile: AnimationProfile = AnimationProfile.BALANCED,
    val reduceMotion: Boolean = false,
    val backgroundAnimation: Boolean = true,
    val animationIntensity: Float = 1f,
    val particleIntensity: Float = 1f,
    val introEnabled: Boolean = true,
    val introSound: Boolean = true,
    val introPlayedOnce: Boolean = false,
    val uiSounds: Boolean = true,
    val themeSounds: Boolean = true,
    val uiVolume: Float = 0.5f,
    val qualityMode: QualityMode = QualityMode.AUTO,
    val manualMaxHeight: Int = 1080,
    val matchFrameRate: Boolean = true,
    val bufferProfile: BufferProfile = BufferProfile.STANDARD,
    val liveFormat: String = "ts",
    val livePreview: Boolean = true,
    val smartSkipEnabled: Boolean = true,
    val autoSkipIntro: Boolean = true,
    val autoSkipOutro: Boolean = false,
    val autoSkipRecap: Boolean = true,
    val autoNextEpisode: Boolean = true,
    val nextCountdownSec: Int = 10,
    val skipManualOnly: Boolean = false,
    val outroMode: OutroMode = OutroMode.FULL,
    val upscalingRequested: Boolean = false,
    val epgUrlOverride: String = "",
    val epgRefreshHours: Int = 12,
    val epgOffsetHours: Int = 0,
    val epgDays: Int = 2,
    val lastSyncMs: Long = 0,
    val lastEpgSyncMs: Long = 0,
    val activeProfileId: Long = 0,
    val showAdult: Boolean = false,
    val searchHistory: List<String> = emptyList(),
    val subtitleScale: Float = 1f,
    val subtitleColor: Int = 0,
    val subtitleBackground: Int = 1,
    val backgroundAudio: Boolean = false,
    val autoUpdateHours: Int = 12,
    val updateCheck: Boolean = true,
    val autoQuality: Boolean = true,
    val autoFailover: Boolean = true,
    val lastUpdateCheck: Long = 0,
)

object Keys {
    val THEME = stringPreferencesKey("theme")
    val ACCENT = longPreferencesKey("accent")
    val CUSTOM_BG = stringPreferencesKey("custom_bg")
    val ANIM_AUTO = booleanPreferencesKey("anim_auto")
    val ANIM_PROFILE = stringPreferencesKey("anim_profile")
    val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
    val BG_ANIM = booleanPreferencesKey("bg_anim")
    val ANIM_INTENSITY = floatPreferencesKey("anim_intensity")
    val PARTICLE_INTENSITY = floatPreferencesKey("particle_intensity")
    val INTRO = booleanPreferencesKey("intro")
    val INTRO_SOUND = booleanPreferencesKey("intro_sound")
    val INTRO_PLAYED = booleanPreferencesKey("intro_played")
    val UI_SOUNDS = booleanPreferencesKey("ui_sounds")
    val THEME_SOUNDS = booleanPreferencesKey("theme_sounds")
    val UI_VOLUME = floatPreferencesKey("ui_volume")
    val QUALITY = stringPreferencesKey("quality")
    val MANUAL_HEIGHT = intPreferencesKey("manual_height")
    val MATCH_FPS = booleanPreferencesKey("match_fps")
    val BUFFER = stringPreferencesKey("buffer")
    val LIVE_FORMAT = stringPreferencesKey("live_format")
    val LIVE_PREVIEW = booleanPreferencesKey("live_preview")
    val SKIP_ENABLED = booleanPreferencesKey("skip_enabled")
    val SKIP_INTRO = booleanPreferencesKey("skip_intro")
    val SKIP_OUTRO = booleanPreferencesKey("skip_outro")
    val SKIP_RECAP = booleanPreferencesKey("skip_recap")
    val AUTO_NEXT = booleanPreferencesKey("auto_next")
    val COUNTDOWN = intPreferencesKey("countdown")
    val SKIP_MANUAL = booleanPreferencesKey("skip_manual")
    val OUTRO_MODE = stringPreferencesKey("outro_mode")
    val UPSCALING = booleanPreferencesKey("upscaling")
    val EPG_URL = stringPreferencesKey("epg_url")
    val EPG_REFRESH = intPreferencesKey("epg_refresh")
    val EPG_OFFSET = intPreferencesKey("epg_offset")
    val EPG_DAYS = intPreferencesKey("epg_days")
    val LAST_SYNC = longPreferencesKey("last_sync")
    val LAST_EPG_SYNC = longPreferencesKey("last_epg_sync")
    val ACTIVE_PROFILE = longPreferencesKey("active_profile")
    val SHOW_ADULT = booleanPreferencesKey("show_adult")
    val SEARCH_HISTORY = stringPreferencesKey("search_history")
    val SUB_SCALE = floatPreferencesKey("sub_scale")
    val SUB_COLOR = intPreferencesKey("sub_color")
    val SUB_BG = intPreferencesKey("sub_bg")
    val BG_AUDIO = booleanPreferencesKey("bg_audio")
    val AUTO_UPDATE_HOURS = intPreferencesKey("auto_update_hours")
    val UPDATE_CHECK = booleanPreferencesKey("update_check")
    val AUTO_QUALITY = booleanPreferencesKey("auto_quality")
    val AUTO_FAILOVER = booleanPreferencesKey("auto_failover")
    val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
}

private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
    value?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

fun Preferences.toSettings(): AppSettings {
    val d = AppSettings()
    return AppSettings(
        themeId = this[Keys.THEME] ?: d.themeId,
        accentArgb = this[Keys.ACCENT] ?: d.accentArgb,
        customBackgroundUrl = this[Keys.CUSTOM_BG] ?: d.customBackgroundUrl,
        animationAuto = this[Keys.ANIM_AUTO] ?: d.animationAuto,
        animationProfile = enumOr(this[Keys.ANIM_PROFILE], d.animationProfile),
        reduceMotion = this[Keys.REDUCE_MOTION] ?: d.reduceMotion,
        backgroundAnimation = this[Keys.BG_ANIM] ?: d.backgroundAnimation,
        animationIntensity = this[Keys.ANIM_INTENSITY] ?: d.animationIntensity,
        particleIntensity = this[Keys.PARTICLE_INTENSITY] ?: d.particleIntensity,
        introEnabled = this[Keys.INTRO] ?: d.introEnabled,
        introSound = this[Keys.INTRO_SOUND] ?: d.introSound,
        introPlayedOnce = this[Keys.INTRO_PLAYED] ?: d.introPlayedOnce,
        uiSounds = this[Keys.UI_SOUNDS] ?: d.uiSounds,
        themeSounds = this[Keys.THEME_SOUNDS] ?: d.themeSounds,
        uiVolume = this[Keys.UI_VOLUME] ?: d.uiVolume,
        qualityMode = enumOr(this[Keys.QUALITY], d.qualityMode),
        manualMaxHeight = this[Keys.MANUAL_HEIGHT] ?: d.manualMaxHeight,
        matchFrameRate = this[Keys.MATCH_FPS] ?: d.matchFrameRate,
        bufferProfile = enumOr(this[Keys.BUFFER], d.bufferProfile),
        liveFormat = this[Keys.LIVE_FORMAT] ?: d.liveFormat,
        livePreview = this[Keys.LIVE_PREVIEW] ?: d.livePreview,
        smartSkipEnabled = this[Keys.SKIP_ENABLED] ?: d.smartSkipEnabled,
        autoSkipIntro = this[Keys.SKIP_INTRO] ?: d.autoSkipIntro,
        autoSkipOutro = this[Keys.SKIP_OUTRO] ?: d.autoSkipOutro,
        autoSkipRecap = this[Keys.SKIP_RECAP] ?: d.autoSkipRecap,
        autoNextEpisode = this[Keys.AUTO_NEXT] ?: d.autoNextEpisode,
        nextCountdownSec = this[Keys.COUNTDOWN] ?: d.nextCountdownSec,
        skipManualOnly = this[Keys.SKIP_MANUAL] ?: d.skipManualOnly,
        outroMode = enumOr(this[Keys.OUTRO_MODE], d.outroMode),
        upscalingRequested = this[Keys.UPSCALING] ?: d.upscalingRequested,
        epgUrlOverride = this[Keys.EPG_URL] ?: d.epgUrlOverride,
        epgRefreshHours = this[Keys.EPG_REFRESH] ?: d.epgRefreshHours,
        epgOffsetHours = this[Keys.EPG_OFFSET] ?: d.epgOffsetHours,
        epgDays = this[Keys.EPG_DAYS] ?: d.epgDays,
        lastSyncMs = this[Keys.LAST_SYNC] ?: d.lastSyncMs,
        lastEpgSyncMs = this[Keys.LAST_EPG_SYNC] ?: d.lastEpgSyncMs,
        activeProfileId = this[Keys.ACTIVE_PROFILE] ?: d.activeProfileId,
        showAdult = this[Keys.SHOW_ADULT] ?: d.showAdult,
        searchHistory = (this[Keys.SEARCH_HISTORY] ?: "").split('\u001F').filter { it.isNotBlank() },
        subtitleScale = this[Keys.SUB_SCALE] ?: d.subtitleScale,
        subtitleColor = this[Keys.SUB_COLOR] ?: d.subtitleColor,
        subtitleBackground = this[Keys.SUB_BG] ?: d.subtitleBackground,
        backgroundAudio = this[Keys.BG_AUDIO] ?: d.backgroundAudio,
        autoUpdateHours = this[Keys.AUTO_UPDATE_HOURS] ?: d.autoUpdateHours,
        updateCheck = this[Keys.UPDATE_CHECK] ?: d.updateCheck,
        autoQuality = this[Keys.AUTO_QUALITY] ?: d.autoQuality,
        autoFailover = this[Keys.AUTO_FAILOVER] ?: d.autoFailover,
        lastUpdateCheck = this[Keys.LAST_UPDATE_CHECK] ?: d.lastUpdateCheck,
    )
}

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.ultraDataStore

    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    suspend fun edit(block: (MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    suspend fun addSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        store.edit { prefs ->
            val list = (prefs[Keys.SEARCH_HISTORY] ?: "").split('\u001F').filter { it.isNotBlank() && !it.equals(q, true) }
            prefs[Keys.SEARCH_HISTORY] = (listOf(q) + list).take(12).joinToString("\u001F")
        }
    }

    /** Alle Rohwerte (für Backup). */
    suspend fun exportRaw(): Map<String, Any> = store.data.first().asMap().mapKeys { it.key.name }

    /** Übernimmt Rohwerte aus einem Backup (Typ wird aus dem Wert abgeleitet). */
    suspend fun importRaw(values: Map<String, Any>) {
        store.edit { prefs ->
            values.forEach { (k, v) ->
                when (v) {
                    is Boolean -> prefs[booleanPreferencesKey(k)] = v
                    is Int -> prefs[intPreferencesKey(k)] = v
                    is Long -> prefs[longPreferencesKey(k)] = v
                    is Float -> prefs[floatPreferencesKey(k)] = v
                    is String -> prefs[stringPreferencesKey(k)] = v
                }
            }
        }
    }

    suspend fun clearSearchHistory() = store.edit { it.remove(Keys.SEARCH_HISTORY) }

    /** Setzt alle Darstellungs-Einstellungen auf Standard zurück. */
    suspend fun resetAppearance() = store.edit {
        it.remove(Keys.THEME); it.remove(Keys.ACCENT); it.remove(Keys.CUSTOM_BG); it.remove(Keys.ANIM_AUTO)
        it.remove(Keys.ANIM_PROFILE); it.remove(Keys.REDUCE_MOTION); it.remove(Keys.BG_ANIM)
        it.remove(Keys.ANIM_INTENSITY); it.remove(Keys.PARTICLE_INTENSITY); it.remove(Keys.THEME_SOUNDS)
    }
}
