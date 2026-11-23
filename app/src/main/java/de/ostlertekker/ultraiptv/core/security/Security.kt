package de.ostlertekker.ultraiptv.core.security

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ostlertekker.ultraiptv.core.model.ProviderConfig
import de.ostlertekker.ultraiptv.core.model.ProviderType
import de.ostlertekker.ultraiptv.core.network.XtreamUrls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Ein gespeichertes Anbieter-Konto (Multi-Account). */
data class ProviderAccount(val id: String, val config: ProviderConfig) {
    val label: String
        get() = config.name.ifBlank {
            when (config.type) {
                ProviderType.XTREAM -> "${config.username} @ ${config.server.removePrefix("http://").removePrefix("https://").substringBefore('/')}"
                ProviderType.M3U -> config.m3uUrl.removePrefix("http://").removePrefix("https://").substringBefore('/')
            }
        }
}

fun ProviderConfig.toJson(): JsonObject = buildJsonObject {
    put("type", type.name); put("name", name); put("m3u", m3uUrl); put("epg", epgUrl)
    put("server", server); put("user", username); put("pass", password)
}

fun JsonObject.toProviderConfig(): ProviderConfig? {
    fun s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""
    val type = runCatching { ProviderType.valueOf(s("type")) }.getOrNull() ?: return null
    return ProviderConfig(type, s("name"), s("m3u"), s("epg"), s("server"), s("user"), s("pass")).takeIf { it.isValid }
}

/**
 * Speichert beliebig viele Anbieter-Konten verschlüsselt (AES-256-GCM, Android Keystore).
 * Konten funktionieren unabhängig vom Wohnort – Verbindungslimits des Anbieters gelten weiterhin.
 * Ist der Keystore defekt (selten auf alten Fire-OS-Builds), wird das in der Diagnose angezeigt.
 */
@Singleton
class CredentialStore @Inject constructor(@ApplicationContext private val context: Context) {

    var encryptionAvailable: Boolean = true
        private set

    private val prefs: SharedPreferences by lazy { createPrefs() }

    private val _accounts = MutableStateFlow<List<ProviderAccount>>(emptyList())
    val accounts: StateFlow<List<ProviderAccount>> = _accounts.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _config = MutableStateFlow<ProviderConfig?>(null)
    /** Konfiguration des aktiven Kontos. */
    val config: StateFlow<ProviderConfig?> = _config.asStateFlow()

    private var initialized = false

    private fun encrypted(): SharedPreferences {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun createPrefs(): SharedPreferences = try {
        encrypted()
    } catch (e: Exception) {
        try {
            context.deleteSharedPreferences(FILE)
            encrypted()
        } catch (e2: Exception) {
            encryptionAvailable = false
            context.getSharedPreferences("ultra_fallback_store", Context.MODE_PRIVATE)
        }
    }

    @Synchronized
    fun init() {
        initialized = true
        val list = ArrayList<ProviderAccount>()
        prefs.getString("accounts", null)?.let { raw ->
            val arr = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            arr?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val cfg = (o["config"] as? JsonObject)?.toProviderConfig() ?: return@forEach
                list.add(ProviderAccount(id, cfg))
            }
        }
        // Übernahme aus Version 1.0 (einzelnes Konto)
        if (list.isEmpty()) legacy()?.let { list.add(ProviderAccount(UUID.randomUUID().toString(), it)) }
        var active = prefs.getString("active", null)
        if (list.none { it.id == active }) active = list.firstOrNull()?.id
        _accounts.value = list
        _activeId.value = active
        _config.value = list.firstOrNull { it.id == active }?.config
        persist()
    }

    private fun legacy(): ProviderConfig? {
        val type = prefs.getString("type", null) ?: return null
        return ProviderConfig(
            type = runCatching { ProviderType.valueOf(type) }.getOrDefault(ProviderType.M3U),
            name = prefs.getString("name", "") ?: "", m3uUrl = prefs.getString("m3u", "") ?: "",
            epgUrl = prefs.getString("epg", "") ?: "", server = prefs.getString("server", "") ?: "",
            username = prefs.getString("user", "") ?: "", password = prefs.getString("pass", "") ?: "",
        ).takeIf { it.isValid }
    }

    private fun persist() {
        val arr = buildJsonArray {
            _accounts.value.forEach { a -> add(buildJsonObject { put("id", a.id); put("config", a.config.toJson()) }) }
        }
        prefs.edit().putString("accounts", arr.toString()).putString("active", _activeId.value)
            .remove("type").remove("name").remove("m3u").remove("epg").remove("server").remove("user").remove("pass")
            .apply()
    }

    /** Aktive Konfiguration (für Sync/Wiedergabe). */
    fun load(): ProviderConfig? {
        if (!initialized) init()
        return _config.value
    }

    /** Speichert ein Konto: ohne [id] als neues Konto, sonst Aktualisierung. Wird aktiv gesetzt. */
    @Synchronized
    fun saveAccount(cfg: ProviderConfig, id: String? = null): String {
        val accId = id ?: UUID.randomUUID().toString()
        val existing = _accounts.value
        val idx = existing.indexOfFirst { it.id == accId }
        _accounts.value = if (idx >= 0) existing.toMutableList().also { it[idx] = ProviderAccount(accId, cfg) } else existing + ProviderAccount(accId, cfg)
        setActiveInternal(accId)
        return accId
    }

    /** Kompatibel zu 1.0: aktualisiert das aktive Konto oder legt das erste an. */
    fun save(cfg: ProviderConfig) { saveAccount(cfg, _activeId.value) }

    @Synchronized
    fun setActive(id: String): Boolean {
        if (_accounts.value.none { it.id == id }) return false
        setActiveInternal(id)
        return true
    }

    private fun setActiveInternal(id: String) {
        _activeId.value = id
        _config.value = _accounts.value.firstOrNull { it.id == id }?.config
        persist()
    }

    @Synchronized
    fun remove(id: String) {
        _accounts.value = _accounts.value.filter { it.id != id }
        if (_activeId.value == id) {
            _activeId.value = _accounts.value.firstOrNull()?.id
            _config.value = _accounts.value.firstOrNull()?.config
        }
        persist()
    }

    /** Entfernt das aktive Konto. */
    fun clear() { _activeId.value?.let { remove(it) } }

    /** Fügt Konten aus einem Backup hinzu (Duplikate werden übersprungen). */
    @Synchronized
    fun importAccounts(items: List<ProviderAccount>) {
        val existing = _accounts.value
        val merged = existing + items.filter { n -> existing.none { it.id == n.id || it.config == n.config } }
        _accounts.value = merged
        if (_activeId.value == null) _activeId.value = merged.firstOrNull()?.id
        _config.value = merged.firstOrNull { it.id == _activeId.value }?.config
        persist()
    }

    private companion object { const val FILE = "ultra_secure_store" }
}

/** Baut abspielbare URLs erst zur Laufzeit – in der Datenbank stehen keine Xtream-Passwörter. */
@Singleton
class StreamResolver @Inject constructor(private val creds: CredentialStore) {
    fun resolve(raw: String, liveFormat: String): String {
        if (!raw.startsWith(SCHEME)) return raw
        val cfg = creds.config.value ?: creds.load() ?: error(tr("Keine Zugangsdaten gespeichert"))
        val rest = raw.removePrefix(SCHEME)
        val type = rest.substringBefore('/')
        val file = rest.substringAfter('/')
        val id = file.substringBeforeLast('.')
        if (type == "timeshift") {
            // Archiv des Anbieters (Catch-up/Timeshift) – nur Xtream-Archiv-URL, keine lokale Aufnahme
            val r = de.ostlertekker.ultraiptv.core.catchup.Catchup.parseXtreamRef(file) ?: error(tr("Ungültige Archiv-Referenz"))
            return de.ostlertekker.ultraiptv.core.catchup.Catchup.xtreamPath(XtreamUrls.normalizeServer(cfg.server), cfg.username, cfg.password, r.streamId, r.startMs, r.durationMin, r.tz)
        }
        var ext = if ('.' in file) file.substringAfterLast('.') else ""
        if (type == "live") ext = liveFormat
        return XtreamUrls.stream(XtreamUrls.normalizeServer(cfg.server), cfg.username, cfg.password, type, id, ext)
    }

    companion object {
        const val SCHEME = "xtream://"
        fun ref(type: String, id: String, ext: String): String =
            if (ext.isBlank()) "$SCHEME$type/$id" else "$SCHEME$type/$id.$ext"
    }
}
