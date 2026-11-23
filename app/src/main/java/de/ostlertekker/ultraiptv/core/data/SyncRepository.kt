package de.ostlertekker.ultraiptv.core.data

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

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
    private val http: OkHttpClient,
    private val creds: CredentialStore,
    private val settings: SettingsRepository,
) {
    /** YouTube-Trailer laut Anbieter (Film-/Serien-ID → Video-ID oder Link), wird beim Öffnen der Details geladen. */
    val trailers = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val _accountInfo = MutableStateFlow<AccountInfo?>(null)
    val accountInfo: StateFlow<AccountInfo?> = _accountInfo.asStateFlow()

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val epgTempDir: java.io.File get() = java.io.File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp").let { java.io.File(it, "ultra-epg").apply { mkdirs() } }

    private fun status(msg: String) { _state.value = SyncState(running = true, message = msg) }

    /** Vollständige Synchronisation: Kataloge + optional EPG. */
    suspend fun syncAll(includeEpg: Boolean = true): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cfg = creds.load()
            if (cfg == null) {
                _state.value = SyncState(false, tr("Keine Quelle konfiguriert"), tr("Bitte zuerst eine Quelle einrichten"))
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
                _state.value = SyncState(false, tr("Synchronisation abgeschlossen"))
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
                _state.value = SyncState(false, tr("EPG aktualisiert"))
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
        status(tr("Playlist wird geladen …"))
        val request = Request.Builder().url(cfg.m3uUrl.trim()).build()
        val result = http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error(trf("Playlist: HTTP {0}", resp.code))
            val body = resp.body ?: error(tr("Playlist: leere Antwort"))
            body.charStream().buffered().useLines { M3uParser.parse(it) }
        }
        if (result.entries.isEmpty()) error(tr("Playlist enthält keine Einträge"))
        result.epgUrl?.let { settings.set(Keys.M3U_HEADER_EPG, it) }
        status(trf("{0} Einträge werden verarbeitet …", result.entries.size))

        val cat = m3uCatalog(result.entries)
        val channels = cat.channels; val movies = cat.movies; val series = cat.series; val episodes = cat.episodes
        status(tr("Datenbank wird aktualisiert …"))
        db.withTransaction {
            val dao = db.content()
            dao.clearChannels(); dao.clearMovies(); dao.clearSeries(); dao.clearEpisodes()
            channels.chunked(500).forEach { dao.upsertChannels(it) }
            movies.chunked(500).forEach { dao.upsertMovies(it) }
            series.chunked(500).forEach { dao.upsertSeries(it) }
            episodes.chunked(500).forEach { dao.upsertEpisodes(it) }
        }
        return result.epgUrl
    }


    /** Inhalt einer M3U-Playlist als Datenbank-Einträge. */
    class M3uCatalog(val channels: List<ChannelEntity>, val movies: List<MovieEntity>, val series: List<SeriesEntity>, val episodes: List<EpisodeEntity>)

    private fun m3uCatalog(entries: List<de.ostlertekker.ultraiptv.core.network.M3uEntry>): M3uCatalog {
        val channels = ArrayList<ChannelEntity>()
        val movies = ArrayList<MovieEntity>()
        val series = LinkedHashMap<String, SeriesEntity>()
        val episodes = ArrayList<EpisodeEntity>()
        val now = System.currentTimeMillis()
        entries.forEachIndexed { index, e ->
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
        return M3uCatalog(channels, movies, series.values.toList(), episodes)
    }

    // ============================================================ System-Updates (inkrementell)
    //
    // Anbieter liefern keine Versionsnummern. Deshalb: Rohdaten in den Zwischenspeicher laden, SHA-256 bilden und mit der
    // zuletzt übernommenen Fassung vergleichen. Nur bei Abweichung wird ein Update gemeldet – mit echtem Vergleich zur Datenbank
    // (neu / geändert / entfernt). Übernommen wird in einer einzigen Transaktion: IDs bleiben, Favoriten/Verlauf hängen an den IDs,
    // nachgeladene Details (Beschreibung, Hintergrund, Laufzeit, Episoden) bleiben erhalten. Bei Fehlern bleibt alles wie vorher.

    enum class CatalogPart(val id: String, val label: String) {
        LIVE("iptv-live", tr("Live-TV: Sender, Kategorien, Catch-up")),
        VOD("iptv-vod", tr("Filme: Katalog, Kategorien, Metadaten")),
        SERIES("iptv-series", tr("Serien: Katalog, Kategorien, Metadaten")),
        PLAYLIST("iptv-m3u", tr("Playlist (M3U): Sender, Filme, Serien, Episoden")),
    }

    data class CatalogDiff(val added: Int, val changed: Int, val removed: Int, val total: Int) {
        val empty get() = added == 0 && changed == 0 && removed == 0
        fun text() = if (empty) "keine inhaltlichen Änderungen" else "$added neu · $changed geändert · $removed entfernt (gesamt $total)"
    }

    /** Welche Katalogteile es für die eingerichtete Quelle gibt. */
    fun catalogParts(): List<CatalogPart> = when (creds.load()?.type) {
        ProviderType.XTREAM -> listOf(CatalogPart.LIVE, CatalogPart.VOD, CatalogPart.SERIES)
        ProviderType.M3U -> listOf(CatalogPart.PLAYLIST)
        null -> emptyList()
    }

    /** Lädt die Rohdaten eines Katalogteils in [dir] (Kategorien + Liste bzw. Playlist). Gibt die Dateien zurück. */
    suspend fun downloadCatalog(part: CatalogPart, dir: java.io.File): List<java.io.File> = withContext(Dispatchers.IO) {
        val cfg = creds.load() ?: error(tr("Keine Quelle eingerichtet"))
        dir.mkdirs()
        fun save(url: String, name: String): java.io.File {
            val out = java.io.File(dir, name); val part2 = java.io.File(dir, "$name.part")
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                val body = r.body ?: throw java.io.IOException(tr("Leere Antwort"))
                body.byteStream().use { i -> part2.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            }
            if (part2.length() == 0L) throw java.io.IOException(tr("Leere Antwort"))
            out.delete(); if (!part2.renameTo(out)) throw java.io.IOException(tr("Zwischenspeichern fehlgeschlagen"))
            return out
        }
        when (part) {
            CatalogPart.PLAYLIST -> listOf(save(cfg.m3uUrl.trim(), "playlist.m3u"))
            else -> {
                val base = XtreamUrls.normalizeServer(cfg.server)
                val (catAction, listAction) = when (part) {
                    CatalogPart.LIVE -> "get_live_categories" to "get_live_streams"
                    CatalogPart.VOD -> "get_vod_categories" to "get_vod_streams"
                    else -> "get_series_categories" to "get_series"
                }
                listOf(save(XtreamUrls.api(base, cfg.username, cfg.password, catAction), "categories.json"),
                    save(XtreamUrls.api(base, cfg.username, cfg.password, listAction), "items.json"))
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> readArrayFile(f: java.io.File, map: (JsonObject) -> T?): List<T> = f.inputStream().buffered().use { input ->
        json.decodeToSequence<JsonElement>(input, DecodeSequenceMode.AUTO_DETECT).mapNotNull { (it as? JsonObject)?.let(map) }.toList()
    }

    private fun catsFile(f: java.io.File): Map<String, String> = readArrayFile(f) { o ->
        val id = o.str("category_id") ?: return@readArrayFile null
        id to (o.str("category_name") ?: "Kategorie $id")
    }.toMap()

    // Nachgeladene Details und stabile Werte der bisherigen Fassung übernehmen (nur wo die neue Liste nichts liefert)
    private fun mergeMovie(n: MovieEntity, o: MovieEntity?, keepAdded: Boolean) = if (o == null) n else n.copy(
        backdrop = n.backdrop ?: o.backdrop, durationSec = n.durationSec ?: o.durationSec, plot = n.plot ?: o.plot,
        genre = n.genre ?: o.genre, actors = n.actors ?: o.actors, director = n.director ?: o.director,
        addedAt = if (keepAdded) o.addedAt else n.addedAt)
    private fun mergeSeries(n: SeriesEntity, o: SeriesEntity?, keepAdded: Boolean) = if (o == null) n else n.copy(
        backdrop = n.backdrop ?: o.backdrop, plot = n.plot ?: o.plot, genre = n.genre ?: o.genre, actors = n.actors ?: o.actors,
        addedAt = if (keepAdded) o.addedAt else n.addedAt)

    private class Plan<T>(val upsert: List<T>, val removeIds: List<String>, val diff: CatalogDiff)

    private fun <T> plan(new: List<T>, old: List<T>, id: (T) -> String, merge: (T, T?) -> T): Plan<T> {
        val oldMap = old.associateBy(id)
        val merged = new.distinctBy(id).map { merge(it, oldMap[id(it)]) }
        val newIds = merged.mapTo(HashSet(merged.size)) { id(it) }
        var added = 0; var changed = 0
        val up = ArrayList<T>()
        for (m in merged) { val o = oldMap[id(m)]; if (o == null) { added++; up += m } else if (o != m) { changed++; up += m } }
        val removed = old.map(id).filter { it !in newIds }
        return Plan(up, removed, CatalogDiff(added, changed, removed.size, merged.size))
    }

    private class FullPlan(val ch: Plan<ChannelEntity>?, val mv: Plan<MovieEntity>?, val se: Plan<SeriesEntity>?, val ep: Plan<EpisodeEntity>?) {
        val diff: CatalogDiff get() {
            val l = listOfNotNull(ch?.diff, mv?.diff, se?.diff, ep?.diff)
            return CatalogDiff(l.sumOf { it.added }, l.sumOf { it.changed }, l.sumOf { it.removed }, l.sumOf { it.total })
        }
    }

    private suspend fun buildPlan(part: CatalogPart, files: List<java.io.File>): FullPlan {
        val dao = db.content()
        return when (part) {
            CatalogPart.LIVE -> { val c = catsFile(files[0]); FullPlan(plan(readArrayFile(files[1]) { mapLive(it, c) }, dao.allChannels().filter { it.id.startsWith("x:") }, { it.id }) { n, _ -> n }, null, null, null) }
            CatalogPart.VOD -> { val c = catsFile(files[0]); FullPlan(null, plan(readArrayFile(files[1]) { mapVod(it, c) }, dao.allMovies().filter { it.id.startsWith("x:") }, { it.id }) { n, o -> mergeMovie(n, o, false) }, null, null) }
            CatalogPart.SERIES -> { val c = catsFile(files[0]); FullPlan(null, null, plan(readArrayFile(files[1]) { mapSeries(it, c) }, dao.allSeries().filter { it.id.startsWith("x:") }, { it.id }) { n, o -> mergeSeries(n, o, false) }, null) }
            CatalogPart.PLAYLIST -> {
                val res = files[0].bufferedReader().useLines { M3uParser.parse(it) }
                if (res.entries.isEmpty()) error(tr("Playlist enthält keine Einträge"))
                res.epgUrl?.let { settings.set(Keys.M3U_HEADER_EPG, it) }
                val cat = m3uCatalog(res.entries)
                FullPlan(
                    plan(cat.channels, dao.allChannels().filter { it.id.startsWith("m3u:") }, { it.id }) { n, _ -> n },
                    plan(cat.movies, dao.allMovies().filter { it.id.startsWith("m3u:") }, { it.id }) { n, o -> mergeMovie(n, o, true) },
                    plan(cat.series, dao.allSeries().filter { it.id.startsWith("m3u:") }, { it.id }) { n, o -> mergeSeries(n, o, true) },
                    plan(cat.episodes, dao.allM3uEpisodes(), { it.id }) { n, _ -> n },
                )
            }
        }
    }

    /** Vergleicht bereitliegende Rohdaten mit der Datenbank (ohne etwas zu ändern). */
    suspend fun diffCatalog(part: CatalogPart, files: List<java.io.File>): CatalogDiff = withContext(Dispatchers.IO) { buildPlan(part, files).diff }

    /** Übernimmt bereitliegende Rohdaten in einer Transaktion. Bei Fehlern bleibt die Datenbank unverändert. */
    suspend fun applyCatalog(part: CatalogPart, files: List<java.io.File>): CatalogDiff = mutex.withLock {
        withContext(Dispatchers.IO) {
            val p = buildPlan(part, files)
            val dao = db.content()
            db.withTransaction {
                p.ch?.let { pl -> pl.removeIds.chunked(500).forEach { dao.deleteChannels(it) }; pl.upsert.chunked(500).forEach { dao.upsertChannels(it) } }
                p.mv?.let { pl -> pl.removeIds.chunked(500).forEach { dao.deleteMovies(it) }; pl.upsert.chunked(500).forEach { dao.upsertMovies(it) } }
                p.se?.let { pl ->
                    pl.removeIds.chunked(500).forEach { dao.deleteSeries(it); dao.deleteEpisodesOfSeries(it) } // Episoden entfernter Serien mit entfernen
                    pl.upsert.chunked(500).forEach { dao.upsertSeries(it) }
                }
                p.ep?.let { pl -> pl.removeIds.chunked(500).forEach { dao.deleteEpisodes(it) }; pl.upsert.chunked(500).forEach { dao.upsertEpisodes(it) } }
            }
            runCatching { db.openHelper.writableDatabase.let { w -> de.ostlertekker.ultraiptv.core.database.Migrations.SANITIZE.forEach { w.execSQL(it) } } }
            settings.set(Keys.LAST_SYNC, System.currentTimeMillis())
            p.diff
        }
    }

    // ---------------------------------------------------------------- EPG (Datei zuerst, dann atomar übernehmen)

    /** Adresse des EPG (wie bei der normalen Synchronisation). */
    suspend fun epgSourceUrl(): String? {
        val cfg = creds.load() ?: return null
        val s = settings.current()
        return s.epgUrlOverride.takeIf { it.isNotBlank() }
            ?: cfg.epgUrl.takeIf { it.isNotBlank() }
            ?: settings.current().m3uHeaderEpg.takeIf { cfg.type == ProviderType.M3U && it.isNotBlank() }
            ?: if (cfg.type == ProviderType.XTREAM) XtreamUrls.xmltv(XtreamUrls.normalizeServer(cfg.server), cfg.username, cfg.password) else null
    }

    /** Liest eine XMLTV-Datei und ersetzt das EPG in einer Transaktion. Erst wenn die Datei vollständig gelesen ist, wird getauscht. */
    suspend fun importEpgFile(file: java.io.File): Int = withContext(Dispatchers.IO) {
        val s = settings.current()
        val now = System.currentTimeMillis()
        val from = now - 86_400_000L
        val to = now + (s.epgDays.coerceIn(1, 7) + 1) * 86_400_000L
        val offset = s.epgOffsetHours * 3_600_000L
        // In EINER Datenbank-Transaktion: alte Sendungen löschen, neue streamend einfügen (wenig Speicher, auch auf Fire TV).
        // Bricht das Lesen ab, wird die Transaktion zurückgerollt – das bisherige EPG bleibt vollständig erhalten.
        var count = 0
        db.runInTransaction {
            val w = db.openHelper.writableDatabase
            w.execSQL("DELETE FROM epg")
            val st = w.compileStatement("INSERT INTO epg (channelEpgId, startMs, endMs, title, description) VALUES (?, ?, ?, ?, ?)")
            file.inputStream().use { raw ->
                XmltvParser { Xml.newPullParser() }.parse(maybeGunzip(raw), offset, from, to) { p ->
                    st.clearBindings()
                    st.bindString(1, p.channel.lowercase()); st.bindLong(2, p.startMs); st.bindLong(3, p.stopMs); st.bindString(4, p.title)
                    p.description?.let { st.bindString(5, it) } ?: st.bindNull(5)
                    st.executeInsert(); count++
                }
            }
            if (count == 0) throw IllegalStateException(tr("EPG-Datei enthält keine Sendungen im Zeitraum – bisheriges EPG bleibt"))
        }
        settings.set(Keys.LAST_EPG_SYNC, System.currentTimeMillis())
        count
    }

    // --------------------------------------------------------------- Xtream

    /** Antwort direkt über OkHttp streamen (große Kataloge werden nie komplett in den Speicher geladen). */
    private suspend fun fetch(url: String): okhttp3.ResponseBody = withContext(Dispatchers.IO) {
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) { val c = resp.code; resp.close(); throw java.io.IOException("HTTP $c") }
        resp.body ?: run { resp.close(); throw java.io.IOException(tr("Leere Antwort")) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> readArray(url: String, map: (JsonObject) -> T?): List<T> {
        val body = fetch(url)
        return body.use { b ->
            b.byteStream().use { input ->
                json.decodeToSequence<JsonElement>(input, DecodeSequenceMode.AUTO_DETECT)
                    .mapNotNull { (it as? JsonObject)?.let(map) }
                    .toList()
            }
        }
    }

    private suspend fun readObject(url: String): JsonObject? {
        val text = fetch(url).use { it.string() }
        return runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    }

    private suspend fun categories(base: String, cfg: ProviderConfig, action: String): Map<String, String> =
        readArray(XtreamUrls.api(base, cfg.username, cfg.password, action)) { o ->
            val id = o.str("category_id") ?: return@readArray null
            id to (o.str("category_name") ?: "Kategorie $id")
        }.toMap()

    private suspend fun syncXtream(cfg: ProviderConfig) {
        val base = XtreamUrls.normalizeServer(cfg.server)
        status(tr("Anmeldung beim Anbieter …"))
        val auth = readObject(XtreamUrls.api(base, cfg.username, cfg.password, null))
            ?: error(tr("Server antwortet nicht im Xtream-Format"))
        val userInfo = auth.obj("user_info")
        if (userInfo == null || userInfo.str("auth") == "0") error(tr("Anmeldung abgelehnt – Zugangsdaten prüfen"))
        _accountInfo.value = parseAccountInfo(auth)
        _accountInfo.value?.serverTimezone?.let { tz -> runCatching { settings.set(Keys.SERVER_TZ, tz) } }
        userInfo.str("status")?.let { if (!it.equals("Active", true)) error(trf("Konto-Status: {0}", it)) }

        status(tr("Live-TV-Kategorien …"))
        val liveCats = categories(base, cfg, "get_live_categories")
        status(tr("Live-Sender …"))
        val channels = readArray(XtreamUrls.api(base, cfg.username, cfg.password, "get_live_streams")) { o -> mapLive(o, liveCats) }

        status(tr("Film-Kategorien …"))
        val vodCats = categories(base, cfg, "get_vod_categories")
        status(tr("Filme …"))
        val movies = readArray(XtreamUrls.api(base, cfg.username, cfg.password, "get_vod_streams")) { o -> mapVod(o, vodCats) }

        status(tr("Serien-Kategorien …"))
        val seriesCats = categories(base, cfg, "get_series_categories")
        status(tr("Serien …"))
        val series = readArray(XtreamUrls.api(base, cfg.username, cfg.password, "get_series")) { o -> mapSeries(o, seriesCats) }

        status(tr("Datenbank wird aktualisiert …"))
        db.withTransaction {
            val dao = db.content()
            dao.clearChannels(); dao.clearMovies(); dao.clearSeries(); dao.clearEpisodes()
            channels.chunked(500).forEach { dao.upsertChannels(it) }
            movies.chunked(500).forEach { dao.upsertMovies(it) }
            series.chunked(500).forEach { dao.upsertSeries(it) }
        }
    }


    // Abbildung der Xtream-Antworten auf Datenbank-Einträge (gemeinsam für Voll-Sync und System-Updates)
    private fun mapLive(o: JsonObject, cats: Map<String, String>): ChannelEntity? {
        val id = o.str("stream_id") ?: return null
        val cat = cats[o.str("category_id")] ?: "Sonstige"
        return ChannelEntity(
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

    private fun mapVod(o: JsonObject, cats: Map<String, String>): MovieEntity? {
        val id = o.str("stream_id") ?: return null
        val cat = cats[o.str("category_id")] ?: "Sonstige"
        return MovieEntity(
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

    private fun mapSeries(o: JsonObject, cats: Map<String, String>): SeriesEntity? {
        val id = o.str("series_id") ?: return null
        val cat = cats[o.str("category_id")] ?: "Sonstige"
        return SeriesEntity(
            id = "x:series:$id", name = o.str("name") ?: "Serie $id", category = cat,
            poster = o.str("cover"), backdrop = o.firstStr("backdrop_path"), plot = o.str("plot"),
            genre = o.str("genre"), year = o.str("releaseDate")?.take(4) ?: o.str("release_date")?.take(4),
            rating = o.str("rating")?.toDoubleOrNull(),
            addedAt = (o.str("last_modified")?.toLongOrNull() ?: 0L) * 1000L,
            adult = isAdultCategory(cat),
            actors = o.str("cast"), language = Countries.languageOf(Countries.detect(cat, o.str("name"))),
        )
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
                (info.str("youtube_trailer") ?: info.str("trailer"))?.trim()?.takeIf { it.isNotBlank() }?.let { trailers[seriesId] = it }
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
            (info.str("youtube_trailer") ?: info.str("trailer"))?.trim()?.takeIf { it.isNotBlank() }?.let { trailers[movieId] = it }
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
        status(tr("TV-Programm (EPG) wird geladen …"))
        // Erst vollständig in eine Datei laden, dann atomar übernehmen – ein abgebrochener Download löscht nie das bisherige EPG
        val tmp = java.io.File(epgTempDir, "epg-sync.xml")
        val part = java.io.File(epgTempDir, "epg-sync.xml.part")
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error(trf("EPG: HTTP {0}", resp.code))
                val body = resp.body ?: error(tr("EPG: leere Antwort"))
                body.byteStream().use { i2 -> part.outputStream().use { o -> i2.copyTo(o, 64 * 1024) } }
            }
            tmp.delete(); if (!part.renameTo(tmp)) error(tr("EPG: Zwischenspeichern fehlgeschlagen"))
            status(tr("TV-Programm (EPG) wird übernommen …"))
            importEpgFile(tmp)
        } finally { part.delete(); tmp.delete() }
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
