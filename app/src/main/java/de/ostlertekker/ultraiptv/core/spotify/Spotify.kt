package de.ostlertekker.ultraiptv.core.spotify

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

// =====================================================================================================
// Modelle
// =====================================================================================================

/** Ein Spotify-Eintrag für die Oberfläche: Titel, Album, Playlist oder Künstler. */
data class SpItem(
    val id: String,
    val uri: String,
    val type: String,          // track, album, playlist, artist
    val name: String,
    val subtitle: String,
    val image: String?,
    val durationMs: Long = 0,
    val contextUri: String? = null,
)

data class SpDevice(val id: String, val name: String, val type: String, val active: Boolean, val volume: Int?, val restricted: Boolean)

data class SpPlayback(
    val playing: Boolean,
    val track: SpItem?,
    val progressMs: Long,
    val durationMs: Long,
    val shuffle: Boolean,
    val repeat: String,
    val device: SpDevice?,
    val at: Long = System.currentTimeMillis(),
)

class SpotifyException(message: String, val code: Int = 0) : IOException(message)

// =====================================================================================================
// Repository: PKCE-Anmeldung, Token sicher speichern/erneuern, Web API, Spotify-Connect-Steuerung
// =====================================================================================================

@Singleton
class SpotifyRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    baseClient: OkHttpClient,
) {
    private val http = baseClient.newBuilder().build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val refreshLock = Mutex()

    /** Für Tests umstellbar. */
    var apiBase = "https://api.spotify.com/v1"
    var accountsBase = "https://accounts.spotify.com"

    companion object {
        /** Muss im Spotify-Entwicklerportal genau so als Redirect-URI eingetragen werden. */
        const val REDIRECT_URI = "http://127.0.0.1:8765/callback"
        val SCOPES = listOf(
            "user-read-playback-state", "user-modify-playback-state", "user-read-currently-playing",
            "playlist-read-private", "playlist-read-collaborative", "user-library-read",
            "user-read-recently-played", "user-top-read", "user-read-private",
        )
    }

    private val prefs: SharedPreferences by lazy {
        try {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(context, "ultra_spotify", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        } catch (e: Exception) {
            context.getSharedPreferences("ultra_spotify_fallback", Context.MODE_PRIVATE)
        }
    }

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _userName = MutableStateFlow<String?>(null)
    val userName: StateFlow<String?> = _userName.asStateFlow()

    init { _connected.value = prefs.getString("refresh_token", null) != null }

    var clientId: String
        get() = prefs.getString("client_id", "") ?: ""
        set(v) { prefs.edit().putString("client_id", v.trim()).apply() }

    // ------------------------------------------------------------------ PKCE-Anmeldung

    private fun b64url(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** Erzeugt die Anmelde-Adresse (offizielle Spotify-Anmeldeseite) und merkt sich den PKCE-Verifier. */
    fun authorizeUrl(): String {
        val verifier = b64url(ByteArray(64).also { SecureRandom().nextBytes(it) })
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = b64url(ByteArray(16).also { SecureRandom().nextBytes(it) })
        prefs.edit().putString("pkce_verifier", verifier).putString("pkce_state", state).apply()
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        return "$accountsBase/authorize?client_id=${e(clientId)}&response_type=code&redirect_uri=${e(REDIRECT_URI)}" +
            "&code_challenge_method=S256&code_challenge=$challenge&state=$state&scope=${e(SCOPES.joinToString(" "))}&show_dialog=false"
    }

    /** Wertet die Rückleitung nach der Anmeldung aus und holt die Tokens. */
    suspend fun finishLogin(callbackUrl: String) {
        val uri = android.net.Uri.parse(callbackUrl)
        uri.getQueryParameter("error")?.let { throw SpotifyException(if (it == "access_denied") "Anmeldung abgebrochen" else "Spotify: $it") }
        val code = uri.getQueryParameter("code") ?: throw SpotifyException("Keine Anmeldebestätigung erhalten")
        if (uri.getQueryParameter("state") != prefs.getString("pkce_state", null)) throw SpotifyException("Anmeldung ungültig (state) – bitte erneut versuchen")
        val form = FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("redirect_uri", REDIRECT_URI)
            .add("client_id", clientId).add("code_verifier", prefs.getString("pkce_verifier", "") ?: "").build()
        val res = tokenCall(form)
        saveTokens(res)
        prefs.edit().remove("pkce_verifier").remove("pkce_state").apply()
        _connected.value = true
        runCatching { me() }
    }

    private suspend fun tokenCall(form: FormBody): JsonObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$accountsBase/api/token").post(form).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val err = runCatching { json.parseToJsonElement(text).jsonObject["error_description"]?.str() }.getOrNull()
                throw SpotifyException(when {
                    r.code == 400 && err?.contains("redirect", true) == true -> "Redirect-URI stimmt nicht: im Spotify-Entwicklerportal $REDIRECT_URI eintragen"
                    r.code == 400 && err?.contains("client", true) == true -> "Client-ID ungültig – bitte im Spotify-Entwicklerportal prüfen"
                    r.code == 400 -> "Anmeldung abgelaufen – bitte erneut anmelden"
                    else -> "Spotify-Anmeldung fehlgeschlagen (${r.code})"
                }, r.code)
            }
            json.parseToJsonElement(text).jsonObject
        }
    }

    private fun saveTokens(o: JsonObject) {
        val access = o["access_token"]?.str() ?: throw SpotifyException("Kein Zugriffstoken erhalten")
        val expires = (o["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600
        prefs.edit().putString("access_token", access).putLong("expires_at", System.currentTimeMillis() + (expires - 60) * 1000)
            .apply { o["refresh_token"]?.str()?.let { putString("refresh_token", it) } }.apply()
    }

    /** Nur für automatische Tests: Tokens direkt setzen. */
    fun setTokensForTest(access: String, refresh: String) {
        prefs.edit().putString("access_token", access).putString("refresh_token", refresh)
            .putLong("expires_at", System.currentTimeMillis() + 3_600_000).apply()
        _connected.value = true
    }

    fun logout() {
        prefs.edit().remove("access_token").remove("refresh_token").remove("expires_at").apply()
        _connected.value = false; _userName.value = null
    }

    private suspend fun token(forceRefresh: Boolean = false): String = refreshLock.withLock {
        val access = prefs.getString("access_token", null)
        if (!forceRefresh && access != null && System.currentTimeMillis() < prefs.getLong("expires_at", 0)) return access
        val refresh = prefs.getString("refresh_token", null) ?: throw SpotifyException("Nicht mit Spotify verbunden", 401)
        val res = try {
            tokenCall(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh).add("client_id", clientId).build())
        } catch (e: SpotifyException) {
            if (e.code == 400) { logout(); throw SpotifyException("Spotify-Anmeldung abgelaufen – bitte neu anmelden", 401) }
            throw e
        }
        saveTokens(res)
        prefs.getString("access_token", null)!!
    }

    // ------------------------------------------------------------------ Web API

    private suspend fun api(method: String, path: String, body: JsonElement? = null, retry: Boolean = true): JsonElement? {
        val tok = token()
        val result = withContext(Dispatchers.IO) {
            val rb = body?.toString()?.toRequestBody(jsonType) ?: if (method == "GET") null else "".toRequestBody(jsonType)
            val url = if (path.startsWith("http")) path else apiBase + path
            http.newCall(Request.Builder().url(url).method(method, rb).header("Authorization", "Bearer $tok").build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                when {
                    r.code == 401 -> 401 to null
                    r.code == 404 && path.startsWith("/me/player") -> throw SpotifyException("Kein aktives Spotify-Gerät – bitte ein Gerät wählen", 404)
                    r.code == 403 && path.startsWith("/me/player") -> throw SpotifyException("Spotify Premium wird für die Steuerung benötigt", 403)
                    r.code == 403 -> throw SpotifyException("Spotify verweigert den Zugriff – ist dein Konto im Entwicklerportal unter „User Management“ eingetragen?", 403)
                    r.code == 429 -> throw SpotifyException("Zu viele Anfragen – kurz warten", 429)
                    !r.isSuccessful -> throw SpotifyException("Spotify-Fehler ${r.code}", r.code)
                    text.isBlank() -> r.code to null
                    else -> r.code to runCatching { json.parseToJsonElement(text) }.getOrNull()
                }
            }
        }
        if (result.first == 401) {
            if (!retry) throw SpotifyException("Spotify-Anmeldung abgelaufen", 401)
            token(forceRefresh = true)
            return api(method, path, body, retry = false)
        }
        return result.second
    }

    suspend fun me(): String {
        val o = api("GET", "/me")?.jsonObject
        val name = o?.get("display_name")?.str() ?: o?.get("id")?.str() ?: "Spotify"
        _userName.value = name
        return name
    }

    private fun img(o: JsonObject?): String? = (o?.get("images") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.get("url")?.str() }
    private fun artists(o: JsonObject): String = (o["artists"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("name")?.str() }.joinToString(", ")

    fun parse(el: JsonElement?): SpItem? {
        val o = el as? JsonObject ?: return null
        val type = o["type"]?.str() ?: return null
        val id = o["id"]?.str() ?: return null
        val uri = o["uri"]?.str() ?: "spotify:$type:$id"
        return when (type) {
            "track" -> SpItem(id, uri, type, o["name"]?.str() ?: "", artists(o), img(o["album"] as? JsonObject),
                (o["duration_ms"] as? JsonPrimitive)?.longOrNull ?: 0, (o["album"] as? JsonObject)?.get("uri")?.str())
            "album" -> SpItem(id, uri, type, o["name"]?.str() ?: "", artists(o), img(o))
            "playlist" -> SpItem(id, uri, type, o["name"]?.str() ?: "",
                ((o["owner"] as? JsonObject)?.get("display_name")?.str() ?: "Playlist") +
                    ((o["tracks"] as? JsonObject)?.get("total")?.let { " · ${(it as? JsonPrimitive)?.intOrNull ?: 0} Titel" } ?: ""), img(o))
            "artist" -> SpItem(id, uri, type, o["name"]?.str() ?: "", "Künstler", img(o))
            else -> null
        }
    }

    private fun items(el: JsonElement?, key: String = "items", inner: String? = null): List<SpItem> =
        ((el as? JsonObject)?.get(key) as? JsonArray).orEmpty().mapNotNull { e -> parse(if (inner != null) (e as? JsonObject)?.get(inner) else e) }

    suspend fun playlists() = items(api("GET", "/me/playlists?limit=50"))
    suspend fun likedTracks() = items(api("GET", "/me/tracks?limit=50"), inner = "track")
    suspend fun savedAlbums() = items(api("GET", "/me/albums?limit=40"), inner = "album")
    suspend fun recentlyPlayed() = items(api("GET", "/me/player/recently-played?limit=40"), inner = "track").distinctBy { it.id }
    suspend fun topTracks() = items(api("GET", "/me/top/tracks?limit=40&time_range=short_term"))
    suspend fun topArtists() = items(api("GET", "/me/top/artists?limit=30&time_range=medium_term"))

    suspend fun search(q: String): List<Pair<String, List<SpItem>>> {
        val o = api("GET", "/search?type=track,artist,album,playlist&limit=20&q=" + URLEncoder.encode(q, "UTF-8")) as? JsonObject ?: return emptyList()
        fun sec(key: String) = ((o[key] as? JsonObject)?.get("items") as? JsonArray).orEmpty().mapNotNull { parse(it) }
        return listOf("Titel" to sec("tracks"), "Künstler" to sec("artists"), "Alben" to sec("albums"), "Playlists" to sec("playlists")).filter { it.second.isNotEmpty() }
    }

    /** Titel einer Playlist/eines Albums bzw. Top-Titel eines Künstlers. */
    suspend fun tracksOf(item: SpItem): List<SpItem> = when (item.type) {
        "playlist" -> items(api("GET", "/playlists/${item.id}/tracks?limit=100"), inner = "track")
        "album" -> {
            val a = api("GET", "/albums/${item.id}")?.jsonObject
            val cover = img(a)
            ((a?.get("tracks") as? JsonObject)?.get("items") as? JsonArray).orEmpty().mapNotNull { parse(it)?.copy(image = cover, contextUri = item.uri) }
        }
        "artist" -> items(api("GET", "/artists/${item.id}/top-tracks?market=from_token"), key = "tracks")
        else -> emptyList()
    }

    // ------------------------------------------------------------------ Spotify Connect

    suspend fun devices(): List<SpDevice> = ((api("GET", "/me/player/devices") as? JsonObject)?.get("devices") as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }.map { d ->
            SpDevice(d["id"]?.str() ?: "", d["name"]?.str() ?: "Gerät", d["type"]?.str() ?: "", d["is_active"]?.bool() == true,
                d["volume_percent"]?.let { (it as? JsonPrimitive)?.intOrNull }, d["is_restricted"]?.bool() == true)
        }.filter { it.id.isNotBlank() }

    suspend fun playback(): SpPlayback? {
        val o = api("GET", "/me/player?additional_types=track") as? JsonObject ?: return null
        val item = parse(o["item"])
        val dev = (o["device"] as? JsonObject)?.let { d ->
            SpDevice(d["id"]?.str() ?: "", d["name"]?.str() ?: "", d["type"]?.str() ?: "", true,
                d["volume_percent"]?.let { (it as? JsonPrimitive)?.intOrNull }, d["is_restricted"]?.bool() == true)
        }
        return SpPlayback(o["is_playing"]?.bool() == true, item, (o["progress_ms"] as? JsonPrimitive)?.longOrNull ?: 0, item?.durationMs ?: 0,
            o["shuffle_state"]?.bool() == true, o["repeat_state"]?.str() ?: "off", dev)
    }

    private fun dev(deviceId: String?) = deviceId?.let { "?device_id=${URLEncoder.encode(it, "UTF-8")}" } ?: ""

    /** Abspielen: Kontext (Playlist/Album/Künstler) ab einem Titel oder einzelne Titel. */
    suspend fun play(item: SpItem, context: SpItem? = null, deviceId: String? = null) {
        val body = buildJsonObject {
            when {
                context != null && context.type != "artist" -> { put("context_uri", context.uri); if (item.type == "track") put("offset", buildJsonObject { put("uri", item.uri) }) }
                item.type == "track" -> put("uris", buildJsonArray { add(JsonPrimitive(item.uri)) })
                item.type == "artist" -> put("context_uri", item.uri)
                else -> put("context_uri", item.uri)
            }
        }
        api("PUT", "/me/player/play" + dev(deviceId), body)
    }

    suspend fun playUris(uris: List<String>, startIndex: Int, deviceId: String? = null) {
        api("PUT", "/me/player/play" + dev(deviceId), buildJsonObject {
            put("uris", buildJsonArray { uris.take(100).forEach { add(JsonPrimitive(it)) } })
            put("offset", buildJsonObject { put("position", startIndex.coerceAtMost(99)) })
        })
    }

    suspend fun resume() = api("PUT", "/me/player/play")
    suspend fun pause() = api("PUT", "/me/player/pause")
    suspend fun next() = api("POST", "/me/player/next")
    suspend fun previous() = api("POST", "/me/player/previous")
    suspend fun seek(ms: Long) = api("PUT", "/me/player/seek?position_ms=${ms.coerceAtLeast(0)}")
    suspend fun shuffle(on: Boolean) = api("PUT", "/me/player/shuffle?state=$on")
    suspend fun repeat(mode: String) = api("PUT", "/me/player/repeat?state=$mode")
    suspend fun volume(pct: Int) = api("PUT", "/me/player/volume?volume_percent=${pct.coerceIn(0, 100)}")
    suspend fun transfer(deviceId: String, play: Boolean = true) = api("PUT", "/me/player", buildJsonObject {
        put("device_ids", buildJsonArray { add(JsonPrimitive(deviceId)) }); put("play", play)
    })

    // ------------------------------------------------------------------ JSON-Helfer

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
}
