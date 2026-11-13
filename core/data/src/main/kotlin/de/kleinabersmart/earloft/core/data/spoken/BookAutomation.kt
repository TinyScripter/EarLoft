package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.parseSeries

/** Die ersten so vielen aus „Weiterhören“ lädt „Angefangene Bücher automatisch laden“. */
const val AUTO_STARTED_BOOKS = 2

/** Ab diesem Anteil eines Bands lädt „Nächsten Band laden“ den folgenden. */
const val NEXT_IN_SERIES_AT = 0.8

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Ein beendeter Band zählt noch so lange für „Nächsten Band laden“ (danach hat man sich wohl dagegen entschieden). */
private const val NEXT_IN_SERIES_FINISHED_DAYS = 14

/**
 * Welche Bücher die Automatik laden soll (Konzept 12.8, `books_improvements.md` M5): mit [started] die ersten
 * [AUTO_STARTED_BOOKS] aus [continueListening], mit [nextInSeries] je Serie den nächsten Band nach einem, der zu
 * [NEXT_IN_SERIES_AT] gehört ist (oder vor höchstens [NEXT_IN_SERIES_FINISHED_DAYS] Tagen beendet wurde) – der nächste ist
 * der mit der kleinsten höheren Bandnummer, der noch nicht beendet ist. Bücher vom Gerät brauchen keinen Download.
 */
fun automaticBookDownloads(
    continueListening: List<BookWithProgress>,
    all: List<BookWithProgress>,
    started: Boolean,
    nextInSeries: Boolean,
    now: Long,
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
}.distinct()

private fun BookWithProgress.isNearlyDone(now: Long): Boolean {
    val progress = progress ?: return false
    if (progress.finished) return now - progress.updatedAt <= NEXT_IN_SERIES_FINISHED_DAYS * DAY_MS
    val duration = progress.durationMs.takeIf { it > 0 } ?: book.durationMs
    return duration > 0 && progress.positionMs >= duration * NEXT_IN_SERIES_AT
}
