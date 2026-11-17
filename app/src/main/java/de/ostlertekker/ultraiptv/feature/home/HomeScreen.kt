package de.ostlertekker.ultraiptv.feature.home

import kotlinx.coroutines.flow.collectLatest
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.delay
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
    private val discover: de.ostlertekker.ultraiptv.core.discover.DiscoverRepository,
) : ViewModel() {
    /** Live-Highlights (läuft jetzt, Lieblingssender zuerst) und Sport-Highlights – echte EPG-Daten, alle 5 Minuten neu. */
    val liveHighlights = MutableStateFlow<List<de.ostlertekker.ultraiptv.core.database.AiringProgram>>(emptyList())
    val sportHighlights = MutableStateFlow<List<de.ostlertekker.ultraiptv.core.database.AiringProgram>>(emptyList())
    init {
        viewModelScope.launch {
            while (true) {
                runCatching {
                    val now = System.currentTimeMillis()
                    val favs = user.favoriteIds(ContentKind.LIVE).first().toSet()
                    val airing = discover.airingAt(now, 400).filter { it.endMs > now }
                    liveHighlights.value = (airing.filter { it.channelId in favs } + airing.filter { it.channelId !in favs }.sortedBy { it.number })
                        .distinctBy { it.channelId }.take(20)
                    sportHighlights.value = discover.sportEvents(now - 2 * 3_600_000L, now + 12 * 3_600_000L).map { it.program }
                        .filter { it.endMs > now }.sortedBy { it.startMs }.distinctBy { it.channelId + it.startMs }.take(20)
                }
                kotlinx.coroutines.delay(5 * 60_000L)
            }
        }
    }
    val popularSeries: StateFlow<List<SeriesEntity>> by lazy { StateFlowOf(content.series(null, SortOrder.RATING, 30)) }
    val favMovies: StateFlow<List<MovieEntity>> by lazy { StateFlowOf(user.favoriteIds(ContentKind.MOVIE).flatMapLatest { content.moviesByIds(it) }) }
    val favSeries: StateFlow<List<SeriesEntity>> by lazy { StateFlowOf(user.favoriteIds(ContentKind.SERIES).flatMapLatest { content.seriesByIds(it) }) }
    val unread = notifications.unread.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val latestNotification = notifications.notifications.map { it.firstOrNull { n -> !n.isRead } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Dynamische Inhaltskategorien – werden aus Katalog, Verlauf und Gerätesprache berechnet. */
    data class DynamicRow(val title: String, val movies: List<MovieEntity> = emptyList(), val channels: List<ChannelEntity> = emptyList())
    val dynamicRows = MutableStateFlow<List<DynamicRow>>(emptyList())

    init {
        viewModelScope.launch {
            combine(content.movieCount, content.channelCount, user.filter) { m, c, f -> Triple(m, c, f.showAdult) }.collectLatest { (_, _, adult) ->
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
    val popularSeries by vm.popularSeries.collectAsStateWithLifecycle()
    val favMovies by vm.favMovies.collectAsStateWithLifecycle()
    val favSeries by vm.favSeries.collectAsStateWithLifecycle()
    val liveHi by vm.liveHighlights.collectAsStateWithLifecycle()
    val sportHi by vm.sportHighlights.collectAsStateWithLifecycle()
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

    // Apple-TV-Startseite: großes Titelbild (wechselt zwischen den neuesten Filmen), darunter Kachelreihen
    val heroes = remember(newMovies) {
        (newMovies.filter { !it.backdrop.isNullOrBlank() } + newMovies.filter { it.backdrop.isNullOrBlank() }).take(5)
    }
    var heroIndex by remember { mutableIntStateOf(0) }
    var heroFocused by remember { mutableStateOf(false) }
    LaunchedEffect(heroes.size, heroFocused) {
        while (heroes.size > 1 && !heroFocused) { delay(9000); heroIndex = (heroIndex + 1) % heroes.size }
    }
    val hero = heroes.getOrNull(heroIndex.coerceAtMost((heroes.size - 1).coerceAtLeast(0)))
    val heroHeight = (LocalConfiguration.current.screenHeightDp * 0.68f).dp
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
        if (hero != null) item(key = "hero") {
            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                androidx.compose.animation.Crossfade(hero, animationSpec = androidx.compose.animation.core.tween(600), label = "hero") { h ->
                    AsyncImage(h.backdrop ?: h.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.82f), 0.55f to Color.Black.copy(alpha = 0.25f), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to p.bgBottom)))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 34.dp).width(560.dp)) {
                    Text("NEU", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)
                    Text(hero.name, color = Color.White, fontSize = 42.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(hero.year?.takeIf { it.isNotBlank() }, hero.genre?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() },
                        hero.rating?.takeIf { it > 0 }?.let { de.ostlertekker.ultraiptv.core.ui.ratingLabel(it) }).joinToString("  ·  ")
                    if (meta.isNotBlank()) Text(meta, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                    if (!hero.plot.isNullOrBlank()) Text(hero.plot, color = Color.White.copy(alpha = 0.72f), fontSize = 15.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 18.dp).onFocusChanged { heroFocused = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        HeroButton("▶  Abspielen", primary = true) { context.openPlayer(ContentKind.MOVIE, hero.id) }
                        HeroButton("Details") { nav.navigate(Routes.movie(hero.id)) }
                        if (heroes.size > 1) Row(Modifier.padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            heroes.indices.forEach { i ->
                                Box(Modifier.size(if (i == heroIndex) 9.dp else 7.dp)
                                    .background(Color.White.copy(alpha = if (i == heroIndex) 0.95f else 0.4f), RoundedCornerShape(50)))
                            }
                        }
                    }
                }
            }
        } else item(key = "top_space") { Spacer(Modifier.height(100.dp)) }
        val pad = Modifier.padding(horizontal = 48.dp)
        val note = latestNotification
        if (note != null) item(key = "note") {
            Row(pad.fillMaxWidth().padding(vertical = 10.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("🔔 ${if (unread > 1) "$unread neue · " else ""}${note.title}", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TvButton("Öffnen", onClick = { nav.navigate(Routes.SETTINGS) })
            }
        }
        if (cw.isNotEmpty()) item(key = "cw") {
            Column(pad) {
                SectionHeader("Weiterschauen")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(cw, key = { _, it -> it.kind + it.itemId }) { i, pr ->
                        FocusCard(pr.title, pr.image, 300.dp, 169.dp, entranceIndex = i,
                            onClick = {
                                val kind = runCatching { ContentKind.valueOf(pr.kind) }.getOrDefault(ContentKind.MOVIE)
                                context.openPlayer(kind, pr.itemId, pr.positionMs)
                            },
                            progress = if (pr.durationMs > 0) pr.positionMs.toFloat() / pr.durationMs else null,
                            subtitle = if (pr.durationMs > 0) "Noch ${((pr.durationMs - pr.positionMs) / 60000).coerceAtLeast(1)} Min." else null)
                    }
                }
            }
        }
        // Reihenfolge laut Designkonzept: ruhig, große Bilder, nur Reihen mit echten Inhalten
        if (recentCh.isNotEmpty()) item(key = "recent") { Column(pad) { ChannelRow("Zuletzt gesehen", recentCh) { context.openPlayer(ContentKind.LIVE, it.id, category = it.category) } } }
        if (popular.isNotEmpty() && popular.any { (it.rating ?: 0.0) > 0 }) item(key = "pop") { Column(pad) { MovieRow("Beliebte Filme", popular) { nav.navigate(Routes.movie(it.id)) } } }
        if (popularSeries.isNotEmpty() && popularSeries.any { (it.rating ?: 0.0) > 0 }) item(key = "popS") { Column(pad) { SeriesRow("Beliebte Serien", popularSeries) { nav.navigate(Routes.seriesDetail(it.id)) } } }
        if (newMovies.isNotEmpty()) item(key = "new") { Column(pad) { MovieRow("Neu hinzugefügt", newMovies) { nav.navigate(Routes.movie(it.id)) } } }
        if (series.isNotEmpty()) item(key = "series") { Column(pad) { SeriesRow("Neue Serien", series) { nav.navigate(Routes.seriesDetail(it.id)) } } }
        if (favCh.isNotEmpty()) item(key = "fav") { Column(pad) { ChannelRow("Lieblingssender", favCh) { context.openPlayer(ContentKind.LIVE, it.id, category = it.category) } } }
        if (favMovies.isNotEmpty() || favSeries.isNotEmpty()) item(key = "favVod") {
            Column(pad) {
                SectionHeader("Meine Merkliste")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(favMovies, key = { "m" + it.id }) { m -> FocusCard(m.name, m.poster, 150.dp, 222.dp, onClick = { nav.navigate(Routes.movie(m.id)) }, subtitle = m.year) }
                    items(favSeries, key = { "s" + it.id }) { se -> FocusCard(se.name, se.poster, 150.dp, 222.dp, onClick = { nav.navigate(Routes.seriesDetail(se.id)) }, subtitle = se.year) }
                }
            }
        }
        if (recommended.isNotEmpty()) item(key = "rec") { Column(pad) { MovieRow("Empfehlungen für dich", recommended) { nav.navigate(Routes.movie(it.id)) } } }
        if (liveHi.isNotEmpty()) item(key = "liveHi") { Column(pad) { ProgramRow("Live-TV-Highlights", liveHi) { context.openPlayer(ContentKind.LIVE, it.channelId, category = it.category) } } }
        if (sportHi.isNotEmpty()) item(key = "sportHi") { Column(pad) { ProgramRow("Sport-Highlights", sportHi) { context.openPlayer(ContentKind.LIVE, it.channelId, category = it.category) } } }
        dynamicRows.filter { it.title != "Sport live" }.take(3).forEach { row ->
            item(key = "dyn_" + row.title) {
                Column(pad) {
                    if (row.movies.isNotEmpty()) MovieRow(row.title, row.movies) { nav.navigate(Routes.movie(it.id)) }
                    else ChannelRow(row.title, row.channels) { context.openPlayer(ContentKind.LIVE, it.id, category = it.category) }
                }
            }
        }
    }
}

@Composable
private fun SeriesRow(title: String, list: List<SeriesEntity>, onClick: (SeriesEntity) -> Unit) {
    SectionHeader(title)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(list, key = { _, s -> s.id }, contentType = { _, _ -> "poster" }) { i, s ->
            FocusCard(s.name, s.poster, 150.dp, 222.dp, onClick = { onClick(s) }, entranceIndex = i,
                subtitle = listOfNotNull(s.year, s.rating?.takeIf { it > 0 }?.let { de.ostlertekker.ultraiptv.core.ui.ratingLabel(it) }).joinToString(" · ").ifBlank { null })
        }
    }
}

/** Sendungen aus dem TV-Programm als Kacheln: Senderlogo, Titel, Sender + Uhrzeit, Fortschritt wenn sie gerade läuft. */
@Composable
private fun ProgramRow(title: String, list: List<de.ostlertekker.ultraiptv.core.database.AiringProgram>, onClick: (de.ostlertekker.ultraiptv.core.database.AiringProgram) -> Unit) {
    val now = System.currentTimeMillis()
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY) }
    SectionHeader(title)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(list, key = { it.channelId + it.startMs }, contentType = { "program" }) { pr ->
            val live = pr.startMs <= now && now < pr.endMs
            FocusCard(pr.title, pr.logo, 260.dp, 146.dp, onClick = { onClick(pr) }, contentScale = ContentScale.Fit,
                progress = if (live && pr.endMs > pr.startMs) (now - pr.startMs).toFloat() / (pr.endMs - pr.startMs) else null,
                subtitle = (if (live) "● Live · " else "") + pr.channelName + " · " + fmt.format(java.util.Date(pr.startMs)) + "–" + fmt.format(java.util.Date(pr.endMs)))
        }
    }
}

/** Apple-TV-Knopf: weiße Pille (Hauptaktion) bzw. Glas-Pille; Fokus hebt leicht an. */
@Composable
private fun HeroButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.07f else 1f, androidx.compose.animation.core.tween(140), label = "hb")
    val bg = when { focused -> Color.White; primary -> Color.White.copy(alpha = 0.88f); else -> Color.White.copy(alpha = 0.16f) }
    Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged { focused = it.isFocused }
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick)
        .background(bg, RoundedCornerShape(12.dp))
        .padding(horizontal = 28.dp, vertical = 12.dp)) {
        Text(label, color = if (focused || primary) Color(0xFF111111) else Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MovieRow(title: String, movies: List<MovieEntity>, onClick: (MovieEntity) -> Unit) {
    SectionHeader(title)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(movies, key = { _, m -> m.id }, contentType = { _, _ -> "poster" }) { i, m ->
            FocusCard(m.name, m.poster, 150.dp, 222.dp, onClick = { onClick(m) }, entranceIndex = i,
                subtitle = listOfNotNull(m.year, m.rating?.takeIf { it > 0 }?.let { de.ostlertekker.ultraiptv.core.ui.ratingLabel(it) }).joinToString(" · ").ifBlank { null })
        }
    }
}

@Composable
private fun ChannelRow(title: String, channels: List<ChannelEntity>, onClick: (ChannelEntity) -> Unit) {
    SectionHeader(title)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(channels, key = { _, c -> c.id }, contentType = { _, _ -> "channel" }) { i, c ->
            FocusCard(c.name, c.logo, 240.dp, 135.dp, onClick = { onClick(c) }, entranceIndex = i, subtitle = c.category, contentScale = ContentScale.Fit)
        }
    }
}
