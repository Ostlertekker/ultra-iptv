package de.ostlertekker.ultraiptv.core.player

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import de.ostlertekker.ultraiptv.core.model.QualityMode
import kotlin.math.abs
import kotlin.math.roundToInt

data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

data class QualityConstraints(
    val maxWidth: Int,
    val maxHeight: Int,
    val maxBitrate: Int,
    val forceHighest: Boolean,
)

/**
 * Reine Entscheidungslogik der Bildqualität. Arbeitet nur mit gemessenen Eingaben –
 * es wird nichts hochgerechnet oder vorgetäuscht.
 */
object UltraQualityEngine {

    fun constraints(
        mode: QualityMode,
        manualMaxHeight: Int,
        displayMaxHeight: Int,
        decoderMaxHeight: Int,
    ): QualityConstraints {
        val display = if (displayMaxHeight > 0) displayMaxHeight else 1080
        val decoder = if (decoderMaxHeight > 0) decoderMaxHeight else 1080
        val cap = minOf(display, decoder)
        return when (mode) {
            QualityMode.AUTO -> QualityConstraints(widthFor(cap), cap, Int.MAX_VALUE, false)
            QualityMode.BEST_AVAILABLE -> QualityConstraints(widthFor(cap), cap, Int.MAX_VALUE, true)
            QualityMode.DATA_SAVER -> QualityConstraints(1280, minOf(720, cap), 2_500_000, false)
            QualityMode.MANUAL -> {
                val h = minOf(manualMaxHeight, cap)
                QualityConstraints(widthFor(h), h, Int.MAX_VALUE, false)
            }
        }
    }

    fun widthFor(height: Int): Int = when {
        height >= 2160 -> 3840
        height >= 1440 -> 2560
        height >= 1080 -> 1920
        height >= 720 -> 1280
        else -> (height * 16 / 9)
    }

    /**
     * Wählt einen Anzeigemodus mit gleicher Auflösung, dessen Bildwiederholrate ein ganzzahliges
     * Vielfaches der Quell-Bildrate ist (z. B. 25 fps → 50 Hz, 23,976 fps → 23,976/24 Hz).
     * Gibt null zurück, wenn kein passender Modus existiert – dann bleibt alles unverändert.
     */
    fun pickDisplayMode(modes: List<DisplayModeInfo>, current: DisplayModeInfo, sourceFps: Float): DisplayModeInfo? {
        if (sourceFps <= 1f) return null
        val sameRes = modes.filter { it.width == current.width && it.height == current.height }
        val candidates = sameRes.mapNotNull { m ->
            val ratio = m.refreshRate / sourceFps
            val nearest = ratio.roundToInt()
            if (nearest >= 1 && abs(ratio - nearest) < 0.012f) m to nearest else null
        }
        if (candidates.isEmpty()) return null
        // Kleinstes Vielfaches bevorzugen (exakte Kadenz), bei Gleichstand die genauere Rate.
        return candidates.minWithOrNull(compareBy<Pair<DisplayModeInfo, Int>>({ it.second }, { abs(it.first.refreshRate / sourceFps - it.second) }))?.first
    }

    /** 120 FPS nur, wenn Quelle, Decoder und Display-Pfad es tatsächlich melden. */
    fun highFrameRateStatus(sourceFps: Float, decoderSupports: Boolean, modes: List<DisplayModeInfo>): String {
        val displayMax = modes.maxOfOrNull { it.refreshRate } ?: 0f
        return when {
            sourceFps in 1f..100f -> trf("Quelle hat {0} fps – 120 FPS nicht relevant", ("%.2f".format(sourceFps)))
            sourceFps <= 0f -> tr("Quell-Bildrate unbekannt")
            !decoderSupports -> tr("Decoder meldet keine 120-fps-Unterstützung")
            displayMax < 119f -> "Display/HDMI meldet max. ${"%.0f".format(displayMax)} Hz – 120 FPS nicht möglich"
            else -> tr("120 FPS: Quelle, Decoder und Display unterstützen es")
        }
    }

    fun resolutionLabel(width: Int, height: Int): String = when {
        width <= 0 || height <= 0 -> "unbekannt"
        height >= 2100 || width >= 3800 -> trf("{0}×{1} (4K UHD)", width, height)
        height >= 1400 -> trf("{0}×{1} (QHD)", width, height)
        height >= 1060 || width >= 1900 -> trf("{0}×{1} (Full HD)", width, height)
        height >= 700 || width >= 1260 -> "${width}×$height (HD)"
        else -> "${width}×$height (SD)"
    }
}
