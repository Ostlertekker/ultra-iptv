package de.ostlertekker.ultraiptv.core.model

enum class ProviderType { M3U, XTREAM }

enum class ContentKind { LIVE, MOVIE, SERIES, EPISODE }

data class ProviderConfig(
    val type: ProviderType,
    val name: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
) {
    val isValid: Boolean
        get() = when (type) {
            ProviderType.M3U -> m3uUrl.isNotBlank()
            ProviderType.XTREAM -> server.isNotBlank() && username.isNotBlank() && password.isNotBlank()
        }
}

enum class AnimationProfile(val label: String) { ECONOMY("Sparsam"), BALANCED("Ausgewogen"), ULTRA("Ultra") }

enum class QualityMode(val label: String) {
    AUTO("Auto"), BEST_AVAILABLE("Beste verfügbare"), DATA_SAVER("Datensparer"), MANUAL("Manuell")
}

enum class OutroMode(val label: String) { FULL("Ganzen Abspann überspringen"), CREDITS_ONLY("Nur Credits überspringen") }

enum class BufferProfile(val label: String) { STANDARD("Standard"), LARGE("Groß (stabil)"), LOW_LATENCY("Niedrige Latenz") }

enum class DeviceTier(val label: String) {
    ENTRY("Einstieg"), STANDARD("Standard"), PREMIUM("Premium"), HIGH_PERFORMANCE("Hochleistung")
}

/** Kennzeichnet, ob ein angezeigter Wert gemessen, geschätzt oder nicht verfügbar ist. */
enum class Accuracy(val label: String) { MEASURED("GEMESSEN"), ESTIMATED("GESCHÄTZT"), UNAVAILABLE("NICHT VERFÜGBAR") }

data class InfoEntry(val label: String, val value: String, val accuracy: Accuracy)

data class SyncState(
    val running: Boolean = false,
    val message: String = "",
    val lastError: String? = null,
)
