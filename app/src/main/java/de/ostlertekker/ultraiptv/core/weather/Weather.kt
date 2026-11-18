package de.ostlertekker.ultraiptv.core.weather

import de.ostlertekker.ultraiptv.core.data.Keys
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class WeatherDay(val label: String, val code: Int, val max: Int, val min: Int, val rainPct: Int?)
data class Weather(
    val place: String, val temp: Int, val feels: Int?, val code: Int, val wind: Int?, val humidity: Int?,
    val todayMax: Int?, val todayMin: Int?, val rainPct: Int?, val days: List<WeatherDay>, val fetchedAt: Long,
    /** Europäischer Luftqualitätsindex (0 = sehr gut … >100 = extrem schlecht) und UV-Index – null, wenn nicht abrufbar. */
    val aqi: Int? = null, val uv: Double? = null,
) {
    val aqiText get() = aqi?.let { when { it <= 20 -> "sehr gut"; it <= 40 -> "gut"; it <= 60 -> "mäßig"; it <= 80 -> "schlecht"; it <= 100 -> "sehr schlecht"; else -> "extrem schlecht" } }
    val uvText get() = uv?.let { when { it < 3 -> "niedrig"; it < 6 -> "mittel"; it < 8 -> "hoch"; it < 11 -> "sehr hoch"; else -> "extrem" } }
    val icon get() = WeatherCodes.icon(code)
    val text get() = WeatherCodes.text(code)
}

/** WMO-Wettercodes → Symbol und deutscher Text. */
object WeatherCodes {
    fun icon(c: Int) = when (c) {
        0 -> "☀️"; 1 -> "🌤️"; 2 -> "⛅"; 3 -> "☁️"; 45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"; 61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"; 95, 96, 99 -> "⛈️"; else -> "🌡️"
    }
    fun text(c: Int) = when (c) {
        0 -> "Klar"; 1 -> "Überwiegend klar"; 2 -> "Teilweise bewölkt"; 3 -> "Bedeckt"; 45, 48 -> "Nebel"
        51, 53, 55 -> "Nieselregen"; 56, 57 -> "Gefrierender Niesel"; 61, 63 -> "Regen"; 65 -> "Starker Regen"
        66, 67 -> "Gefrierender Regen"; 71, 73 -> "Schneefall"; 75 -> "Starker Schneefall"; 77 -> "Schneegriesel"
        80, 81 -> "Regenschauer"; 82 -> "Heftige Schauer"; 85, 86 -> "Schneeschauer"; 95 -> "Gewitter"; 96, 99 -> "Gewitter mit Hagel"
        else -> "–"
    }
}

/**
 * Wetter über Open-Meteo (kostenlos, ohne Konto/Schlüssel). Ort wird in den Einstellungen eingetragen
 * und einmalig über die Open-Meteo-Ortssuche in Koordinaten umgewandelt. Aktualisierung alle 20 Minuten.
 */
@Singleton
class WeatherRepository @Inject constructor(private val http: OkHttpClient, private val settings: SettingsRepository) {
    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Letzter Fehler beim Wetterabruf (für Einstellungen › App-Updates › Fehlermeldungen). */
    @Volatile var lastError: String? = null

    init {
        scope.launch {
            settings.settings.map { listOf(it.weatherPlace, it.weatherLat, it.weatherLon, it.weatherEnabled.toString()) }.distinctUntilChanged().collectLatest { (place, lat, lon, on) ->
                // collectLatest: Ortswechsel bricht die laufende Schleife ab und startet neu
                _weather.value = null
                if (on != "true" || place.isBlank() || lat.isBlank() || lon.isBlank()) return@collectLatest
                // Erst laden, dann alle 20 Minuten (collect wird bei Ortswechsel abgebrochen und neu gestartet)
                kotlinx.coroutines.coroutineScope {
                    launch { while (true) {
                        runCatching { fetch(place, lat, lon) }
                            .onSuccess { _weather.value = it; lastError = null }
                            .onFailure { lastError = "${java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMAN).format(java.util.Date())} $place: $it" }
                        delay(if (lastError != null) 60_000L else 20 * 60_000L)
                    } }
                }
            }
        }
    }

    private fun get(url: String): JsonObject = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) error("HTTP ${r.code}")
        Json.parseToJsonElement(r.body!!.string()) as JsonObject
    }

    /** Ort suchen und speichern. Ergebnis: angezeigter Ortsname oder Fehlermeldung. */
    suspend fun setPlace(query: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val q = query.trim()
            if (q.isEmpty()) {
                settings.set(Keys.WEATHER_PLACE, ""); settings.set(Keys.WEATHER_LAT, ""); settings.set(Keys.WEATHER_LON, ""); settings.set(Keys.WEATHER_REGION, "")
                return@runCatching ""
            }
            val o = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=de&format=json&name=" + URLEncoder.encode(q, "UTF-8"))
            val r = (o["results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: error("Ort „$q“ nicht gefunden")
            val name = listOfNotNull((r["name"] as? JsonPrimitive)?.contentOrNull, (r["country_code"] as? JsonPrimitive)?.contentOrNull).joinToString(", ")
            settings.set(Keys.WEATHER_PLACE, name)
            settings.set(Keys.WEATHER_LAT, (r["latitude"] as JsonPrimitive).content)
            settings.set(Keys.WEATHER_LON, (r["longitude"] as JsonPrimitive).content)
            settings.set(Keys.WEATHER_REGION, (r["admin1"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            name
        }
    }

    private fun fetch(place: String, lat: String, lon: String): Weather {
        val w = WeatherParser.parse(get(WeatherParser.url(lat, lon)), place)
        // Luftqualität + UV: eigener Dienst von Open-Meteo; fällt er aus, bleibt das Wetter trotzdem sichtbar
        val aq = runCatching { WeatherParser.parseAir(get(WeatherParser.airUrl(lat, lon))) }.getOrNull()
        return if (aq == null) w else w.copy(aqi = aq.first, uv = aq.second)
    }
}

internal object WeatherParser {
    fun url(lat: String, lon: String) = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timezone=auto&forecast_days=4" +
        "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"

    fun airUrl(lat: String, lon: String) = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&timezone=auto&current=european_aqi,uv_index"

    /** (Luftqualitätsindex, UV-Index) aus der Open-Meteo-Luftqualitäts-Antwort. */
    fun parseAir(o: JsonObject): Pair<Int?, Double?> {
        val cur = o["current"] as? JsonObject ?: return null to null
        val aqi = (cur["european_aqi"] as? JsonPrimitive)?.doubleOrNull?.let { Math.round(it).toInt() }
        val uv = (cur["uv_index"] as? JsonPrimitive)?.doubleOrNull?.let { Math.round(it * 10) / 10.0 }
        return aqi to uv
    }

    fun parse(o: JsonObject, place: String): Weather {
        fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
        fun JsonObject.arr(k: String) = (this[k] as? JsonArray).orEmpty()
        val cur = o["current"] as JsonObject
        val daily = o["daily"] as? JsonObject
        val dates = daily?.arr("time")?.map { (it as JsonPrimitive).content }.orEmpty()
        val inF = SimpleDateFormat("yyyy-MM-dd", Locale.US); val outF = SimpleDateFormat("EE", Locale.GERMAN)
        fun di(k: String, i: Int) = (daily?.arr(k)?.getOrNull(i) as? JsonPrimitive)?.doubleOrNull
        val days = dates.indices.drop(1).mapNotNull { i ->
            WeatherDay(runCatching { outF.format(inF.parse(dates[i])!!).removeSuffix(".") }.getOrDefault(dates[i]),
                (daily?.arr("weather_code")?.getOrNull(i) as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null,
                di("temperature_2m_max", i)?.toInt() ?: return@mapNotNull null, di("temperature_2m_min", i)?.toInt() ?: return@mapNotNull null,
                di("precipitation_probability_max", i)?.toInt())
        }
        return Weather(place.substringBefore(","), cur.d("temperature_2m")?.let { Math.round(it).toInt() } ?: error("keine Temperatur"),
            cur.d("apparent_temperature")?.let { Math.round(it).toInt() }, (cur["weather_code"] as? JsonPrimitive)?.intOrNull ?: 0,
            cur.d("wind_speed_10m")?.let { Math.round(it).toInt() }, cur.d("relative_humidity_2m")?.toInt(),
            di("temperature_2m_max", 0)?.let { Math.round(it).toInt() }, di("temperature_2m_min", 0)?.let { Math.round(it).toInt() },
            di("precipitation_probability_max", 0)?.toInt(), days, System.currentTimeMillis())
    }
}
