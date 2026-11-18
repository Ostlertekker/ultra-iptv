package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.weather.WeatherParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Echter Abruf bei Open-Meteo (Berlin) + Auswertung – prüft, dass Anfrage und Parser zusammenpassen. */
class WeatherLiveTest {
    @Test fun berlin() {
        val txt = (URL(WeatherParser.url("52.52437", "13.41053")).openConnection() as HttpURLConnection).run {
            setRequestProperty("User-Agent", "UltraIPTV-Test"); inputStream.bufferedReader().readText()
        }
        val w = try { WeatherParser.parse(Json.parseToJsonElement(txt).jsonObject, "Berlin, DE") }
            catch (e: Exception) { throw AssertionError("Parser-Fehler: $e\nAntwort: ${txt.take(1500)}", e) }
        assertTrue("Temperatur unplausibel: ${w.temp}", w.temp in -40..50)
        assertTrue("Tage fehlen: $w", w.days.isNotEmpty())
    }
}
