package de.ostlertekker.ultraiptv

import kotlinx.coroutines.flow.first
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.audio.AudioExperienceEngine
import de.ostlertekker.ultraiptv.core.data.AppSettings
import de.ostlertekker.ultraiptv.core.data.Keys
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.data.SyncRepository
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.device.DeviceCapabilityManager
import de.ostlertekker.ultraiptv.core.notify.NotificationCenter
import de.ostlertekker.ultraiptv.core.player.PlayerEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.security.CredentialStore
import de.ostlertekker.ultraiptv.feature.player.PlayerActivity
import de.ostlertekker.ultraiptv.ui.UltraRoot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var playerEngine: PlayerEngine

    companion object { const val EXTRA_ROUTE = "ultra_route" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startRoute = intent?.getStringExtra(EXTRA_ROUTE)
        setContent { UltraRoot(startRoute) }
        // Läuft eine Vorschau oder der Mini-Player, darf der Bildschirmschoner die Wiedergabe nicht unterbrechen
        lifecycleScope.launch {
            playerEngine.playing.collect { on ->
                if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        de.ostlertekker.ultraiptv.core.motion.InputActivity.mark()
        return super.dispatchKeyEvent(event)
    }
}

@HiltViewModel
class RootViewModel @Inject constructor(
    val settingsRepo: SettingsRepository,
    val device: DeviceCapabilityManager,
    val sounds: AudioExperienceEngine,
    private val creds: CredentialStore,
    private val sync: SyncRepository,
    private val user: UserRepository,
    val engine: PlayerEngine,
    private val notifications: NotificationCenter,
    private val epg: de.ostlertekker.ultraiptv.core.data.EpgRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val hasProvider: StateFlow<Boolean> = creds.config.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, creds.config.value != null)
    val profiles = user.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var startupDone = false

    /** Nach dem Intro: Standardprofil sicherstellen und veraltete Daten im Hintergrund auffrischen. */
    fun onStartup() {
        if (startupDone) return
        startupDone = true
        viewModelScope.launch {
            user.ensureDefaultProfile()
            // Einmalig: neues Premium-Designsystem aktivieren (danach frei wählbar)
            val design = appContext.getSharedPreferences("design_migration", android.content.Context.MODE_PRIVATE)
            if (!design.getBoolean("premium_v1", false)) {
                settingsRepo.set(de.ostlertekker.ultraiptv.core.data.Keys.THEME, "premium")
                settingsRepo.set(de.ostlertekker.ultraiptv.core.data.Keys.FONT_STYLE, 0)
                settingsRepo.set(de.ostlertekker.ultraiptv.core.data.Keys.ACCENT, 0L)
                design.edit().putBoolean("premium_v1", true).apply()
            }
            val s = settingsRepo.current()
            NotificationCenter.scheduleAutoUpdate(appContext, s.autoUpdateHours)
            runCatching { notifications.rescheduleAll() }
            val now = System.currentTimeMillis()
            if (s.updateCheck && now - s.lastUpdateCheck > 24 * 3_600_000L) {
                settingsRepo.set(Keys.LAST_UPDATE_CHECK, now)
                launch { runCatching { notifications.checkAppUpdate() } }
            }
            if (creds.load() == null) return@launch
            if (now - s.lastSyncMs > 24 * 3_600_000L) notifications.syncWithChecks()
            else {
                // Auch neu laden, wenn das gespeicherte Programm nicht mehr die eingestellten Tage im Voraus abdeckt
                val shortCoverage = runCatching { epg.coverageEnd() }.getOrDefault(0L) < now + s.epgDays.coerceIn(1, 7) * 86_400_000L - 6 * 3_600_000L
                if (now - s.lastEpgSyncMs > s.epgRefreshHours * 3_600_000L || shortCoverage) sync.syncEpg()
                sync.refreshAccountInfo(); notifications.checkAccount()
            }
        }
    }

    suspend fun lastChannelId(): String? = user.recentChannels.first().firstOrNull()?.itemId

    fun introFinished() = viewModelScope.launch { settingsRepo.set(Keys.INTRO_PLAYED, true) }
}

/** Startet den Vollbild-Player. */
fun Context.openPlayer(kind: ContentKind, id: String, startMs: Long = 0L, category: String? = null, catchupStart: Long = 0L, catchupEnd: Long = 0L) {
    startActivity(Intent(this, PlayerActivity::class.java).apply {
        if (catchupStart > 0) { putExtra(PlayerActivity.EXTRA_CATCHUP_START, catchupStart); putExtra(PlayerActivity.EXTRA_CATCHUP_END, catchupEnd) }
        putExtra(PlayerActivity.EXTRA_KIND, kind.name)
        putExtra(PlayerActivity.EXTRA_ID, id)
        putExtra(PlayerActivity.EXTRA_START, startMs)
        if (category != null) putExtra(PlayerActivity.EXTRA_CATEGORY, category)
    })
}
