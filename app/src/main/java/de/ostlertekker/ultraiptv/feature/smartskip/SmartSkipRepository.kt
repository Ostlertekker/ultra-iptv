package de.ostlertekker.ultraiptv.feature.smartskip

import de.ostlertekker.ultraiptv.core.data.AppSettings
import de.ostlertekker.ultraiptv.core.database.EpisodeEntity
import de.ostlertekker.ultraiptv.core.database.SkipMarkerEntity
import de.ostlertekker.ultraiptv.core.database.UserDao
import javax.inject.Inject
import javax.inject.Singleton

fun skipPolicyFrom(s: AppSettings) = SkipPolicy(
    enabled = s.smartSkipEnabled, autoIntro = s.autoSkipIntro, autoRecap = s.autoSkipRecap,
    autoOutro = s.autoSkipOutro, outroMode = s.outroMode, manualOnly = s.skipManualOnly,
    autoNext = s.autoNextEpisode, countdownSec = s.nextCountdownSec,
)

/** Lädt und speichert Zeitmarken pro Episode (inkl. Lernen aus Korrekturen). */
@Singleton
class SmartSkipRepository @Inject constructor(private val dao: UserDao) {

    suspend fun segmentsFor(episode: EpisodeEntity): List<SkipSegment> {
        val out = ArrayList<SkipSegment>()
        // 1. Anbieter-Zeitmarken
        if (episode.introStartMs != null && episode.introEndMs != null) {
            out += SkipSegment(SegmentType.INTRO, episode.introStartMs, episode.introEndMs, MarkerSource.PROVIDER, 0.9f)
        }
        val dur = (episode.durationSec ?: 0L) * 1000L
        if (episode.outroStartMs != null && dur > 0) {
            out += SkipSegment(SegmentType.OUTRO, episode.outroStartMs, dur, MarkerSource.PROVIDER, 0.9f)
        }
        // 2./3. Eigene + bestätigte Episoden-Marken
        dao.markersFor(episode.id).forEach { out += it.toSegment() }
        // 4. Staffel-Vorschläge (nur manuell angeboten, da Intros je Folge abweichen können)
        dao.templatesFor(episode.seriesId, episode.season).forEach { out += it.toSegment().copy(source = MarkerSource.TEMPLATE, confidence = 0.6f) }
        return out
    }

    suspend fun saveUserMarker(episode: EpisodeEntity, type: SegmentType, startMs: Long, endMs: Long) {
        dao.deleteMarkers(episode.id, type.name)
        dao.insertMarker(SkipMarkerEntity(
            episodeId = episode.id, seriesId = episode.seriesId, season = episode.season, type = type.name,
            startMs = startMs, endMs = endMs, source = MarkerSource.USER.name, confidence = 1f, createdAt = System.currentTimeMillis(),
        ))
        // Als Staffel-Vorschlag für die übrigen Folgen merken
        dao.deleteTemplates(episode.seriesId, episode.season, type.name)
        dao.insertMarker(SkipMarkerEntity(
            episodeId = "", seriesId = episode.seriesId, season = episode.season, type = type.name,
            startMs = startMs, endMs = endMs, source = MarkerSource.TEMPLATE.name, confidence = 0.6f, createdAt = System.currentTimeMillis(),
        ))
    }

    /** Nutzer hat einen Staffel-Vorschlag angenommen → für diese Folge als bestätigt speichern. */
    suspend fun confirm(episode: EpisodeEntity, segment: SkipSegment) {
        if (segment.source != MarkerSource.TEMPLATE) return
        dao.deleteMarkers(episode.id, segment.type.name)
        dao.insertMarker(SkipMarkerEntity(
            episodeId = episode.id, seriesId = episode.seriesId, season = episode.season, type = segment.type.name,
            startMs = segment.startMs, endMs = segment.endMs, source = MarkerSource.VERIFIED.name, confidence = 0.9f,
            createdAt = System.currentTimeMillis(),
        ))
    }

    suspend fun clear(episode: EpisodeEntity) = dao.deleteAllMarkers(episode.id)

    private fun SkipMarkerEntity.toSegment() = SkipSegment(
        type = runCatching { SegmentType.valueOf(type) }.getOrDefault(SegmentType.INTRO),
        startMs = startMs, endMs = endMs,
        source = runCatching { MarkerSource.valueOf(source) }.getOrDefault(MarkerSource.USER),
        confidence = confidence,
    )
}
