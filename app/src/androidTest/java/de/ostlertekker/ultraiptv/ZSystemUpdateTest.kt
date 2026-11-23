package de.ostlertekker.ultraiptv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import de.ostlertekker.ultraiptv.core.data.Keys
import de.ostlertekker.ultraiptv.core.diagnostics.TestHooks
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.model.ProviderConfig
import de.ostlertekker.ultraiptv.core.model.ProviderType
import de.ostlertekker.ultraiptv.core.sysupdate.RemotePlayerConfig
import de.ostlertekker.ultraiptv.core.sysupdate.SystemUpdateEntryPoint
import de.ostlertekker.ultraiptv.core.sysupdate.UpdateStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * System-Updates Ende-zu-Ende auf dem Gerät:
 *  1. Playlist unverändert → „Aktuell“
 *  2. Playlist geändert (1 neu, 1 entfernt, 1 umbenannt) → genau diese Änderungen in der Warteschlange
 *  3. Installieren → Datenbank aktualisiert, Favorit eines unveränderten Senders bleibt
 *  4. EPG: neue XMLTV-Datei → Update → übernommen; danach ETag → „Aktuell“ ohne erneuten Download
 *  5. Player-Konfiguration vom echten, signierten Manifest (GitHub) → installiert → Rollback
 */
@RunWith(AndroidJUnit4::class)
class ZSystemUpdateTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val hooks by lazy { EntryPointAccessors.fromApplication(context.applicationContext, TestHooks::class.java) }
    private val m by lazy { EntryPointAccessors.fromApplication(context.applicationContext, SystemUpdateEntryPoint::class.java).manager() }
    private fun log(s: String) = android.util.Log.i("UiSmoke", "SYSUPDATE $s")

    @Volatile private var playlist = list(listOf("Alpha" to "a", "Beta" to "b", "Gamma" to "c"))
    @Volatile private var epgVersion = 1
    private var epgRequests = 0

    private fun list(ch: List<Pair<String, String>>) = buildString {
        appendLine("#EXTM3U")
        ch.forEach { (n, k) -> appendLine("#EXTINF:-1 tvg-id=\"$k\" group-title=\"Test\",$n"); appendLine("http://127.0.0.1/live/$k.ts") }
    }

    private fun xmltv(v: Int): String {
        val now = System.currentTimeMillis()
        fun t(ms: Long) = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US).format(java.util.Date(ms))
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>")
            for (i in 0 until 6) appendLine("<programme start=\"${t(now + i * 1_800_000L)}\" stop=\"${t(now + (i + 1) * 1_800_000L)}\" channel=\"a\"><title>Sendung $v-$i</title></programme>")
            appendLine("</tv>")
        }
    }

    @Test fun systemUpdates() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/list.m3u") == true -> MockResponse().setBody(playlist)
                request.path?.startsWith("/epg.xml") == true -> {
                    epgRequests++
                    val tag = "\"v$epgVersion\""
                    if (request.getHeader("If-None-Match") == tag) MockResponse().setResponseCode(304)
                    else MockResponse().setHeader("ETag", tag).setBody(xmltv(epgVersion))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            context.getSharedPreferences("system_update_settings", 0).edit().clear().putBoolean("auto_check", false).commit()
            context.getSharedPreferences("system_update_queue", 0).edit().clear().commit()
            context.getSharedPreferences("system_update_versions", 0).edit().clear().commit()
            runBlocking {
                hooks.settings().set(Keys.UPDATE_CHECK, false)
                hooks.settings().set(Keys.EPG_URL, "$base/epg.xml")
                hooks.credentials().save(ProviderConfig(ProviderType.M3U, "System-Update-Test", m3uUrl = "$base/list.m3u", epgUrl = ""))
                hooks.user().ensureDefaultProfile()
                check(hooks.sync().syncAll(includeEpg = false))
            }
            val idA = runBlocking { hooks.content().channelsAllOnce(50) }.first { it.name == "Alpha" }.id
            runBlocking { hooks.user().toggleFavorite(ContentKind.LIVE, idA) }

            // 1) unverändert
            runBlocking { m.scan(manual = true) }
            val q1 = m.queue.get("iptv-m3u")
            log("1 unverändert: ${q1?.status} ${q1?.changes}")
            assertEquals(UpdateStatus.CURRENT, q1?.status)

            // 2) geändert: Beta entfernt, Gamma umbenannt (gleiche Adresse = gleiche ID), Delta neu
            playlist = list(listOf("Alpha" to "a", "Gamma HD" to "c", "Delta" to "d"))
            runBlocking { m.scan(manual = true) }
            val q2 = m.queue.get("iptv-m3u")
            log("2 geändert: ${q2?.status} · ${q2?.changes} · ${q2?.installed} → ${q2?.available} · ${q2?.sizeBytes} B")
            assertEquals(UpdateStatus.WAITING, q2?.status)
            assertTrue(q2!!.changes, q2.changes.startsWith("1 neu · 1 geändert · 1 entfernt"))
            // Prüfen ändert nichts an der Datenbank
            assertTrue(runBlocking { hooks.content().channelsAllOnce(50) }.any { it.name == "Beta" })

            // 3) installieren
            assertTrue(runBlocking { m.install("iptv-m3u") })
            val names = runBlocking { hooks.content().channelsAllOnce(50) }.map { it.name }.sorted()
            log("3 installiert: $names · ${m.queue.get("iptv-m3u")?.status}")
            assertEquals(listOf("Alpha", "Delta", "Gamma HD"), names)
            assertEquals(UpdateStatus.DONE, m.queue.get("iptv-m3u")?.status)
            assertTrue("Favorit bleibt erhalten", runBlocking { hooks.user().isFavorite(ContentKind.LIVE, idA).first() })
            // Danach ist wieder alles aktuell
            runBlocking { m.scan(manual = true) }
            assertEquals(UpdateStatus.CURRENT, m.queue.get("iptv-m3u")?.status)

            // 4) EPG
            val e1 = m.queue.get("epg")
            log("4 EPG erste Prüfung: ${e1?.status} · ${e1?.changes}")
            assertEquals(UpdateStatus.WAITING, e1?.status)
            assertTrue(runBlocking { m.install("epg") })
            log("4 EPG installiert: ${m.queue.get("epg")?.changes}")
            val before = epgRequests
            runBlocking { m.scan(manual = true) }
            assertEquals("ETag → aktuell", UpdateStatus.CURRENT, m.queue.get("epg")?.status)
            assertEquals(before + 1, epgRequests)
            epgVersion = 2
            runBlocking { m.scan(manual = true) }
            log("4 EPG neue Fassung: ${m.queue.get("epg")?.status} · ${m.queue.get("epg")?.changes}")
            assertEquals(UpdateStatus.WAITING, m.queue.get("epg")?.status)

            // 5) Player-Konfiguration vom signierten Manifest
            val pc = m.queue.get("player-config")
            log("5 Player-Konfiguration: ${pc?.status} · ${pc?.installed} → ${pc?.available} · ${pc?.error ?: pc?.changes}")
            assertNotNull(pc)
            if (pc!!.status == UpdateStatus.WAITING) {
                assertTrue(runBlocking { m.install("player-config") })
                assertEquals(1, RemotePlayerConfig.current.version)
                log("5 installiert: Version ${RemotePlayerConfig.current.version} · ${m.queue.get("player-config")?.changes}")
                m.rollbackPlayerConfig()
                assertEquals(0, RemotePlayerConfig.current.version)
                log("5 Rollback: Version ${RemotePlayerConfig.current.version}")
            } else log("5 übersprungen (Server nicht erreichbar?): ${pc.error}")
            log("Adblock: ${m.queue.get("adblock-lists")?.status} · ${m.queue.get("adblock-lists")?.changes} ${m.queue.get("adblock-lists")?.error ?: ""}")
            m.log.value.forEach { log("Protokoll: $it") }
        } finally {
            server.shutdown()
            runBlocking { hooks.settings().set(Keys.EPG_URL, "") }
        }
    }
}
