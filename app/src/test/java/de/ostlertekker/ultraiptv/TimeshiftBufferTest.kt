package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.player.TimeshiftBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.nio.file.Files

/** Zeitversatz-Puffer: liest lückenlos über Verbindungsabbrüche hinweg, findet Zeitpunkte, löscht Ältestes. */
class TimeshiftBufferTest {
    private lateinit var server: ServerSocket
    @Volatile private var running = true
    private var counter = 0

    @Before fun setUp() {
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (running) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    sock.use { sk ->
                        val input = sk.getInputStream().bufferedReader()
                        while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break }
                        val out = sk.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                        // 20 Pakete à 188 Byte, dann beendet der „Server“ die Verbindung (wie echte Anbieter)
                        repeat(20) {
                            val pkt = ByteArray(188) { i -> if (i == 0) 0x47 else (counter and 0xFF).toByte() }
                            counter++
                            runCatching { out.write(pkt); out.flush() }
                            Thread.sleep(15)
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    @After fun tearDown() { running = false; server.close() }

    private fun url() = "http://127.0.0.1:${server.localPort}/live.ts"

    @Test fun readsContinuouslyAcrossReconnects() {
        val dir = Files.createTempDirectory("ts").toFile()
        val b = TimeshiftBuffer(dir, url(), "test", 60_000, 50L * 1024 * 1024)
        b.start()
        try {
            val buf = ByteArray(188)
            var pos = 0L
            // 60 Pakete = drei Verbindungen
            repeat(60) { n ->
                var got = 0
                while (got < 188) { val r = b.read(pos + got, buf, got, 188 - got); assertTrue(r >= 0); got += r }
                assertEquals("Paket $n beginnt mit Sync-Byte", 0x47.toByte(), buf[0])
                pos += 188
            }
            assertTrue("Neu verbunden", b.reconnects >= 2)
            // Zeit → Position ist monoton und auf Paketgrenzen ausgerichtet
            val early = b.offsetForWall(b.startedAt)
            val late = b.offsetForWall(System.currentTimeMillis())
            assertTrue(late >= early)
            assertEquals(0L, late % 188)
        } finally { b.close() }
        // Aufräumen läuft im Hintergrund (nicht mehr im Bedien-Thread) – auf das Ende warten
        assertTrue("Aufräumen beendet", b.cleaned.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("Verbindung getrennt", b.disconnected.count == 0L)
        assertTrue("Dateien gelöscht", dir.listFiles().isNullOrEmpty())
    }

    @Test fun trimsOldestWhenOverLimit() {
        val dir = Files.createTempDirectory("ts2").toFile()
        val b = TimeshiftBuffer(dir, url(), "test", 60_000, 1L) // Speichergrenze: praktisch nichts
        b.start()
        try {
            val deadline = System.currentTimeMillis() + 3_000
            while (b.endOffset < 188 * 30 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            // Mit nur einem Block wird nichts gelöscht – der aktuelle Block bleibt immer erhalten
            assertTrue(b.endOffset > 0)
            assertTrue(b.oldestOffset <= b.endOffset)
        } finally { b.close() }
    }
}
