package de.ostlertekker.ultraiptv.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.data.ContentRepository
import de.ostlertekker.ultraiptv.core.data.EpgRepository
import de.ostlertekker.ultraiptv.core.data.Keys
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.database.AiringProgram
import de.ostlertekker.ultraiptv.core.database.ChannelEntity
import de.ostlertekker.ultraiptv.core.database.EpgProgramEntity
import de.ostlertekker.ultraiptv.core.database.MovieEntity
import de.ostlertekker.ultraiptv.core.database.VirtualListEntity
import de.ostlertekker.ultraiptv.core.discover.DiscoverRepository
import de.ostlertekker.ultraiptv.core.discover.League
import de.ostlertekker.ultraiptv.core.discover.ListRules
import de.ostlertekker.ultraiptv.core.discover.MatchDetail
import de.ostlertekker.ultraiptv.core.discover.Mood
import de.ostlertekker.ultraiptv.core.discover.ScoreBoard
import de.ostlertekker.ultraiptv.core.discover.ScoreEvent
import de.ostlertekker.ultraiptv.core.discover.SportEvent
import de.ostlertekker.ultraiptv.core.discover.SportsRepository
import de.ostlertekker.ultraiptv.core.discover.WatchStats
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.notify.NotificationCenter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** ViewModel der Sport-Seite (Live-Spielstände, Spieldetails, „Läuft im TV“). */
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    val repo: DiscoverRepository,
    private val sports: SportsRepository,
    private val content: ContentRepository,
    private val epg: EpgRepository,
    private val user: UserRepository,
    private val settings: SettingsRepository,
    private val notifications: NotificationCenter,
) : ViewModel() {

    val loading = MutableStateFlow(false)
    private fun <T> load(target: MutableStateFlow<T>, block: suspend () -> T): Job = viewModelScope.launch {
        loading.value = true
        runCatching { target.value = block() }
        loading.value = false
    }

    suspend fun toggleReminder(p: AiringProgram): Boolean = notifications.toggleReminder(p.channelId, p.channelName, p.title, p.startMs)

    // --------------------------------------------------- Sport-Dashboard
    val board = MutableStateFlow<ScoreBoard?>(null)
    val detail = MutableStateFlow<MatchDetail?>(null)
    private val _leagues = MutableStateFlow(League.defaults.toSet())
    val leagues: StateFlow<Set<League>> = _leagues.asStateFlow()
    init {
        viewModelScope.launch {
            val saved = settings.current().sportsLeagues
            if (saved.isNotBlank()) _leagues.value = saved.split(',').mapNotNull { n -> League.entries.firstOrNull { it.name == n } }.toSet().ifEmpty { League.defaults.toSet() }
        }
    }
    private var boardJob: Job? = null
    val boardFailed = MutableStateFlow(false)
    val nextRefreshAt = MutableStateFlow(0L)
    fun stopScores() { boardJob?.cancel(); boardJob = null }
    fun startScores() {
        // TV-Zeitschriften im Hintergrund vorladen, damit „Läuft im TV“ sofort Ergebnisse hat
        viewModelScope.launch { runCatching { repo.warmGuide() } }
        boardJob?.cancel()
        boardJob = viewModelScope.launch {
            while (true) {
                loading.value = true
                // Alle Wettbewerbe abfragen – angezeigt werden nur laufende Spiele
                val b = runCatching { sports.scoreboard(League.entries, maxAgeMs = 0) }.getOrNull()
                if (b != null) board.value = b
                boardFailed.value = b == null || b.failedLeagues.size == League.entries.size
                loading.value = false
                nextRefreshAt.value = System.currentTimeMillis() + 30_000
                delay(30_000)
            }
        }
    }
    fun toggleLeague(l: League) {
        val f = _leagues
        f.value = if (l in f.value) f.value - l else f.value + l
        viewModelScope.launch { settings.set(Keys.SPORTS_LEAGUES, f.value.joinToString(",") { it.name }) }
        startScores()
    }
    val onTv = MutableStateFlow<List<de.ostlertekker.ultraiptv.core.discover.TvHit>?>(null)
    fun findOnTv(e: ScoreEvent) { onTv.value = null; load(onTv) { runCatching { repo.whereOnTv(e) }.getOrDefault(emptyList()) } }
    /** true = Quelle liefert für dieses Spiel keine Details (statt endlos „Lade …“). */
    val detailFailed = MutableStateFlow(false)
    fun loadDetail(league: League, id: String, refresh: Boolean = false) {
        if (!refresh) { detail.value = null; detailFailed.value = false }
        viewModelScope.launch {
            val d = runCatching { kotlinx.coroutines.withTimeoutOrNull(15_000) { sports.detail(league, id) } }.getOrNull()
            if (d != null || !refresh) { detail.value = d; detailFailed.value = d == null }
        }
    }

}
