package de.ostlertekker.ultraiptv.core.radio

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ostlertekker.ultraiptv.core.player.PlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RadioStation(
    val id: String,
    val name: String,
    val url: String,
    val logo: String? = null,
    val tags: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val country: String = "",
) {
    /** z. B. „Pop, Rock · MP3 · 128 kbit“ */
    val info: String get() = listOfNotNull(
        tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(", ") { t -> t.replaceFirstChar { it.titlecase() } }.ifBlank { null },
        codec.ifBlank { null }?.uppercase(),
        bitrate.takeIf { it > 0 }?.let { "$it kbit" },
    ).joinToString(" · ")
}

data class RadioCountry(val code: String, val name: String, val stations: Int) {
    /** Flagge aus dem Ländercode (Regional-Indicator-Zeichen). */
    val flag: String get() = if (code.length == 2 && code.all { it.isLetter() })
        code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("") else "🌐"
}

/**
 * Radiosender aus der offenen Datenbank Radio Browser (radio-browser.info): öffentliche Streams der Sender selbst,
 * ohne Konto oder Schlüssel. „Top 20“ = die meistgehörten Sender eines Landes laut Radio Browser.
 */
@Singleton
class RadioRepository @Inject constructor(@ApplicationContext private val ctx: Context, http: OkHttpClient) {
    private val client = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val servers = listOf("de1", "de2", "fi1", "at1", "nl1").map { "https://$it.api.radio-browser.info" }
    @Volatile private var countries: List<RadioCountry>? = null
    private val topCache = HashMap<String, Pair<Long, List<RadioStation>>>()
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = ctx.getSharedPreferences("radio", Context.MODE_PRIVATE)

    private fun get(path: String): JsonArray? {
        for (s in servers) {
            val r = runCatching {
                client.newCall(Request.Builder().url(s + path).header("User-Agent", "UltraIPTV/1.0").build()).execute().use { resp ->
                    if (!resp.isSuccessful) null else Json.parseToJsonElement(resp.body!!.string()) as? JsonArray
                }
            }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    suspend fun countries(): List<RadioCountry> = withContext(Dispatchers.IO) {
        countries?.let { return@withContext it }
        val arr = get("/json/countries?hidebroken=true") ?: error(tr("Radio-Datenbank nicht erreichbar"))
        val de = Locale.GERMAN
        arr.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val code = o.s("iso_3166_1")?.uppercase() ?: return@mapNotNull null
            val count = (o["stationcount"] as? JsonPrimitive)?.intOrNull ?: 0
            if (code.length != 2 || count <= 0) return@mapNotNull null
            val name = Locale("", code).getDisplayCountry(de).takeIf { it.isNotBlank() && it != code } ?: o.s("name") ?: code
            RadioCountry(code, name, count)
        }.distinctBy { it.code }.sortedBy { it.name }.also { countries = it }
    }

    suspend fun top(code: String): List<RadioStation> = withContext(Dispatchers.IO) {
        topCache[code]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.let { return@withContext it.second }
        val arr = get("/json/stations/search?countrycode=$code&order=clickcount&reverse=true&limit=60&hidebroken=true")
            ?: error(tr("Radio-Datenbank nicht erreichbar"))
        arr.mapNotNull { it as? JsonObject }.mapNotNull { o -> o.toStation() }
            .distinctBy { it.name.lowercase().replace(Regex("[^a-z0-9]"), "") }
            .take(20).also { topCache[code] = System.currentTimeMillis() to it }
    }

    /** Weltweite Suche nach Sendernamen (meistgehörte zuerst). */
    suspend fun search(q: String): List<RadioStation> = withContext(Dispatchers.IO) {
        val t = q.trim()
        if (t.length < 2) return@withContext emptyList()
        val arr = get("/json/stations/search?name=" + java.net.URLEncoder.encode(t, "UTF-8").replace("+", "%20") +
            "&order=clickcount&reverse=true&limit=80&hidebroken=true") ?: error(tr("Radio-Datenbank nicht erreichbar"))
        arr.mapNotNull { it as? JsonObject }.mapNotNull { o -> o.toStation() }
            .distinctBy { it.name.lowercase().replace(Regex("[^a-z0-9]"), "") + it.country }
            .take(50)
    }

    /** Meldet der Datenbank einen Aufruf (Bitte der Betreiber, damit die Beliebtheit stimmt). */
    suspend fun countClick(id: String) = withContext(Dispatchers.IO) { runCatching { get("/json/url/$id") } }

    private fun JsonObject.toStation(): RadioStation? {
        val url = (s("url_resolved") ?: s("url"))?.takeIf { it.startsWith("http") } ?: return null
        return RadioStation(
            id = s("stationuuid") ?: return null, name = s("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null, url = url,
            logo = s("favicon")?.takeIf { it.startsWith("http") }, tags = s("tags").orEmpty(), codec = s("codec").orEmpty(),
            bitrate = (this["bitrate"] as? JsonPrimitive)?.intOrNull ?: 0, country = s("countrycode").orEmpty(),
        )
    }

    // ------------------------------------------------------------------ Favoriten
    private val _favorites = MutableStateFlow(loadFavs())
    val favorites: StateFlow<List<RadioStation>> = _favorites.asStateFlow()
    private fun loadFavs(): List<RadioStation> =
        runCatching { json.decodeFromString<List<RadioStation>>(prefs.getString("favs", "[]")!!) }.getOrDefault(emptyList())
    fun toggleFavorite(s: RadioStation): Boolean {
        val cur = _favorites.value
        val on = cur.none { it.id == s.id }
        val next = if (on) cur + s else cur.filterNot { it.id == s.id }
        _favorites.value = next
        prefs.edit().putString("favs", json.encodeToString(next)).apply()
        return on
    }
    var lastCountry: String?
        get() = prefs.getString("country", null)
        set(v) { prefs.edit().putString("country", v).apply() }
}

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

/** Radio-Wiedergabe in der App (eigener Audio-Player). Startet ein Video, stoppt das Radio – und umgekehrt. */
@Singleton
class RadioPlayer @Inject constructor(@ApplicationContext private val ctx: Context, private val engine: PlayerEngine, private val repo: RadioRepository) {
    private var exo: ExoPlayer? = null
    private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var list: List<RadioStation> = emptyList()
    private val _current = MutableStateFlow<RadioStation?>(null)
    val current: StateFlow<RadioStation?> = _current.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()
    /** Aktueller Titel, falls der Sender ihn mitsendet (ICY-Metadaten). */
    private val _nowTitle = MutableStateFlow<String?>(null)
    val nowTitle: StateFlow<String?> = _nowTitle.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { engine.beforePlay = { stop() } }

    private fun player(): ExoPlayer = exo ?: ExoPlayer.Builder(ctx).build().also { p ->
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { _playing.value = isPlaying }
            override fun onPlaybackStateChanged(state: Int) { _buffering.value = state == Player.STATE_BUFFERING }
            override fun onMediaMetadataChanged(m: MediaMetadata) {
                val t = listOfNotNull(m.artist?.toString(), m.title?.toString()).filter { it.isNotBlank() }.joinToString(" – ")
                _nowTitle.value = t.takeIf { it.isNotBlank() && !it.equals(_current.value?.name, true) }
            }
            override fun onPlayerError(error: PlaybackException) { _error.value = tr("Sender nicht erreichbar"); _playing.value = false }
        })
        exo = p
    }

    fun play(s: RadioStation, playlist: List<RadioStation> = list) {
        // Läuft ein Video (Mini-Player/Vorschau), wird es beendet
        engine.setMini(null); engine.stop()
        list = playlist.ifEmpty { listOf(s) }
        _current.value = s; _nowTitle.value = null; _error.value = null
        val p = player()
        p.setMediaItem(MediaItem.Builder().setUri(s.url).setMediaMetadata(MediaMetadata.Builder().setStation(s.name).build()).build())
        p.prepare(); p.play()
        io.launch { repo.countClick(s.id) }
    }

    fun toggle() { val p = exo ?: return; if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() } }
    fun next(step: Int) {
        val c = _current.value ?: return
        if (list.size < 2) return
        val i = list.indexOfFirst { it.id == c.id }.coerceAtLeast(0)
        play(list[(i + step + list.size) % list.size], list)
    }
    fun stop() {
        exo?.let { it.stop(); it.clearMediaItems() }
        _current.value = null; _playing.value = false; _nowTitle.value = null
    }
}
