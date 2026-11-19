package de.ostlertekker.ultraiptv.feature.player

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import de.ostlertekker.ultraiptv.openPlayer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import de.ostlertekker.ultraiptv.core.data.AppSettings
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.device.DeviceCapabilityManager
import de.ostlertekker.ultraiptv.core.player.DisplayModeInfo
import de.ostlertekker.ultraiptv.core.player.PlaybackService
import de.ostlertekker.ultraiptv.core.player.UltraQualityEngine
import de.ostlertekker.ultraiptv.core.ui.UltraThemeHost
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()
    @Inject lateinit var device: DeviceCapabilityManager
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var notifications: de.ostlertekker.ultraiptv.core.notify.NotificationCenter
    private var originalModeId = 0
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val inPip = mutableStateOf(false)

    private val pipSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        originalModeId = window.attributes.preferredDisplayModeId
        setContent {
            val settings by settingsRepo.settings.collectAsStateWithLifecycle<AppSettings?>(initialValue = null)
            val s = settings
            if (s == null) Box(Modifier.fillMaxSize().background(Color.Black))
            else UltraThemeHost(s, device.tier, null) {
                val reminder by notifications.popup.collectAsStateWithLifecycle()
                val update by notifications.updatePopup.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize()) {
                    PlayerScreen(
                        vm = vm, settings = s, inPip = inPip.value, pipSupported = pipSupported,
                        onExit = { finish() },
                        onPip = { enterPip() },
                        onMini = { vm.toMini(); finish() },
                        popupActive = (reminder != null || update != null) && !inPip.value,
                    )
                    // Programm-Erinnerung auch im Vollbild-Player (oben links, 30 s)
                    if (!inPip.value) de.ostlertekker.ultraiptv.core.notify.ReminderPopup(reminder,
                        Modifier.align(androidx.compose.ui.Alignment.TopStart).padding(start = 28.dp, top = 24.dp),
                        onDismiss = { notifications.popup.value = null }) { rem ->
                        notifications.popup.value = null
                        if (vm.isLive) vm.playChannelById(rem.channelId)
                        else { vm.handOver(); openPlayer(de.ostlertekker.ultraiptv.core.model.ContentKind.LIVE, rem.channelId); finish() }
                    }
                    // Neues App-Update auch im Vollbild-Player (Erinnerung hat Vorrang)
                    if (!inPip.value && reminder == null) de.ostlertekker.ultraiptv.core.notify.UpdatePopup(notifications,
                        Modifier.align(androidx.compose.ui.Alignment.TopStart).padding(start = 28.dp, top = 24.dp))
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.desiredFps.collect { fps -> if (fps != null) applyFrameRate(fps) }
            }
        }
    }

    /** Bild-in-Bild, sofern das Gerät es unterstützt (viele Fire-TV-Modelle nicht → Mini-Player). */
    private fun enterPip(): Boolean {
        if (!pipSupported) return false
        return runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }.getOrDefault(false)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    /** Wechselt nur dann den Anzeigemodus, wenn das Display einen exakt passenden Modus anbietet. */
    private fun applyFrameRate(fps: Float) {
        val display = window.decorView.display ?: return
        val cur = display.mode
        val modes = display.supportedModes.map { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val pick = UltraQualityEngine.pickDisplayMode(modes, DisplayModeInfo(cur.modeId, cur.physicalWidth, cur.physicalHeight, cur.refreshRate), fps) ?: return
        if (pick.id != cur.modeId) {
            val attrs = window.attributes
            attrs.preferredDisplayModeId = pick.id
            window.attributes = attrs
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onStarted()
        // Hintergrund-Audio: Media-Session-Dienst verbinden, damit die Wiedergabe beim Verlassen weiterläuft
        if (vm.backgroundAudioEnabled && controllerFuture == null) {
            controllerFuture = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        }
    }

    override fun onStop() {
        val keep = (vm.backgroundAudioEnabled && vm.isPlayingNow && !isFinishing) || inPip.value
        if (keep && vm.backgroundAudioEnabled && controllerFuture == null) {
            controllerFuture = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        }
        vm.onStopped(keepPlaying = keep)
        super.onStop()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }

    override fun finish() {
        val attrs = window.attributes
        attrs.preferredDisplayModeId = originalModeId
        window.attributes = attrs
        vm.onExit()
        super.finish()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_ID = "id"
        const val EXTRA_START = "startMs"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_CATCHUP_START = "catchupStart"
        const val EXTRA_CATCHUP_END = "catchupEnd"
    }
}
