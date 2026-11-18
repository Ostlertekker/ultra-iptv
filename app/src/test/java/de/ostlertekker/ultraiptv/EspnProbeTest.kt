package de.ostlertekker.ultraiptv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.fail
import org.junit.Test
import java.net.URL

/** Temporär: zeigt die Struktur der ESPN-Spieldaten (schlägt absichtlich fehl, damit die Ausgabe im CI-Log landet). */
class EspnProbeTest {
    private fun get(u: String) = Json.parseToJsonElement((URL(u).openConnection() as java.net.HttpURLConnection).run {
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) UltraIPTV"); setRequestProperty("Accept", "application/json")
        inputStream.bufferedReader().readText() }).jsonObject
    private fun cut(e: Any?, n: Int = 900) = e.toString().take(n)

    @Test fun probe() {
        val sb = StringBuilder()
        val base = "https://site.api.espn.com/apis/site/v2/sports/"
        for ((lg, range) in listOf("soccer/eng.1" to "20261101-20261110", "basketball/nba" to "20261110-20261112")) {
            runCatching {
                val board = get("${base}$lg/scoreboard?dates=$range")
                val ev = board["events"]!!.jsonArray.first { it.jsonObject["status"]?.jsonObject?.get("type")?.jsonObject?.get("state")?.jsonPrimitive?.content == "post" }.jsonObject
                val id = ev["id"]!!.jsonPrimitive.content
                val s = get("${base}$lg/summary?event=$id")
                sb.append("\n==== $lg $id KEYS: ${s.keys}\n")
                for (k in s.keys) {
                    val v = s[k]
                    val first = (v as? JsonArray)?.firstOrNull() ?: v
                    sb.append("-- $k (${(v as? JsonArray)?.size ?: "obj"}): ${cut(first, if (k in setOf("header", "boxscore", "rosters", "gameInfo", "standings", "headToHeadGames", "commentary", "keyEvents", "lastFiveGames")) 1500 else 200)}\n")
                }
                (s["boxscore"] as? JsonObject)?.let { b -> sb.append("-- boxscore keys ${b.keys}; form: ${cut(b["form"], 700)}; players: ${cut(b["players"], 1500)}\n") }
                (s["header"] as? JsonObject)?.get("competitions")?.jsonArray?.firstOrNull()?.jsonObject?.let { c ->
                    sb.append("-- header.comp keys ${c.keys}; details: ${cut(c["details"], 1200)}; competitors[0]: ${cut(c["competitors"]?.jsonArray?.firstOrNull(), 1200)}\n") }
                (s["rosters"] as? JsonArray)?.firstOrNull()?.jsonObject?.let { r -> sb.append("-- roster keys ${r.keys}; p0: ${cut(r["roster"]?.jsonArray?.firstOrNull(), 1200)}\n") }
            }.onFailure { sb.append("\n==== $lg FEHLER $it\n") }
        }
        fail(sb.toString())
    }
}
