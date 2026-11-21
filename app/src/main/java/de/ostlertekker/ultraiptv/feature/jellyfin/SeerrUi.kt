package de.ostlertekker.ultraiptv.feature.jellyfin

import androidx.compose.foundation.layout.PaddingValues
import de.ostlertekker.ultraiptv.core.ui.FocusBleedV
import de.ostlertekker.ultraiptv.core.ui.FocusBleedH
import de.ostlertekker.ultraiptv.core.ui.focusBleed
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import de.ostlertekker.ultraiptv.core.seerr.SeerrDownload
import de.ostlertekker.ultraiptv.core.seerr.SeerrItem
import de.ostlertekker.ultraiptv.core.seerr.SeerrRequest
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp).focusBleed(), contentPadding = PaddingValues(horizontal = FocusBleedH, vertical = FocusBleedV)) {
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
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusBleed(), contentPadding = PaddingValues(horizontal = FocusBleedH, vertical = FocusBleedV)) {
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

// =====================================================================================================
// Anfrage-Ansicht im Stil der Seerr-Oberfläche: Status-Abzeichen, Download-Balken, Staffeln
// =====================================================================================================

/** Farben der Seerr-Abzeichen. */
object SeerrColors {
    val green = Color(0xFF22C55E); val yellow = Color(0xFFEAB308); val indigo = Color(0xFF6366F1)
    val red = Color(0xFFDC2626); val gray = Color(0xFF4B5563); val track = Color(0xFF374151)
}

/** Abzeichen wie in Seerr: Ablehnung/Fehler → rot, wartet → gelb, lädt/bearbeitet → indigo, verfügbar → grün. */
fun SeerrRequest.badge(): Pair<String, Color> = when {
    status == SeerrStatus.REQ_DECLINED -> "Abgelehnt" to SeerrColors.red
    status == SeerrStatus.REQ_FAILED -> "Fehlgeschlagen" to SeerrColors.red
    item.status == SeerrStatus.AVAILABLE -> "Verfügbar" to SeerrColors.green
    item.status == SeerrStatus.PARTIAL && !downloading -> "Teilweise verfügbar" to SeerrColors.green
    downloading -> "Lädt · ${Math.round((progress ?: 0f) * 100)} %" to SeerrColors.indigo
    status == SeerrStatus.REQ_PENDING -> "Ausstehend" to SeerrColors.yellow
    item.status == SeerrStatus.PROCESSING || item.status == SeerrStatus.PARTIAL -> "Wird bearbeitet" to SeerrColors.indigo
    else -> "Angefragt" to SeerrColors.indigo
}

fun seasonColor(status: Int): Color = when (status) {
    SeerrStatus.AVAILABLE, SeerrStatus.PARTIAL -> SeerrColors.green
    SeerrStatus.PENDING -> SeerrColors.yellow
    SeerrStatus.PROCESSING -> SeerrColors.indigo
    else -> SeerrColors.gray
}

/** Kachel für „Meine Anfragen“: Abzeichen oben links, Download-Balken unten (kind „seerrReq“). */
fun SeerrRequest.tile() = CinemaTile(
    id = "req:$id", title = item.title, image = item.poster, backdrop = item.backdrop, plot = item.overview, kind = "seerrReq",
    subtitle = listOfNotNull(if (item.isTv) "Serie" else "Film", item.year).joinToString(" · "),
    progress = progress?.coerceAtLeast(0.02f), progressColor = SeerrColors.indigo, badge = badge(),
)

@Composable
private fun SeerrBadge(label: String, color: Color) {
    Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.background(color.copy(alpha = 0.8f), RoundedCornerShape(50))
            .border(1.dp, color, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 3.dp))
}

/** Download-Block wie in Seerr: Titel, Balken mit Prozent in der Mitte, Status + Restzeit. */
@Composable
private fun SeerrDownloadBlock(d: SeerrDownload, fallbackTitle: String) {
    val label = if (d.season != null && d.episode != null) "%s – S%02dE%02d".format(fallbackTitle, d.season, d.episode)
        else d.title.ifBlank { fallbackTitle }
    val anim by androidx.compose.animation.core.animateFloatAsState(d.progress, androidx.compose.animation.core.tween(600), label = "dl")
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(22.dp).clip(RoundedCornerShape(50)).background(SeerrColors.track)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(anim).background(SeerrColors.indigo))
            Text("${d.percent} %", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
        }
        Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(SeerrStatus.download(d.status), color = Color.White, fontSize = 12.sp,
                modifier = Modifier.background(SeerrColors.indigo.copy(alpha = 0.8f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 1.dp))
            Spacer(Modifier.weight(1f))
            SeerrStatus.remaining(d.eta)?.let { Text("Fertig in $it", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp) }
        }
    }
}

/**
 * Detailansicht einer eigenen Anfrage (wie die Anfrage-Karte in Seerr): Hintergrundbild, Status, wer/wann,
 * laufende Downloads mit Fortschritt (aktualisiert sich alle 5 s während eines Downloads) und Staffel-Status.
 */
@Composable
fun SeerrRequestDetail(
    initial: SeerrRequest,
    repo: SeerrRepository,
    onDismiss: () -> Unit,
    onOpenJellyfin: (String) -> Unit,
    onRequestMore: (SeerrItem) -> Unit,
) {
    val p = LocalUltraTheme.current.palette
    var req by remember(initial.id) { mutableStateOf(initial) }
    var seasons by remember(initial.id) { mutableStateOf<List<SeerrSeason>>(emptyList()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(initial.id) {
        delay(200); runCatching { focus.requestFocus() }
        while (true) {
            runCatching { req = repo.refresh(req) }
            if (req.item.isTv) runCatching { seasons = repo.seasons(req.item.tmdbId) }
            delay(if (req.downloading) 5_000 else 30_000)
        }
    }
    val r = req
    val (label, color) = r.badge()
    val canOpen = r.item.status == SeerrStatus.AVAILABLE || r.item.status == SeerrStatus.PARTIAL
    val more = r.item.isTv && seasons.any { it.status == SeerrStatus.UNKNOWN }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.width(940.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF111827))) {
            r.item.backdrop?.let {
                AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(Color(0x78111827), Color(0xFF111827)), end = androidx.compose.ui.geometry.Offset(900f, 900f))))
            }
            Row(Modifier.padding(28.dp)) {
                AsyncImage(r.item.poster, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.width(190.dp).height(285.dp).clip(RoundedCornerShape(12.dp)).background(p.surface))
                Spacer(Modifier.width(26.dp))
                Column(Modifier.weight(1f)) {
                    Text(listOfNotNull("SEERR", if (r.item.isTv) "SERIE" else "FILM", r.item.year).joinToString(" · "),
                        color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(r.item.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SeerrBadge(label, color)
                        if (r.is4k) SeerrBadge("4K", SeerrColors.yellow)
                    }
                    val who = listOfNotNull(r.requestedBy?.let { "Angefragt von $it" },
                        r.createdAt?.let { "am " + java.text.SimpleDateFormat("dd.MM.yyyy, HH:mm", java.util.Locale.GERMANY).format(java.util.Date(it)) + " Uhr" })
                    if (who.isNotEmpty()) Text(who.joinToString(" "), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    if (r.item.isTv && r.seasons.isNotEmpty())
                        Text("Angefragt: " + r.seasons.joinToString(", ") { "Staffel $it" }, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)

                    if (r.downloads.isNotEmpty()) {
                        r.downloads.take(3).forEach { SeerrDownloadBlock(it, r.item.title) }
                        if (r.downloads.size > 3) Text("+ ${r.downloads.size - 3} weitere Downloads", color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    } else if (r.status == SeerrStatus.REQ_APPROVED && r.item.status != SeerrStatus.AVAILABLE) {
                        Text("Noch kein Download aktiv – Radarr/Sonarr sucht nach dem Titel.", color = Color.White.copy(alpha = 0.6f),
                            fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                    } else r.item.overview?.let {
                        Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 10.dp))
                    }

                    if (seasons.isNotEmpty()) {
                        Text("Staffeln", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.focusBleed(), contentPadding = PaddingValues(horizontal = FocusBleedH, vertical = FocusBleedV)) {
                            items(seasons, key = { it.number }) { s ->
                                val c = seasonColor(s.status)
                                Text("S${s.number} · " + (SeerrStatus.media(s.status) ?: "Nicht angefragt"), color = Color.White, fontSize = 12.sp,
                                    modifier = Modifier.background(c.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (canOpen) TvButton("In Jellyfin ansehen", primary = true, focusRequester = focus, onClick = { onOpenJellyfin(r.item.title) })
                        if (more) TvButton("Weitere Staffeln anfragen", focusRequester = if (canOpen) null else focus, onClick = { onRequestMore(r.item) })
                        TvButton("Schließen", focusRequester = if (canOpen || more) null else focus, onClick = onDismiss)
                    }
                }
            }
        }
    }
}
