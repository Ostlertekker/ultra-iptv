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

enum class BackgroundEffect { GALAXY, GOLD, CYBER_GRID, GLASS, AURORA, OCEAN, MATRIX, SPACE, DIAMOND, INFERNO }

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
    val effect: BackgroundEffect,
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

/**
 * Registry aller Themes. Neue Themes werden mit [register] hinzugefügt – ohne Änderung der App-Logik.
 */
object ThemeRegistry {
    private val themes = LinkedHashMap<String, ThemeSpec>()

    init { BuiltInThemes.all.forEach { register(it) } }

    fun register(spec: ThemeSpec) { themes[spec.id] = spec }
    fun all(): List<ThemeSpec> = themes.values.toList()
    fun byId(id: String): ThemeSpec = themes[id] ?: themes.getValue("galaxy")
}

object ThemeEngine {
    fun profileFor(s: AppSettings, tier: DeviceTier): AnimationProfile =
        if (s.animationAuto) when (tier) {
            DeviceTier.ENTRY -> AnimationProfile.ECONOMY
            DeviceTier.STANDARD, DeviceTier.PREMIUM -> AnimationProfile.BALANCED
            DeviceTier.HIGH_PERFORMANCE -> AnimationProfile.ULTRA
        } else s.animationProfile

    fun resolve(s: AppSettings, tier: DeviceTier): ResolvedTheme {
        val spec = ThemeRegistry.byId(s.themeId)
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
            particleFactor = (base * s.particleIntensity).coerceIn(0f, 1.5f),
            intensity = s.animationIntensity.coerceIn(0f, 1.5f),
            reduceMotion = s.reduceMotion,
            backgroundMotion = s.backgroundAnimation && !s.reduceMotion,
        )
        return ResolvedTheme(spec, palette, anim)
    }

    /** Auswählbare Akzentfarben (0 = Theme-Standard). */
    val accentPresets: List<Pair<String, Long>> = listOf(
        "Theme-Standard" to 0L,
        "Violett" to 0xFF9D6BFFL, "Eisblau" to 0xFF4FC3F7L, "Gold" to 0xFFFFC94DL, "Smaragd" to 0xFF2EE59DL,
        "Magenta" to 0xFFFF4FD8L, "Rot" to 0xFFFF4545L, "Weiß" to 0xFFF2F2F2L,
    )
}

val LocalUltraTheme = staticCompositionLocalOf<ResolvedTheme> { error("Kein Theme gesetzt") }

private object BuiltInThemes {
    private fun c(v: Long) = Color(v.toInt())

    val all = listOf(
        ThemeSpec(
            "galaxy", "GALAXY", "Die Signature-Erfahrung: Nebel, Sternschnuppen, kosmischer Glanz",
            UltraPalette(c(0xFF070A1F), c(0xFF140A2E), c(0x8C141A3A), c(0xE02A2266), c(0xFFA77BFF), c(0xFF5BC8FF),
                c(0xFFF4F1FF), c(0xFFA9A6C9), c(0xFFB28CFF), c(0xFFFFFFFF), c(0xFF8FD8FF)),
            BackgroundEffect.GALAXY, FontFamily.SansSerif, FontWeight.Bold, 18.dp, 1.08f, 2.dp,
            MenuFx(sparkles = true, shootingStars = true, shimmer = true, glowStrength = 1f),
            TransitionStyle.ZOOM, LoaderStyle.ORBIT, SoundPreset.COSMIC, 220,
        ),
        ThemeSpec(
            "black_gold", "BLACK GOLD", "Metallisches Gold auf tiefem Schwarz",
            UltraPalette(c(0xFF050505), c(0xFF15110A), c(0x99151208), c(0xE03A2E12), c(0xFFE8C468), c(0xFFFFF1C1),
                c(0xFFFFF7E3), c(0xFFB9A98A), c(0xFFFFD36E), c(0xFFFFD77A), c(0xFFFFF3C4)),
            BackgroundEffect.GOLD, FontFamily.Serif, FontWeight.SemiBold, 6.dp, 1.06f, 1.5.dp,
            MenuFx(sparkles = true, shootingStars = false, shimmer = true, glowStrength = 0.8f),
            TransitionStyle.FADE, LoaderStyle.PULSE, SoundPreset.GOLD, 120,
        ),
        ThemeSpec(
            "cyberpunk", "CYBERPUNK", "Neon, digitale Raster, futuristisches Licht",
            UltraPalette(c(0xFF07021A), c(0xFF1B0433), c(0x99120A2E), c(0xE0331060), c(0xFFFF2BD6), c(0xFF19F0FF),
                c(0xFFF6F0FF), c(0xFF9C8FC4), c(0xFFFF2BD6), c(0xFF19F0FF), c(0xFFFF2BD6)),
            BackgroundEffect.CYBER_GRID, FontFamily.Monospace, FontWeight.Bold, 2.dp, 1.07f, 2.dp,
            MenuFx(sparkles = false, shootingStars = false, shimmer = true, glowStrength = 1.2f),
            TransitionStyle.SLIDE, LoaderStyle.BARS, SoundPreset.NEON, 60,
        ),
        ThemeSpec(
            "glass", "GLASS PREMIUM", "Transparente Panels, weiche Reflexe",
            UltraPalette(c(0xFF0E1726), c(0xFF1F2D44), c(0x40FFFFFF), c(0x70FFFFFF), c(0xFFFFFFFF), c(0xFF9EC9FF),
                c(0xFFFFFFFF), c(0xFFC5D2E6), c(0xFFDDEBFF), c(0xFFFFFFFF), c(0xFFB9D7FF)),
            BackgroundEffect.GLASS, FontFamily.SansSerif, FontWeight.Medium, 24.dp, 1.05f, 1.dp,
            MenuFx(sparkles = false, shootingStars = false, shimmer = true, glowStrength = 0.6f),
            TransitionStyle.FADE, LoaderStyle.PULSE, SoundPreset.GLASS, 40,
        ),
        ThemeSpec(
            "aurora", "AURORA", "Animierte Polarlichter und fließende Farbverläufe",
            UltraPalette(c(0xFF020B14), c(0xFF06222B), c(0x80092633), c(0xE0144A55), c(0xFF4DFFB8), c(0xFF8A7CFF),
                c(0xFFEFFFF9), c(0xFF9EC4BC), c(0xFF4DFFB8), c(0xFFE6FFF6), c(0xFF8A7CFF)),
            BackgroundEffect.AURORA, FontFamily.SansSerif, FontWeight.SemiBold, 16.dp, 1.06f, 1.5.dp,
            MenuFx(sparkles = true, shootingStars = false, shimmer = true, glowStrength = 0.9f),
            TransitionStyle.FADE, LoaderStyle.ORBIT, SoundPreset.AURORA, 90,
        ),
        ThemeSpec(
            "ocean", "OCEAN", "Unterwasserwelt mit Lichtstrahlen",
            UltraPalette(c(0xFF02152B), c(0xFF003049), c(0x80052A45), c(0xE00A4E73), c(0xFF35D0FF), c(0xFF7CF5E1),
                c(0xFFEAF9FF), c(0xFF94BED3), c(0xFF35D0FF), c(0xFFBFF3FF), c(0xFF7CF5E1)),
            BackgroundEffect.OCEAN, FontFamily.SansSerif, FontWeight.Medium, 20.dp, 1.06f, 1.5.dp,
            MenuFx(sparkles = false, shootingStars = false, shimmer = true, glowStrength = 0.8f),
            TransitionStyle.SLIDE, LoaderStyle.PULSE, SoundPreset.OCEAN, 80,
        ),
        ThemeSpec(
            "matrix", "MATRIX", "Digitale Datenströme in Grün",
            UltraPalette(c(0xFF000300), c(0xFF001A06), c(0x99001A06), c(0xE0003D10), c(0xFF29FF5C), c(0xFFA6FFB8),
                c(0xFFD9FFE0), c(0xFF5FA36D), c(0xFF29FF5C), c(0xFF29FF5C), c(0xFFB6FFC4)),
            BackgroundEffect.MATRIX, FontFamily.Monospace, FontWeight.Bold, 0.dp, 1.05f, 1.5.dp,
            MenuFx(sparkles = false, shootingStars = false, shimmer = false, glowStrength = 1f),
            TransitionStyle.FADE, LoaderStyle.BARS, SoundPreset.DIGITAL, 60,
        ),
        ThemeSpec(
            "space", "SPACE", "Sterne, Planeten und Himmelsmechanik",
            UltraPalette(c(0xFF000000), c(0xFF0B0F1E), c(0x8C0F1528), c(0xE0222C4E), c(0xFFFFA94D), c(0xFF7FA7FF),
                c(0xFFF3F6FF), c(0xFF9AA3BF), c(0xFFFFB866), c(0xFFFFFFFF), c(0xFFFFD2A1)),
            BackgroundEffect.SPACE, FontFamily.SansSerif, FontWeight.Bold, 14.dp, 1.07f, 1.5.dp,
            MenuFx(sparkles = true, shootingStars = true, shimmer = false, glowStrength = 0.8f),
            TransitionStyle.ZOOM, LoaderStyle.ORBIT, SoundPreset.SPACE, 180,
        ),
        ThemeSpec(
            "diamond", "DIAMOND", "Kristallreflexe und metallische Highlights",
            UltraPalette(c(0xFF0B0F14), c(0xFF1C232D), c(0x80202A36), c(0xE03A4A5C), c(0xFFDDF3FF), c(0xFFB7A6FF),
                c(0xFFFFFFFF), c(0xFFAAB6C4), c(0xFFE6F7FF), c(0xFFFFFFFF), c(0xFFCFE9FF)),
            BackgroundEffect.DIAMOND, FontFamily.Serif, FontWeight.Medium, 10.dp, 1.06f, 1.dp,
            MenuFx(sparkles = true, shootingStars = false, shimmer = true, glowStrength = 0.9f),
            TransitionStyle.ZOOM, LoaderStyle.PULSE, SoundPreset.CRYSTAL, 110,
        ),
        ThemeSpec(
            "red_inferno", "RED INFERNO", "Glühende Partikel und kontrolliertes Feuer",
            UltraPalette(c(0xFF0C0202), c(0xFF2A0505), c(0x99260707), c(0xE05A1010), c(0xFFFF5A2A), c(0xFFFFC14D),
                c(0xFFFFF0EA), c(0xFFC79C90), c(0xFFFF6A33), c(0xFFFFA040), c(0xFFFFE08A)),
            BackgroundEffect.INFERNO, FontFamily.SansSerif, FontWeight.ExtraBold, 12.dp, 1.07f, 2.dp,
            MenuFx(sparkles = true, shootingStars = false, shimmer = true, glowStrength = 1.1f),
            TransitionStyle.SLIDE, LoaderStyle.BARS, SoundPreset.FIRE, 140,
        ),
    )
}
