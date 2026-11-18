package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.data.AppSettings
import de.ostlertekker.ultraiptv.core.design.ThemeEngine
import de.ostlertekker.ultraiptv.core.design.ThemeRegistry
import de.ostlertekker.ultraiptv.core.model.AnimationProfile
import de.ostlertekker.ultraiptv.core.model.DeviceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeEngineTest {
    @Test fun onlyBlackDesign() {
        // Es gibt nur noch ein Design mit komplett schwarzem Hintergrund – auch für alte gespeicherte Design-IDs
        for (id in listOf("premium", "galaxy", "gibt-es-nicht")) {
            val r = ThemeEngine.resolve(AppSettings(themeId = id), DeviceTier.PREMIUM)
            assertEquals(0f, r.palette.bgTop.red + r.palette.bgTop.green + r.palette.bgTop.blue, 0.001f)
            assertEquals(0f, r.palette.bgBottom.red + r.palette.bgBottom.green + r.palette.bgBottom.blue, 0.001f)
        }
        assertEquals("premium", ThemeRegistry.byId("gibt-es-nicht").id)
    }

    @Test fun designOverridesApply() {
        val r = ThemeEngine.resolve(AppSettings(cornerStyle = 1, focusZoom = 1), DeviceTier.PREMIUM)
        assertEquals(0f, r.spec.corner.value)
        assertEquals(1f, r.spec.focusScale)
    }

    @Test fun accentOverride() {
        val r = ThemeEngine.resolve(AppSettings(accentArgb = 0xFFFF0000L), DeviceTier.PREMIUM)
        assertEquals(1f, r.palette.primary.red, 0.01f)
        assertEquals(0f, r.palette.primary.green, 0.01f)
        assertEquals(0f, r.palette.primary.blue, 0.01f)
    }

    @Test fun deviceAdaptiveAnimation() {
        assertEquals(AnimationProfile.ECONOMY, ThemeEngine.resolve(AppSettings(), DeviceTier.ENTRY).anim.profile)
        assertEquals(AnimationProfile.ULTRA, ThemeEngine.resolve(AppSettings(), DeviceTier.HIGH_PERFORMANCE).anim.profile)
        val manual = ThemeEngine.resolve(AppSettings(animationAuto = false, animationProfile = AnimationProfile.BALANCED), DeviceTier.ENTRY)
        assertEquals(AnimationProfile.BALANCED, manual.anim.profile)
        val eco = ThemeEngine.resolve(AppSettings(animationAuto = false, animationProfile = AnimationProfile.ECONOMY), DeviceTier.ENTRY).anim.particleFactor
        val ultra = ThemeEngine.resolve(AppSettings(animationAuto = false, animationProfile = AnimationProfile.ULTRA), DeviceTier.ENTRY).anim.particleFactor
        // Effekte sind entfernt: unabhängig vom Profil keine Partikel, kein bewegter Hintergrund
        assertEquals(0f, eco); assertEquals(0f, ultra)
        assertFalse(ThemeEngine.resolve(AppSettings(), DeviceTier.HIGH_PERFORMANCE).anim.backgroundMotion)
    }

    @Test fun reduceMotionStopsBackground() {
        val r = ThemeEngine.resolve(AppSettings(reduceMotion = true), DeviceTier.PREMIUM)
        assertFalse(r.anim.backgroundMotion)
        assertTrue(r.anim.reduceMotion)
    }
}
