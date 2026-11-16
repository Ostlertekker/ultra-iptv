package de.ostlertekker.ultraiptv.feature.livetv

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
import androidx.compose.ui.Modifier
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
) : ViewModel() {
    val category: String? = savedState.get<String>("category")
    val channels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val programs = MutableStateFlow<Map<String, List<EpgProgramEntity>>>(emptyMap())
    val windowStart = MutableStateFlow(floorHalfHour(System.currentTimeMillis()))
    val loading = MutableStateFlow(true)
    val syncState = sync.state

    init {
        viewModelScope.launch {
            channels.value = if (category != null) content.channelsInOnce(category).take(200) else content.channelsAllOnce(200)
            combine(channels, windowStart) { c, w -> c to w }.collect { (c, w) ->
                programs.value = epg.programs(c.mapNotNull { it.epgId }, w, w + WINDOW)
                loading.value = false
            }
        }
    }

    fun shift(deltaMs: Long) {
        val now = floorHalfHour(System.currentTimeMillis())
        windowStart.value = (windowStart.value + deltaMs).coerceIn(now - 3 * 3_600_000L, now + 2 * 86_400_000L)
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
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("TV-GUIDE", color = p.text, fontSize = 26.sp, fontWeight = theme.spec.titleWeight, fontFamily = theme.spec.fontFamily)
                Text(vm.category ?: "Alle Sender", color = p.textDim, fontSize = 13.sp)
            }
            TvButton("◀ Früher", onClick = { vm.shift(-HALF_HOUR * 2) })
            TvButton("Jetzt", onClick = { vm.windowStart.value = EpgViewModel.floorHalfHour(System.currentTimeMillis()) })
            TvButton("Später ▶", onClick = { vm.shift(HALF_HOUR * 2) })
            TvButton("EPG aktualisieren", onClick = { vm.refresh() })
        }
        // Detailbereich der fokussierten Sendung
        Box(Modifier.fillMaxWidth().height(86.dp).padding(vertical = 8.dp)) {
            val d = detail
            if (d != null) Column {
                Text("${formatTime(d.startMs)}–${formatTime(d.endMs)}  ${d.title}", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(d.description ?: "", color = p.textDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else if (sync.running) Text(sync.message, color = p.textDim)
        }
        if (loading) { UltraLoader(Modifier.fillMaxWidth(), "TV-Programm wird geladen …"); return@Column }
        if (programs.isEmpty()) {
            Text("Keine EPG-Daten vorhanden. EPG-Adresse in den Einstellungen prüfen oder „EPG aktualisieren“ wählen.", color = p.textDim)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val nameW = 200.dp
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
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    items(channels, key = { it.id }) { ch ->
                        Row(Modifier.fillMaxWidth().height(56.dp)) {
                            Box(Modifier.width(nameW).fillMaxHeight().background(p.surface, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
                                contentAlignment = Alignment.CenterStart) {
                                Text(ch.name, color = p.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Box(Modifier.width(gridW).fillMaxHeight()) {
                                val list = ch.epgId?.let { programs[it] }.orEmpty()
                                if (list.isEmpty()) {
                                    ProgramBlock("Keine Daten", false, Modifier.padding(start = 4.dp).width(gridW - 4.dp), { context.openPlayer(ContentKind.LIVE, ch.id, category = ch.category) }) { }
                                }
                                list.forEach { prog ->
                                    val s = maxOf(prog.startMs, start)
                                    val e = minOf(prog.endMs, start + WINDOW)
                                    if (e > s) {
                                        val x = ((s - start) * pxPerMs).dp
                                        val w = ((e - s) * pxPerMs).dp - 4.dp
                                        if (w > 6.dp) ProgramBlock(prog.title, prog.startMs <= now && prog.endMs > now,
                                            Modifier.offset(x = x + 4.dp).width(w),
                                            { context.openPlayer(ContentKind.LIVE, ch.id, category = ch.category) }) { f -> if (f) detail = prog }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgramBlock(title: String, live: Boolean, modifier: Modifier, onClick: () -> Unit, onFocus: (Boolean) -> Unit) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.fillMaxHeight()
            .tvFocusable(shape, onClick, scaleOnFocus = false, onFocus = { focused = it; onFocus(it) })
            .background(if (focused) p.primary else if (live) p.surfaceFocused else p.surface, shape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints {
            // Schmale Sendungen: einzeilig mit „…“ statt Wörter mitten im Wort umzubrechen
            val narrow = maxWidth < 130.dp
            Text(title, color = if (focused) Color.Black else p.text, fontSize = if (narrow) 12.sp else 13.sp,
                maxLines = if (narrow) 1 else 2, softWrap = !narrow, overflow = TextOverflow.Ellipsis,
                fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}
