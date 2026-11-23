package de.ostlertekker.ultraiptv.feature.movies

import androidx.compose.foundation.horizontalScroll
import de.ostlertekker.ultraiptv.core.ui.FocusBleedV
import de.ostlertekker.ultraiptv.core.ui.FocusBleedH
import de.ostlertekker.ultraiptv.core.ui.focusBleed
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import de.ostlertekker.ultraiptv.core.database.MovieEntity
import de.ostlertekker.ultraiptv.core.database.ProgressEntity
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.ui.FocusCard
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.TvChip
import de.ostlertekker.ultraiptv.openPlayer
import de.ostlertekker.ultraiptv.ui.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val PAGE = 240

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MoviesViewModel @Inject constructor(content: ContentRepository, user: UserRepository) : ViewModel() {
    /** Endlose Reihen der Übersicht. */
    val pager = de.ostlertekker.ultraiptv.feature.common.RowPager()
    /** Kino-Übersicht: Weiterschauen, Merkliste, Neu, Beliebt, Empfehlungen, Zuletzt angesehen, Genre-Reihen – alles aus echten Daten. */
    val overview: StateFlow<List<de.ostlertekker.ultraiptv.feature.common.CinemaRow>> = run {
        fun MovieEntity.tile() = de.ostlertekker.ultraiptv.feature.common.CinemaTile(id, name, poster,
            listOfNotNull(year, genre?.split(',')?.firstOrNull()?.trim(), durationSec?.takeIf { it > 0 }?.let { "${it / 60} Min." },
                rating?.takeIf { it > 0 }?.let { de.ostlertekker.ultraiptv.core.ui.ratingLabel(it) }).joinToString(" · ").ifBlank { null },
            backdrop = backdrop, plot = plot, kind = "MOVIE")
        fun ProgressEntity.tile(resume: Boolean) = de.ostlertekker.ultraiptv.feature.common.CinemaTile(itemId, title, image,
            if (durationMs > 0) "Noch ${((durationMs - positionMs) / 60000).coerceAtLeast(1)} Min." else null,
            progress = if (durationMs > 0) positionMs.toFloat() / durationMs else null, positionMs = positionMs, resume = resume, kind = "MOVIE")
        val genreRows = content.movieGenres().flatMapLatest { gs ->
            val top = gs.take(5)
            if (top.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList())
            else combine(top.map { g -> pager.paged("g:$g", 24) { n -> content.movies(null, g, SortOrder.RATING, n) }.map { l -> g to l } }) { it.toList() }
        }
        val recommended = user.recentlyWatched.flatMapLatest { recent ->
            val last = recent.firstOrNull { it.kind == ContentKind.MOVIE.name && !it.itemId.startsWith("jf:") }
            if (last == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else content.movie(last.itemId).flatMapLatest { m ->
                val g = m?.genre?.split(',', '/')?.firstOrNull()?.trim()
                if (g.isNullOrBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else pager.paged("rec", 24) { n -> content.movies(null, g, SortOrder.RATING, n) }.map { l -> l.filter { it.id != m.id } }
            }
        }
        combine(
            user.continueWatching.map { l -> l.filter { it.kind == ContentKind.MOVIE.name && !it.itemId.startsWith("jf:") } },
            user.favoriteIds(ContentKind.MOVIE).flatMapLatest { content.moviesByIds(it) },
            pager.paged("new", 30) { n -> content.movies(null, null, SortOrder.NEWEST, n) },
            pager.paged("pop", 30) { n -> content.movies(null, null, SortOrder.RATING, n) },
            combine(recommended, user.recentlyWatched.map { l -> l.filter { it.kind == ContentKind.MOVIE.name && !it.itemId.startsWith("jf:") } }, genreRows) { a, b, c -> Triple(a, b, c) },
        ) { cw, watch, newest, popular, (rec, recent, genres) ->
            listOf(
                de.ostlertekker.ultraiptv.feature.common.CinemaRow("Weiterschauen", cw.map { it.tile(true) }, landscape = true),
                de.ostlertekker.ultraiptv.feature.common.CinemaRow("Meine Merkliste", watch.map { it.tile() }),
                de.ostlertekker.ultraiptv.feature.common.CinemaRow("Neu hinzugefügt", newest.map { it.tile() }, pageKey = "new"),
                de.ostlertekker.ultraiptv.feature.common.CinemaRow("Beliebte Filme", if (popular.any { (it.rating ?: 0.0) > 0 }) popular.map { it.tile() } else emptyList(), pageKey = "pop"),
                de.ostlertekker.ultraiptv.feature.common.CinemaRow("Empfehlungen für dich", rec.map { it.tile() }, pageKey = "rec"),
            ) + genres.map { (g, l) -> de.ostlertekker.ultraiptv.feature.common.CinemaRow(g, l.map { it.tile() }, pageKey = "g:$g") }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    val category = MutableStateFlow<String?>(null)
    val genre = MutableStateFlow<String?>(null)
    val sort = MutableStateFlow(SortOrder.NEWEST)
    val limit = MutableStateFlow(PAGE)

    val categories = content.movieCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val genres = content.movieGenres().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val movies: StateFlow<List<MovieEntity>> = combine(category, genre, sort, limit) { c, g, s, l -> Quad(c, g, s, l) }
        .flatMapLatest { q -> content.movies(q.c, q.g, q.s, q.l) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadMore() { if (movies.value.size >= limit.value) limit.value += PAGE }
    fun resetPaging() { limit.value = PAGE }

    private data class Quad(val c: String?, val g: String?, val s: SortOrder, val l: Int)
}

@Composable
fun MoviesScreen(nav: NavController, vm: MoviesViewModel = hiltViewModel()) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val cats by vm.categories.collectAsStateWithLifecycle()
    val movies by vm.movies.collectAsStateWithLifecycle()
    val cat by vm.category.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()

    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= grid.layoutInfo.totalItemsCount - 24) vm.loadMore()
        }
    }

    var overviewMode by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    val context = LocalContext.current
    if (overviewMode) {
        val rows by vm.overview.collectAsStateWithLifecycle()
        val hero = rows.firstOrNull { it.title == "Neu hinzugefügt" }?.tiles?.firstOrNull { !it.backdrop.isNullOrBlank() }
            ?: rows.firstOrNull { it.title == "Neu hinzugefügt" }?.tiles?.firstOrNull()
        de.ostlertekker.ultraiptv.feature.common.CinemaOverview(hero, rows, onMore = vm.pager::more,
            header = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvChip("Übersicht", true, { })
                    TvChip("Alle Filme", false, { overviewMode = false })
                }
            },
            onOpen = { nav.navigate(Routes.movie(it.id)) },
            onPlay = { context.openPlayer(ContentKind.MOVIE, it.id, it.positionMs) })
        return
    }
    // Unterkategorien links untereinander – gleicher Aufbau wie im Live-TV
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    LazyColumn(Modifier.weight(0.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Text("Filme", color = p.text, fontSize = 24.sp, fontWeight = theme.spec.titleWeight, fontFamily = theme.spec.fontFamily,
                modifier = Modifier.padding(bottom = 10.dp))
        }
        item(key = "__overview") { de.ostlertekker.ultraiptv.feature.livetv.CategoryItem("Übersicht", false) { overviewMode = true } }
        item(key = "__all") { de.ostlertekker.ultraiptv.feature.livetv.CategoryItem("Alle", cat == null) { vm.category.value = null; vm.resetPaging() } }
        items(cats, key = { it }) { c -> de.ostlertekker.ultraiptv.feature.livetv.CategoryItem(c, c == cat) { vm.category.value = c; vm.resetPaging() } }
    }
    Column(Modifier.weight(0.75f).fillMaxHeight()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusBleed(), contentPadding = PaddingValues(horizontal = FocusBleedH, vertical = FocusBleedV)) {
            items(SortOrder.entries, key = { it.name }) { s -> TvChip("↕ ${s.label}", s == sort, { vm.sort.value = s; vm.resetPaging() }) }
        }
        Spacer(Modifier.height(12.dp))
        if (movies.isEmpty()) Text("Keine Filme gefunden.", color = p.textDim)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(de.ostlertekker.ultraiptv.core.design.LocalUiPrefs.current.posterWidth), state = grid,
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(start = 8.dp + FocusBleedH, end = 8.dp + FocusBleedH, top = 8.dp + FocusBleedV, bottom = 60.dp + FocusBleedV), modifier = Modifier.focusBleed()) {
            items(movies.size, key = { movies[it].id }, contentType = { "poster" }) { i ->
                val m = movies[i]
                FocusCard(m.name, m.poster, 150.dp, 222.dp, onClick = { nav.navigate(Routes.movie(m.id)) }, entranceIndex = i % 8,
                    subtitle = listOfNotNull(m.year, m.rating?.takeIf { it > 0 }?.let { de.ostlertekker.ultraiptv.core.ui.ratingLabel(it) }).joinToString(" · ").ifBlank { null })
            }
        }
    }
    }
}

@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    content: ContentRepository,
    private val user: UserRepository,
    private val sync: SyncRepository,
    private val search: SearchRepository,
) : ViewModel() {
    val id: String = savedState.get<String>("id") ?: ""
    val movie = content.movie(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val similar = MutableStateFlow<List<MovieEntity>>(emptyList())
    val favorite = user.isFavorite(ContentKind.MOVIE, id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val progress = MutableStateFlow<ProgressEntity?>(null)
    val trailer = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            sync.loadMovieDetails(id)
            trailer.value = sync.trailers[id]
            content.movieOnce(id)?.let { similar.value = search.similarMovies(it) }
        }
        refreshProgress()
    }

    fun refreshProgress() = viewModelScope.launch { progress.value = user.progress(ContentKind.MOVIE, id) }
    fun toggleFavorite() = viewModelScope.launch { user.toggleFavorite(ContentKind.MOVIE, id) }
}

@Composable
fun MovieDetailScreen(nav: NavController, vm: MovieDetailViewModel = hiltViewModel()) {
    val similar by vm.similar.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val movie by vm.movie.collectAsStateWithLifecycle()
    val fav by vm.favorite.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }
    val m = movie ?: return
    LaunchedEffect(m.id) { runCatching { playFocus.requestFocus() } }
    androidx.lifecycle.compose.LifecycleResumeEffect(m.id) {
        vm.refreshProgress()
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize()) {
        // Kein Szenenbild: Poster unscharf als Hintergrund (scharf steht es ohnehin links)
        de.ostlertekker.ultraiptv.core.ui.PosterBackdrop(m.backdrop, m.poster, Modifier.fillMaxSize(), showPoster = false, alpha = 0.75f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.95f), 0.55f to Color.Black.copy(alpha = 0.65f), 1f to Color.Black.copy(alpha = 0.2f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        // Inhalt und „Ähnliche Titel“ untereinander statt übereinander gelegt: Die Beschreibung kürzt sich bei Platzmangel,
        // die Knöpfe bleiben immer sichtbar.
        Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 16.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(m.poster, m.name, contentScale = ContentScale.Crop,
                modifier = Modifier.heightIn(max = 356.dp).fillMaxHeight().aspectRatio(240f / 356f, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(theme.spec.corner.coerceAtMost(18.dp))))
            Spacer(Modifier.width(36.dp))
            Column(Modifier.width(640.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text(m.name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(m.year, m.genre, m.rating?.takeIf { it > 0 }?.let { de.ostlertekker.ultraiptv.core.ui.ratingLabel(it) },
                    m.durationSec?.takeIf { it > 0 }?.let { "${it / 60} Min." }, m.category).joinToString("  ·  ")
                Text(meta, color = p.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 10.dp))
                Text(m.plot ?: "Keine Beschreibung verfügbar.", color = p.textDim, fontSize = 15.sp, maxLines = 5, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (!m.actors.isNullOrBlank()) Text("Mit: ${m.actors}", color = p.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                if (!m.director.isNullOrBlank()) Text("Regie: ${m.director}", color = p.textDim, fontSize = 13.sp, maxLines = 1)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 4.dp, horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val pr = progress
                    if (pr != null && pr.positionMs > 30_000 && (pr.durationMs == 0L || pr.positionMs < pr.durationMs * 0.95)) {
                        TvButton("▶ Fortsetzen · ${pr.positionMs / 60000} Min.", onClick = { context.openPlayer(ContentKind.MOVIE, m.id, pr.positionMs) },
                            primary = true, focusRequester = playFocus)
                        TvButton("Von vorne", onClick = { context.openPlayer(ContentKind.MOVIE, m.id, 0) })
                    } else {
                        TvButton("▶ Abspielen", onClick = { context.openPlayer(ContentKind.MOVIE, m.id, 0) }, primary = true, focusRequester = playFocus)
                    }
                    val tr by vm.trailer.collectAsStateWithLifecycle()
                    TvButton("Trailer", onClick = { de.ostlertekker.ultraiptv.feature.web.WebAppActivity.openTrailer(context, tr, m.name) })
                    TvButton(if (fav) "✓ Auf Merkliste" else "+ Merkliste", onClick = { vm.toggleFavorite() })
                }
            }
        }
        if (similar.isNotEmpty()) Column(Modifier.padding(top = 12.dp)) {
            Text("Ähnliche Titel", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.focusBleed(), contentPadding = PaddingValues(horizontal = FocusBleedH, vertical = FocusBleedV)) {
                items(similar, key = { it.id }) { sm -> FocusCard(sm.name, sm.poster, 84.dp, 124.dp, onClick = { nav.navigate(Routes.movie(sm.id)) }) }
            }
        }
        }
    }
}
