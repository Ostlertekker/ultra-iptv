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
import de.ostlertekker.ultraiptv.core.model.InfoEntry
import de.ostlertekker.ultraiptv.core.notify.NotificationCenter
import de.ostlertekker.ultraiptv.core.security.CredentialStore
import de.ostlertekker.ultraiptv.core.security.StreamResolver
import de.ostlertekker.ultraiptv.core.ui.ChoiceRow
import de.ostlertekker.ultraiptv.core.ui.SectionHeader
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
    val creds: CredentialStore,
    val sync: SyncRepository,
    private val extras: ExtrasDao,
    private val content: ContentDao,
    private val settings: SettingsRepository,
    private val resolver: StreamResolver,
    val system: de.ostlertekker.ultraiptv.core.sysupdate.AutoUpdateManager,
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

    fun checkUpdate() = viewModelScope.launch { updates.check(); settings.set(Keys.LAST_UPDATE_CHECK, System.currentTimeMillis()) }
    fun downloadUpdate(a: UpdateState.Available) = viewModelScope.launch { updates.download(a) }

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

    fun switchAccount(id: String) = viewModelScope.launch {
        if (creds.setActive(id)) sync.syncAll(includeEpg = true)
    }

    fun removeAccount(id: String) { creds.remove(id) }

    fun markRead() = viewModelScope.launch { notifications.markAllRead() }
    fun clearNotifications() = viewModelScope.launch { notifications.clearAll() }
}

private fun date(ms: Long) = deDateTime(ms)

// ------------------------------------------------------------------ Konten

fun LazyListScope.accountsSection(x: SettingsExtrasViewModel, nav: NavController) {
    item {
        val p = LocalUltraTheme.current.palette
        val accounts by x.creds.accounts.collectAsStateWithLifecycle()
        val active by x.creds.activeId.collectAsStateWithLifecycle()
        val info by x.sync.accountInfo.collectAsStateWithLifecycle()
        val syncState by x.sync.state.collectAsStateWithLifecycle()
        Column {
            Text("Beliebig viele Anbieter-Konten speichern und mit „Aktivieren“ umschalten. Verbindungslimits deines Anbieters gelten weiterhin.",
                color = p.textDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            accounts.forEach { a ->
                val isActive = a.id == active
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
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
                SHeader("Status des aktiven Kontos")
                SInfo("Status", i.status ?: "unbekannt")
                SInfo("Läuft ab", i.expiresAt?.let { deDate(it) } ?: "unbegrenzt / nicht angegeben")
                SInfo("Verbindungen aktiv / max.", "${i.activeConnections ?: "?"} / ${i.maxConnections ?: "?"}")
                if (i.isTrial) SInfo("Testzugang", "Ja")
                SInfo("Zuletzt geprüft", date(i.checkedAt))
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
            SInfo("Installierte Version", x.updates.currentVersion)
            when (val u = st) {
                UpdateState.Idle -> SRow("Nach Updates suchen") { x.checkUpdate() }
                UpdateState.Checking -> UltraLoader(label = "Suche nach Updates …")
                is UpdateState.UpToDate -> {
                    Text("✓ Du hast die neueste Version (${u.version}).", color = p.primary, fontSize = 15.sp, modifier = Modifier.padding(8.dp))
                    SRow("Erneut prüfen") { x.checkUpdate() }
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
                    SRow("Erneut versuchen") { x.checkUpdate() }
                }
            }
            SSwitch("Automatisch nach Updates suchen", s.updateCheck, "Beim Start und alle 5 Minuten – neue Versionen erscheinen als Einblendung oben links") { vm.set(Keys.UPDATE_CHECK, it) }
            Text("Quelle: GitHub-Releases (${de.ostlertekker.ultraiptv.BuildConfig.UPDATE_REPO}). Updates sind mit demselben Schlüssel signiert.",
                color = p.textDim, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        }
    }
    systemUpdatesSection(x.system)
}

// ------------------------------------------------------------------ Benachrichtigungen

fun LazyListScope.notificationsSection(x: SettingsExtrasViewModel) {
    item {
        val p = LocalUltraTheme.current.palette
        val list by x.notificationList.collectAsStateWithLifecycle()
        val reminders by x.reminders.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { x.markRead() }
        Column {
            Text("Erinnerungen an Sendungen erscheinen 2 Minuten vor Beginn als Einblendung oben links, App-Updates ebenfalls. " +
                "Hier siehst du deine geplanten Erinnerungen und weitere Hinweise (z. B. neue Folgen deiner Lieblingsserien oder ein ablaufendes Anbieter-Konto).",
                color = p.textDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            if (reminders.isNotEmpty()) {
                SHeader("Geplante Erinnerungen")
                reminders.forEach { r -> SInfo(date(r.startMs), "${r.title} · ${r.channelName}") }
            }
            SHeader("Weitere Hinweise")
            if (list.isEmpty()) Text("Keine Hinweise.", color = p.textDim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            list.forEach { n ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(n.title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(n.body, color = p.textDim, fontSize = 13.sp)
                    Text(date(n.createdAt), color = p.textDim.copy(alpha = 0.7f), fontSize = 11.sp)
                }
            }
            if (list.isNotEmpty()) SRow("Alle löschen") { x.clearNotifications() }
        }
    }
}

// ------------------------------------------------------------------ Netzwerk & Speedtest

// ------------------------------------------------------------------ Sender-Analyse

// ------------------------------------------------------------------ Backup & Sync

fun LazyListScope.backupSection(x: SettingsExtrasViewModel) {
    item {
        val p = LocalUltraTheme.current.palette
        val msg by x.message.collectAsStateWithLifecycle()
        val files by x.backups.collectAsStateWithLifecycle()
        Column {
            msg?.let { Text(it, color = p.primary, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
            SHeader("Backup")
            SRow("Backup erstellen (ohne Zugangsdaten)") { x.exportBackup(false) }
            SRow("Backup erstellen (mit Anbieter-Konten)", "Enthält Passwörter – nur sicher aufbewahren") { x.exportBackup(true) }
            files.take(5).forEach { f -> SRow("Wiederherstellen: ${f.name}", "Ersetzt Profile, Favoriten und Verlauf") { x.restoreBackup(f) } }
            Text("Speicherort: ${x.backup.backupDir.absolutePath}", color = p.textDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
    }
}

// ------------------------------------------------------------------ Wiedergabe-Zusatzoptionen

