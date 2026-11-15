package de.ostlertekker.ultraiptv.feature.player

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import de.ostlertekker.ultraiptv.core.data.AppSettings
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.device.DeviceCapabilityManager
import de.ostlertekker.ultraiptv.core.player.DisplayModeInfo
import de.ostlertekker.ultraiptv.core.player.UltraQualityEngine
import de.ostlertekker.ultraiptv.core.ui.UltraThemeHost
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()
    @Inject lateinit var device: DeviceCapabilityManager
    @Inject lateinit var settingsRepo: SettingsRepository
    private var originalModeId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        originalModeId = window.attributes.preferredDisplayModeId
        setContent {
            val settings by settingsRepo.settings.collectAsStateWithLifecycle<AppSettings?>(initialValue = null)
            val s = settings
            if (s == null) Box(Modifier.fillMaxSize().background(Color.Black))
            else UltraThemeHost(s, device.tier, null) {
                PlayerScreen(vm, onExit = { finish() })
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.desiredFps.collect { fps -> if (fps != null) applyFrameRate(fps) }
            }
        }
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
    }

    override fun onStop() {
        vm.onStopped()
        super.onStop()
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
    }
}
