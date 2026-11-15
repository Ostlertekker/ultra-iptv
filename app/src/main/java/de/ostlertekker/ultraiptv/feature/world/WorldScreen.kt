package de.ostlertekker.ultraiptv.feature.world

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.database.ChannelEntity
import de.ostlertekker.ultraiptv.core.database.ContentDao
import de.ostlertekker.ultraiptv.core.database.CountryCount
import de.ostlertekker.ultraiptv.core.design.LocalUltraTheme
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.model.Countries
import de.ostlertekker.ultraiptv.core.ui.tvFocusable
import de.ostlertekker.ultraiptv.openPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.sqrt

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WorldViewModel @Inject constructor(private val dao: ContentDao, private val user: UserRepository) : ViewModel() {
    private val adult = user.filter.map { it.showAdult }.distinctUntilChanged()
    val counts: StateFlow<List<CountryCount>> = adult.flatMapLatest { dao.countryCounts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val selected = MutableStateFlow<String?>(null)
    val channels = MutableStateFlow<List<ChannelEntity>>(emptyList())

    fun select(code: String) {
        if (selected.value == code) return
        selected.value = code
        viewModelScope.launch { channels.value = dao.channelsOfCountry(code, user.filter.first().showAdult) }
    }
}

/** TV-Weltkarte: Sender nach Ländern entdecken. Länder werden automatisch aus Kategorien/Namen erkannt. */
@Composable
fun WorldScreen(vm: WorldViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val theme = LocalUltraTheme.current
    val p = theme.palette
    val counts by vm.counts.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val max = counts.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "p")

    Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text("TV WORLD MAP", color = p.text, fontSize = 26.sp, fontWeight = theme.spec.titleWeight, fontFamily = theme.spec.fontFamily)
            Text(if (counts.isEmpty()) "Noch keine Länder erkannt – Kategorien deines Anbieters enthalten keine Länderkennungen."
                else "${counts.size} Länder · ${counts.sumOf { it.count }} Sender zugeordnet", color = p.textDim, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(2f).background(p.surface.copy(alpha = 0.35f), RoundedCornerShape(16.dp))) {
                // Statische Landpunkte (werden nur bei Größenänderung neu gezeichnet)
                Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                    val cw = size.width / WorldMask.WIDTH
                    val chh = size.height / WorldMask.HEIGHT
                    val r = minOf(cw, chh) * 0.32f
                    val dot = p.textDim.copy(alpha = 0.35f)
                    for (row in 0 until WorldMask.HEIGHT) for (col in 0 until WorldMask.WIDTH) {
                        if (WorldMask.isLand(col, row)) drawCircle(dot, r, Offset(col * cw + cw / 2, row * chh + chh / 2))
                    }
                }
                Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                    fun pos(lat: Float, lon: Float) = Offset((lon + 180f) / 360f * size.width, (90f - lat) / 180f * size.height)
                    counts.forEach { c ->
                        val country = Countries.byCode(c.code) ?: return@forEach
                        val o = pos(country.lat, country.lon)
                        val rad = 4f + 14f * sqrt(c.count.toFloat() / max)
                        val sel = c.code == selected
                        drawCircle(Brush.radialGradient(listOf(p.glow.copy(alpha = 0.6f), Color.Transparent), o, rad * 2.5f), rad * 2.5f, o)
                        drawCircle(if (sel) Color.White else p.primary, rad * (if (sel) 0.75f else 0.5f), o)
                        if (sel) drawCircle(p.primary.copy(alpha = 1f - pulse), rad * (1f + 2f * pulse), o, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val sel = Countries.byCode(selected)
            if (sel != null) Text("${sel.flag}  ${sel.name} · Sprache: ${Countries.languageNames[sel.language] ?: sel.language}",
                color = p.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(Modifier.width(230.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text("LÄNDER", color = p.textDim, fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 6.dp)) }
            items(counts, key = { it.code }) { c ->
                val country = Countries.byCode(c.code)
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(12.dp)
                Row(Modifier.fillMaxWidth().tvFocusable(shape, { vm.select(c.code) }, scaleOnFocus = false, onFocus = { focused = it; if (it) vm.select(c.code) })
                    .background(if (focused) p.surfaceFocused else if (selected == c.code) p.surface else Color.Transparent, shape).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(country?.flag ?: "🌐", fontSize = 18.sp)
                    Text(country?.name ?: c.code, color = p.text, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
                    Text("${c.count}", color = p.textDim, fontSize = 13.sp)
                }
            }
        }
        LazyColumn(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            item { Text("SENDER", color = p.textDim, fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 6.dp)) }
            items(channels, key = { it.id }) { ch ->
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(12.dp)
                Row(Modifier.fillMaxWidth().tvFocusable(shape, { context.openPlayer(ContentKind.LIVE, ch.id, category = ch.category) }, scaleOnFocus = false,
                    onFocus = { focused = it }).background(if (focused) p.surfaceFocused else p.surface, shape).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp, 30.dp)) { if (ch.logo != null) AsyncImage(ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    Text(ch.name, color = p.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
