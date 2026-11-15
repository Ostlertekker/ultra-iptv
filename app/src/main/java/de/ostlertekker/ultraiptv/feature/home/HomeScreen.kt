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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.sqlite.db.SimpleSQLiteQuery
import de.ostlertekker.ultraiptv.core.database.ContentDao
import de.ostlertekker.ultraiptv.core.model.Countries
import de.ostlertekker.ultraiptv.core.notify.NotificationCenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val content: ContentRepository,
    private val user: UserRepository,
    sync: SyncRepository,
    private val dao: ContentDao,
    notifications: NotificationCenter,
) : ViewModel() {
    val unread = notifications.unread.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val latestNotification = notifications.notifications.map { it.firstOrNull { n -> !n.isRead } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Dynamische Inhaltskategorien – werden aus Katalog, Verlauf und Gerätesprache berechnet. */
    data class DynamicRow(val title: String, val movies: List<MovieEntity> = emptyList(), val channels: List<ChannelEntity> = emptyList())
    val dynamicRows = MutableStateFlow<List<DynamicRow>>(emptyList())

    init {
        viewModelScope.launch {
            combine(content.movieCount, content.channelCount, user.filter) { m, c, f -> Triple(m, c, f.showAdult) }.collect { (_, _, adult) ->
                dynamicRows.value = buildDynamicRows(adult)
            }
        }
    }

    private suspend fun buildDynamicRows(adult: Boolean): List<DynamicRow> {
        val a = if (adult) "" else " AND adult = 0"
        fun mq(where: String, order: String, vararg args: Any) = SimpleSQLiteQuery("SELECT * FROM movies WHERE ($where)$a ORDER BY $order LIMIT 30", args)
        fun cq(where: String, vararg args: Any) = SimpleSQLiteQuery("SELECT * FROM channels WHERE ($where)$a ORDER BY number, name LIMIT 30", args)
        val rows = ArrayList<DynamicRow>()
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        dao.rawMovies(mq("addedAt > ?", "addedAt DESC", weekAgo)).takeIf { it.size >= 3 }?.let { rows += DynamicRow("Neu diese Woche", movies = it) }
        dao.rawMovies(mq("name LIKE '%4K%' OR name LIKE '%UHD%' OR category LIKE '%4K%' OR category LIKE '%UHD%'", "addedAt DESC"))
            .takeIf { it.size >= 3 }?.let { rows += DynamicRow("4K / UHD", movies = it) }
        dao.rawChannels(cq("category LIKE '%sport%' OR name LIKE '%sport%' OR name LIKE '%dazn%'")).takeIf { it.size >= 3 }
            ?.let { rows += DynamicRow("Sport live", channels = it) }
        dao.rawMovies(mq("genre LIKE '%anim%' OR genre LIKE '%famil%' OR genre LIKE '%kinder%' OR category LIKE '%kids%' OR category LIKE '%kinder%' OR category LIKE '%family%'", "rating DESC"))
            .takeIf { it.size >= 3 }?.let { rows += DynamicRow("Kinder & Familie", movies = it) }
        dao.rawMovies(mq("year != '' AND CAST(year AS INTEGER) BETWEEN 1950 AND 1999", "rating DESC")).takeIf { it.size >= 3 }
            ?.let { rows += DynamicRow("Klassiker", movies = it) }
        val country = java.util.Locale.getDefault().country.uppercase()
        Countries.byCode(country)?.let { c ->
            dao.rawChannels(cq("country = ?", c.code)).takeIf { it.size >= 3 }?.let { rows += DynamicRow("Sender aus ${c.name} ${c.flag}", channels = it) }
        }
        // Lieblingsgenre aus dem Verlauf
        val recent = user.recentlyWatched.first().filter { it.kind == ContentKind.MOVIE.name }.take(20)
        val genre = recent.mapNotNull { dao.movie(it.itemId)?.genre?.split(',', '/')?.firstOrNull()?.trim() }
            .filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        if (genre != null) dao.rawMovies(mq("genre LIKE ?", "rating DESC", "%$genre%")).takeIf { it.size >= 3 }
            ?.let { rows += DynamicRow("Dein Lieblingsgenre: $genre", movies = it) }
        return rows
    }
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
    val dynamicRows by vm.dynamicRows.collectAsStateWithLifecycle()
    val unread by vm.unread.collectAsStateWithLifecycle()
    val latestNotification by vm.latestNotification.collectAsStateWithLifecycle()
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
        val note = latestNotification
        if (note != null) item {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).background(p.surfaceFocused, RoundedCornerShape(12.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("🔔 ${if (unread > 1) "$unread neue · " else ""}${note.title}", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TvButton("Öffnen", onClick = { nav.navigate(Routes.SETTINGS) })
            }
        }
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
                itemsIndexed(cw, key = { _, it -> it.kind + it.itemId }) { i, pr ->
                    FocusCard(pr.title, pr.image, 260.dp, 146.dp, entranceIndex = i,
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
        if (recommended.isNotEmpty()) item { MovieRow("Weil du ähnliche Filme gesehen hast", recommended) { nav.navigate(Routes.movie(it.id)) } }
        if (newMovies.isNotEmpty()) item { MovieRow("Neu hinzugefügt", newMovies) { nav.navigate(Routes.movie(it.id)) } }
        if (popular.isNotEmpty() && popular.any { (it.rating ?: 0.0) > 0 }) item { MovieRow("Beliebte Filme", popular) { nav.navigate(Routes.movie(it.id)) } }
        dynamicRows.forEach { row ->
            item(key = "dyn_" + row.title) {
                if (row.movies.isNotEmpty()) MovieRow(row.title, row.movies) { nav.navigate(Routes.movie(it.id)) }
                else ChannelRow(row.title, row.channels) { context.openPlayer(ContentKind.LIVE, it.id, category = it.category) }
            }
        }
        if (series.isNotEmpty()) item {
            SectionHeader("Serien")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(series, key = { _, s -> s.id }) { i, s ->
                    FocusCard(s.name, s.poster, 150.dp, 222.dp, onClick = { nav.navigate(Routes.seriesDetail(s.id)) }, subtitle = s.year, entranceIndex = i)
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
        itemsIndexed(movies, key = { _, m -> m.id }, contentType = { _, _ -> "poster" }) { i, m ->
            FocusCard(m.name, m.poster, 150.dp, 222.dp, onClick = { onClick(m) }, entranceIndex = i,
                subtitle = listOfNotNull(m.year, m.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · ").ifBlank { null })
        }
    }
}

@Composable
private fun ChannelRow(title: String, channels: List<ChannelEntity>, onClick: (ChannelEntity) -> Unit) {
    SectionHeader(title)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(channels, key = { _, c -> c.id }, contentType = { _, _ -> "channel" }) { i, c ->
            FocusCard(c.name, c.logo, 200.dp, 112.dp, onClick = { onClick(c) }, entranceIndex = i, subtitle = c.category, contentScale = ContentScale.Fit)
        }
    }
}
