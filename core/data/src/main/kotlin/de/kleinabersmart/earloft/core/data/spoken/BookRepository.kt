package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.EpisodePersonEntity
import de.kleinabersmart.earloft.core.model.EpisodePerson
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.model.AuthorHit
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.SeriesHit
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.model.BookProgress
import de.kleinabersmart.earloft.core.model.BookTimeline
import de.kleinabersmart.earloft.core.model.BookTrack
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.bookAuthors
import de.kleinabersmart.earloft.core.model.parsePeople
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Read access to books, chapters and progress for the UI and the player (concept 12). */
@Singleton
class BookRepository @Inject constructor(
    private val dao: BookDao,
    private val syncer: BookSyncer,
) {
    /** All books alphabetically, each with its progress. */
    fun books(): Flow<List<BookWithProgress>> =
        combine(dao.observeBooks(), dao.observeAllProgress()) { books, progress ->
            val byBook = progress.associateBy { it.bookId }
            books.map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) }
        }

    /**
     * Started, not yet finished books, most recently started or played first. A book counts from the start, even
     * at position 0 (see [touchProgress]); removed ones ([BookProgressEntity.hidden]) are missing until they play again.
     */
    fun continueListening(limit: Int = 12, startOrder: Flow<List<Long>> = flowOf(emptyList())): Flow<List<BookWithProgress>> =
        combine(dao.observeBooks(), dao.observeAllProgress(), startOrder) { books, progress, order ->
            val byId = books.associateBy { it.bookId }
            // The player only writes the previous book's state on switching (with a newer timestamp); that's why
            // the start order applies first and only after that the time of the last change.
            val rank = order.withIndex().associate { it.value to it.index }
            progress
                .filter { !it.finished && !it.hidden }
                .sortedWith(compareBy<BookProgressEntity> { rank[it.bookId] ?: Int.MAX_VALUE }.thenByDescending { it.updatedAt })
                .mapNotNull { p -> byId[p.bookId]?.let { BookWithProgress(it.toModel(), p.toModel()) } }
                .take(limit)
        }

    /** Most recently added books that haven't been started yet. */
    fun recentlyAdded(limit: Int = 12): Flow<List<BookWithProgress>> =
        books().map { list ->
            // Started books are in "Continue listening", not here. Reset ones ("unplayed", position 0) count again
            // as not started; only removed ones with a state stay in neither of the two rows.
            list.filter { item -> item.progress.let { it == null || (it.hidden && it.positionMs == 0L && !it.finished) } }
                .sortedByDescending { it.book.addedAt }
                .take(limit)
        }

    /**
     * The book is started: it immediately appears at the very front of "Continue listening". Position, speed and effects stay
     * as they are after reconciling with the server; the timestamp is set to now.
     */
    suspend fun touchProgress(bookId: Long, startPositionMs: Long, durationMs: Long) {
        val old = dao.progress(bookId)
        dao.upsertProgress(
            old?.copy(positionMs = startPositionMs, finished = false, finishedAt = null, updatedAt = System.currentTimeMillis(), hidden = false)
                ?: BookProgressEntity(bookId, startPositionMs, durationMs, finished = false, updatedAt = System.currentTimeMillis()),
        )
    }

    fun book(bookId: Long): Flow<BookWithProgress?> =
        combine(dao.observeBook(bookId), dao.observeProgress(bookId)) { book, progress ->
            book?.let { BookWithProgress(it.toModel(), progress?.toModel()) }
        }

    fun chapters(bookId: Long): Flow<List<BookChapter>> =
        combine(dao.observeChapters(bookId), dao.observeTracks(bookId)) { chapters, tracks -> chaptersOf(chapters, tracks) }
            .distinctUntilChanged()

    /** Contributors of an episode from the feed (`podcast:person`), in its order. */
    fun persons(bookId: Long): Flow<List<EpisodePerson>> = dao.observePersons(bookId).map { list -> list.map { it.toModel() } }

    suspend fun bookNow(bookId: Long): Book? = dao.book(bookId)?.toModel()

    /** The public image address of the book or episode (ARD Sounds, feeds); `null` without one. */
    suspend fun coverUrlOf(bookId: Long): String? = dao.book(bookId)?.coverUrl?.takeIf { it.startsWith("http") }

    /**
     * Description of the author [name] from Audiobookshelf (`books_improvements.md` M8); if they are on several servers, the first
     * existing one. `null` if no server has one.
     */
    fun authorDescription(name: String): Flow<String?> =
        dao.observeAuthors(name.normalizeForMerge())
            .map { authors -> authors.firstNotNullOfOrNull { it.description?.takeIf(String::isNotBlank) } }
            .distinctUntilChanged()

    /** Book or episode by server or subscription and its identifier there (the phone only tells the watch these, Wear concept 17). */
    suspend fun idByRemote(serverId: String, remoteId: String): Long? = dao.bookByRemote(serverId, remoteId)?.bookId

    /** Like [idByRemote], observed; `null` as long as the book or episode doesn't exist here. */
    fun observeIdByRemote(serverId: String, remoteId: String): Flow<Long?> =
        dao.observeBookIdByRemote(serverId, remoteId).distinctUntilChanged()

    /** Download state of all books; books without a loaded file list are missing (= [TrackDownload.NONE]). */
    fun downloads(): Flow<Map<Long, BookDownload>> =
        dao.observeBookDownloads().map { rows -> rows.associate { it.bookId to it.toDownload() } }

    /** Download state of the book; [TrackDownload.NONE] without a loaded file list. */
    fun download(bookId: Long): Flow<BookDownload> =
        dao.observeBookDownloads().map { rows ->
            rows.firstOrNull { it.bookId == bookId }?.toDownload() ?: BookDownload(TrackDownload.NONE, 0)
        }.distinctUntilChanged()

    suspend fun progressNow(bookId: Long): BookProgress? = dao.progress(bookId)?.toModel()

    /**
     * Timeline with files and chapters; loads it from the server if it's still missing or outdated. If the server is
     * unreachable, the existing one applies (a downloaded book stays playable offline). `null` without files.
     * [fetch] `false`: only read the database, without asking the server (for callers on the main thread).
     */
    suspend fun timeline(bookId: Long, fetch: Boolean = true): BookTimeline? {
        val book = dao.book(bookId) ?: return null
        if (fetch) syncer.ensureDetails(bookId)
        val files = dao.tracks(bookId)
        if (files.isEmpty()) return null
        return BookTimeline(book.durationMs, files.map { it.toModel() }, chaptersOf(dao.chapters(bookId), files))
    }

    /** Files of the book with their server IDs, for the media URLs. */
    suspend fun trackFiles(bookId: Long): List<BookTrack> = dao.tracks(bookId).map { it.toModel() }

    /** Searches books ([BookKind.BOOK]) or podcast episodes ([BookKind.EPISODE]) by title, author and narrator. */
    suspend fun search(query: String, kind: BookKind = BookKind.BOOK, limit: Int = 20): List<BookWithProgress> {
        val needle = query.normalizeForMerge().ifEmpty { return emptyList() }
        // Normalized like the search term (review G4); an episode's author is the podcast, the same for all its episodes.
        val normalized = HashMap<String, Boolean>()
        fun matches(text: String) = text.isNotEmpty() && normalized.getOrPut(text) { text.normalizeForMerge().contains(needle) }
        val ids = dao.searchRows(kind)
            .filter { it.normTitle.contains(needle) || matches(it.author) || matches(it.narrator) }
            .sortedBy { it.normTitle }
            .take(limit)
            .map { it.bookId }
        val books = dao.booksByIds(ids).associateBy { it.bookId }
        return ids.mapNotNull { books[it] }.map { BookWithProgress(it.toModel(), dao.progress(it.bookId)?.toModel()) }
    }

    /** Searches book authors by name; several authors of a book count individually, translators etc. don't. */
    suspend fun searchAuthors(query: String, limit: Int = 20): List<AuthorHit> {
        if (query.normalizeForMerge().isEmpty()) return emptyList()
        return peopleHits(dao.authorRows().map { it.bookId to it.names }, query, limit, ::bookAuthors)
    }

    /** Searches book narrators by name; several narrators of a book count individually. */
    suspend fun searchNarrators(query: String, limit: Int = 20): List<AuthorHit> {
        if (query.normalizeForMerge().isEmpty()) return emptyList()
        return peopleHits(dao.narratorRows().map { it.bookId to it.names }, query, limit, ::parsePeople)
    }

    /** Searches series by name. */
    suspend fun searchSeries(query: String, limit: Int = 20): List<SeriesHit> {
        val needle = query.normalizeForMerge().ifEmpty { return emptyList() }
        return seriesHits(dao.seriesRows(), needle, limit)
    }

    /**
     * Episodes: the feed doesn't always state the duration, or states it wrongly; the file knows it. Adopts the duration
     * learned by the player if it differs noticeably. `true` if something changed.
     */
    suspend fun learnEpisodeDuration(bookId: Long, durationMs: Long): Boolean {
        val book = dao.book(bookId) ?: return false
        if (book.kind != BookKind.EPISODE || durationMs <= 0 || kotlin.math.abs(book.durationMs - durationMs) <= DURATION_TOLERANCE_MS) return false
        dao.setSingleFileDuration(bookId, durationMs)
        return true
    }

}

private const val DURATION_TOLERANCE_MS = 2_000L

internal fun BookEntity.toModel() = Book(
    id = bookId,
    serverId = serverId,
    kind = kind,
    title = title,
    author = author,
    narrator = narrator,
    series = series,
    description = description,
    durationMs = durationMs,
    remoteId = remoteId,
    addedAt = addedAt,
    subtitle = subtitle,
    publishedYear = publishedYear,
    publisher = publisher,
    language = language,
    starredAt = starredAt,
    season = season,
    episodeNumber = episodeNumber,
    episodeType = episodeType,
    transcriptUrl = transcriptUrl,
    genres = genres?.let { parsePeople(it) }.orEmpty(),
    libraryId = libraryId,
    availableUntil = availableUntil,
    webUrl = webUrl,
)

internal fun EpisodePersonEntity.toModel() = EpisodePerson(name, role, personGroup, imageUrl, url)

internal fun BookChapterEntity.toModel() = BookChapter(chapterIndex, title, startMs, endMs, imageUrl, url)

internal fun BookTrackEntity.toModel() = BookTrack(
    trackIndex,
    remoteFileId,
    startMs,
    durationMs,
    mimeType,
    localPath = filePath.takeIf { downloadState == TrackDownload.DONE },
    unavailable = isUnavailable,
)

/** Not downloaded and no longer available at the source. */
internal val BookTrackEntity.isUnavailable: Boolean get() = unavailableSince != null && downloadState != TrackDownload.DONE

/** The chapters starting in a file that is no longer available ([isUnavailable]), as [BookChapter.unavailable]. */
internal fun chaptersOf(chapters: List<BookChapterEntity>, tracks: List<BookTrackEntity>): List<BookChapter> {
    val gone = tracks.filter { it.isUnavailable }
    return chapters.map { chapter ->
        chapter.toModel().let { model ->
            if (gone.any { chapter.startMs >= it.startMs && chapter.startMs < it.startMs + it.durationMs }) model.copy(unavailable = true) else model
        }
    }
}

internal fun BookProgressEntity.toModel() = BookProgress(positionMs, durationMs, finished, speed, updatedAt, dirty, hidden, skipSilence, volumeBoostDb)
