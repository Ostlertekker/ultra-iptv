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
        // Netzwerkprobleme beim Testserver (Open-Meteo nicht erreichbar) überspringen statt den Build zu blockieren
        val txt = try {
            (URL(WeatherParser.url("52.52437", "13.41053")).openConnection() as HttpURLConnection).run {
                connectTimeout = 15000; readTimeout = 15000
                setRequestProperty("User-Agent", "UltraIPTV-Test"); inputStream.bufferedReader().readText()
            }
        } catch (e: java.io.IOException) { org.junit.Assume.assumeNoException("Open-Meteo nicht erreichbar", e); return }
        val w = try { WeatherParser.parse(Json.parseToJsonElement(txt).jsonObject, "Berlin, DE") }
            catch (e: Exception) { throw AssertionError("Parser-Fehler: $e\nAntwort: ${txt.take(1500)}", e) }
        assertTrue("Temperatur unplausibel: ${w.temp}", w.temp in -40..50)
        assertTrue("Tage fehlen: $w", w.days.isNotEmpty())
    }

    @Test fun airQualityBerlin() {
        val txt = try {
            (URL(WeatherParser.airUrl("52.52437", "13.41053")).openConnection() as HttpURLConnection).run {
                connectTimeout = 15000; readTimeout = 15000
                setRequestProperty("User-Agent", "UltraIPTV-Test"); inputStream.bufferedReader().readText()
            }
        } catch (e: java.io.IOException) { org.junit.Assume.assumeNoException("Open-Meteo nicht erreichbar", e); return }
        val (aqi, uv) = WeatherParser.parseAir(Json.parseToJsonElement(txt).jsonObject)
        assertTrue("Kein Luftqualitätsindex: ${txt.take(800)}", aqi != null && aqi in 0..500)
        assertTrue("Kein UV-Index: ${txt.take(800)}", uv != null && uv in 0.0..20.0)
    }
}
