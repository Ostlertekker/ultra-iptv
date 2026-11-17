package de.ostlertekker.ultraiptv.feature.discover

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import de.ostlertekker.ultraiptv.core.database.AiringProgram
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.discover.CommandParser
import de.ostlertekker.ultraiptv.core.discover.Genre
import de.ostlertekker.ultraiptv.core.discover.League
import de.ostlertekker.ultraiptv.core.discover.RemoteCommand
import de.ostlertekker.ultraiptv.core.discover.ScoreEvent
import de.ostlertekker.ultraiptv.core.discover.Section
import de.ostlertekker.ultraiptv.core.discover.Sport
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.ui.ScreenTitle
import de.ostlertekker.ultraiptv.core.ui.SectionHeader
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.TvChip
import de.ostlertekker.ultraiptv.core.ui.TvTextField
import de.ostlertekker.ultraiptv.core.ui.UltraLoader
import de.ostlertekker.ultraiptv.core.ui.initialsOf
import de.ostlertekker.ultraiptv.core.ui.tvFocusable
import de.ostlertekker.ultraiptv.feature.livetv.formatTime
import de.ostlertekker.ultraiptv.openPlayer
import de.ostlertekker.ultraiptv.ui.Routes
import kotlinx.coroutines.launch
import java.util.Calendar

// =====================================================================================================
// Gemeinsame Bausteine
// =====================================================================================================

/** Eine Sendung mit Sender, Zeit, Fortschritt und Status – eine fokussierbare Zeile. */
@Composable
fun ProgramRow(p: AiringProgram, now: Long, badge: String?, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val theme = LocalUltraTheme.current
    val pal = theme.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(theme.spec.corner.coerceAtMost(14.dp))
    Row(Modifier.fillMaxWidth()
        .tvFocusable(shape, onClick, onLongClick, scaleOnFocus = false, onFocus = { focused = it })
        .background(if (focused) pal.surfaceFocused else pal.surface, shape)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp, 40.dp), contentAlignment = Alignment.Center) {
            var ok by remember(p.logo) { mutableStateOf(false) }
            if (!ok) Text(initialsOf(p.channelName), color = pal.textDim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (p.logo != null) AsyncImage(p.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(), onSuccess = { ok = true })
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, color = pal.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${formatTime(p.startMs)}–${formatTime(p.endMs)} · ${p.channelName}", color = pal.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.startMs <= now && p.endMs > now) {
                val f = ((now - p.startMs).toFloat() / (p.endMs - p.startMs)).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).background(pal.textDim.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(pal.primary))
                }
            }
        }
        badge?.let {
            Text(it, color = if (focused) Color.Black else pal.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp).background(if (focused) pal.primary else pal.primary.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

/** Status-Text einer Sendung abhängig von Zeit und Archiv. */
fun programBadge(p: AiringProgram, now: Long): String = when {
    p.startMs <= now && p.endMs > now -> "● LIVE"
    p.endMs <= now && p.archiveDays > 0 && p.startMs >= now - p.archiveDays * 86_400_000L -> "▶ ARCHIV"
    p.endMs <= now -> "vorbei"
    else -> "in ${((p.startMs - now) / 60_000).let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" }}"
}

/** Standard-Aktion: live abspielen, aus dem Archiv abspielen oder Erinnerung setzen. */
@Composable
fun rememberProgramAction(vm: DiscoverViewModel): (AiringProgram) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return { p ->
        val now = System.currentTimeMillis()
        when {
            p.startMs <= now && p.endMs > now -> context.openPlayer(ContentKind.LIVE, p.channelId, category = p.category)
            p.endMs <= now -> if (p.archiveDays > 0) context.openPlayer(ContentKind.LIVE, p.channelId, category = p.category, catchupStart = p.startMs, catchupEnd = p.endMs)
                else Toast.makeText(context, "Für ${p.channelName} meldet der Anbieter kein Archiv", Toast.LENGTH_SHORT).show()
            else -> scope.launch {
                val on = vm.toggleReminder(p)
                Toast.makeText(context, if (on) "⏰ Erinnerung: ${p.title} um ${formatTime(p.startMs)}" else "Erinnerung entfernt", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, color = LocalUltraTheme.current.palette.textDim, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
}

// =====================================================================================================
// Universal Sports Mode
// =====================================================================================================

@Composable
fun SportsScreen(nav: NavController, vm: DiscoverViewModel = hiltViewModel()) {
    val pal = LocalUltraTheme.current.palette
    val board by vm.board.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<ScoreEvent?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) { vm.startScores(); onDispose { vm.stopScores() } }
    val failed by vm.boardFailed.collectAsStateWithLifecycle()
    val nextAt by vm.nextRefreshAt.collectAsStateWithLifecycle()
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick = System.currentTimeMillis() } }
    LaunchedEffect(selected?.id) { selected?.let { vm.loadDetail(it.league, it.id) } }
    // Laufende Spiele: Detail-Feld zeigt immer den aktuellen Stand aus der Live-Liste
    val current = selected?.let { s -> board?.events?.firstOrNull { it.id == s.id && it.league == s.league } ?: s }
    // Spieldetails bei laufenden Spielen alle 60 s nachladen
    LaunchedEffect(selected?.id, current?.live) {
        while (current?.live == true) { kotlinx.coroutines.delay(60_000); vm.loadDetail(current.league, current.id, refresh = true) }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        val secs = ((nextAt - tick) / 1000).coerceAtLeast(0)
        ScreenTitle("Live-Spielstände", subtitle = board?.let {
            "Stand ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMAN).format(java.util.Date(it.fetchedAt))}" +
                (if (failed) " · ⚠ letzte Aktualisierung fehlgeschlagen" else "") +
                " · nächste in $secs s · Quelle: ESPN"
        } ?: "Lade Live-Daten …")
        Row(Modifier.fillMaxSize().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LazyColumn(Modifier.weight(0.5f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Nur Spiele, die gerade laufen
                val evs = board?.events.orEmpty().filter { it.live }
                if (board != null && evs.isEmpty()) item {
                    EmptyHint("Gerade läuft kein Spiel. Abgedeckt: " + League.entries.joinToString(", ") { it.label } + ".")
                }
                items(evs, key = { it.league.name + it.id }) { e -> ScoreRow(e, selected?.id == e.id) { selected = e } }
            }
            Column(Modifier.weight(0.5f).fillMaxHeight().background(pal.surface, RoundedCornerShape(14.dp)).padding(14.dp)) {
                val e = current
                if (e == null) EmptyHint("Spiel auswählen für Ereignisse, Aufstellungen und Statistiken.")
                else MatchPanel(e, detail, vm)
            }
        }
    }
}

@Composable
private fun ScoreRow(e: ScoreEvent, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalUltraTheme.current
    val pal = theme.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().tvFocusable(shape, onClick, scaleOnFocus = false, onFocus = { focused = it })
        .background(if (focused || selected) pal.surfaceFocused else pal.surface, shape).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${e.league.sport.emoji} ${e.league.label}", color = pal.textDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(when (e.state) { "in" -> "● LIVE ${e.clock ?: ""}"; "post" -> e.detail.ifBlank { "Beendet" }
                else -> if (android.text.format.DateUtils.isToday(e.startMs)) formatTime(e.startMs)
                    else java.text.SimpleDateFormat("EE HH:mm", java.util.Locale.GERMAN).format(java.util.Date(e.startMs)) },
                color = if (e.live) Color(0xFFFF5050) else pal.textDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        if (e.competitors.size == 2) e.competitors.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.logo != null) AsyncImage(c.logo, null, Modifier.size(20.dp))
                Text(c.name, color = pal.text, fontSize = 15.sp, fontWeight = if (c.winner) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f).padding(start = 6.dp), maxLines = 1)
                Text(c.score ?: "–", color = pal.text, fontSize = 16.sp, fontWeight = FontWeight.Black)
            }
        } else {
            Text(e.title, color = pal.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            e.competitors.take(5).forEachIndexed { i, c -> Text("${c.order ?: (i + 1)}. ${c.name}" + (c.score?.let { "  $it" } ?: ""), color = pal.text, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun MatchPanel(e: ScoreEvent, d: de.ostlertekker.ultraiptv.core.discover.MatchDetail?, vm: DiscoverViewModel) {
    val pal = LocalUltraTheme.current.palette
    val onTv by vm.onTv.collectAsStateWithLifecycle()
    val failed by vm.detailFailed.collectAsStateWithLifecycle()
    val act = rememberProgramAction(vm)
    // Sender sofort suchen, sobald ein Spiel gewählt wird
    LaunchedEffect(e.id) { vm.findOnTv(e) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Text(e.scoreLine, color = pal.text, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text(listOfNotNull(e.detail.takeIf { it.isNotBlank() }, e.clock?.takeIf { e.live }, e.venue).joinToString(" · "), color = pal.textDim, fontSize = 12.sp)
        }
        item { SectionHeader("Läuft im TV – die 5 wahrscheinlichsten Sender") }
        run {
            val list = onTv
            if (list == null) item { Text("Suche im TV-Programm …", color = pal.textDim, fontSize = 12.sp) }
            else if (list.isEmpty()) item {
                Text("Kein passender Sender gefunden – weder ein Rechteinhaber in deiner Senderliste noch das Spiel im TV-Programm.",
                    color = pal.textDim, fontSize = 12.sp)
            } else {
                val now = System.currentTimeMillis()
                items(list, key = { "tv" + it.program.channelId }) { h ->
                    val badge = h.source.label + (h.country?.let { " · $it" } ?: "")
                    ProgramRow(h.program, now, badge, { act(h.program) })
                }
            }
        }
        if (d == null) item { Text(if (failed) "Für dieses Spiel liefert die Datenquelle keine weiteren Details." else "Lade Spieldetails …", color = pal.textDim, fontSize = 12.sp) }
        else {
            item { SectionHeader("Ereignisse") }
            if (d.keyEvents.isEmpty()) item { Text("Keine Ereignisdaten von der Quelle.", color = pal.textDim, fontSize = 12.sp) }
            items(d.keyEvents.takeLast(40).reversed()) { k ->
                Text("${k.clock ?: ""}  ${k.text}", color = pal.text, fontSize = 12.sp, maxLines = 2)
            }
            if (d.stats.isNotEmpty()) {
                item { SectionHeader("Statistik") }
                items(d.stats.take(16)) { s ->
                    Row { Text(s.home, color = pal.text, fontSize = 12.sp, modifier = Modifier.width(60.dp)); Text(s.label, color = pal.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text(s.away, color = pal.text, fontSize = 12.sp) }
                }
            }
            if (d.rosters.isNotEmpty()) {
                item { SectionHeader("Aufstellungen") }
                items(d.rosters) { r ->
                    Text(r.team + (r.formation?.let { " ($it)" } ?: ""), color = pal.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(r.players.filter { it.starter }.ifEmpty { r.players }.take(11).joinToString(" · ") { p -> listOfNotNull(p.jersey, p.name).joinToString(" ") },
                        color = pal.text, fontSize = 11.sp)
                }
            } else item { Text("Aufstellungen: von der Quelle (noch) nicht geliefert.", color = pal.textDim, fontSize = 12.sp) }
        }
    }
}
