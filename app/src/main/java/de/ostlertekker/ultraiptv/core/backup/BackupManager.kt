package de.ostlertekker.ultraiptv.core.backup

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ostlertekker.ultraiptv.BuildConfig
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.database.ExtrasDao
import de.ostlertekker.ultraiptv.core.database.FavoriteEntity
import de.ostlertekker.ultraiptv.core.database.ProfileEntity
import de.ostlertekker.ultraiptv.core.database.ProgressEntity
import de.ostlertekker.ultraiptv.core.database.ReminderEntity
import de.ostlertekker.ultraiptv.core.database.SkipMarkerEntity
import de.ostlertekker.ultraiptv.core.database.UltraDatabase
import de.ostlertekker.ultraiptv.core.security.CredentialStore
import de.ostlertekker.ultraiptv.core.security.ProviderAccount
import de.ostlertekker.ultraiptv.core.security.toJson
import de.ostlertekker.ultraiptv.core.security.toProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class RestoreResult(val profiles: Int, val favorites: Int, val progress: Int, val markers: Int, val accounts: Int, val settings: Int)

/**
 * Backup & Wiederherstellung: Einstellungen, Profile, Favoriten, Verlauf, Smart-Skip-Marken,
 * Erinnerungen und – nur auf Wunsch – Anbieter-Konten als JSON.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: UltraDatabase,
    private val extras: ExtrasDao,
    private val settings: SettingsRepository,
    private val creds: CredentialStore,
) {
    val backupDir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups").apply { mkdirs() }

    suspend fun export(includeAccounts: Boolean): JsonObject = withContext(Dispatchers.IO) {
        val user = db.user()
        buildJsonObject {
            put("app", tr("ULTRA IPTV"))
            put("format", 1)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("createdAt", System.currentTimeMillis())
            put("settings", buildJsonObject {
                settings.exportRaw().forEach { (k, v) ->
                    if (k in EXCLUDED_SETTINGS) return@forEach
                    put(k, when (v) {
                        is Boolean -> buildJsonObject { put("t", "b"); put("v", v) }
                        is Int -> buildJsonObject { put("t", "i"); put("v", v) }
                        is Long -> buildJsonObject { put("t", "l"); put("v", v) }
                        is Float -> buildJsonObject { put("t", "f"); put("v", v) }
                        else -> buildJsonObject { put("t", "s"); put("v", v.toString()) }
                    })
                }
            })
            put("profiles", buildJsonArray {
                user.profilesOnce().forEach { p -> add(buildJsonObject {
                    put("id", p.id); put("name", p.name); put("color", p.colorIndex); put("pinHash", p.pinHash); put("pinSalt", p.pinSalt)
                    put("maxAge", p.maxAge); put("theme", p.themeId); put("anim", p.animationProfile); put("audio", p.audioLanguage)
                    put("subs", p.subtitleLanguage); put("provider", p.providerId)
                }) }
            })
            put("favorites", buildJsonArray {
                user.allFavorites().forEach { f -> add(buildJsonObject { put("p", f.profileId); put("k", f.kind); put("i", f.itemId); put("a", f.addedAt) }) }
            })
            put("progress", buildJsonArray {
                user.allProgress().forEach { r -> add(buildJsonObject {
                    put("p", r.profileId); put("k", r.kind); put("i", r.itemId); put("pos", r.positionMs); put("dur", r.durationMs)
                    put("u", r.updatedAt); put("t", r.title); put("img", r.image); put("s", r.seriesId)
                }) }
            })
            put("markers", buildJsonArray {
                user.allMarkers().forEach { m -> add(buildJsonObject {
                    put("e", m.episodeId); put("s", m.seriesId); put("season", m.season); put("type", m.type); put("start", m.startMs)
                    put("end", m.endMs); put("src", m.source); put("conf", m.confidence); put("c", m.createdAt)
                }) }
            })
            put("reminders", buildJsonArray {
                extras.allReminders().filter { it.startMs > System.currentTimeMillis() }.forEach { r -> add(buildJsonObject {
                    put("c", r.channelId); put("n", r.channelName); put("t", r.title); put("s", r.startMs)
                }) }
            })
            if (includeAccounts) put("accounts", buildJsonArray {
                creds.accounts.value.forEach { a -> add(buildJsonObject { put("id", a.id); put("config", a.config.toJson()) }) }
            })
        }
    }

    suspend fun exportToFile(includeAccounts: Boolean): File = withContext(Dispatchers.IO) {
        val name = "ultra-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
        File(backupDir, name).apply { writeText(export(includeAccounts).toString()) }
    }

    fun listBackups(): List<File> = backupDir.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    suspend fun restoreFile(file: File): RestoreResult = restore(withContext(Dispatchers.IO) { file.readText() })

    /** Stellt eine Sicherung wieder her. Profile, Favoriten, Verlauf und Marken werden ersetzt; Konten zusammengeführt. */
    suspend fun restore(jsonText: String): RestoreResult = withContext(Dispatchers.IO) {
        val root = Json.parseToJsonElement(jsonText) as? JsonObject ?: error(tr("Keine gültige Sicherung"))
        if ((root["app"] as? JsonPrimitive)?.contentOrNull != tr("ULTRA IPTV")) error(tr("Datei ist keine ULTRA-IPTV-Sicherung"))
        fun JsonElement?.o() = this as? JsonObject
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
        fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
        fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: 0
        fun arr(k: String) = (root[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

        val profiles = arr("profiles").map {
            ProfileEntity(it.l("id"), it.s("name") ?: "Profil", it.i("color"), it.s("pinHash"), it.s("pinSalt"), it.i("maxAge"),
                it.s("theme"), it.s("anim"), it.s("audio") ?: "", it.s("subs") ?: "", it.s("provider"))
        }
        val favorites = arr("favorites").map { FavoriteEntity(it.l("p"), it.s("k") ?: "", it.s("i") ?: "", it.l("a")) }
        val progress = arr("progress").map {
            ProgressEntity(it.l("p"), it.s("k") ?: "", it.s("i") ?: "", it.l("pos"), it.l("dur"), it.l("u"), it.s("t") ?: "", it.s("img"), it.s("s"))
        }
        val markers = arr("markers").map {
            SkipMarkerEntity(0, it.s("e") ?: "", it.s("s") ?: "", it.i("season"), it.s("type") ?: "INTRO", it.l("start"), it.l("end"),
                it.s("src") ?: "USER", (it["conf"] as? JsonPrimitive)?.floatOrNull ?: 1f, it.l("c"))
        }
        val reminders = arr("reminders").map { ReminderEntity(0, it.s("c") ?: "", it.s("n") ?: "", it.s("t") ?: "", it.l("s")) }
        db.withTransaction {
            val u = db.user()
            if (profiles.isNotEmpty()) { u.clearProfiles(); u.insertProfiles(profiles) }
            u.clearAllFavorites(); u.insertFavorites(favorites)
            u.clearAllProgress(); u.insertProgress(progress)
            u.clearAllMarkers(); u.insertMarkers(markers)
            extras.insertReminders(reminders)
        }
        var settingsCount = 0
        root["settings"].o()?.let { s ->
            val values = HashMap<String, Any>()
            s.forEach { (k, v) ->
                val o = v as? JsonObject ?: return@forEach
                val p = o["v"] as? JsonPrimitive ?: return@forEach
                val value: Any? = when ((o["t"] as? JsonPrimitive)?.contentOrNull) {
                    "b" -> p.booleanOrNull; "i" -> p.intOrNull; "l" -> p.longOrNull; "f" -> p.floatOrNull; "s" -> p.contentOrNull
                    else -> null
                }
                if (value != null && k !in EXCLUDED_SETTINGS) values[k] = value
            }
            settings.importRaw(values)
            settingsCount = values.size
        }
        val accounts = arr("accounts").mapNotNull { a ->
            val id = a.s("id") ?: return@mapNotNull null
            val cfg = a["config"].o()?.toProviderConfig() ?: return@mapNotNull null
            ProviderAccount(id, cfg)
        }
        if (accounts.isNotEmpty()) creds.importAccounts(accounts)
        RestoreResult(profiles.size, favorites.size, progress.size, markers.size, accounts.size, settingsCount)
    }

    companion object {
        /** Gerätespezifische Werte werden nicht übertragen. */
        val EXCLUDED_SETTINGS = setOf("last_sync", "last_epg_sync", "intro_played", "last_update_check")
    }
}
