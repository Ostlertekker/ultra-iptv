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
fun HoloGuideScreen(vm: DiscoverViewModel = hiltViewModel()) {
    val pal = LocalUltraTheme.current.palette
    val rows by vm.holoChannels.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val act = rememberProgramAction(vm)
    val clock = rememberAnimationClock(true, fps = 30, pauseOnInput = true)
    LaunchedEffect(Unit) { vm.loadHolo() }
    val now = remember(rows) { System.currentTimeMillis() }
    val from = now - 10 * 60_000L
    val to = now + 3 * 3_600_000L
    Box(Modifier.fillMaxSize()) {
        // Holografischer Boden: perspektivisches Raster, das langsam auf den Betrachter zuläuft
        Canvas(Modifier.fillMaxSize()) {
            val t = clock.value
            val horizon = size.height * 0.22f
            for (i in 0..24) {
                val x = i / 24f * size.width
                drawLine(pal.primary.copy(alpha = 0.10f), Offset(size.width / 2 + (x - size.width / 2) * 0.15f, horizon), Offset(size.width / 2 + (x - size.width / 2) * 2.2f, size.height), 1.2f)
            }
            for (j in 0 until 14) {
                val z = ((j + (t * 0.3f) % 1f) / 14f)
                val y = horizon + (size.height - horizon) * z * z
                drawLine(pal.secondary.copy(alpha = 0.04f + 0.14f * z), Offset(0f, y), Offset(size.width, y), 1.2f)
            }
            // Scanline
            val sy = (t * 0.12f % 1f) * size.height
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, pal.primary.copy(alpha = 0.06f), Color.Transparent), startY = sy - 40f, endY = sy + 40f))
        }
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("HOLO-GUIDE", color = pal.primary, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
            Text("${rows.size} Sender · ${formatTime(from)} – ${formatTime(to)} · OK = ansehen / Archiv / Erinnern", color = pal.textDim, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            if (loading && rows.isEmpty()) UltraLoader(Modifier.align(Alignment.CenterHorizontally))
            if (!loading && rows.isEmpty()) Text("Keine Programmdaten vorhanden.", color = pal.textDim)
            // Räumliche Neigung: die Liste kippt leicht nach hinten weg
            Box(Modifier.fillMaxSize().graphicsLayer {
                rotationX = 12f; cameraDistance = 18f * density; transformOrigin = TransformOrigin(0.5f, 0f)
            }) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { HoloRuler(from, to, now) }
                    itemsIndexed(rows, key = { _, r -> r.first.id }) { _, (ch, progs) -> HoloRow(ch, progs, from, to, now) { act(it) } }
                }
            }
        }
    }
}

@Composable
private fun HoloRuler(from: Long, to: Long, now: Long) {
    val pal = LocalUltraTheme.current.palette
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(170.dp))
        Box(Modifier.weight(1f).height(18.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val span = (to - from).toFloat()
                var t = ((from / 1_800_000L) + 1) * 1_800_000L
                while (t < to) {
                    val x = (t - from) / span * size.width
                    drawLine(pal.textDim.copy(alpha = 0.5f), Offset(x, size.height * 0.5f), Offset(x, size.height), 1.5f)
                    t += 1_800_000L
                }
                val nx = (now - from) / span * size.width
                drawLine(pal.primary, Offset(nx, 0f), Offset(nx, size.height), 3f)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(from), color = pal.textDim, fontSize = 10.sp)
                Text(formatTime((from + to) / 2), color = pal.textDim, fontSize = 10.sp)
                Text(formatTime(to), color = pal.textDim, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun HoloRow(ch: ChannelEntity, progs: List<EpgProgramEntity>, from: Long, to: Long, now: Long, onPick: (AiringProgram) -> Unit) {
    val pal = LocalUltraTheme.current.palette
    Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(ch.name, color = pal.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(170.dp).padding(end = 8.dp))
        Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            var cursor = from
            progs.filter { it.endMs > from && it.startMs < to }.sortedBy { it.startMs }.forEach { p ->
                val s = maxOf(p.startMs, from, cursor); val e = minOf(p.endMs, to)
                if (s > cursor) Spacer(Modifier.weight((s - cursor).toFloat().coerceAtLeast(1f)))
                if (e > s) HoloCard(p, now, Modifier.weight((e - s).toFloat())) { onPick(airing(ch, p)) }
                cursor = maxOf(cursor, e)
            }
            if (cursor < to) Spacer(Modifier.weight((to - cursor).toFloat()))
        }
    }
}

@Composable
private fun HoloCard(p: EpgProgramEntity, now: Long, modifier: Modifier, onClick: () -> Unit) {
    val pal = LocalUltraTheme.current.palette
    var focused by remember { mutableStateOf(false) }
    val live = p.startMs <= now && p.endMs > now
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.fillMaxHeight()
        .tvFocusable(shape, onClick, onFocus = { focused = it })
        .background(Brush.verticalGradient(listOf(
            (if (live) pal.primary else pal.secondary).copy(alpha = if (focused) 0.55f else 0.16f),
            Color.White.copy(alpha = if (focused) 0.12f else 0.03f))), shape)
        .border(if (focused) 2.dp else 1.dp, (if (live) pal.primary else pal.secondary).copy(alpha = if (focused) 1f else 0.35f), shape)
        .padding(horizontal = 6.dp, vertical = 4.dp)) {
        Column {
            Text(p.title, color = Color.White, fontSize = 12.sp, fontWeight = if (live) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatTime(p.startMs), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1)
        }
    }
}

// =====================================================================================================
// Channel Universe
// =====================================================================================================

@Composable
fun UniverseScreen(vm: DiscoverViewModel = hiltViewModel()) {
    val pal = LocalUltraTheme.current.palette
    val context = LocalContext.current
    val clusters by vm.universe.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var ci by remember { mutableIntStateOf(0) }
    var si by remember { mutableIntStateOf(0) }
    var nowTitle by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val measurer = rememberTextMeasurer()
    val clock = rememberAnimationClock(true, fps = 30, pauseOnInput = false)
    val camX by animateFloatAsState(ci.toFloat(), tween(700), label = "cam")
    LaunchedEffect(Unit) { vm.loadUniverse() }
    LaunchedEffect(clusters.isNotEmpty()) { if (clusters.isNotEmpty()) { delay(150); runCatching { focus.requestFocus() } } }
    val cluster = clusters.getOrNull(ci)
    val star = cluster?.second?.getOrNull(si)
    LaunchedEffect(star?.id) { nowTitle = null; star?.let { delay(250); nowTitle = vm.nowOn(it) } }

    Box(Modifier.fillMaxSize()
        .focusRequester(focus)
        .onPreviewKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown || clusters.isEmpty()) return@onPreviewKeyEvent false
            val size = clusters[ci].second.size
            when (ev.key) {
                Key.DirectionRight -> { if (ci < clusters.lastIndex) { ci++; si = 0 }; true }
                Key.DirectionLeft -> if (ci > 0) { ci--; si = 0; true } else false // ganz links: zurück zur Menüleiste
                Key.DirectionDown -> { si = (si + 1) % size.coerceAtLeast(1); true }
                Key.DirectionUp -> { si = (si - 1 + size).mod(size.coerceAtLeast(1)); true }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { star?.let { context.openPlayer(ContentKind.LIVE, it.id, category = it.category) }; true }
                Key.Menu -> { MultiViewActivity.open(context, clusters[ci].second.take(9).map { it.id }); true }
                else -> false
            }
        }
        .focusable()) {
        Canvas(Modifier.fillMaxSize()) {
            val t = clock.value
            val w = size.width; val h = size.height
            // Hintergrund-Sterne
            for (i in 0 until 160) {
                val tw = 0.4f + 0.6f * (0.5f + 0.5f * sin(t * (0.5f + hash01(i, 3)) + i))
                drawCircle(Color.White.copy(alpha = 0.15f + 0.4f * tw * hash01(i, 4)), 0.8f + 1.4f * hash01(i, 5),
                    Offset(((hash01(i, 1) * w * 1.6f - camX * w * 0.08f) % (w * 1.6f) + w * 1.6f) % (w * 1.6f) - w * 0.3f, hash01(i, 2) * h))
            }
            val spacing = w * 0.42f
            clusters.forEachIndexed { k, (name, chans) ->
                val cx = w / 2 + (k - camX) * spacing
                if (cx < -w * 0.4f || cx > w * 1.4f) return@forEachIndexed
                val cy = h * 0.44f + sin(k * 1.7f) * h * 0.08f
                val sel = k == ci
                val color = if (name.startsWith("◆")) pal.secondary else pal.primary
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = if (sel) 0.35f else 0.12f), Color.Transparent), Offset(cx, cy), h * 0.3f), h * 0.3f, Offset(cx, cy))
                chans.forEachIndexed { j, _ ->
                    val ang = j * 2.39996f + t * 0.06f * (if (k % 2 == 0) 1 else -1)
                    val r = h * (0.035f + 0.038f * sqrt(j.toFloat()))
                    val pos = Offset(cx + cos(ang) * r, cy + sin(ang) * r * 0.75f)
                    val isStar = sel && j == si
                    val twinkle = 0.6f + 0.4f * sin(t * 2f + j)
                    drawCircle(color.copy(alpha = if (sel) 0.5f + 0.5f * twinkle else 0.35f), if (isStar) 9f else 3f + 2f * hash01(j, k), pos)
                    if (isStar) {
                        drawCircle(Color.White, 4f, pos)
                        drawCircle(color, 18f + 4f * sin(t * 4f), pos, style = Stroke(2f))
                    }
                }
                val label = measurer.measure(name.take(28), TextStyle(color = if (sel) Color.White else pal.textDim, fontSize = if (sel) 18.sp else 13.sp, fontWeight = FontWeight.Bold))
                drawText(label, topLeft = Offset(cx - label.size.width / 2f, cy + h * 0.27f))
            }
        }
        Column(Modifier.align(Alignment.TopStart).padding(24.dp)) {
            Text("CHANNEL UNIVERSE", color = pal.primary, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
            Text("◀ ▶ Galaxie wechseln · ▲ ▼ Sender · OK = ansehen · Menü = Galaxie im Multi-View", color = pal.textDim, fontSize = 12.sp)
            Text("◆ = deine eigenen Senderwelten (unter „Eigene Senderlisten“ anlegen)", color = pal.textDim, fontSize = 11.sp)
        }
        if (loading && clusters.isEmpty()) UltraLoader(Modifier.align(Alignment.Center))
        star?.let { s ->
            Column(Modifier.align(Alignment.BottomCenter).padding(24.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(cluster.first, nowTitle?.let { "Jetzt: $it" }).joinToString(" · "), color = pal.textDim, fontSize = 13.sp)
                Text("Stern ${si + 1} von ${cluster.second.size}", color = pal.textDim, fontSize = 11.sp)
            }
        }
    }
}

// =====================================================================================================
// AI Mood Mode
// =====================================================================================================

@Composable
fun MoodScreen(nav: NavController, initial: String, vm: DiscoverViewModel = hiltViewModel()) {
    val pal = LocalUltraTheme.current.palette
    val context = LocalContext.current
    val moods by vm.moods.collectAsStateWithLifecycle()
    val programs by vm.moodPrograms.collectAsStateWithLifecycle()
    val movies by vm.moodMovies.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    LaunchedEffect(initial) {
        val m = initial.split(',').mapNotNull { n -> Mood.entries.firstOrNull { it.name == n } }.toSet()
        if (m.isNotEmpty()) vm.setMoods(m)
    }
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        ScreenTitle("MOOD MODE", subtitle = "Wie fühlst du dich? Empfehlungen aus dem echten TV-Programm und deinen Filmen – lokal, regelbasiert, ohne Cloud")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Mood.entries) { m -> TvChip("${m.emoji} ${m.label}", m in moods, { vm.setMoods(if (m in moods) moods - m else moods + m) }) }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { TvTextField(text, { text = it }, "Oder beschreiben: „bin müde, will was Lustiges“") }
            TvButton("Verstehen", onClick = {
                val m = Mood.fromText(text).toSet()
                if (m.isNotEmpty()) vm.setMoods(m) else android.widget.Toast.makeText(context, "Keine Stimmung erkannt – bitte eine Kachel wählen", android.widget.Toast.LENGTH_SHORT).show()
            }, primary = true)
        }
        if (loading) UltraLoader(Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
        LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (moods.isEmpty()) item { Text("Wähle eine oder mehrere Stimmungen.", color = pal.textDim) }
            if (movies.isNotEmpty()) {
                item { SectionHeader("Filme für deine Stimmung") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(movies, key = { it.first.id }) { (m, _) ->
                            FocusCard(m.name, m.poster, 140.dp, 210.dp, onClick = { nav.navigate(Routes.movie(m.id)) }, subtitle = m.genre ?: m.year)
                        }
                    }
                }
            }
            if (programs.isNotEmpty()) item { SectionHeader("Läuft jetzt oder gleich im TV") }
            items(programs, key = { it.first.channelId + it.first.startMs }) { (p, score) ->
                ProgramRow(p, now, if (p.startMs <= now) "● LIVE · ${(score * 50).toInt().coerceIn(1, 100)} %" else programBadge(p, now),
                    { context.openPlayer(ContentKind.LIVE, p.channelId, category = p.category) })
            }
            if (moods.isNotEmpty() && programs.isEmpty() && movies.isEmpty() && !loading) item { Text("Nichts Passendes im aktuellen Programm gefunden.", color = pal.textDim) }
        }
    }
}


// =====================================================================================================
// Personal TV Universe
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
        item { ScreenTitle("MEIN TV-UNIVERSUM", subtitle = "Nur auf diesem Gerät gespeichert · zählt Wiedergaben ab 30 Sekunden") }
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
                    Column(Modifier.weight(1f)) { SectionHeader("Top-Sender"); s.topChannels.forEach { (n, v) -> BarLine(n, v, s.topChannels.first().second, h(v)) } }
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
            ScreenTitle("EIGENE LISTEN", subtitle = "Virtuelle Sender nach Regeln – es werden keine Streams kopiert")
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
        item { SectionHeader("Smart-Gruppen") }
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
                    TvButton("⊞ Multi-View", onClick = { MultiViewActivity.open(context, preview.take(9).map { it.id }) })
                }
            }
        }
        item { Text("${preview.size} Sender passen: " + preview.take(12).joinToString { it.name } + if (preview.size > 12) " …" else "", color = pal.textDim, fontSize = 12.sp) }
    }
}
