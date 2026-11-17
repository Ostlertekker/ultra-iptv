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
import de.ostlertekker.ultraiptv.feature.multiview.MultiViewActivity
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
// Entdecken – Übersicht mit Smart Remote
// =====================================================================================================

private data class HubTile(val emoji: String, val title: String, val text: String, val route: String)

@Composable
fun ExploreHub(nav: NavController, vm: DiscoverViewModel = hiltViewModel()) {
    val pal = LocalUltraTheme.current.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }

    fun execute(text: String) {
        val cmd = CommandParser.parse(text)
        feedback = "Verstanden: " + when (cmd) {
            RemoteCommand.BestSport -> "bester Sportstream"
            is RemoteCommand.ShowTopic -> "Sender mit „${cmd.topic}“"
            RemoteCommand.PreviousChannel -> "vorheriger Sender"
            RemoteCommand.MultiView -> "Mehrfachansicht"
            RemoteCommand.Tonight -> "heute Abend"
            RemoteCommand.WhatsOn -> "was gerade läuft"
            is RemoteCommand.OpenChannel -> "Sender „${cmd.query}“"
            is RemoteCommand.OpenSection -> cmd.section.name.lowercase()
            is RemoteCommand.MoodPick -> "Stimmung " + cmd.moods.joinToString { it.label }
            is RemoteCommand.Search -> "Suche „${cmd.query}“"
        }
        when (cmd) {
            RemoteCommand.BestSport -> scope.launch {
                val best = vm.bestSportChannel()
                if (best != null) context.openPlayer(ContentKind.LIVE, best.channelId, category = best.category)
                else feedback = "Laut TV-Programm läuft gerade kein Sport."
            }
            is RemoteCommand.ShowTopic -> nav.navigate(Routes.radar(cmd.topic))
            RemoteCommand.PreviousChannel -> scope.launch {
                val id = vm.previousChannelId()
                if (id != null) context.openPlayer(ContentKind.LIVE, id) else feedback = "Noch kein Sender im Verlauf."
            }
            RemoteCommand.MultiView -> MultiViewActivity.open(context, emptyList())
            RemoteCommand.Tonight -> nav.navigate(Routes.timeMachine(tonight()))
            RemoteCommand.WhatsOn -> nav.navigate(Routes.WHATSON)
            is RemoteCommand.OpenChannel -> scope.launch {
                val ch = vm.findChannel(cmd.query, cmd.number)
                if (ch != null) context.openPlayer(ContentKind.LIVE, ch.id, category = ch.category) else feedback = "Kein Sender „${cmd.query}“ gefunden."
            }
            is RemoteCommand.OpenSection -> nav.navigate(when (cmd.section) {
                Section.FAVORITES -> Routes.FAVORITES; Section.SETTINGS -> Routes.SETTINGS; Section.MOVIES -> Routes.MOVIES
                Section.SERIES -> Routes.SERIES; Section.GUIDE -> Routes.EPG; Section.WORLD -> Routes.LIVE; Section.SPORTS -> Routes.SPORTS
                Section.STATS -> Routes.MYTV; Section.TIME_MACHINE -> Routes.timeMachine(0); Section.UNIVERSE -> Routes.MYTV
                Section.LIVE -> Routes.LIVE; Section.HOME -> Routes.HOME
            })
            is RemoteCommand.MoodPick -> nav.navigate(Routes.WHATSON)
            is RemoteCommand.Search -> if (cmd.query.isNotBlank()) nav.navigate(Routes.radar(cmd.query))
        }
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input = it; execute(it) }
        }
    }

    val tiles = listOf(
        HubTile("📡", "Jetzt läuft", "Alle Sender auf einen Blick", Routes.WHATSON),
        HubTile("🛰", "Sender-Radar", "Wo läuft was? Suche im ganzen TV-Programm", Routes.radar("")),
        HubTile("⏳", "TV-Zeitmaschine", "Vergangenes, Jetzt und Zukunft · Catch-up", Routes.timeMachine(0)),
        HubTile("🏟", "Live Event Tracker", "Fußball, CL, F1, Tennis, Basketball, Boxen", Routes.EVENTS),
        HubTile("📊", "Sport-Dashboard", "Live-Spielstände, Aufstellungen, Statistik", Routes.SPORTS),
        HubTile("⊞", "Multi-View", "Bis zu 16 Sender gleichzeitig", "multiview"),
        HubTile("📈", "Mein TV", "Statistik, Verlauf, für dich", Routes.MYTV),
        HubTile("🧬", "Eigene Senderlisten", "Virtuelle Sender nach deinen Regeln", Routes.LISTS),
    )

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        ScreenTitle("Entdecken", subtitle = "Intelligente TV-Funktionen – alles aus deinen echten Sender- und Programmdaten")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { TvTextField(input, { input = it }, "Smart Remote – z. B. „Zeige mir alle Sender mit Fußball“") }
            TvButton("Ausführen", onClick = { if (input.isNotBlank()) execute(input) }, primary = true)
            TvButton("🎤 Sprechen", onClick = {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Was soll ULTRA IPTV tun?")
                if (i.resolveActivity(context.packageManager) != null) voice.launch(i) else feedback = "Spracheingabe ist auf diesem Gerät nicht verfügbar – bitte Text eingeben."
            })
        }
        feedback?.let { Text(it, color = pal.primary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Öffne den besten verfügbaren Sportstream", "Zeige mir alle Sender mit Fußball", "Wechsle zum vorherigen Sender",
                "Öffne die Mehrfachansicht", "Zeige mir, was heute Abend läuft")) { ex -> TvChip(ex, false, { input = ex; execute(ex) }) }
        }
        Spacer(Modifier.height(14.dp))
        LazyVerticalGrid(GridCells.Adaptive(250.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(tiles) { t -> HubCard(t) { if (t.route == "multiview") MultiViewActivity.open(context, emptyList()) else nav.navigate(t.route) } }
        }
    }
}

@Composable
private fun HubCard(t: HubTile, onClick: () -> Unit) {
    val theme = LocalUltraTheme.current
    val pal = theme.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(theme.spec.corner)
    Column(Modifier.fillMaxWidth().height(120.dp)
        .tvFocusable(shape, onClick, onFocus = { focused = it })
        .background(Brush.linearGradient(listOf(pal.surfaceFocused.copy(alpha = if (focused) 1f else 0.6f), pal.surface)), shape)
        .padding(16.dp)) {
        Text(t.emoji, fontSize = 28.sp)
        Text(t.title, color = pal.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(t.text, color = pal.textDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

fun tonight(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 15); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

// =====================================================================================================
// What's Playing Now
// =====================================================================================================

@Composable
fun WhatsOnScreen(nav: NavController, vm: DiscoverViewModel = hiltViewModel()) {
    val pal = LocalUltraTheme.current.palette
    val context = LocalContext.current
    val airing by vm.airing.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var genre by remember { mutableStateOf<Genre?>(null) }
    LaunchedEffect(Unit) { while (true) { vm.loadAiring(System.currentTimeMillis()); kotlinx.coroutines.delay(60_000) } }
    val now = System.currentTimeMillis()
    val classified = remember(airing) { airing.map { it to Genre.detect(it.title + " " + (it.description ?: "") + " " + it.category) } }
    val genres = remember(classified) { classified.flatMap { it.second }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    val shown = classified.filter { genre == null || genre in it.second }.map { it.first }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        ScreenTitle("Jetzt läuft", subtitle = "${airing.size} Sender mit Programminfos · aktualisiert sich jede Minute")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { TvChip("Alle", genre == null, { genre = null }) }
            items(genres) { g -> TvChip(g.label, genre == g, { genre = g }) }
        }
        Spacer(Modifier.height(10.dp))
        if (loading && airing.isEmpty()) UltraLoader(Modifier.align(Alignment.CenterHorizontally))
        else if (shown.isEmpty()) EmptyHint("Keine Programmdaten – unter Einstellungen den EPG laden.")
        LazyVerticalGrid(GridCells.Adaptive(330.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.channelId }) { p ->
                ProgramRow(p, now, null, onClick = { context.openPlayer(ContentKind.LIVE, p.channelId, category = p.category) },
                    onLongClick = { MultiViewActivity.openWithComparison(context, p.channelId) })
            }
        }
        Text("OK = ansehen · lange OK = Channel Comparison (gleiche Sendung/Sender in allen Varianten)", color = pal.textDim, fontSize = 11.sp)
    }
}

// =====================================================================================================
// Channel Radar + Global EPG
// =====================================================================================================

@Composable
fun RadarScreen(initial: String, vm: DiscoverViewModel = hiltViewModel()) {
    val pal = LocalUltraTheme.current.palette
    val context = LocalContext.current
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf(initial) }
    var range by remember { mutableStateOf(0) } // 0 = jetzt+heute, 1 = 7 Tage zurück/vor
    val act = rememberProgramAction(vm)
    fun run() {
        val now = System.currentTimeMillis()
        if (range == 0) vm.search(q, now - 60_000, now + 18 * 3_600_000L) else vm.search(q, now - 7 * 86_400_000L, now + 7 * 86_400_000L)
    }
    LaunchedEffect(initial) { if (initial.isNotBlank()) run() }
    val now = System.currentTimeMillis()
    val live = results.filter { it.startMs <= now && it.endMs > now }
    val later = results.filter { it.startMs > now }
    val past = results.filter { it.endMs <= now }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        ScreenTitle("Sender-Radar", subtitle = "Durchsucht Titel, Beschreibungen und Sendernamen im gesamten TV-Programm (Global EPG)")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { TvTextField(q, { q = it }, "Thema, Sendung, Team, Person …") }
            TvButton("Scannen", onClick = { run() }, primary = true)
            TvChip("Heute", range == 0, { range = 0; run() })
            TvChip("± 7 Tage", range == 1, { range = 1; run() })
        }
        LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Fußball", "Bundesliga", "Champions League", "Formel 1", "Tatort", "Nachrichten", "Doku", "Krimi", "Comedy", "Kinder")) { t ->
                TvChip(t, q == t, { q = t; run() })
            }
        }
        if (live.size > 1) TvButton("⊞ Alle ${minOf(live.size, 16)} Live-Treffer im Multi-View", onClick = { MultiViewActivity.open(context, live.map { it.channelId }.distinct()) },
            modifier = Modifier.padding(top = 8.dp))
        if (loading) UltraLoader(Modifier.align(Alignment.CenterHorizontally).padding(10.dp))
        LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (live.isNotEmpty()) item { SectionHeader("Läuft jetzt auf ${live.map { it.channelId }.distinct().size} Sendern") }
            items(live, key = { "l" + it.channelId + it.startMs }) { p -> ProgramRow(p, now, programBadge(p, now), { act(p) }, { MultiViewActivity.openWithComparison(context, p.channelId) }) }
            if (later.isNotEmpty()) item { SectionHeader("Demnächst") }
            items(later, key = { "n" + it.channelId + it.startMs }) { p -> ProgramRow(p, now, programBadge(p, now), { act(p) }) }
            if (past.isNotEmpty()) item { SectionHeader("Bereits gelaufen") }
            items(past, key = { "p" + it.channelId + it.startMs }) { p -> ProgramRow(p, now, programBadge(p, now), { act(p) }) }
            if (results.isEmpty() && query.isNotBlank() && !loading) item { EmptyHint("Nichts gefunden für „$query“.") }
        }
    }
}

// =====================================================================================================
// TV Time Machine / Time Travel EPG
// =====================================================================================================

@Composable
fun TimeMachineScreen(initialAt: Long, vm: DiscoverViewModel = hiltViewModel()) {
    val pal = LocalUltraTheme.current.palette
    val airing by vm.airing.collectAsStateWithLifecycle()
    val at by vm.airingAt.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val act = rememberProgramAction(vm)
    LaunchedEffect(initialAt) { vm.loadAiring(if (initialAt > 0) initialAt else System.currentTimeMillis()) }
    val now = System.currentTimeMillis()
    val dayFmt = remember { java.text.SimpleDateFormat("EE dd.MM.", java.util.Locale.GERMANY) }
    val diffH = (at - now) / 3_600_000.0
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        ScreenTitle("TV-Zeitmaschine", subtitle = when {
            kotlin.math.abs(at - now) < 120_000 -> "Jetzt"
            at < now -> "Zeitreise: %.1f Stunden zurück · Catch-up, wo der Anbieter ein Archiv hat".format(-diffH)
            else -> "Zeitreise: %.1f Stunden in die Zukunft · Erinnerungen setzen".format(diffH)
        })
        // Zeitachse
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton("◀ 1 Tag", onClick = { vm.loadAiring(at - 86_400_000L) })
            TvButton("◀ 1 h", onClick = { vm.loadAiring(at - 3_600_000L) })
            TvButton("◀ 30 min", onClick = { vm.loadAiring(at - 1_800_000L) })
            Text("${dayFmt.format(java.util.Date(at))} ${formatTime(at)}", color = pal.primary, fontSize = 22.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.padding(horizontal = 10.dp))
            TvButton("30 min ▶", onClick = { vm.loadAiring(at + 1_800_000L) })
            TvButton("1 h ▶", onClick = { vm.loadAiring(at + 3_600_000L) })
            TvButton("1 Tag ▶", onClick = { vm.loadAiring(at + 86_400_000L) })
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvChip("Jetzt", kotlin.math.abs(at - now) < 120_000, { vm.loadAiring(System.currentTimeMillis()) })
            TvChip("Heute 20:15", false, { vm.loadAiring(tonight()) })
            TvChip("Gestern 20:15", false, { vm.loadAiring(tonight() - 86_400_000L) })
            TvChip("Morgen 20:15", false, { vm.loadAiring(tonight() + 86_400_000L) })
        }
        TimelineBar(at, now)
        if (loading && airing.isEmpty()) UltraLoader(Modifier.align(Alignment.CenterHorizontally))
        LazyColumn(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (airing.isEmpty() && !loading) item { EmptyHint("Für diesen Zeitpunkt liegen keine Programmdaten vor (EPG reicht meist 1–7 Tage).") }
            items(airing, key = { it.channelId }) { p -> ProgramRow(p, now, programBadge(p, now), { act(p) }) }
        }
    }
}

/** Visuelle Zeitachse −7 Tage … +7 Tage mit Markierung des gewählten Zeitpunkts. */
@Composable
private fun TimelineBar(at: Long, now: Long) {
    val pal = LocalUltraTheme.current.palette
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(26.dp).padding(vertical = 8.dp)) {
        val span = 14 * 86_400_000f
        fun x(t: Long) = ((t - (now - 7 * 86_400_000L)) / span * size.width).coerceIn(0f, size.width)
        drawRect(Brush.horizontalGradient(listOf(pal.secondary.copy(alpha = 0.3f), pal.primary.copy(alpha = 0.6f), pal.textDim.copy(alpha = 0.2f))))
        for (d in -7..7) drawLine(Color.White.copy(alpha = 0.25f), androidx.compose.ui.geometry.Offset(x(now + d * 86_400_000L), 0f), androidx.compose.ui.geometry.Offset(x(now + d * 86_400_000L), size.height))
        drawLine(Color.White, androidx.compose.ui.geometry.Offset(x(now), -4f), androidx.compose.ui.geometry.Offset(x(now), size.height + 4f), 3f)
        drawCircle(pal.primary, 8f, androidx.compose.ui.geometry.Offset(x(at), size.height / 2))
    }
}

// =====================================================================================================
// Live Event Tracker
// =====================================================================================================

@Composable
fun EventsScreen(nav: NavController, vm: DiscoverViewModel = hiltViewModel()) {
    val events by vm.events.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val act = rememberProgramAction(vm)
    var sport by remember { mutableStateOf<Sport?>(null) }
    LaunchedEffect(Unit) { vm.loadEvents() }
    val now = System.currentTimeMillis()
    val shown = events.filter { (sport == null || it.sport == sport) && it.program.endMs > now }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        ScreenTitle("Live-Ereignisse", subtitle = "Sportereignisse automatisch aus dem TV-Programm erkannt · nächste 36 Stunden")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { TvChip("Alle (${events.count { it.program.endMs > now }})", sport == null, { sport = null }) }
            items(Sport.entries) { s -> TvChip("${s.emoji} ${s.label} (${events.count { it.sport == s && it.program.endMs > now }})", sport == s, { sport = s }) }
            item { TvButton("📊 Live-Spielstände", onClick = { nav.navigate(Routes.SPORTS) }) }
        }
        if (loading && events.isEmpty()) UltraLoader(Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
        LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (shown.isEmpty() && !loading) item { EmptyHint("Keine passenden Sportereignisse im TV-Programm gefunden.") }
            items(shown, key = { it.program.channelId + it.program.startMs }) { e ->
                ProgramRow(e.program.copy(title = "${e.sport.emoji} ${e.program.title}"), now, programBadge(e.program, now) + if (e.program.startMs > now) " · ⏰" else "",
                    { act(e.program) }, { MultiViewActivity.openWithComparison(context, e.program.channelId) })
            }
        }
        Text("OK = ansehen bzw. Erinnerung · lange OK = auf allen Sendern vergleichen", color = LocalUltraTheme.current.palette.textDim, fontSize = 11.sp)
    }
}

// =====================================================================================================
// Universal Sports Mode
// =====================================================================================================

@Composable
fun SportsScreen(nav: NavController, vm: DiscoverViewModel = hiltViewModel()) {
    val pal = LocalUltraTheme.current.palette
    val board by vm.board.collectAsStateWithLifecycle()
    val leagues by vm.leagues.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<ScoreEvent?>(null) }
    LaunchedEffect(Unit) { vm.startScores() }
    LaunchedEffect(selected?.id) { selected?.let { vm.loadDetail(it.league, it.id) } }
    // Laufende Spiele: Detail-Feld zeigt immer den aktuellen Stand aus der Live-Liste
    val current = selected?.let { s -> board?.events?.firstOrNull { it.id == s.id && it.league == s.league } ?: s }
    // Spieldetails bei laufenden Spielen alle 60 s nachladen
    LaunchedEffect(selected?.id, current?.live) {
        while (current?.live == true) { kotlinx.coroutines.delay(60_000); vm.loadDetail(current.league, current.id, refresh = true) }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        ScreenTitle("Live-Spielstände", subtitle = board?.let { "Stand ${formatTime(it.fetchedAt)} · Quelle: ESPN (öffentliche Live-Daten) · aktualisiert alle 30 s" } ?: "Lade Live-Daten …")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(League.entries) { l -> TvChip(l.label, l in leagues, { vm.toggleLeague(l) }) }
        }
        board?.failedLeagues?.takeIf { it.isNotEmpty() }?.let { Text("Nicht erreichbar: " + it.joinToString { l -> l.label }, color = Color(0xFFFFB060), fontSize = 11.sp) }
        Row(Modifier.fillMaxSize().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LazyColumn(Modifier.weight(0.5f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val evs = board?.events.orEmpty()
                if (board != null && evs.isEmpty()) item { EmptyHint("Heute keine Spiele in den gewählten Wettbewerben.") }
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
        item { SectionHeader("Läuft im TV") }
        run {
            val list = onTv
            if (list == null) item { Text("Suche im TV-Programm …", color = pal.textDim, fontSize = 12.sp) }
            else if (list.isEmpty()) item {
                Text("Im TV-Programm deiner Sender wurde zu diesem Spiel nichts gefunden (weder Teams/Spieler noch Wettbewerb).",
                    color = pal.textDim, fontSize = 12.sp)
            } else {
                val now = System.currentTimeMillis()
                items(list, key = { "tv" + it.channelId + it.startMs }) { p -> ProgramRow(p, now, programBadge(p, now), { act(p) }) }
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
