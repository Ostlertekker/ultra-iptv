package de.ostlertekker.ultraiptv.core.notify

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ostlertekker.ultraiptv.core.database.ReminderEntity
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.update.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun vec(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()
private val AlarmVec by lazy { vec("alarm", "M22,5.72l-4.6,-3.86 -1.29,1.53 4.6,3.86L22,5.72zM7.88,3.39L6.6,1.86 2,5.71l1.29,1.53 4.59,-3.85zM12.5,8L11,8v6l4.75,2.85 0.75,-1.23 -4,-2.37L12.5,8zM12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z") }
private val UpdateVec by lazy { vec("update", "M5,20h14v-2H5V20zM19,9h-4V3H9v6H5l7,7L19,9z") }

/** Gemeinsamer Rahmen der Einblendungen: dunkle Karte im App-Stil, Symbol im Kreis, sanftes Einblenden von links. */
@Composable
private fun PopupCard(icon: ImageVector, label: String, title: String, subtitle: String?, modifier: Modifier,
                      progress: Float? = null, footer: String? = null, buttons: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val theme = LocalUltraTheme.current
    val pal = theme.palette
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(shown, modifier, enter = fadeIn() + slideInHorizontally { -it / 3 }) {
        val shape = RoundedCornerShape(theme.spec.corner.coerceIn(16.dp, 24.dp))
        Column(Modifier.width(470.dp).shadow(24.dp, shape).clip(shape)
            .background(Brush.verticalGradient(listOf(pal.surfaceFocused, pal.surface)))
            .border(1.dp, Color.White.copy(alpha = 0.09f), shape)) {
            Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).background(pal.primary.copy(alpha = 0.18f), CircleShape).border(1.dp, pal.primary.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = pal.primary, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(label.uppercase(), color = pal.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(title, color = pal.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontFamily = theme.spec.fontFamily)
                    if (subtitle != null) Text(subtitle, color = pal.textDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = if (footer != null) 6.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
            if (footer != null) Text(footer, color = pal.textDim, fontSize = 11.sp, modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp))
            // Dünner Fortschritts-/Countdown-Streifen am unteren Rand
            if (progress != null) Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.06f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(pal.primary))
            }
        }
    }
}

/** Programm-Erinnerung: 2 Minuten vor Beginn oben links, 30 Sekunden lang, mit Knopf zum Sender. */
@Composable
fun ReminderPopup(r: ReminderEntity?, modifier: Modifier, onDismiss: () -> Unit, onOpen: (ReminderEntity) -> Unit) {
    val rem = r ?: return
    val focus = remember { FocusRequester() }
    var left by remember(rem.id) { mutableIntStateOf(30) }
    LaunchedEffect(rem.id) {
        // Veraltet (App war im Hintergrund und die Sendung läuft schon länger) → nicht mehr anzeigen
        if (System.currentTimeMillis() > rem.startMs + 5 * 60_000L) { onDismiss(); return@LaunchedEffect }
        delay(250); runCatching { focus.requestFocus() }
        while (left > 0) { delay(1000); left-- }
        onDismiss()
    }
    BackHandler { onDismiss() }
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMAN).format(java.util.Date(rem.startMs))
    val mins = ((rem.startMs - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
    PopupCard(AlarmVec, "Erinnerung · " + (if (mins > 0) "in $mins Min." else "jetzt") + " · $time",
        rem.title, rem.channelName, modifier, progress = left / 30f) {
        TvButton("Zum Sender", onClick = { onOpen(rem) }, primary = true, focusRequester = focus)
        TvButton("Schließen", onClick = onDismiss)
        Spacer(Modifier.weight(1f))
        Text("${left}s", color = LocalUltraTheme.current.palette.textDim, fontSize = 13.sp)
    }
}

/** Neues App-Update: Einblendung oben links mit „Jetzt aktualisieren“ (lädt und installiert direkt) oder „Später“. */
@Composable
fun UpdatePopup(center: NotificationCenter, modifier: Modifier) {
    val u by center.updatePopup.collectAsStateWithLifecycle()
    val upd = u ?: return
    val st by center.updateState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(upd.version, st::class) { delay(300); runCatching { focus.requestFocus() } }
    // Fertig geladen → Installation direkt starten (Erlaubnis vorausgesetzt)
    LaunchedEffect(st) { (st as? UpdateState.ReadyToInstall)?.let { if (center.canInstallUpdate()) center.installUpdate(it.file) } }
    BackHandler { center.laterUpdate() }
    val note = upd.notes.lineSequence().map { it.trim().removePrefix("- ").removePrefix("* ") }
        .firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.take(120)
    val s = st
    PopupCard(UpdateVec, "App-Update verfügbar", "ULTRA IPTV ${upd.version}",
        when (s) {
            is UpdateState.Downloading -> "Wird geladen … ${(s.progress * 100).toInt()} %"
            is UpdateState.ReadyToInstall -> "Bereit zur Installation"
            is UpdateState.Error -> "⚠ ${s.message}"
            else -> note ?: (if (upd.sizeBytes > 0) "${upd.sizeBytes / 1_000_000} MB" else null)
        },
        modifier,
        progress = (s as? UpdateState.Downloading)?.progress,
        footer = "Einstellungen, Profile und Favoriten bleiben erhalten.") {
        when (s) {
            is UpdateState.Downloading -> TvButton("Im Hintergrund", onClick = { center.laterUpdate() }, focusRequester = focus)
            is UpdateState.ReadyToInstall -> {
                if (!center.canInstallUpdate()) TvButton("Installieren erlauben", onClick = { center.openInstallPermission() }, primary = true, focusRequester = focus)
                TvButton("Jetzt installieren", onClick = { center.installUpdate(s.file) }, primary = center.canInstallUpdate(),
                    focusRequester = if (center.canInstallUpdate()) focus else null)
            }
            is UpdateState.Error -> {
                TvButton("Erneut versuchen", onClick = { scope.launch { center.downloadUpdate(upd) } }, primary = true, focusRequester = focus)
                TvButton("Später", onClick = { center.laterUpdate() })
            }
            else -> {
                TvButton("Jetzt aktualisieren", onClick = { scope.launch { center.downloadUpdate(upd) } }, primary = true, focusRequester = focus)
                TvButton("Später", onClick = { center.laterUpdate() })
            }
        }
    }
}
