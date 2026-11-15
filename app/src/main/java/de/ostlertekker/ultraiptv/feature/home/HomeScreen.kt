package de.ostlertekker.ultraiptv.feature.home

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import androidx.navigation.NavController
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.data.ContentRepository
import de.ostlertekker.ultraiptv.core.data.SortOrder
import de.ostlertekker.ultraiptv.core.data.SyncRepository
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.database.ChannelEntity
import de.ostlertekker.ultraiptv.core.database.MovieEntity
import de.ostlertekker.ultraiptv.core.database.ProgressEntity
import de.ostlertekker.ultraiptv.core.database.SeriesEntity
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.ui.FocusCard
import de.ostlertekker.ultraiptv.core.ui.SectionHeader
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.UltraLoader
import de.ostlertekker.ultraiptv.openPlayer
import de.ostlertekker.ultraiptv.ui.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    content: ContentRepository,
    user: UserRepository,
    sync: SyncRepository,
) : ViewModel() {
    private fun <T> StateFlowOf(flow: kotlinx.coroutines.flow.Flow<List<T>>): StateFlow<List<T>> =
        flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val syncState = sync.state
    val channelCount = content.channelCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)
    val movieCount = content.movieCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)
    val continueWatching: StateFlow<List<ProgressEntity>> = StateFlowOf(user.continueWatching)
    val recentChannels: StateFlow<List<ChannelEntity>> =
        StateFlowOf(user.recentChannels.flatMapLatest { list -> content.channelsByIds(list.map { it.itemId }) })
    val favoriteChannels: StateFlow<List<ChannelEntity>> =
        StateFlowOf(user.favoriteIds(ContentKind.LIVE).flatMapLatest { content.channelsByIds(it) })
    val newMovies: StateFlow<List<MovieEntity>> = StateFlowOf(content.movies(null, null, SortOrder.NEWEST, 30))
    val popularMovies: StateFlow<List<MovieEntity>> = StateFlowOf(content.movies(null, null, SortOrder.RATING, 30))
    val newSeries: StateFlow<List<SeriesEntity>> = StateFlowOf(content.series(null, SortOrder.NEWEST, 30))

    /** Empfehlungen: gleiche Genres wie zuletzt gesehene Filme (nur wenn Daten vorhanden sind). */
    val recommended: StateFlow<List<MovieEntity>> = StateFlowOf(
        user.recentlyWatched.flatMapLatest { recent ->
            val lastMovie = recent.firstOrNull { it.kind == ContentKind.MOVIE.name }
            if (lastMovie == null) flowOf(emptyList())
            else content.movie(lastMovie.itemId).flatMapLatest { m ->
                val genre = m?.genre?.split(',', '/')?.firstOrNull()?.trim()
                if (genre.isNullOrBlank()) flowOf(emptyList())
                else content.movies(null, genre, SortOrder.RATING, 24).map { list -> list.filter { it.id != m?.id } }
            }
        },
    )
}

@Composable
fun HomeScreen(nav: NavController, vm: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val cw by vm.continueWatching.collectAsStateWithLifecycle()
    val recentCh by vm.recentChannels.collectAsStateWithLifecycle()
    val favCh by vm.favoriteChannels.collectAsStateWithLifecycle()
    val newMovies by vm.newMovies.collectAsStateWithLifecycle()
    val popular by vm.popularMovies.collectAsStateWithLifecycle()
    val series by vm.newSeries.collectAsStateWithLifecycle()
    val recommended by vm.recommended.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()
    val channelCount by vm.channelCount.collectAsStateWithLifecycle()
    val movieCount by vm.movieCount.collectAsStateWithLifecycle()

    if (channelCount == 0 && movieCount == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (sync.running) UltraLoader(label = sync.message)
                else {
                    Text("Noch keine Inhalte", color = p.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(sync.lastError ?: "Richte in den Einstellungen eine autorisierte M3U- oder Xtream-Quelle ein.",
                        color = p.textDim, fontSize = 15.sp, modifier = Modifier.padding(vertical = 12.dp))
                    TvButton("Quelle einrichten", onClick = { nav.navigate(Routes.SETUP) }, primary = true)
                }
            }
        }
        return
    }

    val hero = newMovies.firstOrNull { !it.backdrop.isNullOrBlank() } ?: newMovies.firstOrNull()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 60.dp)) {
        if (hero != null) item {
            Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(theme.spec.corner.coerceAtMost(24.dp)))) {
                AsyncImage(hero.backdrop ?: hero.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(p.bgTop.copy(alpha = 0.95f), p.bgTop.copy(alpha = 0.5f), Color.Transparent))))
                Column(Modifier.align(Alignment.CenterStart).padding(32.dp).width(520.dp)) {
                    Text("NEU IM KATALOG", color = p.primary, fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                    Text(hero.name, color = p.text, fontSize = 34.sp, fontWeight = theme.spec.titleWeight, fontFamily = theme.spec.fontFamily,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!hero.plot.isNullOrBlank()) Text(hero.plot, color = p.textDim, fontSize = 14.sp, maxLines = 3,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton("▶  Abspielen", onClick = { context.openPlayer(ContentKind.MOVIE, hero.id) }, primary = true)
                        TvButton("Details", onClick = { nav.navigate(Routes.movie(hero.id)) })
                    }
                }
            }
        }
        if (cw.isNotEmpty()) item {
            SectionHeader("Weiterschauen")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(cw, key = { it.kind + it.itemId }) { pr ->
                    FocusCard(pr.title, pr.image, 260.dp, 146.dp,
                        onClick = {
                            val kind = runCatching { ContentKind.valueOf(pr.kind) }.getOrDefault(ContentKind.MOVIE)
                            context.openPlayer(kind, pr.itemId, pr.positionMs)
                        },
                        progress = if (pr.durationMs > 0) pr.positionMs.toFloat() / pr.durationMs else null,
                        subtitle = "${pr.positionMs / 60000} Min. gesehen")
                }
            }
        }
        if (favCh.isNotEmpty()) item { ChannelRow("Lieblingssender", favCh) { context.openPlayer(ContentKind.LIVE, it.id, category = it.category) } }
        if (recentCh.isNotEmpty()) item { ChannelRow("Zuletzt gesehene Sender", recentCh) { context.openPlayer(ContentKind.LIVE, it.id, category = it.category) } }
        if (recommended.isNotEmpty()) item { MovieRow("Empfohlen für dich", recommended) { nav.navigate(Routes.movie(it.id)) } }
        if (newMovies.isNotEmpty()) item { MovieRow("Neu hinzugefügt", newMovies) { nav.navigate(Routes.movie(it.id)) } }
        if (popular.isNotEmpty() && popular.any { (it.rating ?: 0.0) > 0 }) item { MovieRow("Beliebte Filme", popular) { nav.navigate(Routes.movie(it.id)) } }
        if (series.isNotEmpty()) item {
            SectionHeader("Serien")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(series, key = { it.id }) { s ->
                    FocusCard(s.name, s.poster, 150.dp, 222.dp, onClick = { nav.navigate(Routes.seriesDetail(s.id)) }, subtitle = s.year)
                }
            }
        }
        item {
            SectionHeader("Live TV")
            TvButton("Zu allen Sendern", onClick = { nav.navigate(Routes.LIVE) })
        }
    }
}

@Composable
private fun MovieRow(title: String, movies: List<MovieEntity>, onClick: (MovieEntity) -> Unit) {
    SectionHeader(title)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(movies, key = { it.id }) { m ->
            FocusCard(m.name, m.poster, 150.dp, 222.dp, onClick = { onClick(m) },
                subtitle = listOfNotNull(m.year, m.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · ").ifBlank { null })
        }
    }
}

@Composable
private fun ChannelRow(title: String, channels: List<ChannelEntity>, onClick: (ChannelEntity) -> Unit) {
    SectionHeader(title)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(channels, key = { it.id }) { c ->
            FocusCard(c.name, c.logo, 200.dp, 112.dp, onClick = { onClick(c) }, subtitle = c.category, contentScale = ContentScale.Fit)
        }
    }
}
