package de.ostlertekker.ultraiptv.feature.search

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import de.ostlertekker.ultraiptv.core.ui.FocusBleedV
import de.ostlertekker.ultraiptv.core.ui.FocusBleedH
import de.ostlertekker.ultraiptv.core.ui.focusBleed
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import de.ostlertekker.ultraiptv.feature.jellyfin.tile
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

enum class SearchFilter(val label: String) { ALL("Alle"), LIVE("Live-TV"), MOVIES("Filme"), SERIES("Serien"), EPISODES("Episoden"), JELLYFIN("Jellyfin"), RADIO("Radio") }

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val content: ContentRepository,
    private val settings: SettingsRepository,
    val jellyfin: de.ostlertekker.ultraiptv.core.jellyfin.JellyfinRepository,
    val seerr: de.ostlertekker.ultraiptv.core.seerr.SeerrRepository,
    private val radio: de.ostlertekker.ultraiptv.core.radio.RadioRepository,
    val radioPlayer: de.ostlertekker.ultraiptv.core.radio.RadioPlayer,
) : ViewModel() {
    val query = MutableStateFlow("")
    // Zusätzliche Quellen – jede für sich: nicht verbunden/nicht erreichbar → einfach keine Treffer (max. 8 s warten, nie ein Fehler)
    init { runCatching { if (jellyfin.session.value == null) jellyfin.restore() } }
    val jellyfinOn = jellyfin.session.map { it != null }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), jellyfin.session.value != null)
    private fun <T> source(block: suspend (String) -> List<T>) = query.debounce(400).mapLatest { q ->
        if (q.trim().length < 2) emptyList() else runCatching { kotlinx.coroutines.withTimeoutOrNull(8_000) { block(q.trim()) } }.getOrNull().orEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val jfResults = source { q -> if (jellyfin.session.value == null) emptyList() else jellyfin.items("Movie,Series", searchTerm = q, limit = 30).first }
    val seerrResults = source { q -> seerr.search(q).filter { it.status != de.ostlertekker.ultraiptv.core.seerr.SeerrStatus.AVAILABLE }.take(20) }
    val radioResults = source { q -> radio.search(q).take(20) }
    val filter = MutableStateFlow(SearchFilter.ALL)
    val history = settings.settings.map { it.searchHistory }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val results = query.debounce(300).mapLatest { q -> content.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ContentRepository.SearchResults())

    fun saveQuery() = viewModelScope.launch { settings.addSearch(query.value) }
    fun clearHistory() = viewModelScope.launch { settings.clearSearchHistory() }
}

/** Titelsuche über Live-TV, Filme, Serien und Episoden – per Tastatur oder Spracheingabe. */
@Composable
fun SearchScreen(nav: NavController, vm: SearchViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val p = LocalUltraTheme.current.palette
    val q by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val r by vm.results.collectAsStateWithLifecycle()
    val jf by vm.jfResults.collectAsStateWithLifecycle()
    val sr by vm.seerrResults.collectAsStateWithLifecycle()
    val radio by vm.radioResults.collectAsStateWithLifecycle()
    val jfOn by vm.jellyfinOn.collectAsStateWithLifecycle()
    var seerrItem by remember { mutableStateOf<de.ostlertekker.ultraiptv.core.seerr.SeerrItem?>(null) }
    seerrItem?.let { de.ostlertekker.ultraiptv.feature.jellyfin.SeerrRequestDialog(it, vm.seerr) { seerrItem = null } }
    val show = { f: SearchFilter -> filter == SearchFilter.ALL || filter == f }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
        item { ScreenTitle(tr("Suche"), subtitle = tr("Nach Titel suchen – Sender, Filme, Serien und Episoden")) }
        item {
            val voice = de.ostlertekker.ultraiptv.core.ui.rememberVoiceSearch { vm.query.value = it; vm.saveQuery() }
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvTextField(q, { vm.query.value = it }, tr("Titel eingeben (OK drücken für Tastatur)"), modifier = Modifier.width(620.dp))
                de.ostlertekker.ultraiptv.core.ui.VoiceSearchButton(voice)
            }
        }
        if (q.isBlank() && history.isNotEmpty()) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(start = FocusBleedH, end = FocusBleedH, top = 10.dp + FocusBleedV, bottom = 10.dp + FocusBleedV), modifier = Modifier.focusBleed()) {
                items(history, key = { it }) { h -> TvChip(h, false, { vm.query.value = h }) }
                item { TvChip(tr("Verlauf löschen"), false, { vm.clearHistory() }) }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(start = FocusBleedH, end = FocusBleedH, top = 8.dp + FocusBleedV, bottom = 8.dp + FocusBleedV), modifier = Modifier.focusBleed()) {
                // Jellyfin-Filter nur, wenn Jellyfin verbunden ist
                items(SearchFilter.entries.filter { it != SearchFilter.JELLYFIN || jfOn }, key = { it.name }) { f -> TvChip(f.label, f == filter, { vm.filter.value = f }) }
            }
        }
        if (q.trim().length >= 2 && r.isEmpty && jf.isEmpty() && sr.isEmpty() && radio.isEmpty()) item { Text(trf("Keine Treffer für „{0}“.", q), color = p.textDim, fontSize = 16.sp) }
        if (show(SearchFilter.LIVE) && r.channels.isNotEmpty()) item {
            SectionHeader(trf("Live-TV ({0})", r.channels.size))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.focusBleed(), contentPadding = PaddingValues(horizontal = FocusBleedH, vertical = FocusBleedV)) {
                items(r.channels, key = { it.id }) { c ->
                    FocusCard(c.name, c.logo, 200.dp, 112.dp, contentScale = ContentScale.Fit, subtitle = c.category,
                        onClick = { vm.saveQuery(); context.openPlayer(ContentKind.LIVE, c.id, category = c.category) })
                }
            }
        }
        if (show(SearchFilter.MOVIES) && r.movies.isNotEmpty()) item {
            SectionHeader(trf("Filme ({0})", r.movies.size))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.focusBleed(), contentPadding = PaddingValues(horizontal = FocusBleedH, vertical = FocusBleedV)) {
                items(r.movies, key = { it.id }) { m ->
                    FocusCard(m.name, m.poster, 150.dp, 222.dp, subtitle = listOfNotNull(m.year, m.genre).joinToString(" · ").ifBlank { null },
                        onClick = { vm.saveQuery(); nav.navigate(Routes.movie(m.id)) })
                }
            }
        }
        if (show(SearchFilter.SERIES) && r.series.isNotEmpty()) item {
            SectionHeader(trf("Serien ({0})", r.series.size))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.focusBleed(), contentPadding = PaddingValues(horizontal = FocusBleedH, vertical = FocusBleedV)) {
                items(r.series, key = { it.id }) { s ->
                    FocusCard(s.name, s.poster, 150.dp, 222.dp, subtitle = s.year, onClick = { vm.saveQuery(); nav.navigate(Routes.seriesDetail(s.id)) })
                }
            }
        }
        if (show(SearchFilter.EPISODES) && r.episodes.isNotEmpty()) item {
            SectionHeader(trf("Episoden ({0})", r.episodes.size))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.focusBleed(), contentPadding = PaddingValues(horizontal = FocusBleedH, vertical = FocusBleedV)) {
                items(r.episodes, key = { it.id }) { e ->
                    FocusCard(e.title, e.image, 240.dp, 135.dp, subtitle = "S${e.season} E${e.episode}",
                        onClick = { vm.saveQuery(); context.openPlayer(ContentKind.EPISODE, e.id) })
                }
            }
        }
        if (show(SearchFilter.JELLYFIN) && jf.isNotEmpty()) item {
            SectionHeader(trf("Jellyfin ({0})", jf.size))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.focusBleed(), contentPadding = PaddingValues(horizontal = FocusBleedH, vertical = FocusBleedV)) {
                items(jf, key = { "jf" + it.id }) { i ->
                    val t = vm.jellyfin.tile(i)
                    FocusCard(i.name, t.image, 150.dp, 222.dp, subtitle = t.subtitle,
                        onClick = { vm.saveQuery(); de.ostlertekker.ultraiptv.feature.jellyfin.openJellyfin(nav, context, t, play = false) })
                }
            }
        }
        if (show(SearchFilter.JELLYFIN) && sr.isNotEmpty()) item {
            de.ostlertekker.ultraiptv.feature.jellyfin.SeerrRow(tr("Nicht in Jellyfin? Bei Seerr anfragen"), sr) { seerrItem = it }
        }
        if (show(SearchFilter.RADIO) && radio.isNotEmpty()) item {
            SectionHeader(trf("Radio ({0})", radio.size))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.focusBleed(), contentPadding = PaddingValues(horizontal = FocusBleedH, vertical = FocusBleedV)) {
                items(radio, key = { "r" + it.id }) { s ->
                    FocusCard(s.name, s.logo, 160.dp, 160.dp, contentScale = ContentScale.Fit, subtitle = s.country.ifBlank { null },
                        onClick = { vm.saveQuery(); vm.radioPlayer.play(s, radio) })
                }
            }
        }
    }
}
