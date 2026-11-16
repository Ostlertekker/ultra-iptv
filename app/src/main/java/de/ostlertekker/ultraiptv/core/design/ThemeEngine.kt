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

enum class BackgroundEffect { GALAXY, GOLD, CYBER_GRID, GLASS, AURORA, OCEAN, MATRIX, SPACE, DIAMOND, INFERNO,
    SYNTHWAVE, SNOW, BOKEH, HEX, LAVA, RAIN, EQUALIZER, CHROME }

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

data class ResolvedTheme(val spec: ThemeSpec, val palette: UltraPalette, val anim: AnimationConfig, val bgDim: Float = 0f)

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
) {
    companion object {
        fun from(s: AppSettings) = UiPrefs(
            s.showChannelNumbers, s.showChannelLogos, s.showChannelEpg, s.channelDensity.coerceIn(0, 2),
            when (s.posterSize) { 0 -> 130.dp; 2 -> 200.dp; 3 -> 240.dp; else -> 160.dp }, s.menuClock, s.clock24h,
        )
    }
}

val LocalUiPrefs = staticCompositionLocalOf { UiPrefs() }

object DesignOptions {
    val cornerNames = listOf("Theme-Standard", "Eckig", "Leicht gerundet", "Rund", "Extra rund")
    val cornerValues = listOf<Dp?>(null, 0.dp, 8.dp, 18.dp, 28.dp)
    val zoomNames = listOf("Theme-Standard", "Kein Zoom", "Dezent", "Normal", "Stark")
    val zoomValues = listOf<Float?>(null, 1f, 1.03f, 1.07f, 1.12f)
    val fontNames = listOf("Theme-Standard", "Modern (Sans)", "Klassisch (Serif)", "Technisch (Mono)", "Verspielt (Cursive)")
    val fontValues = listOf<FontFamily?>(null, FontFamily.SansSerif, FontFamily.Serif, FontFamily.Monospace, FontFamily.Cursive)
    val dimValues = listOf(0f, 0.2f, 0.4f, 0.6f)
    val scaleValues = listOf(0.85f, 0.9f, 1f, 1.1f, 1.2f)
    val densityNames = listOf("Kompakt", "Normal", "Groß")
    val posterNames = listOf("Klein", "Mittel", "Groß", "Riesig")
}

/**
 * Registry aller Themes. Neue Themes werden mit [register] hinzugefügt – ohne Änderung der App-Logik.
 */
object ThemeRegistry {
    private val themes = LinkedHashMap<String, ThemeSpec>()

    init { (BuiltInThemes.all + BuiltInThemes.extra).forEach { register(it) } }

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
        val base0 = ThemeRegistry.byId(s.themeId)
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
            particleFactor = (base * s.particleIntensity).coerceIn(0f, 1.5f),
            intensity = s.animationIntensity.coerceIn(0f, 1.5f),
            reduceMotion = s.reduceMotion,
            backgroundMotion = s.backgroundAnimation && !s.reduceMotion,
        )
        return ResolvedTheme(spec, palette, anim, DesignOptions.dimValues.getOrElse(s.bgDim) { 0f })
    }

    /** Auswählbare Akzentfarben (0 = Theme-Standard). */
    val accentPresets: List<Pair<String, Long>> = listOf(
        "Theme-Standard" to 0L,
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

    private fun t(id: String, name: String, tag: String, pal: List<Long>, fx: BackgroundEffect, font: FontFamily, w: FontWeight,
                  corner: Int, zoom: Float, sparkles: Boolean, stars: Boolean, shimmer: Boolean, glow: Float,
                  tr: TransitionStyle, loader: LoaderStyle, sound: SoundPreset, particles: Int) = ThemeSpec(
        id, name, tag,
        UltraPalette(c(pal[0]), c(pal[1]), c(pal[2]), c(pal[3]), c(pal[4]), c(pal[5]), c(pal[6]), c(pal[7]), c(pal[8]), c(pal[9]), c(pal[10])),
        fx, font, w, corner.dp, zoom, 1.5.dp, MenuFx(sparkles, stars, shimmer, glow), tr, loader, sound, particles,
    )

    val extra = listOf(
        t("synthwave", "SYNTHWAVE", "Retro-Sonne, Neon-Raster, 80er-Nächte",
            listOf(0xFF12002B, 0xFF3A0446, 0x99240A3A, 0xE0501466, 0xFFFF3EA5, 0xFF2DE2E6, 0xFFFFF0FB, 0xFFC09AC9, 0xFFFF3EA5, 0xFFFFD319, 0xFF2DE2E6),
            BackgroundEffect.SYNTHWAVE, FontFamily.SansSerif, FontWeight.ExtraBold, 10, 1.07f, false, true, true, 1.1f, TransitionStyle.SLIDE, LoaderStyle.BARS, SoundPreset.NEON, 60),
        t("outrun", "OUTRUN", "Endlose Straße in Orange und Türkis",
            listOf(0xFF0B0221, 0xFF2B0F3A, 0x99200C33, 0xE0482060, 0xFFFF8A00, 0xFF00E5FF, 0xFFFFF4E6, 0xFFB79CC0, 0xFFFF8A00, 0xFFFF2E63, 0xFF00E5FF),
            BackgroundEffect.SYNTHWAVE, FontFamily.Monospace, FontWeight.Bold, 4, 1.07f, false, false, true, 1f, TransitionStyle.SLIDE, LoaderStyle.BARS, SoundPreset.DIGITAL, 40),
        t("hardtekk", "HARDTEKK", "Pumpende Equalizer, Rot auf Schwarz – 200 BPM fürs Auge",
            listOf(0xFF050000, 0xFF1A0000, 0x99200404, 0xE0520A0A, 0xFFFF1A1A, 0xFFFFFFFF, 0xFFFFF0F0, 0xFFB08A8A, 0xFFFF2A2A, 0xFFFF3030, 0xFFFFFFFF),
            BackgroundEffect.EQUALIZER, FontFamily.SansSerif, FontWeight.Black, 2, 1.08f, false, false, true, 1.3f, TransitionStyle.ZOOM, LoaderStyle.BARS, SoundPreset.FIRE, 48),
        t("rave", "RAVE", "Stroboskopfreie Laser-Farben, Equalizer im Takt",
            listOf(0xFF05010F, 0xFF120326, 0x99140A2E, 0xE02E1460, 0xFFB4FF39, 0xFFFF39D7, 0xFFF7FFE8, 0xFFA4A0C4, 0xFFB4FF39, 0xFFFF39D7, 0xFF39E1FF),
            BackgroundEffect.EQUALIZER, FontFamily.Monospace, FontWeight.ExtraBold, 8, 1.08f, true, false, true, 1.2f, TransitionStyle.ZOOM, LoaderStyle.BARS, SoundPreset.NEON, 64),
        t("chrome_oil", "CHROME OIL", "Flüssiges Chrom mit Ölfilm-Schimmer",
            listOf(0xFF07080B, 0xFF1A1D24, 0x80262A33, 0xE0444B58, 0xFFE6ECF5, 0xFF9C7CFF, 0xFFFFFFFF, 0xFFA7AFBD, 0xFFDDE6F3, 0xFFFFFFFF, 0xFF6FE7FF),
            BackgroundEffect.CHROME, FontFamily.SansSerif, FontWeight.Black, 14, 1.06f, true, false, true, 1f, TransitionStyle.ZOOM, LoaderStyle.ORBIT, SoundPreset.CRYSTAL, 50),
        t("liquid_silver", "LIQUID SILVER", "Gebürstetes Metall, kühle Reflexe",
            listOf(0xFF0C0D10, 0xFF22252B, 0x80262A31, 0xE04A505B, 0xFFCFD8E3, 0xFF8FA6C2, 0xFFF4F7FB, 0xFFA2AAB6, 0xFFE3EAF2, 0xFFFFFFFF, 0xFFB9C7D8),
            BackgroundEffect.CHROME, FontFamily.Serif, FontWeight.SemiBold, 6, 1.05f, false, false, true, 0.7f, TransitionStyle.FADE, LoaderStyle.PULSE, SoundPreset.GLASS, 30),
        t("winter", "WINTER", "Leiser Schneefall in Eisblau",
            listOf(0xFF061423, 0xFF16314A, 0x8015304A, 0xE02A5578, 0xFF9AD8FF, 0xFFFFFFFF, 0xFFF2FAFF, 0xFF9DB7CC, 0xFFBFE6FF, 0xFFFFFFFF, 0xFFCFEFFF),
            BackgroundEffect.SNOW, FontFamily.SansSerif, FontWeight.SemiBold, 18, 1.06f, true, false, true, 0.8f, TransitionStyle.FADE, LoaderStyle.PULSE, SoundPreset.CRYSTAL, 160),
        t("christmas", "WEIHNACHT", "Schnee, Gold und tiefes Tannengrün",
            listOf(0xFF03140B, 0xFF1A0A0A, 0x99102418, 0xE0234A2E, 0xFFE53935, 0xFFFFD54F, 0xFFFFF8E7, 0xFFB9B09A, 0xFFFFD54F, 0xFFFFFFFF, 0xFFFFE082),
            BackgroundEffect.SNOW, FontFamily.Serif, FontWeight.Bold, 16, 1.07f, true, false, true, 1f, TransitionStyle.ZOOM, LoaderStyle.ORBIT, SoundPreset.GOLD, 150),
        t("sakura", "SAKURA", "Fallende Kirschblüten in Rosa",
            listOf(0xFF1A0A14, 0xFF2E1424, 0x8030182A, 0xE0582A4A, 0xFFFF8FC7, 0xFFFFD1E6, 0xFFFFF3F8, 0xFFC7A2B6, 0xFFFF9FCF, 0xFFFFB7D9, 0xFFFFE1EE),
            BackgroundEffect.SNOW, FontFamily.Serif, FontWeight.Medium, 22, 1.05f, false, false, true, 0.7f, TransitionStyle.FADE, LoaderStyle.PULSE, SoundPreset.AURORA, 90),
        t("bokeh", "BOKEH NIGHT", "Weiche Lichter wie durch ein Objektiv",
            listOf(0xFF0E0906, 0xFF231710, 0x80281C14, 0xE04A3324, 0xFFFFB45A, 0xFFFF7A59, 0xFFFFF5EA, 0xFFC4AE98, 0xFFFFC27A, 0xFFFFC27A, 0xFFFF8A65),
            BackgroundEffect.BOKEH, FontFamily.SansSerif, FontWeight.SemiBold, 20, 1.06f, false, false, true, 0.8f, TransitionStyle.FADE, LoaderStyle.PULSE, SoundPreset.GOLD, 40),
        t("city_lights", "CITY LIGHTS", "Nächtliche Stadt in Blau und Bernstein",
            listOf(0xFF050A16, 0xFF0F1C33, 0x80121F38, 0xE0243B66, 0xFF4DA3FF, 0xFFFFB74D, 0xFFEFF5FF, 0xFF95A6C2, 0xFF6CB4FF, 0xFF6CB4FF, 0xFFFFC46B),
            BackgroundEffect.BOKEH, FontFamily.SansSerif, FontWeight.Bold, 14, 1.06f, false, false, true, 0.9f, TransitionStyle.SLIDE, LoaderStyle.ORBIT, SoundPreset.OCEAN, 44),
        t("hexcore", "HEXCORE", "Pulsierende Waben, Reaktor-Orange",
            listOf(0xFF0A0703, 0xFF1C1206, 0x99201608, 0xE0463014, 0xFFFF9800, 0xFFFFE0B2, 0xFFFFF6EA, 0xFFB8A58A, 0xFFFFA726, 0xFFFFB74D, 0xFFFFE0B2),
            BackgroundEffect.HEX, FontFamily.Monospace, FontWeight.Bold, 4, 1.07f, false, false, true, 1.1f, TransitionStyle.ZOOM, LoaderStyle.BARS, SoundPreset.DIGITAL, 30),
        t("toxic", "TOXIC", "Giftgrüne Waben und Warnlicht",
            listOf(0xFF030803, 0xFF0F1A06, 0x99121F08, 0xE02A4410, 0xFFB6FF00, 0xFFFFEA00, 0xFFF5FFE0, 0xFF9DB07A, 0xFFC6FF00, 0xFFB6FF00, 0xFFFFEA00),
            BackgroundEffect.HEX, FontFamily.Monospace, FontWeight.ExtraBold, 0, 1.07f, false, false, false, 1.2f, TransitionStyle.SLIDE, LoaderStyle.BARS, SoundPreset.DIGITAL, 30),
        t("lava", "LAVA LAMP", "Langsam wabernde Farbblasen",
            listOf(0xFF14030A, 0xFF2A0716, 0x992A0A18, 0xE0561632, 0xFFFF4F6D, 0xFFFFA94D, 0xFFFFF0F2, 0xFFC799A4, 0xFFFF6B81, 0xFFFF8A65, 0xFFFFC46B),
            BackgroundEffect.LAVA, FontFamily.SansSerif, FontWeight.Bold, 26, 1.06f, false, false, true, 0.9f, TransitionStyle.FADE, LoaderStyle.PULSE, SoundPreset.FIRE, 20),
        t("purple_rain", "PURPLE RAIN", "Violetter Regen auf nassem Asphalt",
            listOf(0xFF07020F, 0xFF180A2B, 0x99180C2C, 0xE0341A5C, 0xFFB388FF, 0xFFEA80FC, 0xFFF6F0FF, 0xFFA39AC0, 0xFFB388FF, 0xFFC9A7FF, 0xFFEA80FC),
            BackgroundEffect.RAIN, FontFamily.SansSerif, FontWeight.SemiBold, 12, 1.06f, false, false, true, 0.9f, TransitionStyle.FADE, LoaderStyle.ORBIT, SoundPreset.AURORA, 140),
        t("neon_rain", "NEON RAIN", "Cyan- und Pink-Regen in der Großstadtnacht",
            listOf(0xFF020812, 0xFF0A1226, 0x990C1528, 0xE01A2C52, 0xFF00E5FF, 0xFFFF2E9A, 0xFFEFFBFF, 0xFF8FA4BF, 0xFF00E5FF, 0xFF00E5FF, 0xFFFF2E9A),
            BackgroundEffect.RAIN, FontFamily.Monospace, FontWeight.Bold, 2, 1.07f, false, false, true, 1.2f, TransitionStyle.SLIDE, LoaderStyle.BARS, SoundPreset.NEON, 160),
        t("midnight", "MIDNIGHT", "Ruhiges Nachtblau, wenig Ablenkung",
            listOf(0xFF02040C, 0xFF070D22, 0x800B132E, 0xE01B2A5C, 0xFF6E8BFF, 0xFF9AB0FF, 0xFFEEF1FF, 0xFF8D96B8, 0xFF7D97FF, 0xFFDDE4FF, 0xFF9AB0FF),
            BackgroundEffect.GALAXY, FontFamily.SansSerif, FontWeight.SemiBold, 16, 1.05f, false, false, false, 0.6f, TransitionStyle.FADE, LoaderStyle.PULSE, SoundPreset.SPACE, 80),
        t("rose_gold", "ROSÉ GOLD", "Warmes Roségold, edel und hell",
            listOf(0xFF140C0B, 0xFF2A1A18, 0x80301E1B, 0xE0583A35, 0xFFF4B6A6, 0xFFFFE3D6, 0xFFFFF6F2, 0xFFC6A8A0, 0xFFFFC7B7, 0xFFFFD1C2, 0xFFFFECE4),
            BackgroundEffect.GOLD, FontFamily.Serif, FontWeight.Medium, 18, 1.05f, true, false, true, 0.8f, TransitionStyle.FADE, LoaderStyle.PULSE, SoundPreset.GOLD, 110),
        t("emerald", "EMERALD", "Smaragdgrüne Polarlichter",
            listOf(0xFF010C08, 0xFF042117, 0x80082A1E, 0xE0105238, 0xFF00E676, 0xFF64FFDA, 0xFFE9FFF4, 0xFF8FBFA9, 0xFF00E676, 0xFFE0FFF0, 0xFF64FFDA),
            BackgroundEffect.AURORA, FontFamily.SansSerif, FontWeight.Bold, 14, 1.06f, true, false, true, 0.9f, TransitionStyle.FADE, LoaderStyle.ORBIT, SoundPreset.AURORA, 90),
        t("sunset", "SUNSET", "Abendrot in Orange, Pink und Violett",
            listOf(0xFF1A0612, 0xFF3A0E22, 0x80361020, 0xE0662040, 0xFFFF7043, 0xFFBA68C8, 0xFFFFF1EA, 0xFFC9A1A8, 0xFFFF8A65, 0xFFFFE0D0, 0xFFCE93D8),
            BackgroundEffect.AURORA, FontFamily.SansSerif, FontWeight.ExtraBold, 20, 1.06f, true, false, true, 1f, TransitionStyle.ZOOM, LoaderStyle.ORBIT, SoundPreset.FIRE, 70),
        t("deep_sea", "DEEP SEA", "Tiefsee mit Biolumineszenz",
            listOf(0xFF000812, 0xFF001A2C, 0x80021C30, 0xE0063A5C, 0xFF00BFA5, 0xFF40C4FF, 0xFFE6FBFF, 0xFF7FA8B8, 0xFF1DE9B6, 0xFF84FFFF, 0xFF40C4FF),
            BackgroundEffect.OCEAN, FontFamily.SansSerif, FontWeight.SemiBold, 22, 1.06f, false, false, true, 0.9f, TransitionStyle.SLIDE, LoaderStyle.PULSE, SoundPreset.OCEAN, 110),
        t("amoled", "AMOLED BLACK", "Reines Schwarz – ideal für OLED-TVs, minimale Effekte",
            listOf(0xFF000000, 0xFF000000, 0x66141414, 0xE0262626, 0xFFFFFFFF, 0xFFBDBDBD, 0xFFFFFFFF, 0xFF9E9E9E, 0xFFFFFFFF, 0xFF444444, 0xFF666666),
            BackgroundEffect.GLASS, FontFamily.SansSerif, FontWeight.Bold, 12, 1.05f, false, false, false, 0.4f, TransitionStyle.FADE, LoaderStyle.PULSE, SoundPreset.GLASS, 0),
        t("ice", "ICE CRYSTAL", "Gefrorene Kristalle und Polarlicht-Blau",
            listOf(0xFF03101A, 0xFF0D2636, 0x8010293A, 0xE0204D66, 0xFFB3F0FF, 0xFF80D8FF, 0xFFF5FDFF, 0xFF99B8C6, 0xFFCCF5FF, 0xFFFFFFFF, 0xFFA6E9FF),
            BackgroundEffect.DIAMOND, FontFamily.SansSerif, FontWeight.Medium, 8, 1.06f, true, false, true, 1f, TransitionStyle.ZOOM, LoaderStyle.PULSE, SoundPreset.CRYSTAL, 120),
        t("volcano", "VOLCANO", "Ascheregen und glühende Lava",
            listOf(0xFF0A0605, 0xFF1F0B06, 0x99221008, 0xE04E200E, 0xFFFF6D00, 0xFFFFD180, 0xFFFFF0E6, 0xFFB59A8A, 0xFFFF7A1A, 0xFFFF9E40, 0xFF9E9E9E),
            BackgroundEffect.INFERNO, FontFamily.Serif, FontWeight.ExtraBold, 6, 1.07f, true, false, true, 1.1f, TransitionStyle.SLIDE, LoaderStyle.BARS, SoundPreset.FIRE, 160),
        t("terminal", "TERMINAL", "Bernstein-Monitor wie in den 80ern",
            listOf(0xFF070400, 0xFF140B00, 0x99180D00, 0xE0382000, 0xFFFFB000, 0xFFFFD580, 0xFFFFE9C4, 0xFFAA8A55, 0xFFFFB000, 0xFFFFB000, 0xFFFFD580),
            BackgroundEffect.MATRIX, FontFamily.Monospace, FontWeight.Bold, 0, 1.05f, false, false, false, 1f, TransitionStyle.FADE, LoaderStyle.BARS, SoundPreset.DIGITAL, 60),
    )

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
