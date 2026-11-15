package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.common.sortKey
import de.kleinabersmart.earloft.core.model.AuthorHit
import de.kleinabersmart.earloft.core.model.AuthorSort
import de.kleinabersmart.earloft.core.model.SeriesSort
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookFilter
import de.kleinabersmart.earloft.core.model.BookSort
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

/** Nach Genre wie [authorGroups]; ein Buch mehrerer Genres steht bei jedem. */
fun List<BookWithProgress>.genreGroups(): List<BookGroup> = peopleGroups { it.genres }

/** Sortierschlüssel wie `normTitle` der Bücher: ohne Artikel „The“, Groß-/Kleinschreibung, Umlaute und Satzzeichen. */
private fun String.sortOrder(): String = sortKey().normalizeForMerge()

/** Hinter allem, was einen Schlüssel hat (Bücher ohne Autor bzw. Serie). */
private const val LAST = "￿"

private class AuthorOrder(val author: String, val series: String, val sequence: Double, val item: BookWithProgress)

private fun List<BookWithProgress>.peopleGroups(names: (Book) -> List<String>): List<BookGroup> =
    flatMap { item -> names(item.book).map { it to item } }
        .groupBy({ it.first }, { it.second })
        .map { (name, books) -> BookGroup(name, books.sortedBy { it.book.title.sortOrder() }) }
        .sortedBy { it.name.sortOrder() }

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
        .sortedBy { it.name.sortOrder() }
        .take(limit)
}

/**
 * Die Bücher (nach Titel) nach [filter] und in der Reihenfolge [sort]; [downloaded]: fertig geladene; mit [genre] nur die
 * Bücher dieses Genres, mit [library] nur die dieser Audiobookshelf-Bibliothek. „Zuletzt gehört“ nimmt die letzte Änderung des Fortschritts, nie gestartete stehen hinten; ohne Jahr
 * bzw. Dauer ebenso.
 */
fun List<BookWithProgress>.sortedAndFiltered(
    sort: BookSort,
    filter: BookFilter,
    downloaded: Set<Long>,
    genre: String? = null,
    library: String? = null,
): List<BookWithProgress> {
    val inLibrary = if (library == null) this else filter { it.book.libraryId == library }
    val inGenre = if (genre == null) inLibrary else inLibrary.filter { genre in it.book.genres }
    val shown = when (filter) {
        BookFilter.ALL -> inGenre
        BookFilter.NOT_STARTED -> inGenre.filter { it.progress.let { p -> p == null || (!p.finished && p.positionMs <= 0) } }
        BookFilter.STARTED -> inGenre.filter { it.progress.let { p -> p != null && !p.finished && p.positionMs > 0 } }
        BookFilter.FINISHED -> inGenre.filter { it.progress?.finished == true }
        BookFilter.DOWNLOADED -> inGenre.filter { it.book.isLocal || it.book.id in downloaded }
    }
    return when (sort) {
        BookSort.TITLE -> shown
        BookSort.AUTHOR -> shown.map { item ->
            val series = parseSeries(item.book.series).firstOrNull()
            AuthorOrder(
                author = bookAuthors(item.book.author).firstOrNull()?.sortOrder() ?: LAST,
                series = series?.name?.sortOrder() ?: LAST,
                sequence = series?.sequence ?: Double.MAX_VALUE,
                item = item,
            )
        }.sortedWith(compareBy({ it.author }, { it.series }, { it.sequence })).map { it.item }
        BookSort.RECENTLY_ADDED -> shown.sortedByDescending { it.book.addedAt }
        BookSort.RECENTLY_PLAYED -> shown.sortedByDescending { it.progress?.updatedAt ?: Long.MIN_VALUE }
        BookSort.DURATION -> shown.sortedByDescending { it.book.durationMs }
        BookSort.YEAR -> shown.sortedByDescending { it.book.publishedYear?.take(4)?.toIntOrNull() ?: Int.MIN_VALUE }
    }
}

/**
 * Serien (nach Name, wie [seriesGroups] sie liefert) in der Reihenfolge [sort]: „Zuletzt gehört“ nach der letzten Änderung des
 * Fortschritts irgendeines Bands; Serien, von denen nichts angefangen ist, bleiben nach Name dahinter.
 */
fun List<BookGroup>.sortedSeries(sort: SeriesSort): List<BookGroup> = when (sort) {
    SeriesSort.NAME -> this
    SeriesSort.RECENTLY_PLAYED -> sortedByDescending { group -> group.books.maxOfOrNull { it.progress?.updatedAt ?: Long.MIN_VALUE } }
}

/** Autoren (nach Name) in der Reihenfolge [sort]; gleich viele Bücher bleiben nach Name. */
fun List<BookGroup>.sortedAuthors(sort: AuthorSort): List<BookGroup> = when (sort) {
    AuthorSort.NAME -> this
    AuthorSort.BOOK_COUNT -> sortedByDescending { it.books.size }
}

/** Ein Abschnitt der Autorenseite: die Bände einer Serie oder ([series] `null`) die übrigen Bücher. */
data class SeriesSection(val series: String?, val books: List<BookWithProgress>)

/**
 * Die Bücher eines Autors nach Serie (`books_improvements.md` M8): je Serie ein Abschnitt (nach Name, darin nach Band, dann
 * Titel), dahinter die Bücher ohne Serie in der bisherigen Reihenfolge. Ein Buch steht nur bei seiner ersten Serie. Ohne jede
 * Serie ein einziger Abschnitt ohne Namen.
 */
fun List<BookWithProgress>.seriesSections(): List<SeriesSection> {
    val first = associateWith { parseSeries(it.book.series).firstOrNull() }
    val series = filter { first[it] != null }
        .groupBy { first.getValue(it)!!.name }
        .map { (name, books) ->
            SeriesSection(name, books.sortedWith(compareBy({ first[it]?.sequence ?: Double.MAX_VALUE }, { it.book.title.sortOrder() })))
        }
        .sortedBy { it.series!!.sortOrder() }
    val rest = filter { first[it] == null }
    return if (rest.isEmpty()) series else series + SeriesSection(null, rest)
}

/** Nach Serie (ohne Bandnummer, ein Buch kann in mehreren stehen); Bücher darin nach Band, dann Titel. */
fun List<BookWithProgress>.seriesGroups(): List<BookGroup> =
    flatMap { item -> parseSeries(item.book.series).map { Triple(it.name, it.sequence, item) } }
        .groupBy { it.first }
        .map { (name, entries) ->
            val sorted = entries.sortedWith(compareBy({ it.second ?: Double.MAX_VALUE }, { it.third.book.title.sortOrder() }))
            BookGroup(name, sorted.map { it.third })
        }
        .sortedBy { it.name.sortOrder() }
