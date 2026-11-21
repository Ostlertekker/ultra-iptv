package de.ostlertekker.ultraiptv.feature.common

import de.ostlertekker.ultraiptv.core.ui.FocusBleedV
import de.ostlertekker.ultraiptv.core.ui.FocusBleedH
import de.ostlertekker.ultraiptv.core.ui.focusBleed
import de.ostlertekker.ultraiptv.core.ui.outlineButton
import androidx.compose.ui.draw.clip
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.ui.FocusCard
import de.ostlertekker.ultraiptv.core.ui.SectionHeader

/** Eine Kachel der Kino-Übersicht (Film, Serie oder angefangene Folge). */
data class CinemaTile(
    val id: String,
    val title: String,
    val image: String?,
    val subtitle: String? = null,
    val progress: Float? = null,
    val positionMs: Long = 0L,
    val backdrop: String? = null,
    val plot: String? = null,
    /** Weiterschauen-Kachel: OK spielt direkt weiter statt Details zu öffnen. */
    val resume: Boolean = false,
    val kind: String = "",
    /** Optionales Statusabzeichen (z. B. Seerr) mit Farbe und Farbe des Fortschrittsbalkens. */
    val badge: Pair<String, Color>? = null,
    val progressColor: Color? = null,
)

data class CinemaRow(val title: String, val tiles: List<CinemaTile>, val landscape: Boolean = false)

/**
 * Cineastische Übersicht für Filme und Serien: großes Titelbild oben, darunter große Posterreihen.
 * Bilder stehen im Vordergrund, Texte bleiben knapp.
 */
@Composable
fun CinemaOverview(
    hero: CinemaTile?,
    rows: List<CinemaRow>,
    header: @Composable () -> Unit,
    onOpen: (CinemaTile) -> Unit,
    onPlay: (CinemaTile) -> Unit,
) {
    val p = LocalUltraTheme.current.palette
    val heroHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
        item(key = "header") { Box(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) { header() } }
        if (hero != null) item(key = "hero") {
            Box(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(heroHeight)
                .background(Color.Black, RoundedCornerShape(18.dp))) {
                Crossfade(hero, animationSpec = tween(500), label = "cinemaHero") { h ->
                    de.ostlertekker.ultraiptv.core.ui.PosterBackdrop(h.backdrop, h.image,
                        Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(Color.Black, RoundedCornerShape(18.dp)), posterPadding = 20.dp,
                        posterOnly = true) // wie Startseite: schwarzer Hintergrund, nur das Cover rechts
                }
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.6f to Color.Black.copy(alpha = 0.2f), 1f to Color.Transparent)))
                Column(Modifier.align(Alignment.BottomStart).padding(28.dp).width(520.dp)) {
                    Text(hero.title, color = Color.White, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    hero.subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
                    hero.plot?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("▶  Abspielen", primary = true) { onPlay(hero) }
                        PillButton("Weitere Informationen") { onOpen(hero) }
                    }
                }
            }
        }
        rows.filter { it.tiles.isNotEmpty() }.forEach { row ->
            item(key = "row_" + row.title) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    SectionHeader(row.title)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(start = FocusBleedH, end = FocusBleedH, top = 8.dp + FocusBleedV, bottom = 8.dp + FocusBleedV), modifier = Modifier.focusBleed()) {
                        items(row.tiles, key = { it.kind + it.id }, contentType = { if (row.landscape) "wide" else "poster" }) { t ->
                            if (row.landscape) FocusCard(t.title, t.backdrop ?: t.image, 280.dp, 158.dp, onClick = { if (t.resume) onPlay(t) else onOpen(t) },
                                subtitle = t.subtitle, progress = t.progress, badge = t.badge, progressColor = t.progressColor)
                            else FocusCard(t.title, t.image, 150.dp, 222.dp, onClick = { if (t.resume) onPlay(t) else onOpen(t) },
                                subtitle = t.subtitle, progress = t.progress, badge = t.badge, progressColor = t.progressColor)
                        }
                    }
                }
            }
        }
        if (rows.all { it.tiles.isEmpty() } && hero == null) item(key = "empty") {
            Text("Noch keine Inhalte – Quelle in den Einstellungen synchronisieren.", color = p.textDim, modifier = Modifier.padding(24.dp))
        }
    }
}

/** Einheitlicher Pillen-Knopf (Designsystem): weiß für die Hauptaktion, Glas für Nebenaktionen. */
@Composable
fun PillButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "pill")
    Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged { focused = it.isFocused }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        .outlineButton(focused, primary, true, RoundedCornerShape(12.dp))
        .padding(horizontal = 24.dp, vertical = 11.dp)) {
        Text(label, color = de.ostlertekker.ultraiptv.core.ui.outlineButtonText(focused, true), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
