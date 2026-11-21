package de.ostlertekker.ultraiptv.feature.jellyfin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.seerr.SeerrItem
import de.ostlertekker.ultraiptv.core.seerr.SeerrRepository
import de.ostlertekker.ultraiptv.core.seerr.SeerrSeason
import de.ostlertekker.ultraiptv.core.seerr.SeerrStatus
import de.ostlertekker.ultraiptv.core.ui.FocusCard
import de.ostlertekker.ultraiptv.core.ui.TvButton
import de.ostlertekker.ultraiptv.core.ui.TvChip
import de.ostlertekker.ultraiptv.feature.common.CinemaTile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Seerr-Eintrag als Kachel für die Jellyfin-Übersicht (kind „seerr“, id = „movie:123“ / „tv:456“). */
fun SeerrItem.tile(subtitle: String? = null) = CinemaTile(
    id = key, title = title, image = poster, backdrop = backdrop, plot = overview, kind = "seerr",
    subtitle = subtitle ?: listOfNotNull(if (isTv) "Serie" else "Film", year, SeerrStatus.media(status)).joinToString(" · "),
)

/** Reihe mit Seerr-Treffern (z. B. in der Jellyfin-Suche). */
@Composable
fun SeerrRow(title: String, items: List<SeerrItem>, onClick: (SeerrItem) -> Unit) {
    val p = LocalUltraTheme.current.palette
    Column(Modifier.padding(top = 10.dp)) {
        Text(title, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(items, key = { it.key }) { i ->
                FocusCard(i.title, i.poster, 120.dp, 178.dp, onClick = { onClick(i) },
                    subtitle = listOfNotNull(i.year, SeerrStatus.media(i.status)).joinToString(" · ").ifBlank { null })
            }
        }
    }
}

/** Anfrage-Dialog: Film direkt anfragen, Serie mit Staffelwahl (alle oder einzelne). */
@Composable
fun SeerrRequestDialog(item: SeerrItem, repo: SeerrRepository, onDismiss: () -> Unit) {
    val p = LocalUltraTheme.current.palette
    val scope = rememberCoroutineScope()
    var seasons by remember(item.key) { mutableStateOf<List<SeerrSeason>?>(null) }
    var chosen by remember(item.key) { mutableStateOf<Set<Int>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(item.key) {
        if (item.isTv) seasons = runCatching { repo.seasons(item.tmdbId) }.getOrElse { message = it.message; emptyList() }
        delay(200); runCatching { focus.requestFocus() }
    }
    LaunchedEffect(done) { if (done) { delay(150); runCatching { focus.requestFocus() } } }
    val alreadyAll = item.status == SeerrStatus.AVAILABLE || item.status == SeerrStatus.PENDING || item.status == SeerrStatus.PROCESSING
    val requestable = seasons?.filter { it.status == SeerrStatus.UNKNOWN }.orEmpty()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(Modifier.width(860.dp).background(Color(0xFF0E1013), RoundedCornerShape(20.dp)).padding(28.dp)) {
            AsyncImage(item.poster, null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(180.dp).height(270.dp).clip(RoundedCornerShape(12.dp)).background(p.surface))
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text("SEERR", color = p.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text(item.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(if (item.isTv) "Serie" else "Film", item.year, SeerrStatus.media(item.status)).joinToString(" · "),
                    color = p.textDim, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                item.overview?.let { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, maxLines = 5, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp)) }
                if (item.isTv && !done) {
                    val list = seasons
                    if (list == null) Text("Staffeln werden geladen …", color = p.textDim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                    else if (list.isNotEmpty()) {
                        Text("Staffeln", color = p.textDim, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { TvChip("Alle fehlenden", chosen.isEmpty(), { chosen = emptySet() }) }
                            items(list, key = { it.number }) { s ->
                                val label = "S${s.number}" + (SeerrStatus.media(s.status)?.let { " · $it" } ?: "")
                                TvChip(label, s.number in chosen, {
                                    if (s.status == SeerrStatus.UNKNOWN) chosen = if (s.number in chosen) chosen - s.number else chosen + s.number
                                })
                            }
                        }
                    }
                }
                message?.let { Text(it, color = if (done) p.primary else Color(0xFFFF8A80), fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val canRequest = !done && !busy && (if (item.isTv) seasons != null && (requestable.isNotEmpty() || seasons!!.isEmpty()) else !alreadyAll)
                    if (canRequest) TvButton(if (busy) "Wird angefragt …" else "Anfragen", focusRequester = focus, primary = true, onClick = {
                        busy = true
                        scope.launch {
                            message = runCatching {
                                repo.request(item, if (item.isTv) chosen.sorted().ifEmpty { null } else null)
                                done = true
                                "Angefragt – du bekommst einen Hinweis, sobald es verfügbar ist."
                            }.getOrElse { it.message ?: "Anfrage fehlgeschlagen" }
                            busy = false
                        }
                    })
                    else if (!done) Text(SeerrStatus.media(item.status) ?: "Nichts mehr anzufragen", color = p.textDim, fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 10.dp))
                    TvButton(if (done) "Fertig" else "Schließen", focusRequester = if (canRequest) null else focus, onClick = onDismiss)
                }
            }
        }
    }
}
