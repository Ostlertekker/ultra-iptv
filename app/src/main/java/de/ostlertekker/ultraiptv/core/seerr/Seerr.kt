package de.ostlertekker.ultraiptv.core.seerr

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Ein Film oder eine Serie aus Seerr (TMDB-Daten) mit Verfügbarkeits-Status. */
data class SeerrItem(
    val tmdbId: Int,
    /** "movie" oder "tv" */
    val mediaType: String,
    val title: String,
    val year: String?,
    val poster: String?,
    val backdrop: String?,
    val overview: String?,
    /** [SeerrStatus] Medien-Status (1 unbekannt … 5 verfügbar). */
    val status: Int,
    /** ID des Titels in Jellyfin (von Seerr gemeldet, sobald verfügbar) – für den direkten Sprung zur Übersicht. */
    val jellyfinId: String? = null,
) {
    val isTv get() = mediaType == "tv"
    val key get() = "$mediaType:$tmdbId"
}

/** Laufender Download aus Radarr/Sonarr (von Seerr weitergereicht). */
data class SeerrDownload(
    val title: String,
    val size: Long,
    val sizeLeft: Long,
    /** Radarr/Sonarr-Status, z. B. „downloading“, „queued“, „paused“, „completed“. */
    val status: String,
    /** Voraussichtliche Fertigstellung (Epoch-ms) oder null. */
    val eta: Long?,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val progress: Float get() = if (size > 0) ((size - sizeLeft).toFloat() / size).coerceIn(0f, 1f) else 0f
    val percent: Int get() = Math.round(progress * 100)
}

data class SeerrRequest(
    val id: Int,
    val status: Int,
    val item: SeerrItem,
    val requestedBy: String? = null,
    /** Zeitpunkt der Anfrage (Epoch-ms). */
    val createdAt: Long? = null,
    val is4k: Boolean = false,
    /** Angefragte Staffeln (nur Serien). */
    val seasons: List<Int> = emptyList(),
    val downloads: List<SeerrDownload> = emptyList(),
) {
    val downloading get() = downloads.isNotEmpty()
    /** Gesamtfortschritt aller laufenden Downloads (größengewichtet). */
    val progress: Float? get() {
        if (downloads.isEmpty()) return null
        val size = downloads.sumOf { it.size }
        return if (size > 0) ((size - downloads.sumOf { it.sizeLeft }).toFloat() / size).coerceIn(0f, 1f) else 0f
    }
}
data class SeerrSeason(val number: Int, val name: String, val status: Int, val episodes: Int = 0)

/** Status-Codes von Seerr (Overseerr/Jellyseerr): Medien-Status und Anfrage-Status. */
object SeerrStatus {
    const val UNKNOWN = 1; const val PENDING = 2; const val PROCESSING = 3; const val PARTIAL = 4; const val AVAILABLE = 5
    const val REQ_PENDING = 1; const val REQ_APPROVED = 2; const val REQ_DECLINED = 3; const val REQ_FAILED = 4; const val REQ_COMPLETED = 5

    /** Radarr/Sonarr-Download-Status auf Deutsch. */
    fun download(s: String): String = when (s.lowercase()) {
        "downloading" -> tr("Lädt"); "queued" -> tr("In Warteschlange"); "paused" -> "Pausiert"
        "completed" -> tr("Fertig – wird importiert"); "delay" -> tr("Verzögert"); "warning" -> "Warnung"; "failed" -> "Fehler"
        else -> s.replaceFirstChar { it.uppercase() }
    }

    /** Restzeit bis [eta] als „ca. 12 Min“ / „ca. 2 Std 5 Min“, null wenn unbekannt oder vorbei. */
    fun remaining(eta: Long?, now: Long = System.currentTimeMillis()): String? {
        val min = ((eta ?: return null) - now) / 60_000L
        if (min < 0) return null
        return when {
            min < 1 -> "unter 1 Min"
            min < 60 -> "ca. $min Min"
            min < 48 * 60 -> "ca. ${min / 60} Std" + (if (min % 60 > 0) " ${min % 60} Min" else "")
            else -> "ca. ${min / (24 * 60)} Tage"
        }
    }

    fun media(s: Int): String? = when (s) {
        PENDING -> "Angefragt"; PROCESSING -> tr("Wird bearbeitet"); PARTIAL -> tr("Teilweise verfügbar"); AVAILABLE -> tr("Verfügbar")
        6 -> "Gesperrt"; else -> null
    }

    /** Text für „Anfragen“: zuerst Ablehnung/Fehler, sonst der Medien-Status. */
    fun request(req: Int, media: Int): String = when {
        req == REQ_DECLINED -> "Abgelehnt"
        req == REQ_FAILED -> "Fehlgeschlagen"
        req == REQ_PENDING -> tr("Wartet auf Freigabe")
        else -> media(media) ?: "Freigegeben"
    }
}

class SeerrException(message: String, val code: Int = 0) : IOException(message)

data class SeerrState(val server: String = "", val user: String? = null) { val loggedIn get() = user != null && server.isNotBlank() }

/** JSON → Modell (ohne Android-Abhängigkeiten, damit testbar). */
object SeerrParser {
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull

    fun img(path: String?, size: String = "w342"): String? = path?.let { "https://image.tmdb.org/t/p/$size$it" }

    /** Ein Suchtreffer / Discover-Eintrag oder eine /movie- bzw. /tv-Detailantwort. */
    fun item(o: JsonObject, typeHint: String? = null): SeerrItem? {
        val type = o["mediaType"].str() ?: typeHint ?: return null
        if (type != "movie" && type != "tv") return null
        val id = o["id"].int() ?: return null
        val title = (o["title"].str() ?: o["name"].str() ?: o["originalTitle"].str() ?: o["originalName"].str()) ?: return null
        val date = o["releaseDate"].str() ?: o["firstAirDate"].str()
        val info = o["mediaInfo"] as? JsonObject
        val status = info?.get("status").int() ?: SeerrStatus.UNKNOWN
        return SeerrItem(id, type, title, date?.take(4), img(o["posterPath"].str()), img(o["backdropPath"].str(), "w780"), o["overview"].str(), status,
            info?.get("jellyfinMediaId").str() ?: info?.get("jellyfinMediaId4k").str())
    }

    fun results(el: JsonElement?): List<SeerrItem> =
        ((el as? JsonObject)?.get("results") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { o -> item(o) } }

    fun seasons(el: JsonElement?): List<SeerrSeason> {
        val o = el as? JsonObject ?: return emptyList()
        val infoSeasons = ((o["mediaInfo"] as? JsonObject)?.get("seasons") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }.associate { (it["seasonNumber"].int() ?: -1) to (it["status"].int() ?: 1) }
        return (o["seasons"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { s ->
            val n = s["seasonNumber"].int() ?: return@mapNotNull null
            if (n == 0) return@mapNotNull null // „Specials“ nicht anbieten
            SeerrSeason(n, s["name"].str() ?: "Staffel $n", infoSeasons[n] ?: SeerrStatus.UNKNOWN, s["episodeCount"].int() ?: 0)
        }
    }

    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

    /** ISO-Zeit von Seerr („2026-11-21T09:30:00.000Z“, UTC) → Epoch-ms. */
    fun isoMillis(s: String?): Long? {
        if (s.isNullOrBlank() || s.length < 19) return null
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(s.take(19))?.time
        }.getOrNull()
    }

    fun downloads(el: JsonElement?): List<SeerrDownload> = (el as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { d ->
        val ep = d["episode"] as? JsonObject
        SeerrDownload(d["title"].str() ?: "", d["size"].long() ?: 0L, d["sizeLeft"].long() ?: 0L, d["status"].str() ?: "",
            isoMillis(d["estimatedCompletionTime"].str()), ep?.get("seasonNumber").int(), ep?.get("episodeNumber").int())
    }

    /** /request-Eintrag (Liste oder /request/{id}). */
    data class RawRequest(
        val id: Int, val status: Int, val tmdbId: Int, val type: String, val mediaStatus: Int, val by: Int?,
        val byName: String? = null, val createdAt: Long? = null, val is4k: Boolean = false,
        val seasons: List<Int> = emptyList(), val downloads: List<SeerrDownload> = emptyList(),
        val jellyfinId: String? = null,
    )

    fun request(r: JsonObject): RawRequest? {
        val media = r["media"] as? JsonObject ?: return null
        val is4k = (r["is4k"] as? JsonPrimitive)?.contentOrNull == "true"
        val by = r["requestedBy"] as? JsonObject
        val type = media["mediaType"].str() ?: r["type"].str() ?: "movie"
        val seasons = (r["seasons"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("seasonNumber").int() }.sorted()
        // Wie Seerr: bei Serien nur Downloads der angefragten Staffeln
        val dl = downloads(media[if (is4k) "downloadStatus4k" else "downloadStatus"])
            .filter { type != "tv" || seasons.isEmpty() || it.season == null || it.season in seasons }
        return RawRequest(r["id"].int() ?: return null, r["status"].int() ?: 1, media["tmdbId"].int() ?: return null, type,
            media[if (is4k) "status4k" else "status"].int() ?: 1, by?.get("id").int(),
            by?.get("displayName").str() ?: by?.get("jellyfinUsername").str() ?: by?.get("username").str(),
            isoMillis(r["createdAt"].str()), is4k, seasons, dl,
            media[if (is4k) "jellyfinMediaId4k" else "jellyfinMediaId"].str() ?: media["jellyfinMediaId"].str())
    }

    fun requests(el: JsonElement?): List<RawRequest> =
        ((el as? JsonObject)?.get("results") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::request) }

    fun toRequest(r: RawRequest, item: SeerrItem) =
        SeerrRequest(r.id, r.status, item.copy(status = r.mediaStatus, jellyfinId = r.jellyfinId ?: item.jellyfinId), r.byName, r.createdAt, r.is4k, r.seasons, r.downloads)

    fun searchUrl(server: String, q: String, lang: String) =
        "$server/api/v1/search?query=" + URLEncoder.encode(q.trim(), "UTF-8").replace("+", "%20") + "&page=1&language=$lang"
}

/**
 * Optionale Seerr-Anbindung (auch Jellyseerr/Overseerr): Anmeldung mit dem Jellyfin-Konto, Suche, Anfragen,
 * eigene Anfragen, „Entdecken“ und Hinweis, sobald eine Anfrage verfügbar ist. Sitzung verschlüsselt gespeichert.
 */
@Singleton
class SeerrRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    baseClient: OkHttpClient,
    private val settingsRepo: SettingsRepository,
) {
    private val http = baseClient.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val lang get() = Locale.getDefault().language.ifBlank { "de" }

    private val prefs: SharedPreferences by lazy {
        try {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(context, "ultra_seerr", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        } catch (e: Exception) {
            context.getSharedPreferences("ultra_seerr_fallback", Context.MODE_PRIVATE)
        }
    }

    private val _state = MutableStateFlow(SeerrState())
    val state: StateFlow<SeerrState> = _state.asStateFlow()

    /** Hinweis „Deine Anfrage ist verfügbar“ (Einblendung oben links). */
    val availablePopup = MutableStateFlow<SeerrItem?>(null)
    @Volatile var lastError: String? = null

    init {
        _state.value = SeerrState(prefs.getString("server", "") ?: "", prefs.getString("user", null))
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            delay(20_000)
            while (true) {
                runCatching { checkAvailable() }
                delay(5 * 60_000L)
            }
        }
    }

    fun savedServer(): String = prefs.getString("server", "") ?: ""

    fun normalize(input: String): String {
        var s = input.trim().trimEnd('/')
        if (s.isBlank()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        // IP-Adresse ohne Port (Heimnetz): Seerr-Standardport 5055 ergänzen; Domains bleiben unverändert (Reverse-Proxy)
        val hostPart = s.substringAfter("://").substringBefore('/')
        val isIp = hostPart.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || hostPart == "localhost"
        if (isIp && s.startsWith("http://")) s = s.replaceFirst(hostPart, "$hostPart:5055")
        return s
    }

    private suspend fun call(method: String, path: String, body: JsonElement? = null, server: String = _state.value.server,
                             cookie: String? = prefs.getString("cookie", null)): Pair<JsonElement?, List<String>> = withContext(Dispatchers.IO) {
        if (server.isBlank()) throw SeerrException(tr("Kein Seerr-Server eingetragen"))
        val req = Request.Builder().url(if (path.startsWith("http")) path else server + path)
            .header("Accept", "application/json")
            .apply { cookie?.let { header("Cookie", it) } }
            .method(method, body?.let { json.encodeToString(JsonElement.serializer(), it).toRequestBody(jsonType) }
                ?: if (method == "POST") "".toRequestBody(jsonType) else null)
            .build()
        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code == 401 || r.code == 403) {
                    if (path.startsWith("/api/v1/auth/jellyfin")) throw SeerrException(tr("Benutzername oder Passwort falsch (oder kein Zugriff auf Seerr)"), r.code)
                    clearSession(); throw SeerrException(tr("Seerr-Anmeldung abgelaufen – bitte neu anmelden"), r.code)
                }
                if (!r.isSuccessful) {
                    val msg = runCatching { (json.parseToJsonElement(text) as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull } }.getOrNull()
                    throw SeerrException(msg ?: trf("Seerr antwortet mit Fehler {0}", r.code), r.code)
                }
                (if (text.isBlank()) null else json.parseToJsonElement(text)) to r.headers("Set-Cookie")
            }
        } catch (e: SeerrException) { throw e } catch (e: Exception) {
            throw SeerrException(trf("Seerr nicht erreichbar ({0})", (e.message ?: e.javaClass.simpleName)))
        }
    }

    private suspend fun get(path: String) = call("GET", path).first

    /** Anmeldung mit dem Jellyfin-Konto (Seerr prüft die Daten bei deinem Jellyfin-Server). */
    suspend fun login(serverInput: String, user: String, password: String): String {
        val server = normalize(serverInput)
        val (el, cookies) = call("POST", "/api/v1/auth/jellyfin", buildJsonObject { put("username", user.trim()); put("password", password) },
            server = server, cookie = null)
        val sid = cookies.map { it.substringBefore(';').trim() }.filter { it.contains('=') }.joinToString("; ")
        if (sid.isBlank()) throw SeerrException(tr("Seerr hat keine Sitzung geliefert"))
        val o = el as? JsonObject
        val name = (o?.get("displayName") as? JsonPrimitive)?.contentOrNull ?: (o?.get("jellyfinUsername") as? JsonPrimitive)?.contentOrNull ?: user.trim()
        val uid = (o?.get("id") as? JsonPrimitive)?.intOrNull ?: -1
        prefs.edit().putString("server", server).putString("cookie", sid).putString("user", name).putInt("uid", uid).apply()
        _state.value = SeerrState(server, name)
        lastError = null
        // Bereits verfügbare Anfragen nicht nachträglich melden
        runCatching { markCurrentAvailableAsSeen() }
        return name
    }

    fun logout() {
        prefs.edit().remove("cookie").remove("user").remove("uid").remove("notified").apply()
        _state.value = SeerrState(prefs.getString("server", "") ?: "", null)
    }

    private fun clearSession() {
        prefs.edit().remove("cookie").remove("user").apply()
        _state.value = SeerrState(prefs.getString("server", "") ?: "", null)
    }

    /** Verbindungstest: Version + angemeldeter Benutzer. */
    suspend fun test(): String {
        val status = get("/api/v1/status") as? JsonObject
        val me = get("/api/v1/auth/me") as? JsonObject
        val v = (status?.get("version") as? JsonPrimitive)?.contentOrNull ?: "?"
        val name = (me?.get("displayName") as? JsonPrimitive)?.contentOrNull ?: _state.value.user ?: "?"
        return trf("Verbunden mit Seerr {0} als {1}", v, name)
    }

    private suspend fun ready() = _state.value.loggedIn && settingsRepo.current().seerrEnabled

    suspend fun search(q: String): List<SeerrItem> {
        if (!ready() || q.trim().length < 2) return emptyList()
        return SeerrParser.results(call("GET", SeerrParser.searchUrl(_state.value.server, q, lang)).first)
    }

    suspend fun discover(): List<SeerrItem> {
        if (!ready()) return emptyList()
        return SeerrParser.results(get("/api/v1/discover/trending?page=1&language=$lang"))
    }

    suspend fun details(type: String, tmdbId: Int): SeerrItem? =
        (get("/api/v1/${if (type == "tv") "tv" else "movie"}/$tmdbId?language=$lang") as? JsonObject)?.let { SeerrParser.item(it, type) }

    suspend fun seasons(tmdbId: Int): List<SeerrSeason> = SeerrParser.seasons(get("/api/v1/tv/$tmdbId?language=$lang"))

    /** Anfrage stellen. Serien: [seasons] = null → alle Staffeln. */
    suspend fun request(item: SeerrItem, seasons: List<Int>? = null) {
        call("POST", "/api/v1/request", buildJsonObject {
            put("mediaType", item.mediaType); put("mediaId", item.tmdbId)
            if (item.isTv) {
                if (seasons.isNullOrEmpty()) put("seasons", "all")
                else put("seasons", buildJsonArray { seasons.forEach { add(JsonPrimitive(it)) } })
            }
        })
    }

    /**
     * Anfragen (neueste zuerst) mit Titel und Poster. [all] = alle Anfragen auf dem Server – Seerr liefert die nur,
     * wenn das Konto Anfragen ansehen/verwalten darf, sonst automatisch nur die eigenen.
     */
    suspend fun myRequests(all: Boolean = false): List<SeerrRequest> = coroutineScope {
        if (!ready()) return@coroutineScope emptyList()
        val uid = if (all) -1 else prefs.getInt("uid", -1)
        val raw = SeerrParser.requests(get("/api/v1/request?take=40&skip=0&sort=added&filter=all" + (if (uid > 0) "&requestedBy=$uid" else "")))
            .filter { uid <= 0 || it.by == null || it.by == uid }
            .distinctBy { it.type + it.tmdbId }
        raw.map { r ->
            async { (detailCache[r.type + r.tmdbId] ?: runCatching { details(r.type, r.tmdbId) }.getOrNull()
                ?.also { detailCache[r.type + r.tmdbId] = it })?.let { SeerrParser.toRequest(r, it) } }
        }.awaitAll().filterNotNull()
    }

    /** Titel/Poster ändern sich nicht – nicht bei jeder Aktualisierung neu laden. */
    private val detailCache = java.util.concurrent.ConcurrentHashMap<String, SeerrItem>()

    /** Eine Anfrage neu laden (Status + Download-Fortschritt). */
    suspend fun refresh(r: SeerrRequest): SeerrRequest {
        val raw = (get("/api/v1/request/${r.id}") as? JsonObject)?.let { SeerrParser.request(it) } ?: return r
        return SeerrParser.toRequest(raw, r.item)
    }

    private fun notified(): MutableSet<String> = (prefs.getStringSet("notified", emptySet()) ?: emptySet()).toMutableSet()

    private suspend fun markCurrentAvailableAsSeen() {
        val done = notified()
        SeerrParser.requests(get("/api/v1/request?take=50&skip=0&sort=added&filter=all"))
            .filter { it.mediaStatus == SeerrStatus.AVAILABLE }.forEach { done += "${it.type}:${it.tmdbId}" }
        prefs.edit().putStringSet("notified", done).apply()
    }

    /** Hinweis, sobald eine eigene Anfrage verfügbar geworden ist (jede nur einmal). */
    suspend fun checkAvailable() {
        if (!ready() || !settingsRepo.current().seerrNotify) return
        val done = notified()
        val fresh = myRequests().firstOrNull { it.item.status == SeerrStatus.AVAILABLE && it.item.key !in done } ?: return
        done += fresh.item.key
        prefs.edit().putStringSet("notified", done).apply()
        availablePopup.value = fresh.item
    }
}
