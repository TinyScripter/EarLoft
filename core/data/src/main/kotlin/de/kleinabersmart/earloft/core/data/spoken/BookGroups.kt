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

/** Books by the same author or from the same series (app library and Android Auto). */
data class BookGroup(val name: String, val books: List<BookWithProgress>)

/**
 * By author: a book with several authors appears under each one ([bookAuthors], without translators etc.); books without
 * an author are left out. Groups by name, books within them by title.
 */
fun List<BookWithProgress>.authorGroups(): List<BookGroup> = peopleGroups { bookAuthors(it.author) }

/** By narrator like [authorGroups]; a book with several narrators appears under each one. */
fun List<BookWithProgress>.narratorGroups(): List<BookGroup> = peopleGroups { parsePeople(it.narrator) }

/**
 * By genre like [authorGroups]; a book with several genres appears under each one. Spellings that differ only in case,
 * umlauts or punctuation are one genre; the name is the most frequent spelling (on a tie the smallest).
 */
fun List<BookWithProgress>.genreGroups(): List<BookGroup> =
    flatMap { item -> item.book.genres.map { it to item } }
        .groupBy { it.first.genreKey() }
        .values
        .map { entries ->
            val spellings = entries.groupingBy { it.first }.eachCount()
            val name = spellings.entries.sortedWith(compareBy({ -it.value }, { it.key })).first().key
            BookGroup(name, entries.map { it.second }.distinct().sortedByOrder { it.book.title })
        }
        .sortedByOrder { it.name }

/** Does the book have [genre], in whatever spelling ([genreGroups])? */
fun Book.hasGenre(genre: String): Boolean = genres.any { sameGenre(it, genre) }

/** Are [a] and [b] the same genre in two spellings? */
fun sameGenre(a: String, b: String): Boolean = a.genreKey() == b.genreKey()

private fun String.genreKey(): String = normalizeForMerge().ifEmpty { trim().lowercase() }

/** Sort key like the books' `normTitle`: without the article "The", case, umlauts and punctuation. */
private fun String.sortOrder(): String = sortKey().normalizeForMerge()

/**
 * Like `sortedBy { text(it).sortOrder() }`, but each key only once: [sortOrder] normalizes (NFKD and two regular
 * expressions), and `sortedBy` recomputed it on every comparison – for large libraries tens of thousands of times every 15 s
 * as soon as a book is playing and its progress re-emits the list (review D12).
 */
private inline fun <T> List<T>.sortedByOrder(crossinline text: (T) -> String): List<T> =
    map { it to text(it).sortOrder() }.sortedBy { it.second }.map { it.first }

/** After everything that has a key (books without author or series). */
private const val LAST = "￿"

private class AuthorOrder(val author: String, val series: String, val sequence: Double, val item: BookWithProgress)

private fun List<BookWithProgress>.peopleGroups(names: (Book) -> List<String>): List<BookGroup> =
    flatMap { item -> names(item.book).map { it to item } }
        .groupBy({ it.first }, { it.second })
        .map { (name, books) -> BookGroup(name, books.sortedByOrder { it.book.title }) }
        .sortedByOrder { it.name }

/**
 * Search hits for authors or narrators from books whose entry matches [query] ([rows]: `bookId` and the whole
 * entry): per individual name ([names]) that matches itself, with the smallest book (for the image) and the count.
 */
internal fun peopleHits(rows: List<Pair<Long, String>>, query: String, limit: Int, names: (String) -> List<String>): List<AuthorHit> {
    val needle = query.normalizeForMerge()
    return rows.flatMap { (bookId, entry) -> names(entry).map { it to bookId } }
        .filter { (name, _) -> name.normalizeForMerge().contains(needle) }
        .groupBy({ it.first }, { it.second })
        .map { (name, bookIds) -> AuthorHit(name, bookIds.min(), bookIds.distinct().size) }
        .sortedByOrder { it.name }
        .take(limit)
}

/**
 * The books (by title) according to [filter] and in the order [sort]; [downloaded]: fully downloaded ones; with [genre] only the
 * books of this genre, with [library] only those of this Audiobookshelf library. "Recently played" uses the last progress change, never-started ones go last; the same
 * applies to missing year or duration.
 */
fun List<BookWithProgress>.sortedAndFiltered(
    sort: BookSort,
    filter: BookFilter,
    downloaded: Set<Long>,
    genre: String? = null,
    library: String? = null,
): List<BookWithProgress> {
    val inLibrary = if (library == null) this else filter { it.book.libraryId == library }
    val inGenre = if (genre == null) inLibrary else inLibrary.filter { it.book.hasGenre(genre) }
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
 * Series (by name, as [seriesGroups] returns them) in the order [sort]: "Recently played" by the last progress change of
 * any volume; series none of which has been started stay behind, sorted by name.
 */
fun List<BookGroup>.sortedSeries(sort: SeriesSort): List<BookGroup> = when (sort) {
    SeriesSort.NAME -> this
    SeriesSort.RECENTLY_PLAYED -> sortedByDescending { group -> group.books.maxOfOrNull { it.progress?.updatedAt ?: Long.MIN_VALUE } }
}

/** Authors (by name) in the order [sort]; equal book counts stay sorted by name. */
fun List<BookGroup>.sortedAuthors(sort: AuthorSort): List<BookGroup> = when (sort) {
    AuthorSort.NAME -> this
    AuthorSort.BOOK_COUNT -> sortedByDescending { it.books.size }
}

/** A section of the author page: the volumes of a series or ([series] `null`) the remaining books. */
data class SeriesSection(val series: String?, val books: List<BookWithProgress>)

/**
 * An author's books by series (`books_improvements.md` M8): one section per series (by name, within it by volume, then
 * title), followed by the books without series in the previous order. A book appears only under its first series. Without any
 * series a single section without a name.
 */
fun List<BookWithProgress>.seriesSections(): List<SeriesSection> {
    val first = associateWith { parseSeries(it.book.series).firstOrNull() }
    val series = filter { first[it] != null }
        .groupBy { first.getValue(it)!!.name }
        .map { (name, books) ->
            val titles = books.associateWith { it.book.title.sortOrder() }
            SeriesSection(name, books.sortedWith(compareBy({ first[it]?.sequence ?: Double.MAX_VALUE }, { titles.getValue(it) })))
        }
        .sortedByOrder { it.series!! }
    val rest = filter { first[it] == null }
    return if (rest.isEmpty()) series else series + SeriesSection(null, rest)
}

/** By series (without volume number, a book can be in several); books within them by volume, then title. */
fun List<BookWithProgress>.seriesGroups(): List<BookGroup> =
    flatMap { item -> parseSeries(item.book.series).map { Triple(it.name, it.sequence, item) } }
        .groupBy { it.first }
        .map { (name, entries) ->
            val sorted = entries.map { it to it.third.book.title.sortOrder() }
                .sortedWith(compareBy({ it.first.second ?: Double.MAX_VALUE }, { it.second }))
            BookGroup(name, sorted.map { it.first.third })
        }
        .sortedByOrder { it.name }
