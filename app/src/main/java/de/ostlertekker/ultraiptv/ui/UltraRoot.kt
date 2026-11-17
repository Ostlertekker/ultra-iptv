package de.ostlertekker.ultraiptv.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.ostlertekker.ultraiptv.RootViewModel
import de.ostlertekker.ultraiptv.core.motion.LocalMotion
import de.ostlertekker.ultraiptv.core.motion.PageTransitionHost
import de.ostlertekker.ultraiptv.core.motion.exitTransitionFor
import de.ostlertekker.ultraiptv.core.animation.ThemeBackground
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.design.TransitionStyle
import de.ostlertekker.ultraiptv.core.ui.LocalUiSounds
import de.ostlertekker.ultraiptv.core.ui.NavItem
import de.ostlertekker.ultraiptv.core.ui.TopNavBar
import de.ostlertekker.ultraiptv.core.ui.NavRail
import de.ostlertekker.ultraiptv.core.ui.UltraThemeHost
import de.ostlertekker.ultraiptv.feature.favorites.FavoritesScreen
import de.ostlertekker.ultraiptv.feature.home.HomeScreen
import de.ostlertekker.ultraiptv.feature.intro.IntroScreen
import de.ostlertekker.ultraiptv.feature.livetv.EpgScreen
import de.ostlertekker.ultraiptv.feature.livetv.LiveTvScreen
import de.ostlertekker.ultraiptv.feature.movies.MovieDetailScreen
import de.ostlertekker.ultraiptv.feature.movies.MoviesScreen
import de.ostlertekker.ultraiptv.feature.profiles.ProfilePickerScreen
import de.ostlertekker.ultraiptv.feature.search.SearchScreen
import de.ostlertekker.ultraiptv.feature.series.SeriesDetailScreen
import de.ostlertekker.ultraiptv.feature.series.SeriesScreen
import de.ostlertekker.ultraiptv.feature.settings.ProviderSetupScreen
import de.ostlertekker.ultraiptv.feature.settings.SettingsScreen
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.openPlayer
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

object Routes {
    const val HOME = "home"
    const val LIVE = "live"
    const val EPG = "epg"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val FAVORITES = "favorites"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val SETUP = "setup"
    const val WORLD = "world"
    const val SETUP_NEW = "setupNew"
    fun movie(id: String) = "movie/${Uri.encode(id)}"
    fun seriesDetail(id: String) = "seriesDetail/${Uri.encode(id)}"
    fun epg(category: String?) = if (category == null) EPG else "$EPG?category=${Uri.encode(category)}"

    // Entdecken-Funktionen
    const val EXPLORE = "explore"
    const val JELLYFIN = "jellyfin"
    const val SPOTIFY = "spotify"
    fun jfMovie(id: String) = "jfMovie/${Uri.encode(id)}"
    fun jfSeries(id: String) = "jfSeries/${Uri.encode(id)}"
    fun jfLibrary(id: String, name: String) = "jfLibrary/${Uri.encode(id)}?name=${Uri.encode(name)}"
    const val WHATSON = "whatson"
    const val EVENTS = "events"
    const val SPORTS = "sports"
    const val MYTV = "mytv"
    const val LISTS = "lists"
    fun radar(q: String) = "radar?q=${Uri.encode(q)}"
    fun timeMachine(at: Long) = "timemachine?at=$at"
}

private enum class Phase { INTRO, SETUP, PROFILES, MAIN }

@Composable
fun UltraRoot(startRoute: String? = null, vm: RootViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hasProvider by vm.hasProvider.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    var phase by rememberSaveable { mutableStateOf(if (s.introEnabled) Phase.INTRO else Phase.SETUP) }
    var introFull by rememberSaveable { mutableStateOf(!s.introPlayedOnce) }

    UltraThemeHost(s, vm.device.tier, vm.sounds) {
        val theme = LocalUltraTheme.current
        when (phase) {
            Phase.INTRO -> IntroScreen(full = introFull, soundEnabled = s.introSound, sounds = vm.sounds, theme = theme) {
                if (introFull) vm.introFinished()
                introFull = false
                phase = Phase.SETUP
            }
            Phase.SETUP -> {
                LaunchedEffect(Unit) { vm.onStartup() }
                if (!hasProvider) {
                    Box(Modifier.fillMaxSize()) {
                        ThemeBackground(theme, Modifier.fillMaxSize())
                        ProviderSetupScreen(onDone = { phase = Phase.PROFILES }, firstRun = true)
                    }
                } else {
                    LaunchedEffect(Unit) { phase = Phase.PROFILES }
                }
            }
            Phase.PROFILES -> {
                if (profiles.size > 1) {
                    Box(Modifier.fillMaxSize()) {
                        ThemeBackground(theme, Modifier.fillMaxSize())
                        ProfilePickerScreen(onPicked = { phase = Phase.MAIN })
                    }
                } else {
                    LaunchedEffect(Unit) { delay(50); phase = Phase.MAIN }
                }
            }
            Phase.MAIN -> Box(Modifier.fillMaxSize()) { MainScaffold(s.customBackgroundUrl, vm, startRoute); CrashReportDialog() }
        }
    }
}

private fun railRouteFor(route: String): String = when {
    route.startsWith("movie/") -> Routes.MOVIES
    route.startsWith("seriesDetail/") -> Routes.SERIES
    route.startsWith(Routes.EPG) -> Routes.EPG
    route.startsWith("jf") || route.startsWith(Routes.JELLYFIN) -> Routes.JELLYFIN
    route.startsWith(Routes.SPORTS) -> Routes.SPORTS
    route.startsWith(Routes.SETUP) -> Routes.SETTINGS
    route.startsWith(Routes.SETUP_NEW) -> Routes.SETTINGS
    else -> route
}

@Composable
private fun MainScaffold(customBackground: String, vm: RootViewModel, startRoute: String? = null) {
    val theme = LocalUltraTheme.current
    val sounds = LocalUiSounds.current
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Routes.HOME
    val railFocus = remember { FocusRequester() }
    val items = remember {
        listOf(
            NavItem(Routes.HOME, "Start"), NavItem(Routes.LIVE, "Live-TV"), NavItem(Routes.EPG, "Programm"), NavItem(Routes.MOVIES, "Filme"),
            NavItem(Routes.SERIES, "Serien"), NavItem(Routes.SPORTS, "Sport"), NavItem(Routes.FAVORITES, "Favoriten"), NavItem(Routes.JELLYFIN, "Jellyfin"), NavItem("app:YOUTUBE", "YouTube"), NavItem(Routes.SPOTIFY, "Spotify"),
            NavItem(Routes.EXPLORE, "Mehr"),
        )
    }
    val utility = remember { listOf(NavItem(Routes.SEARCH, "Suche", Icons.Default.Search), NavItem(Routes.SETTINGS, "Einstellungen", Icons.Default.Settings)) }
    LaunchedEffect(Unit) { delay(150); runCatching { railFocus.requestFocus() } }
    // Direkter Einstieg in eine Seite (z. B. für automatische Oberflächentests)
    LaunchedEffect(startRoute) {
        val r = startRoute ?: return@LaunchedEffect
        if (r != Routes.HOME) runCatching { navigateTop(nav, r) }
    }
    // Einstellung „Beim App-Start“ (wie TiviMate): einmal pro App-Start
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var startHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (startHandled || startRoute != null) return@LaunchedEffect
        startHandled = true
        when (vm.settings.value?.startAction ?: 0) {
            1 -> runCatching { navigateTop(nav, Routes.LIVE) }
            2 -> { runCatching { navigateTop(nav, Routes.LIVE) }; vm.lastChannelId()?.let { ctx.openPlayer(ContentKind.LIVE, it) } }
            3 -> runCatching { navigateTop(nav, Routes.EPG) }
        }
    }
    BackHandler(enabled = route != Routes.HOME) {
        sounds?.back()
        if (!nav.popBackStack()) nav.navigate(Routes.HOME)
    }

    val motion = LocalMotion.current
    val reduce = theme.anim.reduceMotion || !motion.enabled
    // Mit CRAZY-MOTION-Seitenübergang übernimmt das Vorhang-Overlay die Enthüllung → kurzer Fade genügt
    val enter: EnterTransition = if (reduce) EnterTransition.None else if (motion.pageEnter != null) fadeIn(tween(90)) else when (theme.spec.transition) {
        TransitionStyle.FADE -> fadeIn(tween(260))
        TransitionStyle.SLIDE -> fadeIn(tween(260)) + slideInHorizontally(tween(300)) { it / 12 }
        TransitionStyle.ZOOM -> fadeIn(tween(260)) + scaleIn(tween(320), initialScale = 0.96f)
    }
    val exit: ExitTransition = if (reduce) ExitTransition.None else exitTransitionFor(motion)

    Box(Modifier.fillMaxSize()) {
        // Läuft ein Video (Vorschau/Mini-Player), pausiert die Hintergrundanimation → volle Leistung fürs Video
        val engineState by vm.engine.state.collectAsStateWithLifecycle()
        val mini by vm.engine.mini.collectAsStateWithLifecycle()
        val videoActive = (mini != null || vm.engine.owner == "preview") && engineState == androidx.media3.common.Player.STATE_READY
        ThemeBackground(theme, Modifier.fillMaxSize(), animate = !videoActive, customImage = customBackground)
        // Startseite läuft wie bei Apple TV bis unter die Menüleiste, alle anderen Seiten beginnen darunter
        val topPad = if (route == Routes.HOME) 0.dp else 66.dp
        Box(Modifier.fillMaxSize().padding(top = topPad)) {
            PageTransitionHost(key = entry?.id, modifier = Modifier.fillMaxSize()) {
                NavHost(nav, startDestination = Routes.HOME, enterTransition = { enter }, exitTransition = { exit },
                    popEnterTransition = { enter }, popExitTransition = { exit }) {
                    composable(Routes.HOME) { HomeScreen(nav) }
                    composable(Routes.LIVE) { LiveTvScreen(nav) }
                    composable(
                        "${Routes.EPG}?category={category}",
                        arguments = listOf(navArgument("category") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) { EpgScreen() }
                    composable(Routes.MOVIES) { MoviesScreen(nav) }
                    composable("movie/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { MovieDetailScreen(nav) }
                    composable(Routes.SERIES) { SeriesScreen(nav) }
                    composable("seriesDetail/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { SeriesDetailScreen(nav) }
                    composable(Routes.FAVORITES) { FavoritesScreen(nav) }
                    composable(Routes.SEARCH) { SearchScreen(nav) }
                    composable(Routes.SETTINGS) { SettingsScreen(nav) }
                    // „Mehr“: Menüpunkt bleibt, Inhalte vorerst entfernt
                    composable(Routes.EXPLORE) { Box(Modifier.fillMaxSize()) }
                    composable(Routes.SPORTS) { de.ostlertekker.ultraiptv.feature.discover.SportsScreen(nav) }
                    composable(Routes.SPOTIFY) { de.ostlertekker.ultraiptv.feature.spotify.SpotifyScreen() }
                    composable(Routes.JELLYFIN) { de.ostlertekker.ultraiptv.feature.jellyfin.JellyfinScreen(nav) }
                    composable("jfMovie/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                        de.ostlertekker.ultraiptv.feature.jellyfin.JellyfinMovieScreen(nav)
                    }
                    composable("jfSeries/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                        de.ostlertekker.ultraiptv.feature.jellyfin.JellyfinSeriesScreen(nav)
                    }
                    composable("jfLibrary/{id}?name={name}", arguments = listOf(navArgument("id") { type = NavType.StringType },
                        navArgument("name") { type = NavType.StringType; defaultValue = "Bibliothek" })) {
                        de.ostlertekker.ultraiptv.feature.jellyfin.JellyfinLibraryScreen(nav)
                    }
                    composable(Routes.SETUP_NEW) { ProviderSetupScreen(onDone = { nav.popBackStack() }, firstRun = false, newAccount = true) }
                    composable(Routes.SETUP) { ProviderSetupScreen(onDone = { nav.popBackStack() }, firstRun = false) }
                }
            }
        }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        TopNavBar(items, utility, railRouteFor(route), onSelect = { r ->
            // YouTube/Spotify laufen als eingebettete Weboberfläche im eigenen Vollbild – keine externe App
            if (r.startsWith("app:")) de.ostlertekker.ultraiptv.feature.web.WebAppActivity.open(ctx, de.ostlertekker.ultraiptv.feature.web.WebApp.valueOf(r.removePrefix("app:")))
            else navigateTop(nav, r)
            sounds?.transition()
        }, firstFocus = railFocus,
            modifier = Modifier.align(Alignment.TopCenter))
        MiniPlayerOverlay(vm.engine, Modifier.align(Alignment.BottomEnd).padding(28.dp))
    }
}

private fun navigateTop(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}


/**
 * Nach einem Absturz/Hänger zeigt die App beim nächsten Start einmalig den Bericht – mit genauer Code-Stelle.
 * Ein Foto davon genügt, um den Fehler gezielt zu beheben.
 */
@Composable
private fun CrashReportDialog() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("crash_ui", android.content.Context.MODE_PRIVATE) }
    val time = remember { de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard.reportTime(context) }
    var visible by remember { mutableStateOf(time > 0 && prefs.getLong("seen", 0) < time) }
    if (!visible) return
    val text = remember {
        listOfNotNull(
            de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard.lastCrash(context)?.let { "ABSTURZ\n$it" },
            de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard.lastFreeze(context)?.let { "HÄNGER\n$it" },
        ).joinToString("\n\n").lines().filter { it.isNotBlank() }.take(34).joinToString("\n")
    }
    val close = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(300); runCatching { close.requestFocus() } }
    androidx.activity.compose.BackHandler { prefs.edit().putLong("seen", time).apply(); visible = false }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)).padding(36.dp)) {
        Column {
            Text("ULTRA IPTV wurde beim letzten Mal unerwartet beendet", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Bitte ein Foto von diesem Bildschirm schicken – es zeigt genau, wo der Fehler liegt.", color = Color(0xFFB6FF1A), fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 8.dp))
            Text(text, color = Color.White, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF111111)).padding(10.dp))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                de.ostlertekker.ultraiptv.core.ui.TvButton("Schließen", onClick = { prefs.edit().putLong("seen", time).apply(); visible = false },
                    primary = true, focusRequester = close)
                de.ostlertekker.ultraiptv.core.ui.TvButton("Bericht löschen", onClick = {
                    de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard.clear(context); prefs.edit().putLong("seen", time).apply(); visible = false
                })
            }
        }
    }
}
