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
import de.ostlertekker.ultraiptv.core.data.SortOrder
import de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard
import de.ostlertekker.ultraiptv.core.diagnostics.TestHooks
import de.ostlertekker.ultraiptv.core.model.ProviderConfig
import de.ostlertekker.ultraiptv.core.model.ProviderType
import de.ostlertekker.ultraiptv.ui.Routes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Xtream-Quelle wie bei echten Anbietern (inkl. typischer Daten-Eigenheiten): Filme und Serien öffnen,
 * Details nachladen, Folgen anzeigen, abspielen. Läuft gegen den R8-optimierten QA-Build.
 */
@RunWith(AndroidJUnit4::class)
class ZXtreamDetailTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val hooks by lazy { EntryPointAccessors.fromApplication(context.applicationContext, TestHooks::class.java) }

    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val poster: ByteArray by lazy {
        val bmp = android.graphics.Bitmap.createBitmap(2000, 3000, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.rgb(90, 30, 30))
        java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)

    private fun api(r: RecordedRequest, base: String): MockResponse {
        val u = r.requestUrl!!
        return when (u.queryParameter("action")) {
            null -> json("""{"user_info":{"auth":1,"status":"Active","exp_date":"1893456000","max_connections":"1","active_cons":"0","is_trial":"0","created_at":"1600000000"},"server_info":{"url":"x","port":"80","timezone":"Europe/Berlin","timestamp_now":1700000000}}""")
            "get_live_categories" -> json("""[{"category_id":"1","category_name":"DE | Vollprogramm","parent_id":0}]""")
            "get_live_streams" -> json((1..30).joinToString(",", "[", "]") { i ->
                """{"num":$i,"name":"DE: Kanal $i HD","stream_type":"live","stream_id":${100 + i},"stream_icon":"$base/img/l$i.png","epg_channel_id":"k$i","added":"1700000000","category_id":"1","tv_archive":${i % 2},"tv_archive_duration":"${if (i % 2 == 1) 3 else 0}"}"""
            })
            "get_vod_categories" -> json("""[{"category_id":"10","category_name":"Action"},{"category_id":"11","category_name":"Drama"},{"category_id":"12","category_name":null}]""")
            "get_vod_streams" -> json((1..400).joinToString(",", "[", "]") { i ->
                val rating = when (i % 5) { 0 -> "\"7.4\""; 1 -> "8"; 2 -> "\"\""; 3 -> "null"; else -> "\"N/A\"" }
                val cat = when (i % 3) { 0 -> "\"10\""; 1 -> "\"11\""; else -> "null" }
                """{"num":$i,"name":"Film $i (20${10 + i % 15})","stream_type":"movie","stream_id":${5000 + i},"stream_icon":"$base/img/p$i.jpg","rating":$rating,"rating_5based":3.7,"added":"${1700000000 - i}","category_id":$cat,"container_extension":"mkv","custom_sid":null,"direct_source":""}"""
            })
            "get_series_categories" -> json("""[{"category_id":"20","category_name":"Serien DE"}]""")
            "get_series" -> json((1..120).joinToString(",", "[", "]") { i ->
                val backdrop = if (i % 2 == 0) "[\"$base/img/b$i.jpg\"]" else "\"$base/img/b$i.jpg\""
                """{"num":$i,"name":"Serie $i","series_id":${9000 + i},"cover":"$base/img/s$i.jpg","plot":"Handlung $i","cast":"A, B","director":"C","genre":"Drama, Krimi","releaseDate":"2020-01-01","last_modified":"${1700000000 - i}","rating":"${i % 10}","backdrop_path":$backdrop,"category_id":"20","episode_run_time":"45"}"""
            })
            "get_vod_info" -> {
                val id = (u.queryParameter("vod_id") ?: "0").toInt() - 5000
                when (id % 6) {
                    0 -> json("""{"info":[],"movie_data":{"stream_id":${5000 + id},"name":"Film $id","container_extension":"mkv"}}""")
                    1 -> MockResponse().setResponseCode(500).setBody("<html>error</html>")
                    2 -> json("""{"info":{"plot":null,"genre":null,"backdrop_path":[],"duration_secs":"abc","cast":null},"movie_data":{}}""")
                    3 -> json("""{"info":{"plot":"${"Sehr lange Beschreibung ".repeat(400)}","genre":"Action, Thriller","backdrop_path":["$base/img/bd$id.jpg","$base/img/bd2.jpg"],"duration_secs":7260,"duration":"02:01:00","cast":"${"Schauspieler, ".repeat(80)}","director":"D","rating":7.1,"tmdb_id":"123","youtube_trailer":"","releasedate":"2019-05-05"},"movie_data":{"stream_id":${5000 + id},"container_extension":"mkv"}}""")
                    4 -> json("""[]""")
                    else -> json("""{"info":{"plot":"Kurz","genre":"Komödie","backdrop_path":"$base/img/bd$id.jpg","duration_secs":5400,"cast":"X","director":"Y"},"movie_data":{"stream_id":${5000 + id}}}""")
                }
            }
            "get_series_info" -> {
                val id = (u.queryParameter("series_id") ?: "0").toInt() - 9000
                when (id % 5) {
                    0 -> json("""{"seasons":[],"info":[],"episodes":[]}""")
                    1 -> MockResponse().setResponseCode(404)
                    2 -> json("""{"seasons":[{"season_number":1}],"info":{"name":"Serie $id","plot":"P","backdrop_path":["$base/img/sb$id.jpg"]},"episodes":[[{"id":"${id}01","episode_num":1,"title":"Folge 1","container_extension":"mp4","info":[],"season":1}]]}""")
                    3 -> json("""{"seasons":[],"info":{"name":"Serie $id","plot":null},"episodes":{"1":[${(1..12).joinToString(",") { e -> """{"id":"$id$e","episode_num":"$e","title":"S01E$e","container_extension":"mkv","info":{"plot":"Folge $e","movie_image":"$base/img/e$e.jpg","duration_secs":"2400","duration":"00:40:00"},"season":"1"}""" }}],"2":[{"id":"${id}99","episode_num":1,"title":"S02E1","container_extension":"mkv","info":{"movie_image":"","duration_secs":null},"season":2}]}}""")
                    else -> json("""{"seasons":null,"info":{"name":"Serie $id"},"episodes":{"1":[{"id":"${id}1","episode_num":1,"title":null,"container_extension":null,"info":{"plot":"","duration_secs":"0"}}]}}""")
                }
            }
            else -> json("[]")
        }
    }

    private fun key(step: String, vararg codes: Int, pauseMs: Long = 250) {
        android.util.Log.i("UiSmoke", "Xtream: $step")
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
        android.util.Log.i("UiSmoke", "Xtream: öffne $route")
        val sc = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Thread.sleep(4000)
        responsive("öffne $route")
        return sc
    }

    private fun shot(name: String) {
        runCatching { instr.uiAutomation.executeShellCommand("mkdir -p /data/local/tmp/shots").close() }
        runCatching { instr.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/shots/xt_$name.png").close() }
    }

    private val D = KeyEvent.KEYCODE_DPAD_DOWN
    private val U = KeyEvent.KEYCODE_DPAD_UP
    private val R = KeyEvent.KEYCODE_DPAD_RIGHT
    private val L = KeyEvent.KEYCODE_DPAD_LEFT
    private val OK = KeyEvent.KEYCODE_DPAD_CENTER
    private val BACK = KeyEvent.KEYCODE_BACK

    @Test fun moviesAndSeriesFromXtream() {
        StabilityGuard.clear(context)
        val server = MockWebServer()
        lateinit var base: String
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                return when {
                    path.startsWith("/player_api.php") -> api(request, base)
                    path.startsWith("/img/") -> MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(poster))
                    else -> MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(ByteArray(188 * 300) { if (it % 188 == 0) 0x47 else 0 }))
                }
            }
        }
        server.start()
        base = server.url("/").toString().trimEnd('/')
        try {
            hooks.credentials().save(ProviderConfig(ProviderType.XTREAM, "Xtream-Test", server = base, username = "u", password = "p"))
            runBlocking {
                hooks.settings().set(Keys.INTRO, false)
                hooks.settings().set(Keys.INTRO_PLAYED, true)
                hooks.settings().set(Keys.LAST_SYNC, System.currentTimeMillis())
                hooks.settings().set(Keys.LAST_EPG_SYNC, System.currentTimeMillis())
                assertTrue("Xtream-Sync fehlgeschlagen", hooks.sync().syncAll(includeEpg = false))
            }
            val movies = runBlocking { hooks.content().movies(null, null, SortOrder.entries.first(), 30).first { it.isNotEmpty() } }
            val series = runBlocking { hooks.content().series(null, SortOrder.entries.first(), 20).first { it.isNotEmpty() } }
            assertTrue("Filme geladen", movies.size >= 12)

            // Jede Daten-Variante des Anbieters einmal öffnen (Details, abspielen, Merkliste, zurück)
            for (m in movies.take(12)) {
                open(Routes.movie(m.id)).use {
                    Thread.sleep(2500); responsive("Film ${m.id} Detail")
                    if (m === movies.first()) shot("film_detail")
                    if (m.id.filter { it.isDigit() }.toIntOrNull()?.let { (it - 5000) % 6 == 3 } == true) shot("film_detail_lange_beschreibung_${m.id}")
                    key("Film ${m.id} Merkliste", R, OK, L)
                    key("Film ${m.id} abspielen", OK); Thread.sleep(5000); responsive("Film ${m.id} Player")
                    key("Film ${m.id} Player zu", BACK, BACK); Thread.sleep(1500)
                }
            }
            for (s in series.take(10)) {
                open(Routes.seriesDetail(s.id)).use {
                    Thread.sleep(3500); responsive("Serie ${s.id} Detail")
                    if (s === series.first()) shot("serie_detail")
                    key("Serie ${s.id} Folgen", D, D, R, U, pauseMs = 350)
                    key("Serie ${s.id} abspielen", OK); Thread.sleep(5000); responsive("Serie ${s.id} Player")
                    key("Serie ${s.id} zurück", BACK, BACK); Thread.sleep(1500)
                }
            }
            // Wie ein Nutzer: über die Listen in Filme und Serien hinein
            for (route in listOf(Routes.MOVIES, Routes.SERIES)) {
                open(route).use {
                    key("$route betreten", R, R, D, D, pauseMs = 300)
                    key("$route auswählen", OK); Thread.sleep(4000); shot("${route}_auswahl")
                    key("$route im Detail", D, R, L, U, pauseMs = 300)
                    key("$route zurück", BACK); Thread.sleep(1200)
                    key("$route nächster", R, R, OK); Thread.sleep(4000)
                    key("$route zurück 2", BACK); Thread.sleep(1200)
                }
            }
        } finally {
            server.shutdown()
        }
    }
}
