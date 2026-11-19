package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.parseSeries

/** "Download started books automatically" downloads this many of the first entries from "Continue listening". */
const val AUTO_STARTED_BOOKS = 2

/** From this share of a volume on, "Download next volume" downloads the following one. */
const val NEXT_IN_SERIES_AT = 0.8

/** "Download books expiring soon" downloads a book this many days before it expires on ARD Sounds. */
const val ARD_EXPIRY_LOAD_DAYS = 7

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** A finished volume still counts this long for "Download next volume" (after that, one has presumably decided against it). */
private const val NEXT_IN_SERIES_FINISHED_DAYS = 14

/**
 * Which books the automation should download (concept 12.8, `books_improvements.md` M5): with [started] the first
 * [AUTO_STARTED_BOOKS] from [continueListening], with [nextInSeries] for each series the next volume after one that has been
 * listened to up to [NEXT_IN_SERIES_AT] (or was finished at most [NEXT_IN_SERIES_FINISHED_DAYS] days ago) – the next one is
 * the one with the smallest higher volume number that is not finished yet. Books from the device need no download. With [expiring]
 * the books from ARD Sounds that are not finished yet and expire within at most [ARD_EXPIRY_LOAD_DAYS] days, the ones
 * expiring first come first; books that have already expired no longer exist there.
 */
fun automaticBookDownloads(
    continueListening: List<BookWithProgress>,
    all: List<BookWithProgress>,
    started: Boolean,
    nextInSeries: Boolean,
    now: Long,
    expiring: Boolean = false,
): List<Long> = buildList {
    if (started) {
        continueListening.filter { !it.book.isLocal }.take(AUTO_STARTED_BOOKS).forEach { add(it.book.id) }
    }
    if (nextInSeries) {
        val volumes = all.filter { !it.book.isLocal }
            .flatMap { item -> parseSeries(item.book.series).mapNotNull { ref -> ref.sequence?.let { Triple(ref.name, it, item) } } }
            .groupBy({ it.first }, { it.second to it.third })
        for ((_, books) in volumes) {
            val sorted = books.sortedBy { it.first }
            sorted.filter { (_, item) -> item.isNearlyDone(now) }.forEach { (sequence, _) ->
                sorted.firstOrNull { (next, item) -> next > sequence && item.progress?.finished != true }?.let { add(it.second.book.id) }
            }
        }
    }
    if (expiring) {
        all.filter { it.book.isArdSounds && it.progress?.finished != true }
            .mapNotNull { item -> item.book.availableUntil?.takeIf { it > now && it - now <= ARD_EXPIRY_LOAD_DAYS * DAY_MS }?.let { it to item.book.id } }
            .sortedBy { it.first }
            .forEach { add(it.second) }
    }
}.distinct()

private fun BookWithProgress.isNearlyDone(now: Long): Boolean {
    val progress = progress ?: return false
    if (progress.finished) return now - progress.updatedAt <= NEXT_IN_SERIES_FINISHED_DAYS * DAY_MS
    val duration = progress.durationMs.takeIf { it > 0 } ?: book.durationMs
    return duration > 0 && progress.positionMs >= duration * NEXT_IN_SERIES_AT
}
