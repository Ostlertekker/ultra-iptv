package de.ostlertekker.ultraiptv.feature.settings

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import de.ostlertekker.ultraiptv.core.ui.fadeOnChange
import de.ostlertekker.ultraiptv.core.jellyfin.JfOptions
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
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

/** Einstellungs-Kategorien: erst Quellen, dann Bildschirme der App, dann Bedienung/Aussehen, zuletzt System. */
enum class SettingsCategory(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    PLAYLISTS(tr("Playlists"), Icons.AutoMirrored.Filled.List),
    EPG(tr("TV-Programm (EPG)"), Icons.Default.DateRange),
    HOME(tr("Startseite"), Icons.Default.Home),
    LIVETV(tr("Live-TV"), Icons.Default.Star),
    JELLYFIN(tr("Jellyfin"), Icons.Default.PlayArrow),
    PLAYBACK(tr("Wiedergabe"), Icons.Default.PlayArrow),
    REMOTE(tr("Fernbedienung"), Icons.Default.Menu),
    APPEARANCE(tr("Erscheinungsbild"), Icons.Default.Edit),
    REMINDERS(tr("Erinnerungen"), Icons.Default.Notifications),
    PARENTAL(tr("Kindersicherung"), Icons.Default.Lock),
    BACKUP(tr("Datensicherung"), Icons.Default.Done),
    GENERAL(tr("Allgemein"), Icons.Default.Settings),
    UPDATES(tr("App-Updates"), Icons.Default.Refresh),
    ABOUT(tr("Über & Fehler"), Icons.Default.Info),
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val repo: SettingsRepository,
    val weather: de.ostlertekker.ultraiptv.core.weather.WeatherRepository,
    val news: de.ostlertekker.ultraiptv.core.news.NewsRepository,
    val jellyfin: de.ostlertekker.ultraiptv.core.jellyfin.JellyfinRepository,
    val seerr: de.ostlertekker.ultraiptv.core.seerr.SeerrRepository,
    val device: DeviceCapabilityManager,
    private val sync: SyncRepository,
    private val creds: CredentialStore,
    private val epg: EpgRepository,
    val player: PlayerEngine,
    private val user: UserRepository,
    content: de.ostlertekker.ultraiptv.core.data.ContentRepository,
) : ViewModel() {
    val channelGroups = content.channelCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val movieGroups = content.allMovieCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val seriesGroups = content.allSeriesCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val syncState = sync.state
    val provider = creds.config
    val activeProfile = user.activeProfile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val encryption get() = creds.encryptionAvailable
    val deviceReport: List<InfoEntry> by lazy { device.report() }
    var epgCount by androidx.compose.runtime.mutableIntStateOf(-1)
        private set

    fun <T> set(key: Preferences.Key<T>, value: T) = viewModelScope.launch { repo.set(key, value) }
    fun syncNow() = viewModelScope.launch { sync.syncAll(includeEpg = true) }
    fun syncEpg() = viewModelScope.launch { sync.syncEpg(); loadEpgCount() }
    fun loadEpgCount() = viewModelScope.launch { epgCount = epg.count() }
    fun resetAppearance() = viewModelScope.launch { repo.resetAppearance() }
    fun removeProvider() = viewModelScope.launch { creds.clear() }

    fun updateProfile(p: ProfileEntity) = viewModelScope.launch { user.updateProfile(p) }
    fun checkPinFor(p: ProfileEntity, pin: String): Boolean = user.checkPin(p, pin)


}


private fun <T> cycle(list: List<T>, current: T): T = list[(list.indexOf(current) + 1).mod(list.size)]

private val languages = listOf("" to tr("Automatisch"), "de" to tr("Deutsch"), "en" to tr("Englisch"), "fr" to tr("Französisch"), "es" to tr("Spanisch"), "it" to tr("Italienisch"), "tr" to tr("Türkisch"))

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
                Text(tr("Einstellungen"), color = p.text, fontSize = 21.sp, maxLines = 1, softWrap = false, fontWeight = theme.spec.titleWeight,
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
        else LazyColumn(Modifier.weight(1f).fillMaxHeight().fadeOnChange(category), contentPadding = PaddingValues(start = 28.dp, end = 32.dp, top = 18.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "title_" + category.name) {
                Text(category.label, color = p.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            }
            when (category) {
                SettingsCategory.PLAYLISTS -> playlistsPage(vm, x, s, nav)
                SettingsCategory.EPG -> epgPage(vm, s)
                SettingsCategory.HOME -> homePage(vm, s)
                SettingsCategory.JELLYFIN -> jellyfinPage(vm, s, nav)
                SettingsCategory.LIVETV -> liveTvPage(vm, s)
                SettingsCategory.APPEARANCE -> appearancePage(vm, s)
                SettingsCategory.PLAYBACK -> playbackPage(vm, s)
                SettingsCategory.REMOTE -> remotePage(vm, s)
                SettingsCategory.PARENTAL -> item { ParentalSection(vm, s) }
                SettingsCategory.REMINDERS -> {
                    item { SHeader(tr("Erinnerungen & Hinweise")) }
                    notificationsSection(x)
                }
                SettingsCategory.BACKUP -> backupSection(x)
                SettingsCategory.GENERAL -> generalPage(vm, s)
                SettingsCategory.UPDATES -> updatesSection(x, s, vm)
                SettingsCategory.ABOUT -> {
                    aboutSection(vm)
                    item { SHeader(tr("Tondecoder")) }
                    item {
                        val ok = remember { runCatching { androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable() }.getOrDefault(false) }
                        SInfo(tr("Software-Tondecoder"), if (ok) "aktiv – FFmpeg ${runCatching { androidx.media3.decoder.ffmpeg.FfmpegLibrary.getVersion() }.getOrNull() ?: ""}".trim()
                            else "nicht enthalten (Gerätedecoder/Server-Umwandlung)")
                    }
                    item {
                        SInfo(tr("Lizenz"), tr("Diese App nutzt Bibliotheken des FFmpeg-Projekts (ffmpeg.org) unter der LGPL 2.1, nur Audio-Decoder. ") +
                            "Quellcode: github.com/FFmpeg/FFmpeg (release/6.0), Einbindung: AndroidX Media3 decoder_ffmpeg (Apache 2.0).")
                    }
                    item { SHeader(tr("Fehler")) }
                    item {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val n = remember(errorsOpen) { errorEntries(ctx, vm).size }
                        SRow(tr("Fehlermeldungen"), if (n == 0) tr("Keine Fehler aufgezeichnet") else trf("{0} Meldung(en) – zum Abfotografieren öffnen", n)) { errorsOpen = true }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ Zeilen im TiviMate-Stil

@Composable
internal fun SHeader(t: String) {
    Text(t.uppercase(), color = LocalUltraTheme.current.palette.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 4.dp))
}

/** Einstellung: Titel, darunter der aktuelle Wert in Grau. OK wechselt bzw. öffnet. */
@Composable
internal fun SRow(title: String, value: String? = null, onClick: () -> Unit) {
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
internal fun SSwitch(title: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
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

/** Reine Anzeige (nicht anwählbar): Bezeichnung links, Wert rechts – ohne Prüfschild. */
@Composable
internal fun SInfo(label: String, value: String) {
    val p = LocalUltraTheme.current.palette
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = p.textDim, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(0.36f))
        Text(value, color = p.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

/** Datum + Uhrzeit immer im deutschen Format, z. B. „18.11.2026, 23:51“. */
internal fun deDateTime(ms: Long): String = java.text.SimpleDateFormat("dd.MM.yyyy, HH:mm", java.util.Locale.GERMAN).format(Date(ms))
internal fun deDate(ms: Long): String = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMAN).format(Date(ms))

// ------------------------------------------------------------ Seiten

private fun LazyListScope.playlistsPage(vm: SettingsViewModel, x: SettingsExtrasViewModel, s: AppSettings, nav: NavController) {
    item {
        val provider by vm.provider.collectAsStateWithLifecycle()
        val sync by vm.syncState.collectAsStateWithLifecycle()
        val cfg = provider
        Column {
            SRow(tr("Playlist bearbeiten"), cfg?.let { "${it.type.name}${if (it.name.isNotBlank()) " · ${it.name}" else ""}" } ?: tr("Nicht eingerichtet")) { nav.navigate(Routes.SETUP) }
            SRow(tr("Playlist aktualisieren"), if (sync.running) sync.message else (sync.lastError?.let { trf("Fehler: {0}", it) }
                ?: if (s.lastSyncMs > 0) trf("Zuletzt: {0}", (deDateTime(s.lastSyncMs))) else tr("Noch nie"))) { vm.syncNow() }
            // Nur bei Xtream wählbar – bei M3U legt die Playlist das Format fest
            if (cfg?.type == de.ostlertekker.ultraiptv.core.model.ProviderType.XTREAM)
                SRow(tr("Live-Stream-Format"), if (s.liveFormat == "m3u8") tr("HLS (m3u8)") else tr("MPEG-TS (ts)")) {
                    vm.set(Keys.LIVE_FORMAT, if (s.liveFormat == "ts") "m3u8" else "ts")
                }
            if (cfg != null) SRow(tr("Playlist entfernen"), tr("Löscht die gespeicherten Zugangsdaten")) { vm.removeProvider() }
        }
    }
    item { SHeader(tr("Weitere Playlists")) }
    accountsSection(x, nav)
    // Gruppen ein-/ausblenden – getrennt für Live-TV, Filme und Serien (Filme/Serien mit Präfix gespeichert)
    item {
        val live by vm.channelGroups.collectAsStateWithLifecycle()
        val movies by vm.movieGroups.collectAsStateWithLifecycle()
        val series by vm.seriesGroups.collectAsStateWithLifecycle()
        val hidden = s.hiddenGroups.split('\n').filter { it.isNotBlank() }.toSet()
        Column {
            for ((title, groups, prefix) in listOf(Triple("Live-TV-Gruppen", live, ""), Triple("Film-Gruppen", movies, "M:"), Triple("Serien-Gruppen", series, "S:"))) {
                SHeader(title)
                if (groups.isEmpty()) Text(tr("Noch keine Gruppen – zuerst die Playlist aktualisieren."), color = LocalUltraTheme.current.palette.textDim, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp))
                groups.forEach { g ->
                    val key = prefix + g
                    SSwitch(g, key !in hidden) { visible -> vm.set(Keys.HIDDEN_GROUPS, (if (visible) hidden - key else hidden + key).joinToString("\n")) }
                }
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
            SRow(tr("EPG aktualisieren"), if (sync.running) sync.message else trf("Sendungen: {0}", (if (vm.epgCount >= 0) vm.epgCount else "…")) +
                (if (s.lastEpgSyncMs > 0) trf(" · zuletzt {0}", (deDateTime(s.lastEpgSyncMs))) else "")) { vm.syncEpg() }
            SRow(tr("Aktualisierungsintervall"), trf("Alle {0} Stunden", s.epgRefreshHours)) { vm.set(Keys.EPG_REFRESH, cycle(listOf(6, 12, 24, 48), s.epgRefreshHours)) }
            SRow(tr("Tage im TV-Programm"), "${s.epgDays} Tage") { vm.set(Keys.EPG_DAYS, cycle(listOf(1, 2, 3, 5, 7), s.epgDays)) }
            SRow(tr("EPG-Zeitverschiebung"), "${if (s.epgOffsetHours >= 0) "+" else ""}${s.epgOffsetHours} Stunden") {
                vm.set(Keys.EPG_OFFSET, cycle((-12..12).toList(), s.epgOffsetHours))
            }
            SHeader(tr("EPG-Quelle"))
            TvTextField(url, { url = it }, "XMLTV-Adresse (leer = EPG der Playlist)", modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                error = if (url.isNotBlank()) InputValidator.url(url) else null)
            SRow(tr("EPG-Quelle speichern"), s.epgUrlOverride.ifBlank { tr("EPG der Playlist") }) { if (url.isBlank() || InputValidator.url(url) == null) vm.set(Keys.EPG_URL, url.trim()) }
        }
    }
}

private fun LazyListScope.appearancePage(vm: SettingsViewModel, s: AppSettings) {
    item {
        val accentName = ThemeEngine.accentPresets.firstOrNull { it.second == s.accentArgb }?.first ?: tr("Eigene")
        Column {
            SRow(tr("Akzentfarbe"), accentName) {
                val vals = ThemeEngine.accentPresets.map { it.second }
                vm.set(Keys.ACCENT, cycle(vals, if (s.accentArgb in vals) s.accentArgb else 0L))
            }
            SRow(tr("Schriftart"), DesignOptions.fontNames.getOrElse(s.fontStyle) { tr("Standard") }) {
                vm.set(Keys.FONT_STYLE, (s.fontStyle + 1) % DesignOptions.fontNames.size)
            }
            SHeader(tr("PRO-Abzeichen"))
            SRow(tr("Stil"), DesignOptions.proStyleNames.getOrElse(s.proStyle) { tr("Verlauf") }) {
                vm.set(Keys.PRO_STYLE, (s.proStyle + 1) % DesignOptions.proStyleNames.size)
            }
            if (s.proStyle != 0) {
                SRow(tr("Größe"), DesignOptions.proSizeNames.getOrElse(s.proSize) { tr("Sehr klein") }) {
                    vm.set(Keys.PRO_SIZE, (s.proSize + 1) % DesignOptions.proSizeNames.size)
                }
                SRow(tr("Position"), DesignOptions.proPositionNames.getOrElse(s.proPosition) { tr("Hochgestellt") }) {
                    vm.set(Keys.PRO_POSITION, (s.proPosition + 1) % DesignOptions.proPositionNames.size)
                }
                SRow(tr("Animation"), DesignOptions.proAnimNames.getOrElse(s.proAnim) { tr("Aus") }) {
                    vm.set(Keys.PRO_ANIM, (s.proAnim + 1) % DesignOptions.proAnimNames.size)
                }
            }
            SHeader(tr("Uhr"))
            SSwitch(tr("Uhr in der Menüleiste"), s.menuClock) { vm.set(Keys.MENU_CLOCK, it) }
            SSwitch("24-Stunden-Format", s.clock24h) { vm.set(Keys.CLOCK_24H, it) }
            SHeader(tr("Zurücksetzen"))
            SRow(tr("Erscheinungsbild zurücksetzen"), tr("Akzentfarbe, Schriftart und Uhr auf Standard")) { vm.resetAppearance() }
        }
    }
}

private fun LazyListScope.jellyfinPage(vm: SettingsViewModel, s: AppSettings, nav: NavController) {
    item {
        val session by vm.jellyfin.session.collectAsStateWithLifecycle()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val hidden = s.jfHiddenRows.split('\n').filter { it.isNotBlank() }.map { if (it == "Meine Bibliotheken") "Bibliotheken" else it }.toSet()
        Column {
            SHeader(tr("Server & Konto"))
            val js = session
            if (js == null) {
                SRow(tr("Nicht angemeldet"), tr("Zur Jellyfin-Anmeldung")) { nav.navigate(de.ostlertekker.ultraiptv.ui.Routes.JELLYFIN) }
            } else {
                SInfo(tr("Server"), "${js.serverName} · ${js.server.substringAfter("://")}")
                SInfo(tr("Benutzer"), js.userName)
                SRow(tr("Bibliothek neu laden"), tr("Zwischenspeicher leeren – neue Titel sofort sehen")) { vm.jellyfin.invalidate() }
                SRow(tr("Abmelden"), tr("Danach mit einem anderen Server oder Benutzer anmelden")) { scope.launch { vm.jellyfin.logout() } }
            }
            SHeader(tr("Wiedergabe"))
            SRow(tr("Max. Streaming-Bitrate"), JfOptions.bitrateNames.getOrElse(s.jfMaxBitrate) { JfOptions.bitrateNames[0] }) {
                vm.set(Keys.JF_MAX_BITRATE, (s.jfMaxBitrate + 1) % JfOptions.bitrateNames.size)
            }
            SSwitch(tr("Immer transkodieren"), s.jfForceTranscode, tr("Nur bei Problemen einschalten – sonst spielt die App die Originaldatei direkt ab")) {
                vm.set(Keys.JF_FORCE_TRANSCODE, it)
            }
            SRow(tr("Max. Audiokanäle"), JfOptions.channelNames.getOrElse(s.jfAudioChannels) { JfOptions.channelNames[1] }) {
                vm.set(Keys.JF_AUDIO_CHANNELS, (s.jfAudioChannels + 1) % JfOptions.channelNames.size)
            }
            SRow(tr("Untertitel"), JfOptions.subtitleNames.getOrElse(s.jfSubtitles) { JfOptions.subtitleNames[0] }) {
                vm.set(Keys.JF_SUBTITLES, (s.jfSubtitles + 1) % JfOptions.subtitleNames.size)
            }
            SSwitch(tr("Fortschritt mit dem Server abgleichen"), s.jfReportProgress,
                tr("Gesehen-Status und „Weiterschauen“ auch in anderen Jellyfin-Apps")) { vm.set(Keys.JF_REPORT_PROGRESS, it) }
            SHeader(tr("Jellyfin-Übersicht"))
            JfOptions.rows.forEach { r ->
                SSwitch(r, r !in hidden) { visible -> vm.set(Keys.JF_HIDDEN_ROWS, (if (visible) hidden - r else hidden + r).joinToString("\n")) }
            }
        }
    }
    item { SeerrSettings(vm, s) }
}

/** Seerr (optional): Server, Anmeldung mit dem Jellyfin-Konto, Funktionen. */
@Composable
private fun SeerrSettings(vm: SettingsViewModel, s: AppSettings) {
    val p = LocalUltraTheme.current.palette
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val st by vm.seerr.state.collectAsStateWithLifecycle()
    val jf by vm.jellyfin.session.collectAsStateWithLifecycle()
    var server by remember { mutableStateOf(vm.seerr.savedServer()) }
    var user by remember(jf?.userName) { mutableStateOf(jf?.userName ?: "") }
    var pw by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column {
        SHeader(tr("Seerr (optional)"))
        SSwitch(tr("Seerr verwenden"), s.seerrEnabled, "Filme und Serien anfragen, die noch nicht in Jellyfin sind (auch Jellyseerr/Overseerr)") {
            vm.set(Keys.SEERR_ENABLED, it)
        }
        if (!s.seerrEnabled) return@Column
        if (!st.loggedIn) {
            Text(tr("Anmeldung mit deinem Jellyfin-Konto – das Passwort wird nicht gespeichert, nur die Seerr-Sitzung (verschlüsselt)."),
                color = p.textDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvTextField(server, { server = it }, tr("Seerr-Adresse (z. B. 192.168.1.10:5055 oder seerr.meinedomain.de)"), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvTextField(user, { user = it }, tr("Jellyfin-Benutzername"), modifier = Modifier.weight(1f))
                    TvTextField(pw, { pw = it }, tr("Jellyfin-Passwort"), password = true, modifier = Modifier.weight(1f))
                }
                TvButton(if (busy) tr("Anmelden …") else tr("Bei Seerr anmelden"), primary = true, enabled = !busy && server.isNotBlank() && user.isNotBlank(), onClick = {
                    busy = true; msg = null
                    scope.launch {
                        msg = runCatching { tr("Angemeldet als ") + vm.seerr.login(server, user, pw) }.getOrElse { "⚠ " + (it.message ?: tr("Anmeldung fehlgeschlagen")) }
                        pw = ""; busy = false
                    }
                })
            }
        } else {
            SInfo(tr("Server"), st.server.substringAfter("://"))
            SInfo(tr("Angemeldet als"), st.user ?: "")
            SRow(tr("Verbindung testen")) { scope.launch { msg = runCatching { vm.seerr.test() }.getOrElse { "⚠ " + (it.message ?: tr("Fehler")) } } }
            SRow(tr("Bei Seerr abmelden")) { vm.seerr.logout(); msg = null }
            SSwitch(tr("Reihe „Anfragen“"), s.seerrRequestsRow, tr("Alle Anfragen in der Jellyfin-Übersicht, mit Status und Download-Fortschritt")) { vm.set(Keys.SEERR_REQUESTS_ROW, it) }
            SSwitch(tr("Reihe „Entdecken“"), s.seerrDiscoverRow, tr("Angesagte Filme und Serien aus Seerr, direkt anfragbar")) { vm.set(Keys.SEERR_DISCOVER_ROW, it) }
            SSwitch(tr("Hinweis, wenn eine Anfrage verfügbar ist"), s.seerrNotify, tr("Einblendung oben links (Prüfung alle 5 Minuten)")) { vm.set(Keys.SEERR_NOTIFY, it) }
        }
        msg?.let { Text(it, color = if (it.startsWith("⚠")) Color(0xFFFF8A80) else p.primary, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
    }
}

private fun LazyListScope.liveTvPage(vm: SettingsViewModel, s: AppSettings) {
    item {
        Column {
            SHeader(tr("Senderliste"))
            SSwitch(tr("Sendernummern anzeigen"), s.showChannelNumbers) { vm.set(Keys.SHOW_NUMBERS, it) }
            SSwitch(tr("Senderlogos anzeigen"), s.showChannelLogos) { vm.set(Keys.SHOW_LOGOS, it) }
            SSwitch(tr("Laufende Sendung anzeigen"), s.showChannelEpg) { vm.set(Keys.SHOW_CH_EPG, it) }
            SSwitch(tr("Sendungsfortschritt anzeigen"), s.showChannelProgress) { vm.set(Keys.SHOW_CH_PROGRESS, it) }
            SRow(tr("Listendichte"), listOf(tr("Kompakt"), tr("Normal"), tr("Groß"))[s.channelDensity.coerceIn(0, 2)]) { vm.set(Keys.CHANNEL_DENSITY, (s.channelDensity + 1) % 3) }
            SHeader(tr("Vorschau"))
            SSwitch(tr("Vorschau beim Auswählen"), s.livePreview, tr("Auf schwachen Geräten automatisch aus")) { vm.set(Keys.LIVE_PREVIEW, it) }
        }
    }
}

private fun LazyListScope.playbackPage(vm: SettingsViewModel, s: AppSettings) {
    item {
        val profile by vm.activeProfile.collectAsStateWithLifecycle()
        val prof = profile
        Column {
            SRow(tr("Puffergröße"), s.bufferProfile.label) { vm.set(Keys.BUFFER, cycle(BufferProfile.entries, s.bufferProfile).name) }
            SSwitch(tr("Schnellstart beim Senderwechsel"), s.zapTurbo,
                tr("Live-TV startet mit dem pro Sender gelernten Mindestvorrat statt pauschal 2 s, wahrscheinliche nächste Sender werden vorbereitet. Messwerte: Tools › App-Diagnose")) { vm.set(Keys.ZAP_TURBO, it) }
            SSwitch(tr("Bildwiederholrate anpassen (AFR)"), s.matchFrameRate, tr("Schaltet das Display auf die Bildrate des Inhalts, wenn möglich")) { vm.set(Keys.MATCH_FPS, it) }
            SRow(tr("Seitenverhältnis"), de.ostlertekker.ultraiptv.feature.player.aspectNames[s.defaultAspect.coerceIn(0, 3)]) { vm.set(Keys.DEFAULT_ASPECT, (s.defaultAspect + 1) % 4) }
            SRow(tr("Sleep-Timer"), if (s.sleepTimerMin == 0) tr("Aus") else "${s.sleepTimerMin} Minuten") {
                vm.set(Keys.SLEEP_TIMER, cycle(listOf(0, 15, 30, 45, 60, 90, 120), s.sleepTimerMin.takeIf { it in listOf(0, 15, 30, 45, 60, 90, 120) } ?: 0))
            }
            SHeader(tr("Sprache"))
            if (prof != null) {
                SRow(tr("Audiosprache"), languages.firstOrNull { it.first == prof.audioLanguage }?.second ?: prof.audioLanguage) {
                    vm.updateProfile(prof.copy(audioLanguage = cycle(languages.map { it.first }, prof.audioLanguage)))
                }
                SRow(tr("Untertitelsprache"), languages.firstOrNull { it.first == prof.subtitleLanguage }?.second ?: prof.subtitleLanguage) {
                    vm.updateProfile(prof.copy(subtitleLanguage = cycle(languages.map { it.first }, prof.subtitleLanguage)))
                }
            }
            SHeader(tr("Info-Leiste"))
            SRow(tr("Ausblenden nach"), "${s.osdSeconds} Sekunden") { vm.set(Keys.OSD_SECONDS, cycle(listOf(3, 5, 8, 12, 20), s.osdSeconds.takeIf { it in listOf(3, 5, 8, 12, 20) } ?: 5)) }
            SSwitch(tr("Info beim Senderwechsel"), s.zapBanner) { vm.set(Keys.ZAP_BANNER, it) }
            SSwitch(tr("Uhr im Player"), s.playerClock) { vm.set(Keys.PLAYER_CLOCK, it) }
            SHeader(tr("Filme & Serien"))
            SSwitch(tr("Nächste Folge automatisch"), s.autoNextEpisode) { vm.set(Keys.AUTO_NEXT, it) }
            SRow(tr("Countdown bis zur nächsten Folge"), "${s.nextCountdownSec} Sekunden") { vm.set(Keys.COUNTDOWN, cycle(listOf(5, 10, 15, 20, 30), s.nextCountdownSec)) }
        }
    }
}

private fun LazyListScope.remotePage(vm: SettingsViewModel, s: AppSettings) {
    item {
        Column {
            SRow(tr("OK-Taste im Live-Player"), if (s.okAction == 1) tr("Senderliste") else tr("Info-Leiste")) { vm.set(Keys.OK_ACTION, (s.okAction + 1) % 2) }
            SRow("Pfeil hoch/runter im Live-Player", if (s.upDownAction == 1) tr("Senderliste öffnen") else tr("Sender wechseln")) { vm.set(Keys.UPDOWN_ACTION, (s.upDownAction + 1) % 2) }
            SSwitch(tr("Kanalwechsel umkehren"), s.invertChannelKeys, "Pfeil hoch = nächster Sender") { vm.set(Keys.INVERT_ZAP, it) }
            SSwitch(tr("Senderwahl über Zifferntasten"), s.numberZap) { vm.set(Keys.NUMBER_ZAP, it) }
        }
    }
}

private fun LazyListScope.generalPage(vm: SettingsViewModel, s: AppSettings) {
    item {
        Column {
            // Sprache: Auswahl wird gespeichert, die App startet danach neu
            run {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val cur = remember { de.ostlertekker.ultraiptv.core.i18n.I18n.setting(ctx) }
                val opts = listOf("system" to tr("Gerätesprache")) + de.ostlertekker.ultraiptv.core.i18n.I18n.LANGUAGES
                SRow("Sprache / Language", opts.firstOrNull { it.first == cur }?.second ?: tr("Gerätesprache")) {
                    val next = opts[(opts.indexOfFirst { it.first == cur } + 1) % opts.size].first
                    de.ostlertekker.ultraiptv.core.i18n.I18n.save(ctx, next)
                    var c: android.content.Context? = ctx
                    while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
                    (c as? android.app.Activity)?.let { de.ostlertekker.ultraiptv.core.i18n.I18n.restart(it) }
                }
            }
            SRow(tr("Beim App-Start"), listOf(tr("Startseite"), tr("Live-TV"), tr("Letzten Sender abspielen"), tr("TV-Programm"))[s.startAction.coerceIn(0, 3)]) {
                vm.set(Keys.START_ACTION, (s.startAction + 1) % 4)
            }
            SSwitch(tr("Startintro anzeigen"), s.introEnabled) { vm.set(Keys.INTRO, it) }
            SRow(tr("Einrichtungsassistent erneut zeigen"), if (s.wizardAgain) tr("✓ beim nächsten Start") else null) { vm.set(Keys.WIZARD_AGAIN, !s.wizardAgain) }
            SSwitch(tr("Menü-Töne"), s.uiSounds) { vm.set(Keys.UI_SOUNDS, it) }
            SHeader(tr("Bereiche in der Menüzeile"))
            de.ostlertekker.ultraiptv.ui.MENU_SECTIONS.forEach { (id, name) ->
                SSwitch(name, id !in s.hiddenTabs) { on ->
                    val set = if (on) s.hiddenTabs - id else s.hiddenTabs + id
                    vm.set(Keys.HIDDEN_TABS, set.joinToString(","))
                }
            }
        }
    }
}

private fun LazyListScope.homePage(vm: SettingsViewModel, s: AppSettings) {
    item {
        Column {
            SHeader(tr("Wetter"))
            SSwitch(tr("Wetter anzeigen"), s.weatherEnabled, subtitle = tr("Wetterleiste und Wettersymbol oben neben „ULTRA IPTV“")) { vm.set(Keys.WEATHER_ENABLED, it) }
            var place by remember { mutableStateOf(s.weatherPlace.substringBefore(",")) }
            var msg by remember { mutableStateOf<String?>(null) }
            TvTextField(place, { place = it }, "Ort (z. B. Berlin oder 10115) – leer = kein Wetter",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            SRow(tr("Ort speichern"), msg ?: s.weatherPlace.ifBlank { tr("Kein Ort – Wetter ausgeblendet") }) {
                msg = tr("Suche …")
                vm.viewModelScope.launch {
                    msg = vm.weather.setPlace(place).fold({ if (it.isBlank()) tr("Wetter ausgeblendet") else "✓ $it" }, { it.message ?: tr("Fehler") })
                }
            }
            SHeader(tr("Nachrichten-Laufschrift"))
            SSwitch(tr("Nachrichten-Laufschrift anzeigen"), s.newsEnabled) {
                vm.set(Keys.NEWS_ENABLED, it); if (it && s.newsSource == "off") vm.set(Keys.NEWS_SOURCE, "auto")
            }
            run {
                val ids = de.ostlertekker.ultraiptv.core.news.NewsSources.choices.map { it.first }
                SRow(tr("Quelle"), de.ostlertekker.ultraiptv.core.news.NewsSources.label(s.newsSource) +
                    if (s.newsSource == "auto") tr(" – passend zu Land und Sprache des Wetter-Orts") else "") {
                    vm.set(Keys.NEWS_SOURCE, ids[(ids.indexOf(s.newsSource).coerceAtLeast(0) + 1) % ids.size])
                }
            }
            SSwitch(tr("Lokalnachrichten einmischen"), s.newsLocal,
                subtitle = tr("Deutschland: tagesschau aus deinem Bundesland · weltweit: Google News zu deinem Ort"),
                enabled = s.newsEnabled) { vm.set(Keys.NEWS_LOCAL, it) }
            SHeader(tr("Sport-Ticker"))
            SSwitch(tr("Sport-Ticker anzeigen"), s.sportTickerEnabled, subtitle = tr("Nur Spiele, die gerade live laufen")) { vm.set(Keys.SPORT_TICKER_ENABLED, it) }
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
        SSwitch(tr("Erwachsenen-Kategorien anzeigen"), s.showAdult && ageAllows, if (!ageAllows) tr("Für dieses Profil durch Altersgrenze gesperrt") else tr("Freischalten erfordert die Profil-PIN (falls gesetzt)"), enabled = ageAllows) {
            if (!it) vm.set(Keys.SHOW_ADULT, false)
            else if (prof?.pinHash != null) askPin = true
            else vm.set(Keys.SHOW_ADULT, true)
        }
        Text(tr("Kategorien mit Kennzeichnungen wie „XXX“, „Adult“ oder „18+“ werden ausgeblendet. Die meisten Anbieter liefern keine FSK-Angaben pro Titel – ") +
            "die Altersgrenze wirkt daher auf diese Kategorien. Die PIN wird verschlüsselt gespeichert.",
            color = p.textDim, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
    }
    if (askPin && prof != null) {
        PinDialog(tr("PIN eingeben"), onSubmit = { pin ->
            val ok = vm.checkPinFor(prof, pin); if (ok) { vm.set(Keys.SHOW_ADULT, true); askPin = false }; ok
        }, onDismiss = { askPin = false })
    }
}

private fun LazyListScope.aboutSection(vm: SettingsViewModel) {
    item {
        val p = LocalUltraTheme.current.palette
        Column {
            Text(tr("OSTLERTEKKER & HORSTYHAZE PRESENTS"), color = p.textDim, fontSize = 12.sp, letterSpacing = 4.sp)
            Text(tr("ULTRA IPTV"), color = p.text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text(tr("DAS ULTIMATIVE STREAMING-ERLEBNIS"), color = p.primary, fontSize = 13.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))
            SInfo(tr("Version"), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            SInfo(tr("Gerät"), "${vm.device.manufacturer} ${vm.device.model}")
            // Support-Discord: QR-Code zum Scannen mit dem Handy
            SHeader(tr("Support-Discord"))
            // Fokussierbar, damit die Seite per Fernbedienung bis hierher (und weiter) scrollt
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                .tvFocusable(RoundedCornerShape(16.dp), {}, scaleOnFocus = false).padding(8.dp)) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(de.ostlertekker.ultraiptv.R.drawable.support_discord_qr),
                    contentDescription = "discord.gg/Jg2EZc9yE7",
                    modifier = Modifier.width(220.dp).clip(RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Fragen, Probleme oder Ideen? Scanne den QR-Code mit dem Handy und tritt unserem Discord bei."),
                        color = p.text, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("discord.gg/Jg2EZc9yE7", color = p.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(tr("ULTRA IPTV ist ein Abspieler. Er liefert keine Inhalte. Nutze nur Quellen, für die du eine Berechtigung hast. ") +
                tr("DRM- und Zugangsbeschränkungen werden nicht umgangen."), color = p.textDim, fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp).tvFocusable(RoundedCornerShape(10.dp), {}, scaleOnFocus = false).padding(6.dp))
        }
    }
}


// ------------------------------------------------------------ Fehlermeldungen (zum Abfotografieren)

private fun errorEntries(context: android.content.Context, vm: SettingsViewModel): List<Pair<String, String>> {
    val g = de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard
    val st = vm.player.lastSessionStats ?: vm.player.stats.value
    return listOfNotNull(
        g.lastCrash(context)?.let { "ABSTURZ" to it },
        g.lastFreeze(context)?.let { tr("HÄNGER") to it },
        vm.weather.lastError?.let { "WETTER" to it },
        vm.news.lastError?.let { "NACHRICHTEN" to it },
        st.lastError?.let { tr("LETZTER WIEDERGABEFEHLER") to (it + "\nFehler / Wiederherstellungen in der Sitzung: ${st.errors} / ${st.recoveries}") },
    )
}

/** Vollständiges Fehlerprotokoll, zeilenweise mit der Fernbedienung scrollbar. Zurück schließt die Ansicht. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ErrorLogView(vm: SettingsViewModel, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val p = LocalUltraTheme.current.palette
    var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val entries = remember(version) { errorEntries(context, vm) }
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { first.requestFocus() } }
    LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(start = 28.dp, end = 32.dp, top = 18.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Text(tr("Fehlermeldungen"), color = p.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(trf("ULTRA IPTV {0} ({1}) · {2} {3} · Android {4}", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, vm.device.manufacturer, vm.device.model, android.os.Build.VERSION.RELEASE),
                color = p.textDim, fontSize = 13.sp)
            Text(tr("Mit ▲▼ scrollen und abfotografieren · Zurück schließt"), color = p.primary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (entries.isEmpty()) item {
            Box(Modifier.focusRequester(first).tvFocusable(RoundedCornerShape(10.dp), onClose, scaleOnFocus = false)) {
                Text(tr("Keine Abstürze, Hänger oder Wiedergabefehler aufgezeichnet."), color = p.text, fontSize = 15.sp, modifier = Modifier.padding(14.dp))
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
            SRow(tr("Protokoll löschen"), tr("Löscht Absturz- und Hänger-Berichte")) { de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard.clear(context); version++ }
        }
    }
}
