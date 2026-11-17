package de.ostlertekker.ultraiptv.feature.radio

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.radio.RadioCountry
import de.ostlertekker.ultraiptv.core.radio.RadioPlayer
import de.ostlertekker.ultraiptv.core.radio.RadioRepository
import de.ostlertekker.ultraiptv.core.radio.RadioStation
import de.ostlertekker.ultraiptv.core.ui.ScreenTitle
import de.ostlertekker.ultraiptv.core.ui.UltraLoader
import de.ostlertekker.ultraiptv.core.ui.initialsOf
import de.ostlertekker.ultraiptv.core.ui.tvFocusable
import de.ostlertekker.ultraiptv.feature.livetv.CategoryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

const val RADIO_FAVS = "★FAV"

@HiltViewModel
class RadioViewModel @Inject constructor(private val repo: RadioRepository, val player: RadioPlayer) : ViewModel() {
    val countries = MutableStateFlow<List<RadioCountry>?>(null)
    val stations = MutableStateFlow<List<RadioStation>?>(null)
    val failed = MutableStateFlow<String?>(null)
    val selected = MutableStateFlow(repo.lastCountry ?: Locale.getDefault().country.uppercase().ifBlank { "DE" })
    val favorites = repo.favorites

    init {
        viewModelScope.launch {
            runCatching { repo.countries() }.onSuccess { countries.value = it }.onFailure { failed.value = it.message }
        }
        select(selected.value)
    }

    fun select(code: String) {
        selected.value = code
        if (code != RADIO_FAVS) repo.lastCountry = code
        stations.value = null; failed.value = null
        if (code == RADIO_FAVS) { stations.value = favorites.value; return }
        viewModelScope.launch {
            runCatching { repo.top(code) }.onSuccess { if (selected.value == code) stations.value = it }
                .onFailure { if (selected.value == code) failed.value = it.message ?: "Fehler" }
        }
    }

    fun toggleFavorite(s: RadioStation): Boolean = repo.toggleFavorite(s).also { if (selected.value == RADIO_FAVS) stations.value = favorites.value }
}

@Composable
fun RadioScreen(vm: RadioViewModel = hiltViewModel()) {
    val theme = LocalUltraTheme.current
    val pal = theme.palette
    val ctx = LocalContext.current
    val countries by vm.countries.collectAsStateWithLifecycle()
    val stations by vm.stations.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val current by vm.player.current.collectAsStateWithLifecycle()
    val playing by vm.player.playing.collectAsStateWithLifecycle()
    val buffering by vm.player.buffering.collectAsStateWithLifecycle()
    val nowTitle by vm.player.nowTitle.collectAsStateWithLifecycle()
    val error by vm.player.error.collectAsStateWithLifecycle()
    val home = remember { Locale.getDefault().country.uppercase() }
    val barFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    // Pfeil runter in den Listen springt in die Wiedergabe-Leiste (wenn ein Sender läuft); Play/Pause-Taste der Fernbedienung
    val toBar = Modifier.onPreviewKeyEvent { ev ->
        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (ev.key.nativeKeyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE ->
                { vm.player.toggle(); true }
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { vm.player.next(1); true }
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> { vm.player.next(-1); true }
            else -> false
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp).then(toBar)) {
        val cName = countries?.firstOrNull { it.code == selected }?.name
        ScreenTitle("Radio", subtitle = if (selected == RADIO_FAVS) "Deine Lieblingssender" else
            "Top 20 ${cName?.let { "· $it " } ?: ""}· meistgehört laut Radio Browser" +
                (if (current != null) " · Pfeil rechts = Wiedergabe-Leiste" else ""))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Länder (wie die Kategorien bei Live TV)
            val list = countries
            val listState = rememberLazyListState()
            LazyColumn(Modifier.width(260.dp).fillMaxHeight(), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item(key = "fav") { CategoryItem("★ Favoriten (${favs.size})", selected == RADIO_FAVS) { vm.select(RADIO_FAVS) } }
                if (list == null) item { if (failed == null) UltraLoader() }
                else {
                    val pinned = list.filter { it.code == home }
                    items(pinned, key = { "p" + it.code }) { c -> CategoryItem("${c.flag}  ${c.name}", selected == c.code) { vm.select(c.code) } }
                    items(list.filter { it.code != home }, key = { it.code }) { c ->
                        CategoryItem("${c.flag}  ${c.name}", selected == c.code) { vm.select(c.code) }
                    }
                }
            }
            // Sender
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val st = stations
                when {
                    failed != null && st == null -> Text("Radio-Datenbank gerade nicht erreichbar. Bitte später erneut versuchen.", color = pal.textDim, fontSize = 14.sp)
                    st == null -> UltraLoader()
                    st.isEmpty() -> Text(if (selected == RADIO_FAVS) "Noch keine Favoriten – bei einem Sender lange OK drücken." else "Für dieses Land sind keine funktionierenden Sender eingetragen.",
                        color = pal.textDim, fontSize = 14.sp)
                    else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(st, key = { it.id }) { s ->
                            val last = s.id == st.last().id
                            StationRow(s, modifier = Modifier
                                .then(if (st.first().id == s.id) Modifier.focusRequester(listFocus) else Modifier)
                                .then(if (last && current != null) Modifier.focusProperties { down = barFocus } else Modifier)
                                .onPreviewKeyEvent { ev ->
                                    // Pfeil runter auf dem letzten Sender → Leiste; lange gedrückt halten nicht nötig
                                    val k = ev.key.nativeKeyCode
                                    // Pfeil rechts (von jedem Sender) oder Pfeil runter auf dem letzten Sender → Wiedergabe-Leiste
                                    if (current != null && ev.type == KeyEventType.KeyDown &&
                                        (k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || (last && k == android.view.KeyEvent.KEYCODE_DPAD_DOWN))) {
                                        runCatching { barFocus.requestFocus() }; true
                                    } else false
                                }, rank = st.indexOf(s) + 1, isFav = favs.any { it.id == s.id }, isCurrent = current?.id == s.id, playing = playing,
                                onClick = { if (current?.id == s.id) vm.player.toggle() else vm.player.play(s, st) },
                                onLong = {
                                    val on = vm.toggleFavorite(s)
                                    Toast.makeText(ctx, if (on) "★ ${s.name} zu Favoriten hinzugefügt" else "${s.name} aus Favoriten entfernt", Toast.LENGTH_SHORT).show()
                                })
                        }
                    }
                }
            }
        }
        // Wiedergabe-Leiste
        current?.let { c ->
            Spacer(Modifier.size(10.dp))
            RadioBar(c, playing, buffering, nowTitle, error, favs.any { it.id == c.id }, barFocus,
                onToggle = { vm.player.toggle() }, onPrev = { vm.player.next(-1) }, onNext = { vm.player.next(1) },
                onStop = { vm.player.stop() }, onFav = { vm.toggleFavorite(c) })
        }
    }
}

@Composable
private fun StationLogo(s: RadioStation, size: Int) {
    val pal = LocalUltraTheme.current.palette
    Box(Modifier.size(size.dp).background(pal.surfaceFocused.copy(alpha = 0.5f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        var ok by remember(s.logo) { mutableStateOf(false) }
        if (!ok) Text(initialsOf(s.name), color = pal.textDim, fontSize = (size / 3.5f).sp, fontWeight = FontWeight.Bold)
        if (s.logo != null) AsyncImage(s.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp), onSuccess = { ok = true })
    }
}

@Composable
private fun StationRow(s: RadioStation, modifier: Modifier = Modifier, rank: Int, isFav: Boolean, isCurrent: Boolean, playing: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val theme = LocalUltraTheme.current
    val pal = theme.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(theme.spec.corner.coerceAtMost(14.dp))
    Row(modifier.fillMaxWidth().tvFocusable(shape, onClick, onLong, scaleOnFocus = false, onFocus = { focused = it })
        .background(if (focused) pal.surfaceFocused else pal.surface, shape).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", color = pal.textDim, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
        StationLogo(s, 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, color = pal.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.info.isNotBlank()) Text(s.info, color = pal.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isFav) Text("★", color = pal.primary, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
        if (isCurrent) Text(if (playing) "● LÄUFT" else "❚❚", color = if (focused) Color.Black else pal.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 10.dp).background(if (focused) pal.primary else pal.primary.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun RadioBar(s: RadioStation, playing: Boolean, buffering: Boolean, title: String?, error: String?, isFav: Boolean, focus: FocusRequester,
                     onToggle: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onStop: () -> Unit, onFav: () -> Unit) {
    val theme = LocalUltraTheme.current
    val pal = theme.palette
    Row(Modifier.fillMaxWidth().background(pal.surface, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        StationLogo(s, 56)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, color = pal.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(error ?: title ?: if (buffering) "Verbinde …" else if (playing) "Live" else "Pausiert",
                color = if (error != null) Color(0xFFFF6B6B) else pal.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundIcon("⏮", onPrev); RoundIcon(if (playing) "❚❚" else "▶", onToggle, primary = true, focus = focus); RoundIcon("⏭", onNext)
        RoundIcon(if (isFav) "★" else "☆", onFav); RoundIcon("■", onStop)
    }
}

@Composable
internal fun RoundIcon(symbol: String, onClick: () -> Unit, primary: Boolean = false, focus: FocusRequester? = null) {
    val pal = LocalUltraTheme.current.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(Modifier.padding(start = 8.dp).size(46.dp).tvFocusable(shape, onClick, focusRequester = focus, onFocus = { focused = it })
        .background(if (focused) pal.primary else if (primary) pal.primary.copy(alpha = 0.25f) else pal.surfaceFocused.copy(alpha = 0.6f), shape),
        contentAlignment = Alignment.Center) {
        Text(symbol, color = if (focused) Color.Black else pal.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** Kleine Radio-Anzeige außerhalb des Radio-Bereichs: zeigt den laufenden Sender, OK = Pause/Weiter, lange OK = Stopp. */
@Composable
fun RadioMiniOverlay(player: RadioPlayer, modifier: Modifier = Modifier) {
    val current by player.current.collectAsStateWithLifecycle()
    val playing by player.playing.collectAsStateWithLifecycle()
    val title by player.nowTitle.collectAsStateWithLifecycle()
    val s = current ?: return
    val pal = LocalUltraTheme.current.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(28.dp)
    Row(modifier.width(360.dp).tvFocusable(shape, { player.toggle() }, { player.stop() }, scaleOnFocus = false, onFocus = { focused = it })
        .background(if (focused) pal.surfaceFocused else pal.surface.copy(alpha = 0.95f), shape).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        StationLogo(s, 40)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text("📻 ${s.name}", color = pal.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title ?: if (playing) "Live · OK = Pause, lange OK = Stopp" else "Pausiert · OK = Weiter", color = pal.textDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (playing) "❚❚" else "▶", color = pal.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
    }
}
