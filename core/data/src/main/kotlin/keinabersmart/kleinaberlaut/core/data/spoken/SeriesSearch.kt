package keinabersmart.kleinaberlaut.core.data.spoken

import keinabersmart.kleinaberlaut.core.common.normalizeForMerge
import keinabersmart.kleinaberlaut.core.model.Book
import keinabersmart.kleinaberlaut.core.model.SeriesHit
import keinabersmart.kleinaberlaut.core.model.parseSeries

/**
 * Die Serien, deren Name [needle] (schon normalisiert) enthält, mit Anzahl der Bücher und dem kleinsten Band als
 * Cover. Ein Buch kann in mehreren Serien stehen; die Serienangabe ist „Name #Band“, der Band zählt nicht zum Namen.
 */
internal fun seriesHits(books: List<Book>, needle: String, limit: Int): List<SeriesHit> {
    if (needle.isEmpty()) return emptyList()
    return books
        .flatMap { book -> parseSeries(book.series).filter { it.name.normalizeForMerge().contains(needle) }.map { it to book } }
        .groupBy({ it.first.name }, { it.first.sequence to it.second })
        .map { (name, entries) ->
            val first = entries.minWith(compareBy({ it.first ?: Double.MAX_VALUE }, { it.second.title.normalizeForMerge() })).second
            SeriesHit(name, first.id, entries.size)
        }
        .sortedBy { it.name.normalizeForMerge() }
        .take(limit)
}
