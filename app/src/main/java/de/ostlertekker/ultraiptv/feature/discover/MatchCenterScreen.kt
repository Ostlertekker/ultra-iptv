package de.ostlertekker.ultraiptv.feature.discover

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.discover.Incident
import de.ostlertekker.ultraiptv.core.discover.IncidentKind
import de.ostlertekker.ultraiptv.core.discover.Lineup
import de.ostlertekker.ultraiptv.core.discover.MatchCenter
import de.ostlertekker.ultraiptv.core.discover.McTeam
import de.ostlertekker.ultraiptv.core.discover.ScoreEvent
import de.ostlertekker.ultraiptv.core.discover.StatRow
import de.ostlertekker.ultraiptv.core.ui.TvChip
import de.ostlertekker.ultraiptv.core.ui.UltraLoader
import de.ostlertekker.ultraiptv.core.ui.initialsOf
import kotlinx.coroutines.launch

private enum class McTab(val label: String) { OVERVIEW("Übersicht"), TICKER("Live-Ticker"), STATS("Statistik"), LINEUP("Aufstellung"), PLAYERS("Spieler"), TABLE("Tabelle"), FORM("Form & Duelle") }

private fun hex(c: String?, fallback: Color): Color = runCatching { Color(android.graphics.Color.parseColor("#" + c!!.trim('#'))) }.getOrDefault(fallback)

/** Vollbild-Match-Center im Stil der Live-Ansichten von Sportwetten-Seiten (ohne Quoten). Zurück schließt. */
@Composable
fun MatchCenterScreen(e: ScoreEvent, vm: DiscoverViewModel, onClose: () -> Unit) {
    val pal = LocalUltraTheme.current.palette
    val c by vm.center.collectAsStateWithLifecycle()
    val failed by vm.centerFailed.collectAsStateWithLifecycle()
    val board by vm.board.collectAsStateWithLifecycle()
    val ev = board?.events?.firstOrNull { it.id == e.id && it.league == e.league } ?: e
    LaunchedEffect(e.id) { vm.openCenter(e) }
    BackHandler { vm.closeCenter(); onClose() }

    Box(Modifier.fillMaxSize().background(Color(0xFF080B10))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 22.dp)) {
            Scoreboard(ev, c)
            val m = c
            if (m == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (failed) Text("Für dieses Spiel liefert die Datenquelle keine Detaildaten.", color = pal.textDim, fontSize = 16.sp) else UltraLoader(label = "Lade Spieldaten …")
                }
                return@Column
            }
            val tabs = McTab.entries.filter { t ->
                when (t) {
                    McTab.OVERVIEW -> true
                    McTab.TICKER -> m.ticker.isNotEmpty()
                    McTab.STATS -> m.stats.isNotEmpty()
                    McTab.LINEUP -> m.lineups.any { it.starters.isNotEmpty() }
                    McTab.PLAYERS -> m.players.isNotEmpty()
                    McTab.TABLE -> m.standings != null
                    McTab.FORM -> m.form.isNotEmpty() || m.series != null
                }
            }
            var tab by remember { mutableIntStateOf(0) }
            if (tab >= tabs.size) tab = 0
            val tabFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { tabFocus.requestFocus() } }
            LazyRow(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(tabs) { i, t ->
                    TvChip(t.label, i == tab, { tab = i }, Modifier.then(if (i == tab) Modifier.focusRequester(tabFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) tab = i })
                }
            }
            // Inhalt: Pfeil hoch/runter scrollt, links/rechts wechselt den Reiter
            val scroll = rememberScrollState()
            val scope = rememberCoroutineScope()
            var focused by remember { mutableStateOf(false) }
            LaunchedEffect(tab) { scroll.scrollTo(0) }
            Box(Modifier.weight(1f).fillMaxWidth()
                .border(2.dp, if (focused) pal.primary.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(16.dp))
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { k ->
                    if (k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (k.key.nativeKeyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { scope.launch { scroll.animateScrollBy(320f) }; true }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> if (scroll.value == 0) false else { scope.launch { scroll.animateScrollBy(-320f) }; true }
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { if (tab > 0) tab--; true }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { if (tab < tabs.size - 1) tab++; true }
                        else -> false
                    }
                }
                .focusable()) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(6.dp)) {
                    when (tabs.getOrNull(tab)) {
                        McTab.OVERVIEW -> Overview(m, ev)
                        McTab.TICKER -> Ticker(m)
                        McTab.STATS -> Stats(m)
                        McTab.LINEUP -> Lineups(m)
                        McTab.PLAYERS -> Players(m)
                        McTab.TABLE -> Table(m)
                        McTab.FORM -> FormTab(m)
                        null -> Unit
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
            Text("◀ ▶ Reiter wechseln · ▲ ▼ blättern · Zurück schließt · " + (if (ev.live) "aktualisiert alle 30 s" else "Quelle: ESPN") +
                " · Stand ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMAN).format(java.util.Date(m.fetchedAt))}",
                color = pal.textDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Scoreboard(e: ScoreEvent, m: MatchCenter?) {
    val pal = LocalUltraTheme.current.palette
    val h = m?.home; val a = m?.away
    val ch = e.competitors.firstOrNull { it.homeAway == "home" } ?: e.competitors.getOrNull(0)
    val ca = e.competitors.firstOrNull { it.homeAway == "away" } ?: e.competitors.getOrNull(1)
    val hc = hex(h?.color, Color(0xFF1C2A44)); val ac = hex(a?.color, Color(0xFF3A1C2A))
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxWidth().height(if (e.competitors.size == 2) 190.dp else 150.dp)
        .background(Brush.horizontalGradient(listOf(hc.copy(alpha = 0.55f), Color(0xFF0E131B), ac.copy(alpha = 0.55f))), shape)
        .border(1.dp, Color.White.copy(alpha = 0.08f), shape).padding(18.dp)) {
        Text("${e.league.sport.emoji} ${e.league.label}", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, modifier = Modifier.align(Alignment.TopCenter))
        if (e.competitors.size != 2) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(e.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text(statusText(e), color = if (e.live) Color(0xFFFF5050) else Color.White.copy(alpha = 0.7f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            return@Box
        }
        Row(Modifier.fillMaxSize().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamSide(h?.name ?: ch?.name ?: "", h?.logo ?: ch?.logo, Modifier.weight(1f), goals(m, true))
            Column(Modifier.width(300.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${ch?.score ?: h?.score ?: "–"} : ${ca?.score ?: a?.score ?: "–"}", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black)
                if (e.live) {
                    val inf = rememberInfiniteTransition(label = "live")
                    val al by inf.animateFloat(1f, 0.3f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "blink")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).alpha(al).background(Color(0xFFFF3B3B), CircleShape))
                        Text("  LIVE ${e.clock ?: ""}", color = Color(0xFFFF5050), fontSize = 18.sp, fontWeight = FontWeight.Black)
                    }
                } else Text(statusText(e), color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            TeamSide(a?.name ?: ca?.name ?: "", a?.logo ?: ca?.logo, Modifier.weight(1f), goals(m, false))
        }
    }
}

private fun statusText(e: ScoreEvent) = when (e.state) {
    "post" -> e.detail.ifBlank { "Beendet" }
    "pre" -> java.text.SimpleDateFormat("EE dd.MM. HH:mm", java.util.Locale.GERMAN).format(java.util.Date(e.startMs))
    else -> e.detail
}

private fun goals(m: MatchCenter?, home: Boolean) = m?.incidents.orEmpty()
    .filter { it.home == home && it.kind in setOf(IncidentKind.GOAL, IncidentKind.OWN_GOAL, IncidentKind.PENALTY, IncidentKind.RED) }
    .map { "${if (it.kind == IncidentKind.RED) "🟥" else "⚽"} ${it.player} ${it.clock}" + (if (it.kind == IncidentKind.OWN_GOAL) " (ET)" else if (it.kind == IncidentKind.PENALTY) " (E)" else "") }

@Composable
private fun TeamSide(name: String, logo: String?, modifier: Modifier, lines: List<String>) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
            var ok by remember(logo) { mutableStateOf(false) }
            if (!ok) Text(initialsOf(name), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            if (logo != null) AsyncImage(logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(), onSuccess = { ok = true })
        }
        Text(name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        lines.take(4).forEach { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1) }
        if (lines.size > 4) Text("+${lines.size - 4} weitere", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    val pal = LocalUltraTheme.current.palette
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(pal.surface, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Text(title, color = pal.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
private fun Overview(m: MatchCenter, e: ScoreEvent) {
    val pal = LocalUltraTheme.current.palette
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1.3f)) {
            if (m.incidents.isNotEmpty()) Card("Spielverlauf") {
                m.incidents.forEach { i -> IncidentRow(i) }
            }
            if (e.competitors.size > 2) Card("Rangliste") {
                e.competitors.forEachIndexed { i, c ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text("${c.order ?: (i + 1)}.", color = pal.textDim, fontSize = 15.sp, modifier = Modifier.width(40.dp))
                        Text(c.name, color = pal.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(c.score ?: "", color = pal.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (m.stats.isNotEmpty()) Card("Wichtigste Werte") { m.stats.take(5).forEach { StatBar(it) } }
            if (m.incidents.isEmpty() && m.stats.isEmpty() && e.competitors.size <= 2)
                Card("Spielverlauf") { Text(if (e.state == "pre") "Das Spiel hat noch nicht begonnen." else "Die Quelle liefert für dieses Spiel keinen Verlauf.", color = pal.textDim, fontSize = 14.sp) }
        }
        Column(Modifier.weight(1f)) {
            if (m.info.isNotEmpty()) Card("Spielinfo") {
                m.info.forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(k, color = pal.textDim, fontSize = 14.sp, modifier = Modifier.width(150.dp))
                        Text(v, color = pal.text, fontSize = 14.sp)
                    }
                }
            }
            val forms: List<Pair<String, String>> = listOfNotNull(m.home, m.away).mapNotNull { t -> t.form?.let { f -> (t.short ?: t.name) to f } }
            if (forms.isNotEmpty()) Card("Form (letzte Spiele)") {
                forms.forEach { (n, f) -> Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(n, color = pal.text, fontSize = 14.sp, modifier = Modifier.width(150.dp), maxLines = 1)
                    f.forEach { r -> FormDot(r.toString()) }
                } }
            }
            m.series?.let { Card("Direktvergleich") { Text(it, color = pal.text, fontSize = 14.sp) } }
        }
    }
}

@Composable
private fun FormDot(r: String) {
    val (c, t) = when (r.uppercase()) { "W" -> Color(0xFF2BB673) to "S"; "L" -> Color(0xFFE5484D) to "N"; "D" -> Color(0xFF8A94A6) to "U"; else -> Color(0xFF555555) to r }
    Box(Modifier.padding(end = 6.dp).size(28.dp).background(c, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        Text(t, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun IncidentRow(i: Incident) {
    val pal = LocalUltraTheme.current.palette
    val text = "${i.kind.icon} ${i.player}" + (i.assist?.takeIf { i.kind == IncidentKind.GOAL || i.kind == IncidentKind.PENALTY }?.let { "  (Vorlage: $it)" } ?: "")
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (i.home) {
            Text(text, color = pal.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(i.clock, color = pal.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp), textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
            Text(i.clock, color = pal.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp), textAlign = TextAlign.Center)
            Text(text, color = pal.text, fontSize = 15.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun Ticker(m: MatchCenter) {
    val pal = LocalUltraTheme.current.palette
    m.ticker.forEach { t ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(pal.surface, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(t.clock ?: "", color = pal.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
            Text(t.icon, fontSize = 15.sp, modifier = Modifier.width(34.dp))
            Text(t.text, color = pal.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        }
    }
}

private fun num(s: String): Double? = Regex("""-?\d+(?:[.,]\d+)?""").find(s)?.value?.replace(',', '.')?.toDoubleOrNull()

@Composable
private fun StatBar(s: StatRow) {
    val pal = LocalUltraTheme.current.palette
    val hv = num(s.home); val av = num(s.away)
    val total = (hv ?: 0.0) + (av ?: 0.0)
    val f = if (hv != null && av != null && total > 0 && hv >= 0 && av >= 0) (hv / total).toFloat() else 0.5f
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row {
            Text(s.home, color = pal.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp))
            Text(s.label, color = pal.textDim, fontSize = 14.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text(s.away, color = pal.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp), textAlign = TextAlign.End)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp)) {
            Box(Modifier.weight(0.001f + f).fillMaxHeight().background(pal.primary, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)))
            Spacer(Modifier.width(3.dp))
            Box(Modifier.weight(0.001f + (1f - f)).fillMaxHeight().background(Color(0xFFE5484D).copy(alpha = 0.85f), RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
        }
    }
}

@Composable
private fun Stats(m: MatchCenter) {
    val pal = LocalUltraTheme.current.palette
    Card("Teamstatistik") {
        Row(Modifier.padding(bottom = 6.dp)) {
            Text(m.home?.name ?: "Heim", color = pal.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(m.away?.name ?: "Gast", color = Color(0xFFE5484D), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        m.stats.forEach { StatBar(it) }
    }
}

@Composable
private fun Lineups(m: MatchCenter) {
    val pal = LocalUltraTheme.current.palette
    val home = m.lineups.firstOrNull { it.home } ?: m.lineups.getOrNull(0)
    val away = m.lineups.firstOrNull { it !== home }
    Pitch(home, away)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOfNotNull(home, away).forEach { l ->
            Column(Modifier.weight(1f)) {
                Card("${l.team}${l.formation?.let { " · $it" } ?: ""}") {
                    l.starters.forEach { p -> PlayerLine(p.jersey, p.name, p.pos, if (p.subbedOut) "🔻 ausgewechselt" else null) }
                    if (l.bench.isNotEmpty()) {
                        Text("Bank", color = pal.textDim, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                        l.bench.forEach { p -> PlayerLine(p.jersey, p.name, p.pos, if (p.subbedIn) "🔺 eingewechselt" else null) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerLine(jersey: String?, name: String, pos: String?, note: String?) {
    val pal = LocalUltraTheme.current.palette
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(jersey ?: "", color = pal.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
        Text(name, color = pal.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
        pos?.let { Text(it, color = pal.textDim, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp)) }
        note?.let { Text(it, color = pal.textDim, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp)) }
    }
}

/** Spielfeld mit beiden Formationen: Heim links, Gast rechts. */
@Composable
private fun Pitch(home: Lineup?, away: Lineup?) {
    if (home == null && away == null) return
    val grass = Brush.horizontalGradient(listOf(Color(0xFF1E6B3A), Color(0xFF237A43), Color(0xFF1E6B3A), Color(0xFF237A43), Color(0xFF1E6B3A)))
    BoxWithConstraints(Modifier.fillMaxWidth().height(430.dp).background(grass, RoundedCornerShape(18.dp)).border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(18.dp))) {
        val w = maxWidth; val h = maxHeight
        // Mittellinie und Mittelkreis
        Box(Modifier.offset(x = w / 2 - 1.dp).width(2.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.5f)))
        Box(Modifier.offset(x = w / 2 - 60.dp, y = h / 2 - 60.dp).size(120.dp).border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape))
        listOfNotNull(home?.let { it to true }, away?.let { it to false }).forEach { (l, isHome) ->
            val lines = formationLines(l)
            lines.forEachIndexed { li, line ->
                // Linien von der eigenen Torlinie zur Mitte verteilt
                val fx = 0.05f + li * (0.42f / (lines.size - 1).coerceAtLeast(1))
                val x = if (isHome) w * fx else w * (1f - fx)
                line.forEachIndexed { pi, p ->
                    val fy = (pi + 1f) / (line.size + 1f)
                    val y = h * (if (isHome) fy else 1f - fy)
                    Column(Modifier.offset(x = x - 45.dp, y = y - 24.dp).width(90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(30.dp).background(if (isHome) Color(0xFFF2F4F8) else Color(0xFFE5484D), CircleShape)
                            .border(2.dp, Color.Black.copy(alpha = 0.3f), CircleShape), contentAlignment = Alignment.Center) {
                            Text(p.jersey ?: "", color = if (isHome) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                        }
                        Text(p.short + if (p.subbedOut) " 🔻" else "", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** Startelf in Formationsreihen aufteilen (Torwart + z. B. 4-2-3-1). */
private fun formationLines(l: Lineup): List<List<de.ostlertekker.ultraiptv.core.discover.LineupPlayer>> {
    val p = l.starters
    if (p.isEmpty()) return emptyList()
    val counts = l.formation?.split('-')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.sum() == p.size - 1 }
        ?: listOf(4, 4, 2).takeIf { p.size == 11 } ?: listOf(p.size - 1)
    val out = mutableListOf(listOf(p.first()))
    var i = 1
    counts.forEach { n -> out += p.subList(i, (i + n).coerceAtMost(p.size)); i += n }
    return out.filter { it.isNotEmpty() }
}

@Composable
private fun Players(m: MatchCenter) {
    val pal = LocalUltraTheme.current.palette
    m.players.forEach { t ->
        Card(t.team) {
            Row {
                Text("Spieler", color = pal.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(180.dp))
                t.labels.forEach { Text(it, color = pal.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center) }
            }
            t.rows.forEach { (n, v) ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(n, color = pal.text, fontSize = 13.sp, modifier = Modifier.width(180.dp), maxLines = 1)
                    t.labels.indices.forEach { i -> Text(v.getOrElse(i) { "" }, color = pal.text, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

@Composable
private fun Table(m: MatchCenter) {
    val pal = LocalUltraTheme.current.palette
    val st = m.standings ?: return
    val ids = setOfNotNull(m.home?.id, m.away?.id)
    Card(st.title ?: "Tabelle") {
        Row(Modifier.padding(bottom = 4.dp)) {
            Text("Team", color = pal.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            st.columns.forEach { Text(it, color = pal.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp), textAlign = TextAlign.Center) }
        }
        st.rows.forEach { r ->
            val hl = r.teamId != null && r.teamId in ids
            Row(Modifier.fillMaxWidth().background(if (hl) pal.primary.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(6.dp)).padding(vertical = 4.dp, horizontal = 4.dp)) {
                Text(r.team, color = pal.text, fontSize = 14.sp, fontWeight = if (hl) FontWeight.Black else FontWeight.Normal, modifier = Modifier.weight(1f), maxLines = 1)
                r.values.forEach { Text(it, color = pal.text, fontSize = 14.sp, modifier = Modifier.width(70.dp), textAlign = TextAlign.Center) }
            }
        }
    }
}

@Composable
private fun FormTab(m: MatchCenter) {
    val pal = LocalUltraTheme.current.palette
    m.series?.let { Card("Direktvergleich") { Text(it, color = pal.text, fontSize = 15.sp) } }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        m.form.forEach { f ->
            Column(Modifier.weight(1f)) {
                Card("Letzte Spiele · ${f.team}") {
                    f.games.forEach { g ->
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            FormDot(g.result)
                            Text(g.label, color = pal.text, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
