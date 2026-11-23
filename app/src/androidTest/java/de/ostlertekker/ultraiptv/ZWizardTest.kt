package de.ostlertekker.ultraiptv

import android.content.Intent
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Einrichtungsassistent beim ersten Start (ohne Quelle): alle Schritte durchklicken, Bildschirmfotos. */
@RunWith(AndroidJUnit4::class)
class ZWizardTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext

    private fun shot(name: String) {
        runCatching { instr.uiAutomation.executeShellCommand("mkdir -p /data/local/tmp/shots").close() }
        runCatching { instr.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/shots/wiz_$name.png").close() }
    }

    private fun key(code: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        instr.uiAutomation.injectInputEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0), true)
        instr.uiAutomation.injectInputEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0), true)
    }

    @Test fun wizardWalkthrough() {
        StabilityGuard.clear(context)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
            Thread.sleep(14_000) // Intro abwarten
            shot("0_start")
            for (i in 1..8) {
                Thread.sleep(if (i == 1) 500 else 2600)
                shot("${i}_schritt")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
            }
            Thread.sleep(2500)
            shot("9_danach")
            StabilityGuard.lastCrash(context)?.let { throw AssertionError("Absturz:\n$it") }
            assertTrue(true)
        }
    }
}
