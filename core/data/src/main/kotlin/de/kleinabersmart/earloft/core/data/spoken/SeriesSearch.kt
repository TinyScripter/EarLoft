package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.database.model.BookSeriesRow
import de.kleinabersmart.earloft.core.model.SeriesHit
import de.kleinabersmart.earloft.core.model.parseSeries

/**
 * The series whose name contains [needle] (already normalized), with the number of books and the smallest volume as
 * cover. A book can be in several series; the series entry is "Name #Volume", the volume isn't part of the name.
 */
internal fun seriesHits(books: List<BookSeriesRow>, needle: String, limit: Int): List<SeriesHit> {
    if (needle.isEmpty()) return emptyList()
    return books
        .flatMap { book -> parseSeries(book.series).filter { it.name.normalizeForMerge().contains(needle) }.map { it to book } }
        .groupBy({ it.first.name }, { it.first.sequence to it.second })
        .map { (name, entries) ->
            val first = entries.minWith(compareBy({ it.first ?: Double.MAX_VALUE }, { it.second.title.normalizeForMerge() })).second
            SeriesHit(name, first.bookId, entries.size)
        }
        .sortedBy { it.name.normalizeForMerge() }
        .take(limit)
}
