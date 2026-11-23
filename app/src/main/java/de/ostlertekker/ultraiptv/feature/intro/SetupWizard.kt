package de.ostlertekker.ultraiptv.feature.intro

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.data.Keys
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.jellyfin.JellyfinRepository
import de.ostlertekker.ultraiptv.core.news.NewsSources
import de.ostlertekker.ultraiptv.core.seerr.SeerrRepository
import de.ostlertekker.ultraiptv.core.ui.TvChip
import de.ostlertekker.ultraiptv.core.ui.TvTextField
import de.ostlertekker.ultraiptv.core.weather.WeatherRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Wie sich Ulli (das Fernseher-Maskottchen) in einem Schritt verhält. */
internal enum class Mood { WAVE, TALK, POINT, THINK, CHEER }

private enum class WStep { WELCOME, IPTV, JELLYFIN, SEERR, WEATHER, NEWS, DONE }

@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    val jellyfin: JellyfinRepository,
    val seerr: SeerrRepository,
    val weather: WeatherRepository,
    val settingsRepo: SettingsRepository,
) : ViewModel() {
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val busy = MutableStateFlow(false)
    val msg = MutableStateFlow<String?>(null)
    val ok = MutableStateFlow(false)
    val found = MutableStateFlow<List<Pair<String, String>>?>(null)

    fun reset() { msg.value = null; ok.value = false; busy.value = false }

    private fun run(block: suspend () -> String) {
        if (busy.value) return
        busy.value = true; msg.value = null
        viewModelScope.launch {
            runCatching { block() }.onSuccess { msg.value = it; ok.value = true }.onFailure { msg.value = "⚠ " + (it.message ?: "Fehler"); ok.value = false }
            busy.value = false
        }
    }

    fun jfLogin(server: String, user: String, pw: String) = run { jellyfin.login(server, user, pw).let { trf("Verbunden mit {0} als {1}", it.serverName, it.userName) } }
    fun jfSearch() = viewModelScope.launch { found.value = null; found.value = runCatching { jellyfin.discover() }.getOrDefault(emptyList()) }
    fun seerrLogin(server: String, user: String, pw: String) = run {
        val name = seerr.login(server, user, pw); settingsRepo.set(Keys.SEERR_ENABLED, true); trf("Angemeldet als {0}", name)
    }
    fun setPlace(place: String) = run {
        val r = weather.setPlace(place).getOrThrow()
        settingsRepo.set(Keys.WEATHER_ENABLED, r.isNotBlank()); if (r.isBlank()) tr("Wetter ausgeblendet") else trf("Wetter für {0}", r)
    }
    fun news(source: String?) = viewModelScope.launch {
        if (source == null) settingsRepo.set(Keys.NEWS_ENABLED, false)
        else { settingsRepo.set(Keys.NEWS_SOURCE, source); settingsRepo.set(Keys.NEWS_ENABLED, true) }
    }
    fun newsLocal(on: Boolean) = viewModelScope.launch { settingsRepo.set(Keys.NEWS_LOCAL, on) }
}

private fun speechOf(st: WStep, hasProvider: Boolean, ok: Boolean) = when (st) {
    WStep.WELCOME -> tr("Hi, ich bin Ulli! Ich helf dir, alles einzurichten – dauert nur ein paar Minuten. Alles außer dem Anbieter ist freiwillig.")
    WStep.IPTV -> if (hasProvider) tr("Super, dein IPTV-Anbieter ist schon eingerichtet!") else tr("Zuerst dein IPTV-Anbieter. Die Zugangsdaten bekommst du von ihm – Xtream-Zugang oder M3U-Link.")
    WStep.JELLYFIN -> if (ok) tr("Klasse, Jellyfin ist verbunden!") else tr("Hast du einen eigenen Jellyfin-Server? Dann verbinden wir ihn jetzt. Sonst einfach überspringen.")
    WStep.SEERR -> if (ok) tr("Perfekt, Seerr ist angemeldet!") else tr("Mit Seerr kannst du Filme und Serien anfragen, die noch fehlen. Angemeldet wird mit deinem Jellyfin-Konto.")
    WStep.WEATHER -> if (ok) tr("Wetter läuft! Das siehst du oben neben dem Logo.") else tr("Für welchen Ort soll ich dir das Wetter anzeigen? Stadt oder Postleitzahl reicht.")
    WStep.NEWS -> tr("Möchtest du unten eine Nachrichten-Laufschrift? Such dir eine Quelle aus – oder lass sie aus.")
    WStep.DONE -> tr("Fertig! Alles Weitere findest du jederzeit in den Einstellungen. Viel Spaß!")
}

private fun moodOf(st: WStep, ok: Boolean) = when {
    ok -> Mood.CHEER
    st == WStep.WELCOME -> Mood.WAVE
    st == WStep.DONE -> Mood.CHEER
    st == WStep.IPTV || st == WStep.WEATHER -> Mood.POINT
    st == WStep.SEERR -> Mood.THINK
    else -> Mood.TALK
}

/**
 * Einrichtungsassistent beim ersten Start: Ulli führt durch die Anmeldung – IPTV-Anbieter, Jellyfin, Seerr, Wetter, Nachrichten.
 * Jeder Schritt außer dem Anbieter ist freiwillig; „Überspringen“ beendet den Assistenten. [onDone] beim Abschluss/Überspringen.
 */
@Composable
fun SetupWizard(hasProvider: Boolean, onDone: () -> Unit, vm: SetupWizardViewModel = hiltViewModel()) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val all = WStep.entries
    var index by rememberSaveable { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    var providerNow by remember { mutableStateOf(hasProvider) }
    val step = all[index]
    val ok by vm.ok.collectAsStateWithLifecycle()
    val anim = motionOk()
    fun go(d: Int) { val n = (index + d).coerceIn(0, all.lastIndex); if (n != index) { forward = d > 0; vm.reset(); index = n } }
    // bereits verbundene Dienste gleich als erledigt zeigen
    LaunchedEffect(index) {
        when (step) {
            WStep.JELLYFIN -> vm.jellyfin.session.value?.let { vm.msg.value = trf("Verbunden mit {0} als {1}", it.serverName, it.userName); vm.ok.value = true }
            WStep.SEERR -> vm.seerr.state.value.takeIf { it.loggedIn }?.let { vm.msg.value = trf("Angemeldet als {0}", it.user); vm.ok.value = true }
            else -> Unit
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(p.bgTop, p.bgBottom)))
            .onPreviewKeyEvent { ev ->
                if (ev.key == Key.Back && ev.type == KeyEventType.KeyUp) { if (index > 0) go(-1); true }
                else ev.key == Key.Back && ev.type == KeyEventType.KeyDown
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(p.glow.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width * 0.15f, size.height * 0.6f), radius = size.width * 0.3f),
                radius = size.width * 0.3f, center = Offset(size.width * 0.15f, size.height * 0.6f))
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(270.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                SpeechBubble(speechOf(step, providerNow, ok), key = index * 2 + (if (ok) 1 else 0))
                Spacer(Modifier.height(10.dp))
                Ulli(moodOf(step, ok), switchKey = index, modifier = Modifier.size(190.dp, 215.dp))
            }
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                if (step != WStep.WELCOME && step != WStep.DONE) {
                    Text(trf("SCHRITT {0} VON {1}", index, (all.size - 2)), color = p.textDim, fontSize = 12.sp, letterSpacing = 3.sp)
                    Spacer(Modifier.height(6.dp))
                    Progress(index - 1, all.size - 2)
                    Spacer(Modifier.height(14.dp))
                }
                AnimatedContent(index, label = "wizStep", modifier = Modifier.weight(1f, fill = false), transitionSpec = {
                    if (!anim) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    else {
                        val dir = if (forward) 1 else -1
                        (slideInHorizontally(tween(320)) { it / 6 * dir } + fadeIn(tween(320))) togetherWith
                            (slideOutHorizontally(tween(220)) { -it / 8 * dir } + fadeOut(tween(180)))
                    }
                }) { i ->
                    when (all[i]) {
                        WStep.WELCOME -> WelcomePane(onStart = { go(1) }, onSkip = onDone)
                        WStep.IPTV -> IptvPane(providerNow, onSaved = { providerNow = true; go(1) }, onNext = { go(1) }, onBack = { go(-1) })
                        WStep.JELLYFIN -> JellyfinPane(vm, onNext = { go(1) }, onBack = { go(-1) })
                        WStep.SEERR -> SeerrPane(vm, onNext = { go(1) }, onBack = { go(-1) })
                        WStep.WEATHER -> WeatherPane(vm, onNext = { go(1) }, onBack = { go(-1) })
                        WStep.NEWS -> NewsPane(vm, onNext = { go(1) }, onBack = { go(-1) })
                        WStep.DONE -> DonePane(providerNow, onDone)
                    }
                }
            }
        }
    }
}

/** Fokus zuverlässig setzen: wiederholt anfordern, bis er im Bild angekommen ist (Übergänge/Intro können den ersten Versuch schlucken). */
@Composable
private fun AutoFocus(f: FocusRequester) {
    LaunchedEffect(f) {
        repeat(12) {
            delay(if (it == 0) 120 else 200)
            if (runCatching { f.requestFocus() }.isSuccess && it >= 2) return@LaunchedEffect
        }
    }
}

@Composable
private fun PaneTitle(icon: String, title: String, sub: String) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 30.sp); Spacer(Modifier.width(12.dp))
        Text(title, color = p.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = theme.spec.fontFamily)
    }
    Text(sub, color = p.textDim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
}

/** Untere Knopfzeile eines Schritts: Zurück · Hauptaktion · Weiter/Überspringen. */
@Composable
private fun NavRow(onBack: () -> Unit, onNext: () -> Unit, done: Boolean, extra: (@Composable () -> Unit)? = null, nextFocus: FocusRequester? = null) {
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TvButton(tr("Zurück"), onClick = onBack)
        extra?.invoke()
        TvButton(if (done) "Weiter" else tr("Überspringen"), onClick = onNext, primary = done, focusRequester = nextFocus)
    }
}

@Composable
private fun Status(vm: SetupWizardViewModel) {
    val msg by vm.msg.collectAsStateWithLifecycle()
    val ok by vm.ok.collectAsStateWithLifecycle()
    msg?.let { Text(if (ok) "✓ $it" else it, color = if (ok) Color(0xFF4ADE80) else Color(0xFFFF8A80), fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp)) }
}

@Composable
private fun WelcomePane(onStart: () -> Unit, onSkip: () -> Unit) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val f = remember { FocusRequester() }
    AutoFocus(f)
    Column {
        Text(tr("WILLKOMMEN BEI"), color = p.textDim, fontSize = 13.sp, letterSpacing = 4.sp)
        Text(tr("ULTRA IPTV"), color = p.text, fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, fontFamily = theme.spec.fontFamily)
        Spacer(Modifier.height(10.dp))
        listOf("📺" to "IPTV-Anbieter", "🟣" to tr("Jellyfin (optional)"), "🎟" to tr("Seerr (optional)"), "⛅" to "Wetter", "📰" to "Nachrichten-Laufschrift")
            .forEachIndexed { i, (ic, t) ->
                val a = remember { Animatable(0f) }
                LaunchedEffect(Unit) { delay(150L + i * 90L); a.animateTo(1f, tween(260)) }
                Row(Modifier.graphicsLayer { alpha = a.value; translationX = (1f - a.value) * 30f }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(p.primary.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = p.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp)); Text("$ic  $t", color = p.text, fontSize = 18.sp)
                }
            }
        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(tr("Einrichtung starten"), onClick = onStart, primary = true, focusRequester = f)
            TvButton(tr("Überspringen"), onClick = onSkip)
        }
    }
}

@Composable
private fun IptvPane(hasProvider: Boolean, onSaved: () -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    if (hasProvider) {
        val f = remember { FocusRequester() }
        AutoFocus(f)
        Column {
            PaneTitle("📺", "IPTV-Anbieter", tr("Deine Quelle ist bereits eingerichtet. Ändern kannst du sie später unter Einstellungen."))
            Text(tr("✓ Eingerichtet"), color = Color(0xFF4ADE80), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            NavRow(onBack, onNext, done = true, nextFocus = f)
        }
    } else Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            TvButton(tr("Zurück"), onClick = onBack)
            TvButton(tr("Später einrichten"), onClick = onNext)
        }
        Box(Modifier.weight(1f, fill = false)) {
            de.ostlertekker.ultraiptv.feature.settings.ProviderSetupScreen(onDone = onSaved, firstRun = false)
        }
    }
}

@Composable
private fun JellyfinPane(vm: SetupWizardViewModel, onNext: () -> Unit, onBack: () -> Unit) {
    val p = LocalUltraTheme.current.palette
    var server by remember { mutableStateOf(vm.jellyfin.savedServer()) }
    var user by remember { mutableStateOf(vm.jellyfin.savedUser()) }
    var pw by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val ok by vm.ok.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    val f = remember { FocusRequester() }; val next = remember { FocusRequester() }
    AutoFocus(f)
    LaunchedEffect(ok) { if (ok) { delay(100); runCatching { next.requestFocus() } } }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PaneTitle("🟣", tr("Jellyfin verbinden"), tr("Deine eigene Mediathek direkt in der App. Gleiche Adresse und Anmeldung wie in der Jellyfin-App."))
        TvTextField(server, { server = it }, tr("Serveradresse (z. B. 192.168.1.10:8096 oder meinserver.de)"), modifier = Modifier.fillMaxWidth(), focusRequester = f)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TvChip(if (found == null && busy) tr("Suche …") else tr("Im Heimnetz suchen"), false, { vm.jfSearch() })
            found?.let { list ->
                if (list.isEmpty()) Text(tr("Kein Server gefunden"), color = p.textDim, fontSize = 13.sp)
                list.take(3).forEach { (addr, name) -> TvChip("$name · ${addr.substringAfter("://")}", server == addr, { server = addr }) }
            }
        }
        TvTextField(user, { user = it }, "Benutzername", modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        TvTextField(pw, { pw = it }, "Passwort", modifier = Modifier.fillMaxWidth(), password = true)
        Status(vm)
        NavRow(onBack, onNext, done = ok, nextFocus = next, extra = {
            if (!ok) TvButton(if (busy) tr("Verbinde …") else "Verbinden", primary = true, onClick = { if (!busy && server.isNotBlank()) vm.jfLogin(server, user, pw) })
        })
    }
}

@Composable
private fun SeerrPane(vm: SetupWizardViewModel, onNext: () -> Unit, onBack: () -> Unit) {
    var server by remember { mutableStateOf(vm.seerr.savedServer()) }
    var user by remember { mutableStateOf(vm.jellyfin.session.value?.userName ?: vm.jellyfin.savedUser()) }
    var pw by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val ok by vm.ok.collectAsStateWithLifecycle()
    val f = remember { FocusRequester() }; val next = remember { FocusRequester() }
    AutoFocus(f)
    LaunchedEffect(ok) { if (ok) { delay(100); runCatching { next.requestFocus() } } }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PaneTitle("🎟", tr("Seerr anmelden"), "Optional: Filme und Serien anfragen (Jellyseerr/Overseerr). Anmeldung mit deinem Jellyfin-Konto – das Passwort wird nicht gespeichert.")
        TvTextField(server, { server = it }, tr("Seerr-Adresse (z. B. 192.168.1.10:5055)"), modifier = Modifier.fillMaxWidth(), focusRequester = f)
        Spacer(Modifier.height(8.dp))
        TvTextField(user, { user = it }, "Jellyfin-Benutzername", modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        TvTextField(pw, { pw = it }, "Jellyfin-Passwort", modifier = Modifier.fillMaxWidth(), password = true)
        Status(vm)
        NavRow(onBack, onNext, done = ok, nextFocus = next, extra = {
            if (!ok) TvButton(if (busy) tr("Anmelden …") else "Anmelden", primary = true,
                onClick = { if (!busy && server.isNotBlank() && user.isNotBlank()) vm.seerrLogin(server, user, pw) })
        })
    }
}

@Composable
private fun WeatherPane(vm: SetupWizardViewModel, onNext: () -> Unit, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var place by remember { mutableStateOf(s?.weatherPlace?.substringBefore(",") ?: "") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val ok by vm.ok.collectAsStateWithLifecycle()
    val f = remember { FocusRequester() }; val next = remember { FocusRequester() }
    AutoFocus(f)
    LaunchedEffect(ok) { if (ok) { delay(100); runCatching { next.requestFocus() } } }
    Column {
        PaneTitle("⛅", tr("Wetter"), tr("Das Wetter erscheint oben neben „ULTRA IPTV“. Der Ort bestimmt auch die passenden Nachrichten."))
        TvTextField(place, { place = it }, tr("Ort oder Postleitzahl (z. B. Berlin oder 10115)"), modifier = Modifier.fillMaxWidth(), focusRequester = f)
        Status(vm)
        NavRow(onBack, onNext, done = ok, nextFocus = next, extra = {
            if (!ok) TvButton(if (busy) tr("Suche …") else tr("Ort übernehmen"), primary = true, onClick = { if (!busy && place.isNotBlank()) vm.setPlace(place) })
        })
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NewsPane(vm: SetupWizardViewModel, onNext: () -> Unit, onBack: () -> Unit) {
    val p = LocalUltraTheme.current.palette
    val s by vm.settings.collectAsStateWithLifecycle()
    val st = s ?: return
    val f = remember { FocusRequester() }
    AutoFocus(f)
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PaneTitle("📰", tr("Nachrichten-Laufschrift"), tr("Eine Zeile mit aktuellen Schlagzeilen am unteren Rand. Quelle wählen oder ausschalten."))
        val on = st.newsEnabled
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvChip(tr("Aus"), !on, { vm.news(null) }, modifier = Modifier.focusRequester(f))
            NewsSources.choices.filter { it.first != "off" }.forEach { (id, label) -> TvChip(label, on && st.newsSource == id, { vm.news(id) }) }
        }
        Spacer(Modifier.height(12.dp))
        TvChip(if (st.newsLocal) tr("✓ Lokalnachrichten einmischen") else tr("Lokalnachrichten einmischen"), st.newsLocal, { vm.newsLocal(!st.newsLocal) })
        Text(tr("„Automatisch“ wählt passend zu Land und Sprache deines Wetter-Orts."), color = p.textDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        NavRow(onBack, onNext, done = true)
    }
}

@Composable
private fun DonePane(hasProvider: Boolean, onDone: () -> Unit) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val f = remember { FocusRequester() }
    AutoFocus(f)
    Column {
        Text("🎉", fontSize = 48.sp)
        Text(tr("Alles eingerichtet!"), color = p.text, fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, fontFamily = theme.spec.fontFamily)
        Text(if (hasProvider) tr("Viel Spaß mit ULTRA IPTV.") else tr("Den IPTV-Anbieter richtest du als Nächstes ein."),
            color = p.textDim, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 22.dp)) { TvButton(if (hasProvider) tr("Los geht's") else tr("Anbieter einrichten"), onClick = onDone, primary = true, focusRequester = f) }
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
internal fun Ulli(mood: Mood, switchKey: Int, modifier: Modifier = Modifier) {
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
