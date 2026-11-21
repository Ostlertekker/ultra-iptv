package de.ostlertekker.ultraiptv.core.ui

import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.ostlertekker.ultraiptv.core.animation.rememberAnimationClock
import de.ostlertekker.ultraiptv.core.animation.fract
import de.ostlertekker.ultraiptv.core.audio.AudioExperienceEngine
import de.ostlertekker.ultraiptv.core.motion.crazyEntrance
import de.ostlertekker.ultraiptv.core.motion.crazyFocus
import de.ostlertekker.ultraiptv.core.data.AppSettings
import de.ostlertekker.ultraiptv.core.design.LoaderStyle
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.design.ResolvedTheme
import de.ostlertekker.ultraiptv.core.design.ThemeEngine
import de.ostlertekker.ultraiptv.core.model.Accuracy
import de.ostlertekker.ultraiptv.core.model.AnimationProfile
import de.ostlertekker.ultraiptv.core.model.DeviceTier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val LocalUiSounds = staticCompositionLocalOf<AudioExperienceEngine?> { null }

/**
 * Einstiegspunkt der aktuellen Seite für „Pfeil runter“ aus der Menüleiste. Eine Seite meldet ihr erstes Element an
 * (z. B. Startseite: „Abspielen“); ohne Anmeldung sucht Android wie gewohnt selbst.
 */
class ContentEntry {
    val requester = FocusRequester()
    var attached by mutableStateOf(false)
}
val LocalContentEntry = staticCompositionLocalOf { ContentEntry() }

/** Als Einstieg der Seite anmelden (für Pfeil runter aus der Menüleiste). */
@Composable
fun Modifier.contentEntry(): Modifier {
    val e = LocalContentEntry.current
    androidx.compose.runtime.DisposableEffect(e) { e.attached = true; onDispose { e.attached = false } }
    return this.focusRequester(e.requester)
}

/** Stellt Theme, Farbschema und UI-Klänge für einen Bildschirmbaum bereit. */
@Composable
fun UltraThemeHost(settings: AppSettings, tier: DeviceTier, sounds: AudioExperienceEngine?, content: @Composable () -> Unit) {
    val theme = remember(settings, tier) { ThemeEngine.resolve(settings, tier) }
    // Schlanke, ruhige Oberfläche: keine Effekt-Animationen, keine Partikel – nur sanfter Fokus
    val motion = de.ostlertekker.ultraiptv.core.motion.MotionSpec.OFF
    LaunchedEffect(settings.uiSounds, settings.uiVolume) {
        sounds?.configure(settings.uiSounds, settings.uiVolume, de.ostlertekker.ultraiptv.core.audio.SoundPreset.GLASS)
    }
    val p = theme.palette
    val scheme = darkColorScheme(
        primary = p.primary, secondary = p.secondary, background = p.bgTop, surface = p.bgBottom,
        onPrimary = Color.Black, onSurface = p.text, onBackground = p.text, surfaceVariant = p.surface,
        onSurfaceVariant = p.textDim, outline = p.textDim,
    )
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val scale = settings.uiScale.coerceIn(0.8f, 1.3f)
    // Automatische Bildschirmanpassung: Die Oberfläche ist für 960 dp Breite gebaut (Fire TV 1080p/4K).
    // Melden Fernseher/Boxen eine andere Pixeldichte, wird auf diese Breite umgerechnet – damit sieht die App
    // auf jedem Gerät gleich aus und nichts ragt über den Rand.
    val baseConfig = androidx.compose.ui.platform.LocalConfiguration.current
    val dm = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics
    val widthPx = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
    val heightPx = minOf(dm.widthPixels, dm.heightPixels).toFloat()
    val fit = if (widthPx > 0f) minOf(widthPx / 960f, heightPx / 540f) else baseDensity.density
    val targetDensity = (if (kotlin.math.abs(fit - baseDensity.density) / baseDensity.density > 0.03f) fit else baseDensity.density) * scale
    val config = remember(baseConfig, targetDensity) {
        if (targetDensity == baseDensity.density) baseConfig
        else android.content.res.Configuration(baseConfig).apply {
            screenWidthDp = (widthPx / targetDensity).toInt(); screenHeightDp = (heightPx / targetDensity).toInt()
            smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp); densityDpi = (targetDensity * 160).toInt()
        }
    }
    val prefs = remember(settings) { de.ostlertekker.ultraiptv.core.design.UiPrefs.from(settings) }
    CompositionLocalProvider(LocalUltraTheme provides theme, LocalUiSounds provides sounds,
        de.ostlertekker.ultraiptv.core.motion.LocalMotion provides motion,
        de.ostlertekker.ultraiptv.core.design.LocalUiPrefs provides prefs,
        LocalPerfMode provides true,
        androidx.compose.ui.platform.LocalConfiguration provides config,
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(targetDensity, baseDensity.fontScale)) {
        // Gewählte Schrift (oder Theme-Schrift aus dem Schriften-Paket) gilt für die gesamte Oberfläche
        val ff = theme.spec.fontFamily
        val global = settings.fontStyle > 0 || ff !in listOf(androidx.compose.ui.text.font.FontFamily.SansSerif, androidx.compose.ui.text.font.FontFamily.Serif,
            androidx.compose.ui.text.font.FontFamily.Monospace, androidx.compose.ui.text.font.FontFamily.Cursive, androidx.compose.ui.text.font.FontFamily.Default) &&
            ff != de.ostlertekker.ultraiptv.core.design.AppFonts.PressStart // Pixel-Schrift bei Retro-Themes nur für Überschriften (lesbarer)
        val typography = remember(ff, global) {
            val t = androidx.compose.material3.Typography()
            if (!global) t else {
                fun androidx.compose.ui.text.TextStyle.f() = copy(fontFamily = ff)
                androidx.compose.material3.Typography(t.displayLarge.f(), t.displayMedium.f(), t.displaySmall.f(), t.headlineLarge.f(), t.headlineMedium.f(),
                    t.headlineSmall.f(), t.titleLarge.f(), t.titleMedium.f(), t.titleSmall.f(), t.bodyLarge.f(), t.bodyMedium.f(), t.bodySmall.f(),
                    t.labelLarge.f(), t.labelMedium.f(), t.labelSmall.f())
            }
        }
        MaterialTheme(colorScheme = scheme, typography = typography) { content() }
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    val theme = LocalUltraTheme.current
    Column(modifier.padding(bottom = 12.dp)) {
        Text(text, color = theme.palette.text, fontWeight = FontWeight.Bold, fontSize = 32.sp)
        if (subtitle != null) Text(subtitle, color = theme.palette.textDim, fontSize = 14.sp)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val theme = LocalUltraTheme.current
    Text(text, modifier = modifier.padding(start = 2.dp, top = 18.dp, bottom = 6.dp), color = theme.palette.text,
        fontWeight = FontWeight.Bold, fontSize = 20.sp)
}

/** true = Performance-Modus (schlanke Darstellung, keine Dauer-Animationen). */
val LocalPerfMode = androidx.compose.runtime.staticCompositionLocalOf { true }

@Composable
private fun Modifier.lightFocus(focused: Boolean, scale: Float, scaleOnFocus: Boolean): Modifier {
    // Nur ein Wert, nur in der Zeichenphase gelesen: keine Layout-Messung, keine Neukomposition pro Frame
    val s by androidx.compose.animation.core.animateFloatAsState(if (focused && scaleOnFocus) scale else 1f, tween(110), label = "f")
    return this.graphicsLayer { scaleX = s; scaleY = s }
}

/** Gemeinsame Fokus-Logik: Skalierung, Leuchtrand und Fokus-Klang. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tvFocusable(
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    scaleOnFocus: Boolean = true,
    onFocus: ((Boolean) -> Unit)? = null,
): Modifier {
    val theme = LocalUltraTheme.current
    val sounds = LocalUiSounds.current
    var focused by remember { mutableStateOf(false) }
    val p = theme.palette
    // CRAZY MOTION ENGINE: Navigationseffekt (Magnetic, 3D Tilt, Elastic …) für jedes fokussierbare Element
    val light = LocalPerfMode.current || !de.ostlertekker.ultraiptv.core.motion.LocalMotion.current.enabled
    val base = (if (light) this.lightFocus(focused, theme.spec.focusScale, scaleOnFocus) else this.crazyFocus(focused, theme.spec.focusScale, scaleOnFocus))
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged {
            if (it.isFocused && !focused) sounds?.focus()
            focused = it.isFocused
            onFocus?.invoke(it.isFocused)
        }
        .then(if (onLongClick == null) Modifier else Modifier.longPressKeys(onLongClick))
        .combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = { sounds?.select(); onClick() },
            onLongClick = onLongClick,
        )
        .clip(shape)
    // Moderner, ruhiger Fokus wie bei Apple TV: sanftes Anheben + klarer heller Rand
    return if (focused) base.border(2.5.dp, Color.White.copy(alpha = 0.92f), shape)
    else base
}

/**
 * Einheitlicher Button-Stil der ganzen App: transparent mit weißem Rahmen; Hauptaktion/ausgewählt mit kräftigerem Rahmen;
 * der fokussierte Button wird weiß gefüllt (sichtbarer Fokus für die Fernbedienung).
 */
fun Modifier.outlineButton(focused: Boolean, primary: Boolean, enabled: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.background(if (focused && enabled) Color.White else Color.Transparent, shape)
        .border(if (primary) 2.dp else 1.5.dp,
            Color.White.copy(alpha = when { !enabled -> 0.2f; focused -> 1f; primary -> 0.9f; else -> 0.55f }), shape)

/** Textfarbe passend zu [outlineButton]. */
fun outlineButtonText(focused: Boolean, enabled: Boolean): Color =
    when { !enabled -> Color.White.copy(alpha = 0.4f); focused -> Color(0xFF111111); else -> Color.White }

/**
 * Kleines „PRO“-Abzeichen neben dem Schriftzug – Stil, Größe, Position und Animation in
 * Einstellungen › Erscheinungsbild › PRO-Abzeichen.
 */
@Composable
fun ProBadge(modifier: Modifier = Modifier) {
    val p = LocalUltraTheme.current.palette
    val prefs = de.ostlertekker.ultraiptv.core.design.LocalUiPrefs.current
    if (prefs.proStyle == 0) return
    val fs = when (prefs.proSize) { 1 -> 7.5.sp; 2 -> 9.sp; else -> 6.sp }
    val raise = if (prefs.proPosition == 0) Modifier.offset(y = (-5).dp) else Modifier
    val shape = RoundedCornerShape(3.dp)
    val dark = Color(0xFF0B0D10)
    val anim = prefs.proAnim

    // Animationstakt (nur wenn eine Animation gewählt ist)
    val t: Float = if (anim == 0) 0f else {
        val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "pro")
        val v by tr.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(if (anim == 1 || anim == 6) 3600 else 2400, easing = androidx.compose.animation.core.LinearEasing)), label = "proT")
        v
    }
    val hue = if (anim == 4) t * 360f else 0f
    fun hsv(h: Float, s: Float = 0.75f, v: Float = 1f) = Color.hsv(((h % 360f) + 360f) % 360f, s, v)

    val gold = listOf(Color(0xFFFFE08A), Color(0xFFD4A017), Color(0xFFFFF3C4), Color(0xFFB8860B))
    val chrome = listOf(Color(0xFFF5F7FA), Color(0xFF9AA3AD), Color(0xFFFFFFFF), Color(0xFF6B737C))
    val fire = listOf(Color(0xFFFFE259), Color(0xFFFF7A18), Color(0xFFE5282E))
    val rainbow = listOf(hsv(hue), hsv(hue + 60), hsv(hue + 120), hsv(hue + 200), hsv(hue + 280))
    val accentShift = if (anim == 4) hsv(hue, 0.55f) else p.primary

    val bg: Brush? = when (prefs.proStyle) {
        1 -> Brush.linearGradient(listOf(accentShift, Color.White))
        2 -> androidx.compose.ui.graphics.SolidColor(accentShift)
        3 -> androidx.compose.ui.graphics.SolidColor(Color.White)
        6 -> Brush.linearGradient(gold)
        7 -> Brush.linearGradient(chrome)
        9 -> androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.18f))
        10 -> Brush.linearGradient(rainbow)
        11 -> Brush.linearGradient(listOf(Color(0xFF2A2D31), Color(0xFF15171A), Color(0xFF2A2D31), Color(0xFF15171A)),
            end = androidx.compose.ui.geometry.Offset(8f, 8f), tileMode = androidx.compose.ui.graphics.TileMode.Repeated)
        12 -> Brush.linearGradient(fire)
        else -> null
    }
    val neon = if (anim == 4) hsv(hue) else p.primary
    val borderBrush: Brush? = when {
        anim == 5 -> Brush.linearGradient(listOf(Color.White, neon, Color.Transparent, neon, Color.White),
            start = androidx.compose.ui.geometry.Offset(t * 120f - 60f, 0f), end = androidx.compose.ui.geometry.Offset(t * 120f, 30f),
            tileMode = androidx.compose.ui.graphics.TileMode.Mirror)
        prefs.proStyle == 4 -> androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.85f))
        prefs.proStyle == 8 -> androidx.compose.ui.graphics.SolidColor(neon)
        prefs.proStyle == 9 -> androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.5f))
        prefs.proStyle == 11 -> androidx.compose.ui.graphics.SolidColor(Color(0xFF55595F))
        else -> null
    }
    val textColor = when (prefs.proStyle) {
        4, 9 -> Color.White; 5 -> accentShift; 8 -> neon; 11 -> Color(0xFFE8EAED); else -> dark
    }
    val pulse = if (anim == 2) 1f + 0.12f * kotlin.math.sin(t * 2f * Math.PI.toFloat()) else 1f
    val glowA = when {
        anim == 3 -> 0.25f + 0.45f * (0.5f + 0.5f * kotlin.math.sin(t * 2f * Math.PI.toFloat()))
        prefs.proStyle == 8 -> 0.35f
        else -> 0f
    }
    val glowColor = if (prefs.proStyle == 6) Color(0xFFFFD34D) else if (prefs.proStyle == 12) Color(0xFFFF7A18) else neon

    Box(
        modifier.then(raise)
            .graphicsLayer {
                scaleX = pulse; scaleY = pulse
                // Nur für den Glanz nötig (Lichtstreif nur auf dem Abzeichen); sonst würde das Leuchten abgeschnitten
                if (anim == 1) compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            }
            .drawBehind {
                // Leuchten: weiche Ringe um das Abzeichen
                if (glowA > 0f) for (k in 1..3) {
                    val g = k * 1.6.dp.toPx()
                    drawRoundRect(glowColor.copy(alpha = glowA / (k + 0.5f)), topLeft = androidx.compose.ui.geometry.Offset(-g, -g),
                        size = androidx.compose.ui.geometry.Size(size.width + 2 * g, size.height + 2 * g),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx() + g))
                }
            }
            .then(if (bg != null) Modifier.background(bg, shape) else Modifier)
            .then(if (borderBrush != null) Modifier.border(1.dp, borderBrush, shape) else Modifier)
            .drawWithContent {
                drawContent()
                // Glanz: Lichtstreif läuft alle paar Sekunden über das Abzeichen
                if (anim == 1) {
                    val x = -size.width + t * size.width * 3f
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.85f), Color.Transparent),
                        start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x + size.width * 0.6f, size.height)),
                        blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop)
                }
                // Funkeln: kleiner Stern oben rechts blitzt auf
                if (anim == 6 && t > 0.7f) {
                    val q = (t - 0.7f) / 0.3f
                    val a = if (q < 0.4f) q / 0.4f else 1f - (q - 0.4f) / 0.6f
                    val r = 2.5.dp.toPx() * (0.5f + q)
                    val c = androidx.compose.ui.geometry.Offset(size.width - 1.dp.toPx(), 1.dp.toPx())
                    drawLine(Color.White.copy(alpha = a), c.copy(x = c.x - r), c.copy(x = c.x + r), strokeWidth = 1.dp.toPx() * 0.8f)
                    drawLine(Color.White.copy(alpha = a), c.copy(y = c.y - r), c.copy(y = c.y + r), strokeWidth = 1.dp.toPx() * 0.8f)
                }
            }
            .padding(horizontal = if (prefs.proStyle == 5 || prefs.proStyle == 8 && borderBrush == null) 0.dp else 3.dp, vertical = 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("PRO", color = textColor, fontSize = fs, lineHeight = fs, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp,
            fontFamily = de.ostlertekker.ultraiptv.core.design.AppFonts.Inter, maxLines = 1)
    }
}

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val shape = RoundedCornerShape(theme.spec.corner.coerceAtMost(28.dp))
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .tvFocusable(shape, onClick = { if (enabled) onClick() }, focusRequester = focusRequester, onFocus = { focused = it })
            .outlineButton(focused, primary, enabled, shape)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Favorit/Merkliste gesetzt: kurzes Aufploppen
        val pop = remember { androidx.compose.animation.core.Animatable(1f) }
        val anim = motionOk()
        var lastText by remember { mutableStateOf(text) }
        LaunchedEffect(text) {
            if (anim && text != lastText && (text.contains('★') || text.contains('♥') || text.startsWith("✓"))) {
                pop.snapTo(1.25f); pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f))
            }
            lastText = text
        }
        Text(text, color = outlineButtonText(focused, enabled), fontWeight = FontWeight.SemiBold,
            fontFamily = theme.spec.fontFamily, fontSize = 16.sp, maxLines = 1,
            modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value })
    }
}

/** Dezente Animationen nur, wenn „Bewegung reduzieren“ aus ist und das Gerät nicht im Sparprofil läuft. */
@Composable
fun motionOk(): Boolean {
    val a = LocalUltraTheme.current.anim
    return !a.reduceMotion && a.profile != AnimationProfile.ECONOMY
}

/** Kleiner pulsierender Punkt (z. B. „LIVE“). */
@Composable
fun PulsingDot(color: Color, size: Dp = 7.dp) {
    val a = if (motionOk()) androidx.compose.animation.core.rememberInfiniteTransition(label = "dot").animateFloat(0.35f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "dotA") else null
    Box(Modifier.size(size).graphicsLayer { alpha = a?.value ?: 1f; scaleX = 0.85f + 0.15f * (a?.value ?: 1f); scaleY = scaleX }
        .background(color, androidx.compose.foundation.shape.CircleShape))
}

@Composable
fun TvChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
           icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .tvFocusable(shape, onClick, onFocus = { focused = it })
            .outlineButton(focused, primary = selected, enabled = true, shape = shape)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) androidx.compose.material3.Icon(icon, contentDescription = null, tint = outlineButtonText(focused, true),
                modifier = Modifier.padding(end = 6.dp).size(16.dp))
            Text(text, color = outlineButtonText(focused, true), fontSize = 14.sp, maxLines = 1,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

/** Poster-/Kachelkarte mit Bild, Titel und optionalem Fortschrittsbalken. */
@Composable
fun FocusCard(
    title: String,
    image: String?,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    contentScale: ContentScale = ContentScale.Crop,
    /** Position in einer Reihe → gestaffelte Eingangsanimation (CRAZY MOTION ENGINE). */
    entranceIndex: Int? = null,
    /** Kleines Statusabzeichen oben links (z. B. Seerr „Verfügbar“) mit Farbe. */
    badge: Pair<String, Color>? = null,
    progressColor: Color? = null,
    /** Abzeichen pulsiert leicht (z. B. „Lädt · 42 %“). */
    badgePulse: Boolean = false,
) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val shape = RoundedCornerShape(theme.spec.corner.coerceAtMost(20.dp))
    var focused by remember { mutableStateOf(false) }
    val anim = motionOk()
    // Lichtschimmer: zieht einmal schräg über das Cover, wenn der Fokus ankommt (nur Zeichenphase, kein Layout)
    val sheen = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(focused) { if (focused && anim) { sheen.snapTo(0f); sheen.animateTo(1f, androidx.compose.animation.core.tween(750)) } }
    val motion = de.ostlertekker.ultraiptv.core.motion.LocalMotion.current
    val entrance = if (entranceIndex != null && motion.cardEntrance) Modifier.crazyEntrance(entranceIndex) else Modifier
    Column(modifier.then(entrance).width(width)) {
        Box(
            Modifier
                .size(width, height)
                .tvFocusable(shape, onClick, onLongClick, onFocus = { focused = it; onFocus?.invoke(it) })
                .background(p.surface, shape)
                .drawWithContent {
                    drawContent()
                    val t = sheen.value
                    if (t < 1f) {
                        val w = size.width; val x = -w * 0.6f + (w * 2.2f) * t
                        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                            start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x + w * 0.5f, size.height)))
                    }
                },
        ) {
            // Platzhalter liegt immer darunter: lädt ein Bild nicht (404, Timeout), bleibt die Kachel trotzdem erkennbar
            var loaded by remember(image) { mutableStateOf(false) }
            var failed by remember(image) { mutableStateOf(false) }
            if (!loaded) {
                // Solange das Bild lädt: sanftes Pulsieren statt starrer Fläche
                val loading = anim && !image.isNullOrBlank() && !failed
                val pulse = if (loading) androidx.compose.animation.core.rememberInfiniteTransition(label = "ph").animateFloat(0.55f, 1f,
                    androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900),
                        androidx.compose.animation.core.RepeatMode.Reverse), label = "phA") else null
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = pulse?.value ?: 1f }
                    .background(Brush.linearGradient(listOf(p.primary.copy(alpha = 0.5f), p.secondary.copy(alpha = 0.25f)))),
                    contentAlignment = Alignment.Center) {
                    Text(initialsOf(title), color = p.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (!image.isNullOrBlank()) {
                // Cover blendet weich ein, sobald es geladen ist
                val fade by androidx.compose.animation.core.animateFloatAsState(if (loaded || !anim) 1f else 0f,
                    androidx.compose.animation.core.tween(320), label = "fade")
                AsyncImage(model = image, contentDescription = title, contentScale = contentScale, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade },
                    onSuccess = { loaded = true; failed = false }, onError = { loaded = false; failed = true })
            }
            if (progress != null && progress > 0f) {
                // Fortschritt füllt sich beim Erscheinen kurz auf seinen Stand
                val fill = remember { androidx.compose.animation.core.Animatable(if (anim) 0f else progress) }
                LaunchedEffect(progress) { fill.animateTo(progress.coerceIn(0f, 1f), androidx.compose.animation.core.tween(if (anim) 700 else 0)) }
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fill.value.coerceIn(0.001f, 1f)).height(4.dp).background(progressColor ?: p.primary))
            }
            badge?.let { (label, color) ->
                val pa = if (badgePulse && anim) androidx.compose.animation.core.rememberInfiniteTransition(label = "bp").animateFloat(0.6f, 1f,
                    androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800),
                        androidx.compose.animation.core.RepeatMode.Reverse), label = "bpA") else null
                Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).graphicsLayer { alpha = pa?.value ?: 1f }
                        .background(color.copy(alpha = 0.85f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
        Text(title, color = if (focused) p.text else p.textDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            // Nur die Farbe wechselt beim Fokus – ein Wechsel der Schriftstärke würde den Text bei jedem Tastendruck neu setzen
            modifier = Modifier.padding(top = 6.dp, start = 2.dp), fontWeight = FontWeight.Medium)
        if (subtitle != null) Text(subtitle, color = p.textDim.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
    }
}

/**
 * Animierte Menü-Typografie. Im GALAXY-Theme: Verlaufsschrift mit wandernden Lichtreflexen,
 * kosmischem Glühen, Sternen rund um den Text und Starburst beim Auswahlwechsel.
 * Andere Themes nutzen dieselbe Engine mit ihren eigenen Effekt-Flags.
 */
@Composable
fun AnimatedMenuLabel(text: String, focused: Boolean, selected: Boolean, modifier: Modifier = Modifier) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val anim = theme.anim
    val fx = theme.spec.menuFx
    val focusProgress by animateFloatAsState(if (focused) 1f else 0f, tween(if (anim.reduceMotion) 0 else 280), label = "menuFocus")
    // Uhr läuft nur für das fokussierte Element; alle Werte werden ausschließlich in der Zeichenphase gelesen
    // → keine Neukomposition pro Frame (deutlich flüssiger auf Fire TV)
    val perf = LocalPerfMode.current
    val clock = rememberAnimationClock(focused && !perf && !anim.reduceMotion && (fx.shimmer || fx.sparkles), fps = 45)
    val burst = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        if (focused && !perf && fx.sparkles && !anim.reduceMotion && anim.profile != AnimationProfile.ECONOMY) {
            burst.snapTo(0f); burst.animateTo(1f, tween(650))
        }
    }
    val emphasis = maxOf(focusProgress, if (selected) 0.65f else 0f)
    val baseColor = lerp(p.textDim, p.text, emphasis)
    val glow = Shadow(p.glow.copy(alpha = (0.75f * focusProgress * fx.glowStrength).coerceIn(0f, 1f)), Offset.Zero, blurRadius = 14f * focusProgress)
    val style = TextStyle(color = if (focusProgress > 0.05f) Color.White else baseColor, fontFamily = theme.spec.fontFamily,
        fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
        // Längere deutsche Begriffe (z. B. EINSTELLUNGEN) passen so in die Menüleiste
        fontSize = (when { text.length > 11 -> 14f; text.length > 8 -> 16f; else -> 18f } *
            (if (theme.spec.fontFamily == de.ostlertekker.ultraiptv.core.design.AppFonts.PressStart) 0.62f else 1f)).sp, letterSpacing = if (text.length > 11) 0.5.sp else 2.sp, shadow = glow)
    val shimmer = fx.shimmer && focused
    Box(
        modifier.drawBehind {
            val fp = focusProgress
            if (fp > 0.01f) {
                val t = clock.value
                drawRoundRect(
                    Brush.radialGradient(listOf(p.glow.copy(alpha = 0.22f * fp), Color.Transparent), center, size.width * 0.75f),
                    cornerRadius = CornerRadius(size.height / 2),
                )
                if (fx.sparkles) {
                    val n = if (anim.profile == AnimationProfile.ECONOMY) 4 else 8
                    for (i in 0 until n) {
                        val ang = t * 0.55f + i * (2f * PI.toFloat() / n)
                        val c = Offset(center.x + cos(ang) * (size.width / 2 + 10f), center.y + sin(ang) * (size.height / 2 + 6f))
                        val tw = 0.5f + 0.5f * sin(t * 3.1f + i * 1.7f)
                        drawCircle(p.particle.copy(alpha = fp * tw * 0.9f), 1.2f + tw * 1.6f, c)
                    }
                }
            }
            val b = burst.value
            if (b > 0f && b < 1f) {
                for (i in 0 until 10) {
                    val ang = i * (2f * PI.toFloat() / 10f)
                    val r0 = size.height * 0.3f + b * size.height * 0.4f
                    val r1 = r0 + 10f * (1f - b)
                    drawLine(p.particle.copy(alpha = (1f - b) * 0.9f), Offset(center.x + cos(ang) * r0 * 2.2f, center.y + sin(ang) * r0),
                        Offset(center.x + cos(ang) * r1 * 2.2f, center.y + sin(ang) * r1), 1.6f)
                }
            }
        }.padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = style, maxLines = 1, modifier = if (!shimmer) Modifier else Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // Wandernder Verlaufs-Glanz nur auf den Buchstaben (SrcIn), berechnet in der Zeichenphase
                val sweep = fract(clock.value * 0.22f) * (size.width + 400f) - 200f
                drawRect(Brush.linearGradient(listOf(p.primary, Color.White, p.secondary, p.primary),
                    start = Offset(sweep, 0f), end = Offset(sweep + 260f, size.height), tileMode = TileMode.Mirror), blendMode = BlendMode.SrcIn)
            })
    }
}

data class NavItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector? = null)

/** Hauptnavigation links – vollständig per D-Pad bedienbar. */
@Composable
fun NavRail(items: List<NavItem>, selectedRoute: String, onSelect: (String) -> Unit, firstFocus: FocusRequester, modifier: Modifier = Modifier) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    Column(
        modifier
            .width(200.dp)
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(p.bgTop.copy(alpha = 0.92f), p.bgTop.copy(alpha = 0.55f), Color.Transparent)))
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(vertical = 18.dp, horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Animiertes App-Logo: giftiger Planet, der regelmäßig explodiert und sich neu formt
        ToxicPlanetLogo(Modifier.padding(start = 4.dp).size(58.dp), running = theme.anim.backgroundMotion,
            detail = theme.anim.profile != AnimationProfile.ECONOMY)
        Text("OSTLERTEKKER", color = p.textDim, fontSize = 11.sp, letterSpacing = 4.sp, modifier = Modifier.padding(start = 10.dp))
        Text("ULTRA IPTV", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, fontFamily = theme.spec.fontFamily,
            style = TextStyle(brush = Brush.linearGradient(listOf(p.primary, Color.White, p.secondary))), letterSpacing = 2.sp,
            modifier = Modifier.padding(start = 10.dp, bottom = if (de.ostlertekker.ultraiptv.core.design.LocalUiPrefs.current.menuClock) 2.dp else 12.dp))
        if (de.ostlertekker.ultraiptv.core.design.LocalUiPrefs.current.menuClock) RailClock(Modifier.padding(start = 10.dp, bottom = 10.dp))
        items.forEachIndexed { index, item ->
            var focused by remember { mutableStateOf(false) }
            val selected = item.route == selectedRoute
            val shape = RoundedCornerShape(theme.spec.corner.coerceAtMost(22.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .crazyEntrance(index)
                    .tvFocusable(shape, onClick = { onSelect(item.route) }, focusRequester = if (index == 0) firstFocus else null,
                        scaleOnFocus = false, onFocus = { focused = it })
                    .background(if (focused) p.surfaceFocused else if (selected) p.surface else Color.Transparent, shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(if (selected || focused) p.primary else Color.Transparent, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    AnimatedMenuLabel(item.label, focused, selected)
                }
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, value: Boolean, onToggle: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    SettingRowBase(title, subtitle, if (value) "AN" else "AUS", highlight = value && enabled, enabled = enabled) { if (enabled) onToggle(!value) }
}

@Composable
fun ChoiceRow(title: String, value: String, onClick: () -> Unit, subtitle: String? = null) {
    SettingRowBase(title, subtitle, value, highlight = false, enabled = true, onClick = onClick)
}

@Composable
fun ActionRow(title: String, onClick: () -> Unit, subtitle: String? = null, value: String = "›") {
    SettingRowBase(title, subtitle, value, highlight = false, enabled = true, onClick = onClick)
}

@Composable
private fun SettingRowBase(title: String, subtitle: String?, value: String, highlight: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(theme.spec.corner.coerceAtMost(16.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .tvFocusable(shape, onClick, scaleOnFocus = false, onFocus = { focused = it })
            .background(if (focused) p.surfaceFocused else p.surface, shape)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) p.text else p.textDim, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = p.textDim, fontSize = 12.sp)
        }
        Text(value, color = if (highlight) p.primary else p.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
fun InfoRow(label: String, value: String, accuracy: Accuracy) {
    val p = LocalUltraTheme.current.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        // Kurze Marker (z. B. „•“) brauchen keine breite Beschriftungsspalte
        Text(label, color = p.textDim, fontSize = 14.sp,
            modifier = if (label.length <= 2) Modifier.width(22.dp) else Modifier.fillMaxWidth(0.36f).widthIn(max = 280.dp))
        Text(value, color = p.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        val badge = when (accuracy) {
            Accuracy.MEASURED -> Color(0xFF2EE59D)
            Accuracy.ESTIMATED -> Color(0xFFFFC94D)
            Accuracy.UNAVAILABLE -> Color(0xFF8A8A8A)
        }
        Text(accuracy.label, color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp).background(badge, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** Theme-abhängige Ladeanzeige. */
@Composable
fun UltraLoader(modifier: Modifier = Modifier, label: String? = null) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val inf = rememberInfiniteTransition(label = "loader")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "t")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).drawBehind {
            when (theme.spec.loader) {
                LoaderStyle.ORBIT -> for (i in 0 until 6) {
                    val a = (t + i / 6f) * 2f * PI.toFloat()
                    drawCircle(p.primary.copy(alpha = 1f - i / 7f), 4f + (6 - i), Offset(center.x + cos(a) * size.width / 2.6f, center.y + sin(a) * size.height / 2.6f))
                }
                LoaderStyle.PULSE -> {
                    drawCircle(p.primary.copy(alpha = 1f - t), size.minDimension / 2 * t)
                    drawCircle(p.secondary, size.minDimension / 6)
                }
                LoaderStyle.BARS -> for (i in 0 until 5) {
                    val h = size.height * (0.3f + 0.7f * (0.5f + 0.5f * sin((t * 2f * PI.toFloat()) + i)))
                    drawRect(p.primary, topLeft = Offset(i * size.width / 5f + 3f, (size.height - h) / 2), size = androidx.compose.ui.geometry.Size(size.width / 5f - 6f, h))
                }
            }
            Unit
        })
        if (label != null) Text(label, color = p.textDim, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

/** Texteingabe, die auf Fire TV die Bildschirmtastatur der Fernbedienung öffnet. */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    number: Boolean = false,
    error: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val p = LocalUltraTheme.current.palette
    // TV-Bedienung: Fokus auf dem Feld öffnet noch keine Tastatur – erst OK startet die Eingabe.
    // „Fertig“ auf der Tastatur oder Zurück beendet sie wieder, der Fokus bleibt auf dem Feld.
    val outer = focusRequester ?: remember { FocusRequester() }
    val inner = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var hover by remember { mutableStateOf(false) }
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) { if (editing) { runCatching { inner.requestFocus() }; kb?.show() } }
    fun stopEditing() { kb?.hide(); editing = false; runCatching { outer.requestFocus() } }
    Column(modifier
        .focusRequester(outer)
        .onFocusChanged { hover = it.isFocused }
        .focusProperties { canFocus = !editing }
        .focusable()
        .onKeyEvent { ev ->
            val c = ev.nativeKeyEvent.keyCode
            if (!editing && ev.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP &&
                (c == android.view.KeyEvent.KEYCODE_DPAD_CENTER || c == android.view.KeyEvent.KEYCODE_ENTER || c == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                editing = true; true
            } else false
        }) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true,
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { stopEditing() }, onGo = { stopEditing() },
                onSearch = { stopEditing() }, onNext = { stopEditing() }),
            isError = error != null,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = when {
                number && password -> KeyboardType.NumberPassword
                number -> KeyboardType.Number
                password -> KeyboardType.Password
                else -> KeyboardType.Uri
            }, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = p.text, unfocusedTextColor = p.text, focusedBorderColor = p.primary,
                // Feld ausgewählt (noch nicht in Eingabe): kräftiger Rahmen als Fokus-Anzeige
                unfocusedBorderColor = if (hover) Color.White else p.textDim.copy(alpha = 0.5f),
                focusedLabelColor = p.primary, unfocusedLabelColor = if (hover) Color.White else p.textDim,
                cursorColor = p.primary,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(inner)
                .focusProperties { canFocus = editing }
                .onFocusChanged { if (!it.isFocused && editing) { editing = false; kb?.hide() } }
                .onPreviewKeyEvent { ev ->
                    if (editing && ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                        if (ev.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP) stopEditing(); true
                    } else false
                },
        )
        if (error != null) Text(error, color = Color(0xFFFF6B6B), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
    }
}

/** Auswahl-Dialog mit Aktionen (z. B. lange OK auf einem Favoriten). Zurück schließt. */
@Composable
fun ActionDialog(title: String, actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val p = LocalUltraTheme.current.palette
    val focus = remember { FocusRequester() }
    androidx.activity.compose.BackHandler { onDismiss() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp).background(p.bgBottom, RoundedCornerShape(20.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.padding(bottom = 6.dp))
            actions.forEachIndexed { i, (label, act) ->
                TvButton(label, onClick = { act(); onDismiss() }, modifier = Modifier.fillMaxWidth(), focusRequester = if (i == 0) focus else null)
            }
            TvButton("Abbrechen", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** Overlay-Dialog für die PIN-Abfrage. */
@Composable
fun PinDialog(title: String, onSubmit: (String) -> Boolean, onDismiss: () -> Unit) {
    val p = LocalUltraTheme.current.palette
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp).background(p.bgBottom, RoundedCornerShape(20.dp)).padding(28.dp)) {
            Text(title, color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            TvTextField(pin, { v -> pin = v.filter { it.isDigit() }.take(4); error = null }, "PIN (4 Ziffern)", password = true, number = true,
                error = error, focusRequester = focus)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Bestätigen", onClick = { if (!onSubmit(pin)) { error = "Falsche PIN"; pin = "" } }, primary = true)
                TvButton("Abbrechen", onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}


/** Uhrzeit im gewählten Format (24 h oder 12 h mit AM/PM). */
fun formatClock(ms: Long, h24: Boolean): String =
    java.text.SimpleDateFormat(if (h24) "HH:mm" else "h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ms))

@Composable
private fun RailClock(modifier: Modifier, small: Boolean = false) {
    val p = LocalUltraTheme.current.palette
    val h24 = de.ostlertekker.ultraiptv.core.design.LocalUiPrefs.current.clock24h
    var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(20_000) } }
    Text(formatClock(now, h24), color = p.text, fontSize = if (small) 15.sp else 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
        modifier = if (small) modifier.widthIn(min = 50.dp) else modifier)
}


/** Kürzel für Platzhalter: „Testsender 8 HD“ → „T8“, „Das Erste“ → „DE“. */
fun initialsOf(name: String): String {
    val words = name.replace(Regex("""\b(HD|FHD|UHD|4K|SD|HEVC|H265)\b""", RegexOption.IGNORE_CASE), "")
        .split(' ', '-', '_', '|', ':').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> name.take(2).uppercase()
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/**
 * Hauptnavigation oben im Apple-TV-Stil: Schriftzug links, Reiter als Pillen in der Mitte,
 * Suche/Einstellungen/Uhr rechts. Fokus = weiße Pille mit dunkler Schrift, aktive Seite = dezente Pille.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopNavBar(items: List<NavItem>, utility: List<NavItem>, selectedRoute: String, onSelect: (String) -> Unit, firstFocus: FocusRequester,
              modifier: Modifier = Modifier, weatherCode: Int? = null, weatherTemp: Int? = null) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    // Links/Rechts in der Menüzeile fest verdrahtet (nicht über die automatische Fokussuche): so bleibt die Zeile
    // immer bedienbar, egal aus welcher Seite (z. B. Jellyfin) man nach oben gekommen ist
    val all = items.size + utility.size
    val reqs = remember(all) { List(all) { FocusRequester() } }
    fun nav(i: Int): Modifier = Modifier.focusRequester(reqs[i]).onPreviewKeyEvent { ev ->
        if (ev.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
        when (ev.nativeKeyEvent.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { if (i > 0) runCatching { reqs[i - 1].requestFocus() }; true }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { if (i < all - 1) runCatching { reqs[i + 1].requestFocus() }; true }
            else -> false
        }
    }
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Black.copy(alpha = 0.35f), Color.Transparent)))
            .padding(start = 22.dp, end = 18.dp, top = 14.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Animiertes Wetter links neben dem Schriftzug (Ort in Einstellungen › Allgemein)
        if (weatherCode != null && weatherTemp != null) {
            de.ostlertekker.ultraiptv.core.weather.AnimatedWeatherIcon(weatherCode, 30.dp)
            Text("$weatherTemp°", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                modifier = Modifier.padding(start = 4.dp, end = 12.dp))
        }
        Text("ULTRA IPTV", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            fontFamily = de.ostlertekker.ultraiptv.core.design.AppFonts.Inter, maxLines = 1)
        ProBadge(Modifier.padding(start = 3.dp))
        Spacer(Modifier.weight(1f))
        // Fokus-Markierung gleitet weich von Menüpunkt zu Menüpunkt (statt zu springen)
        val glide = motionOk()
        val bounds = remember(items.size) { arrayOfNulls<androidx.compose.ui.geometry.Rect>(items.size) }
        var focusedIdx by remember { mutableStateOf(-1) }
        var lastIdx by remember { mutableStateOf(0) }
        if (focusedIdx >= 0) lastIdx = focusedIdx
        val target = bounds.getOrNull(lastIdx)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val ix by androidx.compose.animation.core.animateFloatAsState(target?.left ?: 0f, androidx.compose.animation.core.spring(0.8f, 600f), label = "ix")
        val iw by androidx.compose.animation.core.animateFloatAsState(target?.width ?: 0f, androidx.compose.animation.core.spring(0.8f, 600f), label = "iw")
        val ia by androidx.compose.animation.core.animateFloatAsState(if (focusedIdx >= 0 && target != null) 1f else 0f,
            androidx.compose.animation.core.tween(150), label = "ia")
        Box(Modifier.padding(start = 8.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50)).padding(3.dp)) {
            if (glide) Box(Modifier.matchParentSize().drawBehind {
                if (ia > 0f && iw > 0f) drawRoundRect(Color.White.copy(alpha = ia), topLeft = androidx.compose.ui.geometry.Offset(ix, 0f),
                    size = androidx.compose.ui.geometry.Size(iw, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f))
            })
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                items.forEachIndexed { i, item ->
                    val focusHere = if (items.any { it.route == selectedRoute }) item.route == selectedRoute else i == 0
                    TopNavPill(item.label, item.route == selectedRoute, { onSelect(item.route) }, if (focusHere) firstFocus else null, nav(i),
                        glide = glide,
                        onFocusIdx = { f -> if (f) focusedIdx = i else if (focusedIdx == i) focusedIdx = -1 },
                        onBounds = { r -> bounds[i] = r })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            utility.forEachIndexed { j, item ->
                val m = nav(items.size + j)
                if (item.icon != null) TopNavIconPill(item.icon, item.label, item.route == selectedRoute, m) { onSelect(item.route) }
                else TopNavPill(item.label, item.route == selectedRoute, { onSelect(item.route) }, null, m)
            }
            if (de.ostlertekker.ultraiptv.core.design.LocalUiPrefs.current.menuClock) RailClock(Modifier.padding(start = 8.dp), small = true)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopNavPill(label: String, selected: Boolean, onClick: () -> Unit, focusRequester: FocusRequester?, navModifier: Modifier = Modifier,
                       glide: Boolean = false, onFocusIdx: (Boolean) -> Unit = {}, onBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {}) {
    val sounds = LocalUiSounds.current
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.06f else 1f, androidx.compose.animation.core.tween(140), label = "pill")
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(navModifier)
            .then(entryDown())
            .onGloballyPositioned { c -> c.parentLayoutCoordinates?.let { p -> onBounds(p.localBoundingBoxOf(c, false)) } }
            .onFocusChanged { if (it.isFocused && !focused) sounds?.focus(); focused = it.isFocused; onFocusIdx(it.isFocused) }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = { sounds?.select(); onClick() })
            .background(
                when { focused && !glide -> Color.White; focused -> Color.Transparent; selected -> Color.White.copy(alpha = 0.18f); else -> Color.Transparent }, shape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (focused) Color(0xFF111111) else if (selected) Color.White else Color.White.copy(alpha = 0.72f),
            fontSize = 13.sp, fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}


/** Rundes Symbol-Element der Navigationsleiste (Suche, Einstellungen) – alle exakt gleich groß und gleich gestaltet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopNavIconPill(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, selected: Boolean, navModifier: Modifier = Modifier, onClick: () -> Unit) {
    val sounds = LocalUiSounds.current
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.06f else 1f, androidx.compose.animation.core.tween(140), label = "iconpill")
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(30.dp)
            .then(navModifier)
            .then(entryDown())
            .onFocusChanged { if (it.isFocused && !focused) sounds?.focus(); focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = { sounds?.select(); onClick() })
            .background(when { focused -> Color.White; selected -> Color.White.copy(alpha = 0.18f); else -> Color.Transparent }, shape),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = description, modifier = Modifier.size(17.dp),
            tint = if (focused) Color(0xFF111111) else if (selected) Color.White else Color.White.copy(alpha = 0.72f))
    }
}

/** „★ 7,4“ – schnell ohne String.format (wird für jede Kachel beim Scrollen gebraucht). */
fun ratingLabel(r: Double): String {
    val t = Math.round(r * 10).toInt()
    return "★ ${t / 10},${t % 10}"
}

/**
 * Langes Drücken der OK-Taste auf der Fernbedienung zuverlässig erkennen (Compose erkennt es auf vielen TV-Geräten nicht):
 * wiederholte Tastensignale beim Halten oder ≥ 500 ms gedrückt → onLongClick, der normale Klick entfällt dann.
 * Zusätzlich öffnet die Menü-Taste (☰) dieselbe Aktion.
 */
@Composable
private fun Modifier.longPressKeys(onLongClick: () -> Unit): Modifier {
    val state = remember { longArrayOf(0L, 0L) } // [Zeitpunkt KeyDown, 1 = lang ausgelöst]
    return this.onPreviewKeyEvent { ev ->
        val code = ev.nativeKeyEvent.keyCode
        if (code == android.view.KeyEvent.KEYCODE_MENU) {
            if (ev.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP) onLongClick()
            return@onPreviewKeyEvent true
        }
        if (code != android.view.KeyEvent.KEYCODE_DPAD_CENTER && code != android.view.KeyEvent.KEYCODE_ENTER && code != android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            return@onPreviewKeyEvent false
        val ne = ev.nativeKeyEvent
        when (ne.action) {
            android.view.KeyEvent.ACTION_DOWN -> {
                if (ne.repeatCount == 0) { state[0] = android.os.SystemClock.uptimeMillis(); state[1] = 0L; false }
                else {
                    // Gedrückt halten → wiederholte Signale
                    if (state[1] == 0L && (ne.isLongPress || ne.repeatCount >= 2)) { state[1] = 1L; onLongClick() }
                    true
                }
            }
            android.view.KeyEvent.ACTION_UP -> {
                // Nur werten, wenn das zugehörige Drücken hier auch angekommen ist (sonst z. B. „Ablegen“ im Bearbeitungsmodus fälschlich als langes OK)
                val down = state[0]; state[0] = 0L
                if (state[1] == 1L) { state[1] = 0L; true } // lang bereits ausgelöst → kein Klick
                else if (down > 0 && android.os.SystemClock.uptimeMillis() - down >= 500) { onLongClick(); true }
                else false
            }
            else -> false
        }
    }
}

/** Menüleiste: Pfeil runter springt zum angemeldeten Einstieg der Seite (falls vorhanden). */
@Composable
private fun entryDown(): Modifier {
    val e = LocalContentEntry.current
    return if (e.attached) Modifier.focusProperties { down = e.requester } else Modifier
}

/** Platz, den ein fokussiertes (vergrößertes) Element samt Rahmen seitlich bzw. oben/unten braucht. */
val FocusBleedH = 18.dp
val FocusBleedV = 14.dp

/**
 * Für Reihen und Raster: vergrößert den Zeichenbereich (und damit den Clip der Lazy-Liste) um [h]/[v] in jede Richtung,
 * ohne das Layout zu verschieben. Zusammen mit gleich großem contentPadding bleibt die Position der Kacheln gleich,
 * aber vergrößerte Kacheln und ihr Fokusrahmen werden am Rand nicht mehr abgeschnitten.
 */
fun Modifier.focusBleed(h: Dp = FocusBleedH, v: Dp = FocusBleedV): Modifier = this.layout { measurable, c ->
    val hp = h.roundToPx(); val vp = v.roundToPx()
    val wc = if (c.hasBoundedWidth) c.copy(minWidth = c.minWidth + 2 * hp, maxWidth = c.maxWidth + 2 * hp) else c
    val cc = if (c.hasBoundedHeight) wc.copy(minHeight = c.minHeight + 2 * vp, maxHeight = c.maxHeight + 2 * vp) else wc
    val p = measurable.measure(cc)
    val w = (p.width - 2 * hp).coerceAtLeast(0); val hh = (p.height - 2 * vp).coerceAtLeast(0)
    layout(w, hh) { p.place(-hp, -vp) }
}
