package de.ostlertekker.ultraiptv

import android.content.Intent
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dagger.hilt.android.EntryPointAccessors
import de.ostlertekker.ultraiptv.core.data.Keys
import de.ostlertekker.ultraiptv.core.diagnostics.TestHooks
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.model.ProviderConfig
import de.ostlertekker.ultraiptv.core.model.ProviderType
import de.ostlertekker.ultraiptv.core.player.ZapMonitor
import de.ostlertekker.ultraiptv.feature.player.PlayerActivity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reproduzierbare Zapping-Messung: echte H.264/AAC-Teststreams (in der CI mit ffmpeg erzeugt, 720p25, 3 Mbit/s,
 * Keyframe-Abstand 1 s bzw. 4 s), ausgeliefert von einem lokalen Testserver unter drei Bedingungen:
 *  rt    – Server liefert in Echtzeit (wie viele IPTV-Server)
 *  burst – Server liefert zu Beginn schneller als Echtzeit (8×)
 *  slow  – Server antwortet erst nach 400 ms, dann Echtzeit
 * Jeder Abruf beginnt an einer anderen Stelle im Stream (wie beim Einstieg in eine laufende Sendung → Warten auf Keyframe).
 * Verglichen werden Original (Schnellstart aus) und optimierte Version (Schnellstart an), je mit Einzel-Wechseln und
 * schnellen Tastenfolgen. Ergebnis: Logcat „UiSmoke: ZAP …“ (CI: steps.txt) und /data/local/tmp/shots/zap_report.txt.
 */
@RunWith(AndroidJUnit4::class)
class ZZapBenchmarkTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val hooks by lazy { EntryPointAccessors.fromApplication(context.applicationContext, TestHooks::class.java) }
    private val report = StringBuilder()

    private fun log(s: String) { android.util.Log.i("UiSmoke", "ZAP $s"); report.appendLine(s) }

    @Test fun zapping() {
        val files = runCatching { instr.context.assets.list("zap")?.toList() }.getOrNull().orEmpty()
        assumeTrue("Keine Teststreams (werden in der CI mit ffmpeg erzeugt)", "gop1.ts" in files && "gop4.ts" in files)
        val media = mapOf("gop1" to instr.context.assets.open("zap/gop1.ts").readBytes(), "gop4" to instr.context.assets.open("zap/gop4.ts").readBytes())
        val seq = AtomicInteger(0)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                if (path.startsWith("/list.m3u")) return MockResponse().setBody(playlist(server.url("/").toString().trimEnd('/')))
                // /s/<bedingung>/<gop>/<n>.ts
                val parts = path.trim('/').split('/')
                if (parts.size < 4 || parts[0] != "s") return MockResponse().setResponseCode(404)
                val cond = parts[1]; val data = media[parts[2]] ?: return MockResponse().setResponseCode(404)
                // Einstieg mitten in den Stream: deterministische, aber wechselnde Startposition (an 188-Byte-TS-Paketen ausgerichtet)
                val frac = ((seq.incrementAndGet() * 0.381966) % 0.6)
                val off = ((data.size * frac).toLong() / 188 * 188).toInt()
                val body = Buffer().write(data, off, data.size - off)
                val perSec = data.size / 40L // Echtzeit-Datenrate (Dateien sind 40 s lang)
                val r = MockResponse().setHeader("Content-Type", "video/mp2t").setBody(body)
                return when (cond) {
                    "burst" -> r.throttleBody(perSec * 8 / 10, 100, TimeUnit.MILLISECONDS)
                    "slow" -> r.setHeadersDelay(400, TimeUnit.MILLISECONDS).throttleBody(perSec / 10, 100, TimeUnit.MILLISECONDS)
                    else -> r.throttleBody(perSec / 10, 100, TimeUnit.MILLISECONDS)
                }
            }
        }
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            hooks.credentials().save(ProviderConfig(ProviderType.M3U, "Zapping-Test", m3uUrl = "$base/list.m3u", epgUrl = ""))
            runBlocking {
                hooks.settings().set(Keys.INTRO_PLAYED, true)
                hooks.settings().set(Keys.INTRO, false)
                hooks.settings().set(Keys.UPDATE_CHECK, false)
                hooks.user().ensureDefaultProfile()
                check(hooks.sync().syncAll(includeEpg = false)) { "Sync der Zapping-Testquelle fehlgeschlagen" }
            }
            val first = runBlocking { hooks.content().channelsAllOnce(50) }.firstOrNull { it.name.startsWith("rt-gop1") }
                ?: error("Testsender fehlen")
            log("Gerät: ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · Teststreams 720p25 3 Mbit/s H.264+AAC")
            for (turbo in listOf(false, true)) {
                runBlocking { hooks.settings().set(Keys.ZAP_TURBO, turbo) }
                context.getSharedPreferences("zap_tuner", 0).edit().clear().commit()
                ZapMonitor.clear()
                val intent = Intent(context, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_KIND, ContentKind.LIVE.name)
                    .putExtra(PlayerActivity.EXTRA_ID, first.id)
                    .putExtra(PlayerActivity.EXTRA_CATEGORY, first.category)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                ActivityScenario.launch<PlayerActivity>(intent).use {
                    Thread.sleep(9_000) // erster Sender läuft an (nicht gewertet)
                    ZapMonitor.clear()
                    // Einzelne Wechsel: 2 Runden durch alle 12 Testsender
                    repeat(24) { device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_DOWN); Thread.sleep(6_500) }
                    val single = ZapMonitor.all()
                    // Schnelle Tastenfolgen: 4 Tasten im Abstand von 150 ms, gemessen wird der Zielsender ab der letzten Taste
                    repeat(4) {
                        repeat(4) { device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_DOWN); Thread.sleep(150) }
                        Thread.sleep(8_000)
                    }
                    val rapid = ZapMonitor.all().drop(single.size)
                    summarize(if (turbo) "OPTIMIERT (Schnellstart an)" else "ORIGINAL (Schnellstart aus)", single, rapid)
                }
            }
        } finally {
            runBlocking { hooks.settings().set(Keys.ZAP_TURBO, true) }
            server.shutdown()
            saveReport()
        }
    }

    private fun summarize(mode: String, single: List<ZapMonitor.Record>, rapid: List<ZapMonitor.Record>) {
        log("== $mode")
        fun line(name: String, l: List<ZapMonitor.Record>) {
            val t = ZapMonitor.pct(l.map { it.total }); val f = ZapMonitor.pct(l.map { it.frame }); val a = ZapMonitor.pct(l.map { it.audio })
            val d = ZapMonitor.pct(l.map { it.data }); val fm = ZapMonitor.pct(l.map { it.format }); val dec = ZapMonitor.pct(l.map { it.decoderInitMs })
            fun s(p: ZapMonitor.Pct?) = p?.let { "${it.median}/${it.p95}/${it.p99}" } ?: "–"
            log("  $name (n=${l.size}, Fehler ${l.count { it.errors > 0 }}): gesamt ${s(t)} · Bild ${s(f)} · Ton ${s(a)} · Daten ${s(d)} · Format ${s(fm)} · Decoder ${s(dec)} ms (Median/P95/P99) · Ø CPU ${l.map { it.cpuPct }.average().toInt()} % · Ø ${l.map { it.netKb }.average().toLong()} KB")
        }
        line("Einzelwechsel alle", single)
        for (c in listOf("rt", "burst", "slow")) for (g in listOf("gop1", "gop4"))
            line("Einzelwechsel $c-$g", single.filter { it.label.startsWith("$c-$g") })
        line("Schnelle Tastenfolge (Zielsender ab letzter Taste)", rapid)
        log("  Zielsender der Tastenfolgen: " + rapid.joinToString { it.label })
    }

    private fun playlist(base: String) = buildString {
        appendLine("#EXTM3U")
        for (c in listOf("rt", "burst", "slow")) for (g in listOf("gop1", "gop4")) for (n in 1..2)
            appendLine("#EXTINF:-1 tvg-id=\"z-$c-$g-$n\" group-title=\"Zapping\",$c-$g #$n").also { appendLine("$base/s/$c/$g/$n.ts") }
    }

    private fun saveReport() {
        val f = java.io.File(context.getExternalFilesDir(null), "zap_report.txt")
        runCatching { f.writeText(report.toString()) }
        runCatching { device.executeShellCommand("mkdir -p /data/local/tmp/shots") }
        runCatching { device.executeShellCommand("cp ${f.absolutePath} /data/local/tmp/shots/zap_report.txt") }
    }
}
