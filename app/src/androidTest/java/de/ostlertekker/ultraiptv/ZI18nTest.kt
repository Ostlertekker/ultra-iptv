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
