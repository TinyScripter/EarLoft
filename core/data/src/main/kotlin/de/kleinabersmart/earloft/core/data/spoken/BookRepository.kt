package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
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
            books.filter { it.kind == BookKind.BOOK }.map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) }
        }

    /**
     * Gestartete, noch nicht beendete Bücher, zuletzt gestartete oder gehörte zuerst. Ein Buch zählt ab dem Start, auch
     * bei Position 0 (siehe [touchProgress]); herausgenommene ([BookProgressEntity.hidden]) fehlen, bis sie wieder laufen.
     */
    fun continueListening(limit: Int = 12, startOrder: Flow<List<Long>> = flowOf(emptyList())): Flow<List<BookWithProgress>> =
        combine(dao.observeBooks(), dao.observeAllProgress(), startOrder) { books, progress, order ->
            val byId = books.filter { it.kind == BookKind.BOOK }.associateBy { it.bookId }
            // Der Player schreibt den Stand des vorigen Buchs erst beim Wechsel (mit neuerem Zeitstempel); darum
            // gilt zuerst die Startreihenfolge und erst dahinter der Zeitpunkt der letzten Änderung.
            val rank = order.withIndex().associate { it.value to it.index }
            progress
                .filter { !it.finished && !it.hidden }
                .sortedWith(compareBy<BookProgressEntity> { rank[it.bookId] ?: Int.MAX_VALUE }.thenByDescending { it.updatedAt })
                .mapNotNull { p -> byId[p.bookId]?.let { BookWithProgress(it.toModel(), p.toModel()) } }
                .take(limit)
        }

    /** Zuletzt hinzugefügte Bücher, die noch nicht angefangen sind. */
    fun recentlyAdded(limit: Int = 12): Flow<List<BookWithProgress>> =
        books().map { list ->
            // Gestartete Bücher stehen in „Weiterhören“, nicht hier. Zurückgesetzte („ungehört“, Position 0) zählen wieder
            // als nicht angefangen; nur herausgenommene mit Stand bleiben in keiner der beiden Zeilen.
            list.filter { item -> item.progress.let { it == null || (it.hidden && it.positionMs == 0L && !it.finished) } }
                .sortedByDescending { it.book.addedAt }
                .take(limit)
        }

    /**
     * Das Buch wird gestartet: Es steht sofort ganz vorn in „Weiterhören“. Position und Geschwindigkeit bleiben, wie sie
     * nach dem Abgleich mit dem Server sind; der Zeitpunkt wird auf jetzt gesetzt.
     */
    suspend fun touchProgress(bookId: Long, startPositionMs: Long, durationMs: Long, speed: Float) {
        val old = dao.progress(bookId)
        dao.upsertProgress(
            old?.copy(positionMs = startPositionMs, finished = false, updatedAt = System.currentTimeMillis(), hidden = false)
                ?: BookProgressEntity(bookId, startPositionMs, durationMs, finished = false, speed = speed, updatedAt = System.currentTimeMillis()),
        )
    }

    fun book(bookId: Long): Flow<BookWithProgress?> =
        combine(dao.observeBook(bookId), dao.observeProgress(bookId)) { book, progress ->
            book?.let { BookWithProgress(it.toModel(), progress?.toModel()) }
        }

    fun chapters(bookId: Long): Flow<List<BookChapter>> = dao.observeChapters(bookId).map { list -> list.map { it.toModel() } }

    suspend fun bookNow(bookId: Long): Book? = dao.book(bookId)?.toModel()

    /** Buch oder Folge nach Server bzw. Abo und Kennung dort (das Handy nennt der Uhr nur diese, Wear-Konzept 17). */
    suspend fun idByRemote(serverId: String, remoteId: String): Long? = dao.bookByRemote(serverId, remoteId)?.bookId

    /** Wie [idByRemote], beobachtet; `null`, solange es das Buch bzw. die Folge hier nicht gibt. */
    fun observeIdByRemote(serverId: String, remoteId: String): Flow<Long?> =
        dao.observeBookIdByRemote(serverId, remoteId).distinctUntilChanged()

    /** Download-Stand aller Bücher; Bücher ohne geladene Dateiliste fehlen (= [TrackDownload.NONE]). */
    fun downloads(): Flow<Map<Long, BookDownload>> =
        dao.observeBookDownloads().map { rows -> rows.associate { it.bookId to BookDownload(it.state(), it.bytes) } }

    /** Download-Stand des Buchs; ohne geladene Dateiliste [TrackDownload.NONE]. */
    fun download(bookId: Long): Flow<BookDownload> =
        dao.observeBookDownloads().map { rows ->
            rows.firstOrNull { it.bookId == bookId }?.let { BookDownload(it.state(), it.bytes) } ?: BookDownload(TrackDownload.NONE, 0)
        }.distinctUntilChanged()

    suspend fun progressNow(bookId: Long): BookProgress? = dao.progress(bookId)?.toModel()

    /**
     * Zeitachse mit Dateien und Kapiteln; lädt sie vom Server, falls sie noch fehlt oder veraltet ist. Ist der Server
     * nicht erreichbar, gilt die vorhandene (ein geladenes Buch bleibt offline abspielbar). `null` ohne Dateien.
     */
    suspend fun timeline(bookId: Long): BookTimeline? {
        val book = dao.book(bookId) ?: return null
        syncer.ensureDetails(bookId)
        val tracks = dao.tracks(bookId).map { it.toModel() }
        if (tracks.isEmpty()) return null
        return BookTimeline(book.durationMs, tracks, dao.chapters(bookId).map { it.toModel() })
    }

    /** Dateien des Buchs mit ihren Server-IDs, für die Medien-URLs. */
    suspend fun trackFiles(bookId: Long): List<BookTrack> = dao.tracks(bookId).map { it.toModel() }

    /** Sucht Bücher ([BookKind.BOOK]) bzw. Podcast-Folgen ([BookKind.EPISODE]) nach Titel, Autor und Sprecher. */
    suspend fun search(query: String, kind: BookKind = BookKind.BOOK, limit: Int = 20): List<BookWithProgress> {
        val pattern = likePattern(query) ?: return emptyList()
        return dao.search(kind, pattern, limit).map { BookWithProgress(it.toModel(), dao.progress(it.bookId)?.toModel()) }
    }

    /** Sucht Autoren von Büchern nach dem Namen. */
    suspend fun searchAuthors(query: String, limit: Int = 20): List<AuthorHit> {
        val pattern = likePattern(query) ?: return emptyList()
        return dao.searchAuthors(pattern, limit)
    }

    /** Sucht Serien nach dem Namen. */
    suspend fun searchSeries(query: String, limit: Int = 20): List<SeriesHit> {
        val pattern = likePattern(query) ?: return emptyList()
        return seriesHits(dao.searchSeriesRows(pattern).map { it.toModel() }, query.normalizeForMerge(), limit)
    }

    private fun likePattern(query: String): String? {
        val normalized = query.normalizeForMerge()
        if (normalized.isEmpty()) return null
        return "%${normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
    }

    /**
     * Folgen: Der Feed nennt die Dauer nicht immer oder falsch; die Datei kennt sie. Übernimmt die vom Player gelernte
     * Dauer, wenn sie spürbar abweicht. `true`, wenn sich etwas geändert hat.
     */
    suspend fun learnEpisodeDuration(bookId: Long, durationMs: Long): Boolean {
        val book = dao.book(bookId) ?: return false
        if (book.kind != BookKind.EPISODE || durationMs <= 0 || kotlin.math.abs(book.durationMs - durationMs) <= DURATION_TOLERANCE_MS) return false
        dao.setSingleFileDuration(bookId, durationMs)
        return true
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
)

internal fun BookChapterEntity.toModel() = BookChapter(chapterIndex, title, startMs, endMs)

internal fun BookTrackEntity.toModel() = BookTrack(
    trackIndex,
    remoteFileId,
    startMs,
    durationMs,
    mimeType,
    localPath = filePath.takeIf { downloadState == TrackDownload.DONE },
)

internal fun BookProgressEntity.toModel() = BookProgress(positionMs, durationMs, finished, speed, updatedAt, dirty, hidden)
