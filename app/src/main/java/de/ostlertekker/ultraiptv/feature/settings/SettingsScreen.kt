package de.ostlertekker.ultraiptv.feature.settings

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.BuildConfig
import de.ostlertekker.ultraiptv.core.animation.ThemeBackground
import de.ostlertekker.ultraiptv.core.data.AppSettings
import de.ostlertekker.ultraiptv.core.data.EpgRepository
import de.ostlertekker.ultraiptv.core.data.Keys
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.data.SyncRepository
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.database.ProfileEntity
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.design.ThemeEngine
import de.ostlertekker.ultraiptv.core.design.ThemeRegistry
import de.ostlertekker.ultraiptv.core.device.DeviceCapabilityManager
import de.ostlertekker.ultraiptv.core.model.Accuracy
import de.ostlertekker.ultraiptv.core.model.AnimationProfile
import de.ostlertekker.ultraiptv.core.model.BufferProfile
import de.ostlertekker.ultraiptv.core.model.InfoEntry
import de.ostlertekker.ultraiptv.core.model.OutroMode
import de.ostlertekker.ultraiptv.core.model.QualityMode
import de.ostlertekker.ultraiptv.core.player.PlayerEngine
import de.ostlertekker.ultraiptv.core.player.UltraQualityEngine
import de.ostlertekker.ultraiptv.core.security.CredentialStore
import de.ostlertekker.ultraiptv.core.security.InputValidator
import de.ostlertekker.ultraiptv.core.ui.ActionRow
import de.ostlertekker.ultraiptv.core.ui.ChoiceRow
import de.ostlertekker.ultraiptv.core.ui.InfoRow
import de.ostlertekker.ultraiptv.core.ui.PinDialog
import de.ostlertekker.ultraiptv.core.ui.SectionHeader
import de.ostlertekker.ultraiptv.core.ui.ToggleRow
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.TvTextField
import de.ostlertekker.ultraiptv.core.ui.tvFocusable
import de.ostlertekker.ultraiptv.feature.profiles.ProfileAvatar
import de.ostlertekker.ultraiptv.feature.profiles.ProfilesViewModel
import de.ostlertekker.ultraiptv.feature.upscaling.UltraAIUpscaling
import de.ostlertekker.ultraiptv.ui.Routes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

enum class SettingsSection(val label: String) {
    GENERAL("Allgemein"), ACCOUNTS("Konten (Multi-Account)"), UPDATES("App-Updates"), NOTIFICATIONS("Benachrichtigungen"), APPEARANCE("Darstellung"), THEMES("Themes"), STUDIO("Animation Studio"), ANIMATIONS("Animationen"), INTRO("Intro"),
    AUDIO("Audio"), PLAYBACK("Wiedergabe"), QUALITY("Bildqualität"), UPSCALING("KI-Upscaling"), SMART_SKIP("Smart Skip"),
    EPG("EPG"), PROFILES("Profile"), PARENTAL("Jugendschutz"), NETWORK("Netzwerk & Speedtest"), ANALYSIS("Sender-Analyse"),
    BACKUP("Backup & Sync"), WATCH_TOGETHER("Watch Together"), DIAGNOSTICS("Diagnose"), ABOUT("Über"),
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val repo: SettingsRepository,
    val device: DeviceCapabilityManager,
    private val sync: SyncRepository,
    private val creds: CredentialStore,
    private val epg: EpgRepository,
    private val player: PlayerEngine,
    private val upscaling: UltraAIUpscaling,
    private val user: UserRepository,
) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val syncState = sync.state
    val provider = creds.config
    val activeProfile = user.activeProfile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val encryption get() = creds.encryptionAvailable
    val upscalingStatus by lazy { upscaling.assess() }
    val deviceReport: List<InfoEntry> by lazy { device.report() }
    var epgCount by mutableStateOf(-1)
        private set

    fun <T> set(key: Preferences.Key<T>, value: T) = viewModelScope.launch { repo.set(key, value) }
    fun syncNow() = viewModelScope.launch { sync.syncAll(includeEpg = true) }
    fun syncEpg() = viewModelScope.launch { sync.syncEpg(); loadEpgCount() }
    fun loadEpgCount() = viewModelScope.launch { epgCount = epg.count() }
    fun resetAppearance() = viewModelScope.launch { repo.resetAppearance() }
    fun removeProvider() = viewModelScope.launch { creds.clear() }

    fun updateProfile(p: ProfileEntity) = viewModelScope.launch { user.updateProfile(p) }
    fun checkPinFor(p: ProfileEntity, pin: String): Boolean = user.checkPin(p, pin)

    fun setTheme(id: String) = viewModelScope.launch {
        repo.set(Keys.THEME, id)
        user.activeProfileOnce()?.let { user.updateProfile(it.copy(themeId = id)) }
    }

    fun playbackReport(): List<InfoEntry> {
        val st = player.lastSessionStats ?: player.stats.value
        val any = st.videoMime != null || st.audioMime != null
        fun e(label: String, v: String?, acc: Accuracy = Accuracy.MEASURED) =
            InfoEntry(label, v ?: "Nicht verfügbar", if (v == null) Accuracy.UNAVAILABLE else acc)
        if (!any) return listOf(InfoEntry("Wiedergabe", "Noch keine Wiedergabe in dieser Sitzung", Accuracy.UNAVAILABLE))
        val mode = device.currentDisplayMode()
        return listOf(
            e("Video-Codec", listOfNotNull(st.videoMime, st.videoCodecs).joinToString(" / ").ifBlank { null }),
            e("Video-Decoder", st.videoDecoder),
            e("Quellauflösung", if (st.sourceWidth > 0) UltraQualityEngine.resolutionLabel(st.sourceWidth, st.sourceHeight) else null),
            e("Dekodierte Auflösung", if (st.decodedWidth > 0) UltraQualityEngine.resolutionLabel(st.decodedWidth, st.decodedHeight) else null),
            e("Ausgabeauflösung (Display)", mode?.let { "${it.width}×${it.height}" }),
            e("Quell-Bildrate", if (st.sourceFps > 0) "%.3f fps".format(st.sourceFps) else null),
            e("Display-Bildwiederholrate", mode?.let { "%.2f Hz".format(it.refreshRate) }),
            e("HDR", st.hdr ?: "SDR (kein HDR-Signal in der Quelle)"),
            e("Video-Bitrate (Manifest)", if (st.videoBitrate > 0) "${st.videoBitrate / 1000} kbit/s" else null, Accuracy.ESTIMATED),
            e("Audio", listOfNotNull(st.audioMime, st.audioChannels.takeIf { it > 0 }?.let { "$it Kanäle" }).joinToString(" · ").ifBlank { null }),
            e("Audio-Decoder", st.audioDecoder),
            e("Verworfene Frames", st.droppedFrames.toString()),
            e("Netzwerkdurchsatz", if (st.bandwidthBps > 0) "%.1f Mbit/s".format(st.bandwidthBps / 1_000_000.0) else null, Accuracy.ESTIMATED),
            e("Wiedergabefehler / Wiederherstellungen", "${st.errors} / ${st.recoveries}" + (st.lastError?.let { " (zuletzt: $it)" } ?: "")),
        )
    }
}

private fun <T> cycle(list: List<T>, current: T): T = list[(list.indexOf(current) + 1).mod(list.size)]

@Composable
fun SettingsScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel(), x: SettingsExtrasViewModel = hiltViewModel()) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    var section by remember { mutableStateOf(SettingsSection.GENERAL) }
    val s by vm.settings.collectAsStateWithLifecycle()

    Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        LazyColumn(Modifier.width(200.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text("SETTINGS", color = p.text, fontSize = 26.sp, fontWeight = theme.spec.titleWeight, fontFamily = theme.spec.fontFamily,
                modifier = Modifier.padding(bottom = 10.dp)) }
            items(SettingsSection.entries, key = { it.name }) { sec ->
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(theme.spec.corner.coerceAtMost(14.dp))
                Box(Modifier.fillMaxWidth().tvFocusable(shape, { section = sec }, scaleOnFocus = false, onFocus = { focused = it; if (it) section = sec })
                    .background(if (focused) p.surfaceFocused else if (section == sec) p.surface else androidx.compose.ui.graphics.Color.Transparent, shape)
                    .padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(sec.label, color = if (section == sec || focused) p.text else p.textDim, fontSize = 15.sp,
                        fontWeight = if (section == sec) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (section == SettingsSection.THEMES) ThemeGallery(vm, s)
            else if (section == SettingsSection.STUDIO) AnimationStudio()
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
                item { SectionHeader(section.label) }
                when (section) {
                    SettingsSection.GENERAL -> generalSection(vm, s, nav)
                    SettingsSection.ACCOUNTS -> accountsSection(x, nav)
                    SettingsSection.UPDATES -> updatesSection(x, s, vm)
                    SettingsSection.NOTIFICATIONS -> notificationsSection(x)
                    SettingsSection.ANALYSIS -> channelAnalysisSection(x)
                    SettingsSection.BACKUP -> backupSection(x)
                    SettingsSection.WATCH_TOGETHER -> watchTogetherSection(x)
                    SettingsSection.APPEARANCE -> appearanceSection(vm, s)
                    SettingsSection.ANIMATIONS -> animationSection(vm, s)
                    SettingsSection.INTRO -> introSection(vm, s)
                    SettingsSection.AUDIO -> audioSection(vm, s)
                    SettingsSection.PLAYBACK -> { playbackSection(vm, s); playbackExtras(vm, s, x) }
                    SettingsSection.QUALITY -> qualitySection(vm, s)
                    SettingsSection.UPSCALING -> upscalingSection(vm, s)
                    SettingsSection.SMART_SKIP -> smartSkipSection(vm, s)
                    SettingsSection.EPG -> epgSection(vm, s)
                    SettingsSection.PROFILES -> item { ProfilesSection() }
                    SettingsSection.PARENTAL -> item { ParentalSection(vm, s) }
                    SettingsSection.NETWORK -> networkDiagSection(x, s, vm)
                    SettingsSection.DIAGNOSTICS -> diagnosticsSection(vm, s)
                    SettingsSection.ABOUT -> aboutSection(vm)
                    SettingsSection.THEMES, SettingsSection.STUDIO -> Unit
                }
            }
        }
    }
}

private fun pct(v: Float) = "${(v * 100).toInt()} %"

private fun LazyListScope.generalSection(vm: SettingsViewModel, s: AppSettings, nav: NavController) {
    item {
        val provider by vm.provider.collectAsStateWithLifecycle()
        val sync by vm.syncState.collectAsStateWithLifecycle()
        val cfg = provider
        Column {
            ActionRow("Quelle (Playlist / Xtream)", { nav.navigate(Routes.SETUP) },
                subtitle = cfg?.let { "${it.type.name}${if (it.name.isNotBlank()) " · ${it.name}" else ""}" } ?: "Nicht eingerichtet")
            ActionRow("Jetzt synchronisieren", { vm.syncNow() },
                subtitle = if (sync.running) sync.message else (sync.lastError?.let { "Fehler: $it" }
                    ?: if (s.lastSyncMs > 0) "Zuletzt: ${DateFormat.getDateTimeInstance().format(Date(s.lastSyncMs))}" else "Noch nie"),
                value = if (sync.running) "…" else "↻")
            if (cfg != null) ActionRow("Quelle entfernen", { vm.removeProvider() }, subtitle = "Löscht gespeicherte Zugangsdaten")
            InfoRow("Zugangsdaten-Speicher", if (vm.encryption) "Verschlüsselt (Android Keystore, AES-256)" else "Keystore nicht verfügbar – unverschlüsselt",
                if (vm.encryption) Accuracy.MEASURED else Accuracy.MEASURED)
        }
    }
}

private fun LazyListScope.appearanceSection(vm: SettingsViewModel, s: AppSettings) {
    item {
        val accentName = ThemeEngine.accentPresets.firstOrNull { it.second == s.accentArgb }?.first ?: "Eigene"
        Column {
            ChoiceRow("Theme", ThemeRegistry.byId(s.themeId).displayName, {
                val ids = ThemeRegistry.all().map { it.id }; vm.setTheme(cycle(ids, s.themeId))
            }, subtitle = "Weitere Optionen mit Live-Vorschau unter „Themes“")
            ChoiceRow("Akzentfarbe", accentName, {
                val vals = ThemeEngine.accentPresets.map { it.second }
                vm.set(Keys.ACCENT, cycle(vals, if (s.accentArgb in vals) s.accentArgb else 0L))
            })
            var bg by remember { mutableStateOf(s.customBackgroundUrl) }
            TvTextField(bg, { bg = it }, "Eigenes Hintergrundbild (Bild-URL, leer = keines)", modifier = Modifier.padding(vertical = 6.dp),
                error = if (bg.isNotBlank()) InputValidator.url(bg) else null)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Hintergrund übernehmen", onClick = { if (bg.isBlank() || InputValidator.url(bg) == null) vm.set(Keys.CUSTOM_BG, bg.trim()) })
                TvButton("Auf Standard zurücksetzen", onClick = { vm.resetAppearance() })
            }
        }
    }
}

private fun LazyListScope.animationSection(vm: SettingsViewModel, s: AppSettings) {
    item {
        Column {
            ToggleRow("Automatisch nach Gerät", s.animationAuto, { vm.set(Keys.ANIM_AUTO, it) },
                subtitle = "Empfohlen für dieses Gerät: ${vm.device.recommendedAnimation.label} (Profil ${vm.device.tier.label})")
            ChoiceRow("Animationsprofil", s.animationProfile.label, {
                vm.set(Keys.ANIM_AUTO, false); vm.set(Keys.ANIM_PROFILE, cycle(AnimationProfile.entries, s.animationProfile).name)
            }, subtitle = "ECONOMY: minimale Effekte · BALANCED: flüssig · ULTRA: volle Effekte")
            ToggleRow("Bewegung reduzieren", s.reduceMotion, { vm.set(Keys.REDUCE_MOTION, it) }, subtitle = "Keine Hintergrundbewegung, keine Übergangsanimationen")
            ToggleRow("Hintergrundanimation", s.backgroundAnimation, { vm.set(Keys.BG_ANIM, it) })
            ChoiceRow("Animationsintensität", pct(s.animationIntensity), { vm.set(Keys.ANIM_INTENSITY, cycle(listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f), s.animationIntensity)) })
            ChoiceRow("Partikelintensität", pct(s.particleIntensity), { vm.set(Keys.PARTICLE_INTENSITY, cycle(listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 1.25f), s.particleIntensity)) })
            Text("Effekte pausieren automatisch, wenn die App im Hintergrund ist oder ein Video läuft.", color = LocalUltraTheme.current.palette.textDim,
                fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

private fun LazyListScope.introSection(vm: SettingsViewModel, s: AppSettings) {
    item {
        Column {
            ToggleRow("Startintro anzeigen", s.introEnabled, { vm.set(Keys.INTRO, it) })
            ToggleRow("Intro-Sound", s.introSound, { vm.set(Keys.INTRO_SOUND, it) })
            ActionRow("Volles Intro beim nächsten Start erneut zeigen", { vm.set(Keys.INTRO_PLAYED, false) },
                subtitle = "Erststart: volle Animation · danach: kurze Version", value = if (s.introPlayedOnce) "›" else "✓")
            Text("Das Intro lässt sich jederzeit mit einer beliebigen Taste überspringen.", color = LocalUltraTheme.current.palette.textDim,
                fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

private fun LazyListScope.audioSection(vm: SettingsViewModel, s: AppSettings) {
    item {
        val a = vm.device.audioCaps
        Column {
            ToggleRow("Menü-Sounds", s.uiSounds, { vm.set(Keys.UI_SOUNDS, it) })
            ToggleRow("Theme-spezifische Sounds", s.themeSounds, { vm.set(Keys.THEME_SOUNDS, it) })
            ChoiceRow("UI-Lautstärke", pct(s.uiVolume), { vm.set(Keys.UI_VOLUME, cycle(listOf(0.1f, 0.25f, 0.5f, 0.75f, 1f), s.uiVolume)) })
            SectionHeader("Audio-Ausgabe (gemessen)")
            InfoRow("Dolby Digital (AC-3)", if (a.ac3) "Passthrough möglich" else "Nicht unterstützt → PCM", Accuracy.MEASURED)
            InfoRow("Dolby Digital Plus", if (a.eac3) "Passthrough möglich" else "Nicht unterstützt", Accuracy.MEASURED)
            InfoRow("Dolby Atmos (DD+ JOC)", if (a.eac3Joc) "Passthrough möglich" else "Nicht von der HDMI-Kette gemeldet", Accuracy.MEASURED)
            InfoRow("DTS", if (a.dts) "Passthrough möglich" else "Nicht unterstützt", Accuracy.MEASURED)
            Text("Passthrough wird automatisch genutzt, wenn Gerät, AV-Receiver/TV und Quelle es unterstützen. Eine Audio-Verzögerung (Lip-Sync) stellst du auf Fire TV systemweit unter Einstellungen › Display & Sound ein.",
                color = LocalUltraTheme.current.palette.textDim, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

private val languages = listOf("" to "Automatisch", "de" to "Deutsch", "en" to "Englisch", "fr" to "Französisch", "es" to "Spanisch", "it" to "Italienisch", "tr" to "Türkisch")

private fun LazyListScope.playbackSection(vm: SettingsViewModel, s: AppSettings) {
    item {
        val profile by vm.activeProfile.collectAsStateWithLifecycle()
        val prof = profile
        Column {
            ChoiceRow("Puffer", s.bufferProfile.label, { vm.set(Keys.BUFFER, cycle(BufferProfile.entries, s.bufferProfile).name) },
                subtitle = "Groß = stabiler bei schwankendem Netz, Niedrige Latenz = schnelleres Umschalten")
            ChoiceRow("Live-Stream-Format", if (s.liveFormat == "m3u8") "HLS (m3u8)" else "MPEG-TS (ts)",
                { vm.set(Keys.LIVE_FORMAT, if (s.liveFormat == "ts") "m3u8" else "ts") }, subtitle = "Nur für Xtream-Anbieter")
            ToggleRow("Live-Vorschau in der Senderliste", s.livePreview, { vm.set(Keys.LIVE_PREVIEW, it) },
                subtitle = "Auf Entry-Geräten automatisch deaktiviert")
            if (prof != null) {
                ChoiceRow("Bevorzugte Audiosprache", languages.firstOrNull { it.first == prof.audioLanguage }?.second ?: prof.audioLanguage, {
                    val next = cycle(languages.map { it.first }, prof.audioLanguage); vm.updateProfile(prof.copy(audioLanguage = next))
                }, subtitle = "Pro Profil")
                ChoiceRow("Bevorzugte Untertitelsprache", languages.firstOrNull { it.first == prof.subtitleLanguage }?.second ?: prof.subtitleLanguage, {
                    val next = cycle(languages.map { it.first }, prof.subtitleLanguage); vm.updateProfile(prof.copy(subtitleLanguage = next))
                }, subtitle = "Pro Profil")
            }
        }
    }
}

private fun LazyListScope.qualitySection(vm: SettingsViewModel, s: AppSettings) {
    item {
        val modes = vm.device.displayModes()
        val decoderMax = vm.device.maxDecoderHeight()
        val displayMax = modes.maxOfOrNull { it.height } ?: 0
        Column {
            ChoiceRow("Qualitätsmodus", s.qualityMode.label, { vm.set(Keys.QUALITY, cycle(QualityMode.entries, s.qualityMode).name) },
                subtitle = "AUTO: adaptiv bis Display/Decoder-Grenze · BESTE: höchste Bitrate · DATENSPARER: max. 720p")
            if (s.qualityMode == QualityMode.MANUAL) ChoiceRow("Max. Auflösung (manuell)", "${s.manualMaxHeight}p",
                { vm.set(Keys.MANUAL_HEIGHT, cycle(listOf(480, 720, 1080, 1440, 2160), s.manualMaxHeight)) })
            ToggleRow("Bildwiederholrate an Inhalt anpassen", s.matchFrameRate, { vm.set(Keys.MATCH_FPS, it) },
                subtitle = "Schaltet z. B. bei 25-fps-Sendern auf 50 Hz, wenn das Display den Modus anbietet")
            SectionHeader("Geräte-Fähigkeiten")
            InfoRow("Höchste Decoder-Auflösung", if (decoderMax > 0) "${decoderMax}p (Hardware)" else "unbekannt", if (decoderMax > 0) Accuracy.MEASURED else Accuracy.UNAVAILABLE)
            InfoRow("Höchster Display-Modus", if (displayMax > 0) "${displayMax}p" else "unbekannt", if (displayMax > 0) Accuracy.MEASURED else Accuracy.UNAVAILABLE)
            InfoRow("Native 4K-Wiedergabe", if (decoderMax >= 2160 && displayMax >= 2160) "Möglich (Decoder + Display 2160p)" else "Nicht möglich auf dieser Kette", Accuracy.MEASURED)
            InfoRow("120 FPS", UltraQualityEngine.highFrameRateStatus(120f, vm.device.decoders.any { it.supports4k120 }, modes), Accuracy.MEASURED)
            InfoRow("HDR", vm.device.hdrTypes().ifEmpty { listOf("Kein HDR vom Display gemeldet") }.joinToString(", "), Accuracy.MEASURED)
            Text("Es wird nie eine höhere Auflösung oder Bildrate angezeigt, als tatsächlich dekodiert und ausgegeben wird.",
                color = LocalUltraTheme.current.palette.textDim, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

private fun LazyListScope.upscalingSection(vm: SettingsViewModel, s: AppSettings) {
    item {
        val st = vm.upscalingStatus
        Column {
            ToggleRow("KI-Upscaling", s.upscalingRequested && st.available, { vm.set(Keys.UPSCALING, it) }, enabled = st.available,
                subtitle = st.headline)
            st.reasons.forEach { r -> InfoRow("•", r, if (st.available) Accuracy.MEASURED else Accuracy.UNAVAILABLE) }
        }
    }
}

private fun LazyListScope.smartSkipSection(vm: SettingsViewModel, s: AppSettings) {
    item {
        Column {
            ToggleRow("Smart Skip aktiv", s.smartSkipEnabled, { vm.set(Keys.SKIP_ENABLED, it) })
            ToggleRow("Nur manuell (Button statt Automatik)", s.skipManualOnly, { vm.set(Keys.SKIP_MANUAL, it) })
            ToggleRow("Intro automatisch überspringen", s.autoSkipIntro, { vm.set(Keys.SKIP_INTRO, it) })
            ToggleRow("Rückblick automatisch überspringen", s.autoSkipRecap, { vm.set(Keys.SKIP_RECAP, it) })
            ToggleRow("Abspann automatisch überspringen", s.autoSkipOutro, { vm.set(Keys.SKIP_OUTRO, it) })
            ChoiceRow("Abspann-Modus", s.outroMode.label, { vm.set(Keys.OUTRO_MODE, cycle(OutroMode.entries, s.outroMode).name) },
                subtitle = "„Nur Credits“ erhält Szenen nach dem Abspann")
            ToggleRow("Nächste Folge automatisch", s.autoNextEpisode, { vm.set(Keys.AUTO_NEXT, it) })
            ChoiceRow("Countdown", "${s.nextCountdownSec} s", { vm.set(Keys.COUNTDOWN, cycle(listOf(5, 10, 15, 20, 30), s.nextCountdownSec)) })
            Text("Automatisch gesprungen wird nur mit Anbieter-Zeitmarken oder deinen bestätigten Markierungen (Sicherheit ≥ 80 %). " +
                "Markierungen setzt du im Player unter „Smart Skip“; sie werden der ganzen Staffel als Vorschlag angeboten. " +
                "Audio-/Bild-Fingerprinting läuft bewusst nicht auf dem Fire TV Stick.",
                color = LocalUltraTheme.current.palette.textDim, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

private fun LazyListScope.epgSection(vm: SettingsViewModel, s: AppSettings) {
    item {
        val sync by vm.syncState.collectAsStateWithLifecycle()
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadEpgCount() }
        var url by remember { mutableStateOf(s.epgUrlOverride) }
        Column {
            ActionRow("EPG jetzt aktualisieren", { vm.syncEpg() }, subtitle = if (sync.running) sync.message
                else "Sendungen gespeichert: ${if (vm.epgCount >= 0) vm.epgCount else "…"}" +
                    (if (s.lastEpgSyncMs > 0) " · zuletzt ${DateFormat.getDateTimeInstance().format(Date(s.lastEpgSyncMs))}" else ""))
            ChoiceRow("Automatische Aktualisierung", "alle ${s.epgRefreshHours} h", { vm.set(Keys.EPG_REFRESH, cycle(listOf(6, 12, 24, 48), s.epgRefreshHours)) })
            ChoiceRow("Zeitraum", "${s.epgDays} Tage", { vm.set(Keys.EPG_DAYS, cycle(listOf(1, 2, 3, 5, 7), s.epgDays)) })
            ChoiceRow("Zeitzonen-Korrektur", "${if (s.epgOffsetHours >= 0) "+" else ""}${s.epgOffsetHours} h",
                { vm.set(Keys.EPG_OFFSET, cycle((-12..12).toList(), s.epgOffsetHours)) },
                subtitle = "Nur nötig, wenn der Anbieter falsche Zeitzonen liefert (Gerätezeitzone: ${java.util.TimeZone.getDefault().id})")
            TvTextField(url, { url = it }, "Eigene XMLTV-Adresse (überschreibt Anbieter-EPG)", modifier = Modifier.padding(vertical = 6.dp),
                error = if (url.isNotBlank()) InputValidator.url(url) else null)
            TvButton("EPG-Adresse speichern", onClick = { if (url.isBlank() || InputValidator.url(url) == null) vm.set(Keys.EPG_URL, url.trim()) })
        }
    }
}

private fun LazyListScope.networkSection(vm: SettingsViewModel, s: AppSettings) {
    item {
        Column {
            InfoRow("Verbindungs-Timeout", "15 s (Wiedergabe: 15 s Verbindung / 20 s Lesen)", Accuracy.MEASURED)
            InfoRow("Automatische Wiederholung", "Bis zu 5 Versuche mit wachsender Wartezeit", Accuracy.MEASURED)
            InfoRow("HTTPS", "Wird genutzt, wenn die Quelle https:// angibt", Accuracy.MEASURED)
            ChoiceRow("Live-Stream-Format", if (s.liveFormat == "m3u8") "HLS (m3u8)" else "MPEG-TS (ts)", { vm.set(Keys.LIVE_FORMAT, if (s.liveFormat == "ts") "m3u8" else "ts") })
        }
    }
}

private fun LazyListScope.diagnosticsSection(vm: SettingsViewModel, s: AppSettings) {
    item { SectionHeader("Gerät") }
    items(vm.deviceReport) { InfoRow(it.label, it.value, it.accuracy) }
    item { SectionHeader("Letzte Wiedergabe") }
    items(vm.playbackReport()) { InfoRow(it.label, it.value, it.accuracy) }
    item {
        SectionHeader("App")
        val th = ThemeEngine.profileFor(s, vm.device.tier)
        InfoRow("Animations-Leistungsmodus", "${th.label}${if (s.animationAuto) " (automatisch)" else ""}", Accuracy.MEASURED)
        InfoRow("KI-Upscaling", vm.upscalingStatus.headline, Accuracy.MEASURED)
        InfoRow("Smart Skip", if (s.smartSkipEnabled) (if (s.skipManualOnly) "Aktiv – nur manuell" else "Aktiv – automatisch ab 80 % Sicherheit") else "Deaktiviert", Accuracy.MEASURED)
        InfoRow("Zugangsdaten verschlüsselt", if (vm.encryption) "Ja" else "Nein", Accuracy.MEASURED)
    }
}

private fun LazyListScope.aboutSection(vm: SettingsViewModel) {
    item {
        val p = LocalUltraTheme.current.palette
        Column {
            Text("OSTLERTEKKER PRESENTS", color = p.textDim, fontSize = 12.sp, letterSpacing = 4.sp)
            Text("ULTRA IPTV", color = p.text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text("THE ULTIMATE STREAMING EXPERIENCE", color = p.primary, fontSize = 13.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))
            InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", Accuracy.MEASURED)
            InfoRow("Gerät", "${vm.device.manufacturer} ${vm.device.model}", Accuracy.MEASURED)
            Text("ULTRA IPTV ist ein Abspieler. Er liefert keine Inhalte. Nutze nur Quellen, für die du eine Berechtigung hast. " +
                "DRM- und Zugangsbeschränkungen werden nicht umgangen.", color = p.textDim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

// ---------------------------------------------------------------- Themes-Galerie

@Composable
private fun ThemeGallery(vm: SettingsViewModel, s: AppSettings) {
    val current = LocalUltraTheme.current
    val p = current.palette
    var focusedId by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        SectionHeader("Themes – Live-Vorschau")
        Text("OK wählt das Theme sofort. Die Vorschau animiert das fokussierte Theme.", color = p.textDim, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        LazyVerticalGrid(GridCells.Adaptive(250.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            items(ThemeRegistry.all().size) { i ->
                val spec = ThemeRegistry.all()[i]
                val resolved = remember(spec.id, s, vm.device.tier) { ThemeEngine.resolve(s.copy(themeId = spec.id, accentArgb = 0L), vm.device.tier) }
                val shape = RoundedCornerShape(spec.corner.coerceAtMost(20.dp))
                Column {
                    Box(Modifier.fillMaxWidth().height(140.dp).tvFocusable(shape, { vm.setTheme(spec.id) }, onFocus = { if (it) focusedId = spec.id })
                        .clip(shape)) {
                        ThemeBackground(resolved, Modifier.fillMaxSize(), animate = focusedId == spec.id)
                        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                            Text(spec.displayName, color = spec.palette.text, fontFamily = spec.fontFamily, fontWeight = spec.titleWeight, fontSize = 18.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(spec.palette.primary, spec.palette.secondary, spec.palette.glow).forEach {
                                    Box(Modifier.size(12.dp).background(it, RoundedCornerShape(6.dp)))
                                }
                            }
                        }
                        if (s.themeId == spec.id) Text("✓ AKTIV", color = spec.palette.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp))
                    }
                    Text(spec.tagline, color = p.textDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
            item {
                Column {
                    ToggleRow("Theme-Sounds", s.themeSounds, { vm.set(Keys.THEME_SOUNDS, it) })
                    ChoiceRow("Partikel", pct(s.particleIntensity), { vm.set(Keys.PARTICLE_INTENSITY, cycle(listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 1.25f), s.particleIntensity)) })
                    ToggleRow("Hintergrundbewegung", s.backgroundAnimation, { vm.set(Keys.BG_ANIM, it) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Profile & Jugendschutz

@Composable
private fun ProfilesSection(pvm: ProfilesViewModel = hiltViewModel()) {
    val p = LocalUltraTheme.current.palette
    val profiles by pvm.profiles.collectAsStateWithLifecycle()
    val active by pvm.active.collectAsStateWithLifecycle()
    val accounts by pvm.accounts.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var pinFor by remember { mutableStateOf<ProfileEntity?>(null) }
    var unlockFor by remember { mutableStateOf<ProfileEntity?>(null) }
    Column {
        profiles.forEach { prof ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(p.surface, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileAvatar(prof, 44)
                Column(Modifier.weight(1f)) {
                    Text(prof.name + if (active?.id == prof.id) "  (aktiv)" else "", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(if (prof.pinHash != null) "PIN geschützt" else "Ohne PIN", if (prof.maxAge > 0) "Altersgrenze ${prof.maxAge}" else "Keine Altersgrenze",
                        prof.themeId?.let { "Theme ${ThemeRegistry.byId(it).displayName}" }).joinToString(" · "), color = p.textDim, fontSize = 12.sp)
                }
                if (active?.id != prof.id) TvButton("Wechseln", onClick = { if (prof.pinHash != null) unlockFor = prof else pvm.select(prof) {} })
                TvButton(if (prof.pinHash != null) "PIN entfernen" else "PIN setzen", onClick = { if (prof.pinHash != null) pvm.setPin(prof, null) else pinFor = prof })
                TvButton("Alter: ${if (prof.maxAge == 0) "–" else prof.maxAge}", onClick = { pvm.update(prof.copy(maxAge = cycle(listOf(0, 6, 12, 16, 18), prof.maxAge))) })
                if (accounts.size > 1) TvButton("Konto: " + (accounts.firstOrNull { it.id == prof.providerId }?.label?.take(14) ?: "Standard"), onClick = {
                    val ids = listOf<String?>(null) + accounts.map { it.id }
                    pvm.update(prof.copy(providerId = ids[(ids.indexOf(prof.providerId) + 1).mod(ids.size)]))
                })
                if (profiles.size > 1) TvButton("Löschen", onClick = { pvm.delete(prof) })
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvTextField(newName, { newName = it; nameError = null }, "Neues Profil – Name", modifier = Modifier.width(320.dp), error = nameError)
            TvButton("Profil anlegen", onClick = {
                val err = InputValidator.profileName(newName)
                if (err != null) nameError = err else { pvm.create(newName); newName = "" }
            }, primary = true)
        }
        Text("Jedes Profil hat eigene Favoriten, eigenen Verlauf, eigenes Theme und eigene Sprachen.", color = p.textDim, fontSize = 12.sp,
            modifier = Modifier.padding(8.dp))
    }
    pinFor?.let { target ->
        var first by remember { mutableStateOf<String?>(null) }
        PinDialog(if (first == null) "Neue PIN für ${target.name}" else "PIN wiederholen", onSubmit = { pin ->
            when {
                InputValidator.pin(pin) != null -> false
                first == null -> { first = pin; true }
                first == pin -> { pvm.setPin(target, pin); pinFor = null; true }
                else -> { first = null; false }
            }
        }, onDismiss = { pinFor = null })
    }
    unlockFor?.let { target ->
        PinDialog("PIN für ${target.name}", onSubmit = { pin ->
            val ok = pvm.checkPin(target, pin); if (ok) { pvm.select(target) {}; unlockFor = null }; ok
        }, onDismiss = { unlockFor = null })
    }
}

@Composable
private fun ParentalSection(vm: SettingsViewModel, s: AppSettings) {
    val p = LocalUltraTheme.current.palette
    val profile by vm.activeProfile.collectAsStateWithLifecycle()
    var askPin by remember { mutableStateOf(false) }
    val prof = profile
    val ageAllows = prof == null || prof.maxAge == 0 || prof.maxAge >= 18
    Column {
        ToggleRow("Erwachsenen-Kategorien anzeigen", s.showAdult && ageAllows, {
            if (!it) vm.set(Keys.SHOW_ADULT, false)
            else if (prof?.pinHash != null) askPin = true
            else vm.set(Keys.SHOW_ADULT, true)
        }, enabled = ageAllows, subtitle = if (!ageAllows) "Für dieses Profil durch Altersgrenze gesperrt" else "Freischalten erfordert die Profil-PIN (falls gesetzt)")
        Text("Kategorien mit Kennzeichnungen wie „XXX“, „Adult“ oder „18+“ werden ausgeblendet. Die meisten Anbieter liefern keine FSK-Angaben pro Titel – " +
            "die Altersgrenze wirkt daher auf diese Kategorien. PINs werden nur als gesalzener PBKDF2-Hash gespeichert.",
            color = p.textDim, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
    }
    if (askPin && prof != null) {
        PinDialog("PIN eingeben", onSubmit = { pin ->
            val ok = vm.checkPinFor(prof, pin); if (ok) { vm.set(Keys.SHOW_ADULT, true); askPin = false }; ok
        }, onDismiss = { askPin = false })
    }
}
