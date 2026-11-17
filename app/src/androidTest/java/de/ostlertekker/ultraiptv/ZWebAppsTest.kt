package de.ostlertekker.ultraiptv

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard
import de.ostlertekker.ultraiptv.feature.web.WebApp
import de.ostlertekker.ultraiptv.feature.web.WebAppActivity
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** YouTube (TV-Oberfläche) und Spotify (Web-Player) eingebettet: laden, per Fernbedienung bedienen, kein Hänger/Absturz. */
@RunWith(AndroidJUnit4::class)
class ZWebAppsTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext

    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(300)

    private fun key(step: String, vararg codes: Int, pauseMs: Long = 600) {
        android.util.Log.i("UiSmoke", "Web: $step")
        for (c in codes) {
            val t = android.os.SystemClock.uptimeMillis()
            instr.uiAutomation.injectInputEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, c, 0), true)
            instr.uiAutomation.injectInputEvent(KeyEvent(t, t + 30, KeyEvent.ACTION_UP, c, 0), true)
            Thread.sleep(pauseMs)
        }
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { latch.countDown() }
        if (!latch.await(3, TimeUnit.SECONDS)) fail("App hängt: $step")
        StabilityGuard.lastCrash(context)?.let { fail("Absturz nach „$step“:\n$it") }
    }

    private fun shot(name: String) {
        runCatching { instr.uiAutomation.executeShellCommand("mkdir -p /data/local/tmp/shots").close() }
        runCatching { instr.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/shots/web_$name.png").close() }
        Thread.sleep(500)
    }

    private fun launch(app: WebApp) = ActivityScenario.launch<WebAppActivity>(
        Intent(context, WebAppActivity::class.java).putExtra(WebAppActivity.EXTRA_APP, app.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))

    @Test fun youtubeAndSpotifyEmbedded() {
        StabilityGuard.clear(context)
        launch(WebApp.YOUTUBE).use {
            Thread.sleep(15_000); shot("youtube_start")
            key("YouTube navigieren", KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT)
            Thread.sleep(2000); shot("youtube_navigiert")
            key("YouTube Zurück (kurz)", KeyEvent.KEYCODE_BACK)
            Thread.sleep(1500); shot("youtube_zurueck")
        }
        launch(WebApp.SPOTIFY).use {
            Thread.sleep(15_000); shot("spotify_start")
            key("Spotify Pfeiltasten-Navigation", KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, pauseMs = 400)
            Thread.sleep(800); shot("spotify_fokus")
            key("Spotify klicken", KeyEvent.KEYCODE_DPAD_CENTER)
            Thread.sleep(3000); shot("spotify_klick")
            key("Spotify Wiedergabe-Taste", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }
}
