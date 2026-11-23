package de.ostlertekker.ultraiptv.core.sysupdate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.ostlertekker.ultraiptv.BuildConfig
import de.ostlertekker.ultraiptv.core.data.SyncRepository
import de.ostlertekker.ultraiptv.core.data.SyncRepository.CatalogPart
import de.ostlertekker.ultraiptv.feature.web.adblock.AdblockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// =============================================================================================== Einstellungen

/** Einstellungen der System-Updates (jede Funktion einzeln schaltbar). */
@Singleton
class SystemUpdateSettings @Inject constructor(@ApplicationContext context: Context) {
    private val p = context.getSharedPreferences("system_update_settings", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)
    /** Ändert sich bei jeder Einstellungsänderung (für die Oberfläche). */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun b(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun set(k: String, v: Boolean) { p.edit().putBoolean(k, v).apply(); _version.value++ }

    var autoCheck: Boolean get() = b("auto_check", true); set(v) = set("auto_check", v)
    var autoQueue: Boolean get() = b("auto_queue", true); set(v) = set("auto_queue", v)
    var autoDownload: Boolean get() = b("auto_download", true); set(v) = set("auto_download", v)
    var autoInstall: Boolean get() = b("auto_install", false); set(v) = set("auto_install", v)
    var wifiOnly: Boolean get() = b("wifi_only", false); set(v) = set("wifi_only", v)
    var autoRetry: Boolean get() = b("auto_retry", true); set(v) = set("auto_retry", v)
    var serverUrl: String
        get() = p.getString("server", null) ?: DEFAULT_SERVER
        set(v) { p.edit().putString("server", v.trim()).apply(); _version.value++ }

    var lastCheck: Long get() = p.getLong("last_check", 0L); set(v) { p.edit().putLong("last_check", v).apply(); _version.value++ }
    var lastSuccess: Long get() = p.getLong("last_success", 0L); set(v) { p.edit().putLong("last_success", v).apply(); _version.value++ }
    var installedCount: Int get() = p.getInt("installed_count", 0); set(v) { p.edit().putInt("installed_count", v).apply(); _version.value++ }

    companion object {
        /** Standard-Update-Server: signiertes Manifest im Projekt-Repository (Zweig „system-updates“). */
        const val DEFAULT_SERVER = "https://raw.githubusercontent.com/Ostlertekker/ultra-iptv/system-updates"
    }
}

// =============================================================================================== Komponenten

/**
 * Anbieter-Kataloge (Xtream: Live / Filme / Serien je eigene Komponente; M3U: eine Playlist).
 * Enthält auch die Metadaten des Anbieters (Beschreibung, Genre, Darsteller, Regie, Jahr, Bewertung, Cover-/Logo-Adressen).
 */
class XtreamSyncManager(
    private val part: CatalogPart,
    private val sync: SyncRepository,
    private val downloader: UpdateDownloader,
    private val versions: VersionManager,
) : SystemComponent {
    override val id = part.id
    override val name = part.label
    override val group = "IPTV-Daten"
    override val heavyCheck = true
    private val dir get() = downloader.stagingFor(id)

    private fun files(): List<File> = if (part == CatalogPart.PLAYLIST) listOf(File(dir, "playlist.m3u")) else listOf(File(dir, "categories.json"), File(dir, "items.json"))

    override suspend fun check(onProgress: (Float) -> Unit): CheckResult {
        onProgress(0.1f)
        val f = sync.downloadCatalog(part, dir)
        onProgress(0.7f)
        val hash = HashVerifier.sha256(*f.toTypedArray())
        val installed = versions.installedLabel(id) ?: "Stand der letzten Synchronisation"
        if (hash == versions.installedHash(id)) { discardStaged(); return CheckResult.Current(installed) }
        val diff = sync.diffCatalog(part, f)
        onProgress(1f)
        if (diff.empty) {
            // Inhaltlich identisch (z. B. nur andere Reihenfolge): als aktuell merken, nichts installieren
            versions.markInstalled(id, hash, "Stand ${VersionManager.stamp()} · ${VersionManager.short(hash)}")
            discardStaged()
            return CheckResult.Current(versions.installedLabel(id)!!)
        }
        return CheckResult.Available(installed, "Stand ${VersionManager.stamp()} · ${VersionManager.short(hash)}", f.sumOf { it.length() },
            diff.text(), hash, if (diff.removed + diff.added > 50) UpdatePriority.HIGH else UpdatePriority.NORMAL)
    }

    override suspend fun install(item: UpdateItem, onProgress: (Float) -> Unit): String {
        val f = files()
        if (f.any { !it.exists() }) error("Bereitliegende Daten fehlen – bitte erneut nach Updates suchen")
        if (HashVerifier.sha256(*f.toTypedArray()) != item.stagedHash) error("Prüfsumme stimmt nicht – Daten verworfen")
        onProgress(0.3f)
        val d = sync.applyCatalog(part, f)
        versions.markInstalled(id, item.stagedHash!!, item.available)
        discardStaged()
        return d.text()
    }

    override fun discardStaged() { dir.listFiles()?.forEach { it.delete() } }
}

/** XMLTV-EPG: Änderungserkennung per ETag/Last-Modified und SHA-256, Übernahme atomar (bisheriges EPG bleibt bei Fehlern). */
class EpgUpdateManager(
    private val sync: SyncRepository,
    private val downloader: UpdateDownloader,
    private val versions: VersionManager,
) : SystemComponent {
    override val id = "epg"
    override val name = "TV-Programm (XMLTV): Sendungen, Zeiten, Zuordnungen"
    override val group = "EPG"
    override val heavyCheck = true
    private val file get() = File(downloader.stagingFor(id), "epg.xml")

    override suspend fun check(onProgress: (Float) -> Unit): CheckResult {
        val url = sync.epgSourceUrl() ?: return CheckResult.NotApplicable("Keine EPG-Quelle eingerichtet")
        val installed = versions.installedLabel(id) ?: "Stand der letzten Synchronisation"
        val haveInstalled = versions.installedHash(id) != null
        val r = downloader.download(url, file,
            etag = if (haveInstalled) versions.extra(id, "etag") else null,
            lastModified = if (haveInstalled) versions.extra(id, "lm") else null,
            requireHttps = false, onProgress = { onProgress(it * 0.9f) })
        if (r.notModified) return CheckResult.Current(installed)
        val f = r.file ?: return CheckResult.Current(installed)
        versions.setExtra(id, "pending_etag", r.etag); versions.setExtra(id, "pending_lm", r.lastModified)
        val hash = HashVerifier.sha256(f)
        if (hash == versions.installedHash(id)) {
            versions.setExtra(id, "etag", r.etag); versions.setExtra(id, "lm", r.lastModified)
            f.delete(); return CheckResult.Current(installed)
        }
        val programmes = countProgrammes(f)
        if (programmes == 0) { f.delete(); error("EPG-Datei enthält keine Sendungen – verworfen") }
        onProgress(1f)
        return CheckResult.Available(installed, "Stand ${VersionManager.stamp()} · ${VersionManager.short(hash)}", f.length(),
            "Neue Fassung mit $programmes Sendungen (Datei gesamt)" + if (!url.startsWith("https", true)) " · Quelle ohne HTTPS (vom Anbieter vorgegeben)" else "",
            hash, UpdatePriority.NORMAL)
    }

    /** Zählt Sendungen grob (ohne die ganze Datei zu parsen); gzip wird erkannt. */
    private fun countProgrammes(f: File): Int {
        val raw = f.inputStream().buffered()
        raw.mark(2); val gz = raw.read() == 0x1f && raw.read() == 0x8b; raw.reset()
        val input = if (gz) java.util.zip.GZIPInputStream(raw) else raw
        var n = 0
        input.bufferedReader().use { r -> r.lineSequence().forEach { l -> var i = l.indexOf("<programme"); while (i >= 0) { n++; i = l.indexOf("<programme", i + 10) } } }
        return n
    }

    override suspend fun install(item: UpdateItem, onProgress: (Float) -> Unit): String {
        val f = file
        if (!f.exists()) error("Bereitliegende EPG-Datei fehlt – bitte erneut nach Updates suchen")
        if (!HashVerifier.matches(f, item.stagedHash ?: "")) error("Prüfsumme stimmt nicht – Daten verworfen")
        onProgress(0.2f)
        val n = sync.importEpgFile(f)
        versions.markInstalled(id, item.stagedHash!!, item.available)
        versions.setExtra(id, "etag", versions.extra(id, "pending_etag")); versions.setExtra(id, "lm", versions.extra(id, "pending_lm"))
        f.delete()
        return "$n Sendungen übernommen"
    }

    override fun discardStaged() { file.delete() }
}

/** Werbeblocker-Filterlisten des integrierten Browsers (EasyList, EasyPrivacy, EasyList Germany, URLhaus). */
class AssetUpdateManager(private val adblock: AdblockManager, private val versions: VersionManager) : SystemComponent {
    override val id = "adblock-lists"
    override val name = "Werbeblocker-Filterlisten (Browser)"
    override val group = "App-Ressourcen"

    private fun installedLabel(): String {
        val newest = adblock.listInfo().maxOfOrNull { it.second } ?: 0L
        return if (newest > 0) "Stand ${VersionManager.stamp(newest)}" else "mitgelieferte Fassung"
    }

    override suspend fun check(onProgress: (Float) -> Unit): CheckResult = withContext(Dispatchers.IO) {
        val changed = adblock.stageUpdates()
        onProgress(1f)
        val staged = adblock.stagedFiles()
        if (changed.isEmpty() || staged.isEmpty()) return@withContext CheckResult.Current(installedLabel())
        val hash = HashVerifier.sha256(*staged.sortedBy { it.name }.toTypedArray())
        CheckResult.Available(installedLabel(), "Stand ${VersionManager.stamp()} · ${VersionManager.short(hash)}", staged.sumOf { it.length() },
            "Neue Fassung: " + changed.joinToString(", "), hash, UpdatePriority.LOW)
    }

    override suspend fun install(item: UpdateItem, onProgress: (Float) -> Unit): String {
        val staged = adblock.stagedFiles()
        if (staged.isEmpty()) error("Bereitliegende Listen fehlen – bitte erneut nach Updates suchen")
        if (HashVerifier.sha256(*staged.sortedBy { it.name }.toTypedArray()) != item.stagedHash) error("Prüfsumme stimmt nicht – Daten verworfen")
        val n = adblock.installStaged()
        versions.markInstalled(id, item.stagedHash!!, item.available)
        return "$n Liste(n) übernommen, Filter neu aufgebaut"
    }

    override fun discardStaged() = adblock.discardStaged()
}

/** Player-/Streaming-Konfiguration aus dem signierten Manifest (Version, SHA-256, Kompatibilität, Sicherung für Rollback). */
class RemotePlayerConfigManager(
    private val context: Context,
    private val manifest: ManifestRepository,
    private val settings: SystemUpdateSettings,
    private val downloader: UpdateDownloader,
    private val versions: VersionManager,
) : SystemComponent {
    override val id = "player-config"
    override val name = "Player- und Streaming-Konfiguration"
    override val group = "Player & Streaming"
    private val file get() = File(downloader.stagingFor(id), "player-config.json")

    override suspend fun check(onProgress: (Float) -> Unit): CheckResult {
        val m = manifest.fetch(settings.serverUrl)
        val e = m.components.firstOrNull { it.id == id } ?: return CheckResult.NotApplicable("Im Manifest nicht enthalten")
        val cur = RemotePlayerConfig.current.version
        val installed = if (cur == 0) "eingebaute Werte" else "Version $cur"
        if (e.version <= cur) return CheckResult.Current(installed)
        if (e.minAppVersion > BuildConfig.VERSION_CODE) return CheckResult.NotApplicable("Version ${e.version} benötigt eine neuere App (ab Build ${e.minAppVersion})")
        val r = downloader.download(e.url, file, maxBytes = 200_000, onProgress = onProgress)
        val f = r.file ?: error("Download fehlgeschlagen")
        if (e.size > 0 && f.length() != e.size) { f.delete(); error("Größe stimmt nicht mit dem Manifest überein") }
        if (!HashVerifier.matches(f, e.sha256)) { f.delete(); error("SHA-256 stimmt nicht mit dem Manifest überein") }
        val cfg = runCatching { RemotePlayerConfig.parse(f.readText()) }.getOrElse { f.delete(); error("Konfiguration nicht lesbar") }
        cfg.validate()?.let { f.delete(); error("Konfiguration abgelehnt: $it") }
        if (cfg.version != e.version) { f.delete(); error("Versionsangabe widersprüchlich") }
        return CheckResult.Available(installed, "Version ${e.version}", f.length(), e.changes.ifBlank { "Neue Konfiguration" }, e.sha256.lowercase(),
            when (e.priority) { "high" -> UpdatePriority.HIGH; "low" -> UpdatePriority.LOW; else -> UpdatePriority.NORMAL })
    }

    override suspend fun install(item: UpdateItem, onProgress: (Float) -> Unit): String {
        val f = file
        if (!f.exists() || !HashVerifier.matches(f, item.stagedHash ?: "")) error("Bereitliegende Datei fehlt oder ist verändert – bitte erneut suchen")
        val cfg = RemotePlayerConfig.parse(f.readText())
        cfg.validate()?.let { error("Konfiguration abgelehnt: $it") }
        val target = RemotePlayerConfig.file(context); val bak = RemotePlayerConfig.backup(context)
        target.parentFile?.mkdirs()
        // Sicherung der bisherigen Fassung (für Rollback), dann atomar ersetzen
        bak.delete(); if (target.exists()) target.copyTo(bak, overwrite = true)
        val tmp = File(target.parentFile, "player-config.json.new")
        f.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) error("Speichern fehlgeschlagen") }
        RemotePlayerConfig.load(context)
        if (RemotePlayerConfig.current.version != cfg.version) { RollbackManager.restorePlayerConfig(context); error("Konfiguration ließ sich nicht laden – zurückgesetzt") }
        versions.markInstalled(id, item.stagedHash!!, item.available)
        f.delete()
        return "Aktiv ab dem nächsten Senderstart"
    }

    override fun discardStaged() { file.delete() }
}

/** Rücksetzen auf die vorherige Fassung (Datenbank-Änderungen sind Transaktionen und brauchen keinen eigenen Rollback). */
object RollbackManager {
    fun canRollbackPlayerConfig(ctx: Context) = RemotePlayerConfig.backup(ctx).exists() || RemotePlayerConfig.file(ctx).exists()

    /** Stellt die gesicherte Konfiguration wieder her (ohne Sicherung: eingebaute Werte). */
    fun restorePlayerConfig(ctx: Context): String {
        val bak = RemotePlayerConfig.backup(ctx); val f = RemotePlayerConfig.file(ctx)
        val msg = if (bak.exists()) { f.delete(); bak.renameTo(f); "Vorherige Fassung wiederhergestellt" } else { f.delete(); "Eingebaute Werte wiederhergestellt" }
        RemotePlayerConfig.load(ctx)
        return msg
    }
}

// =============================================================================================== Steuerung

/**
 * Zentrale Steuerung: Scanner (prüft alle Komponenten), Warteschlange, Installer, automatische/manuelle Abläufe.
 * Der APK-Updater der App ist davon unabhängig und wird hier nicht berührt.
 */
@Singleton
class AutoUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val settings: SystemUpdateSettings,
    val queue: UpdateQueueManager,
    private val versions: VersionManager,
    private val sync: SyncRepository,
    private val downloader: UpdateDownloader,
    private val manifest: ManifestRepository,
    private val adblock: AdblockManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _busy = MutableStateFlow<String?>(null)
    /** Laufender Vorgang (null = untätig) – verhindert und zeigt doppelte Prozesse. */
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** Registrierte Komponenten (abhängig von der eingerichteten Quelle). */
    fun components(): List<SystemComponent> =
        sync.catalogParts().map { XtreamSyncManager(it, sync, downloader, versions) } +
            listOf(EpgUpdateManager(sync, downloader, versions), RemotePlayerConfigManager(context, manifest, settings, downloader, versions),
                AssetUpdateManager(adblock, versions))

    private fun note(s: String) {
        val t = java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(java.util.Date())
        _log.value = (_log.value + "$t  $s").takeLast(30)
        android.util.Log.i("UltraSysUpdate", s)
    }

    /** Darf jetzt automatisch geladen werden (nur-WLAN-Einstellung; LAN zählt wie WLAN)? */
    fun networkAllowed(): Boolean {
        if (!settings.wifiOnly) return true
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val c = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // ------------------------------------------------------------------- UpdateScanner

    /** Prüft alle Komponenten. manual = vom Nutzer ausgelöst (dann immer vollständig, unabhängig von Automatik-Schaltern). */
    suspend fun scan(manual: Boolean): Int = mutex.withLock {
        _busy.value = "Suche nach Updates …"
        var found = 0
        try {
            val comps = components()
            // Nicht mehr vorhandene Komponenten (z. B. Quelle gewechselt) aus der Warteschlange nehmen
            queue.items.value.filter { q -> comps.none { it.id == q.id } }.forEach { q -> queue.put(q.copy(status = UpdateStatus.CURRENT, changes = "Für diese Quelle nicht zutreffend", stagedHash = null)) }
            for (c in comps) {
                // Automatisch: datenintensive Prüfungen höchstens alle 6 Stunden (spart Datenvolumen bei häufigen App-Starts)
                val lastChecked = queue.get(c.id)?.checkedAt ?: 0L
                if (!manual && c.heavyCheck && System.currentTimeMillis() - lastChecked < 6 * 3_600_000L && queue.get(c.id)?.status != UpdateStatus.FAILED) continue
                if (!manual && c.heavyCheck && (!settings.autoDownload || !networkAllowed())) {
                    note("${c.name}: automatische Prüfung übersprungen (" + (if (!settings.autoDownload) "automatisches Herunterladen aus" else "kein WLAN") + ")")
                    continue
                }
                if (checkOne(c, manual)) found++
            }
            settings.lastCheck = System.currentTimeMillis()
        } finally { _busy.value = null }
        found
    }

    private suspend fun checkOne(c: SystemComponent, manual: Boolean): Boolean {
        val prev = queue.get(c.id)
        var attempt = 0
        while (true) {
            queue.put(base(c, prev).copy(status = UpdateStatus.DOWNLOADING, progress = 0f, error = null))
            try {
                val r = c.check { p -> queue.patch(c.id) { it.copy(progress = p) } }
                val now = System.currentTimeMillis()
                when (r) {
                    is CheckResult.Current -> { queue.put(base(c, prev).copy(installed = r.installed, available = r.installed, sizeBytes = 0, changes = "",
                        status = UpdateStatus.CURRENT, checkedAt = now, stagedHash = null)); return false }
                    is CheckResult.NotApplicable -> { queue.put(base(c, prev).copy(installed = "–", available = "–", changes = r.reason,
                        status = UpdateStatus.CURRENT, checkedAt = now, stagedHash = null)); return false }
                    is CheckResult.Available -> {
                        // Gleiche Fassung schon in der Warteschlange → kein Doppel-Eintrag, nur Prüfzeit aktualisieren
                        val st = if (prev?.stagedHash == r.hash && prev.status == UpdateStatus.WAITING) UpdateStatus.WAITING
                            else if (settings.autoQueue || manual) UpdateStatus.WAITING else UpdateStatus.AVAILABLE
                        queue.put(base(c, prev).copy(installed = r.installed, available = r.available, sizeBytes = r.sizeBytes, changes = r.changes,
                            priority = r.priority, status = st, progress = 0f, checkedAt = now, stagedHash = r.hash, attempts = 0))
                        if (prev?.stagedHash != r.hash) note("${c.name}: Update gefunden (${r.changes})")
                        return true
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                val msg = de.ostlertekker.ultraiptv.core.network.SafeLog.redact(e.message ?: e.javaClass.simpleName)
                attempt++
                if (settings.autoRetry && attempt < 3) { note("${c.name}: Prüfung fehlgeschlagen ($msg) – neuer Versuch"); delay(4_000L * attempt); continue }
                c.discardStaged()
                queue.put(base(c, prev).copy(status = UpdateStatus.FAILED, error = "Prüfung: $msg", checkedAt = System.currentTimeMillis(), stagedHash = null, attempts = attempt))
                note("${c.name}: Prüfung fehlgeschlagen – $msg")
                return false
            }
        }
    }

    private fun base(c: SystemComponent, prev: UpdateItem?) = prev?.copy(name = c.name, group = c.group) ?: UpdateItem(
        id = c.id, name = c.name, group = c.group, installed = versions.installedLabel(c.id) ?: "–", available = "–", sizeBytes = 0,
        changes = "", status = UpdateStatus.CURRENT, lastUpdated = versions.installedAt(c.id))

    // ------------------------------------------------------------------- UpdateInstaller

    /** Installiert einen Eintrag (Prüfsumme wird erneut geprüft; Fehler lassen die bisherigen Daten unverändert). */
    suspend fun install(id: String): Boolean = mutex.withLock { installLocked(id) }

    private suspend fun installLocked(id: String): Boolean {
        val item = queue.get(id) ?: return false
        if (item.stagedHash == null) return false
        val c = components().firstOrNull { it.id == id } ?: return false
        _busy.value = "Installiere ${c.name} …"
        queue.patch(id) { it.copy(status = UpdateStatus.INSTALLING, progress = 0f, error = null) }
        return try {
            val res = c.install(item) { p -> queue.patch(id) { it.copy(progress = p) } }
            val now = System.currentTimeMillis()
            queue.patch(id) { it.copy(status = UpdateStatus.DONE, installed = it.available, progress = 1f, lastUpdated = now, stagedHash = null, error = null, changes = res) }
            settings.lastSuccess = now; settings.installedCount = settings.installedCount + 1
            note("${c.name}: installiert – $res")
            true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            val msg = de.ostlertekker.ultraiptv.core.network.SafeLog.redact(e.message ?: e.javaClass.simpleName)
            val stagedGone = msg.contains("fehlt") || msg.contains("Prüfsumme")
            if (stagedGone) c.discardStaged()
            queue.patch(id) { it.copy(status = UpdateStatus.FAILED, error = "Installation: $msg", attempts = it.attempts + 1, stagedHash = if (stagedGone) null else it.stagedHash) }
            note("${c.name}: Installation fehlgeschlagen – $msg (bisherige Daten unverändert)")
            false
        } finally { _busy.value = null }
    }

    /** Alle wartenden Updates installieren (wichtige zuerst). */
    suspend fun installAll(): Int = mutex.withLock {
        var n = 0
        for (it in queue.pending().sortedByDescending { it.priority.ordinal }) if (installLocked(it.id)) n++
        n
    }

    fun rollbackPlayerConfig(): String {
        val m = RollbackManager.restorePlayerConfig(context)
        queue.patch("player-config") { it.copy(installed = if (RemotePlayerConfig.current.version == 0) "eingebaute Werte" else "Version ${RemotePlayerConfig.current.version}", status = UpdateStatus.CURRENT, changes = m) }
        note("Player-Konfiguration: $m")
        return m
    }

    // ------------------------------------------------------------------- Abläufe

    /** Automatischer Ablauf (App-Start, Hintergrund): prüfen → bei Bedarf installieren, je nach Einstellungen. */
    suspend fun runAutomatic(reason: String) {
        if (!settings.autoCheck) return
        note("Automatische Prüfung ($reason)")
        scan(manual = false)
        if (settings.autoInstall && networkAllowed()) installAll()
    }

    fun runAutomaticAsync(reason: String, delayMs: Long = 0) { scope.launch { delay(delayMs); runCatching { runAutomatic(reason) } } }
    fun scanAsync() { scope.launch { runCatching { scan(manual = true) } } }
    fun installAsync(id: String) { scope.launch { runCatching { install(id) } } }
    fun installAllAsync() { scope.launch { runCatching { installAll() } } }

    /** Regelmäßige Prüfung im Hintergrund (WorkManager, alle 12 h, nur mit Netz). */
    fun schedule() {
        val wm = WorkManager.getInstance(context)
        if (!settings.autoCheck) { wm.cancelUniqueWork(SystemUpdateWorker.NAME); return }
        val req = PeriodicWorkRequestBuilder<SystemUpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setInitialDelay(12, TimeUnit.HOURS).build()
        wm.enqueueUniquePeriodicWork(SystemUpdateWorker.NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SystemUpdateEntryPoint { fun manager(): AutoUpdateManager }

/** Hintergrundprüfung der System-Updates (eigene, eindeutige Aufgabe – keine doppelten Läufe). */
class SystemUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val m = EntryPointAccessors.fromApplication(applicationContext, SystemUpdateEntryPoint::class.java).manager()
        return runCatching { m.runAutomatic("Hintergrund") }.fold({ Result.success() }, { if (m.settings.autoRetry && runAttemptCount < 3) Result.retry() else Result.failure() })
    }

    companion object { const val NAME = "ultra_system_updates" }
}
