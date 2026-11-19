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
import de.ostlertekker.ultraiptv.core.discover.League
import de.ostlertekker.ultraiptv.core.discover.ScoreCompetitor
import de.ostlertekker.ultraiptv.core.discover.ScoreEvent
import de.ostlertekker.ultraiptv.core.discover.SetScore
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
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Automatischer Oberflächentest auf einem echten Android-Emulator:
 * legt eine Test-Quelle (lokaler Mock-Server) an, öffnet jede Seite, bedient sie per Fernbedienungstasten,
 * startet Sender/Filme und prüft dabei laufend, dass die App nicht abstürzt und nicht hängt.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UiSmokeTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    companion object {
        /** Mock-Server und Testdaten werden einmal pro Testlauf angelegt (gleiche Sender-IDs für alle Abschnitte). */
        lateinit var server: MockWebServer
        var seeded = false
    }
    private val hooks by lazy { EntryPointAccessors.fromApplication(context.applicationContext, TestHooks::class.java) }

    @Before fun setUp() {
        StabilityGuard.clear(context)
        // Laufschriften im Test aus: auf dem langsamen Emulator wird der Hauptthread sonst nie „ruhig“
        // und ActivityScenario.close() wartet endlos (auf echten Geräten kein Problem)
        runBlocking { hooks.settings().set(Keys.NEWS_ENABLED, false); hooks.settings().set(Keys.SPORT_TICKER_ENABLED, false) }
        if (seeded) return
        seeded = true
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                return when {
                    path.startsWith("/list.m3u") -> MockResponse().setBody(playlist())
                    path.startsWith("/epg.xml") -> MockResponse().setBody(epg())
                    // Große, echte Poster wie bei vielen Anbietern (1500×2250 JPEG)
                    path.startsWith("/poster/") -> MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bigPoster))
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
        // Server bleibt für die folgenden Abschnitte aktiv; der Prozess endet nach dem Testlauf
    }

    private val bigPoster: ByteArray by lazy {
        val bmp = android.graphics.Bitmap.createBitmap(1500, 2250, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.rgb(40, 60, 120))
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(200, 220, 80); textSize = 180f }
        for (y in 0 until 2250 step 300) c.drawText("POSTER", 100f, y.toFloat() + 200f, paint)
        java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    }

    private fun playlist(): String = buildString {
        val base = server.url("/").toString().trimEnd('/')
        appendLine("#EXTM3U")
        val groups = listOf("Deutschland", "Sport", "Kinder", "Musik")
        for (i in 1..600) {
            val g = groups[i % groups.size]
            val stream = when (i % 10) { 3 -> "404"; 7 -> "403"; else -> "ok$i" }
            val cu = if (i % 4 == 0) " catchup=\"shift\" catchup-days=\"3\"" else ""
            appendLine("#EXTINF:-1 tvg-id=\"ch$i\" tvg-name=\"Sender $i\" tvg-logo=\"$base/logo/$i.png\" group-title=\"$g\"$cu,Testsender $i HD")
            appendLine("$base/live/$stream.ts")
        }
        // Realistische Größe: 20.000 Filme in 60 Kategorien, 300 Serien mit je 10 Folgen
        for (i in 1..20000) {
            appendLine("#EXTINF:-1 tvg-logo=\"$base/poster/$i.jpg\" group-title=\"Filme Kategorie ${i % 60}\",Testfilm $i (${1980 + i % 45})")
            appendLine("$base/movie/film$i.mp4")
        }
        for (s in 1..300) for (e in 1..10) {
            appendLine("#EXTINF:-1 tvg-logo=\"$base/poster/s$s.jpg\" group-title=\"Serien ${s % 20}\",Testserie $s S01E${"%02d".format(e)}")
            appendLine("$base/series/s${s}e$e.mp4")
        }
    }

    private fun epg(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>")
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
        val now = System.currentTimeMillis() - 6 * 3_600_000L
        for (i in 1..80) {
            append("<channel id=\"ch$i\"><display-name>Sender $i</display-name></channel>")
            for (p in 0 until 14) {
                val st = now + p * 3_600_000L
                val title = when {
                    i % 5 == 0 && p % 3 == 0 -> "Fußball: 1. Bundesliga – Testspiel $p"
                    i % 7 == 0 -> "Comedy-Show $p"
                    i % 11 == 0 -> "Tatort: Der Kommissar ermittelt"
                    else -> "Sendung $p auf $i"
                }
                append("<programme start=\"${fmt.format(st)}\" stop=\"${fmt.format(st + 3_600_000L)}\" channel=\"ch$i\"><title>$title</title><desc>Beschreibung</desc></programme>")
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

    /** Leistungsmessung: Bildzeiten des App-Fensters (gfxinfo) für einen Abschnitt – landet im CI-Bericht (perf.txt). */
    private fun perfStart() { runCatching { device.executeShellCommand("dumpsys gfxinfo ${context.packageName} reset") } }
    private fun perfEnd(name: String) {
        val out = runCatching { device.executeShellCommand("dumpsys gfxinfo ${context.packageName}") }.getOrDefault("")
        fun v(label: String) = out.lineSequence().firstOrNull { it.trim().startsWith(label) }?.substringAfter(":")?.trim() ?: "?"
        android.util.Log.i("UiPerf", "$name | Frames ${v("Total frames rendered")} | Ruckler ${v("Janky frames")} | " +
            "p50 ${v("50th percentile")} | p90 ${v("90th percentile")} | p99 ${v("99th percentile")} | langsamer UI-Thread ${v("Number Slow UI thread")}")
    }

    private fun keys(step: String, vararg codes: Int, pauseMs: Long = 220) {
        android.util.Log.i("UiSmoke", "Schritt: $step")
        // Tasten direkt einspeisen (ohne auf Barrierefreiheits-Ruhe zu warten – Animationen laufen dauerhaft)
        for (c in codes) {
            val t = android.os.SystemClock.uptimeMillis()
            instr.uiAutomation.injectInputEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, c, 0), true)
            instr.uiAutomation.injectInputEvent(KeyEvent(t, t + 30, KeyEvent.ACTION_UP, c, 0), true)
            Thread.sleep(pauseMs)
        }
        assertResponsive(step)
    }

    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(420)

    private fun openRoute(route: String): ActivityScenario<MainActivity> {
        android.util.Log.i("UiSmoke", "Öffne Seite: $route")
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

    @Test fun b_liveTvAndPlayer() {
        // Live TV: Kategorien, schnelles Scrollen mit Vorschau, Fehler-Sender (404/403), Player
        openRoute(Routes.LIVE).use {
            shot("live_tv")
            keys("Live Kategorien", D, D, D, OK, R)
            perfStart()
            keys("Live Dauer-Scrollen", *IntArray(25) { D }, pauseMs = 60)
            perfEnd("Live-TV Senderliste scrollen")
            Thread.sleep(2500)
            shot("live_tv_vorschau")
            keys("Live hoch", *IntArray(10) { U }, pauseMs = 80)
            keys("Live Sender öffnen", OK); Thread.sleep(6000); assertResponsive("Live Player")
            // Live-Zeitversatz: Puffer füllen, dann zurück- und vorspulen, pausieren, zurück zu Live
            Thread.sleep(6000)
            keys("Live zurückspulen 3× 10 s", L, L, L, pauseMs = 150); Thread.sleep(300); shot("live_spulen_vorschau")
            Thread.sleep(2500); assertResponsive("Live nach Zurückspulen")
            keys("Live Rückspultaste", KeyEvent.KEYCODE_MEDIA_REWIND, pauseMs = 300); Thread.sleep(2500)
            keys("Live Pause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); Thread.sleep(3000)
            keys("Live weiter", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); Thread.sleep(1500)
            keys("Live Overlay", OK); Thread.sleep(800); shot("live_zeitleiste")
            keys("Overlay zu", BACK)
            keys("Live vorspulen", R, R, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, pauseMs = 200); Thread.sleep(2500)
            assertResponsive("Live nach Vorspulen")
            keys("Live zappen inkl. Fehlersender", D, D, D, D, pauseMs = 1500); Thread.sleep(4000)
            keys("Ziffern-Direktwahl", KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2); Thread.sleep(3000)
            keys("Overlay öffnen", OK); Thread.sleep(600); shot("player_overlay")
            keys("Overlay + Menüs", R, R, R, OK, BACK, BACK); Thread.sleep(1000)
            keys("Overlay für Mehr", OK); Thread.sleep(600)
            keys("Zu „Mehr“", *IntArray(14) { R }, OK, pauseMs = 120); Thread.sleep(700); shot("player_mehr")
            keys("Mehr zu", BACK, BACK); Thread.sleep(800)
            keys("Player zu", BACK, BACK); Thread.sleep(1500)
        }
        finish()
    }

    @Test fun c_classicScreens() {
        // Filme & Serien mit großer Datenmenge: öffnen, lange scrollen (Nachladen), Kategorie wechseln, Detail öffnen
        for (route in listOf(Routes.MOVIES, Routes.SERIES)) {
            openRoute(route).use {
                Thread.sleep(4000); shot("${route}_gross")
                keys("$route betreten", R, R, D, D, pauseMs = 300)
                perfStart()
                keys("$route lange scrollen", *IntArray(60) { D }, pauseMs = 90)
                perfEnd("$route Übersicht scrollen")
                Thread.sleep(2500); assertResponsive("$route nach Scrollen"); shot("${route}_gescrollt")
                keys("$route nach oben", *IntArray(70) { U }, pauseMs = 60)
                keys("$route Kategorie", U, U, R, R, R, OK, pauseMs = 300); Thread.sleep(2500)
                keys("$route Detail", D, D, D, OK); Thread.sleep(3500); assertResponsive("$route Detail")
                keys("$route zurück", BACK); Thread.sleep(1500)
            }
        }
        // Serien › „Alle Serien“: Unterkategorien links untereinander (wie Live-TV)
        openRoute(Routes.SERIES).use {
            device.findObject(androidx.test.uiautomator.By.text("Alle Serien"))?.click(); Thread.sleep(2000); shot("serien_alle_kategorien")
        }
        // Filme › „Alle Filme“: Unterkategorien links untereinander (wie Live-TV)
        openRoute(Routes.MOVIES).use {
            device.findObject(androidx.test.uiautomator.By.text("Alle Filme"))?.click(); Thread.sleep(2000); shot("movies_alle_kategorien")
            device.findObject(androidx.test.uiautomator.By.text("Filme Kategorie 2"))?.click(); Thread.sleep(2000); shot("movies_kategorie_gewaehlt")
        }
        for (route in listOf(Routes.HOME, Routes.MOVIES, Routes.SERIES, Routes.SEARCH, Routes.EPG)) {
            openRoute(route).use {
                shot(route)
                if (route == Routes.HOME) { keys("Start: Titelbild", D, pauseMs = 600); shot("home_titelbild_fokus"); perfStart(); keys("Start: Reihen", D, D, R, R, *IntArray(12) { D }, *IntArray(12) { R }, pauseMs = 150); perfEnd("Startseite Reihen scrollen"); keys("Start: hoch", *IntArray(14) { U }, pauseMs = 80); shot("home_reihen") }
                perfStart()
                keys("$route bedienen", R, R, D, D, R, R, D, U, U, L)
                if (route == Routes.EPG) keys("EPG scrollen", *IntArray(20) { D }, *IntArray(10) { R }, pauseMs = 90)
                perfEnd("$route bedienen")
                keys("$route öffnen", OK); Thread.sleep(3500); assertResponsive("$route Detail")
                keys("$route zurück", BACK); Thread.sleep(1500)
                keys("$route nach Zurück", D, R, U)
            }
        }
        finish()
    }

    @Test fun d_discoverScreens() {
        // Neue Entdecken-Funktionen: jede Seite öffnen, bedienen, Aktion auslösen
        val past = System.currentTimeMillis() - 2 * 3_600_000L
        for (route in listOf(Routes.EXPLORE, Routes.SPORTS, Routes.RADIO)) {
            val name = route.substringBefore('?')
            openRoute(route).use {
                Thread.sleep(1500)
                shot(name)
                keys("$name bedienen", R, R, D, D, R, U, pauseMs = 300)
                keys("$name Aktion", OK); Thread.sleep(4500); assertResponsive("$name Aktion")
                if (name == "timemachine") shot("catchup_player")
                if (name == "radio") { Thread.sleep(3000); shot("radio_wiedergabe") }
                keys("$name zurück", BACK); Thread.sleep(1500)
                keys("$name nach Zurück", D, U)
            }
        }
        finish()
    }

    @Test fun e_weather() {
        // Wetter: Ort setzen (Berlin) → animiertes Symbol links neben dem Schriftzug + Wetterleiste auf der Startseite
        kotlinx.coroutines.runBlocking {
            val st = hooks.settings()
            st.set(de.ostlertekker.ultraiptv.core.data.Keys.WEATHER_PLACE, "Berlin, DE")
            st.set(de.ostlertekker.ultraiptv.core.data.Keys.WEATHER_LAT, "52.52437")
            st.set(de.ostlertekker.ultraiptv.core.data.Keys.WEATHER_LON, "13.41053")
        }
        openRoute(Routes.HOME).use { Thread.sleep(9000); shot("wetter_start") }
        finish()
    }

    @Test fun e_sportTicker() {
        // Sport-Ticker mit zwei kurzen Tennis-Einträgen: Fotoserie, um das Durchlaufen zu prüfen
        fun c(n: String, g: List<Int>) = ScoreCompetitor(n, null, null, null, null, false, null, g.map { SetScore(it, null) })
        de.ostlertekker.ultraiptv.feature.home.HomeViewModel.testSportEvents = listOf(
            ScoreEvent("t1", League.ATP, "Sinner – Alcaraz", System.currentTimeMillis(), "in", "", null, listOf(c("J. Sinner", listOf(6, 3)), c("C. Alcaraz", listOf(4, 2))), null),
            ScoreEvent("t2", League.WTA, "Swiatek – Gauff", System.currentTimeMillis(), "in", "", null, listOf(c("I. Swiatek", listOf(2)), c("C. Gauff", listOf(5))), null),
        )
        runBlocking { hooks.settings().set(Keys.SPORT_TICKER_ENABLED, true) }
        val sc = openRoute(Routes.HOME)
        Thread.sleep(2500)
        repeat(14) { i -> shot("sportticker_%02d".format(i)); Thread.sleep(700) }
        // Ticker vor dem Schließen abschalten, damit der Test das Fenster schließen kann
        runBlocking { hooks.settings().set(Keys.SPORT_TICKER_ENABLED, false) }
        Thread.sleep(1500)
        sc.close()
        de.ostlertekker.ultraiptv.feature.home.HomeViewModel.testSportEvents = null
        finish()
    }

    @Test fun e_homeFocus() {
        // Diagnose Startseite: nach jedem Tastendruck Foto + Position des fokussierten Elements im Dateinamen
        fun where(): String = runCatching {
            val f = device.findObject(androidx.test.uiautomator.By.focused(true))
            if (f == null) "nichts" else f.visibleBounds.let { b -> "x${b.left}y${b.top}w${b.width()}" }
        }.getOrDefault("fehler")
        openRoute(Routes.HOME).use {
            Thread.sleep(3000); shot("homefokus_start_" + where())
            listOf("D1" to D, "D2" to D, "D3" to D, "R1" to R, "U1" to U, "U2" to U).forEach { (n, k) ->
                keys("Start $n", k, pauseMs = 700); shot("homefokus_${n}_" + where())
            }
        }
        finish()
    }

    @Test fun e_popups() {
        // Erinnerungs- und Update-Einblendung im Menü sichtbar machen (Testdaten)
        openRoute(Routes.LIVE).use {
            val n = hooks.notifications()
            n.popup.value = de.ostlertekker.ultraiptv.core.database.ReminderEntity(9_999, "x", "Testsender 1 HD", "Fußball: 1. Bundesliga – Testspiel", System.currentTimeMillis() + 120_000)
            Thread.sleep(2500); shot("popup_erinnerung")
            n.popup.value = null
            n.updatePopup.value = de.ostlertekker.ultraiptv.core.update.UpdateState.Available("9.9.9", 99999, "- Neue Einblendungen im App-Stil", "https://example.invalid", 14_000_000)
            Thread.sleep(2500); shot("popup_update")
            n.updatePopup.value = null
        }
        finish()
    }

    @Test fun e_matchCenter() {
        // Sport → zweites Live-Spiel wählen → OK nochmal = Match-Center im Vollbild, Reiter durchschalten
        openRoute(Routes.SPORTS).use {
            Thread.sleep(4000)
            keys("Spiel wählen", D, D, OK, pauseMs = 700); Thread.sleep(2500); shot("sport_gewaehlt")
            keys("Match-Center öffnen", OK); Thread.sleep(7000); shot("match_center")
            keys("Reiter 2", R); Thread.sleep(2500); shot("match_center_reiter2")
            keys("Reiter 3", R); Thread.sleep(2500); shot("match_center_reiter3")
            keys("Inhalt + Reiter 4", D, R, pauseMs = 600); Thread.sleep(2500); shot("match_center_reiter4")
            keys("Reiter 5", R); Thread.sleep(2500); shot("match_center_reiter5")
            keys("Schließen", BACK); Thread.sleep(2000); assertResponsive("Match-Center geschlossen")
        }
        finish()
    }

    @Test fun f_settingsAndIntro() {
        // Einstellungen: jede Sektion einmal rendern
        openRoute(Routes.SETTINGS).use {
            keys("Einstellungen betreten", D)
            Thread.sleep(800); shot("einstellungen_00")
            // Jede Kategorie der linken Leiste einmal anzeigen und fotografieren
            for (i in 1 until 13) {
                keys("Kategorie $i", D, pauseMs = 700)
                shot("einstellungen_%02d".format(i))
            }
            keys("Kategorien zurück", *IntArray(13) { U }, pauseMs = 120)
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
        finish()
    }

    private fun finish() {
        StabilityGuard.lastCrash(context)?.let { fail("Absturz aufgezeichnet:\n$it") }
        StabilityGuard.lastFreeze(context)?.let { fail("Hänger aufgezeichnet:\n$it") }
    }
}
