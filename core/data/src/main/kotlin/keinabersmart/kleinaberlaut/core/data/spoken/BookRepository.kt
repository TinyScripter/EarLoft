package keinabersmart.kleinaberlaut.core.data.spoken

import keinabersmart.kleinaberlaut.core.common.normalizeForMerge
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.entity.BookChapterEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookProgressEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookTrackEntity
import keinabersmart.kleinaberlaut.core.model.Book
import keinabersmart.kleinaberlaut.core.model.BookChapter
import keinabersmart.kleinaberlaut.core.model.BookProgress
import keinabersmart.kleinaberlaut.core.model.BookTimeline
import keinabersmart.kleinaberlaut.core.model.BookTrack
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Lesezugriff auf Bücher, Kapitel und Fortschritt für die UI und den Player (Konzept 12). */
@Singleton
class BookRepository @Inject constructor(
    private val dao: BookDao,
    private val syncer: BookSyncer,
) {
    /** Alle Bücher alphabetisch, jeweils mit ihrem Fortschritt. */
    fun books(): Flow<List<BookWithProgress>> =
        combine(dao.observeBooks(), dao.observeAllProgress()) { books, progress ->
            val byBook = progress.associateBy { it.bookId }
            books.map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) }
        }

    /** Angefangene, noch nicht beendete Bücher, zuletzt gehörte zuerst. */
    fun continueListening(limit: Int = 12): Flow<List<BookWithProgress>> =
        combine(dao.observeBooks(), dao.observeAllProgress()) { books, progress ->
            val byId = books.associateBy { it.bookId }
            progress
                .filter { !it.finished && it.positionMs > 0 }
                .sortedByDescending { it.updatedAt }
                .mapNotNull { p -> byId[p.bookId]?.let { BookWithProgress(it.toModel(), p.toModel()) } }
                .take(limit)
        }

    /** Zuletzt hinzugefügte Bücher, die noch nicht angefangen sind. */
    fun recentlyAdded(limit: Int = 12): Flow<List<BookWithProgress>> =
        books().map { list ->
            list.filter { (it.progress?.positionMs ?: 0L) == 0L }
                .sortedByDescending { it.book.addedAt }
                .take(limit)
        }

    fun book(bookId: Long): Flow<BookWithProgress?> =
        combine(dao.observeBook(bookId), dao.observeProgress(bookId)) { book, progress ->
            book?.let { BookWithProgress(it.toModel(), progress?.toModel()) }
        }

    fun chapters(bookId: Long): Flow<List<BookChapter>> = dao.observeChapters(bookId).map { list -> list.map { it.toModel() } }

    suspend fun bookNow(bookId: Long): Book? = dao.book(bookId)?.toModel()

    suspend fun progressNow(bookId: Long): BookProgress? = dao.progress(bookId)?.toModel()

    /** Zeitachse mit Dateien und Kapiteln; lädt sie vom Server, falls sie noch fehlt. `null`, wenn das nicht gelingt. */
    suspend fun timeline(bookId: Long): BookTimeline? {
        val book = dao.book(bookId) ?: return null
        if (!syncer.ensureDetails(bookId)) return null
        val tracks = dao.tracks(bookId).map { it.toModel() }
        if (tracks.isEmpty()) return null
        return BookTimeline(book.durationMs, tracks, dao.chapters(bookId).map { it.toModel() })
    }

    /** Dateien des Buchs mit ihren Server-IDs, für die Medien-URLs. */
    suspend fun trackFiles(bookId: Long): List<BookTrack> = dao.tracks(bookId).map { it.toModel() }

    suspend fun search(query: String, limit: Int = 20): List<BookWithProgress> {
        val normalized = query.normalizeForMerge()
        if (normalized.isEmpty()) return emptyList()
        val pattern = "%${normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        return dao.search(pattern, limit).map { BookWithProgress(it.toModel(), dao.progress(it.bookId)?.toModel()) }
    }

    /** Merkt die Geschwindigkeit des Buchs, auch wenn es noch keinen Fortschritt hat (der Server gewinnt dann sofort). */
    suspend fun setSpeed(bookId: Long, speed: Float) {
        if (dao.progress(bookId) != null) {
            dao.setSpeed(bookId, speed)
        } else {
            val duration = dao.book(bookId)?.durationMs ?: return
            dao.upsertProgress(BookProgressEntity(bookId, positionMs = 0, durationMs = duration, speed = speed, updatedAt = 0))
        }
    }
}

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
)

internal fun BookChapterEntity.toModel() = BookChapter(chapterIndex, title, startMs, endMs)

internal fun BookTrackEntity.toModel() = BookTrack(trackIndex, remoteFileId, startMs, durationMs, mimeType)

internal fun BookProgressEntity.toModel() = BookProgress(positionMs, durationMs, finished, speed, updatedAt, dirty)
