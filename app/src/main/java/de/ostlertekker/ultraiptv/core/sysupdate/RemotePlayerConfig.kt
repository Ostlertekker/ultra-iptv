package de.ostlertekker.ultraiptv.core.sysupdate

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per System-Update aktualisierbare Player-/Streaming-Konfiguration.
 * Nur Werte, die der Player zur Laufzeit wirklich liest – keine Bibliotheken, keine Decoder, kein App-Code.
 * Ohne installierte Fassung gelten die eingebauten Standardwerte (identisch mit dem bisherigen Verhalten).
 */
@Serializable
data class PlayerConfig(
    val version: Int = 0,
    /** User-Agent für Stream-Anfragen (leer = eingebauter). */
    val userAgent: String = "",
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 12_000,
    /** Live-Schnellstart: Start-Vorrat neuer Sender sowie Unter-/Obergrenze des Lernens. */
    val liveStartDefaultMs: Long = 700,
    val liveStartMinMs: Long = 500,
    val liveStartMaxMs: Long = 2_500,
    /** Schonfrist beim ersten Verbindungsaufbau, bevor die Hänger-Erkennung eingreift. */
    val firstConnectGraceMs: Long = 12_000,
) {
    /** Unsinnige Werte abweisen (Kompatibilitätsprüfung vor der Installation). */
    fun validate(): String? = when {
        connectTimeoutMs !in 2_000..60_000 -> "connectTimeoutMs außerhalb 2–60 s"
        readTimeoutMs !in 2_000..120_000 -> "readTimeoutMs außerhalb 2–120 s"
        liveStartMinMs !in 200..5_000 || liveStartMaxMs !in 500..10_000 || liveStartDefaultMs !in liveStartMinMs..liveStartMaxMs -> "Start-Vorrat unplausibel"
        firstConnectGraceMs !in 3_000..60_000 -> "firstConnectGraceMs außerhalb 3–60 s"
        userAgent.length > 300 || userAgent.any { it == '\n' || it == '\r' } -> "User-Agent ungültig"
        else -> null
    }
}

/** Hält die aktive Konfiguration (wird beim App-Start und nach jeder Installation geladen). */
object RemotePlayerConfig {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile var current: PlayerConfig = PlayerConfig()
        private set

    fun file(ctx: Context) = File(ctx.filesDir, "system/player-config.json")
    fun backup(ctx: Context) = File(ctx.filesDir, "system/player-config.json.bak")

    fun parse(text: String): PlayerConfig = json.decodeFromString(PlayerConfig.serializer(), text)

    fun load(ctx: Context) {
        current = runCatching { parse(file(ctx).readText()).takeIf { it.validate() == null } }.getOrNull() ?: PlayerConfig()
    }
}
