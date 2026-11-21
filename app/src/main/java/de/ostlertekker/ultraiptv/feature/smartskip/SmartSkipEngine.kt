package de.ostlertekker.ultraiptv.feature.smartskip

import de.ostlertekker.ultraiptv.core.data.AppSettings
import de.ostlertekker.ultraiptv.core.model.OutroMode

enum class SegmentType(val label: String) { INTRO("Intro"), RECAP("Rückblick"), OUTRO("Abspann"), CREDITS("Credits") }

enum class MarkerSource(val label: String) {
    PROVIDER("Anbieter"), USER("Eigene Markierung"), VERIFIED("Bestätigt"), TEMPLATE("Staffel-Vorschlag")
}

data class SkipSegment(
    val type: SegmentType,
    val startMs: Long,
    val endMs: Long,
    val source: MarkerSource,
    val confidence: Float,
) {
    val key: String get() = "${type.name}@$startMs"
}

data class SkipPolicy(
    val enabled: Boolean = true,
    val autoIntro: Boolean = true,
    val autoRecap: Boolean = true,
    val autoOutro: Boolean = false,
    val outroMode: OutroMode = OutroMode.FULL,
    val manualOnly: Boolean = false,
    val autoNext: Boolean = true,
    val countdownSec: Int = 10,
    val threshold: Float = 0.8f,
)

sealed interface SkipAction {
    data object None : SkipAction
    data class Offer(val segment: SkipSegment, val targetMs: Long) : SkipAction
    data class AutoSkip(val segment: SkipSegment, val targetMs: Long) : SkipAction
    data object NextEpisode : SkipAction
}

/**
 * Entscheidet, ob Intro/Rückblick/Abspann übersprungen werden. Keine schwere Analyse auf dem
 * Gerät: es werden ausschließlich Anbieter-Zeitmarken, eigene/ bestätigte Markierungen und
 * Staffel-Vorschläge verwendet. Automatisch gesprungen wird nur bei ausreichender Sicherheit.
 */
class SmartSkipEngine {
    private val handled = HashSet<String>()

    fun reset() = handled.clear()

    fun markHandled(segment: SkipSegment) { handled += segment.key }

    fun evaluate(segments: List<SkipSegment>, positionMs: Long, durationMs: Long, policy: SkipPolicy, hasNext: Boolean): SkipAction {
        if (durationMs <= 0) return SkipAction.None
        val valid = if (policy.enabled) normalize(segments, durationMs) else emptyList()

        // Nächste Folge: ab Abspann-Beginn bzw. kurz vor Ende
        if (hasNext && policy.autoNext) {
            val outro = valid.filter { it.type == SegmentType.OUTRO || it.type == SegmentType.CREDITS }
                .filter { it.endMs >= durationMs - END_TOLERANCE_MS }
                .minByOrNull { it.startMs }
            val trigger = outro?.startMs ?: (durationMs - maxOf(policy.countdownSec * 1000L, 15_000L))
            if (positionMs >= trigger && positionMs < durationMs) return SkipAction.NextEpisode
        }

        val active = valid.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - 1_000 } ?: return SkipAction.None
        if (active.type == SegmentType.OUTRO && policy.outroMode == OutroMode.CREDITS_ONLY) {
            // Nur Credits automatisch – der komplette Abspann wird lediglich angeboten.
            return SkipAction.Offer(active, active.endMs)
        }
        val atEnd = active.endMs >= durationMs - END_TOLERANCE_MS
        if (atEnd && hasNext && (active.type == SegmentType.OUTRO || active.type == SegmentType.CREDITS)) {
            return if (policy.autoNext) SkipAction.NextEpisode else SkipAction.Offer(active, durationMs)
        }
        val wantsAuto = !policy.manualOnly && active.confidence >= policy.threshold && when (active.type) {
            SegmentType.INTRO -> policy.autoIntro
            SegmentType.RECAP -> policy.autoRecap
            SegmentType.OUTRO -> policy.autoOutro && policy.outroMode == OutroMode.FULL
            SegmentType.CREDITS -> policy.autoOutro
        }
        // Bereits übersprungen und vom Nutzer zurückgespult → nur noch anbieten (manuelle Übersteuerung).
        return if (wantsAuto && active.key !in handled) SkipAction.AutoSkip(active, active.endMs)
        else SkipAction.Offer(active, active.endMs)
    }

    companion object {
        const val END_TOLERANCE_MS = 5_000L

        /** Prüft eine Zeitmarke gegen die tatsächliche Episodenlänge. */
        fun isValid(s: SkipSegment, durationMs: Long): Boolean {
            if (durationMs <= 0) return false
            if (s.startMs < 0 || s.endMs <= s.startMs) return false
            if (s.endMs > durationMs + 1_000) return false
            val len = s.endMs - s.startMs
            if (len < 3_000) return false
            return when (s.type) {
                SegmentType.INTRO -> len <= 240_000 && len <= durationMs * 0.35 && s.startMs <= durationMs * 0.5
                SegmentType.RECAP -> len <= 300_000 && s.startMs <= durationMs * 0.3
                SegmentType.OUTRO, SegmentType.CREDITS -> s.startMs >= durationMs * 0.5 && len <= 900_000
            }
        }

        /** Gültige Segmente; pro Typ gewinnt die Marke mit der höchsten Sicherheit. */
        fun normalize(segments: List<SkipSegment>, durationMs: Long): List<SkipSegment> =
            segments.filter { isValid(it, durationMs) }
                .groupBy { it.type }
                .map { (_, list) -> list.maxBy { it.confidence } }
                .sortedBy { it.startMs }
    }
}

fun skipPolicyFrom(s: AppSettings) = SkipPolicy(
    enabled = s.smartSkipEnabled, autoIntro = s.autoSkipIntro, autoRecap = s.autoSkipRecap,
    autoOutro = s.autoSkipOutro, outroMode = s.outroMode, manualOnly = s.skipManualOnly,
    autoNext = s.autoNextEpisode, countdownSec = s.nextCountdownSec,
)
