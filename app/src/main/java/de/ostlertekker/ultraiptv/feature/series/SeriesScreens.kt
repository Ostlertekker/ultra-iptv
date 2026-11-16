package de.ostlertekker.ultraiptv.feature.series

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
import de.ostlertekker.ultraiptv.core.data.ContentRepository
import de.ostlertekker.ultraiptv.core.data.SortOrder
import de.ostlertekker.ultraiptv.core.data.SyncRepository
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.search.SearchRepository
import de.ostlertekker.ultraiptv.core.database.EpisodeEntity
import de.ostlertekker.ultraiptv.core.database.SeriesEntity
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.ui.FocusCard
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.TvChip
import de.ostlertekker.ultraiptv.core.ui.UltraLoader
import de.ostlertekker.ultraiptv.core.ui.tvFocusable
import de.ostlertekker.ultraiptv.openPlayer
import de.ostlertekker.ultraiptv.ui.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val PAGE = 240

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SeriesViewModel @Inject constructor(content: ContentRepository) : ViewModel() {
    val category = MutableStateFlow<String?>(null)
    val sort = MutableStateFlow(SortOrder.NEWEST)
    val limit = MutableStateFlow(PAGE)
    val categories = content.seriesCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val series: StateFlow<List<SeriesEntity>> = combine(category, sort, limit) { c, s, l -> Triple(c, s, l) }
        .flatMapLatest { (c, s, l) -> content.series(c, s, l) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadMore() { if (series.value.size >= limit.value) limit.value += PAGE }
}

@Composable
fun SeriesScreen(nav: NavController, vm: SeriesViewModel = hiltViewModel()) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val cats by vm.categories.collectAsStateWithLifecycle()
    val list by vm.series.collectAsStateWithLifecycle()
    val cat by vm.category.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= grid.layoutInfo.totalItemsCount - 24) vm.loadMore()
        }
    }
    Column(Modifier.fillMaxSize().padding(start = 20.dp, top = 20.dp, end = 20.dp)) {
        Text("Serien", color = p.text, fontSize = 26.sp, fontWeight = theme.spec.titleWeight, fontFamily = theme.spec.fontFamily)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SortOrder.entries, key = { it.name }) { s -> TvChip("↕ ${s.label}", s == sort, { vm.sort.value = s }) }
            item { Spacer(Modifier.width(16.dp)) }
            item { TvChip("Alle", cat == null, { vm.category.value = null }) }
            items(cats, key = { it }) { c -> TvChip(c, c == cat, { vm.category.value = c }) }
        }
        Spacer(Modifier.height(12.dp))
        if (list.isEmpty()) Text("Keine Serien gefunden.", color = p.textDim)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(de.ostlertekker.ultraiptv.core.design.LocalUiPrefs.current.posterWidth), state = grid,
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 60.dp, start = 8.dp, end = 8.dp),
        ) {
            items(list.size, key = { list[it].id }) { i ->
                val s = list[i]
                FocusCard(s.name, s.poster, 150.dp, 222.dp, onClick = { nav.navigate(Routes.seriesDetail(s.id)) }, subtitle = s.year, entranceIndex = i % 8)
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    content: ContentRepository,
    private val user: UserRepository,
    private val sync: SyncRepository,
    private val search: SearchRepository,
) : ViewModel() {
    val id: String = savedState.get<String>("id") ?: ""
    val similar = MutableStateFlow<List<SeriesEntity>>(emptyList())
    val series = content.seriesItem(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val episodes = content.episodesOf(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val favorite = user.isFavorite(ContentKind.SERIES, id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val progress = user.seriesProgress(id).map { list -> list.associateBy { it.itemId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val season = MutableStateFlow<Int?>(null)
    val loading = MutableStateFlow(true)
    val loadFailed = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            loadFailed.value = !sync.loadSeriesEpisodes(id)
            content.seriesOnce(id)?.let { similar.value = search.similarSeries(it) }
            loading.value = false
        }
    }

    fun toggleFavorite() = viewModelScope.launch { user.toggleFavorite(ContentKind.SERIES, id) }
}

@Composable
fun SeriesDetailScreen(nav: NavController, vm: SeriesDetailViewModel = hiltViewModel()) {
    val similar by vm.similar.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val s by vm.series.collectAsStateWithLifecycle()
    val eps by vm.episodes.collectAsStateWithLifecycle()
    val fav by vm.favorite.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val selSeason by vm.season.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val failed by vm.loadFailed.collectAsStateWithLifecycle()
    val series = s ?: return
    val seasons = eps.map { it.season }.distinct().sorted()
    val season = selSeason ?: seasons.firstOrNull()
    val shown = eps.filter { it.season == season }

    // "Weiterschauen": zuletzt begonnene oder nächste ungesehene Folge
    val resume: EpisodeEntity? = run {
        val started = eps.filter { progress[it.id] != null }.maxByOrNull { progress[it.id]!!.updatedAt }
        if (started == null) eps.firstOrNull()
        else {
            val pr = progress[started.id]!!
            val done = pr.durationMs > 0 && pr.positionMs > pr.durationMs * 0.95
            if (done) eps.getOrNull(eps.indexOf(started) + 1) ?: started else started
        }
    }

    Box(Modifier.fillMaxSize()) {
        AsyncImage(series.backdrop ?: series.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.35f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(p.bgTop, p.bgTop.copy(alpha = 0.85f), Color.Transparent))))
        Column(Modifier.fillMaxSize().padding(32.dp)) {
            Text(series.name, color = p.text, fontSize = 34.sp, fontWeight = theme.spec.titleWeight, fontFamily = theme.spec.fontFamily)
            Text(listOfNotNull(series.year, series.genre, series.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString("  ·  "),
                color = p.primary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
            Text(series.plot ?: "", color = p.textDim, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(760.dp))
            if (!series.actors.isNullOrBlank()) Text("Mit: ${series.actors}", color = p.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(760.dp).padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (resume != null) {
                    val pr = progress[resume.id]
                    TvButton(if (pr != null && pr.positionMs > 30_000) "▶ Weiter: S${resume.season} E${resume.episode}" else "▶ S${resume.season} E${resume.episode} abspielen",
                        onClick = { context.openPlayer(ContentKind.EPISODE, resume.id, pr?.positionMs ?: 0) }, primary = true)
                }
                TvButton(if (fav) "★ Favorit" else "☆ Favorit", onClick = { vm.toggleFavorite() })
            }
            Spacer(Modifier.height(14.dp))
            if (loading && eps.isEmpty()) UltraLoader(label = "Episoden werden geladen …")
            if (!loading && eps.isEmpty()) Text(if (failed) "Episoden konnten nicht geladen werden." else "Keine Episoden vorhanden.", color = p.textDim)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons, key = { it }) { n -> TvChip("Staffel $n", n == season, { vm.season.value = n }) }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
                items(shown, key = { it.id }) { ep ->
                    val pr = progress[ep.id]
                    EpisodeRow(ep, if (pr != null && pr.durationMs > 0) pr.positionMs.toFloat() / pr.durationMs else null) {
                        context.openPlayer(ContentKind.EPISODE, ep.id, if (pr != null && pr.durationMs > 0 && pr.positionMs < pr.durationMs * 0.95) pr.positionMs else 0)
                    }
                }
                if (similar.isNotEmpty()) item {
                    Text("ÄHNLICHE SERIEN", color = p.textDim, fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(similar, key = { it.id }) { sm -> FocusCard(sm.name, sm.poster, 110.dp, 162.dp, onClick = { nav.navigate(Routes.seriesDetail(sm.id)) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: EpisodeEntity, progress: Float?, onClick: () -> Unit) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(theme.spec.corner.coerceAtMost(14.dp))
    Row(
        Modifier.fillMaxWidth().padding(end = 20.dp)
            .tvFocusable(shape, onClick, scaleOnFocus = false, onFocus = { focused = it })
            .background(if (focused) p.surfaceFocused else p.surface, shape).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(176.dp, 99.dp).background(Color.Black, RoundedCornerShape(8.dp))) {
            if (ep.image != null) AsyncImage(ep.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.6f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(p.primary))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("E${ep.episode} · ${ep.title}", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ep.durationSec != null && ep.durationSec > 0) Text("${ep.durationSec / 60} Min.", color = p.textDim, fontSize = 12.sp)
            if (ep.plot != null) Text(ep.plot, color = p.textDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
