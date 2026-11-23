package de.ostlertekker.ultraiptv.core.sysupdate

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton

// =============================================================================================== Modelle

/** Status eines Eintrags der Update-Warteschlange. */
enum class UpdateStatus(val label: String) {
    CURRENT("Aktuell"),
    AVAILABLE(tr("Update verfügbar")),
    WAITING(tr("Wartet auf Installation")),
    DOWNLOADING(tr("Wird heruntergeladen")),
    INSTALLING(tr("Wird installiert")),
    DONE(tr("Erfolgreich aktualisiert")),
    FAILED("Fehlgeschlagen"),
}

enum class UpdatePriority(val label: String) { LOW("niedrig"), NORMAL("normal"), HIGH("hoch") }

/**
 * Ein Eintrag der Update-Warteschlange (eine Komponente).
 * Versionen sind echte Angaben: Manifest-Version, bzw. bei Anbieterdaten ohne Versionsnummer Stand + Prüfsumme.
 */
@Serializable
data class UpdateItem(
    val id: String,
    val name: String,
    val group: String,
    val installed: String,
    val available: String,
    val sizeBytes: Long,
    val changes: String,
    val priority: UpdatePriority = UpdatePriority.NORMAL,
    val status: UpdateStatus,
    val progress: Float = 0f,
    val error: String? = null,
    val lastUpdated: Long = 0L,
    val checkedAt: Long = 0L,
    /** Prüfsumme der bereitliegenden (geprüften) Daten – Grundlage, um Doppel-Updates zu erkennen. */
    val stagedHash: String? = null,
    val attempts: Int = 0,
)

/** Ergebnis einer Komponenten-Prüfung. */
sealed class CheckResult {
    /** Lokale Fassung ist aktuell. */
    data class Current(val installed: String) : CheckResult()
    /** Neue Fassung liegt geprüft bereit (Datei(en) im Zwischenspeicher). */
    data class Available(val installed: String, val available: String, val sizeBytes: Long, val changes: String,
                         val hash: String, val priority: UpdatePriority = UpdatePriority.NORMAL) : CheckResult()
    /** Komponente gibt es in dieser Konfiguration nicht (z. B. keine Quelle eingerichtet). */
    data class NotApplicable(val reason: String) : CheckResult()
}

/** Eine überwachte Komponente. Prüfen = Änderung erkennen und neue Fassung geprüft zwischenspeichern; Installieren = übernehmen. */
interface SystemComponent {
    val id: String
    val name: String
    val group: String
    /** Kann die Prüfung selbst große Datenmengen laden (z. B. Anbieter ohne Versionsangabe)? → nur WLAN-Einstellung beachten. */
    val heavyCheck: Boolean get() = false
    suspend fun check(onProgress: (Float) -> Unit): CheckResult
    /** Übernimmt die bereitliegende Fassung. Muss atomar sein: bei Fehlern bleiben die bisherigen Daten unverändert. */
    suspend fun install(item: UpdateItem, onProgress: (Float) -> Unit): String
    /** Verwirft bereitliegende Dateien. */
    fun discardStaged() {}
}

// =============================================================================================== Prüfung

/** SHA-256 über Dateien (Prüfsumme in Kleinbuchstaben-Hex). */
object HashVerifier {
    fun sha256(vararg files: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        files.forEach { f -> f.inputStream().use { i -> while (true) { val n = i.read(buf); if (n <= 0) break; md.update(buf, 0, n) } } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun matches(file: File, expected: String) = sha256(file).equals(expected.trim(), ignoreCase = true)
}

/**
 * Prüft die digitale Signatur des Update-Manifests (ECDSA P-256 / SHA-256).
 * Der öffentliche Schlüssel ist fest in der App; der private Schlüssel liegt nur beim Herausgeber (GitHub-Secret).
 */
object SignatureVerifier {
    private const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE2ei8JPwGG3gIqAd/m2c3TM8SlgXPvHW07AqPBK/H6Wxpi73uCBNXaOeKDtNxAnlkhZSTA+djM+gcgq/7S/Ov2Q=="

    fun verify(data: ByteArray, signatureB64: String): Boolean = verifyWith(data, signatureB64) { Base64.decode(it, Base64.DEFAULT) }

    /** Kern der Prüfung (Base64-Dekoder austauschbar, damit sie auch im JVM-Test läuft). */
    internal fun verifyWith(data: ByteArray, signatureB64: String, b64: (String) -> ByteArray): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b64(PUBLIC_KEY)))
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(data); verify(b64(signatureB64.trim())) }
    }.getOrDefault(false)
}

/** Lädt Dateien nur über HTTPS in den Zwischenspeicher (.part → fertig), mit Fortschritt und optionaler Größengrenze. */
@Singleton
class UpdateDownloader @Inject constructor(@ApplicationContext private val context: Context, private val http: OkHttpClient) {
    val stageDir: File get() = File(context.cacheDir, "system-updates").apply { mkdirs() }

    fun stagingFor(componentId: String): File = File(stageDir, componentId).apply { mkdirs() }

    data class Result(val file: File?, val notModified: Boolean, val etag: String?, val lastModified: String?)

    suspend fun download(url: String, dest: File, etag: String? = null, lastModified: String? = null, maxBytes: Long = 2_000_000_000L,
                         requireHttps: Boolean = true, onProgress: (Float) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        if (requireHttps && !url.startsWith("https://", true)) error(tr("Nur HTTPS erlaubt"))
        val req = Request.Builder().url(url).apply {
            etag?.let { header("If-None-Match", it) }
            lastModified?.let { header("If-Modified-Since", it) }
        }.build()
        http.newCall(req).execute().use { r ->
            if (r.code == 304) return@withContext Result(null, true, etag, lastModified)
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body ?: error(tr("Leere Antwort"))
            val total = body.contentLength()
            val part = File(dest.parentFile, dest.name + ".part")
            var done = 0L
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n <= 0) break
                        out.write(buf, 0, n); done += n
                        if (done > maxBytes) error(tr("Datei zu groß"))
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (done == 0L) { part.delete(); error(tr("Leere Datei")) }
            dest.delete()
            if (!part.renameTo(dest)) error(tr("Zwischenspeichern fehlgeschlagen"))
            Result(dest, false, r.header("ETag"), r.header("Last-Modified"))
        }
    }
}

// =============================================================================================== Versionen

/** Merkt sich pro Komponente die installierte Fassung (Version/Prüfsumme/Zeitpunkt) und Zusatzangaben (ETag usw.). */
@Singleton
class VersionManager @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("system_update_versions", Context.MODE_PRIVATE)

    fun installedHash(id: String): String? = prefs.getString("$id.hash", null)
    fun installedLabel(id: String): String? = prefs.getString("$id.label", null)
    fun installedAt(id: String): Long = prefs.getLong("$id.at", 0L)
    fun extra(id: String, key: String): String? = prefs.getString("$id.x.$key", null)
    fun setExtra(id: String, key: String, value: String?) = prefs.edit().apply { if (value == null) remove("$id.x.$key") else putString("$id.x.$key", value) }.apply()

    fun markInstalled(id: String, hash: String, label: String) {
        prefs.edit().putString("$id.hash", hash).putString("$id.label", label).putLong("$id.at", System.currentTimeMillis()).apply()
    }

    companion object {
        fun stamp(ms: Long = System.currentTimeMillis()): String =
            java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.GERMANY).format(java.util.Date(ms))
        fun short(hash: String) = hash.take(8)
    }
}

// =============================================================================================== Warteschlange

/** Zentrale Update-Warteschlange (eine Zeile pro Komponente, gespeichert über App-Neustarts). */
@Singleton
class UpdateQueueManager @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("system_update_queue", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<UpdateItem>> = _items.asStateFlow()

    private fun load(): List<UpdateItem> = runCatching {
        json.decodeFromString<List<UpdateItem>>(prefs.getString("items", "[]") ?: "[]")
            // Nach einem Abbruch (App beendet) laufende Zustände nicht stehen lassen
            .map { if (it.status == UpdateStatus.DOWNLOADING || it.status == UpdateStatus.INSTALLING) it.copy(status = UpdateStatus.FAILED, error = "Unterbrochen") else it }
    }.getOrDefault(emptyList())

    private fun save() { prefs.edit().putString("items", json.encodeToString(_items.value)).apply() }

    fun get(id: String) = _items.value.firstOrNull { it.id == id }

    /** Fügt hinzu oder ersetzt (eine Zeile pro Komponente → keine Doppel-Updates). */
    fun put(item: UpdateItem) {
        _items.update { l -> (l.filter { it.id != item.id } + item) }
        save()
    }

    fun patch(id: String, block: (UpdateItem) -> UpdateItem) {
        _items.update { l -> l.map { if (it.id == id) block(it) else it } }
        save()
    }

    fun pending(): List<UpdateItem> = _items.value.filter { it.status == UpdateStatus.AVAILABLE || it.status == UpdateStatus.WAITING || it.status == UpdateStatus.FAILED && it.stagedHash != null }
}

// =============================================================================================== Manifest

/** Manifest-Format (system-manifest.json): Komponenten mit Version, Adresse, Größe, SHA-256 und Änderungstext. */
@Serializable
data class SystemManifest(val schema: Int = 1, val generated: String = "", val components: List<ManifestEntry> = emptyList())

@Serializable
data class ManifestEntry(
    val id: String,
    val name: String = id,
    val version: Int,
    val url: String,
    val sha256: String,
    val size: Long = 0,
    val changes: String = "",
    val priority: String = "normal",
    /** Kleinste App-Version (versionCode), die diese Fassung versteht. */
    val minAppVersion: Int = 0,
)

/**
 * Lädt das zentrale Manifest vom eingestellten Update-Server und prüft die Signatur (manifest + .sig).
 * Ohne gültige Signatur wird nichts aus dem Manifest verwendet.
 */
@Singleton
class ManifestRepository @Inject constructor(private val downloader: UpdateDownloader) {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile var last: SystemManifest? = null
        private set

    suspend fun fetch(baseUrl: String): SystemManifest {
        val base = baseUrl.trim().trimEnd('/')
        if (!base.startsWith("https://", true)) error(tr("Update-Server muss HTTPS verwenden"))
        val dir = downloader.stagingFor("_manifest")
        val m = downloader.download("$base/system-manifest.json", File(dir, "system-manifest.json"), maxBytes = 1_000_000).file ?: error(tr("Manifest fehlt"))
        val s = downloader.download("$base/system-manifest.json.sig", File(dir, "system-manifest.json.sig"), maxBytes = 10_000).file ?: error(tr("Signatur fehlt"))
        val bytes = m.readBytes()
        if (!SignatureVerifier.verify(bytes, s.readText())) error(tr("Signatur des Manifests ungültig – Update verworfen"))
        return json.decodeFromString<SystemManifest>(bytes.decodeToString()).also { last = it }
    }
}
