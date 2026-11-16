package de.ostlertekker.ultraiptv.core.ui

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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.ostlertekker.ultraiptv.core.animation.drawShootingStars
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
    val prefs = remember(settings) { de.ostlertekker.ultraiptv.core.design.UiPrefs.from(settings) }
    CompositionLocalProvider(LocalUltraTheme provides theme, LocalUiSounds provides sounds,
        de.ostlertekker.ultraiptv.core.motion.LocalMotion provides motion,
        de.ostlertekker.ultraiptv.core.design.LocalUiPrefs provides prefs,
        LocalPerfMode provides true,
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density * scale, baseDensity.fontScale)) {
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
            .background(
                when {
                    !enabled -> Color.White.copy(alpha = 0.04f)
                    focused -> p.primary
                    primary -> p.primary.copy(alpha = 0.35f)
                    else -> p.surface
                }, shape,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (focused) Color.Black else if (enabled) p.text else p.textDim, fontWeight = FontWeight.SemiBold,
            fontFamily = theme.spec.fontFamily, fontSize = 16.sp, maxLines = 1)
    }
}

@Composable
fun TvChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .tvFocusable(shape, onClick, onFocus = { focused = it })
            .background(if (focused) p.primary else if (selected) p.primary.copy(alpha = 0.32f) else p.surface, shape)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(text, color = if (focused) Color.Black else p.text, fontSize = 14.sp, maxLines = 1,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
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
) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val shape = RoundedCornerShape(theme.spec.corner.coerceAtMost(20.dp))
    var focused by remember { mutableStateOf(false) }
    val motion = de.ostlertekker.ultraiptv.core.motion.LocalMotion.current
    val entrance = if (entranceIndex != null && motion.cardEntrance) Modifier.crazyEntrance(entranceIndex) else Modifier
    Column(modifier.then(entrance).width(width)) {
        Box(
            Modifier
                .size(width, height)
                .tvFocusable(shape, onClick, onLongClick, onFocus = { focused = it; onFocus?.invoke(it) })
                .background(p.surface, shape),
        ) {
            // Platzhalter liegt immer darunter: lädt ein Bild nicht (404, Timeout), bleibt die Kachel trotzdem erkennbar
            var loaded by remember(image) { mutableStateOf(false) }
            if (!loaded) {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(p.primary.copy(alpha = 0.5f), p.secondary.copy(alpha = 0.25f)))),
                    contentAlignment = Alignment.Center) {
                    Text(initialsOf(title), color = p.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (!image.isNullOrBlank()) {
                AsyncImage(model = image, contentDescription = title, contentScale = contentScale, modifier = Modifier.fillMaxSize(),
                    onSuccess = { loaded = true }, onError = { loaded = false })
            }
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(p.primary))
            }
        }
        Text(title, color = if (focused) p.text else p.textDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp), fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal)
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

data class NavItem(val route: String, val label: String)

/** Hauptnavigation links – vollständig per D-Pad bedienbar. */
@Composable
fun NavRail(items: List<NavItem>, selectedRoute: String, onSelect: (String) -> Unit, firstFocus: FocusRequester, modifier: Modifier = Modifier) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val railClock = rememberAnimationClock(!LocalPerfMode.current && theme.spec.menuFx.shootingStars && theme.anim.backgroundMotion &&
        theme.anim.profile != AnimationProfile.ECONOMY, fps = 30)
    Column(
        modifier
            .width(200.dp)
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(p.bgTop.copy(alpha = 0.92f), p.bgTop.copy(alpha = 0.55f), Color.Transparent)))
            .drawBehind {
                if (theme.spec.menuFx.shootingStars) drawShootingStars(p.particle, railClock.value * 1.3f + 3f, 1)
            }
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
    Column(modifier) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true,
            isError = error != null,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = when {
                number && password -> KeyboardType.NumberPassword
                number -> KeyboardType.Number
                password -> KeyboardType.Password
                else -> KeyboardType.Uri
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = p.text, unfocusedTextColor = p.text, focusedBorderColor = p.primary,
                unfocusedBorderColor = p.textDim.copy(alpha = 0.5f), focusedLabelColor = p.primary, unfocusedLabelColor = p.textDim,
                cursorColor = p.primary,
            ),
            modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        )
        if (error != null) Text(error, color = Color(0xFFFF6B6B), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
    }
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
        modifier = if (small) modifier.widthIn(min = 46.dp) else modifier)
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
              modifier: Modifier = Modifier) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Black.copy(alpha = 0.35f), Color.Transparent)))
            .padding(start = 28.dp, end = 24.dp, top = 14.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("OSTLERTEKKER", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            fontFamily = de.ostlertekker.ultraiptv.core.design.AppFonts.Inter, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Row(Modifier.padding(start = 16.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50)).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { i, item ->
                val focusHere = if (items.any { it.route == selectedRoute }) item.route == selectedRoute else i == 0
                TopNavPill(item.label, item.route == selectedRoute, { onSelect(item.route) }, if (focusHere) firstFocus else null)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            utility.forEach { item -> TopNavPill(item.label, item.route == selectedRoute, { onSelect(item.route) }, null) }
            if (de.ostlertekker.ultraiptv.core.design.LocalUiPrefs.current.menuClock) RailClock(Modifier.padding(start = 8.dp), small = true)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopNavPill(label: String, selected: Boolean, onClick: () -> Unit, focusRequester: FocusRequester?) {
    val sounds = LocalUiSounds.current
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.06f else 1f, androidx.compose.animation.core.tween(140), label = "pill")
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused && !focused) sounds?.focus(); focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = { sounds?.select(); onClick() })
            .background(
                when { focused -> Color.White; selected -> Color.White.copy(alpha = 0.18f); else -> Color.Transparent }, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (focused) Color(0xFF111111) else if (selected) Color.White else Color.White.copy(alpha = 0.72f),
            fontSize = 14.sp, fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}
