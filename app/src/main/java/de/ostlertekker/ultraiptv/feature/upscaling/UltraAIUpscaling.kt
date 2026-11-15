package de.ostlertekker.ultraiptv.feature.upscaling

import de.ostlertekker.ultraiptv.core.device.DeviceCapabilityManager
import de.ostlertekker.ultraiptv.core.model.DeviceTier
import javax.inject.Inject
import javax.inject.Singleton

data class UpscalingStatus(
    val available: Boolean,
    val headline: String,
    val reasons: List<String>,
)

/**
 * Optionales KI-Upscaling. Ehrliche Bewertung: Diese Version enthält KEIN neuronales
 * Upscaling-Modell und keine LiteRT-Laufzeit. Deshalb meldet das Modul den Status
 * "nicht verfügbar" – normale Wiedergabe ist davon völlig unabhängig.
 *
 * Integrationspunkt für später: ein LiteRT-Modell (z. B. ESRGAN-Variante) in
 * assets/upscaling/ ablegen, [modelBundled] auf true setzen und die GPU-Delegate-
 * Verarbeitung im Video-Pfad (SurfaceTexture → GL-Shader) anbinden. Erst nach
 * Messung von FPS, Temperatur und Speicher auf echter Hardware freischalten.
 */
@Singleton
class UltraAIUpscaling @Inject constructor(private val device: DeviceCapabilityManager) {

    private val modelBundled = false

    fun assess(): UpscalingStatus {
        val reasons = ArrayList<String>()
        if (!modelBundled) reasons += "In dieser Version ist kein KI-Upscaling-Modell (LiteRT) enthalten."
        val gb = device.totalMemBytes / (1024.0 * 1024 * 1024)
        if (gb < 1.9) reasons += "Arbeitsspeicher (%.1f GB) reicht für Echtzeit-KI-Upscaling neben dem Video-Decoder nicht aus.".format(gb)
        if (device.tier == DeviceTier.ENTRY || device.tier == DeviceTier.STANDARD)
            reasons += "Leistungsprofil ${device.tier.label}: Echtzeit-Inferenz pro Videobild ist auf dieser GPU nicht realistisch."
        val (thermal, _) = device.thermalStatus()
        if (thermal.startsWith("Hoch") || thermal.startsWith("Kritisch")) reasons += "Gerät ist thermisch gedrosselt."
        reasons += "Hinweis: Die TV-eigene Skalierung bleibt aktiv – sie ist kein KI-Upscaling und wird hier auch nicht so bezeichnet."
        val available = modelBundled && reasons.size == 1
        return UpscalingStatus(
            available = available,
            headline = if (available) "KI-Upscaling verfügbar" else "KI-Upscaling auf diesem Gerät nicht verfügbar",
            reasons = reasons,
        )
    }
}
