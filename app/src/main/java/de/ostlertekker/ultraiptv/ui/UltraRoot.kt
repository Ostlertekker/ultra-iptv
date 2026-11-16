package de.ostlertekker.ultraiptv.ui

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
import de.ostlertekker.ultraiptv.core.motion.MotionOverlay
import de.ostlertekker.ultraiptv.core.motion.PageTransitionHost
import de.ostlertekker.ultraiptv.core.motion.exitTransitionFor
import de.ostlertekker.ultraiptv.core.animation.ThemeBackground
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.design.TransitionStyle
import de.ostlertekker.ultraiptv.core.ui.LocalUiSounds
import de.ostlertekker.ultraiptv.core.ui.NavItem
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
import de.ostlertekker.ultraiptv.feature.world.WorldScreen
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
            Phase.MAIN -> MainScaffold(s.customBackgroundUrl, vm, startRoute)
        }
    }
}

private fun railRouteFor(route: String): String = when {
    route.startsWith("movie/") -> Routes.MOVIES
    route.startsWith("seriesDetail/") -> Routes.SERIES
    route.startsWith(Routes.EPG) -> Routes.LIVE
    route.startsWith(Routes.WORLD) -> Routes.WORLD
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
            NavItem(Routes.HOME, "HOME"), NavItem(Routes.LIVE, "LIVE TV"), NavItem(Routes.WORLD, "WORLD MAP"), NavItem(Routes.MOVIES, "MOVIES"),
            NavItem(Routes.SERIES, "SERIES"), NavItem(Routes.FAVORITES, "FAVORITES"), NavItem(Routes.SEARCH, "SEARCH"),
            NavItem(Routes.SETTINGS, "SETTINGS"),
        )
    }
    LaunchedEffect(Unit) { delay(150); runCatching { railFocus.requestFocus() } }
    // Direkter Einstieg in eine Seite (z. B. für automatische Oberflächentests)
    LaunchedEffect(startRoute) {
        val r = startRoute ?: return@LaunchedEffect
        if (r != Routes.HOME) runCatching { navigateTop(nav, r) }
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
        Row(Modifier.fillMaxSize()) {
            NavRail(items, railRouteFor(route), onSelect = { r -> navigateTop(nav, r); sounds?.transition() }, firstFocus = railFocus)
            PageTransitionHost(key = entry?.id, modifier = Modifier.weight(1f).fillMaxHeight()) {
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
                    composable(Routes.WORLD) { WorldScreen() }
                    composable(Routes.SETUP_NEW) { ProviderSetupScreen(onDone = { nav.popBackStack() }, firstRun = false, newAccount = true) }
                    composable(Routes.SETUP) { ProviderSetupScreen(onDone = { nav.popBackStack() }, firstRun = false) }
                }
            }
        }
        MiniPlayerOverlay(vm.engine, Modifier.align(Alignment.BottomEnd).padding(28.dp))
        MotionOverlay()
    }

    // Watch Together (Gast): Inhalt des Host-Geräts automatisch öffnen
    val context = LocalContext.current
    val guest by vm.lan.guestState.collectAsStateWithLifecycle()
    LaunchedEffect(guest?.id) {
        val w = guest ?: return@LaunchedEffect
        if (vm.engine.owner != "player" && w.id.isNotBlank()) {
            val kind = runCatching { ContentKind.valueOf(w.kind) }.getOrNull() ?: return@LaunchedEffect
            context.openPlayer(kind, w.id, w.positionMs, w.category)
        }
    }
}

private fun navigateTop(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
