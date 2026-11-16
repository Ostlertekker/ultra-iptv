package de.ostlertekker.ultraiptv.feature.discover

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.ostlertekker.ultraiptv.core.animation.hash01
import de.ostlertekker.ultraiptv.core.animation.rememberAnimationClock
import de.ostlertekker.ultraiptv.core.database.AiringProgram
import de.ostlertekker.ultraiptv.core.database.ChannelEntity
import de.ostlertekker.ultraiptv.core.database.EpgProgramEntity
import de.ostlertekker.ultraiptv.core.database.VirtualListEntity
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.discover.ListRules
import de.ostlertekker.ultraiptv.core.discover.Mood
import de.ostlertekker.ultraiptv.core.discover.SmartGroup
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.ui.FocusCard
import de.ostlertekker.ultraiptv.core.ui.ScreenTitle
import de.ostlertekker.ultraiptv.core.ui.SectionHeader
import de.ostlertekker.ultraiptv.core.ui.ToggleRow
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.TvChip
import de.ostlertekker.ultraiptv.core.ui.TvTextField
import de.ostlertekker.ultraiptv.core.ui.UltraLoader
import de.ostlertekker.ultraiptv.core.ui.tvFocusable
import de.ostlertekker.ultraiptv.feature.livetv.formatTime
import de.ostlertekker.ultraiptv.feature.multiview.MultiViewActivity
import de.ostlertekker.ultraiptv.openPlayer
import de.ostlertekker.ultraiptv.ui.Routes
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private fun airing(ch: ChannelEntity, p: EpgProgramEntity) =
    AiringProgram(ch.id, ch.name, ch.logo, ch.category, ch.number, ch.archiveDays, p.title, p.description, p.startMs, p.endMs)

// =====================================================================================================
// Holographic TV Guide
// =====================================================================================================

@Composable
fun MyTvScreen(vm: DiscoverViewModel = hiltViewModel()) {
    val pal = LocalUltraTheme.current.palette
    val context = LocalContext.current
    val stats by vm.stats.collectAsStateWithLifecycle()
    val forYou by vm.forYou.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadStats() }
    val s = stats
    fun h(ms: Long) = if (ms >= 3_600_000) "%.1f h".format(ms / 3_600_000f) else "${ms / 60_000} min"
    val now = System.currentTimeMillis()
    LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenTitle("Mein TV", subtitle = "Nur auf diesem Gerät gespeichert · zählt Wiedergaben ab 30 Sekunden") }
        if (s == null) item { UltraLoader() } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("7 Tage", h(s.totalMs7d), Modifier.weight(1f))
                    StatTile("30 Tage", h(s.totalMs30d), Modifier.weight(1f))
                    StatTile("Sitzungen", "${s.sessions}", Modifier.weight(1f))
                    StatTile("Favoriten", "${s.favoriteCount}", Modifier.weight(1f))
                    StatTile("Lieblingszeit", s.busiestHour?.let { "%02d–%02d Uhr".format(it, (it + 1) % 24) } ?: "–", Modifier.weight(1f))
                }
            }
            item {
                SectionHeader("Sehzeit pro Tag")
                Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                    val max = (s.perDay.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
                    val bw = size.width / s.perDay.size
                    s.perDay.forEachIndexed { i, (_, v) ->
                        val bh = v.toFloat() / max * (size.height - 20f)
                        drawRect(Brush.verticalGradient(listOf(pal.primary, pal.secondary)), Offset(i * bw + bw * 0.2f, size.height - 20f - bh), androidx.compose.ui.geometry.Size(bw * 0.6f, bh))
                    }
                }
                Row { s.perDay.forEach { (d, v) -> Text("$d ${h(v)}", color = pal.textDim, fontSize = 10.sp, modifier = Modifier.weight(1f)) } }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) { SectionHeader("Beliebteste Sender"); s.topChannels.forEach { (n, v) -> BarLine(n, v, s.topChannels.first().second, h(v)) } }
                    Column(Modifier.weight(1f)) {
                        SectionHeader("Kategorien"); s.topCategories.forEach { (n, v) -> BarLine(n, v, s.topCategories.first().second, h(v)) }
                        SectionHeader("Genres"); if (s.topGenres.isEmpty()) Text("Noch zu wenig Daten", color = pal.textDim, fontSize = 12.sp)
                        s.topGenres.forEach { (g, v) -> BarLine(g.label, v, s.topGenres.first().second, h(v)) }
                    }
                }
            }
            if (forYou.isNotEmpty()) {
                item { SectionHeader("Für dich – läuft gerade") }
                items(forYou, key = { "fy" + it.channelId }) { p -> ProgramRow(p, now, "● LIVE", { context.openPlayer(ContentKind.LIVE, p.channelId, category = p.category) }) }
            }
            item { SectionHeader("Verlauf") }
            if (s.history.isEmpty()) item { Text("Noch nichts angesehen.", color = pal.textDim) }
            items(s.history, key = { "h" + it.id }) { w ->
                Text("${java.text.SimpleDateFormat("EE HH:mm", java.util.Locale.GERMANY).format(java.util.Date(w.startedAt))}  ·  ${w.name}  ·  ${h(w.durationMs)}",
                    color = pal.text, fontSize = 13.sp)
            }
            item { TvButton("Statistik löschen", onClick = { vm.clearStats() }, modifier = Modifier.padding(top = 10.dp)) }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    val pal = LocalUltraTheme.current.palette
    Column(modifier.background(pal.surface, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Text(label.uppercase(), color = pal.textDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(value, color = pal.text, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun BarLine(name: String, v: Long, max: Long, label: String) {
    val pal = LocalUltraTheme.current.palette
    Column(Modifier.padding(vertical = 3.dp)) {
        Row { Text(name, color = pal.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text(label, color = pal.textDim, fontSize = 12.sp) }
        Box(Modifier.fillMaxWidth().height(5.dp).background(pal.textDim.copy(alpha = 0.2f), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth((v.toFloat() / max.coerceAtLeast(1)).coerceIn(0.02f, 1f)).fillMaxHeight().background(pal.primary, RoundedCornerShape(3.dp)))
        }
    }
}

// =====================================================================================================
// AI Channel Cloning – eigene, virtuelle Senderlisten
// =====================================================================================================

@Composable
fun ListsScreen(vm: DiscoverViewModel = hiltViewModel()) {
    val pal = LocalUltraTheme.current.palette
    val context = LocalContext.current
    val lists by vm.lists.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<VirtualListEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(280.dp).fillMaxHeight()) {
            ScreenTitle("Eigene Listen", subtitle = "Virtuelle Sender nach Regeln – es werden keine Streams kopiert")
            TvButton("＋ Neue Liste", onClick = { editing = null; creating = true }, primary = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(lists, key = { it.id }) { l ->
                    TvButton("◆ " + l.name, onClick = { editing = l; creating = false }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (creating || editing != null) ListEditor(vm, editing, onDone = { editing = null; creating = false })
            else Text("Lege Listen wie „Fußball live“, „Nur 4K“ oder „Meine Nachrichten“ an. Sie erscheinen in Live TV unter ◆ und im Channel Universe.",
                color = pal.textDim, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ListEditor(vm: DiscoverViewModel, existing: VirtualListEntity?, onDone: () -> Unit) {
    val pal = LocalUltraTheme.current.palette
    val context = LocalContext.current
    val cats by vm.categories.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val start = remember(existing?.id) { existing?.let { ListRules.fromJson(it.rulesJson) } ?: ListRules() }
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var selCats by remember(existing?.id) { mutableStateOf(start.categories.toSet()) }
    var groups by remember(existing?.id) { mutableStateOf(start.groups.toSet()) }
    var keywords by remember(existing?.id) { mutableStateOf(start.keywords.joinToString(", ")) }
    var topic by remember(existing?.id) { mutableStateOf(start.epgTopic) }
    var favOnly by remember(existing?.id) { mutableStateOf(start.favoritesOnly) }
    fun rules() = ListRules(selCats.toList(), keywords.split(',').map { it.trim() }.filter { it.isNotBlank() }, groups.toList(), topic.trim(), favOnly)
    LaunchedEffect(existing?.id) { vm.previewRules(rules()) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TvTextField(name, { name = it }, "Name der Liste") }
        item { SectionHeader("Intelligente Gruppen") }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(SmartGroup.entries) { g -> TvChip(g.label, g in groups, { groups = if (g in groups) groups - g else groups + g }) } } }
        item { SectionHeader("Kategorien (${selCats.size} gewählt)") }
        item {
            LazyVerticalGrid(GridCells.Adaptive(200.dp), Modifier.height(150.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(cats.size) { i -> val c = cats[i]; TvChip(c, c in selCats, { selCats = if (c in selCats) selCats - c else selCats + c }) }
            }
        }
        item { TvTextField(keywords, { keywords = it }, "Sendername enthält (Komma-getrennt, z. B. „sport, 4k“)") }
        item { TvTextField(topic, { topic = it }, "Nur Sender, auf denen gerade läuft … (z. B. „Fußball“)") }
        item { ToggleRow("Nur Lieblingssender", favOnly, { favOnly = it }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("Vorschau", onClick = { vm.previewRules(rules()) })
                TvButton("Speichern", onClick = { vm.saveList(existing?.id ?: 0L, name, rules()); onDone() }, primary = true)
                if (existing != null) TvButton("Löschen", onClick = { vm.deleteList(existing.id); onDone() })
                if (preview.isNotEmpty()) {
                    TvButton("▶ Abspielen", onClick = { context.openPlayer(ContentKind.LIVE, preview.first().id, category = preview.first().category) })
                    TvButton("⊞ Mehrfachansicht", onClick = { MultiViewActivity.open(context, preview.take(9).map { it.id }) })
                }
            }
        }
        item { Text("${preview.size} Sender passen: " + preview.take(12).joinToString { it.name } + if (preview.size > 12) " …" else "", color = pal.textDim, fontSize = 12.sp) }
    }
}
