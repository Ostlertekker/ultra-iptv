package de.ostlertekker.ultraiptv.feature.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.data.ContentRepository
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.ui.FocusCard
import de.ostlertekker.ultraiptv.core.ui.ScreenTitle
import de.ostlertekker.ultraiptv.core.ui.SectionHeader
import de.ostlertekker.ultraiptv.openPlayer
import de.ostlertekker.ultraiptv.ui.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavoritesViewModel @Inject constructor(content: ContentRepository, user: UserRepository) : ViewModel() {
    val channels = user.favoriteIds(ContentKind.LIVE).flatMapLatest { content.channelsByIds(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val movies = user.favoriteIds(ContentKind.MOVIE).flatMapLatest { content.moviesByIds(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val series = user.favoriteIds(ContentKind.SERIES).flatMapLatest { content.seriesByIds(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@Composable
fun FavoritesScreen(nav: NavController, vm: FavoritesViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val p = LocalUltraTheme.current.palette
    val channels by vm.channels.collectAsStateWithLifecycle()
    val movies by vm.movies.collectAsStateWithLifecycle()
    val series by vm.series.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item { ScreenTitle("FAVORITEN", subtitle = "Sender, Merkliste und Serien deines Profils") }
        if (channels.isEmpty() && movies.isEmpty() && series.isEmpty()) item {
            Text("Noch keine Favoriten. In Live TV, bei Filmen oder Serien „Favorit“ wählen.", color = p.textDim, fontSize = 16.sp)
        }
        if (channels.isNotEmpty()) item {
            SectionHeader("Sender")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(channels, key = { it.id }) { c ->
                    FocusCard(c.name, c.logo, 200.dp, 112.dp, onClick = { context.openPlayer(ContentKind.LIVE, c.id, category = c.category) },
                        contentScale = ContentScale.Fit, subtitle = c.category)
                }
            }
        }
        if (movies.isNotEmpty()) item {
            SectionHeader("Merkliste – Filme")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(movies, key = { it.id }) { m -> FocusCard(m.name, m.poster, 150.dp, 222.dp, onClick = { nav.navigate(Routes.movie(m.id)) }) }
            }
        }
        if (series.isNotEmpty()) item {
            SectionHeader("Serien")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(series, key = { it.id }) { s -> FocusCard(s.name, s.poster, 150.dp, 222.dp, onClick = { nav.navigate(Routes.seriesDetail(s.id)) }) }
            }
        }
    }
}
