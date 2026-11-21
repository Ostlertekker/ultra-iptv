package de.ostlertekker.ultraiptv.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.data.SyncRepository
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.model.ProviderConfig
import de.ostlertekker.ultraiptv.core.model.ProviderType
import de.ostlertekker.ultraiptv.core.security.CredentialStore
import de.ostlertekker.ultraiptv.core.security.InputValidator
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.TvChip
import de.ostlertekker.ultraiptv.core.ui.TvTextField
import de.ostlertekker.ultraiptv.core.ui.UltraLoader
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProviderSetupViewModel @Inject constructor(
    private val creds: CredentialStore,
    private val sync: SyncRepository,
) : ViewModel() {
    val existing: ProviderConfig? = creds.load()
    val syncState = sync.state
    val hasAccounts: Boolean get() = creds.accounts.value.isNotEmpty()

    /** [newAccount] = als zusätzliches Konto speichern (Multi-Account), sonst aktives Konto aktualisieren. */
    fun saveAndSync(cfg: ProviderConfig, newAccount: Boolean, onSuccess: () -> Unit) = viewModelScope.launch {
        if (newAccount) creds.saveAccount(cfg, null) else creds.save(cfg)
        if (sync.syncAll(includeEpg = true)) onSuccess()
    }
}

@Composable
fun ProviderSetupScreen(onDone: () -> Unit, firstRun: Boolean, newAccount: Boolean = false, vm: ProviderSetupViewModel = hiltViewModel()) {
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val ex = if (newAccount) null else vm.existing
    var type by remember { mutableStateOf(ex?.type ?: ProviderType.XTREAM) }
    var name by remember { mutableStateOf(ex?.name ?: "") }
    var m3u by remember { mutableStateOf(ex?.m3uUrl ?: "") }
    var epg by remember { mutableStateOf(ex?.epgUrl ?: "") }
    var server by remember { mutableStateOf(ex?.server ?: "") }
    var user by remember { mutableStateOf(ex?.username ?: "") }
    var pass by remember { mutableStateOf(ex?.password ?: "") }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val sync by vm.syncState.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(720.dp).background(p.bgBottom.copy(alpha = 0.88f), RoundedCornerShape(theme.spec.corner.coerceAtMost(24.dp)))
                .padding(24.dp).verticalScroll(rememberScrollState()),
        ) {
            if (firstRun) Text("OSTLERTEKKER PRESENTS", color = p.textDim, fontSize = 12.sp, letterSpacing = 4.sp)
            Text(if (newAccount) "Weiteres Konto hinzufügen" else "Quelle einrichten", color = p.text, fontSize = 28.sp, fontWeight = theme.spec.titleWeight, fontFamily = theme.spec.fontFamily)
            Text("Nur legal lizenzierte, eigene oder vom Anbieter autorisierte Quellen verwenden. Zugangsdaten werden verschlüsselt gespeichert.",
                color = p.textDim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Box(Modifier) { TvButton("Xtream Codes API", onClick = { type = ProviderType.XTREAM }, primary = type == ProviderType.XTREAM, focusRequester = first) }
                TvChip("M3U-Playlist", type == ProviderType.M3U, { type = ProviderType.M3U })
            }
            TvTextField(name, { name = it }, "Name (optional)")
            Spacer(Modifier.height(8.dp))
            if (type == ProviderType.XTREAM) {
                TvTextField(server, { server = it }, "Server (z. B. http://anbieter.tld:8080)", error = errors["server"])
                Spacer(Modifier.height(8.dp))
                TvTextField(user, { user = it }, "Benutzername", error = errors["user"])
                Spacer(Modifier.height(8.dp))
                TvTextField(pass, { pass = it }, "Passwort", password = true, error = errors["pass"])
            } else {
                TvTextField(m3u, { m3u = it }, "M3U-Adresse (http/https)", error = errors["m3u"])
            }
            Spacer(Modifier.height(8.dp))
            TvTextField(epg, { epg = it }, "XMLTV-EPG-Adresse (optional)", error = errors["epg"])
            Spacer(Modifier.height(18.dp))
            if (sync.running) UltraLoader(label = sync.message)
            else {
                sync.lastError?.let { Text("⚠ ${sync.message}: $it", color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Speichern & synchronisieren", primary = true, onClick = {
                        val e = HashMap<String, String>()
                        if (type == ProviderType.XTREAM) {
                            InputValidator.url(server)?.let { e["server"] = it }
                            InputValidator.credential(user, "Benutzername")?.let { e["user"] = it }
                            InputValidator.credential(pass, "Passwort")?.let { e["pass"] = it }
                        } else {
                            InputValidator.url(m3u)?.let { e["m3u"] = it }
                        }
                        if (epg.isNotBlank()) InputValidator.url(epg)?.let { e["epg"] = it }
                        errors = e
                        if (e.isEmpty()) {
                            val m3uFixed = if (m3u.isNotBlank() && "://" !in m3u) "http://${m3u.trim()}" else m3u.trim()
                            val epgFixed = if (epg.isNotBlank() && "://" !in epg) "http://${epg.trim()}" else epg.trim()
                            vm.saveAndSync(ProviderConfig(type, name.trim(), m3uFixed, epgFixed, server.trim(), user.trim(), pass), newAccount, onDone)
                        }
                    })
                    if (!firstRun) TvButton("Abbrechen", onClick = onDone)
                }
            }
            Text("Hinweis: Die App enthält keine Inhalte und umgeht keine DRM- oder Zugangsbeschränkungen.",
                color = p.textDim.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 16.dp))
        }
    }
}
