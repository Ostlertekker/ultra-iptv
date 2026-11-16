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

/** Gemeinsames ViewModel der Entdecken-Seiten. Jede Seite ruft nur die Teile auf, die sie braucht. */
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

    // --------------------------------------------------- Jetzt läuft / Zeitreise / Radar
    val airing = MutableStateFlow<List<AiringProgram>>(emptyList())
    val airingAt = MutableStateFlow(System.currentTimeMillis())
    fun loadAiring(at: Long = airingAt.value) { airingAt.value = at; load(airing) { repo.airingAt(at) } }

    val searchResults = MutableStateFlow<List<AiringProgram>>(emptyList())
    val query = MutableStateFlow("")
    fun search(q: String, from: Long, to: Long) { query.value = q; load(searchResults) { repo.search(q, from, to) } }

    // --------------------------------------------------- Event Tracker
    val events = MutableStateFlow<List<SportEvent>>(emptyList())
    fun loadEvents() { val now = System.currentTimeMillis(); load(events) { repo.sportEvents(now - 3 * 3_600_000L, now + 36 * 3_600_000L) } }

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
    fun startScores() {
        boardJob?.cancel()
        boardJob = viewModelScope.launch {
            while (true) {
                loading.value = true
                board.value = runCatching { sports.scoreboard(leagues.value) }.getOrNull() ?: board.value
                loading.value = false
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
    fun loadDetail(league: League, id: String) { detail.value = null; viewModelScope.launch { detail.value = sports.detail(league, id) } }

    // --------------------------------------------------- Mood
    val moodPrograms = MutableStateFlow<List<Pair<AiringProgram, Float>>>(emptyList())
    val moodMovies = MutableStateFlow<List<Pair<MovieEntity, Float>>>(emptyList())
    val moods = MutableStateFlow<Set<Mood>>(emptySet())
    fun setMoods(m: Set<Mood>) {
        moods.value = m
        viewModelScope.launch {
            loading.value = true
            val (p, mv) = runCatching { repo.moodPicks(m.toList(), System.currentTimeMillis()) }.getOrDefault(emptyList<Pair<AiringProgram, Float>>() to emptyList())
            moodPrograms.value = p; moodMovies.value = mv
            loading.value = false
        }
    }

    // --------------------------------------------------- Mein TV
    val stats = MutableStateFlow<WatchStats?>(null)
    val forYou = MutableStateFlow<List<AiringProgram>>(emptyList())
    fun loadStats() = viewModelScope.launch {
        val s = repo.stats(); stats.value = s
        forYou.value = repo.forYou(s, System.currentTimeMillis())
    }
    fun clearStats() = viewModelScope.launch { repo.clearStats(); loadStats() }

    // --------------------------------------------------- Holo-Guide / Universum
    val holoChannels = MutableStateFlow<List<Pair<ChannelEntity, List<EpgProgramEntity>>>>(emptyList())
    fun loadHolo() = viewModelScope.launch {
        loading.value = true
        val now = System.currentTimeMillis()
        val chans = repo.allChannels().filter { !it.epgId.isNullOrBlank() }.take(400)
        val progs = epg.programs(chans.mapNotNull { it.epgId }, now - 30 * 60_000L, now + 5 * 3_600_000L)
        holoChannels.value = chans.map { it to (progs[it.epgId] ?: emptyList()) }.filter { it.second.isNotEmpty() }.take(80)
        loading.value = false
    }

    val universe = MutableStateFlow<List<Pair<String, List<ChannelEntity>>>>(emptyList())
    fun loadUniverse() = viewModelScope.launch {
        loading.value = true
        val all = repo.allChannels()
        val cats = all.groupBy { it.category }.entries.sortedByDescending { it.value.size }.take(10).map { it.key to it.value.take(36) }
        val lists = repo.listsOnce().map { "◆ " + it.name to repo.resolveList(ListRules.fromJson(it.rulesJson)).take(36) }.filter { it.second.isNotEmpty() }
        universe.value = lists + cats
        loading.value = false
    }

    suspend fun nowOn(ch: ChannelEntity): String? = epg.nowNext(ch.epgId).firstOrNull { it.startMs <= System.currentTimeMillis() }?.title

    // --------------------------------------------------- Eigene Listen
    val lists: StateFlow<List<VirtualListEntity>> = repo.lists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val categories: StateFlow<List<String>> = content.channelCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val preview = MutableStateFlow<List<ChannelEntity>>(emptyList())
    fun previewRules(r: ListRules) = load(preview) { repo.resolveList(r) }
    fun saveList(id: Long, name: String, r: ListRules) = viewModelScope.launch { repo.saveList(id, name, r) }
    fun deleteList(id: Long) = viewModelScope.launch { repo.deleteList(id) }

    // --------------------------------------------------- Smart Remote
    suspend fun bestSportChannel(): AiringProgram? {
        val now = System.currentTimeMillis()
        val live = repo.sportEvents(now, now + 60_000).map { it.program }
        val best = repo.bestOf(live.map { it.channelId })
        return live.firstOrNull { it.channelId == best } ?: live.firstOrNull()
    }
    suspend fun previousChannelId(): String? = user.recentChannels.first().getOrNull(0)?.itemId
    suspend fun findChannel(q: String, number: Int?): ChannelEntity? =
        number?.let { content.channelByNumber(it) } ?: content.channelsNamed(q).minByOrNull { it.name.length }
}
