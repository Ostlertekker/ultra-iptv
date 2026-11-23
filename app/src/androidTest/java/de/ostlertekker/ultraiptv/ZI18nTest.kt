package de.ostlertekker.ultraiptv

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ostlertekker.ultraiptv.core.i18n.I18n
import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Sprachen: Übersetzungsdateien laden, Oberfläche auf Englisch/Türkisch fotografieren, danach zurück auf Deutsch. */
@RunWith(AndroidJUnit4::class)
class ZI18nTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext

    private fun shot(name: String) {
        runCatching { instr.uiAutomation.executeShellCommand("mkdir -p /data/local/tmp/shots").close() }
        runCatching { instr.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/shots/i18n_$name.png").close() }
    }

    private fun open(route: String, name: String) {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { Thread.sleep(6000); shot(name) }
    }

    /** Sprachwechsel wie ein Nutzer: Neustart über den Hilfsprozess, App muss danach wieder im Vordergrund sein. */
    @Test fun restartAfterSwitch() {
        try {
            I18n.save(context, "de"); I18n.init(context)
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, Routes.SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { sc ->
                Thread.sleep(4000)
                I18n.save(context, "en")
                context.startActivity(Intent(context, de.ostlertekker.ultraiptv.core.i18n.RestartActivity::class.java)
                    .putExtra(de.ostlertekker.ultraiptv.core.i18n.RestartActivity.EXTRA_PID, -1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Thread.sleep(6000)
                shot("restart_after")
                val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instr.uiAutomation.executeShellCommand("dumpsys activity activities")).bufferedReader().readText()
                check(dump.lines().any { it.contains("mResumedActivity") && it.contains("ultraiptv") }) { "App nach Neustart nicht im Vordergrund" }
            }
        } finally {
            I18n.save(context, "system"); I18n.init(context)
        }
    }

    @Test fun languages() {
        try {
            for (code in listOf("en", "tr", "pl", "es", "fr")) {
                I18n.save(context, code); I18n.init(context)
                assertEquals(code, I18n.lang)
                check(tr("Einstellungen") != "Einstellungen") { "$code: keine Übersetzung geladen" }
            }
            I18n.save(context, "en"); I18n.init(context)
            open(Routes.SETTINGS, "en_settings")
            open(Routes.HOME, "en_home")
            I18n.save(context, "tr"); I18n.init(context)
            open(Routes.SETTINGS, "tr_settings")
        } finally {
            I18n.save(context, "system"); I18n.init(context)
        }
    }
}
