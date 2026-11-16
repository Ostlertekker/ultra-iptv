package de.ostlertekker.ultraiptv.core.jellyfin

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodecList
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ostlertekker.ultraiptv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// =====================================================================================================
// Modelle
// =====================================================================================================

/** Ein Jellyfin-Eintrag (Film, Serie, Staffel, Folge oder Bibliothek) – nur die Felder, die die App anzeigt. */
data class JfItem(
    val id: String,
    val name: String,
    val type: String,
    val overview: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val officialRating: String? = null,
    val runtimeMs: Long = 0,
    val genres: List<String> = emptyList(),
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonId: String? = null,
    val index: Int? = null,
    val parentIndex: Int? = null,
    val hasPrimary: Boolean = false,
    val hasBackdrop: Boolean = false,
    val parentBackdropId: String? = null,
    val seriesPrimaryId: String? = null,
    val played: Boolean = false,
    val positionMs: Long = 0,
    val favorite: Boolean = false,
    val collectionType: String? = null,
    val childCount: Int? = null,
    val people: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
) {
    val isSeries get() = type == "Series"
    val isEpisode get() = type == "Episode"
    val isMovie get() = type == "Movie"
    val isFolder get() = type == "CollectionFolder" || type == "UserView" || type == "Folder" || type == "BoxSet"
    val progress: Float? get() = if (runtimeMs > 0 && positionMs > 0) (positionMs.toFloat() / runtimeMs).coerceIn(0f, 1f) else null
}

data class JfSession(val server: String, val userId: String, val userName: String, val token: String, val serverName: String)

data class JfSubtitle(val url: String, val language: String?, val label: String, val format: String)

/** Wie ein Titel abgespielt wird: Direct Play (Originaldatei), Direct Stream oder Transkodierung (HLS). */
data class JfPlayback(
    val url: String,
    val method: String,
    val fallbackUrl: String?,
    val mediaSourceId: String,
    val playSessionId: String?,
    val subtitles: List<JfSubtitle>,
)

class JellyfinException(message: String, val code: Int = 0) : IOException(message)

// =====================================================================================================
// Repository: Anmeldung, sichere Speicherung, Abfragen mit Cache, Wiedergabe, Fortschritt
// =====================================================================================================

@Singleton
class JellyfinRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    baseClient: OkHttpClient,
) {
    private val http = baseClient.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val prefs: SharedPreferences by lazy {
        try {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(context, "ultra_jellyfin", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        } catch (e: Exception) {
            context.getSharedPreferences("ultra_jellyfin_fallback", Context.MODE_PRIVATE)
        }
    }

    private val _session = MutableStateFlow<JfSession?>(null)
    val session: StateFlow<JfSession?> = _session.asStateFlow()
    private var loaded = false

    val deviceId: String by lazy {
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
    }

    /** Gespeicherte Sitzung laden (Token) – ohne Netzwerk. */
    @Synchronized
    fun restore(): JfSession? {
        if (!loaded) {
            loaded = true
            val server = prefs.getString("server", null); val uid = prefs.getString("user_id", null); val token = prefs.getString("token", null)
            if (server != null && uid != null && token != null) {
                _session.value = JfSession(server, uid, prefs.getString("user_name", "") ?: "", token, prefs.getString("server_name", "Jellyfin") ?: "Jellyfin")
            }
        }
        return _session.value
    }

    fun savedServer(): String = prefs.getString("server", "") ?: ""
    fun savedUser(): String = prefs.getString("login_user", "") ?: ""

    private fun authHeader(token: String?): String = buildString {
        append("MediaBrowser Client=\"ULTRA IPTV\", Device=\"")
        append((android.os.Build.MODEL ?: "Android TV").replace("\"", ""))
        append("\", DeviceId=\"").append(deviceId).append("\", Version=\"").append(BuildConfig.VERSION_NAME).append('"')
        if (token != null) append(", Token=\"").append(token).append('"')
    }

    /** Adresse vereinheitlichen: Schema ergänzen, Schrägstrich am Ende entfernen. */
    fun normalizeServer(input: String): String {
        var s = input.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s
    }

    // ------------------------------------------------------------------ HTTP

    private suspend fun call(method: String, url: String, body: JsonElement? = null, token: String? = _session.value?.token): JsonElement? =
        withContext(Dispatchers.IO) {
            val rb = when {
                body != null -> body.toString().toRequestBody(jsonType)
                method == "POST" -> "".toRequestBody(jsonType)
                else -> null
            }
            val req = Request.Builder().url(url).method(method, if (method == "GET" || method == "DELETE" && rb == null) null else rb)
                .header("Authorization", authHeader(token))
                .header("X-Emby-Authorization", authHeader(token))
                .header("Accept", "application/json")
                .apply { if (token != null) header("X-Emby-Token", token) }
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 -> throw JellyfinException("Anmeldung abgelaufen oder ungültig", 401)
                    resp.code == 403 -> throw JellyfinException("Keine Berechtigung für diesen Inhalt", 403)
                    resp.code == 404 -> throw JellyfinException("Nicht gefunden (Server-Adresse prüfen)", 404)
                    !resp.isSuccessful -> throw JellyfinException("Serverfehler ${resp.code}", resp.code)
                    text.isBlank() -> null
                    else -> runCatching { json.parseToJsonElement(text) }.getOrNull()
                }
            }
        }

    /** Aufruf mit Sitzung; bei abgelaufenem Token automatisch neu anmelden (gespeichertes Passwort). */
    private suspend fun authed(method: String, path: String, body: JsonElement? = null): JsonElement? {
        val s = restore() ?: throw JellyfinException("Nicht mit Jellyfin verbunden", 401)
        return try {
            call(method, s.server + path, body, s.token)
        } catch (e: JellyfinException) {
            if (e.code != 401) throw e
            val user = prefs.getString("login_user", null); val pw = prefs.getString("login_pw", null)
            if (user == null || pw == null) { clearSession(); throw e }
            val fresh = login(s.server, user, pw)
            call(method, fresh.server + path, body, fresh.token)
        }
    }

    // ------------------------------------------------------------------ Cache (Metadaten)

    private data class Cached(val at: Long, val value: JsonElement?)
    private val cache = object : LinkedHashMap<String, Cached>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 120
    }
    private val cacheTtl = 3 * 60_000L

    private suspend fun cachedGet(path: String): JsonElement? {
        synchronized(cache) { cache[path]?.takeIf { System.currentTimeMillis() - it.at < cacheTtl }?.let { return it.value } }
        val v = authed("GET", path)
        synchronized(cache) { cache[path] = Cached(System.currentTimeMillis(), v) }
        return v
    }

    fun invalidate() = synchronized(cache) { cache.clear() }

    // ------------------------------------------------------------------ Anmeldung & Sitzung

    suspend fun serverName(server: String): String {
        val info = call("GET", normalizeServer(server) + "/System/Info/Public", token = null)?.jsonObject
        return info?.get("ServerName")?.str() ?: "Jellyfin"
    }

    suspend fun login(serverInput: String, user: String, password: String): JfSession {
        val server = normalizeServer(serverInput)
        val name = runCatching { serverName(server) }.getOrElse { throw JellyfinException("Server nicht erreichbar: ${it.message ?: server}") }
        val body = buildJsonObject { put("Username", user); put("Pw", password) }
        val res = try {
            call("POST", "$server/Users/AuthenticateByName", body, token = null)?.jsonObject
        } catch (e: JellyfinException) {
            throw if (e.code == 401) JellyfinException("Benutzername oder Passwort falsch", 401) else e
        } ?: throw JellyfinException("Leere Antwort vom Server")
        val token = res["AccessToken"]?.str() ?: throw JellyfinException("Kein Zugangstoken erhalten")
        val u = res["User"]?.jsonObject
        val session = JfSession(server, u?.get("Id")?.str() ?: "", u?.get("Name")?.str() ?: user, token, name)
        prefs.edit().putString("server", server).putString("user_id", session.userId).putString("user_name", session.userName)
            .putString("token", token).putString("server_name", name).putString("login_user", user).putString("login_pw", password).apply()
        loaded = true
        _session.value = session
        invalidate()
        return session
    }

    suspend fun logout() {
        runCatching { authed("POST", "/Sessions/Logout") }
        clearSession()
        prefs.edit().remove("login_pw").apply()
    }

    private fun clearSession() {
        prefs.edit().remove("token").remove("user_id").apply()
        _session.value = null
        invalidate()
    }

    // ------------------------------------------------------------------ Bilder

    fun image(item: JfItem, kind: String = "Primary", width: Int = 360): String? {
        val s = _session.value ?: return null
        return when {
            kind == "Backdrop" && item.hasBackdrop -> "${s.server}/Items/${item.id}/Images/Backdrop/0?fillWidth=1280&quality=85"
            kind == "Backdrop" && item.parentBackdropId != null -> "${s.server}/Items/${item.parentBackdropId}/Images/Backdrop/0?fillWidth=1280&quality=85"
            kind == "Backdrop" -> null
            item.hasPrimary -> "${s.server}/Items/${item.id}/Images/Primary?fillWidth=$width&quality=90"
            item.seriesPrimaryId != null -> "${s.server}/Items/${item.seriesPrimaryId}/Images/Primary?fillWidth=$width&quality=90"
            else -> null
        }
    }

    // ------------------------------------------------------------------ Abfragen

    private val fields = "Overview,Genres,ProductionYear,CommunityRating,OfficialRating,RunTimeTicks,ChildCount,ParentId"
    private fun uid() = restore()?.userId ?: throw JellyfinException("Nicht mit Jellyfin verbunden", 401)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun itemsOf(el: JsonElement?): List<JfItem> = when (el) {
        is JsonArray -> el.mapNotNull { parse(it) }
        is JsonObject -> (el["Items"] as? JsonArray)?.mapNotNull { parse(it) }.orEmpty()
        else -> emptyList()
    }

    suspend fun libraries(): List<JfItem> {
        val u = uid()
        val el = runCatching { cachedGet("/UserViews?userId=$u") }.getOrElse { cachedGet("/Users/$u/Views") }
        return itemsOf(el).filter { it.collectionType == null || it.collectionType in setOf("movies", "tvshows", "homevideos", "boxsets", "mixed", "musicvideos") }
    }

    suspend fun items(
        types: String? = null, parentId: String? = null, sortBy: String = "SortName", descending: Boolean = false,
        filters: String? = null, limit: Int = 40, start: Int = 0, recursive: Boolean = true, genre: String? = null,
    ): Pair<List<JfItem>, Int> {
        val q = StringBuilder("/Users/${uid()}/Items?Fields=$fields&EnableImageTypes=Primary,Backdrop&ImageTypeLimit=1&Limit=$limit&StartIndex=$start")
        q.append("&SortBy=").append(sortBy).append("&SortOrder=").append(if (descending) "Descending" else "Ascending")
        if (recursive) q.append("&Recursive=true")
        types?.let { q.append("&IncludeItemTypes=").append(it) }
        parentId?.let { q.append("&ParentId=").append(it) }
        filters?.let { q.append("&Filters=").append(it) }
        genre?.let { q.append("&Genres=").append(enc(it)) }
        val el = cachedGet(q.toString())
        val total = (el as? JsonObject)?.get("TotalRecordCount")?.int() ?: 0
        return itemsOf(el) to total
    }

    suspend fun latest(parentId: String? = null): List<JfItem> =
        itemsOf(cachedGet("/Users/${uid()}/Items/Latest?Limit=30&Fields=$fields&GroupItems=true" + (parentId?.let { "&ParentId=$it" } ?: "")))

    suspend fun resume(): List<JfItem> = itemsOf(cachedGet("/Users/${uid()}/Items/Resume?Limit=30&MediaTypes=Video&Fields=$fields"))
    suspend fun nextUp(): List<JfItem> = itemsOf(cachedGet("/Shows/NextUp?userId=${uid()}&Limit=30&Fields=$fields"))
    suspend fun recentlyWatched() = items("Movie,Episode", sortBy = "DatePlayed", descending = true, filters = "IsPlayed", limit = 30).first
    suspend fun favorites() = items("Movie,Series,Episode", sortBy = "SortName", filters = "IsFavorite", limit = 60).first
    suspend fun item(id: String): JfItem? = parse(authed("GET", "/Users/${uid()}/Items/$id"))
    suspend fun seasons(seriesId: String) = itemsOf(cachedGet("/Shows/$seriesId/Seasons?userId=${uid()}&Fields=$fields"))
    suspend fun episodes(seriesId: String, seasonId: String? = null): List<JfItem> =
        itemsOf(authed("GET", "/Shows/$seriesId/Episodes?userId=${uid()}&Fields=$fields" + (seasonId?.let { "&SeasonId=$it" } ?: "")))
    suspend fun similar(id: String) = itemsOf(cachedGet("/Items/$id/Similar?userId=${uid()}&Limit=16&Fields=$fields"))

    suspend fun setPlayed(id: String, played: Boolean) { authed(if (played) "POST" else "DELETE", "/Users/${uid()}/PlayedItems/$id"); invalidate() }
    suspend fun setFavorite(id: String, fav: Boolean) { authed(if (fav) "POST" else "DELETE", "/Users/${uid()}/FavoriteItems/$id"); invalidate() }

    // ------------------------------------------------------------------ Wiedergabe

    private fun hasDecoder(mime: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
    }.getOrDefault(false)

    private val deviceProfile: JsonObject by lazy {
        val video = buildList {
            add("h264"); add("mpeg2video"); add("mpeg4")
            if (hasDecoder("video/hevc")) add("hevc")
            if (hasDecoder("video/x-vnd.on2.vp9")) add("vp9")
            if (hasDecoder("video/av01")) add("av1")
        }.joinToString(",")
        buildJsonObject {
            put("Name", "ULTRA IPTV")
            put("MaxStreamingBitrate", 120_000_000)
            put("MaxStaticBitrate", 200_000_000)
            put("DirectPlayProfiles", buildJsonArray {
                add(buildJsonObject {
                    put("Container", "mp4,m4v,mkv,webm,ts,mov,avi"); put("Type", "Video")
                    put("VideoCodec", video); put("AudioCodec", "aac,mp3,ac3,eac3,opus,flac,vorbis,mp2,dts")
                })
            })
            put("TranscodingProfiles", buildJsonArray {
                add(buildJsonObject {
                    put("Container", "ts"); put("Type", "Video"); put("VideoCodec", "h264"); put("AudioCodec", "aac,ac3,eac3,mp3")
                    put("Protocol", "hls"); put("Context", "Streaming"); put("MaxAudioChannels", "6"); put("MinSegments", 1); put("BreakOnNonKeyFrames", true)
                })
            })
            put("SubtitleProfiles", buildJsonArray {
                for (f in listOf("srt", "subrip", "vtt", "webvtt")) add(buildJsonObject { put("Format", f); put("Method", "External") })
                for (f in listOf("ass", "ssa", "pgssub", "dvdsub")) add(buildJsonObject { put("Format", f); put("Method", "Embed") })
            })
            put("CodecProfiles", JsonArray(emptyList())); put("ContainerProfiles", JsonArray(emptyList()))
        }
    }

    /** Fragt den Server, wie der Titel auf diesem Gerät abgespielt werden kann (Direct Play, sonst Transkodierung). */
    suspend fun playback(id: String, startMs: Long, forceTranscode: Boolean = false): JfPlayback {
        val s = restore() ?: throw JellyfinException("Nicht mit Jellyfin verbunden", 401)
        val body = buildJsonObject {
            put("DeviceProfile", deviceProfile)
            put("StartTimeTicks", startMs * 10_000)
            put("AutoOpenLiveStream", true)
            if (forceTranscode) { put("EnableDirectPlay", false); put("EnableDirectStream", false) }
        }
        val res = authed("POST", "/Items/$id/PlaybackInfo?userId=${s.userId}", body)?.jsonObject ?: throw JellyfinException("Keine Wiedergabe-Infos")
        val src = (res["MediaSources"] as? JsonArray)?.firstOrNull()?.jsonObject ?: throw JellyfinException("Keine abspielbare Quelle")
        val msid = src["Id"]?.str() ?: id
        val psid = res["PlaySessionId"]?.str()
        val container = src["Container"]?.str()?.split(',')?.firstOrNull()?.trim()?.ifBlank { null } ?: "mkv"
        val common = "MediaSourceId=${enc(msid)}&DeviceId=${enc(deviceId)}&api_key=${s.token}" + (psid?.let { "&PlaySessionId=$it" } ?: "")
        val direct = "${s.server}/Videos/$id/stream.$container?static=true&$common"
        val transcode = src["TranscodingUrl"]?.str()?.let { t -> s.server + t + (if ("api_key=" in t) "" else "&api_key=${s.token}") }
        val canDirect = !forceTranscode && (src["SupportsDirectPlay"]?.bool() == true || src["SupportsDirectStream"]?.bool() == true)
        val subs = (src["MediaStreams"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { it["Type"]?.str() == "Subtitle" && (it["DeliveryMethod"]?.str() == "External" || it["IsExternal"]?.bool() == true) }
            .mapNotNull { st ->
                val du = st["DeliveryUrl"]?.str() ?: return@mapNotNull null
                val url = s.server + du + (if ("api_key=" in du) "" else (if ('?' in du) "&" else "?") + "api_key=${s.token}")
                JfSubtitle(url, st["Language"]?.str(), st["DisplayTitle"]?.str() ?: st["Language"]?.str() ?: "Untertitel",
                    if (du.substringBefore('?').endsWith(".vtt")) "vtt" else "srt")
            }
        return when {
            canDirect -> JfPlayback(direct, "DirectPlay", transcode, msid, psid, subs)
            transcode != null -> JfPlayback(transcode, "Transcode", null, msid, psid, subs)
            else -> throw JellyfinException("Dieser Titel kann auf diesem Gerät nicht abgespielt werden")
        }
    }

    private fun progressBody(id: String, pb: JfPlayback, posMs: Long, paused: Boolean, event: String? = null) = buildJsonObject {
        put("ItemId", id); put("MediaSourceId", pb.mediaSourceId); pb.playSessionId?.let { put("PlaySessionId", it) }
        put("PositionTicks", posMs * 10_000); put("IsPaused", paused); put("CanSeek", true); put("PlayMethod", pb.method)
        event?.let { put("EventName", it) }
    }

    suspend fun reportStart(id: String, pb: JfPlayback, posMs: Long) = runCatching { authed("POST", "/Sessions/Playing", progressBody(id, pb, posMs, false)) }
    suspend fun reportProgress(id: String, pb: JfPlayback, posMs: Long, paused: Boolean) =
        runCatching { authed("POST", "/Sessions/Playing/Progress", progressBody(id, pb, posMs, paused, if (paused) "pause" else "timeupdate")) }
    suspend fun reportStopped(id: String, pb: JfPlayback, posMs: Long) {
        runCatching { authed("POST", "/Sessions/Playing/Stopped", progressBody(id, pb, posMs, false)) }
        invalidate()
    }

    // ------------------------------------------------------------------ JSON → Modell

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement.int(): Int? = (this as? JsonPrimitive)?.intOrNull ?: (this as? JsonPrimitive)?.doubleOrNull?.toInt()
    private fun JsonElement.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

    fun parse(el: JsonElement?): JfItem? {
        val o = el as? JsonObject ?: return null
        val id = o["Id"]?.str() ?: return null
        val tags = o["ImageTags"] as? JsonObject
        val backdrops = o["BackdropImageTags"] as? JsonArray
        val ud = o["UserData"] as? JsonObject
        val people = (o["People"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return JfItem(
            id = id,
            name = o["Name"]?.str() ?: "",
            type = o["Type"]?.str() ?: "",
            overview = o["Overview"]?.str(),
            year = o["ProductionYear"]?.int(),
            rating = (o["CommunityRating"] as? JsonPrimitive)?.doubleOrNull,
            officialRating = o["OfficialRating"]?.str(),
            runtimeMs = ((o["RunTimeTicks"] as? JsonPrimitive)?.longOrNull ?: 0L) / 10_000,
            genres = (o["Genres"] as? JsonArray).orEmpty().mapNotNull { it.str() },
            seriesId = o["SeriesId"]?.str(),
            seriesName = o["SeriesName"]?.str(),
            seasonId = o["SeasonId"]?.str(),
            index = o["IndexNumber"]?.int(),
            parentIndex = o["ParentIndexNumber"]?.int(),
            hasPrimary = tags?.containsKey("Primary") == true,
            hasBackdrop = (backdrops?.size ?: 0) > 0,
            parentBackdropId = o["ParentBackdropItemId"]?.str(),
            seriesPrimaryId = if (o["SeriesPrimaryImageTag"] != null) o["SeriesId"]?.str() else null,
            played = ud?.get("Played")?.bool() == true,
            positionMs = ((ud?.get("PlaybackPositionTicks") as? JsonPrimitive)?.longOrNull ?: 0L) / 10_000,
            favorite = ud?.get("IsFavorite")?.bool() == true,
            collectionType = o["CollectionType"]?.str(),
            childCount = o["ChildCount"]?.int(),
            people = people.filter { it["Type"]?.str() == "Actor" }.mapNotNull { it["Name"]?.str() }.take(8),
            directors = people.filter { it["Type"]?.str() == "Director" }.mapNotNull { it["Name"]?.str() },
        )
    }
}
