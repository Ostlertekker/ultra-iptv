package de.ostlertekker.ultraiptv

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard
import de.ostlertekker.ultraiptv.core.diagnostics.TestHooks
import de.ostlertekker.ultraiptv.ui.Routes
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native Spotify-Oberfläche gegen einen nachgebauten Spotify-Web-API-Server: Bibliothek, Sammlung, Abspielen, Steuerung, Geräte. */
@RunWith(AndroidJUnit4::class)
class ZSpotifyTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val hooks by lazy { EntryPointAccessors.fromApplication(context.applicationContext, TestHooks::class.java) }
    private val requests: MutableList<String> = Collections.synchronizedList(ArrayList())

    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(300)

    private val jpeg: ByteArray by lazy {
        val bmp = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.rgb(30, 215, 96))
        java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
    private lateinit var base: String
    private var playing = false

    private fun track(i: Int) = """{"type":"track","id":"t$i","uri":"spotify:track:t$i","name":"Hardtekk Track $i","duration_ms":${180_000 + i * 1000},
        "artists":[{"name":"OSTLERTEKKER"},{"name":"Gast $i"}],"album":{"uri":"spotify:album:a1","images":[{"url":"$base/img/t$i.jpg"}]}}"""
    private fun playlist(i: Int) = """{"type":"playlist","id":"p$i","uri":"spotify:playlist:p$i","name":"Playlist $i","owner":{"display_name":"Ossi"},
        "tracks":{"total":${10 + i}},"images":${if (i % 3 == 0) "[]" else "[{\"url\":\"$base/img/p$i.jpg\"}]"}}"""

    private fun dispatch(r: RecordedRequest): MockResponse {
        val path = (r.path ?: "/").substringBefore('?')
        requests += "${r.method} $path"
        return when {
            path.startsWith("/img/") -> MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg))
            path == "/v1/me" -> json("""{"id":"ossi","display_name":"Ossi"}""")
            path == "/v1/me/playlists" -> json("""{"items":[${(1..12).joinToString(",") { playlist(it) }}]}""")
            path == "/v1/me/tracks" -> json("""{"items":[${(1..20).joinToString(",") { """{"track":${track(it)}}""" }}]}""")
            path == "/v1/me/albums" -> json("""{"items":[{"album":{"type":"album","id":"a1","uri":"spotify:album:a1","name":"Tekk Album","artists":[{"name":"OSTLERTEKKER"}],"images":[]}}]}""")
            path == "/v1/me/player/recently-played" -> json("""{"items":[${(1..10).joinToString(",") { """{"track":${track(it % 4)}}""" }}]}""")
            path == "/v1/me/top/tracks" -> json("""{"items":[${(5..15).joinToString(",") { track(it) }}]}""")
            path == "/v1/me/top/artists" -> json("""{"items":[{"type":"artist","id":"ar1","uri":"spotify:artist:ar1","name":"OSTLERTEKKER","images":[]}]}""")
            path.startsWith("/v1/playlists/") -> json("""{"items":[${(1..25).joinToString(",") { """{"track":${track(it)}}""" }}]}""")
            path == "/v1/search" -> json("""{"tracks":{"items":[${track(1)},${track(2)}]},"artists":{"items":[]},"albums":{"items":[]},"playlists":{"items":[null,${playlist(1)}]}}""")
            path == "/v1/me/player/devices" -> json("""{"devices":[{"id":"dev-tv","name":"Fire TV Wohnzimmer","type":"TV","is_active":false,"volume_percent":40}]}""")
            path == "/v1/me/player" && r.method == "GET" ->
                json("""{"is_playing":$playing,"progress_ms":42000,"shuffle_state":false,"repeat_state":"off","item":${track(3)},
                    "device":{"id":"dev-tv","name":"Fire TV Wohnzimmer","type":"TV","volume_percent":40}}""")
            path == "/v1/me/player/play" -> { playing = true; MockResponse().setResponseCode(204) }
            path == "/v1/me/player/pause" -> { playing = false; MockResponse().setResponseCode(204) }
            path.startsWith("/v1/me/player") -> MockResponse().setResponseCode(204)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun key(step: String, vararg codes: Int, pauseMs: Long = 350) {
        android.util.Log.i("UiSmoke", "Spotify: $step")
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
        StabilityGuard.lastFreeze(context)?.let { fail("Hänger nach „$step“:\n$it") }
    }

    private fun shot(name: String) {
        runCatching { instr.uiAutomation.executeShellCommand("mkdir -p /data/local/tmp/shots").close() }
        runCatching { instr.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/shots/sp_$name.png").close() }
        Thread.sleep(400)
    }

    @Test fun nativeSpotify() {
        StabilityGuard.clear(context)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = this@ZSpotifyTest.dispatch(request) }
        server.start()
        base = server.url("/").toString().trimEnd('/')
        val repo = hooks.spotify()
        try {
            // Ohne Anmeldung: Einrichtungsseite
            repo.logout()
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, Routes.SPOTIFY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
                Thread.sleep(3500); shot("einrichtung")
            }
            repo.apiBase = "$base/v1"; repo.accountsBase = base
            repo.setTokensForTest("acc", "ref")
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, Routes.SPOTIFY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
                Thread.sleep(5000); shot("bibliothek")
                key("Zur Bibliothek", KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
                shot("fokus")
                key("Kachel öffnen", KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(2500); shot("auswahl")
                key("Medientaste Play/Pause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); Thread.sleep(1500)
                key("Medientaste Weiter", KeyEvent.KEYCODE_MEDIA_NEXT); Thread.sleep(1500)
                key("Zurück", KeyEvent.KEYCODE_BACK); Thread.sleep(1500); shot("nach_zurueck")
            }
            android.util.Log.i("UiSmoke", "Spotify-Anfragen: " + requests.distinct().joinToString(" | "))
            assertTrue("Bibliothek geladen", requests.contains("GET /v1/me/playlists"))
            assertTrue("Läuft-gerade abgefragt", requests.contains("GET /v1/me/player"))
            assertTrue("Steuerung gesendet", requests.any { it == "PUT /v1/me/player/play" || it == "PUT /v1/me/player/pause" || it == "POST /v1/me/player/next" })
        } finally {
            repo.logout()
            server.shutdown()
        }
    }
}
