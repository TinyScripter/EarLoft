package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.database.dao.KeyedBookPart
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdPart
import kotlin.math.abs

/**
 * A part of a book from ARD Sounds in the library: its file ([track]), its chapter name and until when ARD Sounds
 * offers it ([until]; for parts no longer listed, since when they are no longer offered).
 */
internal data class ArdBookPart(val track: BookTrackEntity, val title: String, val until: Long?)

/** The parts as ARD Sounds currently offers them, in its order (a book not yet started). */
internal fun freshArdParts(offered: List<ArdPart>): List<ArdBookPart> {
    var start = 0L
    return offered.mapIndexed { index, part ->
        ArdBookPart(BookTrackEntity(0, index, part.audioUrl, start, part.durationMs, part.mimeType, remoteItemId = part.id), part.title, part.availableUntil)
            .also { start += part.durationMs }
    }
}

/**
 * Merges the parts of a started book ([known], ordered by track) with those ARD Sounds currently offers
 * ([offered]) without anything shifting: every known part stays in its place. A part counts as the same with
 * the same title ID ([BookTrackEntity.remoteItemId], [ArdPart.id]; the URL can change at ARD Sounds), otherwise
 * with the same URL, otherwise with the same name and (almost) the same length (republished). Whatever ARD Sounds no longer lists
 * stays with [BookTrackEntity.unavailableSince]; new parts are appended at the end. Lengths and chapter names of known parts stay
 * (ARD Sounds names parts differently depending on their number), so positions, bookmarks and chapters stay correct; downloaded or downloading
 * parts also keep their URL. The title ID always comes from ARD Sounds.
 */
internal fun mergeArdParts(known: List<ArdBookPart>, offered: List<ArdPart>, now: Long): List<ArdBookPart> {
    val matchOf = arrayOfNulls<ArdPart>(known.size)
    val taken = BooleanArray(offered.size)
    fun match(same: (ArdBookPart, ArdPart) -> Boolean) = known.forEachIndexed { i, part ->
        if (matchOf[i] != null) return@forEachIndexed
        val j = offered.indices.firstOrNull { !taken[it] && same(part, offered[it]) } ?: return@forEachIndexed
        matchOf[i] = offered[j]
        taken[j] = true
    }
    match { part, offer -> part.track.remoteItemId != null && part.track.remoteItemId == offer.id }
    match { part, offer -> part.track.remoteFileId == offer.audioUrl }
    match(::sameAudio)
    val merged = known.mapIndexed { i, part ->
        val match = matchOf[i]
        val track = part.track
        when {
            match == null -> part.copy(track = track.copy(unavailableSince = track.unavailableSince ?: now), until = track.unavailableSince ?: now)
            track.downloadState in KEEP_FILE ->
                ArdBookPart(track.copy(unavailableSince = null, remoteItemId = match.id), part.title.ifEmpty { match.title }, match.availableUntil)
            else -> ArdBookPart(
                track.copy(
                    remoteFileId = match.audioUrl,
                    mimeType = match.mimeType,
                    durationMs = track.durationMs.takeIf { it > 0 } ?: match.durationMs,
                    unavailableSince = null,
                    remoteItemId = match.id,
                ),
                part.title.ifEmpty { match.title },
                match.availableUntil,
            )
        }
    }
    val next = (known.maxOfOrNull { it.track.trackIndex } ?: -1) + 1
    val added = offered.filterIndexed { j, _ -> !taken[j] }.mapIndexed { k, part ->
        ArdBookPart(BookTrackEntity(0, next + k, part.audioUrl, 0, part.durationMs, part.mimeType, remoteItemId = part.id), part.title, part.availableUntil)
    }
    var start = 0L
    return (merged + added).map { part ->
        part.copy(track = part.track.copy(startMs = start)).also { start += it.track.durationMs }
    }
}

/**
 * Until when the book is available: the earliest expiry of an available part that isn't downloaded yet; if everything available
 * is downloaded, [whenAllLoaded]. Parts that ARD Sounds no longer lists don't count: they won't come back.
 */
internal fun ardAvailableUntil(parts: List<ArdBookPart>, whenAllLoaded: Long?): Long? {
    val pending = parts.filter { it.track.downloadState != TrackDownload.DONE && it.track.unavailableSince == null }
    return if (pending.isEmpty()) whenAllLoaded else pending.mapNotNull { it.until }.minOrNull()
}

/**
 * The parts of a book for the backup (`UserArdBookEntity.parts`), in their order: per line URL, length, MIME type,
 * since when unavailable, chapter name and title ID, separated by tabs.
 */
internal fun encodeArdParts(parts: List<KeyedBookPart>): String =
    parts.sortedBy { it.trackIndex }.joinToString("\n") { part ->
        listOf(
            part.remoteFileId,
            part.durationMs.toString(),
            part.mimeType.orEmpty(),
            part.unavailableSince?.toString().orEmpty(),
            part.title.orEmpty(),
            part.remoteItemId.orEmpty(),
        ).joinToString("\t") { it.replace(SEPARATORS, " ") }
    }

/**
 * The backed-up parts ([encodeArdParts]) as known parts of a book of which nothing is downloaded yet. Empty without backed-up
 * parts or if a line is unreadable (then the book takes the parts as ARD Sounds offers them). Older backups
 * don't know the title ID.
 */
internal fun decodeArdParts(text: String?): List<ArdBookPart> {
    if (text.isNullOrBlank()) return emptyList()
    var start = 0L
    return text.split('\n').mapIndexed { index, line ->
        val fields = line.split('\t')
        val url = fields.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val duration = fields.getOrNull(1)?.toLongOrNull() ?: return emptyList()
        val since = fields.getOrNull(3)?.toLongOrNull()
        val track = BookTrackEntity(
            0, index, url, start, duration, fields.getOrNull(2)?.ifEmpty { null },
            unavailableSince = since,
            remoteItemId = fields.getOrNull(5)?.ifEmpty { null },
        )
        start += duration
        ArdBookPart(track, fields.getOrNull(4).orEmpty(), since)
    }
}

private fun sameAudio(part: ArdBookPart, offered: ArdPart): Boolean =
    part.title.trim().equals(offered.title.trim(), ignoreCase = true) &&
        (part.track.durationMs <= 0 || abs(part.track.durationMs - offered.durationMs) <= SAME_LENGTH_TOLERANCE_MS)

/** Files that are already (or currently) being downloaded: their URL stays. */
private val KEEP_FILE = setOf(TrackDownload.DONE, TrackDownload.QUEUED, TrackDownload.RUNNING)

private const val SAME_LENGTH_TOLERANCE_MS = 2_000L

private val SEPARATORS = Regex("[\t\r\n]")
