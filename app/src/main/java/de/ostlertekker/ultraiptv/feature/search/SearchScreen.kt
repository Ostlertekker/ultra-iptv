package de.ostlertekker.ultraiptv.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.ui.FocusCard
import de.ostlertekker.ultraiptv.core.ui.ScreenTitle
import de.ostlertekker.ultraiptv.core.ui.SectionHeader
import de.ostlertekker.ultraiptv.core.ui.TvChip
import de.ostlertekker.ultraiptv.core.ui.TvTextField
import de.ostlertekker.ultraiptv.openPlayer
import de.ostlertekker.ultraiptv.ui.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchFilter(val label: String) { ALL("Alle"), LIVE("Live-TV"), MOVIES("Filme"), SERIES("Serien"), EPISODES("Episoden") }

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(private val content: ContentRepository, private val settings: SettingsRepository) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(SearchFilter.ALL)
    val history = settings.settings.map { it.searchHistory }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val results = query.debounce(300).mapLatest { q -> content.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ContentRepository.SearchResults())

    fun saveQuery() = viewModelScope.launch { settings.addSearch(query.value) }
    fun clearHistory() = viewModelScope.launch { settings.clearSearchHistory() }
}

/** Titelsuche über Live-TV, Filme, Serien und Episoden (keine Sprach- oder „natürliche“ Suche). */
@Composable
fun SearchScreen(nav: NavController, vm: SearchViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val p = LocalUltraTheme.current.palette
    val q by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val r by vm.results.collectAsStateWithLifecycle()
    val show = { f: SearchFilter -> filter == SearchFilter.ALL || filter == f }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
        item { ScreenTitle("Suche", subtitle = "Nach Titel suchen – Sender, Filme, Serien und Episoden") }
        item { TvTextField(q, { vm.query.value = it }, "Titel eingeben (OK drücken für Tastatur)", modifier = Modifier.width(620.dp)) }
        if (q.isBlank() && history.isNotEmpty()) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                items(history, key = { it }) { h -> TvChip(h, false, { vm.query.value = h }) }
                item { TvChip("Verlauf löschen", false, { vm.clearHistory() }) }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(SearchFilter.entries, key = { it.name }) { f -> TvChip(f.label, f == filter, { vm.filter.value = f }) }
            }
        }
        if (q.trim().length >= 2 && r.isEmpty) item { Text("Keine Treffer für „$q“.", color = p.textDim, fontSize = 16.sp) }
        if (show(SearchFilter.LIVE) && r.channels.isNotEmpty()) item {
            SectionHeader("Live-TV (${r.channels.size})")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(r.channels, key = { it.id }) { c ->
                    FocusCard(c.name, c.logo, 200.dp, 112.dp, contentScale = ContentScale.Fit, subtitle = c.category,
                        onClick = { vm.saveQuery(); context.openPlayer(ContentKind.LIVE, c.id, category = c.category) })
                }
            }
        }
        if (show(SearchFilter.MOVIES) && r.movies.isNotEmpty()) item {
            SectionHeader("Filme (${r.movies.size})")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(r.movies, key = { it.id }) { m ->
                    FocusCard(m.name, m.poster, 150.dp, 222.dp, subtitle = listOfNotNull(m.year, m.genre).joinToString(" · ").ifBlank { null },
                        onClick = { vm.saveQuery(); nav.navigate(Routes.movie(m.id)) })
                }
            }
        }
        if (show(SearchFilter.SERIES) && r.series.isNotEmpty()) item {
            SectionHeader("Serien (${r.series.size})")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(r.series, key = { it.id }) { s ->
                    FocusCard(s.name, s.poster, 150.dp, 222.dp, subtitle = s.year, onClick = { vm.saveQuery(); nav.navigate(Routes.seriesDetail(s.id)) })
                }
            }
        }
        if (show(SearchFilter.EPISODES) && r.episodes.isNotEmpty()) item {
            SectionHeader("Episoden (${r.episodes.size})")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(r.episodes, key = { it.id }) { e ->
                    FocusCard(e.title, e.image, 240.dp, 135.dp, subtitle = "S${e.season} E${e.episode}",
                        onClick = { vm.saveQuery(); context.openPlayer(ContentKind.EPISODE, e.id) })
                }
            }
        }
    }
}
