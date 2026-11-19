package de.ostlertekker.ultraiptv.feature.jellyfin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.jellyfin.JellyfinRepository
import de.ostlertekker.ultraiptv.core.jellyfin.JfItem
import de.ostlertekker.ultraiptv.core.ui.FocusCard
import de.ostlertekker.ultraiptv.core.ui.TvChip
import de.ostlertekker.ultraiptv.core.ui.TvTextField
import de.ostlertekker.ultraiptv.core.ui.UltraLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/** Filter der Jellyfin-Suche. */
data class JfSearchFilter(
    val query: String = "",
    /** "" = Filme + Serien, sonst "Movie", "Series" oder "Episode". */
    val type: String = "",
    /** "" = alle, sonst "IsUnplayed", "IsPlayed" oder "IsFavorite". */
    val status: String = "",
    val genre: String? = null,
    /** Jahrzehnt als Startjahr (z. B. 2010), 0 = alle, -1 = vor 1980. */
    val decade: Int = 0,
    val sort: String = "SortName",
) {
    val isDefault get() = this == JfSearchFilter(query = query)

    fun years(): List<Int>? = when {
        decade > 0 -> (decade..minOf(decade + 9, Calendar.getInstance().get(Calendar.YEAR) + 1)).toList()
        decade < 0 -> (1900..1979).toList()
        else -> null
    }

    fun types(): String = type.ifBlank { if (query.isNotBlank()) "Movie,Series,Episode" else "Movie,Series" }
}

@HiltViewModel
class JellyfinSearchViewModel @Inject constructor(val repo: JellyfinRepository) : ViewModel() {
    val filter = MutableStateFlow(JfSearchFilter())
    val results = MutableStateFlow<List<JfItem>>(emptyList())
    val total = MutableStateFlow(-1)
    val genres = MutableStateFlow<List<String>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    private var job: Job? = null
    private var loadingMore = false

    init {
        viewModelScope.launch { runCatching { repo.genres() }.onSuccess { genres.value = it } }
        search(immediate = true)
    }

    fun update(f: (JfSearchFilter) -> JfSearchFilter) {
        val old = filter.value
        filter.value = f(old)
        // Beim Tippen kurz warten (nicht bei jedem Buchstaben eine Anfrage), Filter-Wechsel sofort
        search(immediate = old.query == filter.value.query)
    }

    fun reset() { filter.value = JfSearchFilter(query = filter.value.query); search(immediate = true) }

    private fun search(immediate: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            if (!immediate) delay(450)
            results.value = emptyList(); total.value = -1; error.value = null
            loadingMore = false
            load(0)
        }
    }

    fun loadMore() {
        if (loadingMore || total.value < 0 || results.value.size >= total.value) return
        viewModelScope.launch { load(results.value.size) }
    }

    private suspend fun load(start: Int) {
        loadingMore = true
        val f = filter.value
        runCatching {
            repo.items(
                types = f.types(), sortBy = f.sort, descending = f.sort != "SortName",
                filters = f.status.ifBlank { null }, limit = 60, start = start,
                genre = f.genre, searchTerm = f.query, years = f.years(),
            )
        }.onSuccess { (list, t) ->
            if (f == filter.value) { results.value = results.value + list; total.value = t }
        }.onFailure { error.value = it.message; if (total.value < 0) total.value = 0 }
        loadingMore = false
    }
}

/** Jellyfin-Suche: Suchbegriff plus Filter für Typ, Status, Genre, Jahrzehnt und Sortierung. */
@Composable
fun JellyfinSearchScreen(nav: NavController, vm: JellyfinSearchViewModel = hiltViewModel()) {
    val p = LocalUltraTheme.current.palette
    val context = LocalContext.current
    val f by vm.filter.collectAsStateWithLifecycle()
    val list by vm.results.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    val genres by vm.genres.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= grid.layoutInfo.totalItemsCount - 18) vm.loadMore()
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Jellyfin-Suche", color = p.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (total >= 0) Text("$total Treffer", color = p.textDim, fontSize = 14.sp)
        }
        TvTextField(f.query, { q -> vm.update { it.copy(query = q) } }, "Titel, Schauspieler oder Stichwort …",
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

        FilterRow("Typ") {
            listOf("" to "Filme & Serien", "Movie" to "Filme", "Series" to "Serien", "Episode" to "Folgen").forEach { (k, l) ->
                item { TvChip(l, f.type == k, { vm.update { it.copy(type = k) } }) }
            }
        }
        FilterRow("Status") {
            listOf("" to "Alle", "IsUnplayed" to "Ungesehen", "IsPlayed" to "Gesehen", "IsFavorite" to "Favoriten").forEach { (k, l) ->
                item { TvChip(l, f.status == k, { vm.update { it.copy(status = k) } }) }
            }
        }
        FilterRow("Jahr") {
            listOf(0 to "Alle", 2020 to "2020er", 2010 to "2010er", 2000 to "2000er", 1990 to "90er", 1980 to "80er", -1 to "Älter").forEach { (k, l) ->
                item { TvChip(l, f.decade == k, { vm.update { it.copy(decade = k) } }) }
            }
        }
        FilterRow("Sortierung") {
            listOf("SortName" to "A–Z", "DateCreated" to "Neu hinzugefügt", "CommunityRating" to "Bewertung", "ProductionYear" to "Erscheinungsjahr").forEach { (k, l) ->
                item { TvChip(l, f.sort == k, { vm.update { it.copy(sort = k) } }) }
            }
            if (!f.isDefault) item { TvChip("✕ Filter zurücksetzen", false, { vm.reset() }) }
        }
        if (genres.isNotEmpty()) FilterRow("Genre") {
            item { TvChip("Alle", f.genre == null, { vm.update { it.copy(genre = null) } }) }
            items(genres, key = { it }) { g -> TvChip(g, f.genre == g, { vm.update { it.copy(genre = g) } }) }
        }

        error?.let { Text("⚠ $it", color = Color(0xFFFF8A80), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
        if (total < 0) UltraLoader(Modifier.padding(top = 24.dp), "Suche läuft …")
        else if (total == 0 && error == null) Text("Nichts gefunden – anderen Begriff oder weniger Filter probieren.",
            color = p.textDim, fontSize = 15.sp, modifier = Modifier.padding(top = 20.dp))

        LazyVerticalGrid(GridCells.Adaptive(160.dp), state = grid, horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 60.dp)) {
            items(list.size, key = { list[it].id }, contentType = { "poster" }) { i ->
                val item = list[i]
                val t = vm.repo.tile(item)
                FocusCard(item.name, t.image, 150.dp, 222.dp, onClick = { openJellyfin(nav, context, t, play = false) },
                    subtitle = t.subtitle, progress = item.progress)
            }
        }
    }
}

@Composable
private fun FilterRow(label: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val p = LocalUltraTheme.current.palette
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = p.textDim, fontSize = 13.sp, modifier = Modifier.width(92.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
