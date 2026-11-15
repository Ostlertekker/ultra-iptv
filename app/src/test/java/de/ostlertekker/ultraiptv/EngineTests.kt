package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.model.OutroMode
import de.ostlertekker.ultraiptv.core.model.QualityMode
import de.ostlertekker.ultraiptv.core.player.DisplayModeInfo
import de.ostlertekker.ultraiptv.core.player.UltraQualityEngine
import de.ostlertekker.ultraiptv.core.security.InputValidator
import de.ostlertekker.ultraiptv.core.security.PinHasher
import de.ostlertekker.ultraiptv.feature.smartskip.MarkerSource
import de.ostlertekker.ultraiptv.feature.smartskip.SegmentType
import de.ostlertekker.ultraiptv.feature.smartskip.SkipAction
import de.ostlertekker.ultraiptv.feature.smartskip.SkipPolicy
import de.ostlertekker.ultraiptv.feature.smartskip.SkipSegment
import de.ostlertekker.ultraiptv.feature.smartskip.SmartSkipEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSkipEngineTest {
    private val dur = 45 * 60_000L
    private val intro = SkipSegment(SegmentType.INTRO, 60_000, 120_000, MarkerSource.USER, 1f)

    @Test fun autoSkipsConfidentIntro() {
        val e = SmartSkipEngine()
        val a = e.evaluate(listOf(intro), 70_000, dur, SkipPolicy(), hasNext = true)
        assertTrue(a is SkipAction.AutoSkip)
        assertEquals(120_000, (a as SkipAction.AutoSkip).targetMs)
    }

    @Test fun onlyOffersLowConfidence() {
        val e = SmartSkipEngine()
        val a = e.evaluate(listOf(intro.copy(source = MarkerSource.TEMPLATE, confidence = 0.6f)), 70_000, dur, SkipPolicy(), true)
        assertTrue(a is SkipAction.Offer)
    }

    @Test fun manualOnlyNeverAutoSkips() {
        val a = SmartSkipEngine().evaluate(listOf(intro), 70_000, dur, SkipPolicy(manualOnly = true), true)
        assertTrue(a is SkipAction.Offer)
    }

    @Test fun userOverrideAfterSeekBack() {
        val e = SmartSkipEngine()
        e.markHandled(intro)
        assertTrue(e.evaluate(listOf(intro), 70_000, dur, SkipPolicy(), true) is SkipAction.Offer)
    }

    @Test fun nothingOutsideSegments() {
        assertEquals(SkipAction.None, SmartSkipEngine().evaluate(listOf(intro), 200_000, dur, SkipPolicy(), true))
    }

    @Test fun rejectsInvalidTimestamps() {
        assertFalse(SmartSkipEngine.isValid(intro.copy(endMs = dur + 60_000), dur))          // länger als Episode
        assertFalse(SmartSkipEngine.isValid(intro.copy(startMs = 0, endMs = 20 * 60_000), dur)) // Intro > 35 %
        assertFalse(SmartSkipEngine.isValid(intro.copy(endMs = 61_000), dur))                 // < 3 s
        assertFalse(SmartSkipEngine.isValid(SkipSegment(SegmentType.OUTRO, 60_000, dur, MarkerSource.USER, 1f), dur)) // Outro zu früh
        assertTrue(SmartSkipEngine.isValid(intro, dur))
        assertEquals(SkipAction.None, SmartSkipEngine().evaluate(listOf(intro.copy(endMs = dur * 2)), 70_000, dur, SkipPolicy(), true))
    }

    @Test fun nextEpisodeAtOutro() {
        val outro = SkipSegment(SegmentType.OUTRO, dur - 90_000, dur, MarkerSource.PROVIDER, 0.9f)
        val e = SmartSkipEngine()
        assertEquals(SkipAction.NextEpisode, e.evaluate(listOf(outro), dur - 60_000, dur, SkipPolicy(), hasNext = true))
        // Letzte Folge, kein Auto-Abspann: nur manuelles Angebot
        assertTrue(e.evaluate(listOf(outro), dur - 60_000, dur, SkipPolicy(autoNext = false, autoOutro = false), hasNext = false) is SkipAction.Offer)
    }

    @Test fun nextEpisodeFallbackNearEnd() {
        val e = SmartSkipEngine()
        assertEquals(SkipAction.NextEpisode, e.evaluate(emptyList(), dur - 10_000, dur, SkipPolicy(countdownSec = 10), true))
        assertEquals(SkipAction.None, e.evaluate(emptyList(), dur - 60_000, dur, SkipPolicy(countdownSec = 10), true))
        assertEquals(SkipAction.None, e.evaluate(emptyList(), dur - 10_000, dur, SkipPolicy(countdownSec = 10), false))
    }

    @Test fun creditsOnlyKeepsOutroManual() {
        val outro = SkipSegment(SegmentType.OUTRO, dur - 300_000, dur - 120_000, MarkerSource.USER, 1f)
        val a = SmartSkipEngine().evaluate(listOf(outro), dur - 200_000, dur, SkipPolicy(autoOutro = true, outroMode = OutroMode.CREDITS_ONLY, autoNext = false), false)
        assertTrue(a is SkipAction.Offer)
        val b = SmartSkipEngine().evaluate(listOf(outro), dur - 200_000, dur, SkipPolicy(autoOutro = true, outroMode = OutroMode.FULL, autoNext = false), false)
        assertTrue(b is SkipAction.AutoSkip)
    }

    @Test fun disabledSkipsNothing() {
        assertEquals(SkipAction.None, SmartSkipEngine().evaluate(listOf(intro), 70_000, dur, SkipPolicy(enabled = false, autoNext = false), true))
    }

    @Test fun normalizePrefersHighestConfidence() {
        val list = SmartSkipEngine.normalize(listOf(intro.copy(confidence = 0.6f, startMs = 50_000), intro), dur)
        assertEquals(1, list.size)
        assertEquals(1f, list[0].confidence)
    }
}

class UltraQualityEngineTest {
    private val modes = listOf(
        DisplayModeInfo(1, 3840, 2160, 60f), DisplayModeInfo(2, 3840, 2160, 50f), DisplayModeInfo(3, 3840, 2160, 23.976f),
        DisplayModeInfo(4, 3840, 2160, 24f), DisplayModeInfo(5, 1920, 1080, 60f), DisplayModeInfo(6, 3840, 2160, 59.94f),
    )
    private val current = modes[0]

    @Test fun picksMatchingRefreshRate() {
        assertEquals(2, UltraQualityEngine.pickDisplayMode(modes, current, 25f)?.id)
        assertEquals(3, UltraQualityEngine.pickDisplayMode(modes, current, 23.976f)?.id)
        assertEquals(4, UltraQualityEngine.pickDisplayMode(modes, current, 24f)?.id)
        assertEquals(6, UltraQualityEngine.pickDisplayMode(modes, current, 29.97f)?.id)
        assertEquals(1, UltraQualityEngine.pickDisplayMode(modes, current, 60f)?.id)
    }

    @Test fun neverFakes120() {
        assertNull(UltraQualityEngine.pickDisplayMode(modes, current, 120f))
        assertTrue(UltraQualityEngine.highFrameRateStatus(120f, true, modes).contains("nicht möglich"))
        assertTrue(UltraQualityEngine.highFrameRateStatus(120f, true, modes + DisplayModeInfo(9, 3840, 2160, 120f)).contains("unterstützen"))
        assertTrue(UltraQualityEngine.highFrameRateStatus(120f, false, modes).contains("Decoder"))
    }

    @Test fun constraintsRespectDeviceLimits() {
        val auto = UltraQualityEngine.constraints(QualityMode.AUTO, 1080, 2160, 1080)
        assertEquals(1080, auto.maxHeight)
        val best = UltraQualityEngine.constraints(QualityMode.BEST_AVAILABLE, 1080, 2160, 2160)
        assertEquals(2160, best.maxHeight); assertTrue(best.forceHighest)
        val saver = UltraQualityEngine.constraints(QualityMode.DATA_SAVER, 1080, 2160, 2160)
        assertEquals(720, saver.maxHeight); assertEquals(2_500_000, saver.maxBitrate)
        val manual = UltraQualityEngine.constraints(QualityMode.MANUAL, 2160, 1080, 2160)
        assertEquals(1080, manual.maxHeight)
    }

    @Test fun labels() {
        assertTrue(UltraQualityEngine.resolutionLabel(3840, 2160).contains("4K"))
        assertTrue(UltraQualityEngine.resolutionLabel(1920, 1080).contains("Full HD"))
        assertEquals("unbekannt", UltraQualityEngine.resolutionLabel(0, 0))
    }
}

class SecurityTest {
    @Test fun pinHashing() {
        val salt = PinHasher.newSalt()
        val h = PinHasher.hash("1234", salt)
        assertNotEquals("1234", h)
        assertTrue(PinHasher.verify("1234", salt, h))
        assertFalse(PinHasher.verify("4321", salt, h))
        assertNotEquals(h, PinHasher.hash("1234", PinHasher.newSalt()))
    }

    @Test fun validation() {
        assertNull(InputValidator.url("http://example.com:8080"))
        assertNull(InputValidator.url("example.com"))
        assertNotNull(InputValidator.url("ftp://example.com"))
        assertNotNull(InputValidator.url("http://exa mple.com"))
        assertNotNull(InputValidator.url(""))
        assertNull(InputValidator.pin("0815"))
        assertNotNull(InputValidator.pin("12a4"))
        assertNotNull(InputValidator.pin("123"))
        assertNotNull(InputValidator.credential("a\u0000b", "Passwort"))
        assertNotNull(InputValidator.profileName(""))
    }
}
