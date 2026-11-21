package de.ostlertekker.ultraiptv

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import de.ostlertekker.ultraiptv.core.data.Keys
import de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard
import de.ostlertekker.ultraiptv.core.diagnostics.TestHooks
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.ui.Routes
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Nachgebauter Jellyfin-Server (offizielle REST-API) mit typischen Daten-Eigenheiten:
 * Anmeldung, Übersicht, Bibliothek, Film/Serie, Wiedergabe im eigenen Player, Fortschritt an den Server.
 */
@RunWith(AndroidJUnit4::class)
class ZJellyfinTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val hooks by lazy { EntryPointAccessors.fromApplication(context.applicationContext, TestHooks::class.java) }
    private val requests: MutableList<String> = Collections.synchronizedList(ArrayList())

    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val jpeg: ByteArray by lazy {
        val bmp = android.graphics.Bitmap.createBitmap(1280, 720, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.rgb(20, 60, 110))
        java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)

    private fun movie(i: Int): String {
        val img = if (i % 4 == 0) "{}" else """{"Primary":"p$i"}"""
        val rating = if (i % 3 == 0) "null" else "${5 + i % 5}.${i % 10}"
        val overview = when (i % 5) { 0 -> "null"; 1 -> "\"${"Sehr lange Beschreibung ".repeat(200)}\""; else -> "\"Film Nummer $i\"" }
        val ticks = if (i % 6 == 0) "" else "\"RunTimeTicks\":${(90 + i) * 60L * 10_000_000L},"
        val pos = if (i == 1) 1_200L * 10_000_000L else 0L
        return """{"Id":"m$i","Name":"Jelly-Film $i","Type":"Movie","ProductionYear":${2000 + i % 24},"CommunityRating":$rating,"Overview":$overview,$ticks
            "Genres":["Action","Drama"],"ImageTags":$img,"BackdropImageTags":${if (i % 2 == 0) "[\"b\"]" else "[]"},
            "UserData":{"PlaybackPositionTicks":$pos,"Played":${i == 3},"IsFavorite":${i % 7 == 0}},
            "People":[{"Name":"Schauspieler A","Type":"Actor"},{"Name":"Regie B","Type":"Director"}]}"""
    }

    private fun series(i: Int) = """{"Id":"s$i","Name":"Jelly-Serie $i","Type":"Series","ProductionYear":2019,"Overview":"Serie $i","ImageTags":{"Primary":"x"},
        "BackdropImageTags":["b"],"ChildCount":2,"UserData":{"Played":false,"IsFavorite":${i == 1}}}"""

    private fun episode(n: Int, season: Int) = """{"Id":"e${season}_$n","Name":"Folge $n","Type":"Episode","SeriesId":"s1","SeriesName":"Jelly-Serie 1",
        "SeasonId":"sea$season","IndexNumber":${if (n == 3) "null" else n},"ParentIndexNumber":$season,"RunTimeTicks":${45 * 60L * 10_000_000L},
        "Overview":${if (n % 2 == 0) "null" else "\"Inhalt $n\""},"ImageTags":${if (n == 2) "{}" else "{\"Primary\":\"e\"}"},"SeriesPrimaryImageTag":"s",
        "UserData":{"Played":${season == 1 && n == 1},"PlaybackPositionTicks":${if (season == 1 && n == 2) 600L * 10_000_000L else 0}}}"""

    private fun dispatch(r: RecordedRequest): MockResponse {
        val path = r.path ?: "/"
        requests += "${r.method} ${path.substringBefore('?')}"
        val u = r.requestUrl!!
        val p = path.substringBefore('?')
        return when {
            p == "/System/Info/Public" -> json("""{"ServerName":"Test-Jellyfin","Version":"10.9.11","Id":"srv"}""")
            p == "/Users/AuthenticateByName" -> {
                val body = r.body.readUtf8()
                if ("\"Pw\":\"falsch\"" in body) MockResponse().setResponseCode(401)
                else json("""{"User":{"Id":"u1","Name":"Tester"},"AccessToken":"tok123","ServerId":"srv"}""")
            }
            p == "/UserViews" -> json("""{"Items":[{"Id":"lib1","Name":"Filme","Type":"CollectionFolder","CollectionType":"movies","ImageTags":{"Primary":"t"}},
                {"Id":"lib2","Name":"Serien","Type":"CollectionFolder","CollectionType":"tvshows"},{"Id":"lib3","Name":"Musik","Type":"CollectionFolder","CollectionType":"music"}]}""")
            p == "/Users/u1/Items/Latest" -> json((1..12).joinToString(",", "[", "]") { movie(it) })
            p == "/Users/u1/Items/Resume" -> json("""{"Items":[${movie(1)},${episode(2, 1)}],"TotalRecordCount":2}""")
            p == "/Shows/NextUp" -> json("""{"Items":[${episode(2, 1)}]}""")
            p == "/Users/u1/Items" -> {
                val types = u.queryParameter("IncludeItemTypes") ?: ""
                val start = u.queryParameter("StartIndex")?.toIntOrNull() ?: 0
                val limit = u.queryParameter("Limit")?.toIntOrNull() ?: 40
                val total = if (u.queryParameter("ParentId") != null) 150 else 40
                val list = (start until minOf(total, start + limit)).map { i -> if (types.startsWith("Series")) series(i + 1) else movie(i + 1) }
                json("""{"Items":[${list.joinToString(",")}],"TotalRecordCount":$total}""")
            }
            p.startsWith("/Users/u1/Items/") -> {
                val id = p.removePrefix("/Users/u1/Items/")
                when {
                    id.startsWith("m") -> json(movie(id.removePrefix("m").toIntOrNull() ?: 1))
                    id.startsWith("s") -> json(series(id.removePrefix("s").toIntOrNull() ?: 1))
                    id.startsWith("e") -> { val (se, n) = id.removePrefix("e").split('_').map { it.toInt() }; json(episode(n, se)) }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            p.startsWith("/Users/u1/PlayedItems/") || p.startsWith("/Users/u1/FavoriteItems/") -> json("{}")
            p == "/Shows/s1/Seasons" -> json("""{"Items":[{"Id":"sea1","Name":"Staffel 1","Type":"Season","IndexNumber":1,"UserData":{"Played":false}},
                {"Id":"sea2","Name":"Staffel 2","Type":"Season","IndexNumber":2}]}""")
            p == "/Shows/s1/Episodes" -> {
                val season = u.queryParameter("SeasonId")?.removePrefix("sea")?.toIntOrNull()
                val eps = (if (season == null) listOf(1, 2) else listOf(season)).flatMap { se -> (1..6).map { n -> episode(n, se) } }
                json("""{"Items":[${eps.joinToString(",")}]}""")
            }
            p.endsWith("/Similar") -> json("""{"Items":[${(20..26).joinToString(",") { movie(it) }}]}""")
            p.endsWith("/PlaybackInfo") -> {
                val id = p.removePrefix("/Items/").substringBefore('/')
                json("""{"MediaSources":[{"Id":"ms_$id","Container":"ts","SupportsDirectPlay":true,"SupportsDirectStream":true,
                    "TranscodingUrl":"/videos/$id/master.m3u8?MediaSourceId=ms_$id&api_key=tok123",
                    "MediaStreams":[{"Type":"Video","Codec":"h264"},{"Type":"Subtitle","Index":3,"Language":"ger","DisplayTitle":"Deutsch","IsExternal":true,
                    "DeliveryMethod":"External","DeliveryUrl":"/Videos/$id/ms_$id/Subtitles/3/0/Stream.srt"}]}],"PlaySessionId":"ps1"}""")
            }
            p.startsWith("/Sessions/") -> MockResponse().setResponseCode(204)
            p.contains("/Images/") -> MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg))
            p.endsWith(".srt") -> MockResponse().setHeader("Content-Type", "text/plain").setBody("1\n00:00:01,000 --> 00:00:04,000\nHallo\n")
            p.endsWith("master.m3u8") -> MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg0.ts\n#EXT-X-ENDLIST\n")
            else -> MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(ByteArray(188 * 400) { if (it % 188 == 0) 0x47 else 0 }))
        }
    }

    private fun key(step: String, vararg codes: Int, pauseMs: Long = 300) {
        android.util.Log.i("UiSmoke", "Jellyfin: $step")
        for (c in codes) {
            val t = android.os.SystemClock.uptimeMillis()
            instr.uiAutomation.injectInputEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, c, 0), true)
            instr.uiAutomation.injectInputEvent(KeyEvent(t, t + 30, KeyEvent.ACTION_UP, c, 0), true)
            Thread.sleep(pauseMs)
        }
        responsive(step)
    }

    private fun responsive(step: String) {
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { latch.countDown() }
        if (!latch.await(3, TimeUnit.SECONDS)) fail("App hängt: $step")
        StabilityGuard.lastCrash(context)?.let { fail("Absturz nach „$step“:\n$it") }
        StabilityGuard.lastFreeze(context)?.let { fail("Hänger nach „$step“:\n$it") }
    }

    private fun open(route: String): ActivityScenario<MainActivity> {
        android.util.Log.i("UiSmoke", "Jellyfin: öffne $route")
        val sc = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Thread.sleep(4500)
        responsive("öffne $route")
        return sc
    }

    private fun shot(name: String) {
        runCatching { instr.uiAutomation.executeShellCommand("mkdir -p /data/local/tmp/shots").close() }
        runCatching { instr.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/shots/jf_$name.png").close() }
        Thread.sleep(400)
    }

    private val D = KeyEvent.KEYCODE_DPAD_DOWN
    private val U = KeyEvent.KEYCODE_DPAD_UP
    private val R = KeyEvent.KEYCODE_DPAD_RIGHT
    private val L = KeyEvent.KEYCODE_DPAD_LEFT
    private val OK = KeyEvent.KEYCODE_DPAD_CENTER
    private val BACK = KeyEvent.KEYCODE_BACK

    @Test fun jellyfinEndToEnd() {
        StabilityGuard.clear(context)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = this@ZJellyfinTest.dispatch(request) }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        try {
            runBlocking {
                hooks.settings().set(Keys.INTRO, false)
                hooks.settings().set(Keys.INTRO_PLAYED, true)
                hooks.settings().set(Keys.LAST_SYNC, System.currentTimeMillis())
                hooks.settings().set(Keys.LAST_EPG_SYNC, System.currentTimeMillis())
                hooks.jellyfin().logout()
            }
            // Login-Maske ohne Sitzung
            open(Routes.JELLYFIN).use { shot("anmeldung"); key("Login-Maske bedienen", D, D, U, pauseMs = 300) }
            // Falsches Passwort → klare Fehlermeldung, kein Absturz
            val wrong = runCatching { runBlocking { hooks.jellyfin().login(base, "tester", "falsch") } }
            assertTrue("Falsches Passwort muss abgelehnt werden", wrong.isFailure)
            assertEquals("Benutzername oder Passwort falsch", wrong.exceptionOrNull()?.message)
            // Richtige Anmeldung (Adresse ohne Schema wie ein Nutzer sie tippt)
            val session = runBlocking { hooks.jellyfin().login(base.removePrefix("http://"), "tester", "geheim") }
            assertEquals("tok123", session.token)
            assertEquals("Test-Jellyfin", session.serverName)

            open(Routes.JELLYFIN).use {
                Thread.sleep(3000); shot("uebersicht")
                key("Übersicht scrollen", D, D, R, R, D, D, R, D, D, D, pauseMs = 300); shot("uebersicht_reihen")
                key("Übersicht zurück hoch", *IntArray(10) { U }, pauseMs = 100)
            }
            open(Routes.jfLibrary("lib1", "Filme")).use {
                Thread.sleep(2500); shot("bibliothek")
                key("Bibliothek scrollen (Nachladen)", D, D, *IntArray(30) { D }, pauseMs = 120)
                Thread.sleep(1500); responsive("Bibliothek nach Nachladen")
            }
            assertTrue("Bibliothek lädt seitenweise nach", requests.count { it == "GET /Users/u1/Items" } >= 3)

            open(Routes.jfMovie("m1")).use {
                Thread.sleep(2500); shot("film")
                // Reihenfolge: Fortsetzen · Von vorne · Trailer · Favorit · Gesehen
                key("Favorit + gesehen", R, R, R, OK, R, OK, pauseMs = 400)
                Thread.sleep(1200); shot("film_nach_gesehen") // Fokus springt automatisch zurück auf „Abspielen“
                key("Film abspielen", OK); Thread.sleep(8000); responsive("Jellyfin-Film im Player"); shot("film_player")
                key("Player Overlay + Spulen", OK, BACK, R, R, L, pauseMs = 500); Thread.sleep(2000)
                key("Player schließen", BACK, BACK); Thread.sleep(2500)
            }
            android.util.Log.i("UiSmoke", "Jellyfin-Anfragen nach Film: " + requests.distinct().joinToString(" | "))
            assertTrue("PlaybackInfo abgefragt", requests.any { it.endsWith("/Items/m1/PlaybackInfo") })
            assertTrue("Wiedergabestart an Jellyfin gemeldet", requests.contains("POST /Sessions/Playing"))
            assertTrue("Wiedergabe-Ende an Jellyfin gemeldet", requests.contains("POST /Sessions/Playing/Stopped"))
            assertTrue("Favorit an Jellyfin gemeldet", requests.any { it.startsWith("POST /Users/u1/FavoriteItems/") || it.startsWith("DELETE /Users/u1/FavoriteItems/") })

            open(Routes.jfSeries("s1")).use {
                Thread.sleep(3000); shot("serie")
                key("Staffel wechseln", D, R, OK, pauseMs = 500); Thread.sleep(1500); shot("serie_staffel2")
                key("Staffel 1", L, OK, D, pauseMs = 500); Thread.sleep(1500)
                key("Folge abspielen", OK); Thread.sleep(8000); responsive("Jellyfin-Folge im Player")
                key("Player schließen", BACK, BACK); Thread.sleep(2500)
            }
            // Direkt eine Folge aus „Weiterschauen“ starten (wie von der Übersicht)
            ActivityScenario.launch<de.ostlertekker.ultraiptv.feature.player.PlayerActivity>(
                Intent(context, de.ostlertekker.ultraiptv.feature.player.PlayerActivity::class.java)
                    .putExtra(de.ostlertekker.ultraiptv.feature.player.PlayerActivity.EXTRA_KIND, ContentKind.EPISODE.name)
                    .putExtra(de.ostlertekker.ultraiptv.feature.player.PlayerActivity.EXTRA_ID, "jf:e1_2")
                    .putExtra(de.ostlertekker.ultraiptv.feature.player.PlayerActivity.EXTRA_START, 600_000L)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
                Thread.sleep(8000); responsive("Folge direkt"); shot("folge_player")
            }
            assertTrue("Folge an Jellyfin gemeldet", requests.any { it == "POST /Items/e1_2/PlaybackInfo" })
            android.util.Log.i("UiSmoke", "Jellyfin-Anfragen: " + requests.distinct().joinToString(" | "))
        } finally {
            server.shutdown()
        }
    }
}
