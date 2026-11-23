package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.sysupdate.HashVerifier
import de.ostlertekker.ultraiptv.core.sysupdate.PlayerConfig
import de.ostlertekker.ultraiptv.core.sysupdate.RemotePlayerConfig
import de.ostlertekker.ultraiptv.core.sysupdate.SignatureVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** System-Updates: Signaturprüfung des echten Manifests, Prüfsummen, Kompatibilitätsprüfung der Player-Konfiguration. */
class SystemUpdateTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("sysupdate/$name")!!.readBytes()
    private val b64: (String) -> ByteArray = { java.util.Base64.getMimeDecoder().decode(it) }

    @Test fun echtesManifestIstGueltigSigniert() {
        val m = res("system-manifest.json"); val sig = res("system-manifest.json.sig").decodeToString()
        assertTrue(SignatureVerifier.verifyWith(m, sig, b64))
    }

    @Test fun veraendertesManifestWirdAbgelehnt() {
        val m = res("system-manifest.json").clone(); m[m.size / 2] = (m[m.size / 2] + 1).toByte()
        assertFalse(SignatureVerifier.verifyWith(m, res("system-manifest.json.sig").decodeToString(), b64))
    }

    @Test fun falscheSignaturWirdAbgelehnt() {
        assertFalse(SignatureVerifier.verifyWith(res("system-manifest.json"), "AAAA", b64))
    }

    @Test fun sha256() {
        val f = java.io.File.createTempFile("hash", ".bin").apply { writeText("abc"); deleteOnExit() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", HashVerifier.sha256(f))
        assertTrue(HashVerifier.matches(f, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
    }

    @Test fun eingebauteWerteEntsprechenVersion1() {
        // Die erste Fernkonfiguration darf nichts am bisherigen Verhalten ändern
        val v1 = RemotePlayerConfig.parse(java.io.File("../system-updates/player-config.json").readText())
        assertEquals(1, v1.version)
        assertEquals(PlayerConfig().copy(version = 1), v1)
        assertNull(v1.validate())
    }

    @Test fun unsinnigeWerteWerdenAbgelehnt() {
        assertNotNull(PlayerConfig(connectTimeoutMs = 100).validate())
        assertNotNull(PlayerConfig(liveStartDefaultMs = 9_000).validate())
        assertNotNull(PlayerConfig(userAgent = "a\nb").validate())
        assertNull(PlayerConfig().validate())
    }
}
