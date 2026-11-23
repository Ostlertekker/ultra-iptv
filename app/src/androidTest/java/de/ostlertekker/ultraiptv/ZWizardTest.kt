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
            val D = KeyEvent.KEYCODE_DPAD_DOWN; val U = KeyEvent.KEYCODE_DPAD_UP; val R = KeyEvent.KEYCODE_DPAD_RIGHT; val OK = KeyEvent.KEYCODE_DPAD_CENTER
            fun keys(vararg c: Int) { for (k in c) { key(k); Thread.sleep(250) } }
            shot("0_willkommen"); keys(OK); Thread.sleep(2500)
            shot("1_iptv"); keys(U, R, OK); Thread.sleep(2500)
            shot("2_jellyfin"); keys(D, D, D, D, R, R, OK); Thread.sleep(2500)
            shot("3_seerr"); keys(D, D, D, R, R, OK); Thread.sleep(2500)
            shot("4_wetter"); keys(D, R, R, OK); Thread.sleep(2500)
            shot("5_news"); keys(D, D, R, OK); Thread.sleep(2500)
            shot("6_fertig"); keys(OK)
            Thread.sleep(2500)
            shot("9_danach")
            StabilityGuard.lastCrash(context)?.let { throw AssertionError("Absturz:\n$it") }
            assertTrue(true)
        }
    }
}
