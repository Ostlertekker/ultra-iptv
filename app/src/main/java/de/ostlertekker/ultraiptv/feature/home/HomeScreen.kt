package de.ostlertekker.ultraiptv.feature.home

import de.ostlertekker.ultraiptv.core.ui.outlineButton
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.withStyle
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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateFloat
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
import de.ostlertekker.ultraiptv.core.ui.contentEntry
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
    weatherRepo: de.ostlertekker.ultraiptv.core.weather.WeatherRepository,
    newsRepo: de.ostlertekker.ultraiptv.core.news.NewsRepository,
    private val sportsRepo: de.ostlertekker.ultraiptv.core.discover.SportsRepository,
    private val appSettings: de.ostlertekker.ultraiptv.core.data.SettingsRepository,
) : ViewModel() {
    val sportEnabled = MutableStateFlow(true)
    companion object {
        /** Nur für Bildschirmtests (androidTest): feste Spiele für den Sport-Ticker. */
        @androidx.annotation.VisibleForTesting @Volatile var testSportEvents: List<de.ostlertekker.ultraiptv.core.discover.ScoreEvent>? = null
    }
    /** Sport-Ticker: nur laufende Spiele, jede Minute neu. Schalter in den Einstellungen wirkt sofort. */
    val sportTicker = MutableStateFlow<List<de.ostlertekker.ultraiptv.core.discover.ScoreEvent>>(emptyList())
    init {
        viewModelScope.launch {
            appSettings.settings.map { it.sportTickerEnabled }.distinctUntilChanged().collectLatest { on ->
                sportEnabled.value = on
                if (!on) { sportTicker.value = emptyList(); return@collectLatest }
                while (true) {
                    // Nur für Bildschirmtests: feste Beispielspiele statt Abruf
                    val b = testSportEvents?.let { de.ostlertekker.ultraiptv.core.discover.ScoreBoard(it, System.currentTimeMillis(), emptyList()) }
                        ?: runCatching { sportsRepo.scoreboard(de.ostlertekker.ultraiptv.core.discover.League.entries, maxAgeMs = 0) }.getOrNull()
                    if (b != null) sportTicker.value = b.events.filter { it.live }.sortedBy { it.startMs }.take(30)
                    delay(60_000L)
                }
            }
        }
    }

    val weather = weatherRepo.weather
    val news = newsRepo.items
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
    val weather by vm.weather.collectAsStateWithLifecycle()
    val news by vm.news.collectAsStateWithLifecycle()
    val sportTicker by vm.sportTicker.collectAsStateWithLifecycle()
    val sportEnabled by vm.sportEnabled.collectAsStateWithLifecycle()
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
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Verlässt der Fokus die Startseite nach oben (Menüleiste), Liste ganz nach oben → Titelbild immer komplett sichtbar
    LazyColumn(Modifier.fillMaxSize().onFocusChanged {
        if (!it.hasFocus && (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0))
            scope.launch { listState.animateScrollToItem(0) }
    }, state = listState, contentPadding = PaddingValues(bottom = 60.dp)) {
        if (hero != null) item(key = "hero") {
            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                androidx.compose.animation.Crossfade(hero, animationSpec = androidx.compose.animation.core.tween(600), label = "hero") { h ->
                    de.ostlertekker.ultraiptv.core.ui.PosterBackdrop(h.backdrop, h.poster, Modifier.fillMaxSize(), posterPadding = 40.dp, posterOnly = true)
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
                    Row(Modifier.padding(top = 18.dp).onFocusChanged {
                        heroFocused = it.hasFocus
                        // Zurück nach oben: Titelbild immer komplett zeigen (sonst bleibt es oben abgeschnitten)
                        if (it.hasFocus && (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0))
                            scope.launch { listState.animateScrollToItem(0) }
                    }, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        HeroButton("▶  Abspielen", primary = true, modifier = Modifier.contentEntry()) { context.openPlayer(ContentKind.MOVIE, hero.id) }
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
        // Schmale Wetterleiste unter dem Titelbild (Ort in den Einstellungen › Allgemein)
        weather?.let { w -> item(key = "weather") { WeatherStrip(w) } }
        // Dezente Nachrichten-Laufschrift (Quelle in den Einstellungen › Allgemein)
        if (news.isNotEmpty()) item(key = "news") { NewsTicker(news) }
        // Schmaler Sport-Ticker mit Live-Ergebnissen (ersetzt den früheren Menüpunkt „Sport“)
        if (sportEnabled) item(key = "sportticker") { SportTicker(sportTicker) }
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
private fun HeroButton(label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.07f else 1f, androidx.compose.animation.core.tween(140), label = "hb")
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // Ausdrücklich fokussierbar (Fernbedienung): „clickable“ allein ist auf manchen Geräten nicht per Pfeiltaste erreichbar
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged { focused = it.isFocused }
        .focusable(interactionSource = src)
        .onKeyEvent { ev ->
            if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyUp &&
                (ev.key == androidx.compose.ui.input.key.Key.DirectionCenter || ev.key == androidx.compose.ui.input.key.Key.Enter || ev.key == androidx.compose.ui.input.key.Key.NumPadEnter)) { onClick(); true } else false
        }

        .outlineButton(focused, primary, true, RoundedCornerShape(12.dp))
        .padding(horizontal = 28.dp, vertical = 12.dp)) {
        Text(label, color = if (focused) Color(0xFF111111) else Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
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

/**
 * Ruckelfreie Laufschrift: rückt bei jedem Bild um genau gleich viele ganze Pixel weiter (keine krummen
 * Zwischenschritte), verschiebt nur die fertig gezeichnete Ebene (kein Neuaufbau des Textes) und läuft nahtlos im Kreis.
 */
@Composable
private fun SmoothMarquee(text: androidx.compose.ui.text.AnnotatedString, modifier: Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val step = remember(density) { kotlin.math.max(1f, kotlin.math.round(density.density)) } // ≈ 1 dp pro Bild
    val gapPx = remember(density) { with(density) { 48.dp.toPx() } }
    var width by remember { mutableIntStateOf(0) }
    var box by remember { mutableIntStateOf(0) }
    val offset = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    // Rundenlänge: mindestens die Zeilenbreite – kurzer Text läuft komplett hinaus, bevor er rechts wieder hereinkommt
    val loop = kotlin.math.max(width + gapPx, box.toFloat())
    val loopNow by androidx.compose.runtime.rememberUpdatedState(loop)
    // Läuft durchgehend weiter – neue Spielstände/Schlagzeilen setzen die Zeile NICHT zurück an den Anfang
    LaunchedEffect(Unit) {
        delay(1200)
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            val l = loopNow
            if (width <= 0 || box <= 0 || l <= 0f) continue
            var o = offset.floatValue + step
            while (o >= l) o -= l
            offset.floatValue = o
        }
    }
    Box(modifier.clipToBounds().onSizeChanged { box = it.width }) {
        Text(text, fontSize = 14.sp, maxLines = 1, softWrap = false,
            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).onSizeChanged { width = it.width }
                .graphicsLayer { translationX = -offset.floatValue })
        Text(text, fontSize = 14.sp, maxLines = 1, softWrap = false,
            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                .graphicsLayer { translationX = loop - offset.floatValue })
    }
}

@Composable
private fun SportTicker(events: List<de.ostlertekker.ultraiptv.core.discover.ScoreEvent>) {
    val p = LocalUltraTheme.current.palette
    val green = Color(0xFF16A34A)
    val red = Color(0xFFD7262E)
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMAN) }
    val text = remember(events) {
        androidx.compose.ui.text.buildAnnotatedString {
            events.forEachIndexed { i, e ->
                if (i > 0) withStyle(androidx.compose.ui.text.SpanStyle(color = green, fontWeight = FontWeight.Bold)) { append("   ▪   ") }
                when {
                    e.live -> withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White, background = red, fontWeight = FontWeight.Bold, fontSize = 11.sp)) {
                        append(" LIVE" + (e.clock?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") + " ")
                    }
                    e.state == "post" -> withStyle(androidx.compose.ui.text.SpanStyle(color = p.textDim, fontWeight = FontWeight.Bold, fontSize = 12.sp)) { append("ENDE") }
                    else -> withStyle(androidx.compose.ui.text.SpanStyle(color = green, fontWeight = FontWeight.Bold, fontSize = 12.sp)) { append(fmt.format(java.util.Date(e.startMs))) }
                }
                withStyle(androidx.compose.ui.text.SpanStyle(color = p.textDim, fontSize = 12.sp)) { append("  ${e.league.label}  ") }
                withStyle(androidx.compose.ui.text.SpanStyle(color = p.text, fontWeight = if (e.live) FontWeight.SemiBold else FontWeight.Normal)) {
                    append(if (e.state == "pre" && e.league.sport != de.ostlertekker.ultraiptv.core.discover.Sport.TENNIS && e.competitors.size == 2)
                        e.competitors.joinToString(" – ") { it.name } else e.scoreLine)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 8.dp).height(26.dp),
        verticalAlignment = Alignment.CenterVertically) {
        NewsBadge(green, "SPORT")
        if (events.isEmpty())
            Text("Gerade keine Live-Events", color = p.textDim, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
        else SmoothMarquee(text, Modifier.weight(1f).padding(start = 12.dp, end = 8.dp))
    }
}

/** Rotes „NEWS“-Kästchen: pulsierender Live-Punkt und ein langsam darüberlaufender Lichtschimmer. */
@Composable
private fun NewsBadge(red: Color, label: String = "NEWS") {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "news")
    val pulse by t.animateFloat(0.25f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
    val sweep by t.animateFloat(-0.6f, 1.6f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.keyframes { durationMillis = 4200; -0.6f at 2600; 1.6f at 4200 }), label = "sweep")
    Box(Modifier.fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(red)
        .drawWithContent {
            drawContent()
            val x = size.width * sweep
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                startX = x - size.width * 0.35f, endX = x + size.width * 0.35f))
        }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).graphicsLayer { alpha = pulse; scaleX = 0.8f + 0.2f * pulse; scaleY = scaleX }
                .background(Color.White, androidx.compose.foundation.shape.CircleShape))
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NewsTicker(items: List<de.ostlertekker.ultraiptv.core.news.NewsItem>) {
    val p = LocalUltraTheme.current.palette
    val red = Color(0xFFD7262E)
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMAN) }
    val text = remember(items) {
        androidx.compose.ui.text.buildAnnotatedString {
            items.forEachIndexed { i, n ->
                if (i > 0) withStyle(androidx.compose.ui.text.SpanStyle(color = red, fontWeight = FontWeight.Bold)) { append("   ▪   ") }
                if (n.local) withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White, background = red, fontWeight = FontWeight.Bold, fontSize = 11.sp)) { append(" LOKAL ") }
                if (n.local) append(" ")
                withStyle(androidx.compose.ui.text.SpanStyle(color = p.textDim, fontSize = 12.sp)) {
                    append(listOfNotNull(n.time?.let { fmt.format(java.util.Date(it)) }, n.source).joinToString(" · ") + "  ")
                }
                withStyle(androidx.compose.ui.text.SpanStyle(color = p.text)) { append(n.title) }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 8.dp).height(30.dp),
        verticalAlignment = Alignment.CenterVertically) {
        NewsBadge(red)
        SmoothMarquee(text, Modifier.weight(1f).padding(start = 12.dp, end = 8.dp))
    }
}

@Composable
private fun WeatherStrip(w: de.ostlertekker.ultraiptv.core.weather.Weather) {
    val p = LocalUltraTheme.current.palette
    // Transparent: nur Inhalt, kein Kasten
    // Über die volle Bildschirmbreite (nur kleiner Rand, damit nichts am Fernsehrand abgeschnitten wird)
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            de.ostlertekker.ultraiptv.core.weather.AnimatedWeatherIcon(w.code, 40.dp)
            Text("${w.temp}°", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        }
        Column {
            Text("${w.place} · ${w.text}", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(listOfNotNull(w.feels?.let { "gefühlt $it°" }, if (w.todayMax != null && w.todayMin != null) "heute ${w.todayMax}° / ${w.todayMin}°" else null,
                w.rainPct?.let { "Regen $it %" }, w.wind?.let { "Wind $it km/h" }, w.humidity?.let { "Luftfeuchte $it %" }).joinToString("  ·  "),
                color = p.textDim, fontSize = 12.sp, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        w.days.take(3).forEach { d ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(d.label, color = p.textDim, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    de.ostlertekker.ultraiptv.core.weather.AnimatedWeatherIcon(d.code, 24.dp)
                    Text(" ${d.max}° / ${d.min}°", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
