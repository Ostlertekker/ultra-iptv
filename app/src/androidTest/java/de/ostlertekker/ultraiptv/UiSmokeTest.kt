package de.ostlertekker.ultraiptv

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dagger.hilt.android.EntryPointAccessors
import de.ostlertekker.ultraiptv.core.data.Keys
import de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard
import de.ostlertekker.ultraiptv.core.diagnostics.TestHooks
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.model.ProviderConfig
import de.ostlertekker.ultraiptv.core.model.ProviderType
import de.ostlertekker.ultraiptv.ui.Routes
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Automatischer Oberflächentest auf einem echten Android-Emulator:
 * legt eine Test-Quelle (lokaler Mock-Server) an, öffnet jede Seite, bedient sie per Fernbedienungstasten,
 * startet Sender/Filme und prüft dabei laufend, dass die App nicht abstürzt und nicht hängt.
 */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private lateinit var server: MockWebServer
    private val hooks by lazy { EntryPointAccessors.fromApplication(context.applicationContext, TestHooks::class.java) }

    @Before fun setUp() {
        StabilityGuard.clear(context)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                return when {
                    path.startsWith("/list.m3u") -> MockResponse().setBody(playlist())
                    path.startsWith("/epg.xml") -> MockResponse().setBody(epg())
                    // Echte Fehlerfälle von Anbietern nachstellen
                    path.contains("/live/404") -> MockResponse().setResponseCode(404)
                    path.contains("/live/403") -> MockResponse().setResponseCode(403)
                    path.contains("/live/") -> MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(ByteArray(188 * 400) { if (it % 188 == 0) 0x47 else 0 }))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        hooks.credentials().save(ProviderConfig(ProviderType.M3U, "Testquelle", m3uUrl = "$base/list.m3u", epgUrl = "$base/epg.xml"))
        runBlocking {
            hooks.settings().set(Keys.INTRO_PLAYED, true)
            hooks.settings().set(Keys.INTRO, false)
            hooks.settings().set(Keys.LAST_SYNC, System.currentTimeMillis())
            hooks.settings().set(Keys.LAST_EPG_SYNC, System.currentTimeMillis())
            hooks.settings().set(Keys.UPDATE_CHECK, false)
            hooks.user().ensureDefaultProfile()
            assertTrue("Sync der Testquelle fehlgeschlagen", hooks.sync().syncAll(includeEpg = true))
            // Favoriten aller Arten anlegen
            hooks.content().channelsAllOnce(200).forEach { hooks.user().toggleFavorite(ContentKind.LIVE, it.id) }
            kotlinx.coroutines.withTimeout(10_000) {
                hooks.content().movies(null, null, de.ostlertekker.ultraiptv.core.data.SortOrder.entries.first(), 40)
                    .first { it.isNotEmpty() }.forEach { hooks.user().toggleFavorite(ContentKind.MOVIE, it.id) }
                hooks.content().series(null, de.ostlertekker.ultraiptv.core.data.SortOrder.entries.first(), 10)
                    .first { it.isNotEmpty() }.forEach { hooks.user().toggleFavorite(ContentKind.SERIES, it.id) }
            }
        }
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun playlist(): String = buildString {
        val base = server.url("/").toString().trimEnd('/')
        appendLine("#EXTM3U")
        val groups = listOf("Deutschland", "Sport", "Kinder", "Musik")
        for (i in 1..600) {
            val g = groups[i % groups.size]
            val stream = when (i % 10) { 3 -> "404"; 7 -> "403"; else -> "ok$i" }
            appendLine("#EXTINF:-1 tvg-id=\"ch$i\" tvg-name=\"Sender $i\" tvg-logo=\"$base/logo/$i.png\" group-title=\"$g\",Testsender $i HD")
            appendLine("$base/live/$stream.ts")
        }
        for (i in 1..120) {
            appendLine("#EXTINF:-1 tvg-logo=\"$base/poster/$i.jpg\" group-title=\"Filme Action\",Testfilm $i (2024)")
            appendLine("$base/movie/film$i.mp4")
        }
        for (s in 1..3) for (e in 1..5) {
            appendLine("#EXTINF:-1 group-title=\"Serien\",Testserie $s S01E0$e")
            appendLine("$base/series/s${s}e$e.mp4")
        }
    }

    private fun epg(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>")
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
        val now = System.currentTimeMillis() - 30 * 60_000L
        for (i in 1..80) {
            append("<channel id=\"ch$i\"><display-name>Sender $i</display-name></channel>")
            for (p in 0 until 6) {
                val st = now + p * 3_600_000L
                append("<programme start=\"${fmt.format(st)}\" stop=\"${fmt.format(st + 3_600_000L)}\" channel=\"ch$i\"><title>Sendung $p auf $i</title><desc>Beschreibung</desc></programme>")
            }
        }
        append("</tv>")
    }

    // ------------------------------------------------------------- Hilfen

    /** Der UI-Thread muss innerhalb von 3 s reagieren – sonst hängt die App. */
    private fun assertResponsive(step: String) {
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { latch.countDown() }
        if (!latch.await(3, TimeUnit.SECONDS)) fail("App hängt nach Schritt: $step")
        StabilityGuard.lastFreeze(context)?.let { fail("Hänger aufgezeichnet nach „$step“:\n$it") }
    }

    /** Bildschirmfoto (als Shell aufgenommen → überlebt die Deinstallation, CI lädt es hoch). */
    private var shotNo = 0
    private fun shot(name: String) {
        shotNo++
        runCatching { device.executeShellCommand("mkdir -p /data/local/tmp/shots") }
        runCatching { device.executeShellCommand("screencap -p /data/local/tmp/shots/%02d_%s.png".format(shotNo, name)) }
    }

    private fun keys(step: String, vararg codes: Int, pauseMs: Long = 220) {
        for (c in codes) { device.pressKeyCode(c); Thread.sleep(pauseMs) }
        assertResponsive(step)
    }

    private fun openRoute(route: String): ActivityScenario<MainActivity> {
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val sc = ActivityScenario.launch<MainActivity>(intent)
        Thread.sleep(3500)
        assertResponsive("Seite $route geöffnet")
        return sc
    }

    private val R = KeyEvent.KEYCODE_DPAD_RIGHT
    private val L = KeyEvent.KEYCODE_DPAD_LEFT
    private val U = KeyEvent.KEYCODE_DPAD_UP
    private val D = KeyEvent.KEYCODE_DPAD_DOWN
    private val OK = KeyEvent.KEYCODE_DPAD_CENTER
    private val BACK = KeyEvent.KEYCODE_BACK

    // ------------------------------------------------------------- Test

    @Test fun everyScreenWorksWithRemote() {
        // Favoriten (gemeldetes Problem) – mehrfach rein, bedienen, Sender starten, zurück
        repeat(3) { round ->
            openRoute(Routes.FAVORITES).use {
                if (round == 0) shot("favoriten")
                keys("Favoriten Runde $round betreten", R, R, D, R, R, L)
                keys("Favoriten weit scrollen", *IntArray(40) { R }, pauseMs = 70)
                keys("Favoriten Filme/Serien", D, *IntArray(10) { R }, D, R, R, U, U, pauseMs = 120)
                if (round == 0) shot("favoriten_fokus")
                keys("Favoriten Sender starten", OK); Thread.sleep(6000); assertResponsive("Favoriten → Player")
                if (round == 0) shot("player_aus_favoriten")
                keys("Player zappen", D, D, U); Thread.sleep(3000)
                keys("Player verlassen", BACK, BACK); Thread.sleep(1500)
                keys("Favoriten nach Player", R, D, U, L, L)
            }
        }
        // Live TV: Kategorien, schnelles Scrollen mit Vorschau, Fehler-Sender (404/403), Player
        openRoute(Routes.LIVE).use {
            shot("live_tv")
            keys("Live Kategorien", D, D, D, OK, R)
            keys("Live Dauer-Scrollen", *IntArray(25) { D }, pauseMs = 60)
            Thread.sleep(2500)
            shot("live_tv_vorschau")
            keys("Live hoch", *IntArray(10) { U }, pauseMs = 80)
            keys("Live Sender öffnen", OK); Thread.sleep(6000); assertResponsive("Live Player")
            keys("Live zappen inkl. Fehlersender", D, D, D, D, pauseMs = 1500); Thread.sleep(4000)
            keys("Ziffern-Direktwahl", KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2); Thread.sleep(3000)
            keys("Overlay öffnen", OK); Thread.sleep(600); shot("player_overlay")
            keys("Overlay + Menüs", R, R, R, OK, BACK, BACK); Thread.sleep(1000)
            keys("Player zu", BACK, BACK); Thread.sleep(1500)
        }
        for (route in listOf(Routes.HOME, Routes.MOVIES, Routes.SERIES, Routes.WORLD, Routes.SEARCH, Routes.EPG)) {
            openRoute(route).use {
                shot(route)
                keys("$route bedienen", R, R, D, D, R, R, D, U, U, L)
                keys("$route öffnen", OK); Thread.sleep(3500); assertResponsive("$route Detail")
                keys("$route zurück", BACK); Thread.sleep(1500)
                keys("$route nach Zurück", D, R, U)
            }
        }
        // Einstellungen: jede Sektion einmal rendern
        openRoute(Routes.SETTINGS).use {
            keys("Einstellungen betreten", R)
            for (i in 0 until 32) {
                keys("Sektion $i", D, pauseMs = 350)
                if (i in listOf(4, 5, 11, 12, 13, 23, 24)) shot("einstellungen_$i")
            }
            keys("Sektionen zurück", *IntArray(32) { U }, pauseMs = 120)
        }
        // Volles Intro mit explodierendem Planeten
        runBlocking { hooks.settings().set(Keys.INTRO, true); hooks.settings().set(Keys.INTRO_PLAYED, false) }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            Thread.sleep(700)
            var prev = 0L
            for (ms in listOf(0L, 600L, 1100L, 1500L, 1900L, 2400L, 4200L)) { Thread.sleep(ms - prev); prev = ms; shot("intro_$ms") }
            Thread.sleep(4000); shot("hauptmenue_nach_intro")
            assertResponsive("Intro")
        }
        StabilityGuard.lastCrash(context)?.let { fail("Absturz aufgezeichnet:\n$it") }
        StabilityGuard.lastFreeze(context)?.let { fail("Hänger aufgezeichnet:\n$it") }
    }
}
