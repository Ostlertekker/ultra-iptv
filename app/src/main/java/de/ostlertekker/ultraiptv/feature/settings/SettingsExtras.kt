package de.ostlertekker.ultraiptv.feature.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ostlertekker.ultraiptv.core.backup.BackupManager
import de.ostlertekker.ultraiptv.core.data.AppSettings
import de.ostlertekker.ultraiptv.core.data.Keys
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.data.SyncRepository
import de.ostlertekker.ultraiptv.core.database.ChannelEntity
import de.ostlertekker.ultraiptv.core.database.ContentDao
import de.ostlertekker.ultraiptv.core.database.ExtrasDao
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.diagnostics.NetworkDiagnostics
import de.ostlertekker.ultraiptv.core.diagnostics.SpeedTestState
import de.ostlertekker.ultraiptv.core.lan.LanHub
import de.ostlertekker.ultraiptv.core.model.Accuracy
import de.ostlertekker.ultraiptv.core.model.InfoEntry
import de.ostlertekker.ultraiptv.core.notify.NotificationCenter
import de.ostlertekker.ultraiptv.core.security.CredentialStore
import de.ostlertekker.ultraiptv.core.security.StreamResolver
import de.ostlertekker.ultraiptv.core.ui.ActionRow
import de.ostlertekker.ultraiptv.core.ui.ChoiceRow
import de.ostlertekker.ultraiptv.core.ui.InfoRow
import de.ostlertekker.ultraiptv.core.ui.SectionHeader
import de.ostlertekker.ultraiptv.core.ui.ToggleRow
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.TvTextField
import de.ostlertekker.ultraiptv.core.ui.UltraLoader
import de.ostlertekker.ultraiptv.core.update.UpdateManager
import de.ostlertekker.ultraiptv.core.update.UpdateState
import de.ostlertekker.ultraiptv.ui.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class SettingsExtrasViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val updates: UpdateManager,
    val notifications: NotificationCenter,
    val net: NetworkDiagnostics,
    val backup: BackupManager,
    val lan: LanHub,
    val creds: CredentialStore,
    val sync: SyncRepository,
    private val extras: ExtrasDao,
    private val content: ContentDao,
    private val settings: SettingsRepository,
    private val resolver: StreamResolver,
) : ViewModel() {
    val connection = MutableStateFlow<List<InfoEntry>?>(null)
    val connectionRunning = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val backups = MutableStateFlow(backup.listBackups())
    val quality = extras.recentQuality().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val health = extras.recentHealth().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val notificationList = notifications.notifications.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val reminders = notifications.upcomingReminders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val channelNames = MutableStateFlow<Map<String, String>>(emptyMap())

    fun loadNames(ids: List<String>) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        channelNames.value = content.channels(ids.take(400)).associate { it.id to it.name }
    }

    fun checkUpdate() = viewModelScope.launch { updates.check(); settings.set(Keys.LAST_UPDATE_CHECK, System.currentTimeMillis()) }
    fun downloadUpdate(a: UpdateState.Available) = viewModelScope.launch { updates.download(a) }

    fun runConnection() = viewModelScope.launch {
        connectionRunning.value = true
        connection.value = net.connectionReport()
        connectionRunning.value = false
    }

    fun runSpeedTest(includeProvider: Boolean) = viewModelScope.launch {
        var url: String? = null
        if (includeProvider) {
            val first: ChannelEntity? = content.channelsAllOnce(false, 1).firstOrNull()
            url = first?.let { runCatching { resolver.resolve(it.streamUrl, settings.current().liveFormat) }.getOrNull() }
        }
        net.runSpeedTest(url)
    }

    fun exportBackup(withAccounts: Boolean) = viewModelScope.launch {
        message.value = runCatching { "Gespeichert: " + backup.exportToFile(withAccounts).absolutePath }.getOrElse { "Fehler: ${it.message}" }
        backups.value = backup.listBackups()
    }

    fun restoreBackup(file: java.io.File) = viewModelScope.launch {
        message.value = runCatching {
            val r = backup.restoreFile(file)
            "Wiederhergestellt: ${r.profiles} Profile, ${r.favorites} Favoriten, ${r.progress} Verlaufseinträge, ${r.accounts} Konten"
        }.getOrElse { "Fehler: ${it.message}" }
    }

    fun syncFrom(host: String, code: String, withAccounts: Boolean) = viewModelScope.launch {
        val (h, port) = parseHost(host)
        message.value = "Verbinde mit $h …"
        message.value = runCatching {
            val r = lan.syncFrom(LanHub.Remote(h, port, code.trim()), withAccounts)
            "Übernommen: ${r.profiles} Profile, ${r.favorites} Favoriten, ${r.progress} Verlaufseinträge, ${r.accounts} Konten"
        }.getOrElse { "Fehler: ${it.message}" }
    }

    private fun parseHost(input: String): Pair<String, Int> {
        val t = input.trim().removePrefix("http://")
        val h = t.substringBefore(':')
        val port = t.substringAfter(':', "8765").toIntOrNull() ?: 8765
        return h to port
    }

    fun switchAccount(id: String) = viewModelScope.launch {
        if (creds.setActive(id)) sync.syncAll(includeEpg = true)
    }

    fun removeAccount(id: String) { creds.remove(id) }

    fun setAutoUpdate(hours: Int) = viewModelScope.launch {
        settings.set(Keys.AUTO_UPDATE_HOURS, hours)
        NotificationCenter.scheduleAutoUpdate(context, hours)
    }

    fun markRead() = viewModelScope.launch { notifications.markAllRead() }
    fun clearNotifications() = viewModelScope.launch { notifications.clearAll() }
    suspend fun firstHealthIds() = health.first().map { it.channelId }
}

private fun date(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

// ------------------------------------------------------------------ Konten

fun LazyListScope.accountsSection(x: SettingsExtrasViewModel, nav: NavController) {
    item {
        val p = LocalUltraTheme.current.palette
        val accounts by x.creds.accounts.collectAsStateWithLifecycle()
        val active by x.creds.activeId.collectAsStateWithLifecycle()
        val info by x.sync.accountInfo.collectAsStateWithLifecycle()
        val syncState by x.sync.state.collectAsStateWithLifecycle()
        Column {
            Text("Beliebig viele Anbieter-Konten speichern und umschalten – unabhängig davon, wo du gerade bist. " +
                "Pro Profil lässt sich ein eigenes Konto festlegen (Einstellungen › Profile). Verbindungslimits deines Anbieters gelten weiterhin.",
                color = p.textDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            accounts.forEach { a ->
                val isActive = a.id == active
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(if (isActive) p.surfaceFocused else p.surface, RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text((if (isActive) "● " else "") + a.label, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(a.config.type.name + if (isActive) " · aktiv" else "", color = p.textDim, fontSize = 12.sp)
                    }
                    if (!isActive) TvButton("Aktivieren", onClick = { x.switchAccount(a.id) }, primary = true)
                    else TvButton("Bearbeiten", onClick = { nav.navigate(Routes.SETUP) })
                    TvButton("Entfernen", onClick = { x.removeAccount(a.id) })
                }
            }
            if (syncState.running) UltraLoader(label = syncState.message)
            TvButton("✚ Konto hinzufügen", onClick = { nav.navigate(Routes.SETUP_NEW) }, primary = true, modifier = Modifier.padding(top = 8.dp))
            val i = info
            if (i != null) {
                SectionHeader("Status des aktiven Kontos")
                InfoRow("Status", i.status ?: "unbekannt", Accuracy.MEASURED)
                InfoRow("Läuft ab", i.expiresAt?.let { DateFormat.getDateInstance().format(Date(it)) } ?: "unbegrenzt / nicht angegeben",
                    if (i.expiresAt != null) Accuracy.MEASURED else Accuracy.UNAVAILABLE)
                InfoRow("Verbindungen aktiv / max.", "${i.activeConnections ?: "?"} / ${i.maxConnections ?: "?"}", Accuracy.MEASURED)
                if (i.isTrial) InfoRow("Testzugang", "Ja", Accuracy.MEASURED)
                InfoRow("Zuletzt geprüft", date(i.checkedAt), Accuracy.MEASURED)
            }
        }
    }
}

// ------------------------------------------------------------------ Updates

fun LazyListScope.updatesSection(x: SettingsExtrasViewModel, s: AppSettings, vm: SettingsViewModel) {
    item {
        val p = LocalUltraTheme.current.palette
        val st by x.updates.state.collectAsStateWithLifecycle()
        Column {
            InfoRow("Installierte Version", x.updates.currentVersion, Accuracy.MEASURED)
            when (val u = st) {
                UpdateState.Idle -> ActionRow("Nach Updates suchen", { x.checkUpdate() })
                UpdateState.Checking -> UltraLoader(label = "Suche nach Updates …")
                is UpdateState.UpToDate -> {
                    Text("✓ Du hast die neueste Version (${u.version}).", color = p.primary, fontSize = 15.sp, modifier = Modifier.padding(8.dp))
                    ActionRow("Erneut prüfen", { x.checkUpdate() })
                }
                is UpdateState.Available -> {
                    Text("Neue Version ${u.version} verfügbar (${u.sizeBytes / 1_000_000} MB)", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp))
                    if (u.notes.isNotBlank()) Text(u.notes.take(600), color = p.textDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    TvButton("Jetzt herunterladen", onClick = { x.downloadUpdate(u) }, primary = true, modifier = Modifier.padding(8.dp))
                }
                is UpdateState.Downloading -> UltraLoader(label = "Lade ${u.version} … ${(u.progress * 100).toInt()} %")
                is UpdateState.ReadyToInstall -> {
                    Text("Version ${u.version} ist bereit.", color = p.text, fontSize = 16.sp, modifier = Modifier.padding(8.dp))
                    if (!x.updates.canInstall()) {
                        Text("ULTRA IPTV braucht einmalig die Erlaubnis, Apps zu installieren.", color = p.textDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                        TvButton("Erlaubnis erteilen", onClick = { x.updates.openInstallPermissionSettings() }, modifier = Modifier.padding(8.dp))
                    }
                    TvButton("Jetzt installieren", onClick = { x.updates.install(u.file) }, primary = true, modifier = Modifier.padding(8.dp))
                    Text("Deine Einstellungen, Profile und Favoriten bleiben beim Update erhalten.", color = p.textDim, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                }
                is UpdateState.Error -> {
                    Text("⚠ ${u.message}", color = p.text, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                    ActionRow("Erneut versuchen", { x.checkUpdate() })
                }
            }
            ToggleRow("Automatisch nach Updates suchen", s.updateCheck, { vm.set(Keys.UPDATE_CHECK, it) }, subtitle = "Einmal täglich beim Start, mit Benachrichtigung")
            Text("Quelle: GitHub-Releases (${de.ostlertekker.ultraiptv.BuildConfig.UPDATE_REPO}). Updates sind mit demselben Schlüssel signiert.",
                color = p.textDim, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

// ------------------------------------------------------------------ Benachrichtigungen

fun LazyListScope.notificationsSection(x: SettingsExtrasViewModel) {
    item {
        val p = LocalUltraTheme.current.palette
        val list by x.notificationList.collectAsStateWithLifecycle()
        val reminders by x.reminders.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { x.markRead() }
        Column {
            Text("Erinnerungen an Sendungen, neue Folgen deiner Lieblingsserien, ablaufendes Anbieter-Konto und App-Updates.",
                color = p.textDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            if (reminders.isNotEmpty()) {
                SectionHeader("Geplante Erinnerungen")
                reminders.forEach { r -> InfoRow(date(r.startMs), "${r.title} · ${r.channelName}", Accuracy.MEASURED) }
            }
            SectionHeader("Postfach")
            if (list.isEmpty()) Text("Keine Benachrichtigungen.", color = p.textDim, fontSize = 14.sp)
            list.forEach { n ->
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(p.surface, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Text(n.title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(n.body, color = p.textDim, fontSize = 13.sp)
                    Text(date(n.createdAt), color = p.textDim.copy(alpha = 0.7f), fontSize = 11.sp)
                }
            }
            if (list.isNotEmpty()) ActionRow("Alle löschen", { x.clearNotifications() })
        }
    }
}

// ------------------------------------------------------------------ Netzwerk & Speedtest

fun LazyListScope.networkDiagSection(x: SettingsExtrasViewModel, s: AppSettings, vm: SettingsViewModel) {
    item {
        val p = LocalUltraTheme.current.palette
        val report by x.connection.collectAsStateWithLifecycle()
        val running by x.connectionRunning.collectAsStateWithLifecycle()
        val speed by x.net.speed.collectAsStateWithLifecycle()
        Column {
            SectionHeader("Internetgeschwindigkeit")
            when (val sp = speed) {
                is SpeedTestState.Running -> UltraLoader(label = "${sp.phase} … ${(sp.progress * 100).toInt()} %")
                is SpeedTestState.Done -> {
                    val r = sp.result
                    if (r.error != null) InfoRow("Fehler", r.error, Accuracy.MEASURED)
                    InfoRow("Ping / Jitter", r.pingMs?.let { "$it ms / ${r.jitterMs ?: "?"} ms" } ?: "–", if (r.pingMs != null) Accuracy.MEASURED else Accuracy.UNAVAILABLE)
                    InfoRow("Download", r.downloadMbps?.let { "%.1f Mbit/s".format(it) } ?: "–", if (r.downloadMbps != null) Accuracy.MEASURED else Accuracy.UNAVAILABLE)
                    InfoRow("Upload", r.uploadMbps?.let { "%.1f Mbit/s".format(it) } ?: "–", if (r.uploadMbps != null) Accuracy.MEASURED else Accuracy.UNAVAILABLE)
                    if (r.providerMbps != null) InfoRow("Durchsatz eines Anbieter-Streams", "%.1f Mbit/s (Stream-Bitrate begrenzt den Wert)".format(r.providerMbps), Accuracy.MEASURED)
                    InfoRow("Messserver", r.server + (r.location?.let { " · Standort $it" } ?: ""), Accuracy.MEASURED)
                    Text(r.verdict, color = p.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(8.dp))
                }
                SpeedTestState.Idle -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Speedtest starten", onClick = { x.runSpeedTest(false) }, primary = true)
                TvButton("Mit Anbieter-Stream", onClick = { x.runSpeedTest(true) })
            }
            SectionHeader("Verbindungsdiagnose")
            if (running) UltraLoader(label = "Prüfe Verbindung …")
            report?.forEach { InfoRow(it.label, it.value, it.accuracy) }
            ActionRow("Verbindung prüfen", { x.runConnection() }, subtitle = "Netzwerk, DNS, Anbieter-Server, Anmeldung und Konto-Limits")
            SectionHeader("Wiedergabe-Netzwerk")
            ChoiceRow("Live-Stream-Format", if (s.liveFormat == "m3u8") "HLS (m3u8)" else "MPEG-TS (ts)", { vm.set(Keys.LIVE_FORMAT, if (s.liveFormat == "ts") "m3u8" else "ts") })
            InfoRow("Automatische Wiederholung", "Bis zu 5 Versuche mit wachsender Wartezeit", Accuracy.MEASURED)
        }
    }
}

// ------------------------------------------------------------------ Sender-Analyse

fun LazyListScope.channelAnalysisSection(x: SettingsExtrasViewModel) {
    item {
        val p = LocalUltraTheme.current.palette
        val quality by x.quality.collectAsStateWithLifecycle()
        val health by x.health.collectAsStateWithLifecycle()
        val names by x.channelNames.collectAsStateWithLifecycle()
        LaunchedEffect(quality.size, health.size) { x.loadNames((quality.map { it.channelId } + health.map { it.channelId }).distinct()) }
        Column {
            Text("Qualität aus echter Wiedergabe (gemessen).",
                color = p.textDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            SectionHeader("Sender-Qualität & Buffering-Analyse")
            if (quality.isEmpty()) Text("Noch keine Daten – einfach Sender schauen.", color = p.textDim, fontSize = 14.sp)
            quality.take(40).forEach { q ->
                val min = (q.watchMs / 60_000).coerceAtLeast(1)
                val perTen = q.rebuffers * 10.0 / min
                val verdict = when {
                    q.errors > 2 -> "instabil (Fehler)"
                    perTen >= 3 -> "häufiges Nachladen → Puffer „Groß“ oder Datensparer"
                    perTen >= 1 -> "gelegentliches Nachladen"
                    else -> "stabil"
                }
                InfoRow(names[q.channelId] ?: q.channelId,
                    "${if (q.height > 0) "${q.width}×${q.height}" else "?"} · ${if (q.fps > 0) "%.0f fps".format(q.fps) else "? fps"} · ${q.codec?.substringAfter('/') ?: "?"} · " +
                        "${q.rebuffers}× nachgeladen in $min Min. · $verdict", Accuracy.MEASURED)
            }
            SectionHeader("Stream-Gesundheit (zuletzt geprüft)")
            if (health.isEmpty()) Text("Noch keine Prüfung durchgeführt.", color = p.textDim, fontSize = 14.sp)
            health.take(60).forEach { h ->
                InfoRow(names[h.channelId] ?: h.channelId,
                    if (h.ok) "✓ online · Antwort ${h.latencyMs} ms" + (if (h.bitrateKbps > 0) " · %.1f Mbit/s".format(h.bitrateKbps / 1000.0) else "") + (h.resolution?.let { " · $it" } ?: "")
                    else "✗ ${h.error ?: "Fehler"}", Accuracy.MEASURED)
            }
        }
    }
}

// ------------------------------------------------------------------ Backup & Sync

fun LazyListScope.backupSection(x: SettingsExtrasViewModel) {
    item {
        val p = LocalUltraTheme.current.palette
        val msg by x.message.collectAsStateWithLifecycle()
        val files by x.backups.collectAsStateWithLifecycle()
        val hub by x.lan.status.collectAsStateWithLifecycle()
        var host by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        Column {
            msg?.let { Text(it, color = p.primary, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
            SectionHeader("Backup")
            ActionRow("Backup erstellen (ohne Zugangsdaten)", { x.exportBackup(false) })
            ActionRow("Backup erstellen (mit Anbieter-Konten)", { x.exportBackup(true) }, subtitle = "Enthält Passwörter – nur sicher aufbewahren")
            files.take(5).forEach { f -> ActionRow("Wiederherstellen: ${f.name}", { x.restoreBackup(f) }, subtitle = "Ersetzt Profile, Favoriten und Verlauf") }
            Text("Speicherort: ${x.backup.backupDir.absolutePath}", color = p.textDim, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
            SectionHeader("Gerätekopplung (Heimnetz)")
            ToggleRow("Dieses Gerät freigeben", hub.running, { if (it) x.lan.start(false) else x.lan.stop() },
                subtitle = "Andere ULTRA-IPTV-Geräte oder der Browser am Handy können Daten übernehmen")
            if (hub.running) {
                Text("Adresse: http://${hub.address ?: "?"}:${hub.port}    Kopplungscode: ${hub.code}", color = p.text, fontSize = 17.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                Text("Am Handy/PC im selben WLAN diese Adresse öffnen: Backup herunterladen oder hochladen.", color = p.textDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
            }
            SectionHeader("Von anderem Gerät übernehmen")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TvTextField(host, { host = it }, "IP des anderen Geräts (z. B. 192.168.1.20)", modifier = Modifier.width(320.dp))
                TvTextField(code, { code = it.filter { c -> c.isDigit() }.take(6) }, "Code", number = true, modifier = Modifier.width(140.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                TvButton("Übernehmen", onClick = { x.syncFrom(host, code, false) }, primary = true)
                TvButton("Mit Konten übernehmen", onClick = { x.syncFrom(host, code, true) })
            }
            Text("Geräteübergreifende Synchronisation läuft direkt zwischen deinen Geräten im Heimnetz – ohne Cloud-Konto.",
                color = p.textDim, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

// ------------------------------------------------------------------ Wiedergabe-Zusatzoptionen

fun LazyListScope.playbackExtras(vm: SettingsViewModel, s: AppSettings, x: SettingsExtrasViewModel) {
    item {
        val p = LocalUltraTheme.current.palette
        Column {
            SectionHeader("Automatik")
            ToggleRow("Automatische Qualitätsoptimierung", s.autoQuality, { vm.set(Keys.AUTO_QUALITY, it) },
                subtitle = "Senkt die Auflösung bei häufigem Nachladen oder Bildaussetzern")
            ToggleRow("Automatische Fehlererkennung & Ausweichsender", s.autoFailover, { vm.set(Keys.AUTO_FAILOVER, it) },
                subtitle = "Bei gestörtem Sender automatisch auf eine gleichnamige Alternative (z. B. HD ↔ FHD) wechseln")
            ToggleRow("Hintergrund-Audio", s.backgroundAudio, { vm.set(Keys.BG_AUDIO, it) },
                subtitle = "Ton läuft weiter, wenn du den Player mit der Home-Taste verlässt (sofern vom Gerät unterstützt)")
            SectionHeader("Untertitel-Stil")
            ChoiceRow("Schriftgröße", "${(s.subtitleScale * 100).toInt()} %", { vm.set(Keys.SUB_SCALE, listOf(0.75f, 1f, 1.25f, 1.5f, 2f).let { l -> l[(l.indexOf(s.subtitleScale) + 1).mod(l.size)] }) })
            ChoiceRow("Farbe", listOf("Weiß", "Gelb", "Cyan")[s.subtitleColor.coerceIn(0, 2)], { vm.set(Keys.SUB_COLOR, (s.subtitleColor + 1) % 3) })
            ChoiceRow("Hintergrund", listOf("Keiner", "Halbtransparent", "Schwarz")[s.subtitleBackground.coerceIn(0, 2)], { vm.set(Keys.SUB_BG, (s.subtitleBackground + 1) % 3) })
            SectionHeader("Playlist")
            ChoiceRow("Automatische Playlist-Aktualisierung", if (s.autoUpdateHours <= 0) "Aus" else "alle ${s.autoUpdateHours} h",
                { x.setAutoUpdate(listOf(0, 6, 12, 24, 48).let { l -> l[(l.indexOf(s.autoUpdateHours) + 1).mod(l.size)] }) },
                subtitle = "Lädt Sender, Filme, Serien und EPG im Hintergrund neu und meldet neue Folgen")
            Text("Bild-in-Bild: nutzbar auf Geräten, die es unterstützen. Sonst bietet der Player einen In-App-Mini-Player.",
                color = p.textDim, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        }
    }
}
