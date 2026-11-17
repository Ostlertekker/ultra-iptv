package de.ostlertekker.ultraiptv.feature.livetv

import kotlinx.coroutines.flow.collectLatest
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
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
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.data.ContentRepository
import de.ostlertekker.ultraiptv.core.data.EpgRepository
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.database.ChannelEntity
import de.ostlertekker.ultraiptv.core.database.ChannelQualityEntity
import de.ostlertekker.ultraiptv.core.database.EpgProgramEntity
import de.ostlertekker.ultraiptv.core.database.ExtrasDao
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.model.Countries
import de.ostlertekker.ultraiptv.core.notify.NotificationCenter
import de.ostlertekker.ultraiptv.core.player.PlayerEngine
import de.ostlertekker.ultraiptv.core.security.StreamResolver
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.TvChip
import de.ostlertekker.ultraiptv.core.ui.tvFocusable
import de.ostlertekker.ultraiptv.openPlayer
import de.ostlertekker.ultraiptv.ui.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

const val CAT_FAVORITES = "★ Favoriten"
const val CAT_RECENT = "Zuletzt gesehen"
/** Präfixe für virtuelle Kategorien: eigene Listen (AI Channel Cloning) und Smart Channel Groups. */
const val CAT_LIST_PREFIX = "◆ "
const val CAT_SMART_PREFIX = "✦ "
fun isVirtualCategory(c: String) = c == CAT_FAVORITES || c == CAT_RECENT || c.startsWith(CAT_LIST_PREFIX) || c.startsWith(CAT_SMART_PREFIX)

fun formatTime(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val content: ContentRepository,
    private val user: UserRepository,
    private val epg: EpgRepository,
    private val settingsRepo: SettingsRepository,
    private val resolver: StreamResolver,
    private val extras: ExtrasDao,
    private val notifications: NotificationCenter,
    val engine: PlayerEngine,
    private val discover: de.ostlertekker.ultraiptv.core.discover.DiscoverRepository,
) : ViewModel() {

    val selected = MutableStateFlow<String?>(null)
    val sortByName = MutableStateFlow(false)

    val categories: StateFlow<List<String>> = content.channelCategories()
        .combine(settingsRepo.settings.map { s -> s.hiddenGroups.split('\n').filter { it.isNotBlank() }.toSet() }) { cats, hidden -> cats.filter { it !in hidden } }
        // Nur echte Gruppen der Playlist (die früheren automatisch erzeugten Kategorien sind entfernt)
        .map { cats -> listOf(CAT_FAVORITES, CAT_RECENT) + cats }
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
    val focusedQuality = MutableStateFlow<ChannelQualityEntity?>(null)
    /** ID des Senders, der gerade in der Vorschau läuft. */
    val previewing = MutableStateFlow<String?>(null)
    val previewError = MutableStateFlow<String?>(null)
    val reminderSet = MutableStateFlow<Set<Long>>(emptySet())
    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            // collectLatest: beim schnellen Kategoriewechsel wird die veraltete EPG-Abfrage abgebrochen
            channels.collectLatest { list ->
                val now = System.currentTimeMillis()
                programs.value = epg.programs(list.mapNotNull { it.epgId }.take(800), now, now + 6 * 3_600_000L)
            }
        }
        viewModelScope.launch { engine.error.collect { previewError.value = if (engine.owner == "preview") it else null } }
    }

    /** Fokus auf einem Sender: Infos sofort, Vorschau nach kurzer Verweildauer (schont das Verbindungslimit beim Scrollen). */
    fun onFocus(ch: ChannelEntity) {
        if (focused.value?.id == ch.id && previewing.value == ch.id) return
        focused.value = ch
        viewModelScope.launch { focusedQuality.value = extras.quality(ch.id) }
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val s = settingsRepo.current()
            if (!s.livePreview || engine.mini.value != null) { previewing.value = null; return@launch }
            delay(350)
            val url = runCatching { resolver.resolve(ch.streamUrl, s.liveFormat) }.getOrNull() ?: return@launch
            previewError.value = null
            engine.play(url, 0, s.bufferProfile, who = "preview", isLive = true)
            previewing.value = ch.id
        }
    }

    fun restartPreview() { focused.value?.let { previewing.value = null; onFocus(it) } }

    fun stopPreview() {
        previewJob?.cancel()
        engine.stopIfOwner("preview")
        previewing.value = null
    }

    fun nowNext(ch: ChannelEntity?): List<EpgProgramEntity> {
        val id = ch?.epgId ?: return emptyList()
        val now = System.currentTimeMillis()
        return programs.value[id].orEmpty().filter { it.endMs > now }.take(3)
    }

    fun toggleFavorite(ch: ChannelEntity) = viewModelScope.launch { user.toggleFavorite(ContentKind.LIVE, ch.id) }
    fun moveFavorite(ch: ChannelEntity, delta: Int) = viewModelScope.launch { user.moveFavorite(ContentKind.LIVE, ch.id, delta) }

    fun toggleReminder(ch: ChannelEntity, p: EpgProgramEntity) = viewModelScope.launch {
        val on = notifications.toggleReminder(ch.id, ch.name, p.title, p.startMs)
        reminderSet.value = if (on) reminderSet.value + p.startMs else reminderSet.value - p.startMs
    }

    fun currentCategory(): String = selected.value ?: categories.value.getOrNull(2) ?: CAT_FAVORITES

    fun open(context: Context, ch: ChannelEntity) {
        previewJob?.cancel()
        // Übergabe an den Vollbild-Player: läuft der Sender schon in der Vorschau, gibt es keinen Neustart.
        if (previewing.value == ch.id) engine.claim("player") else engine.stopIfOwner("preview")
        val cat = currentCategory().takeIf { !isVirtualCategory(it) } ?: ch.category
        context.openPlayer(ContentKind.LIVE, ch.id, category = cat)
    }
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
    val sortByName by vm.sortByName.collectAsStateWithLifecycle()
    val current = sel ?: cats.getOrNull(2) ?: CAT_FAVORITES
    val now = System.currentTimeMillis()
    val firstChannel = remember { FocusRequester() }
    var autoFocused by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Beim Öffnen direkt auf den ersten Sender → Infos und Vorschau sofort sichtbar.
    LaunchedEffect(channels.firstOrNull()?.id) {
        if (!autoFocused && channels.isNotEmpty()) {
            delay(120)
            if (runCatching { firstChannel.requestFocus() }.isSuccess) autoFocused = true
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> vm.stopPreview()
                Lifecycle.Event.ON_START -> vm.restartPreview()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); vm.stopPreview() }
    }

    var favMenu by remember { mutableStateOf<ChannelEntity?>(null) }
    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Anteilige Breiten: die Senderliste behält auf jedem Bildschirm genug Platz für die Sendernamen
        LazyColumn(Modifier.weight(0.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Text("Live-TV", color = p.text, fontSize = 24.sp, fontWeight = theme.spec.titleWeight, fontFamily = theme.spec.fontFamily,
                    modifier = Modifier.padding(bottom = 10.dp))
            }
            items(cats, key = { it }) { c -> CategoryItem(c, c == current) { vm.selected.value = c } }
        }
        Column(Modifier.weight(0.42f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text("${channels.size} Sender", color = p.textDim, fontSize = 13.sp)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                item { TvChip(if (sortByName) "↕ A–Z" else "↕ Nummer", false, { vm.sortByName.value = !sortByName }) }
                item { TvChip("TV-Programm", false, { nav.navigate(Routes.epg(current.takeIf { !isVirtualCategory(it) })) }) }
            }
            if (channels.isEmpty()) {
                Text(if (current == CAT_FAVORITES) "Noch keine Lieblingssender. Sender auswählen und „☆ Favorit“ drücken." else "Keine Sender in dieser Kategorie.",
                    color = p.textDim, fontSize = 15.sp)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                itemsIndexed(channels, key = { _, c -> c.id }, contentType = { _, _ -> "channel" }) { index, ch ->
                    val nowProg = ch.epgId?.let { id -> programs[id]?.firstOrNull { it.startMs <= now && it.endMs > now } }
                    ChannelItem(ch, nowProg, ch.id in favs,
                        focusRequester = if (index == 0) firstChannel else null,
                        onFocus = { vm.onFocus(ch) },
                        onClick = { vm.open(context, ch) },
                        // In „Favoriten“: lange OK öffnet Entfernen/Verschieben, sonst Favorit an/aus
                        onLongClick = { if (current == CAT_FAVORITES) favMenu = ch else vm.toggleFavorite(ch) })
                }
            }
        }
        // Eigener Recomposition-Bereich: ein Fokuswechsel aktualisiert nur dieses Panel, nicht die Senderliste
        RightPanel(vm, favs, Modifier.weight(0.33f).fillMaxHeight())
    }
    favMenu?.let { ch ->
        de.ostlertekker.ultraiptv.core.ui.ActionDialog(ch.name, listOf(
            "Aus Favoriten entfernen" to { vm.toggleFavorite(ch) },
            "▲ Nach oben verschieben" to { vm.moveFavorite(ch, -1) },
            "▼ Nach unten verschieben" to { vm.moveFavorite(ch, 1) },
            "Ganz nach oben" to { vm.moveFavorite(ch, -10_000) },
        )) { favMenu = null }
    }
    }
}

@Composable
private fun RightPanel(vm: LiveTvViewModel, favs: Set<String>, modifier: Modifier) {
    val context = LocalContext.current
    val p = LocalUltraTheme.current.palette
    val focused by vm.focused.collectAsStateWithLifecycle()
    Column(modifier) {
        val ch = focused
        PreviewBox(vm, ch)
        Spacer(Modifier.height(12.dp))
        if (ch != null) FocusedInfo(vm, ch, favs, context)
        else Text("Sender mit den Pfeiltasten auswählen – Infos und Vorschau erscheinen sofort.", color = p.textDim, fontSize = 14.sp)
    }
}

@Composable
private fun PreviewBox(vm: LiveTvViewModel, ch: ChannelEntity?) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val previewing by vm.previewing.collectAsStateWithLifecycle()
    val err by vm.previewError.collectAsStateWithLifecycle()
    val mini by vm.engine.mini.collectAsStateWithLifecycle()
    val state by vm.engine.state.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(theme.spec.corner.coerceAtMost(18.dp))
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Color.Black), contentAlignment = Alignment.Center) {
        val live = ch != null && previewing == ch.id
        if (live) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false; isFocusable = false; player = vm.engine.player
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { v -> if (v.player !== vm.engine.player) v.player = vm.engine.player },
                modifier = Modifier.fillMaxSize(),
            )
        }
        val logo = ch?.logo
        if (logo != null && (!live || state == Player.STATE_BUFFERING)) {
            AsyncImage(logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(40.dp))
        }
        val msg: String? = when {
            ch == null -> "Vorschau"
            mini != null -> "Vorschau pausiert – Mini-Player aktiv"
            live && err != null -> err
            live && state == Player.STATE_BUFFERING -> "Lädt …"
            else -> null
        }
        if (msg != null) {
            Text(msg, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopCenter)
                .padding(top = 8.dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        if (live) {
            Text("● LIVE", color = Color(0xFFFF4545), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp))
        }
        if (ch != null) {
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ch.number > 0) Text("${ch.number}", color = p.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                Text(ch.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FocusedInfo(vm: LiveTvViewModel, ch: ChannelEntity, favs: Set<String>, context: Context) {
    val p = LocalUltraTheme.current.palette
    val quality by vm.focusedQuality.collectAsStateWithLifecycle()
    val reminders by vm.reminderSet.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ch.logo != null) AsyncImage(ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.size(56.dp, 40.dp).padding(end = 10.dp))
            Column(Modifier.weight(1f)) {
                Text(ch.name, color = p.text, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(ch.category, Countries.byCode(ch.country)?.let { "${it.flag} ${it.name}" }).joinToString(" · "),
                    color = p.textDim, fontSize = 12.sp, maxLines = 1)
            }
        }
        val badges = ArrayList<String>()
        quality?.let { q -> if (q.height > 0) badges += "Zuletzt ${q.width}×${q.height}" + (if (q.fps > 0) " · ${"%.0f".format(q.fps)} fps" else "") }
        badges.forEach { Text(it, color = p.primary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        val nn = vm.nowNext(ch)
        if (nn.isEmpty()) Text("Keine Programminformationen (EPG)", color = p.textDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Column(Modifier.height(170.dp)) {
            nn.forEach { prog ->
                val running = prog.startMs <= now
                Text((if (running) "JETZT  " else "DANACH  ") + "${formatTime(prog.startMs)}–${formatTime(prog.endMs)}" +
                    (if (prog.startMs in reminders) "  ⏰" else ""),
                    color = if (running) p.primary else p.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Text(prog.title, color = p.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (running) {
                    val prg = ((now - prog.startMs).toFloat() / (prog.endMs - prog.startMs)).coerceIn(0f, 1f)
                    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).background(p.surface)) {
                        Box(Modifier.fillMaxWidth(prg).height(3.dp).background(p.primary))
                    }
                    val d = prog.description
                    if (d != null) Text(d, color = p.textDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        // Umbrechende Knopfzeile: bei schmaler Vorschau rutschen Knöpfe in die nächste Zeile statt abgeschnitten zu werden
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp)) {
            TvButton("▶ Vollbild", onClick = { vm.open(context, ch) }, primary = true)
            TvButton(if (ch.id in favs) "★ Favorit entfernen" else "☆ Favorit", onClick = { vm.toggleFavorite(ch) })
            val next = nn.firstOrNull { it.startMs > now }
            if (next != null) TvButton(if (next.startMs in reminders) "⏰ Aus" else "⏰ Erinnern", onClick = { vm.toggleReminder(ch, next) })
        }
    }
}

/** Kategorie-Eintrag der linken Seitenleiste (Live-TV, Filme). */
@Composable
internal fun CategoryItem(name: String, selected: Boolean, onClick: () -> Unit) {
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
private fun ChannelItem(
    ch: ChannelEntity,
    now: EpgProgramEntity?,
    favorite: Boolean,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(theme.spec.corner.coerceAtMost(14.dp))
    val prefs = de.ostlertekker.ultraiptv.core.design.LocalUiPrefs.current
    val vPad = when (prefs.density) { 0 -> 4.dp; 2 -> 13.dp; else -> 8.dp }
    val nameSize = when (prefs.density) { 0 -> 14.sp; 2 -> 19.sp; else -> 16.sp }
    Row(
        Modifier.fillMaxWidth()
            .tvFocusable(shape, onClick, onLongClick, focusRequester = focusRequester, scaleOnFocus = false, onFocus = { focused = it; if (it) onFocus() })
            .background(if (focused) p.surfaceFocused else p.surface, shape)
            .padding(horizontal = 12.dp, vertical = vPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prefs.showNumbers) Text(if (ch.number > 0) ch.number.toString() else "", color = p.textDim, fontSize = 13.sp, modifier = Modifier.width(30.dp), maxLines = 1)
        if (prefs.showLogos) {
            Box(Modifier.size(if (prefs.density == 0) 40.dp else if (prefs.density == 2) 64.dp else 52.dp, if (prefs.density == 0) 26.dp else if (prefs.density == 2) 42.dp else 34.dp), contentAlignment = Alignment.Center) {
                var logoOk by remember(ch.logo) { mutableStateOf(false) }
                if (!logoOk) Text(de.ostlertekker.ultraiptv.core.ui.initialsOf(ch.name), color = p.textDim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (ch.logo != null) AsyncImage(ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    onSuccess = { logoOk = true }, onError = { logoOk = false })
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(ch.name, color = p.text, fontSize = nameSize, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null && prefs.showChannelEpg) Text("${formatTime(now.startMs)}  ${now.title}", color = p.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null && prefs.showChannelProgress && now.endMs > now.startMs) {
                val prog = ((System.currentTimeMillis() - now.startMs).toFloat() / (now.endMs - now.startMs)).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).background(p.textDim.copy(alpha = 0.25f), RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(prog).height(3.dp).background(p.primary, RoundedCornerShape(2.dp)))
                }
            }
        }
        val country = Countries.byCode(ch.country)
        if (country != null) Text(country.flag, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 4.dp))
        if (favorite) Text("★", color = p.primary, fontSize = 18.sp)
    }
}
