package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.data.AppSettings
import de.ostlertekker.ultraiptv.core.design.ThemeEngine
import de.ostlertekker.ultraiptv.core.design.ThemeRegistry
import de.ostlertekker.ultraiptv.core.model.AnimationProfile
import de.ostlertekker.ultraiptv.core.model.DeviceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeEngineTest {
    @Test fun allThemesUniqueAndEveryEffectUsed() {
        val all = ThemeRegistry.all()
        assertEquals(35, all.size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(de.ostlertekker.ultraiptv.core.design.BackgroundEffect.entries.toSet(), all.map { it.effect }.toSet())
    }

    @Test fun designOverridesApply() {
        val r = ThemeEngine.resolve(AppSettings(cornerStyle = 1, focusZoom = 1, bgDim = 2), DeviceTier.PREMIUM)
        assertEquals(0f, r.spec.corner.value)
        assertEquals(1f, r.spec.focusScale)
        assertEquals(0.4f, r.bgDim)
    }

    @Test fun unknownIdFallsBackToGalaxy() {
        assertEquals("galaxy", ThemeRegistry.byId("gibt-es-nicht").id)
    }

    @Test fun switchingThemeChangesPalette() {
        val a = ThemeEngine.resolve(AppSettings(themeId = "galaxy"), DeviceTier.PREMIUM)
        val b = ThemeEngine.resolve(AppSettings(themeId = "red_inferno"), DeviceTier.PREMIUM)
        assertNotEquals(a.palette.primary, b.palette.primary)
        assertNotEquals(a.spec.effect, b.spec.effect)
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
        assertTrue(eco < ultra)
    }

    @Test fun reduceMotionStopsBackground() {
        val r = ThemeEngine.resolve(AppSettings(reduceMotion = true), DeviceTier.PREMIUM)
        assertFalse(r.anim.backgroundMotion)
        assertTrue(r.anim.reduceMotion)
    }
}
