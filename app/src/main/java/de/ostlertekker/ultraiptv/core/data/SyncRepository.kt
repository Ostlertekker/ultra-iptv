package de.ostlertekker.ultraiptv.core.data

import android.util.Xml
import androidx.room.withTransaction
import de.ostlertekker.ultraiptv.core.database.ChannelEntity
import de.ostlertekker.ultraiptv.core.database.EpgProgramEntity
import de.ostlertekker.ultraiptv.core.database.EpisodeEntity
import de.ostlertekker.ultraiptv.core.database.MovieEntity
import de.ostlertekker.ultraiptv.core.database.SeriesEntity
import de.ostlertekker.ultraiptv.core.database.UltraDatabase
import de.ostlertekker.ultraiptv.core.model.Countries
import de.ostlertekker.ultraiptv.core.model.ProviderConfig
import de.ostlertekker.ultraiptv.core.model.ProviderType
import de.ostlertekker.ultraiptv.core.model.SyncState
import de.ostlertekker.ultraiptv.core.network.M3uKind
import de.ostlertekker.ultraiptv.core.network.M3uParser
import de.ostlertekker.ultraiptv.core.network.SafeLog
import de.ostlertekker.ultraiptv.core.network.XmltvParser
import de.ostlertekker.ultraiptv.core.network.XtreamUrls
import de.ostlertekker.ultraiptv.core.security.CredentialStore
import de.ostlertekker.ultraiptv.core.security.StreamResolver
import de.ostlertekker.ultraiptv.di.XtreamApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeToSequence
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

internal fun JsonObject.firstStr(key: String): String? = when (val e = this[key]) {
    is JsonArray -> (e.firstOrNull() as? JsonPrimitive)?.contentOrNull
    is JsonPrimitive -> e.contentOrNull
    else -> null
}?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private val ADULT = Regex("""(?i)(xxx|adult|18\+|porn|erotic|for adults|\+18)""")
fun isAdultCategory(name: String): Boolean = ADULT.containsMatchIn(name)

private fun shortHash(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(10).joinToString("") { "%02x".format(it) }

/** Kontostatus laut Xtream-API (user_info). */
data class AccountInfo(
    val status: String?,
    val expiresAt: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val isTrial: Boolean,
    val createdAt: Long?,
    val serverTimezone: String?,
    val checkedAt: Long,
)

@Singleton
class SyncRepository @Inject constructor(
    private val db: UltraDatabase,
    private val api: XtreamApi,
    private val http: OkHttpClient,
    private val creds: CredentialStore,
    private val settings: SettingsRepository,
) {
    private val _accountInfo = MutableStateFlow<AccountInfo?>(null)
    val accountInfo: StateFlow<AccountInfo?> = _accountInfo.asStateFlow()

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun status(msg: String) { _state.value = SyncState(running = true, message = msg) }

    /** Vollständige Synchronisation: Kataloge + optional EPG. */
    suspend fun syncAll(includeEpg: Boolean = true): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cfg = creds.load()
            if (cfg == null) {
                _state.value = SyncState(false, "Keine Quelle konfiguriert", "Bitte zuerst eine Quelle einrichten")
                return@withContext false
            }
            try {
                var m3uEpg: String? = null
                when (cfg.type) {
                    ProviderType.M3U -> m3uEpg = syncM3u(cfg)
                    ProviderType.XTREAM -> syncXtream(cfg)
                }
                runCatching { db.openHelper.writableDatabase.let { w -> de.ostlertekker.ultraiptv.core.database.Migrations.SANITIZE.forEach { w.execSQL(it) } } }
                settings.set(Keys.LAST_SYNC, System.currentTimeMillis())
                if (includeEpg) syncEpgInternal(cfg, m3uEpg)
                _state.value = SyncState(false, "Synchronisation abgeschlossen")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SyncState(false, "Synchronisation fehlgeschlagen", SafeLog.redact(e.message ?: e.javaClass.simpleName))
                false
            }
        }
    }

    suspend fun syncEpg(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cfg = creds.load() ?: return@withContext false
            try {
                syncEpgInternal(cfg, null)
                _state.value = SyncState(false, "EPG aktualisiert")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SyncState(false, "EPG-Aktualisierung fehlgeschlagen", SafeLog.redact(e.message ?: e.javaClass.simpleName))
                false
            }
        }
    }

    // ------------------------------------------------------------------ M3U

    private suspend fun syncM3u(cfg: ProviderConfig): String? {
        status("Playlist wird geladen …")
        val request = Request.Builder().url(cfg.m3uUrl.trim()).build()
        val result = http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Playlist: HTTP ${resp.code}")
            val body = resp.body ?: error("Playlist: leere Antwort")
            body.charStream().buffered().useLines { M3uParser.parse(it) }
        }
        if (result.entries.isEmpty()) error("Playlist enthält keine Einträge")
        status("${result.entries.size} Einträge werden verarbeitet …")

        val channels = ArrayList<ChannelEntity>()
        val movies = ArrayList<MovieEntity>()
        val series = LinkedHashMap<String, SeriesEntity>()
        val episodes = ArrayList<EpisodeEntity>()
        val now = System.currentTimeMillis()
        result.entries.forEachIndexed { index, e ->
            val adult = isAdultCategory(e.group)
            when (M3uParser.classify(e)) {
                M3uKind.LIVE -> channels += ChannelEntity(
                    id = "m3u:" + shortHash(e.url), name = e.name, category = e.group, logo = e.logo,
                    streamUrl = e.url, epgId = (e.tvgId ?: e.tvgName ?: e.name).lowercase(),
                    number = index + 1, adult = adult, country = Countries.detect(e.group, e.name),
                    archiveDays = e.catchup?.days ?: 0, catchupMode = e.catchup?.mode, catchupSource = e.catchup?.source,
                )
                M3uKind.MOVIE -> movies += MovieEntity(
                    id = "m3u:" + shortHash(e.url), name = e.name, category = e.group, poster = e.logo,
                    backdrop = null, plot = null, genre = null, year = null, rating = null, durationSec = null,
                    streamUrl = e.url, addedAt = now - index, adult = adult,
                    language = Countries.languageOf(Countries.detect(e.group, e.name)),
                )
                M3uKind.EPISODE -> {
                    val info = M3uParser.episodeInfo(e.name)
                    val show = info?.showName ?: e.group
                    val seriesId = "m3u:series:" + shortHash(show.lowercase())
                    if (seriesId !in series) series[seriesId] = SeriesEntity(
                        id = seriesId, name = show, category = e.group, poster = e.logo, backdrop = null,
                        plot = null, genre = null, year = null, rating = null, addedAt = now - index, adult = adult,
                        language = Countries.languageOf(Countries.detect(e.group, show)),
                    )
                    episodes += EpisodeEntity(
                        id = "m3u:" + shortHash(e.url), seriesId = seriesId, season = info?.season ?: 1,
                        episode = info?.episode ?: (episodes.size + 1), title = e.name, plot = null, image = e.logo,
                        durationSec = null, streamUrl = e.url,
                    )
                }
            }
        }
        status("Datenbank wird aktualisiert …")
        db.withTransaction {
            val dao = db.content()
            dao.clearChannels(); dao.clearMovies(); dao.clearSeries(); dao.clearEpisodes()
            channels.chunked(500).forEach { dao.upsertChannels(it) }
            movies.chunked(500).forEach { dao.upsertMovies(it) }
            series.values.chunked(500).forEach { dao.upsertSeries(it) }
            episodes.chunked(500).forEach { dao.upsertEpisodes(it) }
        }
        return result.epgUrl
    }

    // --------------------------------------------------------------- Xtream

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> readArray(url: String, map: (JsonObject) -> T?): List<T> {
        val body = api.stream(url)
        return body.use { b ->
            b.byteStream().use { input ->
                json.decodeToSequence<JsonElement>(input, DecodeSequenceMode.AUTO_DETECT)
                    .mapNotNull { (it as? JsonObject)?.let(map) }
                    .toList()
            }
        }
    }

    private suspend fun readObject(url: String): JsonObject? {
        val text = api.stream(url).use { it.string() }
        return runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    }

    private suspend fun categories(base: String, cfg: ProviderConfig, action: String): Map<String, String> =
        readArray(XtreamUrls.api(base, cfg.username, cfg.password, action)) { o ->
            val id = o.str("category_id") ?: return@readArray null
            id to (o.str("category_name") ?: "Kategorie $id")
        }.toMap()

    private suspend fun syncXtream(cfg: ProviderConfig) {
        val base = XtreamUrls.normalizeServer(cfg.server)
        status("Anmeldung beim Anbieter …")
        val auth = readObject(XtreamUrls.api(base, cfg.username, cfg.password, null))
            ?: error("Server antwortet nicht im Xtream-Format")
        val userInfo = auth.obj("user_info")
        if (userInfo == null || userInfo.str("auth") == "0") error("Anmeldung abgelehnt – Zugangsdaten prüfen")
        _accountInfo.value = parseAccountInfo(auth)
        _accountInfo.value?.serverTimezone?.let { tz -> runCatching { settings.set(Keys.SERVER_TZ, tz) } }
        userInfo.str("status")?.let { if (!it.equals("Active", true)) error("Konto-Status: $it") }

        status("Live-TV-Kategorien …")
        val liveCats = categories(base, cfg, "get_live_categories")
        status("Live-Sender …")
        val channels = readArray(XtreamUrls.api(base, cfg.username, cfg.password, "get_live_streams")) { o ->
            val id = o.str("stream_id") ?: return@readArray null
            val cat = liveCats[o.str("category_id")] ?: "Sonstige"
            ChannelEntity(
                id = "x:live:$id", name = o.str("name") ?: "Kanal $id", category = cat,
                logo = o.str("stream_icon"), streamUrl = StreamResolver.ref("live", id, "ts"),
                epgId = o.str("epg_channel_id")?.lowercase(), number = o.str("num")?.toIntOrNull() ?: 0,
                adult = isAdultCategory(cat) || o.str("is_adult") == "1",
                country = Countries.detect(cat, o.str("name")),
                // Archiv laut Anbieter (tv_archive / tv_archive_duration) → TV Time Machine, Live-Pause, Zurückspulen
                archiveDays = if (o.str("tv_archive") == "1") (o.str("tv_archive_duration")?.toIntOrNull() ?: 1).coerceIn(1, 30) else 0,
                catchupMode = if (o.str("tv_archive") == "1") "xc" else null,
                catchupSource = if (o.str("tv_archive") == "1") id else null,
            )
        }

        status("Film-Kategorien …")
        val vodCats = categories(base, cfg, "get_vod_categories")
        status("Filme …")
        val movies = readArray(XtreamUrls.api(base, cfg.username, cfg.password, "get_vod_streams")) { o ->
            val id = o.str("stream_id") ?: return@readArray null
            val cat = vodCats[o.str("category_id")] ?: "Sonstige"
            MovieEntity(
                id = "x:vod:$id", name = o.str("name") ?: "Film $id", category = cat,
                poster = o.str("stream_icon"), backdrop = null, plot = o.str("plot"), genre = o.str("genre"),
                year = o.str("year") ?: o.str("releasedate")?.take(4), rating = o.str("rating")?.toDoubleOrNull(),
                durationSec = null, streamUrl = StreamResolver.ref("movie", id, o.str("container_extension") ?: "mp4"),
                addedAt = (o.str("added")?.toLongOrNull() ?: 0L) * 1000L,
                adult = isAdultCategory(cat) || o.str("is_adult") == "1",
                actors = o.str("cast") ?: o.str("actors"), director = o.str("director"),
                language = Countries.languageOf(Countries.detect(cat, o.str("name"))),
            )
        }

        status("Serien-Kategorien …")
        val seriesCats = categories(base, cfg, "get_series_categories")
        status("Serien …")
        val series = readArray(XtreamUrls.api(base, cfg.username, cfg.password, "get_series")) { o ->
            val id = o.str("series_id") ?: return@readArray null
            val cat = seriesCats[o.str("category_id")] ?: "Sonstige"
            SeriesEntity(
                id = "x:series:$id", name = o.str("name") ?: "Serie $id", category = cat,
                poster = o.str("cover"), backdrop = o.firstStr("backdrop_path"), plot = o.str("plot"),
                genre = o.str("genre"), year = o.str("releaseDate")?.take(4) ?: o.str("release_date")?.take(4),
                rating = o.str("rating")?.toDoubleOrNull(),
                addedAt = (o.str("last_modified")?.toLongOrNull() ?: 0L) * 1000L,
                adult = isAdultCategory(cat),
                actors = o.str("cast"), language = Countries.languageOf(Countries.detect(cat, o.str("name"))),
            )
        }

        status("Datenbank wird aktualisiert …")
        db.withTransaction {
            val dao = db.content()
            dao.clearChannels(); dao.clearMovies(); dao.clearSeries(); dao.clearEpisodes()
            channels.chunked(500).forEach { dao.upsertChannels(it) }
            movies.chunked(500).forEach { dao.upsertMovies(it) }
            series.chunked(500).forEach { dao.upsertSeries(it) }
        }
    }

    /** Lädt Staffeln/Episoden einer Xtream-Serie bei Bedarf nach. */
    suspend fun loadSeriesEpisodes(seriesId: String): Boolean = withContext(Dispatchers.IO) {
        if (!seriesId.startsWith("x:series:")) return@withContext true
        val cfg = creds.load() ?: return@withContext false
        val base = XtreamUrls.normalizeServer(cfg.server)
        val raw = seriesId.removePrefix("x:series:")
        try {
            val root = readObject(XtreamUrls.api(base, cfg.username, cfg.password, "get_series_info", mapOf("series_id" to raw)))
                ?: return@withContext false
            val list = ArrayList<EpisodeEntity>()
            fun add(o: JsonObject, seasonHint: Int) {
                val id = o.str("id") ?: return
                val info = o.obj("info")
                list += EpisodeEntity(
                    id = "x:ep:$id", seriesId = seriesId,
                    season = o.str("season")?.toIntOrNull() ?: seasonHint,
                    episode = o.str("episode_num")?.toIntOrNull() ?: (list.size + 1),
                    title = o.str("title") ?: "Episode",
                    plot = info?.str("plot"), image = info?.str("movie_image"),
                    durationSec = info?.str("duration_secs")?.toLongOrNull(),
                    streamUrl = StreamResolver.ref("series", id, o.str("container_extension") ?: "mp4"),
                )
            }
            when (val eps = root["episodes"]) {
                is JsonObject -> eps.forEach { (season, arr) ->
                    (arr as? JsonArray)?.forEach { el -> (el as? JsonObject)?.let { add(it, season.toIntOrNull() ?: 1) } }
                }
                is JsonArray -> eps.forEach { el ->
                    when (el) {
                        is JsonArray -> el.forEach { inner -> (inner as? JsonObject)?.let { add(it, 1) } }
                        is JsonObject -> add(el, 1)
                        else -> Unit
                    }
                }
                else -> Unit
            }
            root.obj("info")?.let { info ->
                val s = db.content().series(seriesId)
                if (s != null) db.content().upsertSeries(listOf(s.copy(
                    plot = info.str("plot") ?: s.plot, genre = info.str("genre") ?: s.genre, actors = info.str("cast") ?: s.actors,
                    backdrop = info.firstStr("backdrop_path") ?: s.backdrop,
                )))
            }
            db.withTransaction {
                db.content().clearEpisodesOf(seriesId)
                list.chunked(500).forEach { db.content().upsertEpisodes(it) }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Ergänzt Filmdetails (Handlung, Genre, Hintergrund) bei Xtream-Anbietern. */
    suspend fun loadMovieDetails(movieId: String) = withContext(Dispatchers.IO) {
        if (!movieId.startsWith("x:vod:")) return@withContext
        val cfg = creds.load() ?: return@withContext
        val base = XtreamUrls.normalizeServer(cfg.server)
        try {
            val root = readObject(XtreamUrls.api(base, cfg.username, cfg.password, "get_vod_info", mapOf("vod_id" to movieId.removePrefix("x:vod:"))))
                ?: return@withContext
            val info = root.obj("info") ?: return@withContext
            val m = db.content().movie(movieId) ?: return@withContext
            db.content().updateMovieDetails(
                movieId,
                plot = info.str("plot") ?: info.str("description") ?: m.plot,
                genre = info.str("genre") ?: m.genre,
                backdrop = info.firstStr("backdrop_path") ?: m.backdrop,
                durationSec = info.str("duration_secs")?.toLongOrNull() ?: m.durationSec,
            )
            db.content().updateMovieCredits(movieId, info.str("cast") ?: info.str("actors") ?: m.actors, info.str("director") ?: m.director)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun parseAccountInfo(auth: JsonObject): AccountInfo {
        val u = auth.obj("user_info")
        val srv = auth.obj("server_info")
        return AccountInfo(
            status = u?.str("status"),
            expiresAt = u?.str("exp_date")?.toLongOrNull()?.times(1000L),
            maxConnections = u?.str("max_connections")?.toIntOrNull(),
            activeConnections = u?.str("active_cons")?.toIntOrNull(),
            isTrial = u?.str("is_trial") == "1",
            createdAt = u?.str("created_at")?.toLongOrNull()?.times(1000L),
            serverTimezone = srv?.str("timezone"),
            checkedAt = System.currentTimeMillis(),
        )
    }

    /** Fragt den Kontostatus (Ablauf, Verbindungen) beim Xtream-Server ab. */
    suspend fun refreshAccountInfo(): AccountInfo? = withContext(Dispatchers.IO) {
        val cfg = creds.load() ?: return@withContext null
        if (cfg.type != ProviderType.XTREAM) return@withContext null
        runCatching {
            readObject(XtreamUrls.api(XtreamUrls.normalizeServer(cfg.server), cfg.username, cfg.password, null))?.let { parseAccountInfo(it) }
        }.getOrNull().also { _accountInfo.value = it }
    }

    // ------------------------------------------------------------------ EPG

    private suspend fun syncEpgInternal(cfg: ProviderConfig, m3uHeaderEpg: String?) {
        val s = settings.current()
        val url = s.epgUrlOverride.takeIf { it.isNotBlank() }
            ?: cfg.epgUrl.takeIf { it.isNotBlank() }
            ?: m3uHeaderEpg
            ?: if (cfg.type == ProviderType.XTREAM) XtreamUrls.xmltv(XtreamUrls.normalizeServer(cfg.server), cfg.username, cfg.password) else null
        if (url == null) return
        status("TV-Programm (EPG) wird geladen …")
        val now = System.currentTimeMillis()
        val from = now - 3 * 3_600_000L
        val to = now + s.epgDays.coerceIn(1, 7) * 86_400_000L
        val offset = s.epgOffsetHours * 3_600_000L
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("EPG: HTTP ${resp.code}")
            val body = resp.body ?: error("EPG: leere Antwort")
            val input = maybeGunzip(body.byteStream())
            val parser = XmltvParser { Xml.newPullParser() }
            val batch = ArrayList<EpgProgramEntity>(2000)
            db.epg().clear()
            var total = 0
            parser.parse(input, offset, from, to) { p ->
                batch += EpgProgramEntity(channelEpgId = p.channel.lowercase(), startMs = p.startMs, endMs = p.stopMs, title = p.title, description = p.description)
                if (batch.size >= 2000) {
                    val copy = ArrayList(batch); batch.clear()
                    runBlocking { db.epg().insertPrograms(copy) }
                    total += copy.size
                    status("EPG: $total Sendungen …")
                }
            }
            if (batch.isNotEmpty()) db.epg().insertPrograms(batch)
        }
        settings.set(Keys.LAST_EPG_SYNC, System.currentTimeMillis())
    }

    private fun maybeGunzip(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(4)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024), 64 * 1024) else buffered
    }
}
