package de.ostlertekker.ultraiptv.core.security

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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speichert Anbieter-Zugangsdaten verschlüsselt (AES-256-GCM, Android Keystore).
 * Falls der Keystore defekt ist (selten auf älteren Fire-OS-Builds), wird das in der
 * Diagnose als "nicht verschlüsselt" ausgewiesen – nie stillschweigend.
 */
@Singleton
class CredentialStore @Inject constructor(@ApplicationContext private val context: Context) {

    var encryptionAvailable: Boolean = true
        private set

    private val prefs: SharedPreferences by lazy { createPrefs() }

    private val _config = MutableStateFlow<ProviderConfig?>(null)
    val config: StateFlow<ProviderConfig?> = _config.asStateFlow()

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

    fun init() {
        _config.value = load()
    }

    fun load(): ProviderConfig? {
        val type = prefs.getString("type", null) ?: return null
        val cfg = ProviderConfig(
            type = runCatching { ProviderType.valueOf(type) }.getOrDefault(ProviderType.M3U),
            name = prefs.getString("name", "") ?: "",
            m3uUrl = prefs.getString("m3u", "") ?: "",
            epgUrl = prefs.getString("epg", "") ?: "",
            server = prefs.getString("server", "") ?: "",
            username = prefs.getString("user", "") ?: "",
            password = prefs.getString("pass", "") ?: "",
        )
        return cfg.takeIf { it.isValid }
    }

    fun save(cfg: ProviderConfig) {
        prefs.edit()
            .putString("type", cfg.type.name)
            .putString("name", cfg.name)
            .putString("m3u", cfg.m3uUrl.trim())
            .putString("epg", cfg.epgUrl.trim())
            .putString("server", cfg.server.trim())
            .putString("user", cfg.username.trim())
            .putString("pass", cfg.password)
            .apply()
        _config.value = cfg
    }

    fun clear() {
        prefs.edit().clear().apply()
        _config.value = null
    }

    private companion object { const val FILE = "ultra_secure_store" }
}

/** Baut abspielbare URLs erst zur Laufzeit – in der Datenbank stehen keine Xtream-Passwörter. */
@Singleton
class StreamResolver @Inject constructor(private val creds: CredentialStore) {
    fun resolve(raw: String, liveFormat: String): String {
        if (!raw.startsWith(SCHEME)) return raw
        val cfg = creds.config.value ?: creds.load() ?: error("Keine Zugangsdaten gespeichert")
        val rest = raw.removePrefix(SCHEME)
        val type = rest.substringBefore('/')
        val file = rest.substringAfter('/')
        val id = file.substringBeforeLast('.')
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
