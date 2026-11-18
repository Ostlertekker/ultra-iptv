package de.ostlertekker.ultraiptv.feature.settings

import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
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
import de.ostlertekker.ultraiptv.core.design.DesignOptions
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
import de.ostlertekker.ultraiptv.ui.Routes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** Einstellungs-Kategorien nach dem Vorbild von TiviMate Premium (plus App-Updates und Über). */
enum class SettingsCategory(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    PLAYLISTS("Playlists", Icons.AutoMirrored.Filled.List),
    EPG("EPG", Icons.Default.DateRange),
    APPEARANCE("Erscheinungsbild", Icons.Default.Edit),
    PLAYBACK("Wiedergabe", Icons.Default.PlayArrow),
    REMOTE("Fernbedienung", Icons.Default.Menu),
    PARENTAL("Kindersicherung", Icons.Default.Lock),
    REMINDERS("Erinnerungen", Icons.Default.Notifications),
    BACKUP("Datensicherung", Icons.Default.Done),
    GENERAL("Allgemein", Icons.Default.Settings),
    UPDATES("App-Updates", Icons.Default.Refresh),
    ABOUT("Über", Icons.Default.Info),
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val repo: SettingsRepository,
    val weather: de.ostlertekker.ultraiptv.core.weather.WeatherRepository,
    val news: de.ostlertekker.ultraiptv.core.news.NewsRepository,
    val device: DeviceCapabilityManager,
    private val sync: SyncRepository,
    private val creds: CredentialStore,
    private val epg: EpgRepository,
    val player: PlayerEngine,
    private val user: UserRepository,
    content: de.ostlertekker.ultraiptv.core.data.ContentRepository,
) : ViewModel() {
    val channelGroups = content.channelCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val syncState = sync.state
    val provider = creds.config
    val activeProfile = user.activeProfile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val encryption get() = creds.encryptionAvailable
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

}


private fun <T> cycle(list: List<T>, current: T): T = list[(list.indexOf(current) + 1).mod(list.size)]

private val languages = listOf("" to "Automatisch", "de" to "Deutsch", "en" to "Englisch", "fr" to "Französisch", "es" to "Spanisch", "it" to "Italienisch", "tr" to "Türkisch")

@Composable
fun SettingsScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel(), x: SettingsExtrasViewModel = hiltViewModel()) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    // rememberSaveable: Kommt man aus einer Aktion zurück (z. B. Update-Installation), bleibt der gewählte Reiter erhalten
    var category by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsCategory.PLAYLISTS) }
    var errorsOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val railFocus = remember { SettingsCategory.entries.associateWith { androidx.compose.ui.focus.FocusRequester() } }
    val railScope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.activity.compose.BackHandler(enabled = errorsOpen) { errorsOpen = false }
    val s by vm.settings.collectAsStateWithLifecycle()

    Row(Modifier.fillMaxSize()) {
        // Linke Spalte wie bei TiviMate: Symbol + Bezeichnung, dunkler Hintergrund
        LazyColumn(Modifier.width(270.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.35f)),
            contentPadding = PaddingValues(vertical = 14.dp, horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                Text("Einstellungen", color = p.text, fontSize = 21.sp, maxLines = 1, softWrap = false, fontWeight = theme.spec.titleWeight,
                    fontFamily = theme.spec.fontFamily, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
            }
            items(SettingsCategory.entries, key = { it.name }) { sec ->
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(10.dp)
                val sel = category == sec
                Row(Modifier.fillMaxWidth().tvFocusable(shape, { category = sec; errorsOpen = false }, focusRequester = railFocus.getValue(sec), scaleOnFocus = false, onFocus = {
                    focused = it
                    if (it && category != sec) {
                        // Nur bei Pfeiltasten-Navigation umschalten. Verschwindet ein gedrückter Knopf (z. B. „Nach Updates suchen“),
                        // springt Android den Fokus auf den ersten Eintrag („Playlists“) – dann zurück zum aktuellen Reiter statt zu wechseln.
                        if (de.ostlertekker.ultraiptv.core.motion.InputActivity.navigatedByArrow()) { category = sec; errorsOpen = false }
                        else railScope.launch { kotlinx.coroutines.delay(30); runCatching { railFocus.getValue(category).requestFocus() } }
                    }
                })
                    .background(if (focused) p.surfaceFocused else if (sel) p.surface else Color.Transparent, shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(sec.icon, null, tint = if (sel || focused) p.text else p.textDim, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(sec.label, color = if (sel || focused) p.text else p.textDim, fontSize = 15.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
        if (errorsOpen) ErrorLogView(vm) { errorsOpen = false }
        else LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(start = 28.dp, end = 32.dp, top = 18.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "title_" + category.name) {
                Text(category.label, color = p.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            }
            when (category) {
                SettingsCategory.PLAYLISTS -> playlistsPage(vm, x, s, nav)
                SettingsCategory.EPG -> epgPage(vm, s)
                SettingsCategory.APPEARANCE -> appearancePage(vm, s)
                SettingsCategory.PLAYBACK -> playbackPage(vm, s)
                SettingsCategory.REMOTE -> remotePage(vm, s)
                SettingsCategory.PARENTAL -> item { ParentalSection(vm, s) }
                SettingsCategory.REMINDERS -> {
                    item { SHeader("Erinnerungen & Hinweise") }
                    notificationsSection(x)
                }
                SettingsCategory.BACKUP -> backupSection(x)
                SettingsCategory.GENERAL -> generalPage(vm, s)
                SettingsCategory.UPDATES -> {
                    updatesSection(x, s, vm)
                    item { SHeader("Fehler") }
                    item {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val n = remember(errorsOpen) { errorEntries(ctx, vm).size }
                        SRow("Fehlermeldungen", if (n == 0) "Keine Fehler aufgezeichnet" else "$n Meldung(en) – zum Abfotografieren öffnen") { errorsOpen = true }
                    }
                }
                SettingsCategory.ABOUT -> aboutSection(vm)
            }
        }
    }
}

// ------------------------------------------------------------ Zeilen im TiviMate-Stil

@Composable
private fun SHeader(t: String) {
    Text(t.uppercase(), color = LocalUltraTheme.current.palette.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 4.dp))
}

/** Einstellung: Titel, darunter der aktuelle Wert in Grau. OK wechselt bzw. öffnet. */
@Composable
private fun SRow(title: String, value: String? = null, onClick: () -> Unit) {
    val p = LocalUltraTheme.current.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().tvFocusable(shape, onClick, scaleOnFocus = false, onFocus = { focused = it })
        .background(if (focused) p.surfaceFocused else Color.Transparent, shape).padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(title, color = p.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!value.isNullOrBlank()) Text(value, color = p.textDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Ein/Aus-Einstellung mit Schalter rechts. */
@Composable
private fun SSwitch(title: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val p = LocalUltraTheme.current.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().tvFocusable(shape, { if (enabled) onChange(!checked) }, scaleOnFocus = false, onFocus = { focused = it })
        .background(if (focused) p.surfaceFocused else Color.Transparent, shape).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) p.text else p.textDim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = p.textDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(14.dp))
        val on = checked && enabled
        Box(Modifier.size(40.dp, 22.dp).background(if (on) p.primary else Color.White.copy(alpha = 0.22f), RoundedCornerShape(50)).padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(16.dp).background(Color.White, RoundedCornerShape(50)))
        }
    }
}

// ------------------------------------------------------------ Seiten

private fun LazyListScope.playlistsPage(vm: SettingsViewModel, x: SettingsExtrasViewModel, s: AppSettings, nav: NavController) {
    item {
        val provider by vm.provider.collectAsStateWithLifecycle()
        val sync by vm.syncState.collectAsStateWithLifecycle()
        val cfg = provider
        Column {
            SRow("Playlist bearbeiten", cfg?.let { "${it.type.name}${if (it.name.isNotBlank()) " · ${it.name}" else ""}" } ?: "Nicht eingerichtet") { nav.navigate(Routes.SETUP) }
            SRow("Playlist aktualisieren", if (sync.running) sync.message else (sync.lastError?.let { "Fehler: $it" }
                ?: if (s.lastSyncMs > 0) "Zuletzt: ${DateFormat.getDateTimeInstance().format(Date(s.lastSyncMs))}" else "Noch nie")) { vm.syncNow() }
            SRow("Live-Stream-Format", if (s.liveFormat == "m3u8") "HLS (m3u8)" else "MPEG-TS (ts) · nur Xtream") {
                vm.set(Keys.LIVE_FORMAT, if (s.liveFormat == "ts") "m3u8" else "ts")
            }
            if (cfg != null) SRow("Playlist entfernen", "Löscht die gespeicherten Zugangsdaten") { vm.removeProvider() }
        }
    }
    item { SHeader("Weitere Playlists") }
    accountsSection(x, nav)
    item { SHeader("Gruppen") }
    item {
        val groups by vm.channelGroups.collectAsStateWithLifecycle()
        val hidden = s.hiddenGroups.split('\n').filter { it.isNotBlank() }.toSet()
        Column {
            if (groups.isEmpty()) Text("Noch keine Gruppen – zuerst die Playlist aktualisieren.", color = LocalUltraTheme.current.palette.textDim, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp))
            groups.forEach { g ->
                SSwitch(g, g !in hidden) { visible -> vm.set(Keys.HIDDEN_GROUPS, (if (visible) hidden - g else hidden + g).joinToString("\n")) }
            }
        }
    }
}

private fun LazyListScope.epgPage(vm: SettingsViewModel, s: AppSettings) {
    item {
        val sync by vm.syncState.collectAsStateWithLifecycle()
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadEpgCount() }
        var url by remember { mutableStateOf(s.epgUrlOverride) }
        Column {
            SRow("EPG aktualisieren", if (sync.running) sync.message else "Sendungen: ${if (vm.epgCount >= 0) vm.epgCount else "…"}" +
                (if (s.lastEpgSyncMs > 0) " · zuletzt ${DateFormat.getDateTimeInstance().format(Date(s.lastEpgSyncMs))}" else "")) { vm.syncEpg() }
            SRow("Aktualisierungsintervall", "Alle ${s.epgRefreshHours} Stunden") { vm.set(Keys.EPG_REFRESH, cycle(listOf(6, 12, 24, 48), s.epgRefreshHours)) }
            SRow("Tage im TV-Programm", "${s.epgDays} Tage") { vm.set(Keys.EPG_DAYS, cycle(listOf(1, 2, 3, 5, 7), s.epgDays)) }
            SRow("EPG-Zeitverschiebung", "${if (s.epgOffsetHours >= 0) "+" else ""}${s.epgOffsetHours} Stunden") {
                vm.set(Keys.EPG_OFFSET, cycle((-12..12).toList(), s.epgOffsetHours))
            }
            SHeader("EPG-Quelle")
            TvTextField(url, { url = it }, "XMLTV-Adresse (leer = EPG der Playlist)", modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                error = if (url.isNotBlank()) InputValidator.url(url) else null)
            SRow("EPG-Quelle speichern", s.epgUrlOverride.ifBlank { "EPG der Playlist" }) { if (url.isBlank() || InputValidator.url(url) == null) vm.set(Keys.EPG_URL, url.trim()) }
        }
    }
}

private fun LazyListScope.appearancePage(vm: SettingsViewModel, s: AppSettings) {
    item {
        val accentName = ThemeEngine.accentPresets.firstOrNull { it.second == s.accentArgb }?.first ?: "Eigene"
        Column {
            SRow("Design", ThemeRegistry.byId(s.themeId).displayName) { vm.setTheme(cycle(ThemeRegistry.all().map { it.id }, s.themeId)) }
            SRow("Akzentfarbe", accentName) {
                val vals = ThemeEngine.accentPresets.map { it.second }
                vm.set(Keys.ACCENT, cycle(vals, if (s.accentArgb in vals) s.accentArgb else 0L))
            }
            SRow("Schriftart", DesignOptions.fontNames.getOrElse(s.fontStyle) { "Standard" }) {
                vm.set(Keys.FONT_STYLE, (s.fontStyle + 1) % DesignOptions.fontNames.size)
            }
            SHeader("Senderliste")
            SSwitch("Sendernummern anzeigen", s.showChannelNumbers) { vm.set(Keys.SHOW_NUMBERS, it) }
            SSwitch("Senderlogos anzeigen", s.showChannelLogos) { vm.set(Keys.SHOW_LOGOS, it) }
            SSwitch("Laufende Sendung anzeigen", s.showChannelEpg) { vm.set(Keys.SHOW_CH_EPG, it) }
            SSwitch("Sendungsfortschritt anzeigen", s.showChannelProgress) { vm.set(Keys.SHOW_CH_PROGRESS, it) }
            SSwitch("Vorschau beim Auswählen", s.livePreview, "Auf schwachen Geräten automatisch aus") { vm.set(Keys.LIVE_PREVIEW, it) }
            SRow("Listendichte", listOf("Kompakt", "Normal", "Groß")[s.channelDensity.coerceIn(0, 2)]) { vm.set(Keys.CHANNEL_DENSITY, (s.channelDensity + 1) % 3) }
            SHeader("Uhr")
            SSwitch("Uhr in der Menüleiste", s.menuClock) { vm.set(Keys.MENU_CLOCK, it) }
            SSwitch("24-Stunden-Format", s.clock24h) { vm.set(Keys.CLOCK_24H, it) }
        }
    }
}

private fun LazyListScope.playbackPage(vm: SettingsViewModel, s: AppSettings) {
    item {
        val profile by vm.activeProfile.collectAsStateWithLifecycle()
        val prof = profile
        Column {
            SRow("Puffergröße", s.bufferProfile.label) { vm.set(Keys.BUFFER, cycle(BufferProfile.entries, s.bufferProfile).name) }
            SSwitch("Bildwiederholrate anpassen (AFR)", s.matchFrameRate, "Schaltet das Display auf die Bildrate des Inhalts, wenn möglich") { vm.set(Keys.MATCH_FPS, it) }
            SRow("Seitenverhältnis", de.ostlertekker.ultraiptv.feature.player.aspectNames[s.defaultAspect.coerceIn(0, 3)]) { vm.set(Keys.DEFAULT_ASPECT, (s.defaultAspect + 1) % 4) }
            SHeader("Sprache")
            if (prof != null) {
                SRow("Audiosprache", languages.firstOrNull { it.first == prof.audioLanguage }?.second ?: prof.audioLanguage) {
                    vm.updateProfile(prof.copy(audioLanguage = cycle(languages.map { it.first }, prof.audioLanguage)))
                }
                SRow("Untertitelsprache", languages.firstOrNull { it.first == prof.subtitleLanguage }?.second ?: prof.subtitleLanguage) {
                    vm.updateProfile(prof.copy(subtitleLanguage = cycle(languages.map { it.first }, prof.subtitleLanguage)))
                }
            }
            SHeader("Info-Leiste")
            SRow("Ausblenden nach", "${s.osdSeconds} Sekunden") { vm.set(Keys.OSD_SECONDS, cycle(listOf(3, 5, 8, 12, 20), s.osdSeconds.takeIf { it in listOf(3, 5, 8, 12, 20) } ?: 5)) }
            SSwitch("Info beim Senderwechsel", s.zapBanner) { vm.set(Keys.ZAP_BANNER, it) }
            SSwitch("Uhr im Player", s.playerClock) { vm.set(Keys.PLAYER_CLOCK, it) }
            SHeader("Filme & Serien")
            SSwitch("Nächste Folge automatisch", s.autoNextEpisode) { vm.set(Keys.AUTO_NEXT, it) }
            SRow("Countdown bis zur nächsten Folge", "${s.nextCountdownSec} Sekunden") { vm.set(Keys.COUNTDOWN, cycle(listOf(5, 10, 15, 20, 30), s.nextCountdownSec)) }
        }
    }
}

private fun LazyListScope.remotePage(vm: SettingsViewModel, s: AppSettings) {
    item {
        Column {
            SRow("OK-Taste im Live-Player", if (s.okAction == 1) "Senderliste" else "Info-Leiste") { vm.set(Keys.OK_ACTION, (s.okAction + 1) % 2) }
            SRow("Pfeil hoch/runter im Live-Player", if (s.upDownAction == 1) "Senderliste öffnen" else "Sender wechseln") { vm.set(Keys.UPDOWN_ACTION, (s.upDownAction + 1) % 2) }
            SSwitch("Kanalwechsel umkehren", s.invertChannelKeys, "Pfeil hoch = nächster Sender") { vm.set(Keys.INVERT_ZAP, it) }
            SSwitch("Senderwahl über Zifferntasten", s.numberZap) { vm.set(Keys.NUMBER_ZAP, it) }
        }
    }
}

private fun LazyListScope.generalPage(vm: SettingsViewModel, s: AppSettings) {
    item {
        Column {
            SRow("Beim App-Start", listOf("Startseite", "Live-TV", "Letzten Sender abspielen", "TV-Programm")[s.startAction.coerceIn(0, 3)]) {
                vm.set(Keys.START_ACTION, (s.startAction + 1) % 4)
            }
            SSwitch("Startintro anzeigen", s.introEnabled) { vm.set(Keys.INTRO, it) }
            SRow("Sleep-Timer beim Abspielen", if (s.sleepTimerMin == 0) "Aus" else "${s.sleepTimerMin} Minuten") {
                vm.set(Keys.SLEEP_TIMER, cycle(listOf(0, 15, 30, 45, 60, 90, 120), s.sleepTimerMin.takeIf { it in listOf(0, 15, 30, 45, 60, 90, 120) } ?: 0))
            }
            SSwitch("Menü-Töne", s.uiSounds) { vm.set(Keys.UI_SOUNDS, it) }
            SHeader("Startseite")
            SSwitch("Wetter anzeigen", s.weatherEnabled, subtitle = "Wetterleiste und Wettersymbol oben neben „ULTRA IPTV“") { vm.set(Keys.WEATHER_ENABLED, it) }
            SSwitch("Nachrichten-Laufschrift", s.newsEnabled) {
                vm.set(Keys.NEWS_ENABLED, it); if (it && s.newsSource == "off") vm.set(Keys.NEWS_SOURCE, "auto")
            }
            SSwitch("Sport-Ticker (nur Live-Spiele)", s.sportTickerEnabled) { vm.set(Keys.SPORT_TICKER_ENABLED, it) }
            SHeader("Wetter")
            var place by remember { mutableStateOf(s.weatherPlace.substringBefore(",")) }
            var msg by remember { mutableStateOf<String?>(null) }
            TvTextField(place, { place = it }, "Ort (z. B. Berlin oder 10115) – leer = kein Wetter",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            SRow("Ort speichern", msg ?: s.weatherPlace.ifBlank { "Kein Ort – Wetter ausgeblendet" }) {
                msg = "Suche …"
                vm.viewModelScope.launch {
                    msg = vm.weather.setPlace(place).fold({ if (it.isBlank()) "Wetter ausgeblendet" else "✓ $it" }, { it.message ?: "Fehler" })
                }
            }
            SHeader("Nachrichten-Laufschrift")
            run {
                val ids = de.ostlertekker.ultraiptv.core.news.NewsSources.choices.map { it.first }
                SRow("Quelle", de.ostlertekker.ultraiptv.core.news.NewsSources.label(s.newsSource) +
                    if (s.newsSource == "auto") " – passend zu Land und Sprache des Wetter-Orts" else "") {
                    vm.set(Keys.NEWS_SOURCE, ids[(ids.indexOf(s.newsSource).coerceAtLeast(0) + 1) % ids.size])
                }
            }
            SSwitch("Lokalnachrichten einmischen", s.newsLocal,
                subtitle = "Deutschland: tagesschau aus deinem Bundesland · weltweit: Google News zu deinem Ort",
                enabled = s.newsEnabled) { vm.set(Keys.NEWS_LOCAL, it) }
            SRow("Erscheinungsbild zurücksetzen", "Design, Farben und Größen auf Standard") { vm.resetAppearance() }
        }
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
        SSwitch("Erwachsenen-Kategorien anzeigen", s.showAdult && ageAllows, if (!ageAllows) "Für dieses Profil durch Altersgrenze gesperrt" else "Freischalten erfordert die Profil-PIN (falls gesetzt)", enabled = ageAllows) {
            if (!it) vm.set(Keys.SHOW_ADULT, false)
            else if (prof?.pinHash != null) askPin = true
            else vm.set(Keys.SHOW_ADULT, true)
        }
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

private fun LazyListScope.aboutSection(vm: SettingsViewModel) {
    item {
        val p = LocalUltraTheme.current.palette
        Column {
            Text("OSTLERTEKKER PRESENTS", color = p.textDim, fontSize = 12.sp, letterSpacing = 4.sp)
            Text("ULTRA IPTV", color = p.text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text("DAS ULTIMATIVE STREAMING-ERLEBNIS", color = p.primary, fontSize = 13.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))
            InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", Accuracy.MEASURED)
            InfoRow("Gerät", "${vm.device.manufacturer} ${vm.device.model}", Accuracy.MEASURED)
            Text("ULTRA IPTV ist ein Abspieler. Er liefert keine Inhalte. Nutze nur Quellen, für die du eine Berechtigung hast. " +
                "DRM- und Zugangsbeschränkungen werden nicht umgangen.", color = p.textDim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}


// ------------------------------------------------------------ Fehlermeldungen (zum Abfotografieren)

private fun errorEntries(context: android.content.Context, vm: SettingsViewModel): List<Pair<String, String>> {
    val g = de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard
    val st = vm.player.lastSessionStats ?: vm.player.stats.value
    return listOfNotNull(
        g.lastCrash(context)?.let { "ABSTURZ" to it },
        g.lastFreeze(context)?.let { "HÄNGER" to it },
        vm.weather.lastError?.let { "WETTER" to it },
        vm.news.lastError?.let { "NACHRICHTEN" to it },
        st.lastError?.let { "LETZTER WIEDERGABEFEHLER" to (it + "\nFehler / Wiederherstellungen in der Sitzung: ${st.errors} / ${st.recoveries}") },
    )
}

/** Vollständiges Fehlerprotokoll, zeilenweise mit der Fernbedienung scrollbar. Zurück schließt die Ansicht. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ErrorLogView(vm: SettingsViewModel, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val p = LocalUltraTheme.current.palette
    var version by remember { mutableStateOf(0) }
    val entries = remember(version) { errorEntries(context, vm) }
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { first.requestFocus() } }
    LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(start = 28.dp, end = 32.dp, top = 18.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Text("Fehlermeldungen", color = p.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("ULTRA IPTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${vm.device.manufacturer} ${vm.device.model} · Android ${android.os.Build.VERSION.RELEASE}",
                color = p.textDim, fontSize = 13.sp)
            Text("Mit ▲▼ scrollen und abfotografieren · Zurück schließt", color = p.primary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (entries.isEmpty()) item {
            Box(Modifier.focusRequester(first).tvFocusable(RoundedCornerShape(10.dp), onClose, scaleOnFocus = false)) {
                Text("Keine Abstürze, Hänger oder Wiedergabefehler aufgezeichnet.", color = p.text, fontSize = 15.sp, modifier = Modifier.padding(14.dp))
            }
        }
        entries.forEachIndexed { ei, (title, text) ->
            item(key = "t$ei$version") { SHeader(title) }
            // Blöcke à 10 Zeilen: jeder Block ist fokussierbar, damit die Fernbedienung Stück für Stück scrollt
            text.lines().chunked(10).forEachIndexed { ci, chunk ->
                item(key = "c$ei-$ci-$version") {
                    var focused by remember { mutableStateOf(false) }
                    Text(chunk.joinToString("\n"), color = p.text, fontSize = 12.sp, lineHeight = 16.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth()
                            .then(if (ei == 0 && ci == 0) Modifier.focusRequester(first) else Modifier)
                            .tvFocusable(RoundedCornerShape(6.dp), {}, scaleOnFocus = false, onFocus = { focused = it })
                            .background(Color.Black.copy(alpha = if (focused) 0.65f else 0.45f), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
        if (entries.isNotEmpty()) item {
            Spacer(Modifier.height(10.dp))
            SRow("Protokoll löschen", "Löscht Absturz- und Hänger-Berichte") { de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard.clear(context); version++ }
        }
    }
}
