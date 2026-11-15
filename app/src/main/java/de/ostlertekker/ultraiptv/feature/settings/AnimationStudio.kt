package de.ostlertekker.ultraiptv.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.data.Keys
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.device.DeviceCapabilityManager
import de.ostlertekker.ultraiptv.core.model.DeviceTier
import de.ostlertekker.ultraiptv.core.motion.EntranceAnim
import de.ostlertekker.ultraiptv.core.motion.ExitAnim
import de.ostlertekker.ultraiptv.core.motion.FocusEffect
import de.ostlertekker.ultraiptv.core.motion.LocalMotion
import de.ostlertekker.ultraiptv.core.motion.MotionAnim
import de.ostlertekker.ultraiptv.core.motion.MotionConfig
import de.ostlertekker.ultraiptv.core.motion.MotionPresets
import de.ostlertekker.ultraiptv.core.motion.MotionProfile
import de.ostlertekker.ultraiptv.core.motion.MotionSpec
import de.ostlertekker.ultraiptv.core.motion.PageTransition
import de.ostlertekker.ultraiptv.core.motion.PageTransitionHost
import de.ostlertekker.ultraiptv.core.motion.crazyEntrance
import de.ostlertekker.ultraiptv.core.motion.exitTransitionFor
import de.ostlertekker.ultraiptv.core.ui.ChoiceRow
import de.ostlertekker.ultraiptv.core.ui.SectionHeader
import de.ostlertekker.ultraiptv.core.ui.ToggleRow
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.TvTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Was die Vorschau-Bühne gerade zeigt. */
sealed interface PreviewTarget {
    data class Entrance(val anim: EntranceAnim) : PreviewTarget
    data class Focus(val effect: FocusEffect) : PreviewTarget
    data class Page(val type: PageTransition) : PreviewTarget
    data class Exit(val anim: ExitAnim) : PreviewTarget
}

@HiltViewModel
class AnimationStudioViewModel @Inject constructor(
    private val settings: SettingsRepository,
    val device: DeviceCapabilityManager,
) : ViewModel() {
    val config = settings.settings.map { MotionConfig.fromJson(it.motionJson) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MotionConfig())
    val profiles = settings.settings.map { MotionPresets.all + MotionPresets.profilesFromJson(it.motionProfilesJson) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MotionPresets.all)
    val preview = kotlinx.coroutines.flow.MutableStateFlow<PreviewTarget>(PreviewTarget.Entrance(EntranceAnim.DOMINO))
    val previewKey = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun update(block: (MotionConfig) -> MotionConfig) = viewModelScope.launch {
        val cur = MotionConfig.fromJson(settings.settings.first().motionJson)
        settings.set(Keys.MOTION, block(cur).toJson().toString())
    }

    fun toggleEnabled(a: MotionAnim) = update { c -> c.copy(disabled = if (a.id in c.disabled) c.disabled - a.id else c.disabled + a.id) }

    fun play(t: PreviewTarget) { preview.value = t; previewKey.value++ }

    fun saveProfile(name: String) = viewModelScope.launch {
        val s = settings.settings.first()
        val custom = MotionPresets.profilesFromJson(s.motionProfilesJson).filter { it.name != name } +
            MotionProfile(name, MotionConfig.fromJson(s.motionJson))
        settings.set(Keys.MOTION_PROFILES, MotionPresets.profilesToJson(custom))
    }

    fun loadProfile(p: MotionProfile) = viewModelScope.launch { settings.set(Keys.MOTION, p.config.toJson().toString()) }

    fun deleteProfile(p: MotionProfile) = viewModelScope.launch {
        val custom = MotionPresets.profilesFromJson(settings.settings.first().motionProfilesJson).filter { it.name != p.name }
        settings.set(Keys.MOTION_PROFILES, MotionPresets.profilesToJson(custom))
    }
}

private fun <T> next(list: List<T>, cur: T): T = list[(list.indexOf(cur) + 1).mod(list.size)]

/** ANIMATION STUDIO – eigenes Einstellungsmenü der CRAZY MOTION ENGINE. */
@Composable
fun AnimationStudio(vm: AnimationStudioViewModel = hiltViewModel()) {
    val p = LocalUltraTheme.current.palette
    val c by vm.config.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val weak = vm.device.tier == DeviceTier.ENTRY
    val effective = remember(c) { MotionSpec.resolve(c, weak, false) }
    var profileName by remember { mutableStateOf("") }

    // Vorschau-Bühne oben (immer sichtbar), Optionen darunter in voller Breite – Titel werden nie abgeschnitten
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("VORSCHAU", color = p.textDim, fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            Text("▶ spielt die Animation hier ab · Fokus-Effekte mit den Feldern testen", color = p.textDim, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        PreviewStage(vm, effective)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 60.dp)) {
            item {
                SectionHeader("Animation Studio · CRAZY MOTION ENGINE")
                ToggleRow("Engine aktiv", c.enabled, { v -> vm.update { it.copy(enabled = v) } })
                ToggleRow("Reduzierte Animationen", c.reduced, { v -> vm.update { it.copy(reduced = v) } },
                    subtitle = "Nur ruhige, kurze Effekte ohne Partikel")
                ToggleRow("Auf schwacher Hardware vereinfachen", c.autoSimplify, { v -> vm.update { it.copy(autoSimplify = v) } },
                    subtitle = if (weak) "Dieses Gerät: Entry-Profil → vereinfacht (weniger Partikel/Splitter, kürzer)" else "Dieses Gerät: ${vm.device.tier.label} – volle Effekte")
                ChoiceRow("Geschwindigkeit", "${c.speed}×", { vm.update { it.copy(speed = next(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), it.speed)) } })
                ChoiceRow("Intensität / Stärke", "${(c.intensity * 100).toInt()} %", { vm.update { it.copy(intensity = next(listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f), it.intensity)) } })
                ToggleRow("Partikeleffekte", c.particles, { v -> vm.update { it.copy(particles = v) } })
                ToggleRow("Eingangsanimation für Karten", c.cardEntrance, { v -> vm.update { it.copy(cardEntrance = v) } },
                    subtitle = "Poster und Sender in Reihen erscheinen gestaffelt")
            }
            item {
                SectionHeader("Menü-Eingangsanimation")
                ChoiceRow("Aktiv", c.entrance?.label ?: "Zufall (aus aktivierten)", {
                    vm.update { cfg -> cfg.copy(entrance = next(listOf<EntranceAnim?>(null) + EntranceAnim.entries, cfg.entrance)) }
                })
            }
            items(EntranceAnim.entries, key = { it.id }) { a ->
                AnimRow(a, c.entrance == a, a.id !in c.disabled,
                    onSelect = { vm.update { it.copy(entrance = a) } }, onToggle = { vm.toggleEnabled(a) },
                    onPreview = { vm.play(PreviewTarget.Entrance(a)) })
            }
            item {
                SectionHeader("Navigationseffekte (Fokus)")
                Text("Wirkt auf alle Menüs, Karten, Listen und die Player-Steuerung.", color = p.textDim, fontSize = 12.sp, modifier = Modifier.padding(4.dp))
            }
            items(FocusEffect.entries, key = { it.id }) { f ->
                AnimRow(f, c.focus == f, f.id !in c.disabled,
                    onSelect = { vm.update { it.copy(focus = f) } }, onToggle = { vm.toggleEnabled(f) },
                    onPreview = { vm.play(PreviewTarget.Focus(f)) })
            }
            item {
                SectionHeader("Seitenübergänge")
                ChoiceRow("Eingang (neue Seite)", c.pageEnter?.label ?: "Keiner", {
                    vm.update { cfg -> cfg.copy(pageEnter = next(listOf<PageTransition?>(null) + PageTransition.entries, cfg.pageEnter)) }
                })
                ChoiceRow("Ausgangsanimation (alte Seite)", c.pageExit.label, {
                    vm.update { it.copy(pageExit = next(ExitAnim.entries, it.pageExit)) }; vm.play(PreviewTarget.Exit(next(ExitAnim.entries, c.pageExit)))
                })
                ChoiceRow("Player öffnen", c.playerEnter?.label ?: "Keiner", {
                    vm.update { cfg -> cfg.copy(playerEnter = next(listOf<PageTransition?>(null) + PageTransition.entries, cfg.playerEnter)) }
                })
                ChoiceRow("Senderwechsel (über dem Video)", c.zapEffect?.label ?: "Keiner", {
                    vm.update { cfg -> cfg.copy(zapEffect = next(listOf<PageTransition?>(null) + PageTransition.entries, cfg.zapEffect)) }
                }, subtitle = "Der neue Sender startet sofort – der Effekt läuft nur darüber")
            }
            items(PageTransition.entries, key = { it.id }) { t ->
                AnimRow(t, c.pageEnter == t, t.id !in c.disabled,
                    onSelect = { vm.update { it.copy(pageEnter = t) } }, onToggle = { vm.toggleEnabled(t) },
                    onPreview = { vm.play(PreviewTarget.Page(t)) })
            }
            item {
                SectionHeader("Animationsprofile")
                profiles.forEach { pr ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(p.surface, RoundedCornerShape(12.dp)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pr.name + if (pr.builtIn) "  (Vorlage)" else "", color = p.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        TvButton("Laden", onClick = { vm.loadProfile(pr) }, primary = true)
                        if (!pr.builtIn) TvButton("Löschen", onClick = { vm.deleteProfile(pr) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    TvTextField(profileName, { profileName = it.take(24) }, "Name für eigenes Profil", modifier = Modifier.width(300.dp))
                    TvButton("Aktuelle Einstellungen speichern", onClick = { if (profileName.isNotBlank()) { vm.saveProfile(profileName.trim()); profileName = "" } })
                }
            }
        }
    }
}

@Composable
private fun AnimRow(a: MotionAnim, selected: Boolean, enabled: Boolean, onSelect: () -> Unit, onToggle: () -> Unit, onPreview: () -> Unit) {
    val p = LocalUltraTheme.current.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(if (selected) p.surfaceFocused else p.surface, RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(a.label, color = if (enabled) p.text else p.textDim, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (a.heavy) Text("aufwändig – wird auf schwacher Hardware vereinfacht", color = p.textDim, fontSize = 10.sp)
        }
        TvButton("▶", onClick = onPreview)
        TvButton(if (enabled) "AN" else "AUS", onClick = onToggle, primary = enabled)
        TvButton(if (selected) "✓ Aktiv" else "Auswählen", onClick = onSelect, enabled = enabled)
    }
}

@Composable
private fun PreviewStage(vm: AnimationStudioViewModel, spec: MotionSpec) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val target by vm.preview.collectAsStateWithLifecycle()
    val key by vm.previewKey.collectAsStateWithLifecycle()
    // Vorschau läuft immer (auch wenn die Engine global aus ist)
    val stageSpec = spec.copy(enabled = true, particles = spec.particles, speed = spec.speed)
    Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)).background(p.bgBottom).padding(10.dp)) {
        when (val t = target) {
            is PreviewTarget.Entrance -> CompositionLocalProvider(LocalMotion provides stageSpec) {
                Column {
                    Text(t.anim.label, color = p.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (i in 0 until 6) {
                            Box(Modifier.crazyEntrance(i, key, t.anim, forceEnabled = true).size(70.dp, 100.dp).clip(RoundedCornerShape(10.dp))
                                .background(Brush.linearGradient(listOf(p.primary, p.secondary))), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.crazyEntrance(6, key, t.anim, forceEnabled = true).fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(p.surfaceFocused),
                        contentAlignment = Alignment.Center) { Text("MENÜPUNKT", color = p.text, fontSize = 14.sp, letterSpacing = 2.sp) }
                }
            }
            is PreviewTarget.Focus -> CompositionLocalProvider(LocalMotion provides stageSpec.copy(focus = t.effect)) {
                Column {
                    Text("${t.effect.label} – mit den Pfeiltasten hierher wechseln", color = p.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { for (i in 1..3) TvButton("Feld $i", onClick = {}) }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { for (i in 4..6) TvButton("Feld $i", onClick = {}) }
                }
            }
            is PreviewTarget.Page -> CompositionLocalProvider(LocalMotion provides stageSpec) {
                PageTransitionHost(key = key, modifier = Modifier.fillMaxSize(), override = t.type, forceEnabled = true) {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(if (key % 2 == 0) listOf(p.primary, p.bgTop) else listOf(p.secondary, p.bgBottom))),
                        contentAlignment = Alignment.Center) {
                        Text("${t.type.label}\nSeite ${key % 2 + 1}", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            is PreviewTarget.Exit -> {
                var visible by remember(key) { mutableStateOf(true) }
                LaunchedEffect(key) { delay(250); visible = false; delay(1200); visible = true }
                AnimatedVisibility(visible, enter = EnterTransition.None, exit = exitTransitionFor(stageSpec.copy(pageExit = t.anim)),
                    modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(p.secondary, p.bgTop))), contentAlignment = Alignment.Center) {
                        Text("Ausgang: ${t.anim.label}", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
