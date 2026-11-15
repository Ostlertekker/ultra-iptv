package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.motion.EntranceAnim
import de.ostlertekker.ultraiptv.core.motion.ExitAnim
import de.ostlertekker.ultraiptv.core.motion.FocusEffect
import de.ostlertekker.ultraiptv.core.motion.MotionConfig
import de.ostlertekker.ultraiptv.core.motion.MotionMath
import de.ostlertekker.ultraiptv.core.motion.MotionPresets
import de.ostlertekker.ultraiptv.core.motion.MotionProfile
import de.ostlertekker.ultraiptv.core.motion.MotionSpec
import de.ostlertekker.ultraiptv.core.motion.PageTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionEngineTest {
    @Test fun catalogIsComplete() {
        assertEquals(14, EntranceAnim.entries.size)
        assertEquals(8, FocusEffect.entries.size)
        assertEquals(7, PageTransition.entries.size)
        val ids = EntranceAnim.entries.map { it.id } + FocusEffect.entries.map { it.id } + PageTransition.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun configJsonRoundTrip() {
        val c = MotionConfig(entrance = null, focus = FocusEffect.TRAIL, pageEnter = null, pageExit = ExitAnim.GRAVITY_FALL,
            zapEffect = PageTransition.WARP_SPEED, playerEnter = null, speed = 1.5f, intensity = 0.7f, particles = false,
            disabled = setOf(EntranceAnim.BLACK_HOLE.id), reduced = true)
        assertEquals(c, MotionConfig.fromJson(c.toJson().toString()))
        assertEquals(MotionConfig(), MotionConfig.fromJson("kaputt"))
    }

    @Test fun profilesRoundTripWithoutPresets() {
        val list = MotionPresets.all + MotionProfile("Mein Profil", MotionConfig(speed = 2f))
        val back = MotionPresets.profilesFromJson(MotionPresets.profilesToJson(list))
        assertEquals(1, back.size)
        assertEquals("Mein Profil", back[0].name)
        assertEquals(2f, back[0].config.speed, 0.001f)
    }

    @Test fun disabledAnimationsAreNotUsed() {
        val c = MotionConfig(entrance = EntranceAnim.EXPLOSION, focus = FocusEffect.TILT_3D,
            disabled = setOf(EntranceAnim.EXPLOSION.id, FocusEffect.TILT_3D.id))
        val s = MotionSpec.resolve(c, weakHardware = false, systemReduceMotion = false)
        assertNull(s.entrance)
        assertNull(s.focus)
        assertFalse(EntranceAnim.EXPLOSION in s.enabledEntrances)
    }

    @Test fun randomPicksOnlyEnabled() {
        val c = MotionConfig(entrance = null, disabled = EntranceAnim.entries.filter { it != EntranceAnim.PORTAL }.map { it.id }.toSet())
        val s = MotionSpec.resolve(c, false, false)
        repeat(20) { assertEquals(EntranceAnim.PORTAL, s.entranceFor(it)) }
    }

    @Test fun reducedAndWeakHardware() {
        val reduced = MotionSpec.resolve(MotionConfig(reduced = true), false, false)
        assertNull(reduced.entrance); assertNull(reduced.zapEffect); assertFalse(reduced.particles)
        val sys = MotionSpec.resolve(MotionConfig(), false, systemReduceMotion = true)
        assertNull(sys.entrance)
        val weak = MotionSpec.resolve(MotionConfig(speed = 1f), weakHardware = true, systemReduceMotion = false)
        assertTrue(weak.lite)
        assertEquals(4, weak.particleCount(12))
        assertTrue(weak.durationMs(800) < 800)
        assertFalse(MotionSpec.resolve(MotionConfig(autoSimplify = false), true, false).lite)
        assertFalse(MotionSpec.resolve(MotionConfig(enabled = false), false, false).enabled)
    }

    @Test fun physicsCurves() {
        assertEquals(0f, MotionMath.gravityBounce(0f), 1e-4f)
        assertEquals(1f, MotionMath.gravityBounce(1f), 1e-4f)
        // Rückprall: nach dem ersten Aufschlag steigt das Element wieder (Wert < 1)
        val samples = (0..200).map { MotionMath.gravityBounce(it / 200f) }
        val firstImpact = samples.indexOfFirst { it > 0.98f }
        assertTrue(firstImpact in 1..150)
        assertTrue(samples.drop(firstImpact).any { it < 0.9f })   // springt wieder hoch
        // Elastisch: überschwingt über 1 und endet bei 1
        assertTrue((0..100).any { MotionMath.elastic(it / 100f) > 1.05f })
        assertEquals(1f, MotionMath.elastic(1f), 1e-4f)
        assertEquals(1f, MotionMath.bounceOut(1f), 1e-3f)
        assertTrue(MotionMath.easeOutBack(0.6f) > 1f)
        assertNotNull(MotionMath.hash(1, 2))
    }
}
