package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.common.sortKey
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.parseSeries

/** Bücher mit gleichem Autor bzw. aus der gleichen Serie (Bibliothek der App und Android Auto). */
data class BookGroup(val name: String, val books: List<BookWithProgress>)

/** Nach Autor; Bücher ohne Autor entfallen. Gruppen nach Name, Bücher darin nach Titel. */
fun List<BookWithProgress>.authorGroups(): List<BookGroup> =
    filter { it.book.author.isNotBlank() }
        .groupBy { it.book.author.trim() }
        .map { (name, books) -> BookGroup(name, books.sortedBy { it.book.title.sortKey() }) }
        .sortedBy { it.name.sortKey() }

/** Nach Serie (ohne Bandnummer, ein Buch kann in mehreren stehen); Bücher darin nach Band, dann Titel. */
fun List<BookWithProgress>.seriesGroups(): List<BookGroup> =
    flatMap { item -> parseSeries(item.book.series).map { Triple(it.name, it.sequence, item) } }
        .groupBy { it.first }
        .map { (name, entries) ->
            val sorted = entries.sortedWith(compareBy({ it.second ?: Double.MAX_VALUE }, { it.third.book.title.sortKey() }))
            BookGroup(name, sorted.map { it.third })
        }
        .sortedBy { it.name.sortKey() }
