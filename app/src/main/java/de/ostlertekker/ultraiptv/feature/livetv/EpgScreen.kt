package de.ostlertekker.ultraiptv.feature.livetv

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.data.ContentRepository
import de.ostlertekker.ultraiptv.core.data.EpgRepository
import de.ostlertekker.ultraiptv.core.data.SyncRepository
import de.ostlertekker.ultraiptv.core.database.ChannelEntity
import de.ostlertekker.ultraiptv.core.database.EpgProgramEntity
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.UltraLoader
import de.ostlertekker.ultraiptv.core.ui.tvFocusable
import de.ostlertekker.ultraiptv.openPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val HALF_HOUR = 30 * 60_000L
private const val WINDOW = 3 * 3_600_000L

@HiltViewModel
class EpgViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val content: ContentRepository,
    private val epg: EpgRepository,
    private val sync: SyncRepository,
    private val notifications: de.ostlertekker.ultraiptv.core.notify.NotificationCenter,
) : ViewModel() {
    val message = MutableStateFlow<String?>(null)
    private var msgJob: kotlinx.coroutines.Job? = null
    /** Gesetzte Erinnerungen (Sender-ID + Startzeit) – für das Glocken-Symbol in der Programmübersicht. */
    val reminderKeys = MutableStateFlow<Set<String>>(emptySet())
    fun reminderKey(channelId: String, startMs: Long) = "$channelId@$startMs"

    fun toggleReminder(ch: ChannelEntity, prog: EpgProgramEntity) = viewModelScope.launch {
        val on = runCatching { notifications.toggleReminder(ch.id, ch.name, prog.title, prog.startMs) }.getOrNull()
        val t = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.GERMANY).format(java.util.Date(prog.startMs))
        message.value = when (on) { true -> trf("🔔 Erinnerung gesetzt: {0} ({1}, {2})", prog.title, t, ch.name); false -> trf("Erinnerung entfernt: {0}", prog.title); null -> tr("Erinnerung nicht möglich") }
        msgJob?.cancel(); msgJob = viewModelScope.launch { kotlinx.coroutines.delay(4_000); message.value = null }
    }
    fun jumpDay(days: Int) {
        val cal = java.util.Calendar.getInstance()
        val nowStart = floorHalfHour(System.currentTimeMillis())
        if (days == 0) { windowStart.value = nowStart; return }
        cal.timeInMillis = System.currentTimeMillis(); cal.add(java.util.Calendar.DAY_OF_YEAR, days)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 20); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        windowStart.value = cal.timeInMillis.coerceIn(nowStart - 7 * 86_400_000L, nowStart + 7 * 86_400_000L)
    }
    val category: String? = savedState.get<String>("category")
    val channels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val programs = MutableStateFlow<Map<String, List<EpgProgramEntity>>>(emptyMap())
    val windowStart = MutableStateFlow(floorHalfHour(System.currentTimeMillis()))
    val loading = MutableStateFlow(true)
    val syncState = sync.state

    init {
        viewModelScope.launch {
            notifications.upcomingReminders().collect { l -> reminderKeys.value = l.mapTo(HashSet()) { reminderKey(it.channelId, it.startMs) } }
        }
        viewModelScope.launch {
            channels.value = if (category != null) content.channelsInOnce(category).take(200) else content.channelsAllOnce(200)
            combine(channels, windowStart) { c, w -> c to w }.collectLatest { (c, w) ->
                programs.value = epg.programs(c.mapNotNull { it.epgId }, w, w + WINDOW)
                loading.value = false
            }
        }
    }

    fun shift(deltaMs: Long) {
        val now = floorHalfHour(System.currentTimeMillis())
        windowStart.value = (windowStart.value + deltaMs).coerceIn(now - 7 * 86_400_000L, now + 7 * 86_400_000L)
    }

    fun refresh() = viewModelScope.launch {
        loading.value = true
        sync.syncEpg()
        windowStart.value = windowStart.value + 1 // Neuberechnung anstoßen
        windowStart.value = windowStart.value - 1
    }

    companion object { fun floorHalfHour(ms: Long) = ms - ms % HALF_HOUR }
}

@Composable
fun EpgScreen(vm: EpgViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val channels by vm.channels.collectAsStateWithLifecycle()
    val programs by vm.programs.collectAsStateWithLifecycle()
    val start by vm.windowStart.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<EpgProgramEntity?>(null) }
    val reminders by vm.reminderKeys.collectAsStateWithLifecycle()
    // Blättern mit ◀/▶ am Rand der Programmzeile: danach Fokus in derselben Senderzeile (true = erste, false = letzte Sendung)
    var pendingFocus by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        val msg by vm.message.collectAsStateWithLifecycle()
        val dayFmt = remember { java.text.SimpleDateFormat("EEEE, d. MMMM", java.util.Locale.GERMANY) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("TV-Programm", color = p.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text((vm.category ?: tr("Alle Sender")) + " · " + dayFmt.format(java.util.Date(start)), color = p.textDim, fontSize = 13.sp)
            }
            TvButton(tr("Gestern"), onClick = { vm.jumpDay(-1) })
            TvButton("◀", onClick = { vm.shift(-HALF_HOUR * 2) })
            TvButton(tr("Jetzt"), onClick = { vm.jumpDay(0) }, primary = true)
            TvButton("▶", onClick = { vm.shift(HALF_HOUR * 2) })
            TvButton(tr("Morgen"), onClick = { vm.jumpDay(1) })
            TvButton("↻", onClick = { vm.refresh() })
        }
        // Detailbereich der fokussierten Sendung
        Box(Modifier.fillMaxWidth().height(86.dp).padding(vertical = 8.dp)) {
            val d = detail
            if (msg != null) Text(msg ?: "", color = p.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopEnd).background(p.surface, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
            if (d != null) Column {
                Text("${formatTime(d.startMs)}–${formatTime(d.endMs)}  ${d.title}", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(d.description ?: "", color = p.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when { d.endMs <= now -> tr("OK: als Catch-up ansehen (wenn der Sender ein Archiv hat)"); d.startMs > now -> tr("OK: Erinnerung setzen oder entfernen · Lang drücken: Sender jetzt ansehen"); else -> tr("OK: jetzt ansehen") } +
                    tr(" · ◀ ▶ am Rand: Zeit weiterblättern"), color = p.primary, fontSize = 12.sp)
            } else if (sync.running) Text(sync.message, color = p.textDim)
        }
        if (loading) { UltraLoader(Modifier.fillMaxWidth(), tr("TV-Programm wird geladen …")); return@Column }
        if (programs.isEmpty()) {
            Text(tr("Keine EPG-Daten vorhanden. EPG-Adresse in den Einstellungen prüfen oder „EPG aktualisieren“ wählen."), color = p.textDim)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val nameW = 230.dp
            val gridW = maxWidth - nameW
            val pxPerMs = gridW.value / WINDOW
            Column {
                // Zeitleiste
                Row(Modifier.fillMaxWidth().height(26.dp)) {
                    Spacer(Modifier.width(nameW))
                    Box(Modifier.width(gridW)) {
                        for (i in 0 until 6) {
                            Text(formatTime(start + i * HALF_HOUR), color = p.textDim, fontSize = 12.sp,
                                modifier = Modifier.offset(x = (i * HALF_HOUR * pxPerMs).dp))
                        }
                        if (now in start until start + WINDOW) {
                            Box(Modifier.offset(x = ((now - start) * pxPerMs).dp).width(2.dp).fillMaxHeight().background(p.primary))
                        }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    items(channels, key = { it.id }) { ch ->
                        Row(Modifier.fillMaxWidth().height(56.dp)) {
                            Row(Modifier.width(nameW).fillMaxHeight().background(p.surface, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (ch.number > 0) "${ch.number}" else "", color = p.textDim, fontSize = 12.sp, modifier = Modifier.width(26.dp))
                                Box(Modifier.width(44.dp).height(30.dp), contentAlignment = Alignment.Center) {
                                    if (!ch.logo.isNullOrBlank()) coil.compose.AsyncImage(ch.logo, null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                }
                                Text(ch.name, color = p.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Box(Modifier.width(gridW).fillMaxHeight()) {
                                val list = ch.epgId?.let { programs[it] }.orEmpty()
                                // Sichtbare Sendungen dieser Zeile (für Blättern am Rand)
                                val visible = list.filter { prog ->
                                    val s0 = maxOf(prog.startMs, start); val e0 = minOf(prog.endMs, start + WINDOW)
                                    e0 > s0 && ((e0 - s0) * pxPerMs).dp - 4.dp > 6.dp
                                }
                                val pf = pendingFocus
                                val target = if (pf != null && pf.first == ch.id) (if (pf.second) visible.firstOrNull() else visible.lastOrNull()) else null
                                val targetReq = remember(ch.id) { androidx.compose.ui.focus.FocusRequester() }
                                if (pf != null && pf.first == ch.id) LaunchedEffect(start, programs) {
                                    kotlinx.coroutines.delay(30)
                                    runCatching { targetReq.requestFocus() }
                                    pendingFocus = null
                                }
                                fun edgeKeys(first: Boolean, last: Boolean) = Modifier.onPreviewKeyEvent { ev ->
                                    if (ev.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (ev.key) {
                                        androidx.compose.ui.input.key.Key.DirectionRight -> if (last) { pendingFocus = ch.id to true; vm.shift(HALF_HOUR * 2); true } else false
                                        androidx.compose.ui.input.key.Key.DirectionLeft -> if (first) { pendingFocus = ch.id to false; vm.shift(-HALF_HOUR * 2); true } else false
                                        else -> false
                                    }
                                }
                                if (list.isEmpty() || visible.isEmpty()) {
                                    ProgramBlock(tr("Keine Daten"), false, Modifier.padding(start = 4.dp).width(gridW - 4.dp).then(edgeKeys(true, true)),
                                        { context.openPlayer(ContentKind.LIVE, ch.id, category = ch.category) },
                                        focusRequester = if (pf != null && pf.first == ch.id) targetReq else null) { }
                                }
                                visible.forEachIndexed { idx, prog ->
                                    val s = maxOf(prog.startMs, start)
                                    val e = minOf(prog.endMs, start + WINDOW)
                                    run {
                                        val x = ((s - start) * pxPerMs).dp
                                        val w = ((e - s) * pxPerMs).dp - 4.dp
                                        val catchup = prog.endMs <= now && ch.archiveDays > 0 && prog.startMs > now - ch.archiveDays * 86_400_000L
                                        val hasReminder = prog.startMs > now && vm.reminderKey(ch.id, prog.startMs) in reminders
                                        val label = (if (hasReminder) "🔔 " else "") + (if (catchup) "↺ " else "") + prog.title
                                        ProgramBlock(label, prog.startMs <= now && prog.endMs > now,
                                            Modifier.offset(x = x + 4.dp).width(w).then(edgeKeys(idx == 0, idx == visible.lastIndex)),
                                            {
                                                when {
                                                    prog.startMs > now -> vm.toggleReminder(ch, prog)
                                                    prog.endMs <= now -> if (catchup) context.openPlayer(ContentKind.LIVE, ch.id, category = ch.category,
                                                        catchupStart = prog.startMs, catchupEnd = prog.endMs) else vm.message.value = tr("Für diese Sendung gibt es kein Archiv.")
                                                    else -> context.openPlayer(ContentKind.LIVE, ch.id, category = ch.category)
                                                }
                                            }, past = prog.endMs <= now, narrow = w < 130.dp,
                                            onLongClick = if (prog.startMs > now) ({ context.openPlayer(ContentKind.LIVE, ch.id, category = ch.category) }) else null,
                                            focusRequester = if (prog === target) targetReq else null) { f -> if (f) detail = prog }
                                    }
                                }
                            }
                        }
                    }
                }
                // Aktuelle Uhrzeit als durchgehende senkrechte Linie
                if (now in start until start + WINDOW) {
                    Box(Modifier.offset(x = nameW + ((now - start) * pxPerMs).dp).width(2.dp).fillMaxHeight().background(p.primary.copy(alpha = 0.85f)))
                }
                }
            }
        }
    }
}

@Composable
private fun ProgramBlock(title: String, live: Boolean, modifier: Modifier, onClick: () -> Unit, past: Boolean = false, narrow: Boolean = false,
                         onLongClick: (() -> Unit)? = null, focusRequester: androidx.compose.ui.focus.FocusRequester? = null, onFocus: (Boolean) -> Unit) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.fillMaxHeight()
            .tvFocusable(shape, onClick, onLongClick = onLongClick, focusRequester = focusRequester, scaleOnFocus = false, onFocus = { focused = it; onFocus(it) })
            .background(if (focused) p.primary else if (live) p.surfaceFocused else if (past) p.surface.copy(alpha = 0.55f) else p.surface, shape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Schmale Sendungen: einzeilig mit „…“ (Breite wird übergeben – kein teures Nachmessen je Block)
        Text(title, color = if (focused) Color.Black else p.text, fontSize = if (narrow) 12.sp else 13.sp,
            maxLines = if (narrow) 1 else 2, softWrap = !narrow, overflow = TextOverflow.Ellipsis,
            fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal)
    }
}
