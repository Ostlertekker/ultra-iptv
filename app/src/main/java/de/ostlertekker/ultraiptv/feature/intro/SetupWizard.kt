package de.ostlertekker.ultraiptv.feature.intro

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.motionOk
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Wie sich Ulli (das Fernseher-Maskottchen) in einem Schritt verhält. */
private enum class Mood { WAVE, TALK, POINT, THINK, CHEER }

private data class WizardStep(
    val icon: String,
    val title: String,
    val speech: String,
    val points: List<Pair<String, String>>,
    val mood: Mood,
)

private fun steps(hasProvider: Boolean) = listOf(
    WizardStep("👋", "Willkommen bei ULTRA IPTV",
        "Hi, ich bin Ulli! Ich zeig dir in einer Minute die wichtigsten Funktionen. Keine Lust? Dann einfach „Überspringen“.",
        listOf("📺" to "Live-TV, Filme und Serien deines Anbieters", "🎬" to "Jellyfin, Radio, YouTube und Browser – alles in der App",
            "⚡" to "Schnelles Umschalten und ein übersichtliches TV-Programm"), Mood.WAVE),
    WizardStep("🎮", "Die Fernbedienung",
        "Alles geht mit der Fernbedienung. Hier die wichtigsten Tasten im Player:",
        listOf("▲ ▼" to "Im Live-TV den Sender wechseln – sofort", "OK" to "Steuerleiste einblenden",
            "◀ ▶" to "Bei Filmen und Serien spulen", "0–9" to "Sendernummer direkt eingeben", "⟲" to "Zurück schließt Menüs und den Player"), Mood.POINT),
    WizardStep("📡", "Live-TV & TV-Programm",
        "Unter „Live-TV“ findest du alle Sender und das TV-Programm. Da kannst du dir auch Erinnerungen stellen.",
        listOf("🗂" to "Sender nach Gruppen sortiert", "🗓" to "TV-Programm: mit ◀ ▶ am Rand durch die Zeit blättern",
            "🔔" to "Künftige Sendung auswählen → Erinnerung setzen", "⏮" to "Vorherige-Taste: zurück zum letzten Sender"), Mood.TALK),
    WizardStep("🍿", "Filme & Serien",
        "Bei Filmen und Serien merkt sich die App, wo du warst. Die Reihen laden beim Scrollen einfach weiter nach.",
        listOf("▶" to "Weiterschauen, wo du aufgehört hast", "♥" to "Favoriten und Merkliste",
            "∞" to "Reihen laden beim Scrollen automatisch nach", "📺" to "Serien: nächste Folge startet von selbst"), Mood.POINT),
    WizardStep("🧩", "Noch mehr im Menü",
        "Oben im Menü steckt noch mehr. Alles läuft direkt in der App – es öffnet sich keine fremde App.",
        listOf("🟣" to "Jellyfin: deine eigene Mediathek", "📻" to "Radio", "▶" to "YouTube und Browser",
            "🔍" to "Suche oben rechts – auch per Spracheingabe"), Mood.TALK),
    WizardStep("🛠", "Tools",
        "Hakt mal etwas? Unter „Tools“ findest du Helfer, die das Problem eingrenzen.",
        listOf("🚀" to "Speedtest – reicht die Leitung für HD oder 4K?", "✅" to "Verbindungscheck: Internet, DNS, Anbieter, Jellyfin",
            "🩺" to "App-Diagnose mit Umschalt-Zeiten"), Mood.THINK),
    WizardStep("⚙", "Einstellungen",
        "In den Einstellungen passt du die App an dich an. Diesen Assistenten kannst du dort auch erneut starten.",
        listOf("🎨" to "Design, Hintergrund und Töne", "👤" to "Profile für mehrere Personen",
            "⬆" to "App- und System-Updates", "🎓" to "Allgemein › Einrichtungsassistent erneut zeigen"), Mood.TALK),
    WizardStep("🎉", "Alles klar!",
        if (hasProvider) "Das war's schon! Viel Spaß mit ULTRA IPTV – ich bin dann mal still."
        else "Das war's schon! Als Nächstes richtest du deine Quelle ein – dein Anbieter gibt dir dafür die Zugangsdaten.",
        if (hasProvider) listOf("✓" to "Deine Quelle ist schon eingerichtet")
        else listOf("1" to "Xtream-Zugang oder M3U-Playlist deines Anbieters", "2" to "Nur legale, autorisierte Quellen verwenden",
            "3" to "Zugangsdaten werden verschlüsselt gespeichert"), Mood.CHEER),
)

/**
 * Einrichtungsassistent beim ersten Start: führt mit dem Maskottchen „Ulli“ durch die wichtigsten Funktionen.
 * Jederzeit überspringbar; [onDone] wird beim Abschluss und beim Überspringen aufgerufen.
 */
@Composable
fun SetupWizard(hasProvider: Boolean, onDone: () -> Unit) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val all = remember(hasProvider) { steps(hasProvider) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    val step = all[index]
    val last = index == all.lastIndex
    val nextFocus = remember { FocusRequester() }
    val anim = motionOk()
    fun go(d: Int) { val n = (index + d).coerceIn(0, all.lastIndex); if (n != index) { forward = d > 0; index = n } }

    LaunchedEffect(index) { delay(80); runCatching { nextFocus.requestFocus() } }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(p.bgTop, p.bgBottom)))
            .onPreviewKeyEvent { ev ->
                if (ev.key == Key.Back && ev.type == KeyEventType.KeyUp) { if (index > 0) go(-1); true }
                else ev.key == Key.Back
            },
    ) {
        // weicher Lichtschein hinter dem Maskottchen
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(p.glow.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width * 0.2f, size.height * 0.55f), radius = size.width * 0.35f),
                radius = size.width * 0.35f, center = Offset(size.width * 0.2f, size.height * 0.55f))
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            // Links: Ulli + Sprechblase
            Column(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                SpeechBubble(step.speech, key = index)
                Spacer(Modifier.height(14.dp))
                Ulli(step.mood, switchKey = index, modifier = Modifier.size(230.dp, 260.dp))
            }
            Spacer(Modifier.width(40.dp))
            // Rechts: Inhalt des Schritts
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text("SCHRITT ${index + 1} VON ${all.size}", color = p.textDim, fontSize = 13.sp, letterSpacing = 3.sp)
                Spacer(Modifier.height(8.dp))
                Progress(index, all.size)
                Spacer(Modifier.height(22.dp))
                AnimatedContent(index, label = "wizStep", transitionSpec = {
                    if (!anim) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    else {
                        val dir = if (forward) 1 else -1
                        (slideInHorizontally(tween(320)) { it / 6 * dir } + fadeIn(tween(320))) togetherWith
                            (slideOutHorizontally(tween(220)) { -it / 8 * dir } + fadeOut(tween(180)))
                    }
                }) { i -> StepCard(all[i]) }
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (index > 0) TvButton("Zurück", onClick = { go(-1) })
                    TvButton(if (last) (if (hasProvider) "Los geht's" else "Quelle einrichten") else "Weiter",
                        onClick = { if (last) onDone() else go(1) }, primary = true, focusRequester = nextFocus)
                    if (!last) TvButton("Überspringen", onClick = onDone)
                }
            }
        }
    }
}

@Composable
private fun Progress(index: Int, total: Int) {
    val p = LocalUltraTheme.current.palette
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            val w by animateFloatAsState(if (i == index) 34f else 10f, tween(260), label = "dotW")
            Box(Modifier.size(w.dp, 6.dp).clip(RoundedCornerShape(3.dp))
                .background(if (i <= index) p.primary else p.textDim.copy(alpha = 0.3f)))
        }
    }
}

@Composable
private fun StepCard(step: WizardStep) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val anim = motionOk()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(step.icon, fontSize = 40.sp)
            Spacer(Modifier.width(14.dp))
            Text(step.title, color = p.text, fontSize = 38.sp, fontWeight = FontWeight.Bold, fontFamily = theme.spec.fontFamily)
        }
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            step.points.forEachIndexed { i, (k, v) ->
                // Punkte erscheinen nacheinander
                val a = remember { Animatable(if (anim) 0f else 1f) }
                LaunchedEffect(Unit) { if (anim) { delay(140L + i * 110L); a.animateTo(1f, tween(280)) } }
                Row(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 18f }
                    .fillMaxWidth(0.92f).clip(RoundedCornerShape(14.dp)).background(p.surface.copy(alpha = 0.55f))
                    .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(76.dp), contentAlignment = Alignment.CenterStart) {
                        Text(k, color = p.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.5.dp, p.primary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 3.dp))
                    }
                    Text(v, color = p.text, fontSize = 18.sp)
                }
            }
        }
    }
}

/** Sprechblase mit Schreibmaschinen-Text; Spitze zeigt nach unten auf Ulli. */
@Composable
private fun SpeechBubble(text: String, key: Int) {
    val p = LocalUltraTheme.current.palette
    val anim = motionOk()
    var shown by remember(key) { mutableIntStateOf(if (anim) 0 else text.length) }
    LaunchedEffect(key) {
        if (!anim) return@LaunchedEffect
        while (shown < text.length) { delay(if (text[shown] in ".,!?–") 120 else 22); shown++ }
    }
    talking.floatValue = if (shown < text.length) 1f else 0f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White)
            .padding(horizontal = 20.dp, vertical = 16.dp)) {
            // unsichtbarer Volltext hält die Größe fest, damit die Blase beim Tippen nicht springt
            Text(text, color = Color.Transparent, fontSize = 17.sp, lineHeight = 23.sp)
            Text(text.take(shown), color = Color(0xFF111318), fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
        }
        Canvas(Modifier.size(28.dp, 14.dp)) {
            val path = androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close() }
            drawPath(path, Color.White)
        }
    }
}

/** Ulli spricht, solange die Sprechblase tippt (vereinfachter, geteilter Zustand). */
private val talking = mutableFloatStateOf(0f)

/**
 * Ulli – ein selbst gezeichneter kleiner Röhrenfernseher mit Antennen, Gesicht auf dem Bildschirm, Füßchen und Arm.
 * Wippt, blinzelt, bewegt beim Sprechen den Mund, winkt/zeigt/denkt je nach [mood]; beim Schrittwechsel kurzes „Rauschen“.
 */
@Composable
private fun Ulli(mood: Mood, switchKey: Int, modifier: Modifier = Modifier) {
    val p = LocalUltraTheme.current.palette
    val anim = motionOk()
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(anim) {
        if (!anim) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t = (it - start) / 1_000_000_000f }
    }
    // Rauschen beim Umschalten (wie beim Senderwechsel)
    val static = remember { Animatable(0f) }
    LaunchedEffect(switchKey) { if (anim && switchKey > 0) { static.snapTo(1f); static.animateTo(0f, tween(420)) } }
    val hop = remember { Animatable(0f) }
    LaunchedEffect(switchKey) { if (anim) { hop.snapTo(0f); hop.animateTo(1f, tween(520)) } }
    val talk = talking.floatValue
    val body = p.primary
    val dark = Color(0xFF14161C)

    Canvas(modifier) {
        val w = size.width; val h = size.height
        val bob = if (anim) sin(t * 2.2f) * h * 0.012f else 0f
        val jump = if (anim) -sin(hop.value * PI.toFloat()) * h * 0.05f else 0f
        val y0 = bob + jump
        // Schatten
        val sh = 1f - abs(y0) / (h * 0.08f)
        drawOval(Color.Black.copy(alpha = 0.28f * sh.coerceIn(0.4f, 1f)), Offset(w * 0.24f, h * 0.93f), Size(w * 0.52f * sh.coerceIn(0.7f, 1f), h * 0.05f))
        translate(top = y0) {
            val bx = w * 0.14f; val by = h * 0.26f; val bw = w * 0.72f; val bh = h * 0.56f
            // Antennen
            val wig = if (anim) sin(t * 3.1f) * 6f else 0f
            antenna(Offset(w * 0.46f, by + 2f), -28f + wig, h * 0.2f, body)
            antenna(Offset(w * 0.54f, by + 2f), 26f - wig * 0.8f, h * 0.17f, p.secondary)
            // Füße
            drawRoundRect(dark, Offset(w * 0.27f, by + bh - 4f), Size(w * 0.12f, h * 0.08f), CornerRadius(10f))
            drawRoundRect(dark, Offset(w * 0.61f, by + bh - 4f), Size(w * 0.12f, h * 0.08f), CornerRadius(10f))
            // Gehäuse
            drawRoundRect(Brush.verticalGradient(listOf(body, body.copy(alpha = 0.75f).compositeOver(dark)), by, by + bh),
                Offset(bx, by), Size(bw, bh), CornerRadius(w * 0.09f))
            drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(bx + 6f, by + 6f), Size(bw - 12f, bh * 0.22f), CornerRadius(w * 0.07f))
            // Bildschirm
            val sx = bx + bw * 0.09f; val sy = by + bh * 0.11f; val sw = bw * 0.66f; val shh = bh * 0.74f
            drawRoundRect(Color(0xFF0B0E14), Offset(sx, sy), Size(sw, shh), CornerRadius(w * 0.06f))
            drawRoundRect(Brush.radialGradient(listOf(p.glow.copy(alpha = 0.35f), Color.Transparent), Offset(sx + sw / 2, sy + shh / 2), sw * 0.7f),
                Offset(sx, sy), Size(sw, shh), CornerRadius(w * 0.06f))
            // Scanlines
            var ly = sy + 4f
            while (ly < sy + shh - 3f) { drawLine(Color.White.copy(alpha = 0.04f), Offset(sx + 6f, ly), Offset(sx + sw - 6f, ly), 1f); ly += 5f }
            // Gesicht
            face(Offset(sx, sy), Size(sw, shh), mood, t, talk, anim)
            // Rauschen
            if (static.value > 0.01f) {
                val r = kotlin.random.Random((t * 60).toInt())
                repeat(140) {
                    val px = sx + r.nextFloat() * sw; val py = sy + r.nextFloat() * shh
                    drawRect(Color.White.copy(alpha = static.value * r.nextFloat()), Offset(px, py), Size(3f, 2f))
                }
            }
            // Bedienfeld rechts: zwei Drehknöpfe + Lautsprecher
            val kx = bx + bw * 0.86f
            drawCircle(dark, w * 0.035f, Offset(kx, by + bh * 0.25f))
            drawLine(Color.White.copy(alpha = 0.7f), Offset(kx, by + bh * 0.25f),
                Offset(kx + w * 0.022f * kotlin.math.cos(t * 1.3f), by + bh * 0.25f + w * 0.022f * sin(t * 1.3f)), 3f, StrokeCap.Round)
            drawCircle(dark, w * 0.03f, Offset(kx, by + bh * 0.45f))
            repeat(3) { i -> drawLine(dark.copy(alpha = 0.7f), Offset(kx - w * 0.03f, by + bh * (0.62f + i * 0.08f)), Offset(kx + w * 0.03f, by + bh * (0.62f + i * 0.08f)), 3f, StrokeCap.Round) }
            // Arm
            arm(Offset(bx, by + bh * 0.5f), Offset(bx + bw, by + bh * 0.5f), mood, t, anim, body)
        }
    }
}

private fun Color.compositeOver(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

private fun DrawScope.antenna(base: Offset, deg: Float, len: Float, tip: Color) {
    rotate(deg, base) {
        drawLine(Color(0xFF2A2E38), base, Offset(base.x, base.y - len), 5f, StrokeCap.Round)
        drawCircle(tip, 9f, Offset(base.x, base.y - len))
        drawCircle(Color.White.copy(alpha = 0.6f), 3f, Offset(base.x - 2.5f, base.y - len - 2.5f))
    }
}

private fun DrawScope.face(o: Offset, s: Size, mood: Mood, t: Float, talk: Float, anim: Boolean) {
    val eyeC = Color(0xFF7CF7FF)
    // Blinzeln alle ~3,7 s
    val ph = (t % 3.7f)
    val blink = if (anim && ph < 0.14f) (1f - sin(ph / 0.14f * PI.toFloat())).coerceIn(0.08f, 1f) else 1f
    val look = when (mood) { Mood.POINT -> 0.18f; Mood.THINK -> -0.1f; else -> if (anim) sin(t * 0.7f) * 0.08f else 0f }
    val lookUp = if (mood == Mood.THINK) -0.12f else 0f
    val ey = o.y + s.height * (0.4f + lookUp * 0.3f)
    val ew = s.width * 0.11f; val eh = s.height * 0.2f * blink
    for (cx in listOf(o.x + s.width * 0.33f, o.x + s.width * 0.67f)) {
        val x = cx + look * s.width * 0.18f
        if (mood == Mood.CHEER) {
            // Freude: ^ ^
            drawArc(eyeC, 200f, 140f, false, Offset(x - ew, ey - ew * 0.6f), Size(ew * 2, ew * 1.6f), style = Stroke(5f, cap = StrokeCap.Round))
        } else {
            drawOval(eyeC, Offset(x - ew / 2, ey - eh / 2), Size(ew, eh))
            drawOval(Color.White, Offset(x - ew * 0.15f, ey - eh * 0.32f), Size(ew * 0.3f, eh * 0.25f))
        }
    }
    // Wangen
    drawCircle(Color(0xFFFF6FA8).copy(alpha = 0.35f), s.width * 0.05f, Offset(o.x + s.width * 0.2f, o.y + s.height * 0.62f))
    drawCircle(Color(0xFFFF6FA8).copy(alpha = 0.35f), s.width * 0.05f, Offset(o.x + s.width * 0.8f, o.y + s.height * 0.62f))
    // Mund
    val mx = o.x + s.width * 0.5f; val my = o.y + s.height * 0.68f
    val open = if (anim && talk > 0f) (0.25f + 0.75f * abs(sin(t * 13f)) * (0.6f + 0.4f * abs(sin(t * 5.3f)))) else 0f
    when {
        open > 0f -> drawOval(eyeC, Offset(mx - s.width * 0.08f, my - s.height * 0.02f), Size(s.width * 0.16f, s.height * (0.04f + 0.11f * open)))
        mood == Mood.THINK -> drawLine(eyeC, Offset(mx - s.width * 0.06f, my + 2f), Offset(mx + s.width * 0.07f, my - 4f), 5f, StrokeCap.Round)
        mood == Mood.CHEER -> drawArc(eyeC, 0f, 180f, true, Offset(mx - s.width * 0.11f, my - s.height * 0.06f), Size(s.width * 0.22f, s.height * 0.18f))
        else -> drawArc(eyeC, 20f, 140f, false, Offset(mx - s.width * 0.1f, my - s.height * 0.1f), Size(s.width * 0.2f, s.height * 0.14f), style = Stroke(5f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.arm(left: Offset, right: Offset, mood: Mood, t: Float, anim: Boolean, color: Color) {
    val arm = Color(0xFF2A2E38)
    // linker Arm hängt locker
    val l2 = Offset(left.x - 22f, left.y + 34f + (if (anim) sin(t * 2.2f) * 3f else 0f))
    drawLine(arm, left, l2, 7f, StrokeCap.Round); drawCircle(color, 10f, l2)
    // rechter Arm je nach Stimmung
    val (angle, len) = when (mood) {
        Mood.WAVE, Mood.CHEER -> (-120f + (if (anim) sin(t * 9f) * 24f else 0f)) to 58f
        Mood.POINT -> (-20f + (if (anim) sin(t * 3f) * 5f else 0f)) to 62f
        Mood.THINK -> (-150f) to 46f
        Mood.TALK -> (40f + (if (anim) sin(t * 2.5f) * 10f else 0f)) to 44f
    }
    val rad = Math.toRadians(angle.toDouble())
    val end = Offset(right.x + (len * kotlin.math.cos(rad)).toFloat(), right.y + (len * sin(rad)).toFloat())
    drawLine(arm, right, end, 7f, StrokeCap.Round)
    drawCircle(color, 11f, end)
    if (mood == Mood.POINT) drawCircle(Color.White, 4f, Offset(end.x + 8f, end.y - 2f))
}
