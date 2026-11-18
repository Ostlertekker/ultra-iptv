package de.ostlertekker.ultraiptv.core.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.ostlertekker.ultraiptv.core.audio.SoundPreset
import de.ostlertekker.ultraiptv.core.data.AppSettings
import de.ostlertekker.ultraiptv.core.model.AnimationProfile
import de.ostlertekker.ultraiptv.core.model.DeviceTier

enum class TransitionStyle { FADE, SLIDE, ZOOM }

enum class LoaderStyle { ORBIT, PULSE, BARS }

data class MenuFx(
    val sparkles: Boolean,
    val shootingStars: Boolean,
    val shimmer: Boolean,
    val glowStrength: Float,
)

data class UltraPalette(
    val bgTop: Color,
    val bgBottom: Color,
    val surface: Color,
    val surfaceFocused: Color,
    val primary: Color,
    val secondary: Color,
    val text: Color,
    val textDim: Color,
    val glow: Color,
    val particle: Color,
    val particle2: Color,
)

data class ThemeSpec(
    val id: String,
    val displayName: String,
    val tagline: String,
    val palette: UltraPalette,
    val fontFamily: FontFamily,
    val titleWeight: FontWeight,
    val corner: Dp,
    val focusScale: Float,
    val borderWidth: Dp,
    val menuFx: MenuFx,
    val transition: TransitionStyle,
    val loader: LoaderStyle,
    val sound: SoundPreset,
    val baseParticles: Int,
)

data class AnimationConfig(
    val profile: AnimationProfile,
    val particleFactor: Float,
    val intensity: Float,
    val reduceMotion: Boolean,
    val backgroundMotion: Boolean,
)

data class ResolvedTheme(val spec: ThemeSpec, val palette: UltraPalette, val anim: AnimationConfig)

/** Layout-/Anzeige-Vorlieben, die überall in der Oberfläche gelten. */
data class UiPrefs(
    val showNumbers: Boolean = true,
    val showLogos: Boolean = true,
    val showChannelEpg: Boolean = true,
    /** 0 = kompakt, 1 = normal, 2 = groß */
    val density: Int = 1,
    val posterWidth: Dp = 160.dp,
    val menuClock: Boolean = true,
    val clock24h: Boolean = true,
    val showChannelProgress: Boolean = false,
) {
    companion object {
        fun from(s: AppSettings) = UiPrefs(
            s.showChannelNumbers, s.showChannelLogos, s.showChannelEpg, s.channelDensity.coerceIn(0, 2),
            when (s.posterSize) { 0 -> 130.dp; 2 -> 200.dp; 3 -> 240.dp; else -> 160.dp }, s.menuClock, s.clock24h, s.showChannelProgress,
        )
    }
}

val LocalUiPrefs = staticCompositionLocalOf { UiPrefs() }

object DesignOptions {
    val cornerNames = listOf("Theme-Standard", "Eckig", "Leicht gerundet", "Rund", "Extra rund")
    val cornerValues = listOf<Dp?>(null, 0.dp, 8.dp, 18.dp, 28.dp)
    val zoomNames = listOf("Theme-Standard", "Kein Zoom", "Dezent", "Normal", "Stark")
    val zoomValues = listOf<Float?>(null, 1f, 1.03f, 1.07f, 1.12f)
    // Reihenfolge der ersten fünf nicht ändern (gespeicherte Auswahl bleibt gültig); neue Schriften hinten anhängen
    val fontNames = listOf("Theme-Standard", "System modern", "System klassisch", "System technisch", "System verspielt") +
        AppFonts.catalog.map { it.first }
    val fontValues = listOf<FontFamily?>(null, FontFamily.SansSerif, FontFamily.Serif, FontFamily.Monospace, FontFamily.Cursive) +
        AppFonts.catalog.map { it.third }
    val scaleValues = listOf(0.85f, 0.9f, 1f, 1.1f, 1.2f)
    val densityNames = listOf("Kompakt", "Normal", "Groß")
    val posterNames = listOf("Klein", "Mittel", "Groß", "Riesig")
}

/** Es gibt nur noch ein Design: komplett schwarzer Hintergrund; Akzentfarbe und Schriftart bleiben wählbar. */
object ThemeRegistry {
    @Suppress("UNUSED_PARAMETER")
    fun byId(id: String): ThemeSpec = BuiltInThemes.black
}

object ThemeEngine {
    fun profileFor(s: AppSettings, tier: DeviceTier): AnimationProfile =
        if (s.animationAuto) when (tier) {
            DeviceTier.ENTRY -> AnimationProfile.ECONOMY
            DeviceTier.STANDARD, DeviceTier.PREMIUM -> AnimationProfile.BALANCED
            DeviceTier.HIGH_PERFORMANCE -> AnimationProfile.ULTRA
        } else s.animationProfile

    fun resolve(s: AppSettings, tier: DeviceTier): ResolvedTheme {
        val base0 = BuiltInThemes.black
        val spec = base0.copy(
            corner = DesignOptions.cornerValues.getOrNull(s.cornerStyle) ?: base0.corner,
            focusScale = DesignOptions.zoomValues.getOrNull(s.focusZoom) ?: base0.focusScale,
            fontFamily = DesignOptions.fontValues.getOrNull(s.fontStyle) ?: base0.fontFamily,
            menuFx = if (s.focusGlow) base0.menuFx else base0.menuFx.copy(glowStrength = 0f),
            borderWidth = if (s.focusGlow) base0.borderWidth else 1.dp,
        )
        val profile = profileFor(s, tier)
        val base = when (profile) {
            AnimationProfile.ECONOMY -> 0.3f
            AnimationProfile.BALANCED -> 0.65f
            AnimationProfile.ULTRA -> 1f
        }
        val palette = if (s.accentArgb != 0L) {
            val accent = Color(s.accentArgb.toInt())
            spec.palette.copy(primary = accent, glow = accent.copy(alpha = 0.9f))
        } else spec.palette
        val anim = AnimationConfig(
            profile = profile,
            particleFactor = 0f,
            intensity = s.animationIntensity.coerceIn(0f, 1.5f),
            reduceMotion = s.reduceMotion,
            backgroundMotion = false,
        )
        return ResolvedTheme(spec, palette, anim)
    }

    /** Auswählbare Akzentfarben (0 = Theme-Standard). */
    val accentPresets: List<Pair<String, Long>> = listOf(
        "Standard (Eisblau)" to 0L,
        "Violett" to 0xFF9D6BFFL, "Eisblau" to 0xFF4FC3F7L, "Gold" to 0xFFFFC94DL, "Smaragd" to 0xFF2EE59DL,
        "Magenta" to 0xFFFF4FD8L, "Rot" to 0xFFFF4545L, "Weiß" to 0xFFF2F2F2L,
        "Orange" to 0xFFFF8A2AL, "Zitrone" to 0xFFF4FF4DL, "Limette" to 0xFF9BFF3DL, "Türkis" to 0xFF1DE9B6L,
        "Himmelblau" to 0xFF40A9FFL, "Königsblau" to 0xFF3D5AFEL, "Lavendel" to 0xFFC3A6FFL, "Pink" to 0xFFFF80ABL,
        "Koralle" to 0xFFFF7F6BL, "Kupfer" to 0xFFD9895BL, "Silber" to 0xFFC9D1D9L, "Rosé-Gold" to 0xFFF4B6A6L,
        "Neon-Grün" to 0xFF39FF14L, "Blutrot" to 0xFFD50000L,
    )
}

val LocalUltraTheme = staticCompositionLocalOf<ResolvedTheme> { error("Kein Theme gesetzt") }

private object BuiltInThemes {
    private fun c(v: Long) = Color(v.toInt())

    /** Einziges Design: reines Schwarz, Anthrazit-Flächen, Eisblau als Standard-Akzent, Inter-Schrift. */
    val black = ThemeSpec(
        "premium", "SCHWARZ", "Komplett schwarzer Hintergrund",
        UltraPalette(c(0xFF000000), c(0xFF000000), c(0xFF1C1F24), c(0xFF243447), c(0xFF8FD3FF), c(0xFF5AB4FF),
            c(0xFFFFFFFF), c(0xFFB4B9C3), c(0xFF8FD3FF), c(0xFFFFFFFF), c(0xFF8FD3FF)),
        AppFonts.Inter, FontWeight.Bold, 14.dp, 1.05f, 1.dp,
        MenuFx(sparkles = false, shootingStars = false, shimmer = false, glowStrength = 0.3f),
        TransitionStyle.FADE, LoaderStyle.PULSE, SoundPreset.GLASS, 0,
    )
}
