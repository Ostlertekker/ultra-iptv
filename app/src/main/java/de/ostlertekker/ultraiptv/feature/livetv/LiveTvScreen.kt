package de.ostlertekker.ultraiptv.feature.livetv

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.audio.AudioExperienceEngine
import de.ostlertekker.ultraiptv.core.data.ContentRepository
import de.ostlertekker.ultraiptv.core.data.EpgRepository
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.database.ChannelEntity
import de.ostlertekker.ultraiptv.core.database.EpgProgramEntity
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.device.DeviceCapabilityManager
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.model.DeviceTier
import de.ostlertekker.ultraiptv.core.security.StreamResolver
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.TvChip
import de.ostlertekker.ultraiptv.core.ui.tvFocusable
import de.ostlertekker.ultraiptv.openPlayer
import de.ostlertekker.ultraiptv.ui.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

const val CAT_FAVORITES = "★ Favoriten"
const val CAT_RECENT = "Zuletzt gesehen"

fun formatTime(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val content: ContentRepository,
    private val user: UserRepository,
    private val epg: EpgRepository,
    private val settingsRepo: SettingsRepository,
    private val resolver: StreamResolver,
    device: DeviceCapabilityManager,
    val sounds: AudioExperienceEngine,
) : ViewModel() {

    val selected = MutableStateFlow<String?>(null)
    val sortByName = MutableStateFlow(false)

    val categories: StateFlow<List<String>> = content.channelCategories()
        .map { listOf(CAT_FAVORITES, CAT_RECENT) + it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteIds: StateFlow<Set<String>> = user.favoriteIds(ContentKind.LIVE).map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val channels: StateFlow<List<ChannelEntity>> = combine(selected, categories) { sel, cats -> sel ?: cats.getOrNull(2) ?: CAT_FAVORITES }
        .flatMapLatest { cat ->
            when (cat) {
                CAT_FAVORITES -> user.favoriteIds(ContentKind.LIVE).flatMapLatest { content.channelsByIds(it) }
                CAT_RECENT -> user.recentChannels.flatMapLatest { list -> content.channelsByIds(list.map { it.itemId }) }
                else -> content.channelsIn(cat)
            }
        }
        .combine(sortByName) { list, byName -> if (byName) list.sortedBy { it.name.lowercase() } else list }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val programs = MutableStateFlow<Map<String, List<EpgProgramEntity>>>(emptyMap())
    val focused = MutableStateFlow<ChannelEntity?>(null)
    val previewUrl = MutableStateFlow<String?>(null)
    val previewAllowed: Boolean = device.tier != DeviceTier.ENTRY

    init {
        viewModelScope.launch {
            channels.collect { list ->
                val now = System.currentTimeMillis()
                programs.value = epg.programs(list.mapNotNull { it.epgId }.take(800), now, now + 4 * 3_600_000L)
            }
        }
        viewModelScope.launch {
            focused.collect { ch ->
                previewUrl.value = null
                if (ch == null || !previewAllowed) return@collect
                val s = settingsRepo.current()
                if (!s.livePreview) return@collect
                delay(1300)
                previewUrl.value = runCatching { resolver.resolve(ch.streamUrl, s.liveFormat) }.getOrNull()
            }
        }
    }

    fun nowNext(ch: ChannelEntity?): List<EpgProgramEntity> {
        val id = ch?.epgId ?: return emptyList()
        val now = System.currentTimeMillis()
        return programs.value[id].orEmpty().filter { it.endMs > now }.take(2)
    }

    fun toggleFavorite(ch: ChannelEntity) = viewModelScope.launch { user.toggleFavorite(ContentKind.LIVE, ch.id) }

    fun currentCategory(): String = selected.value ?: categories.value.getOrNull(2) ?: CAT_FAVORITES

    fun open(context: Context, ch: ChannelEntity) {
        previewUrl.value = null
        context.openPlayer(ContentKind.LIVE, ch.id, category = currentCategory())
    }

    suspend fun hasEpg(): Boolean = epg.count() > 0
    suspend fun liveFormat(): String = settingsRepo.settings.first().liveFormat
}

@Composable
fun LiveTvScreen(nav: NavController, vm: LiveTvViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val cats by vm.categories.collectAsStateWithLifecycle()
    val sel by vm.selected.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val favs by vm.favoriteIds.collectAsStateWithLifecycle()
    val programs by vm.programs.collectAsStateWithLifecycle()
    val focused by vm.focused.collectAsStateWithLifecycle()
    val previewUrl by vm.previewUrl.collectAsStateWithLifecycle()
    val sortByName by vm.sortByName.collectAsStateWithLifecycle()
    val current = sel ?: cats.getOrNull(2) ?: CAT_FAVORITES
    val now = System.currentTimeMillis()

    Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // Kategorien
        LazyColumn(Modifier.width(230.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text("LIVE TV", color = p.text, fontSize = 26.sp, fontWeight = theme.spec.titleWeight, fontFamily = theme.spec.fontFamily,
                modifier = Modifier.padding(bottom = 10.dp)) }
            items(cats, key = { it }) { c ->
                CategoryItem(c, c == current) { vm.selected.value = c }
            }
        }
        // Senderliste
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(current, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                TvChip(if (sortByName) "Sortierung: A–Z" else "Sortierung: Nummer", false, { vm.sortByName.value = !sortByName })
                TvChip("TV-Guide", false, { nav.navigate(Routes.epg(current.takeIf { it != CAT_FAVORITES && it != CAT_RECENT })) })
            }
            Spacer(Modifier.height(10.dp))
            if (channels.isEmpty()) {
                Text(if (current == CAT_FAVORITES) "Noch keine Lieblingssender. Sender auswählen und „Favorit“ drücken." else "Keine Sender in dieser Kategorie.",
                    color = p.textDim, fontSize = 15.sp)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(channels, key = { it.id }) { ch ->
                    val nowProg = ch.epgId?.let { id -> programs[id]?.firstOrNull { it.startMs <= now && it.endMs > now } }
                    ChannelItem(ch, nowProg, ch.id in favs,
                        onFocus = { vm.focused.value = ch },
                        onClick = { vm.open(context, ch) },
                        onLongClick = { vm.toggleFavorite(ch) })
                }
            }
        }
        // Vorschau / Details
        Column(Modifier.width(380.dp).fillMaxHeight()) {
            val shape = RoundedCornerShape(theme.spec.corner.coerceAtMost(18.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Color.Black), contentAlignment = Alignment.Center) {
                val url = previewUrl
                if (url != null) LivePreview(url, vm.sounds)
                else if (focused?.logo != null) AsyncImage(focused?.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(30.dp))
                else Text(if (vm.previewAllowed) "Vorschau" else "Vorschau auf diesem Gerät deaktiviert", color = p.textDim, fontSize = 13.sp)
            }
            Spacer(Modifier.height(14.dp))
            val ch = focused
            if (ch != null) {
                Text(ch.name, color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(ch.category, color = p.textDim, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                val nn = vm.nowNext(ch)
                if (nn.isEmpty()) Text("Keine Programminformationen", color = p.textDim, fontSize = 14.sp)
                nn.forEachIndexed { i, prog ->
                    Text((if (i == 0) "JETZT  " else "DANACH  ") + "${formatTime(prog.startMs)}–${formatTime(prog.endMs)}",
                        color = if (i == 0) p.primary else p.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    Text(prog.title, color = p.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (i == 0) {
                        val prg = ((now - prog.startMs).toFloat() / (prog.endMs - prog.startMs)).coerceIn(0f, 1f)
                        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).background(p.surface)) {
                            Box(Modifier.fillMaxWidth(prg).height(3.dp).background(p.primary))
                        }
                        prog.description?.let { Text(it, color = p.textDim, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("▶ Ansehen", onClick = { vm.open(context, ch) }, primary = true)
                    TvButton(if (ch.id in favs) "★ Entfernen" else "☆ Favorit", onClick = { vm.toggleFavorite(ch) })
                }
            }
        }
    }
}

@Composable
private fun CategoryItem(name: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(theme.spec.corner.coerceAtMost(14.dp))
    Box(
        Modifier.fillMaxWidth()
            .tvFocusable(shape, onClick, scaleOnFocus = false, onFocus = { focused = it })
            .background(if (focused) p.surfaceFocused else if (selected) p.surface else Color.Transparent, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(name, color = if (selected || focused) p.text else p.textDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun ChannelItem(ch: ChannelEntity, now: EpgProgramEntity?, favorite: Boolean, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(theme.spec.corner.coerceAtMost(14.dp))
    Row(
        Modifier.fillMaxWidth()
            .tvFocusable(shape, onClick, onLongClick, scaleOnFocus = false, onFocus = { focused = it; if (it) onFocus() })
            .background(if (focused) p.surfaceFocused else p.surface, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (ch.number > 0) ch.number.toString() else "", color = p.textDim, fontSize = 13.sp, modifier = Modifier.width(44.dp))
        Box(Modifier.size(64.dp, 40.dp), contentAlignment = Alignment.Center) {
            if (ch.logo != null) AsyncImage(ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null) Text("${formatTime(now.startMs)}  ${now.title}", color = p.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (favorite) Text("★", color = p.primary, fontSize = 18.sp)
    }
}

/** Kleine Live-Vorschau mit eigenem, leichtem Player. Pausiert, sobald die Ansicht nicht sichtbar ist. */
@Composable
private fun LivePreview(url: String, sounds: AudioExperienceEngine) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url)); volume = 0.7f; prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) {
        sounds.suppressed = true
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> player.stop()
                Lifecycle.Event.ON_START -> { player.prepare(); player.playWhenReady = true }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            player.release()
            sounds.suppressed = false
        }
    }
    AndroidView(factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = player } },
        update = { it.player = player }, modifier = Modifier.fillMaxSize())
}
