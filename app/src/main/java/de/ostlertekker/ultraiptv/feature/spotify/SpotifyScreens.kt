package de.ostlertekker.ultraiptv.feature.spotify

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.spotify.SpDevice
import de.ostlertekker.ultraiptv.core.spotify.SpItem
import de.ostlertekker.ultraiptv.core.spotify.SpPlayback
import de.ostlertekker.ultraiptv.core.spotify.SpotifyRepository
import de.ostlertekker.ultraiptv.core.ui.FocusCard
import de.ostlertekker.ultraiptv.core.ui.SectionHeader
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.TvChip
import de.ostlertekker.ultraiptv.core.ui.TvTextField
import de.ostlertekker.ultraiptv.core.ui.UltraLoader
import de.ostlertekker.ultraiptv.core.ui.tvFocusable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private val SpotifyGreen = Color(0xFF1ED760)

@HiltViewModel
class SpotifyViewModel @Inject constructor(val repo: SpotifyRepository) : ViewModel() {
    val connected = repo.connected
    val userName = repo.userName
    val rows = MutableStateFlow<List<Pair<String, List<SpItem>>>>(emptyList())
    val playback = MutableStateFlow<SpPlayback?>(null)
    val devices = MutableStateFlow<List<SpDevice>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)
    val collection = MutableStateFlow<SpItem?>(null)
    val tracks = MutableStateFlow<List<SpItem>>(emptyList())
    val search = MutableStateFlow<List<Pair<String, List<SpItem>>>?>(null)
    val loginOpen = MutableStateFlow(false)

    init { if (repo.connected.value) load() }

    fun load() = viewModelScope.launch {
        loading.value = true; error.value = null
        runCatching { repo.me() }.onFailure { error.value = it.message }
        val recent = async { runCatching { repo.recentlyPlayed() }.getOrDefault(emptyList()) }
        val lists = async { runCatching { repo.playlists() }.getOrDefault(emptyList()) }
        val liked = async { runCatching { repo.likedTracks() }.getOrDefault(emptyList()) }
        val top = async { runCatching { repo.topTracks() }.getOrDefault(emptyList()) }
        val artists = async { runCatching { repo.topArtists() }.getOrDefault(emptyList()) }
        val albums = async { runCatching { repo.savedAlbums() }.getOrDefault(emptyList()) }
        rows.value = listOf(
            "Zuletzt gehört" to recent.await(), "Deine Playlists" to lists.await(), "Lieblingssongs" to liked.await(),
            "Deine Top-Titel" to top.await(), "Deine Künstler" to artists.await(), "Gespeicherte Alben" to albums.await(),
        ).filter { it.second.isNotEmpty() }
        loading.value = false
        refreshPlayback()
    }

    fun refreshPlayback() = viewModelScope.launch {
        runCatching { repo.playback() }.onSuccess { playback.value = it }
    }

    fun loadDevices() = viewModelScope.launch {
        runCatching { repo.devices() }.onSuccess { devices.value = it }.onFailure { error.value = it.message }
    }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onSuccess { error.value = null; delay(350); refreshPlayback() }.onFailure {
            error.value = it.message
            if ((it as? de.ostlertekker.ultraiptv.core.spotify.SpotifyException)?.code == 404) loadDevices()
        }
    }

    /** Antippen einer Kachel: Titel spielen, Sammlungen öffnen. */
    fun open(item: SpItem) {
        if (item.type == "track") { act { repo.play(item, deviceId = targetDevice()) }; return }
        collection.value = item; tracks.value = emptyList()
        viewModelScope.launch { tracks.value = runCatching { repo.tracksOf(item) }.getOrElse { error.value = it.message; emptyList() } }
    }

    fun playCollection(item: SpItem) = act { repo.play(item, deviceId = targetDevice()) }
    fun playTrackIn(track: SpItem, list: List<SpItem>, index: Int) = act {
        val c = collection.value
        if (c != null && c.type != "artist") repo.play(track, c, targetDevice()) else repo.playUris(list.map { it.uri }, index, targetDevice())
    }
    fun closeCollection() { collection.value = null; tracks.value = emptyList() }

    /** Läuft nichts, das erste verfügbare Gerät nehmen (bevorzugt den Fernseher). */
    private suspend fun targetDevice(): String? {
        if (playback.value?.device != null) return null
        val list = runCatching { repo.devices() }.getOrDefault(emptyList()).also { devices.value = it }
        return (list.firstOrNull { it.active } ?: list.firstOrNull { it.type.equals("TV", true) } ?: list.firstOrNull())?.id
    }

    fun toggle() = act { if (playback.value?.playing == true) repo.pause() else repo.resume() }
    fun next() = act { repo.next() }
    fun previous() = act { repo.previous() }
    fun seekBy(deltaMs: Long) = act { val p = playback.value ?: return@act; repo.seek(p.progressMs + (System.currentTimeMillis() - p.at) + deltaMs) }
    fun shuffle() = act { repo.shuffle(!(playback.value?.shuffle ?: false)) }
    fun repeat() = act { repo.repeat(when (playback.value?.repeat) { "off" -> "context"; "context" -> "track"; else -> "off" }) }
    fun volume(delta: Int) = act { repo.volume((playback.value?.device?.volume ?: 50) + delta) }
    fun transfer(d: SpDevice) = act { repo.transfer(d.id) }

    fun doSearch(q: String) {
        if (q.isBlank()) { search.value = null; return }
        viewModelScope.launch { search.value = runCatching { repo.search(q) }.getOrElse { error.value = it.message; emptyList() } }
    }

    fun finishLogin(url: String) = viewModelScope.launch {
        loginOpen.value = false
        runCatching { repo.finishLogin(url) }.onSuccess { load() }.onFailure { error.value = it.message }
    }

    fun logout() { repo.logout(); rows.value = emptyList(); playback.value = null }
}

private fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

@Composable
fun SpotifyScreen(vm: SpotifyViewModel = hiltViewModel()) {
    val connected by vm.connected.collectAsStateWithLifecycle()
    val loginOpen by vm.loginOpen.collectAsStateWithLifecycle()
    when {
        loginOpen -> SpotifyLogin(vm)
        !connected -> SpotifySetup(vm)
        else -> SpotifyHome(vm)
    }
}

// ------------------------------------------------------------------ Einrichtung

@Composable
private fun SpotifySetup(vm: SpotifyViewModel) {
    val p = LocalUltraTheme.current.palette
    var clientId by remember { mutableStateOf(vm.repo.clientId) }
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 60.dp, vertical = 12.dp)) {
        item {
            Text("Spotify verbinden", color = p.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Einmalige Einrichtung (ca. 3 Minuten, am Handy oder PC). Danach steuerst du Spotify direkt hier – mit eigener Oberfläche.",
                color = p.textDim, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
            listOf(
                "1. developer.spotify.com/dashboard öffnen und mit deinem Spotify-Konto anmelden",
                "2. „Create app“: Name z. B. ULTRA IPTV, bei „Redirect URIs“ genau eintragen:  ${SpotifyRepository.REDIRECT_URI}",
                "3. Bei „Which API/SDKs“ „Web API“ anhaken, speichern",
                "4. Unter „User Management“ deine Spotify-E-Mail hinzufügen",
                "5. Die angezeigte „Client ID“ unten eintragen und „Mit Spotify anmelden“ drücken",
            ).forEach { Text(it, color = p.text, fontSize = 15.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            Spacer(Modifier.height(14.dp))
            TvTextField(clientId, { clientId = it.trim() }, "Client ID (32 Zeichen)", modifier = Modifier.width(620.dp))
            error?.let { Text("⚠ $it", color = Color(0xFFFF8A80), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton("Mit Spotify anmelden", onClick = {
                    if (clientId.length < 20) vm.error.value = "Bitte die Client ID aus dem Spotify-Entwicklerportal eintragen"
                    else { vm.repo.clientId = clientId; vm.error.value = null; vm.loginOpen.value = true }
                }, primary = true)
                TvButton("Web-Player öffnen", onClick = {
                    de.ostlertekker.ultraiptv.feature.web.WebAppActivity.open(context, de.ostlertekker.ultraiptv.feature.web.WebApp.SPOTIFY)
                })
                Text("Benötigt Spotify Premium. Es wird kein Passwort in der App gespeichert.", color = p.textDim, fontSize = 12.sp)
            }
        }
    }
}

/** Offizielle Spotify-Anmeldeseite in der App – die Rückleitung wird abgefangen, nichts verlässt die App. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SpotifyLogin(vm: SpotifyViewModel) {
    val p = LocalUltraTheme.current.palette
    var googleHint by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { vm.loginOpen.value = false }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 8.dp)) {
        Text(if (googleHint) "⚠ Google erlaubt keine Anmeldung in eingebetteten Apps – bitte „Mit E-Mail-Adresse anmelden“ wählen (Code per Mail)."
             else "Bei Spotify anmelden – am besten mit E-Mail-Adresse (Einmal-Code per Mail). Zurück bricht ab.",
            color = if (googleHint) Color(0xFFFFB74D) else p.textDim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        AndroidView(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)), factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                fun handle(url: String): Boolean {
                    if (url.startsWith(SpotifyRepository.REDIRECT_URI)) { vm.finishLogin(url); return true }
                    if (android.net.Uri.parse(url).host?.endsWith("accounts.google.com") == true) { googleHint = true; return true }
                    return false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = handle(request.url.toString())
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { if (handle(url)) view.stopLoading() }
                }
                loadUrl(vm.repo.authorizeUrl())
                requestFocus()
            }
        })
    }
}

// ------------------------------------------------------------------ Hauptansicht

@Composable
private fun SpotifyHome(vm: SpotifyViewModel) {
    val p = LocalUltraTheme.current.palette
    val context = LocalContext.current
    val rows by vm.rows.collectAsStateWithLifecycle()
    val pb by vm.playback.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val user by vm.userName.collectAsStateWithLifecycle()
    val collection by vm.collection.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var devicesOpen by remember { mutableStateOf(false) }

    // „Läuft gerade“ regelmäßig abgleichen, solange die Seite offen ist
    LaunchedEffect(Unit) { while (true) { vm.refreshPlayback(); delay(4000) } }
    androidx.activity.compose.BackHandler(enabled = collection != null || search != null) {
        if (collection != null) vm.closeCollection() else { query = ""; vm.doSearch("") }
    }

    Row(Modifier.fillMaxSize().padding(start = 36.dp, end = 24.dp, top = 6.dp).onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
        when (e.key) {
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { vm.toggle(); true }
            Key.MediaNext, Key.MediaFastForward -> { vm.next(); true }
            Key.MediaPrevious, Key.MediaRewind -> { vm.previous(); true }
            else -> false
        }
    }, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        // ---------------- Läuft gerade
        NowPlaying(vm, pb, Modifier.width(330.dp).fillMaxHeight()) { devicesOpen = true; vm.loadDevices() }

        // ---------------- Bibliothek / Suche / Sammlung
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Spotify", color = SpotifyGreen, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                user?.let { Text(it, color = p.textDim, fontSize = 13.sp) }
                Box(Modifier.weight(1f)) { TvTextField(query, { query = it; if (it.length >= 2 || it.isEmpty()) vm.doSearch(it) }, "Suchen: Titel, Künstler, Alben, Playlists") }
                TvChip("↻", false, { vm.load() })
                TvChip("Web-Player", false, { de.ostlertekker.ultraiptv.feature.web.WebAppActivity.open(context, de.ostlertekker.ultraiptv.feature.web.WebApp.SPOTIFY) })
                TvChip("Abmelden", false, { vm.logout() })
            }
            error?.let { Text("⚠ $it", color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            val c = collection
            when {
                c != null -> CollectionView(vm, c, tracks)
                search != null -> LazyColumn(contentPadding = PaddingValues(bottom = 60.dp)) {
                    if (search!!.isEmpty()) item { Text("Keine Treffer.", color = p.textDim, modifier = Modifier.padding(top = 16.dp)) }
                    search!!.forEach { (title, list) -> item(key = "s_$title") { SpRow(title, list) { vm.open(it) } } }
                }
                loading && rows.isEmpty() -> UltraLoader(Modifier.padding(top = 30.dp), "Deine Spotify-Bibliothek wird geladen …")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 60.dp)) {
                    rows.forEach { (title, list) -> item(key = "r_$title") { SpRow(title, list) { vm.open(it) } } }
                }
            }
        }
    }
    if (devicesOpen) DevicePicker(vm) { devicesOpen = false }
}

@Composable
private fun NowPlaying(vm: SpotifyViewModel, pb: SpPlayback?, modifier: Modifier, onDevices: () -> Unit) {
    val p = LocalUltraTheme.current.palette
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pb?.playing) { while (pb?.playing == true) { now = System.currentTimeMillis(); delay(1000) } }
    val t = pb?.track
    val pos = if (pb == null) 0L else (pb.progressMs + if (pb.playing) now - pb.at else 0L).coerceAtMost(pb.durationMs)
    Column(modifier.padding(top = 10.dp), horizontalAlignment = Alignment.Start) {
        Box(Modifier.size(300.dp).clip(RoundedCornerShape(14.dp)).background(p.surface), contentAlignment = Alignment.Center) {
            if (t?.image != null) AsyncImage(t.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text("♪", color = SpotifyGreen, fontSize = 72.sp)
        }
        Text(t?.name ?: "Nichts läuft gerade", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
        Text(t?.subtitle ?: "Wähle rechts einen Titel oder eine Playlist", color = p.textDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (pb != null && pb.durationMs > 0) {
            Box(Modifier.padding(top = 10.dp).width(300.dp).height(4.dp).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth((pos.toFloat() / pb.durationMs).coerceIn(0f, 1f)).height(4.dp).background(SpotifyGreen, RoundedCornerShape(2.dp)))
            }
            Row(Modifier.width(300.dp)) {
                Text(fmt(pos), color = p.textDim, fontSize = 12.sp); Spacer(Modifier.weight(1f)); Text(fmt(pb.durationMs), color = p.textDim, fontSize = 12.sp)
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            CtrlButton(if (pb?.shuffle == true) "🔀•" else "🔀") { vm.shuffle() }
            CtrlButton("⏮") { vm.previous() }
            CtrlButton(if (pb?.playing == true) "⏸" else "▶", big = true) { vm.toggle() }
            CtrlButton("⏭") { vm.next() }
            CtrlButton(when (pb?.repeat) { "track" -> "🔂"; "context" -> "🔁•"; else -> "🔁" }) { vm.repeat() }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            CtrlButton("−10 s") { vm.seekBy(-10_000) }
            CtrlButton("+10 s") { vm.seekBy(10_000) }
            CtrlButton("🔉") { vm.volume(-10) }
            CtrlButton("🔊") { vm.volume(10) }
        }
        Row(Modifier.padding(top = 8.dp)) {
            TvChip("📡 " + (pb?.device?.name ?: "Gerät wählen") + (pb?.device?.volume?.let { " · $it %" } ?: ""), false, onDevices)
        }
    }
}

@Composable
private fun CtrlButton(label: String, big: Boolean = false, onClick: () -> Unit) {
    val p = LocalUltraTheme.current.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(Modifier.tvFocusable(shape, onClick, scaleOnFocus = true, onFocus = { focused = it })
        .background(if (big) SpotifyGreen else if (focused) Color.White.copy(alpha = 0.22f) else p.surface, shape)
        .padding(horizontal = if (big) 20.dp else 12.dp, vertical = if (big) 12.dp else 8.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (big) Color.Black else p.text, fontSize = if (big) 20.sp else 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SpRow(title: String, list: List<SpItem>, onClick: (SpItem) -> Unit) {
    SectionHeader(title)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        items(list, key = { it.type + it.id }, contentType = { "sp" }) { it ->
            FocusCard(it.name, it.image, 150.dp, 150.dp, onClick = { onClick(it) },
                subtitle = when (it.type) { "track" -> it.subtitle; "album" -> "Album · ${it.subtitle}"; else -> it.subtitle })
        }
    }
}

@Composable
private fun CollectionView(vm: SpotifyViewModel, c: SpItem, tracks: List<SpItem>) {
    val p = LocalUltraTheme.current.palette
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AsyncImage(c.image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(110.dp).clip(RoundedCornerShape(10.dp)).background(p.surface))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.subtitle, color = p.textDim, fontSize = 13.sp)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("▶ Abspielen", onClick = { vm.playCollection(c) }, primary = true)
                TvButton("Zurück", onClick = { vm.closeCollection() })
            }
        }
    }
    if (tracks.isEmpty()) UltraLoader(Modifier.padding(top = 20.dp), "Titel werden geladen …")
    LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
        itemsIndexed(tracks, key = { i, t -> "$i${t.id}" }) { i, t ->
            var focused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(10.dp)
            Row(Modifier.fillMaxWidth().tvFocusable(shape, { vm.playTrackIn(t, tracks, i) }, scaleOnFocus = false, onFocus = { focused = it })
                .background(if (focused) p.surfaceFocused else p.surface.copy(alpha = 0.6f), shape).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${i + 1}", color = p.textDim, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                AsyncImage(t.image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
                Column(Modifier.weight(1f)) {
                    Text(t.name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.subtitle, color = p.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(fmt(t.durationMs), color = p.textDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DevicePicker(vm: SpotifyViewModel, onClose: () -> Unit) {
    val p = LocalUltraTheme.current.palette
    val devices by vm.devices.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).background(p.surface, RoundedCornerShape(18.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Abspielen auf …", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (devices.isEmpty()) Text("Kein Spotify-Gerät gefunden. Öffne Spotify einmal auf deinem Handy, PC, einer Box oder dem Fernseher – " +
                "dann erscheint es hier (Spotify Connect).", color = p.textDim, fontSize = 14.sp)
            devices.forEach { d ->
                TvButton((if (d.active) "● " else "") + d.name + "  ·  " + d.type + (if (d.restricted) " (nicht steuerbar)" else ""),
                    onClick = { vm.transfer(d); onClose() }, primary = d.active)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Aktualisieren", onClick = { vm.loadDevices() })
                TvButton("Schließen", onClick = onClose)
            }
        }
    }
}
