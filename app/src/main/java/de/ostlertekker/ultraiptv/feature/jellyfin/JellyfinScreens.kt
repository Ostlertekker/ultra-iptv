package de.ostlertekker.ultraiptv.feature.jellyfin

import de.ostlertekker.ultraiptv.core.ui.FocusBleedV
import de.ostlertekker.ultraiptv.core.ui.FocusBleedH
import de.ostlertekker.ultraiptv.core.ui.focusBleed
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.jellyfin.JfItem
import de.ostlertekker.ultraiptv.core.jellyfin.JellyfinRepository
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.ui.FocusCard
import de.ostlertekker.ultraiptv.core.ui.SectionHeader
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.TvChip
import de.ostlertekker.ultraiptv.core.ui.TvTextField
import de.ostlertekker.ultraiptv.core.ui.UltraLoader
import de.ostlertekker.ultraiptv.core.ui.ratingLabel
import de.ostlertekker.ultraiptv.core.ui.tvFocusable
import de.ostlertekker.ultraiptv.feature.common.CinemaOverview
import de.ostlertekker.ultraiptv.feature.common.CinemaRow
import de.ostlertekker.ultraiptv.feature.common.CinemaTile
import de.ostlertekker.ultraiptv.openPlayer
import de.ostlertekker.ultraiptv.ui.Routes
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Kennung, mit der Jellyfin-Titel im gemeinsamen Player abgespielt werden. */
const val JF_PREFIX = "jf:"

private fun JfItem.meta(): String = listOfNotNull(
    year?.toString(), genres.firstOrNull(),
    runtimeMs.takeIf { it > 0 }?.let { "${it / 60_000} Min." },
    rating?.takeIf { it > 0 }?.let { ratingLabel(it) }, officialRating,
).joinToString(" · ")

fun JellyfinRepository.tile(i: JfItem, resume: Boolean = false): CinemaTile = CinemaTile(
    id = i.id,
    title = if (i.isEpisode) "${i.seriesName ?: ""} · S${i.parentIndex ?: 0}E${i.index ?: 0}".trim(' ', '·') else i.name,
    image = if (i.isEpisode && resume) image(i, width = 480) else image(i),
    subtitle = when {
        i.isEpisode -> i.name
        i.isFolder -> i.childCount?.let { "$it Titel" }
        else -> i.meta().ifBlank { null }
    },
    progress = i.progress,
    positionMs = i.positionMs,
    backdrop = image(i, "Backdrop") ?: image(i, width = 640),
    plot = i.overview,
    resume = resume,
    kind = if (i.isFolder) "Lib" else i.type,
)

// =====================================================================================================
// Übersicht + Anmeldung
// =====================================================================================================

@HiltViewModel
class JellyfinViewModel @Inject constructor(
    val repo: JellyfinRepository,
    private val settings: de.ostlertekker.ultraiptv.core.data.SettingsRepository,
    val seerr: de.ostlertekker.ultraiptv.core.seerr.SeerrRepository,
) : ViewModel() {
    /** Seerr-Kacheln der Übersicht (id → Eintrag), für den Anfrage-Dialog. */
    var seerrItems: Map<String, de.ostlertekker.ultraiptv.core.seerr.SeerrItem> = emptyMap()
        private set
    /** Eigene Seerr-Anfragen der Reihe „Meine Anfragen“ (Kachel-id → Anfrage). */
    var seerrRequests: Map<String, de.ostlertekker.ultraiptv.core.seerr.SeerrRequest> = emptyMap()
        private set
    private var seerrTicker: kotlinx.coroutines.Job? = null

    /** Solange etwas lädt oder bearbeitet wird: „Meine Anfragen“ regelmäßig auffrischen (Fortschrittsbalken). */
    private fun watchRequests() {
        seerrTicker?.cancel()
        seerrTicker = viewModelScope.launch {
            while (true) {
                val active = seerrRequests.values.any { it.downloading || it.item.status == de.ostlertekker.ultraiptv.core.seerr.SeerrStatus.PROCESSING }
                if (!active) return@launch
                kotlinx.coroutines.delay(if (seerrRequests.values.any { it.downloading }) 15_000 else 60_000)
                val fresh = runCatching { seerr.myRequests() }.getOrNull() ?: continue
                seerrRequests = fresh.associateBy { "req:${it.id}" }
                rows.value = rows.value.map { if (it.title == "Meine Anfragen") it.copy(tiles = fresh.map { r -> r.tile() }) else it }
            }
        }
    }
    /** In Einstellungen › Jellyfin ausgeblendete Reihen. */
    val hiddenRows = settings.settings.map { s -> s.jfHiddenRows.split('\n').filter { it.isNotBlank() }.toSet() }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptySet())
    val session = repo.session
    val rows = MutableStateFlow<List<CinemaRow>>(emptyList())
    val libraries = MutableStateFlow<List<JfItem>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    init { if (repo.restore() != null) load() }

    fun login(server: String, user: String, pw: String) = viewModelScope.launch {
        if (server.isBlank() || user.isBlank()) { error.value = "Bitte Serveradresse und Benutzername eingeben."; return@launch }
        busy.value = true; error.value = null
        runCatching { repo.login(server, user, pw) }
            .onSuccess { load() }
            .onFailure { error.value = it.message ?: "Anmeldung fehlgeschlagen" }
        busy.value = false
    }

    val found = MutableStateFlow<List<Pair<String, String>>?>(null)
    val searching = MutableStateFlow(false)
    fun searchLan() = viewModelScope.launch {
        searching.value = true; found.value = null
        found.value = runCatching { repo.discover() }.getOrDefault(emptyList())
        searching.value = false
    }

    fun logout() = viewModelScope.launch { repo.logout(); rows.value = emptyList(); libraries.value = emptyList() }

    fun refresh() { repo.invalidate(); load() }

    fun load() = viewModelScope.launch {
        loading.value = true; error.value = null
        try {
            // Alles parallel laden – ein langsamer Bereich hält die anderen nicht auf
            val resume = async { runCatching { repo.resume() }.getOrDefault(emptyList()) }
            val nextUp = async { runCatching { repo.nextUp() }.getOrDefault(emptyList()) }
            val latest = async { runCatching { repo.latest() }.getOrDefault(emptyList()) }
            val recent = async { runCatching { repo.recentlyWatched() }.getOrDefault(emptyList()) }
            val favs = async { runCatching { repo.favorites() }.getOrDefault(emptyList()) }
            val movies = async { runCatching { repo.items("Movie", sortBy = "DateCreated", descending = true, limit = 30).first }.getOrDefault(emptyList()) }
            val series = async { runCatching { repo.items("Series", sortBy = "DateCreated", descending = true, limit = 30).first }.getOrDefault(emptyList()) }
            val libs = async { repo.libraries() }
            // Seerr (optional): eigene Anfragen + „Entdecken“
            val cfg = settings.current()
            val sReq = async { if (cfg.seerrEnabled && cfg.seerrRequestsRow) runCatching { seerr.myRequests() }.getOrDefault(emptyList()) else emptyList() }
            val sDisc = async {
                if (cfg.seerrEnabled && cfg.seerrDiscoverRow) runCatching { seerr.discover() }.getOrDefault(emptyList())
                    .filter { it.status != de.ostlertekker.ultraiptv.core.seerr.SeerrStatus.AVAILABLE } else emptyList()
            }
            awaitAll(resume, nextUp, latest, recent, favs, movies, series)
            val reqs = sReq.await(); val disc = sDisc.await()
            seerrItems = (reqs.map { it.item } + disc).associateBy { it.key }
            seerrRequests = reqs.associateBy { "req:${it.id}" }
            libraries.value = libs.await()
            rows.value = listOf(
                CinemaRow("Weiterschauen", resume.await().map { repo.tile(it, resume = true) }, landscape = true),
                CinemaRow("Nächste Folgen", nextUp.await().map { repo.tile(it, resume = true) }, landscape = true),
                CinemaRow("Meine Anfragen", reqs.map { it.tile() }),
                CinemaRow("Zuletzt hinzugefügt", latest.await().map { repo.tile(it) }),
                CinemaRow("Zuletzt angesehen", recent.await().map { repo.tile(it) }),
                CinemaRow("Meine Favoriten", favs.await().map { repo.tile(it) }),
                CinemaRow("Filme", movies.await().map { repo.tile(it) }),
                CinemaRow("Serien", series.await().map { repo.tile(it) }),
                CinemaRow("Entdecken (Seerr)", disc.map { it.tile() }),
                CinemaRow("Meine Bibliotheken", libraries.value.map { repo.tile(it) }, landscape = true),
            )
            watchRequests()
        } catch (e: Exception) {
            error.value = e.message ?: "Jellyfin nicht erreichbar"
        }
        loading.value = false
    }
}

/** Gemeinsame Aktion für alle Jellyfin-Kacheln: Details öffnen oder direkt abspielen. */
fun openJellyfin(nav: NavController, context: android.content.Context, t: CinemaTile, play: Boolean) {
    when (t.kind) {
        "Movie" -> if (play) context.openPlayer(ContentKind.MOVIE, JF_PREFIX + t.id, t.positionMs) else nav.navigate(Routes.jfMovie(t.id))
        "Series" -> nav.navigate(Routes.jfSeries(t.id))
        "Episode" -> context.openPlayer(ContentKind.EPISODE, JF_PREFIX + t.id, t.positionMs)
        "Lib" -> nav.navigate(Routes.jfLibrary(t.id, t.title))
        else -> nav.navigate(Routes.jfMovie(t.id))
    }
}

@Composable
fun JellyfinScreen(nav: NavController, vm: JellyfinViewModel = hiltViewModel()) {
    val session by vm.session.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val p = LocalUltraTheme.current.palette
    if (session == null) { JellyfinLogin(vm); return }
    val allRows by vm.rows.collectAsStateWithLifecycle()
    val hiddenRows by vm.hiddenRows.collectAsStateWithLifecycle()
    val rows = remember(allRows, hiddenRows) { allRows.filter { it.title !in hiddenRows } }
    val libs by vm.libraries.collectAsStateWithLifecycle()
    var seerrDialog by remember { mutableStateOf<de.ostlertekker.ultraiptv.core.seerr.SeerrItem?>(null) }
    var requestDetail by remember { mutableStateOf<de.ostlertekker.ultraiptv.core.seerr.SeerrRequest?>(null) }
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (allRows.isEmpty() && !loading) vm.load() }
    val hero = allRows.firstOrNull { it.title == "Zuletzt hinzugefügt" }?.tiles?.firstOrNull { !it.backdrop.isNullOrBlank() && it.kind != "Episode" }
    CinemaOverview(hero, rows,
        header = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Jellyfin", color = p.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("${session?.serverName ?: ""} · ${session?.userName ?: ""} · ${session?.server?.substringAfter("://") ?: ""}", color = p.textDim, fontSize = 13.sp,
                        maxLines = 1, modifier = Modifier.padding(end = 10.dp))
                    TvChip("🔎 Suchen", false, { nav.navigate(Routes.JF_SEARCH) })
                    libs.forEach { l -> TvChip(l.name, false, { nav.navigate(Routes.jfLibrary(l.id, l.name)) }) }
                    TvChip("↻ Aktualisieren", false, { vm.refresh() })
                    TvChip("Abmelden", false, { vm.logout() })
                }
                if (loading && rows.isEmpty()) UltraLoader(Modifier.padding(top = 20.dp), "Jellyfin-Bibliothek wird geladen …")
                error?.let { Text("⚠ $it", color = Color(0xFFFF8A80), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        onOpen = { t -> when (t.kind) {
            "seerr" -> seerrDialog = vm.seerrItems[t.id]
            "seerrReq" -> requestDetail = vm.seerrRequests[t.id]
            else -> openJellyfin(nav, context, t, play = false)
        } },
        onPlay = { t -> when (t.kind) {
            "seerr" -> seerrDialog = vm.seerrItems[t.id]
            "seerrReq" -> requestDetail = vm.seerrRequests[t.id]
            else -> openJellyfin(nav, context, t, play = true)
        } })
    requestDetail?.let { r ->
        SeerrRequestDetail(r, vm.seerr, onDismiss = { requestDetail = null },
            onOpenJellyfin = { title -> requestDetail = null; nav.navigate(Routes.JF_SEARCH + "?q=" + android.net.Uri.encode(title)) },
            onRequestMore = { item -> requestDetail = null; seerrDialog = item })
    }
    seerrDialog?.let { SeerrRequestDialog(it, vm.seerr) { seerrDialog = null; vm.refresh() } }
}

@Composable
private fun JellyfinLogin(vm: JellyfinViewModel) {
    val p = LocalUltraTheme.current.palette
    var server by remember { mutableStateOf(vm.repo.savedServer()) }
    var user by remember { mutableStateOf(vm.repo.savedUser()) }
    var pw by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).background(p.surface, RoundedCornerShape(18.dp)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Mit Jellyfin verbinden", color = p.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Deine eigene Mediathek direkt in ULTRA IPTV – Filme und Serien laufen im eingebauten Player.", color = p.textDim, fontSize = 14.sp)
            TvTextField(server, { server = it }, "Serveradresse – wie in der Jellyfin-App (z. B. meinserver.de oder 192.168.1.10:8096)", modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TvChip(if (searching) "Suche …" else "🔎 Im Heimnetz suchen", false, { if (!searching) vm.searchLan() })
                found?.let { list ->
                    if (list.isEmpty()) Text("Kein Server im Heimnetz gefunden", color = p.textDim, fontSize = 13.sp)
                    list.forEach { (addr, name) -> TvChip("$name · ${addr.substringAfter("://")}", server == addr, { server = addr }) }
                }
            }
            TvTextField(user, { user = it }, "Benutzername", modifier = Modifier.fillMaxWidth())
            TvTextField(pw, { pw = it }, "Passwort", modifier = Modifier.fillMaxWidth(), password = true)
            error?.let { Text("⚠ $it", color = Color(0xFFFF8A80), fontSize = 14.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton(if (busy) "Verbinde … (prüfe Adressen)" else "Verbinden", onClick = { if (!busy) vm.login(server, user, pw) }, primary = true)
                Text("Zugangsdaten werden verschlüsselt auf dem Gerät gespeichert.", color = p.textDim, fontSize = 12.sp)
            }
        }
    }
}

// =====================================================================================================
// Bibliothek (Raster mit Nachladen)
// =====================================================================================================

@HiltViewModel
class JellyfinLibraryViewModel @Inject constructor(savedState: SavedStateHandle, val repo: JellyfinRepository) : ViewModel() {
    val libId: String = savedState.get<String>("id") ?: ""
    val title: String = savedState.get<String>("name") ?: "Bibliothek"
    val items = MutableStateFlow<List<JfItem>>(emptyList())
    val total = MutableStateFlow(-1)
    val sort = MutableStateFlow("SortName")
    val error = MutableStateFlow<String?>(null)
    private var loadingMore = false

    init { reload() }

    fun setSort(s: String) { sort.value = s; reload() }

    fun reload() { items.value = emptyList(); total.value = -1; loadMore() }

    fun loadMore() {
        if (loadingMore || (total.value >= 0 && items.value.size >= total.value)) return
        loadingMore = true
        viewModelScope.launch {
            runCatching {
                repo.items("Movie,Series,Video,BoxSet", parentId = libId, sortBy = sort.value, descending = sort.value != "SortName",
                    limit = 60, start = items.value.size)
            }.onSuccess { (list, t) -> items.value = items.value + list; total.value = t }
                .onFailure { error.value = it.message; if (total.value < 0) total.value = 0 }
            loadingMore = false
        }
    }
}

@Composable
fun JellyfinLibraryScreen(nav: NavController, vm: JellyfinLibraryViewModel = hiltViewModel()) {
    val p = LocalUltraTheme.current.palette
    val context = LocalContext.current
    val list by vm.items.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= grid.layoutInfo.totalItemsCount - 18) vm.loadMore()
        }
    }
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(vm.title, color = p.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
            if (total >= 0) Text("$total Titel", color = p.textDim, fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
            TvChip("A–Z", sort == "SortName", { vm.setSort("SortName") })
            TvChip("Neu", sort == "DateCreated", { vm.setSort("DateCreated") })
            TvChip("Bewertung", sort == "CommunityRating", { vm.setSort("CommunityRating") })
            TvChip("Erscheinungsjahr", sort == "ProductionYear", { vm.setSort("ProductionYear") })
        }
        error?.let { Text("⚠ $it", color = Color(0xFFFF8A80), fontSize = 14.sp) }
        if (total < 0) UltraLoader(Modifier.padding(top = 30.dp), "Bibliothek wird geladen …")
        LazyVerticalGrid(GridCells.Adaptive(160.dp), state = grid, horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(start = FocusBleedH, end = FocusBleedH, top = 12.dp + FocusBleedV, bottom = 60.dp + FocusBleedV), modifier = Modifier.focusBleed()) {
            items(list.size, key = { list[it].id }, contentType = { "poster" }) { i ->
                val it2 = list[i]
                val t = vm.repo.tile(it2)
                FocusCard(it2.name, t.image, 150.dp, 222.dp, onClick = { openJellyfin(nav, context, t, play = false) },
                    subtitle = t.subtitle, progress = it2.progress)
            }
        }
    }
}

// =====================================================================================================
// Film-Details
// =====================================================================================================

@HiltViewModel
class JellyfinItemViewModel @Inject constructor(savedState: SavedStateHandle, val repo: JellyfinRepository) : ViewModel() {
    val id: String = savedState.get<String>("id") ?: ""
    val item = MutableStateFlow<JfItem?>(null)
    val similar = MutableStateFlow<List<JfItem>>(emptyList())
    val seasons = MutableStateFlow<List<JfItem>>(emptyList())
    val season = MutableStateFlow<String?>(null)
    val episodes = MutableStateFlow<List<JfItem>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val episodesLoading = MutableStateFlow(false)

    fun load() = viewModelScope.launch {
        runCatching { repo.item(id) }.onSuccess { item.value = it }.onFailure { error.value = it.message }
        val it = item.value ?: return@launch
        if (it.isSeries) {
            val s = runCatching { repo.seasons(id) }.getOrDefault(emptyList())
            seasons.value = s
            if (season.value == null) season.value = s.firstOrNull { st -> !st.played }?.id ?: s.firstOrNull()?.id
            loadEpisodes()
        }
        similar.value = runCatching { repo.similar(id) }.getOrDefault(emptyList())
    }

    fun selectSeason(id: String) { season.value = id; loadEpisodes() }

    private fun loadEpisodes() = viewModelScope.launch {
        episodesLoading.value = true
        episodes.value = runCatching { repo.episodes(id, season.value) }.getOrElse { error.value = it.message; emptyList() }
        episodesLoading.value = false
    }

    fun toggleFavorite() = viewModelScope.launch {
        val cur = item.value ?: return@launch
        runCatching { repo.setFavorite(cur.id, !cur.favorite) }.onSuccess { item.value = cur.copy(favorite = !cur.favorite) }.onFailure { e -> error.value = e.message }
    }

    fun togglePlayed(target: JfItem) = viewModelScope.launch {
        runCatching { repo.setPlayed(target.id, !target.played) }.onFailure { e -> error.value = e.message }
        if (target.id == item.value?.id) item.value = item.value?.copy(played = !target.played, positionMs = 0)
        else episodes.value = episodes.value.map { if (it.id == target.id) it.copy(played = !target.played, positionMs = 0) else it }
    }
}

@Composable
private fun Backdrop(url: String?) {
    if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.75f)
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.95f), 0.55f to Color.Black.copy(alpha = 0.7f), 1f to Color.Black.copy(alpha = 0.25f))))
}

@Composable
fun JellyfinMovieScreen(nav: NavController, vm: JellyfinItemViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val p = LocalUltraTheme.current.palette
    val item by vm.item.collectAsStateWithLifecycle()
    val similar by vm.similar.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }
    // Bei jeder Rückkehr aus dem Player neu laden → Fortschritt/„gesehen“ stimmen mit dem Server überein
    androidx.lifecycle.compose.LifecycleResumeEffect(vm.id) { vm.load(); onPauseOrDispose { } }
    val m = item
    if (m == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (error != null) Text("⚠ $error", color = p.textDim) else UltraLoader(label = "Lädt …") }
        return
    }
    // Ändert sich die Knopfreihe (z. B. nach „Gesehen“), bleibt der Fokus auf dem Abspielknopf statt verloren zu gehen
    LaunchedEffect(m.id, m.played, m.positionMs > 30_000) { kotlinx.coroutines.delay(400); runCatching { playFocus.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        Backdrop(vm.repo.image(m, "Backdrop"))
        // Oben ausgerichtet und begrenzt: Knöpfe bleiben auch bei langen Beschreibungen sichtbar und erreichbar
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp), verticalAlignment = Alignment.Top) {
            AsyncImage(vm.repo.image(m, width = 480), m.name, contentScale = ContentScale.Crop,
                modifier = Modifier.size(190.dp, 282.dp).clip(RoundedCornerShape(14.dp)).background(p.surface))
            Spacer(Modifier.width(36.dp))
            Column(Modifier.width(640.dp)) {
                Text(m.name, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.meta() + (if (m.played) "  ·  ✓ Gesehen" else "") + (if (m.genres.size > 1) "  ·  " + m.genres.drop(1).joinToString(", ") else ""),
                    color = p.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(vertical = 6.dp))
                Text(m.overview ?: "Keine Beschreibung verfügbar.", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, maxLines = 3,
                    overflow = TextOverflow.Ellipsis)
                val credits = listOfNotNull(m.people.takeIf { it.isNotEmpty() }?.let { "Mit: " + it.joinToString(", ") },
                    m.directors.takeIf { it.isNotEmpty() }?.let { "Regie: " + it.joinToString(", ") }).joinToString("   ·   ")
                if (credits.isNotBlank()) Text(credits, color = p.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (m.positionMs > 30_000 && !m.played) {
                        TvButton("▶ Fortsetzen (${m.positionMs / 60_000} Min.)", onClick = { context.openPlayer(ContentKind.MOVIE, JF_PREFIX + m.id, m.positionMs) },
                            primary = true, focusRequester = playFocus)
                        TvButton("Von vorne", onClick = { context.openPlayer(ContentKind.MOVIE, JF_PREFIX + m.id, 0) })
                    } else {
                        TvButton("▶ Abspielen", onClick = { context.openPlayer(ContentKind.MOVIE, JF_PREFIX + m.id, 0) }, primary = true, focusRequester = playFocus)
                    }
                    TvButton(if (m.favorite) "♥ Favorit" else "♡ Favorit", onClick = { vm.toggleFavorite() })
                    TvButton(if (m.played) "Als ungesehen markieren" else "✓ Gesehen", onClick = { vm.togglePlayed(m) })
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    TvButton("Trailer", onClick = { scope.launch { openJfTrailer(context, vm.repo, m) } })
                }
                error?.let { Text("⚠ $it", color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
        if (similar.isNotEmpty()) Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 10.dp)) {
            Text("Ähnliche Titel", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.focusBleed(), contentPadding = PaddingValues(horizontal = FocusBleedH, vertical = FocusBleedV)) {
                items(similar, key = { it.id }) { s ->
                    FocusCard(s.name, vm.repo.image(s), 84.dp, 124.dp, onClick = { openJellyfin(nav, context, vm.repo.tile(s), play = false) })
                }
            }
        }
    }
}

// =====================================================================================================
// Serien-Details: Staffeln, Folgen, gesehen, Fortschritt
// =====================================================================================================

@Composable
fun JellyfinSeriesScreen(nav: NavController, vm: JellyfinItemViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val p = LocalUltraTheme.current.palette
    val item by vm.item.collectAsStateWithLifecycle()
    val seasons by vm.seasons.collectAsStateWithLifecycle()
    val season by vm.season.collectAsStateWithLifecycle()
    val eps by vm.episodes.collectAsStateWithLifecycle()
    val epsLoading by vm.episodesLoading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleResumeEffect(vm.id) { vm.load(); onPauseOrDispose { } }
    val s = item
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (error != null) Text("⚠ $error", color = p.textDim) else UltraLoader(label = "Lädt …") }
        return
    }
    // Weiterschauen: angefangene Folge, sonst erste ungesehene
    val resume = eps.firstOrNull { it.positionMs > 0 && !it.played } ?: eps.firstOrNull { !it.played } ?: eps.firstOrNull()
    Box(Modifier.fillMaxSize()) {
        Backdrop(vm.repo.image(s, "Backdrop"))
        Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 16.dp)) {
            Text(s.name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(listOfNotNull(s.meta().ifBlank { null }, seasons.size.takeIf { it > 0 }?.let { if (it == 1) "1 Staffel" else "$it Staffeln" }).joinToString("  ·  "),
                color = p.primary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
            Text(s.overview ?: "", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(780.dp))
            if (s.people.isNotEmpty()) Text("Mit: ${s.people.joinToString(", ")}", color = p.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(780.dp).padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (resume != null) TvButton(
                    (if (resume.positionMs > 0) "▶ Weiter: " else "▶ ") + "S${resume.parentIndex ?: 0} E${resume.index ?: 0}",
                    onClick = { context.openPlayer(ContentKind.EPISODE, JF_PREFIX + resume.id, if (resume.played) 0 else resume.positionMs) }, primary = true)
                TvButton(if (s.favorite) "♥ Favorit" else "♡ Favorit", onClick = { vm.toggleFavorite() })
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                TvButton("Trailer", onClick = { scope.launch { openJfTrailer(context, vm.repo, s) } })
                Text("Lange OK auf einer Folge: gesehen / ungesehen", color = p.textDim, fontSize = 12.sp)
            }
            error?.let { Text("⚠ $it", color = Color(0xFFFF8A80), fontSize = 13.sp) }
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusBleed(), contentPadding = PaddingValues(horizontal = FocusBleedH, vertical = FocusBleedV)) {
                items(seasons, key = { it.id }) { st -> TvChip(st.name, st.id == season, { vm.selectSeason(st.id) }) }
            }
            Spacer(Modifier.height(10.dp))
            if (epsLoading && eps.isEmpty()) UltraLoader(label = "Folgen werden geladen …")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
                items(eps, key = { it.id }) { ep ->
                    JfEpisodeRow(ep, vm.repo.image(ep, width = 480),
                        onLongClick = { vm.togglePlayed(ep) }) {
                        context.openPlayer(ContentKind.EPISODE, JF_PREFIX + ep.id, if (ep.played) 0 else ep.positionMs)
                    }
                }
            }
        }
    }
}

@Composable
private fun JfEpisodeRow(ep: JfItem, image: String?, onLongClick: () -> Unit, onClick: () -> Unit) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().padding(end = 20.dp)
        .tvFocusable(shape, onClick, onLongClick = onLongClick, scaleOnFocus = false, onFocus = { focused = it })
        .background(if (focused) p.surfaceFocused else p.surface.copy(alpha = 0.85f), shape).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(176.dp, 99.dp).background(Color.Black, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))) {
            if (image != null) AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            val pr = ep.progress
            if (pr != null && !ep.played) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.6f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(pr).height(4.dp).background(p.primary))
            }
            if (ep.played) Text("✓", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(p.primary.copy(alpha = 0.85f), RoundedCornerShape(50)).padding(horizontal = 7.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("S${ep.parentIndex ?: 0} · E${ep.index ?: 0} · ${ep.name}", color = if (ep.played) p.textDim else p.text, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(ep.runtimeMs.takeIf { it > 0 }?.let { "${it / 60_000} Min." }, if (ep.played) "Gesehen" else null,
                ep.positionMs.takeIf { it > 0 && !ep.played }?.let { "bei ${it / 60_000} Min." }).joinToString(" · "), color = p.textDim, fontSize = 12.sp)
            ep.overview?.let { Text(it, color = p.textDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}


/** Trailer: lokale Trailer-Datei vom Jellyfin-Server, sonst hinterlegter Online-Trailer, sonst YouTube-Suche – alles in der App. */
private suspend fun openJfTrailer(context: android.content.Context, repo: de.ostlertekker.ultraiptv.core.jellyfin.JellyfinRepository, item: de.ostlertekker.ultraiptv.core.jellyfin.JfItem) {
    val local = if (item.localTrailers > 0) runCatching { repo.localTrailerId(item.id) }.getOrNull() else null
    if (local != null) context.openPlayer(ContentKind.MOVIE, JF_PREFIX + local, 0)
    else de.ostlertekker.ultraiptv.feature.web.WebAppActivity.openTrailer(context, item.remoteTrailer, item.name)
}
