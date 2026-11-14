package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdPart
import kotlin.math.abs

/**
 * Ein Teil eines Buchs aus ARD Sounds in der Bibliothek: seine Datei ([track]), sein Kapitelname und bis wann ARD Sounds ihn
 * anbietet ([until]; bei nicht mehr geführten Teilen seit wann nicht mehr).
 */
internal data class ArdBookPart(val track: BookTrackEntity, val title: String, val until: Long?)

/** Die Teile, wie ARD Sounds sie gerade anbietet, in seiner Reihenfolge (ein noch nicht angefangenes Buch). */
internal fun freshArdParts(offered: List<ArdPart>): List<ArdBookPart> {
    var start = 0L
    return offered.mapIndexed { index, part ->
        ArdBookPart(BookTrackEntity(0, index, part.audioUrl, start, part.durationMs, part.mimeType), part.title, part.availableUntil)
            .also { start += part.durationMs }
    }
}

/**
 * Führt die Teile eines angefangenen Buchs ([known], nach Spur geordnet) mit denen zusammen, die ARD Sounds gerade anbietet
 * ([offered]), ohne dass sich etwas verschiebt: Jeder bekannte Teil bleibt an seinem Platz. Ein Teil gilt als derselbe bei
 * gleicher Adresse, sonst bei gleichem Namen und (fast) gleicher Länge (neu veröffentlicht). Was ARD Sounds nicht mehr führt, bleibt
 * mit [BookTrackEntity.unavailableSince]; neue Teile kommen hinten dazu. Längen und Kapitelnamen bekannter Teile bleiben (ARD
 * Sounds benennt Teile je nach ihrer Zahl anders), damit Positionen, Lesezeichen und Kapitel stimmen; geladene bzw. ladende Teile
 * behalten auch ihre Adresse.
 */
internal fun mergeArdParts(known: List<ArdBookPart>, offered: List<ArdPart>, now: Long): List<ArdBookPart> {
    val matchOf = arrayOfNulls<ArdPart>(known.size)
    val taken = BooleanArray(offered.size)
    known.forEachIndexed { i, part ->
        val j = offered.indices.firstOrNull { !taken[it] && offered[it].audioUrl == part.track.remoteFileId } ?: return@forEachIndexed
        matchOf[i] = offered[j]
        taken[j] = true
    }
    known.forEachIndexed { i, part ->
        if (matchOf[i] != null) return@forEachIndexed
        val j = offered.indices.firstOrNull { !taken[it] && sameAudio(part, offered[it]) } ?: return@forEachIndexed
        matchOf[i] = offered[j]
        taken[j] = true
    }
    val merged = known.mapIndexed { i, part ->
        val match = matchOf[i]
        val track = part.track
        when {
            match == null -> part.copy(track = track.copy(unavailableSince = track.unavailableSince ?: now), until = track.unavailableSince ?: now)
            track.downloadState in KEEP_FILE -> ArdBookPart(track.copy(unavailableSince = null), part.title.ifEmpty { match.title }, match.availableUntil)
            else -> ArdBookPart(
                track.copy(
                    remoteFileId = match.audioUrl,
                    mimeType = match.mimeType,
                    durationMs = track.durationMs.takeIf { it > 0 } ?: match.durationMs,
                    unavailableSince = null,
                ),
                part.title.ifEmpty { match.title },
                match.availableUntil,
            )
        }
    }
    val next = (known.maxOfOrNull { it.track.trackIndex } ?: -1) + 1
    val added = offered.filterIndexed { j, _ -> !taken[j] }.mapIndexed { k, part ->
        ArdBookPart(BookTrackEntity(0, next + k, part.audioUrl, 0, part.durationMs, part.mimeType), part.title, part.availableUntil)
    }
    var start = 0L
    return (merged + added).map { part ->
        part.copy(track = part.track.copy(startMs = start)).also { start += it.track.durationMs }
    }
}

/**
 * Bis wann das Buch abrufbar ist: der früheste Ablauf eines abrufbaren Teils, der noch nicht geladen ist; ist alles Abrufbare
 * geladen, [whenAllLoaded]. Teile, die ARD Sounds nicht mehr führt, zählen nicht: Sie kommen nicht wieder.
 */
internal fun ardAvailableUntil(parts: List<ArdBookPart>, whenAllLoaded: Long?): Long? {
    val pending = parts.filter { it.track.downloadState != TrackDownload.DONE && it.track.unavailableSince == null }
    return if (pending.isEmpty()) whenAllLoaded else pending.mapNotNull { it.until }.minOrNull()
}

private fun sameAudio(part: ArdBookPart, offered: ArdPart): Boolean =
    part.title.trim().equals(offered.title.trim(), ignoreCase = true) &&
        (part.track.durationMs <= 0 || abs(part.track.durationMs - offered.durationMs) <= SAME_LENGTH_TOLERANCE_MS)

/** Dateien, die schon (bzw. gerade) geladen werden: Ihre Adresse bleibt. */
private val KEEP_FILE = setOf(TrackDownload.DONE, TrackDownload.QUEUED, TrackDownload.RUNNING)

private const val SAME_LENGTH_TOLERANCE_MS = 2_000L
