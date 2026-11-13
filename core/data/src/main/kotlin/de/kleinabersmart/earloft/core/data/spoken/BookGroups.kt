package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.common.sortKey
import de.kleinabersmart.earloft.core.model.AuthorHit
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.bookAuthors
import de.kleinabersmart.earloft.core.model.parsePeople
import de.kleinabersmart.earloft.core.model.parseSeries

/** Bücher mit gleichem Autor bzw. aus der gleichen Serie (Bibliothek der App und Android Auto). */
data class BookGroup(val name: String, val books: List<BookWithProgress>)

/**
 * Nach Autor: Ein Buch mehrerer Autoren steht bei jedem ([bookAuthors], ohne Übersetzer u. Ä.); Bücher ohne Autor
 * entfallen. Gruppen nach Name, Bücher darin nach Titel.
 */
fun List<BookWithProgress>.authorGroups(): List<BookGroup> = peopleGroups { bookAuthors(it.author) }

/** Nach Sprecher wie [authorGroups]; ein Buch mehrerer Sprecher steht bei jedem. */
fun List<BookWithProgress>.narratorGroups(): List<BookGroup> = peopleGroups { parsePeople(it.narrator) }

private fun List<BookWithProgress>.peopleGroups(names: (Book) -> List<String>): List<BookGroup> =
    flatMap { item -> names(item.book).map { it to item } }
        .groupBy({ it.first }, { it.second })
        .map { (name, books) -> BookGroup(name, books.sortedBy { it.book.title.sortKey() }) }
        .sortedBy { it.name.sortKey() }

/**
 * Suchtreffer für Autoren bzw. Sprecher aus Büchern, deren Eintrag zu [query] passt ([rows]: `bookId` und der ganze
 * Eintrag): je einzelner Name ([names]), der selbst passt, mit dem kleinsten Buch (für das Bild) und der Anzahl.
 */
internal fun peopleHits(rows: List<Pair<Long, String>>, query: String, limit: Int, names: (String) -> List<String>): List<AuthorHit> {
    val needle = query.normalizeForMerge()
    return rows.flatMap { (bookId, entry) -> names(entry).map { it to bookId } }
        .filter { (name, _) -> name.normalizeForMerge().contains(needle) }
        .groupBy({ it.first }, { it.second })
        .map { (name, bookIds) -> AuthorHit(name, bookIds.min(), bookIds.distinct().size) }
        .sortedBy { it.name.sortKey() }
        .take(limit)
}

/** Nach Serie (ohne Bandnummer, ein Buch kann in mehreren stehen); Bücher darin nach Band, dann Titel. */
fun List<BookWithProgress>.seriesGroups(): List<BookGroup> =
    flatMap { item -> parseSeries(item.book.series).map { Triple(it.name, it.sequence, item) } }
        .groupBy { it.first }
        .map { (name, entries) ->
            val sorted = entries.sortedWith(compareBy({ it.second ?: Double.MAX_VALUE }, { it.third.book.title.sortKey() }))
            BookGroup(name, sorted.map { it.third })
        }
        .sortedBy { it.name.sortKey() }
