package de.ostlertekker.ultraiptv.core.notify

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Programm-Erinnerung: 2 Minuten vor Beginn oben links, 30 Sekunden lang, mit Knopf zum Sender. */
@Composable
fun ReminderPopup(r: de.ostlertekker.ultraiptv.core.database.ReminderEntity?, modifier: Modifier, onDismiss: () -> Unit, onOpen: (de.ostlertekker.ultraiptv.core.database.ReminderEntity) -> Unit) {
    val rem = r ?: return
    val pal = de.ostlertekker.ultraiptv.core.design.LocalUltraTheme.current.palette
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    var left by androidx.compose.runtime.remember(rem.id) { androidx.compose.runtime.mutableIntStateOf(30) }
    androidx.compose.runtime.LaunchedEffect(rem.id) {
        // Veraltet (App war im Hintergrund und die Sendung läuft schon länger) → nicht mehr anzeigen
        if (System.currentTimeMillis() > rem.startMs + 5 * 60_000L) { onDismiss(); return@LaunchedEffect }
        kotlinx.coroutines.delay(200); runCatching { focus.requestFocus() }
        while (left > 0) { kotlinx.coroutines.delay(1000); left-- }
        onDismiss()
    }
    androidx.activity.compose.BackHandler { onDismiss() }
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMAN).format(java.util.Date(rem.startMs))
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    androidx.compose.foundation.layout.Column(modifier.width(440.dp)
        .background(pal.surface.copy(alpha = 0.97f), shape)
        .border(2.dp, pal.primary.copy(alpha = 0.7f), shape).padding(16.dp)) {
        androidx.compose.material3.Text("⏰  Erinnerung · beginnt um $time", color = pal.primary, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        androidx.compose.material3.Text(rem.title, color = pal.text, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 2,
            modifier = Modifier.padding(top = 4.dp))
        androidx.compose.material3.Text(rem.channelName, color = pal.textDim, fontSize = 14.sp, maxLines = 1)
        androidx.compose.foundation.layout.Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            de.ostlertekker.ultraiptv.core.ui.TvButton("Zum Sender", onClick = { onOpen(rem) }, primary = true, focusRequester = focus)
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
            de.ostlertekker.ultraiptv.core.ui.TvButton("Schließen", onClick = { onDismiss() })
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            androidx.compose.material3.Text("${left}s", color = pal.textDim, fontSize = 13.sp)
        }
        androidx.compose.material3.LinearProgressIndicator(progress = { left / 30f }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(3.dp),
            color = pal.primary, trackColor = pal.surfaceFocused)
    }
}

/** Neues App-Update: Einblendung oben links mit „Jetzt aktualisieren“ (lädt und installiert direkt) oder „Später“. */
@Composable
fun UpdatePopup(center: NotificationCenter, modifier: Modifier) {
    val u by center.updatePopup.collectAsStateWithLifecycle()
    val upd = u ?: return
    val st by center.updateState.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pal = de.ostlertekker.ultraiptv.core.design.LocalUltraTheme.current.palette
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(upd.version) { kotlinx.coroutines.delay(300); runCatching { focus.requestFocus() } }
    // Fertig geladen → Installation direkt starten (Erlaubnis vorausgesetzt)
    androidx.compose.runtime.LaunchedEffect(st) {
        val s = st
        if (s is de.ostlertekker.ultraiptv.core.update.UpdateState.ReadyToInstall && center.canInstallUpdate()) center.installUpdate(s.file)
    }
    androidx.activity.compose.BackHandler { center.laterUpdate() }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    androidx.compose.foundation.layout.Column(modifier.width(460.dp)
        .background(pal.surface.copy(alpha = 0.97f), shape)
        .border(2.dp, pal.primary.copy(alpha = 0.7f), shape).padding(16.dp)) {
        androidx.compose.material3.Text("⬆  App-Update verfügbar", color = pal.primary, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        androidx.compose.material3.Text("ULTRA IPTV ${upd.version}", color = pal.text, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp))
        upd.notes.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.let {
            androidx.compose.material3.Text(it.take(140), color = pal.textDim, fontSize = 13.sp, maxLines = 2)
        }
        when (val s = st) {
            is de.ostlertekker.ultraiptv.core.update.UpdateState.Downloading -> {
                androidx.compose.material3.Text("Wird geladen … ${(s.progress * 100).toInt()} %", color = pal.text, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                androidx.compose.material3.LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
                    color = pal.primary, trackColor = pal.surfaceFocused)
            }
            is de.ostlertekker.ultraiptv.core.update.UpdateState.ReadyToInstall -> androidx.compose.foundation.layout.Row(Modifier.padding(top = 12.dp)) {
                if (!center.canInstallUpdate()) {
                    de.ostlertekker.ultraiptv.core.ui.TvButton("Installieren erlauben", onClick = { center.openInstallPermission() }, primary = true, focusRequester = focus)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                }
                de.ostlertekker.ultraiptv.core.ui.TvButton("Jetzt installieren", onClick = { center.installUpdate(s.file) }, primary = center.canInstallUpdate(),
                    focusRequester = if (center.canInstallUpdate()) focus else null)
            }
            is de.ostlertekker.ultraiptv.core.update.UpdateState.Error -> androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text("⚠ ${s.message}", color = pal.text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                androidx.compose.foundation.layout.Row(Modifier.padding(top = 10.dp)) {
                    de.ostlertekker.ultraiptv.core.ui.TvButton("Erneut versuchen", onClick = { scope.launch { center.downloadUpdate(upd) } }, primary = true, focusRequester = focus)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                    de.ostlertekker.ultraiptv.core.ui.TvButton("Später", onClick = { center.laterUpdate() })
                }
            }
            else -> androidx.compose.foundation.layout.Row(Modifier.padding(top = 12.dp)) {
                de.ostlertekker.ultraiptv.core.ui.TvButton("Jetzt aktualisieren", onClick = { scope.launch { center.downloadUpdate(upd) } }, primary = true, focusRequester = focus)
                androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                de.ostlertekker.ultraiptv.core.ui.TvButton("Später", onClick = { center.laterUpdate() })
            }
        }
        androidx.compose.material3.Text("Einstellungen, Profile und Favoriten bleiben erhalten.", color = pal.textDim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
    }
}
