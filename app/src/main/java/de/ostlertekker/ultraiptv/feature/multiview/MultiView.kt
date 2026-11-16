package de.ostlertekker.ultraiptv.feature.multiview

import android.content.Context
import android.content.Intent
import android.media.MediaCodecList
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ostlertekker.ultraiptv.core.data.ContentRepository
import de.ostlertekker.ultraiptv.core.data.EpgRepository
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.database.ChannelEntity
import de.ostlertekker.ultraiptv.core.device.DeviceCapabilityManager
import de.ostlertekker.ultraiptv.core.discover.DiscoverRepository
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.player.PlayerEngine
import de.ostlertekker.ultraiptv.core.security.StreamResolver
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.TvChip
import de.ostlertekker.ultraiptv.core.ui.UltraThemeHost
import de.ostlertekker.ultraiptv.core.ui.tvFocusable
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.openPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Wie viele Videos das Gerät gleichzeitig in Hardware decodieren kann (Herstellerangabe des Decoders). */
fun maxHardwareDecoders(): Int = try {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals("video/avc", true) } }
        .filter { ci -> val n = ci.name.lowercase(); !n.startsWith("omx.google.") && !n.startsWith("c2.android.") && !n.contains("sw") }
        .maxOfOrNull { ci -> ci.getCapabilitiesForType("video/avc").maxSupportedInstances } ?: 2
} catch (_: Exception) { 2 }.coerceIn(1, 16)

data class Cell(val channel: ChannelEntity?, val now: String? = null, val error: String? = null, val active: Boolean = false)

data class MultiViewState(
    val layout: Int = 4,
    val cells: List<Cell> = emptyList(),
    val focused: Int = 0,
    val decoderLimit: Int = 2,
    val picker: Int? = null,
    val allChannels: List<ChannelEntity> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class MultiViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: androidx.lifecycle.SavedStateHandle,
    private val content: ContentRepository,
    private val user: UserRepository,
    private val epg: EpgRepository,
    private val discover: DiscoverRepository,
    private val resolver: StreamResolver,
    private val settings: SettingsRepository,
    engine: PlayerEngine,
) : ViewModel() {
    private val _state = MutableStateFlow(MultiViewState(decoderLimit = maxHardwareDecoders()))
    val state: StateFlow<MultiViewState> = _state.asStateFlow()

    /** Ein ExoPlayer pro aktiver Zelle; Index = Zellenindex. */
    val players = HashMap<Int, ExoPlayer>()
    private val lru = ArrayList<Int>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    init {
        engine.stop() // Haupt-Player gibt seinen Decoder frei
        val ids = savedState.get<ArrayList<String>>(MultiViewActivity.EXTRA_IDS) ?: arrayListOf()
        val compare = savedState.get<String>(MultiViewActivity.EXTRA_COMPARE)
        viewModelScope.launch {
            val chans = when {
                compare != null -> discover.comparisonFor(compare, System.currentTimeMillis())
                ids.isNotEmpty() -> ids.mapNotNull { content.channel(it) }
                else -> {
                    val fav = user.favoriteIds(ContentKind.LIVE).first().take(4).mapNotNull { content.channel(it) }
                    fav.ifEmpty { content.channelsAllOnce(4) }
                }
            }
            val layout = when { chans.size <= 1 -> 1; chans.size == 2 -> 2; chans.size <= 4 -> 4; chans.size <= 6 -> 6; chans.size <= 9 -> 9; else -> 16 }
            _state.update { it.copy(layout = layout, cells = List(layout) { i -> Cell(chans.getOrNull(i)) }, loading = false) }
            for (i in 0 until layout) if (chans.getOrNull(i) != null) activate(i)
            _state.update { it.copy(allChannels = content.channelsAllOnce(5000)) }
            while (true) { refreshEpg(); delay(60_000) }
        }
    }

    private suspend fun refreshEpg() {
        val cells = _state.value.cells
        val updated = cells.map { c -> c.channel?.let { ch -> c.copy(now = runCatching { epg.nowNext(ch.epgId).firstOrNull { it.startMs <= System.currentTimeMillis() }?.title }.getOrNull()) } ?: c }
        _state.update { it.copy(cells = updated) }
    }

    private fun maxCellSize(layout: Int): Pair<Int, Int> = when {
        layout <= 1 -> Int.MAX_VALUE to Int.MAX_VALUE
        layout <= 4 -> 1280 to 720
        layout <= 9 -> 854 to 480
        else -> 640 to 360
    }

    /** Startet die Wiedergabe in Zelle [i]; ist das Decoder-Limit erreicht, wird die am längsten nicht genutzte Zelle angehalten. */
    fun activate(i: Int) {
        val st = _state.value
        val ch = st.cells.getOrNull(i)?.channel ?: return
        if (players.containsKey(i)) { touch(i); return }
        if (players.size >= st.decoderLimit) {
            val victim = lru.firstOrNull { it != i } ?: return
            release(victim)
        }
        viewModelScope.launch {
            val fmt = settings.current().liveFormat
            val url = runCatching { resolver.resolve(ch.streamUrl, fmt) }.getOrNull()
            if (url == null) { setCell(i) { it.copy(error = "Stream nicht verfügbar") }; return@launch }
            val (w, h) = maxCellSize(st.layout)
            val selector = DefaultTrackSelector(context).apply { setParameters(buildUponParameters().setMaxVideoSize(w, h)) }
            val p = ExoPlayer.Builder(context)
                .setTrackSelector(selector)
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(4000, 15000, 1000, 2000).build())
                .build()
            p.volume = if (i == _state.value.focused) 1f else 0f
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    setCell(i) { it.copy(error = "Fehler – neuer Versuch …") }
                    viewModelScope.launch { delay(4000); if (players[i] === p) { p.prepare(); p.play() } }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) setCell(i) { it.copy(error = null) }
                    if (state == Player.STATE_ENDED) { p.seekToDefaultPosition(); p.prepare(); p.play() } // Live-Stream neu verbinden
                }
            })
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare(); p.play()
            players[i] = p
            touch(i)
            setCell(i) { it.copy(active = true, error = null) }
            _version.update { it + 1 }
        }
    }

    private fun touch(i: Int) { lru.remove(i); lru.add(i) }

    private fun release(i: Int) {
        players.remove(i)?.release()
        lru.remove(i)
        setCell(i) { it.copy(active = false) }
        _version.update { it + 1 }
    }

    private fun setCell(i: Int, f: (Cell) -> Cell) = _state.update { s -> s.copy(cells = s.cells.mapIndexed { idx, c -> if (idx == i) f(c) else c }) }

    /** Ton folgt dem Fokus – nur eine Zelle ist hörbar. */
    fun focus(i: Int) {
        _state.update { it.copy(focused = i) }
        players.forEach { (idx, p) -> p.volume = if (idx == i) 1f else 0f }
        if (!players.containsKey(i)) activate(i)
    }

    fun setLayout(n: Int) {
        val chans = _state.value.cells.mapNotNull { it.channel }
        (players.keys.toList()).forEach { release(it) }
        _state.update { it.copy(layout = n, cells = List(n) { i -> Cell(chans.getOrNull(i)) }, focused = 0) }
        for (i in 0 until n) if (chans.getOrNull(i) != null) activate(i)
        viewModelScope.launch { refreshEpg() }
    }

    fun openPicker(i: Int) = _state.update { it.copy(picker = i) }
    fun closePicker() = _state.update { it.copy(picker = null) }

    fun assign(i: Int, ch: ChannelEntity) {
        release(i)
        setCell(i) { Cell(ch) }
        closePicker()
        activate(i)
        viewModelScope.launch { refreshEpg() }
    }

    override fun onCleared() {
        players.values.forEach { it.release() }
        players.clear()
        super.onCleared()
    }
}

@AndroidEntryPoint
class MultiViewActivity : ComponentActivity() {
    @Inject lateinit var device: DeviceCapabilityManager
    @Inject lateinit var settingsRepo: SettingsRepository
    private val vm: MultiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val s by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = null)
            val settings = s
            if (settings == null) Box(Modifier.fillMaxSize().background(Color.Black))
            else UltraThemeHost(settings, device.tier, null) { MultiViewScreen(vm, onExit = { finish() }) }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.players.values.forEach { it.pause() }
    }

    override fun onStart() {
        super.onStart()
        vm.players.values.forEach { it.play() }
    }

    companion object {
        const val EXTRA_IDS = "ids"
        const val EXTRA_COMPARE = "compare"

        fun open(context: Context, channelIds: List<String>) =
            context.startActivity(Intent(context, MultiViewActivity::class.java).putStringArrayListExtra(EXTRA_IDS, ArrayList(channelIds.take(16))))

        /** Channel Comparison: derselbe Sender/dieselbe Sendung auf allen verfügbaren Varianten. */
        fun openWithComparison(context: Context, channelId: String) =
            context.startActivity(Intent(context, MultiViewActivity::class.java).putExtra(EXTRA_COMPARE, channelId))
    }
}

@Composable
fun MultiViewScreen(vm: MultiViewModel, onExit: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val version by vm.version.collectAsStateWithLifecycle()
    val p = LocalUltraTheme.current.palette
    val context = androidx.compose.ui.platform.LocalContext.current
    val firstFocus = remember { FocusRequester() }
    androidx.activity.compose.BackHandler { if (st.picker != null) vm.closePicker() else onExit() }
    LaunchedEffect(st.loading) { if (!st.loading) { delay(200); runCatching { firstFocus.requestFocus() } } }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MULTI-VIEW", color = p.primary, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text("${vm.players.size} aktiv · Gerät decodiert bis zu ${st.decoderLimit} gleichzeitig", color = p.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            listOf(1, 2, 4, 6, 9, 16).forEach { n -> TvChip("$n", st.layout == n, { vm.setLayout(n) }) }
        }
        val cols = when (st.layout) { 1 -> 1; 2, 4 -> 2; 6, 9 -> 3; else -> 4 }
        val rows = (st.layout + cols - 1) / cols
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in 0 until rows) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until cols) {
                    val i = r * cols + c
                    if (i < st.layout) {
                        val cell = st.cells.getOrNull(i) ?: Cell(null)
                        CellView(vm, i, cell, version, st.focused == i, Modifier.weight(1f).fillMaxHeight(), if (i == 0) firstFocus else null,
                            onOpen = { cell.channel?.let { ch -> context.openPlayer(ContentKind.LIVE, ch.id, category = ch.category) } },
                            onPick = { vm.openPicker(i) })
                    } else Spacer(Modifier.weight(1f))
                }
            }
        }
        Text("OK = Vollbild · Lange OK / Menü = Sender wählen · Ton folgt der Auswahl", color = p.textDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
    st.picker?.let { idx -> ChannelPicker(st.allChannels, onPick = { vm.assign(idx, it) }, onClose = { vm.closePicker() }) }
}

@Composable
private fun CellView(vm: MultiViewModel, i: Int, cell: Cell, version: Int, focused: Boolean, modifier: Modifier, focusRequester: FocusRequester?, onOpen: () -> Unit, onPick: () -> Unit) {
    val p = LocalUltraTheme.current.palette
    val shape = RoundedCornerShape(8.dp)
    Box(modifier
        .tvFocusable(shape, onClick = onOpen, onLongClick = onPick, focusRequester = focusRequester, scaleOnFocus = false, onFocus = { if (it) vm.focus(i) })
        .border(if (focused) 3.dp else 1.dp, if (focused) p.primary else Color.White.copy(alpha = 0.15f), shape)
        .background(Color(0xFF0A0A0A), shape)
        .onMenuKey(onPick)) {
        val player = vm.players[i]
        if (player != null) key(version, i) {
            AndroidView(factory = { ctx -> PlayerView(ctx).apply { useController = false; isFocusable = false; this.player = player } },
                update = { v -> if (v.player !== player) v.player = player }, modifier = Modifier.fillMaxSize().padding(3.dp))
        } else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (cell.channel == null) "+ Sender wählen" else "⏸ angehalten", color = p.textDim, fontSize = 13.sp)
            if (cell.channel != null) Text("Auswählen = abspielen (Decoder-Limit)", color = p.textDim, fontSize = 10.sp)
        }
        cell.channel?.let { ch ->
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text((if (focused) "🔊 " else "") + ch.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                cell.now?.let { Text(it, color = p.textDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        cell.error?.let { Text(it, color = Color(0xFFFF7070), fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) }
    }
}

private fun Modifier.onMenuKey(action: () -> Unit): Modifier = this.onPreviewKeyEvent { ev ->
    if (ev.key == Key.Menu && ev.type == KeyEventType.KeyUp) { action(); true } else false
}

@Composable
private fun ChannelPicker(channels: List<ChannelEntity>, onPick: (ChannelEntity) -> Unit, onClose: () -> Unit) {
    val p = LocalUltraTheme.current.palette
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.width(420.dp).fillMaxHeight().background(p.bgBottom).padding(16.dp)) {
            Text("Sender für diese Ansicht", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(channels, key = { it.id }) { ch ->
                    TvButton((if (ch.number > 0) "${ch.number}  " else "") + ch.name, onClick = { onPick(ch) }, modifier = Modifier.fillMaxWidth(),
                        focusRequester = if (ch === channels.firstOrNull()) first else null)
                }
            }
        }
    }
}
