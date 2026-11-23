package de.ostlertekker.ultraiptv.feature.settings

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.sysupdate.AutoUpdateManager
import de.ostlertekker.ultraiptv.core.sysupdate.RollbackManager
import de.ostlertekker.ultraiptv.core.sysupdate.UpdateItem
import de.ostlertekker.ultraiptv.core.sysupdate.UpdateStatus
import de.ostlertekker.ultraiptv.core.sysupdate.VersionManager
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.TvTextField
import de.ostlertekker.ultraiptv.core.ui.UltraLoader
import de.ostlertekker.ultraiptv.core.ui.tvFocusable

/** Kategorie „System-Updates“ unter App-Updates: interne Komponenten (IPTV-Daten, EPG, Player-Konfiguration, Filterlisten). */
fun LazyListScope.systemUpdatesSection(m: AutoUpdateManager) {
    item { SHeader(tr("System-Updates")) }
    item { SystemUpdatesPanel(m) }
}

private fun size(b: Long) = when { b <= 0 -> "–"; b < 1024 * 1024 -> "${(b + 1023) / 1024} KB"; else -> "%.1f MB".format(java.util.Locale.GERMANY, b / 1_048_576.0) }

@Composable
private fun SystemUpdatesPanel(m: AutoUpdateManager) {
    val p = LocalUltraTheme.current.palette
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val items by m.queue.items.collectAsStateWithLifecycle()
    val busy by m.busy.collectAsStateWithLifecycle()
    val log by m.log.collectAsStateWithLifecycle()
    val ver by m.settings.version.collectAsStateWithLifecycle()
    val s = m.settings
    @Suppress("UNUSED_EXPRESSION") ver // Neu zeichnen nach Einstellungsänderungen
    val pending = items.filter { it.stagedHash != null && (it.status == UpdateStatus.WAITING || it.status == UpdateStatus.AVAILABLE || it.status == UpdateStatus.FAILED) }
    Column {
        Text(tr("Aktualisiert interne Daten und Einstellungen der App – nicht die App selbst (dafür ist „Nach Updates suchen“ oben zuständig)."),
            color = p.textDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        SInfo(tr("Verfügbare Updates"), "${pending.size}")
        SInfo(tr("Installierte Updates"), "${s.installedCount}")
        SInfo(tr("Letzte Prüfung"), if (s.lastCheck > 0) VersionManager.stamp(s.lastCheck) else "noch nie")
        SInfo(tr("Letzte erfolgreiche Aktualisierung"), if (s.lastSuccess > 0) VersionManager.stamp(s.lastSuccess) else "noch keine")
        busy?.let { UltraLoader(label = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
            TvButton(tr("Jetzt nach Updates suchen"), onClick = { if (busy == null) m.scanAsync() }, primary = true, enabled = busy == null)
            TvButton(trf("Alle Updates installieren ({0})", pending.size), onClick = { if (busy == null) m.installAllAsync() }, enabled = busy == null && pending.isNotEmpty())
        }

        // Warteschlange / Komponenten
        if (items.isEmpty()) Text(tr("Noch nicht geprüft – „Jetzt nach Updates suchen“ prüft alle Komponenten."), color = p.textDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        items.sortedWith(compareBy<UpdateItem> { it.group }.thenBy { it.name }).forEach { it2 -> ItemRow(m, it2, busy == null) { m.rollbackPlayerConfig() } }

        SHeader(tr("Automatik"))
        SSwitch(tr("Beim App-Start automatisch prüfen"), s.autoCheck, tr("Auch regelmäßig im Hintergrund (alle 12 Stunden)")) { s.autoCheck = it; m.schedule() }
        SSwitch(tr("Neue Updates automatisch in die Warteschlange"), s.autoQueue, tr("Aus: Funde werden nur angezeigt („Update verfügbar“)")) { s.autoQueue = it }
        SSwitch(tr("Automatisch herunterladen"), s.autoDownload, tr("Für IPTV-Daten und EPG ist das Herunterladen die Prüfung selbst – der Anbieter liefert keine Versionsnummern")) { s.autoDownload = it }
        SSwitch(tr("Automatisch installieren"), s.autoInstall, tr("Aus: Updates warten, bis du sie hier installierst")) { s.autoInstall = it }
        SSwitch("Nur über WLAN/LAN aktualisieren", s.wifiOnly, "Gilt für automatische Abläufe; manuelle Suche geht immer") { s.wifiOnly = it; m.schedule() }
        SSwitch(tr("Fehlgeschlagene Downloads automatisch wiederholen"), s.autoRetry, tr("Bis zu 3 Versuche")) { s.autoRetry = it }
        var server by remember { mutableStateOf(s.serverUrl) }
        TvTextField(server, { server = it }, tr("Update-Server (HTTPS, signiertes Manifest)"), modifier = Modifier.fillMaxWidth().padding(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
            TvButton(tr("Server speichern"), onClick = { if (server.startsWith("https://", true)) s.serverUrl = server })
            TvButton(tr("Standard-Server"), onClick = { server = de.ostlertekker.ultraiptv.core.sysupdate.SystemUpdateSettings.DEFAULT_SERVER; s.serverUrl = server })
        }
        Text(tr("Nur per App-Update änderbar (nicht dynamisch): Übersetzungen, Icons, Länderflaggen, Genre-Symbole, Player-Bibliotheken und Decoder. ") +
            tr("Senderlogos, Cover, Hintergründe und Metadaten (Beschreibung, Darsteller, Regie, Jahr, Laufzeit, Genre, Bewertung) kommen mit den IPTV-Daten des Anbieters."),
            color = p.textDim, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        if (log.isNotEmpty()) {
            Text(tr("Protokoll"), color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, top = 6.dp))
            Text(log.takeLast(10).reversed().joinToString("\n"), color = p.textDim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

@Composable
private fun ItemRow(m: AutoUpdateManager, it: UpdateItem, idle: Boolean, rollback: () -> Unit) {
    val p = LocalUltraTheme.current.palette
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val col = when (it.status) {
        UpdateStatus.CURRENT, UpdateStatus.DONE -> Color(0xFF4ADE80)
        UpdateStatus.FAILED -> Color(0xFFFF6B6B)
        UpdateStatus.AVAILABLE, UpdateStatus.WAITING -> Color(0xFFFFC857)
        else -> p.primary
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
        .background(p.surface.copy(alpha = 0.5f)).tvFocusable(RoundedCornerShape(12.dp), {}, scaleOnFocus = false).padding(12.dp)) {
        Row {
            Text(it.name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(it.status.label, color = col, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(trf("{0} · Installiert: {1} · Verfügbar: {2} · Größe: {3} · Priorität: {4}", it.group, it.installed, it.available, (size(it.sizeBytes)), it.priority.label) +
            (if (it.lastUpdated > 0) trf(" · zuletzt aktualisiert {0}", (VersionManager.stamp(it.lastUpdated))) else ""), color = p.textDim, fontSize = 12.sp)
        if (it.changes.isNotBlank()) Text(it.changes, color = p.text, fontSize = 12.sp)
        it.error?.let { e -> Text("⚠ $e", color = Color(0xFFFF8A80), fontSize = 12.sp) }
        if (it.status == UpdateStatus.DOWNLOADING || it.status == UpdateStatus.INSTALLING) {
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.surface)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(it.progress.coerceIn(0.03f, 1f)).background(p.primary))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (it.stagedHash != null && it.status in listOf(UpdateStatus.WAITING, UpdateStatus.AVAILABLE, UpdateStatus.FAILED))
                TvButton(tr("Installieren"), onClick = { if (idle) m.installAsync(it.id) }, enabled = idle)
            if (it.id == "player-config" && RollbackManager.canRollbackPlayerConfig(ctx))
                TvButton(tr("Vorherige Fassung wiederherstellen"), onClick = { if (idle) rollback() }, enabled = idle)
        }
    }
}
