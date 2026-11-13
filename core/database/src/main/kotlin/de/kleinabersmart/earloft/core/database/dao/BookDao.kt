package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.SpokenServerEntity
import de.kleinabersmart.earloft.core.database.model.BookCoverRow
import de.kleinabersmart.earloft.core.model.AuthorHit
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.TrackDownload
import kotlinx.coroutines.flow.Flow

/** Bücher (und Folgen) der ausgeschalteten Audiobookshelf-Server sind ausgeblendet; Podcast-Feeds stehen nicht in `spoken_servers`. */
private const val BOOK_VISIBLE = "serverId NOT IN (SELECT serverId FROM spoken_servers WHERE enabled = 0)"

// Folgen von Probeabos aus der Podcast-Vorschau (Konzept 12.10) haben eine Seite, stehen aber in keiner Liste und Suche;
// Folgen archivierter Abos ebenso wenig.
private const val BOOK_LISTED =
    "$BOOK_VISIBLE AND serverId NOT IN (SELECT feedId FROM podcast_feeds WHERE subscribed = 0 OR archived = 1)"

private const val TRACK_TOTALS =
    "SELECT COALESCE(SUM(CASE WHEN downloadState = 'DONE' THEN fileSize ELSE 0 END), 0) AS doneBytes, " +
        "COALESCE(SUM(downloadState IN ('QUEUED', 'RUNNING')), 0) AS pendingCount FROM book_tracks"

@Dao
abstract class BookDao {

    // --- Server ---

    @Query("SELECT * FROM spoken_servers ORDER BY name")
    abstract fun observeServers(): Flow<List<SpokenServerEntity>>

    @Query("SELECT * FROM spoken_servers")
    abstract suspend fun servers(): List<SpokenServerEntity>

    @Query("SELECT * FROM spoken_servers WHERE serverId = :serverId")
    abstract suspend fun server(serverId: String): SpokenServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertServer(server: SpokenServerEntity)

    @Query("UPDATE spoken_servers SET enabled = :enabled WHERE serverId = :serverId")
    abstract suspend fun setServerEnabled(serverId: String, enabled: Boolean)

    @Query("DELETE FROM spoken_servers WHERE serverId = :serverId")
    abstract suspend fun deleteServerRow(serverId: String)

    @Query("DELETE FROM books WHERE serverId = :serverId")
    abstract suspend fun deleteBooksOf(serverId: String)

    @Transaction
    open suspend fun deleteServer(serverId: String) {
        deleteBooksOf(serverId)
        deleteServerRow(serverId)
    }

    // --- Bücher ---

    @Query("SELECT * FROM books WHERE $BOOK_LISTED ORDER BY normTitle")
    abstract fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE bookId = :bookId AND $BOOK_VISIBLE")
    abstract fun observeBook(bookId: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE bookId = :bookId")
    abstract suspend fun book(bookId: Long): BookEntity?

    @Query("SELECT * FROM books WHERE serverId = :serverId AND remoteId = :remoteId")
    abstract suspend fun bookByRemote(serverId: String, remoteId: String): BookEntity?

    @Query("SELECT bookId FROM books WHERE serverId = :serverId AND remoteId = :remoteId")
    abstract fun observeBookIdByRemote(serverId: String, remoteId: String): Flow<Long?>

    @Query("SELECT * FROM books WHERE serverId = :serverId")
    abstract suspend fun booksOf(serverId: String): List<BookEntity>

    /** Alle Bücher und Folgen, auch ausgeblendete: Ihre Bilder bleiben auf dem Gerät, solange es sie gibt. */
    @Query("SELECT bookId, serverId, kind, coverUrl FROM books")
    abstract suspend fun coverRows(): List<BookCoverRow>

    /** Bücher bzw. Folgen [kind] in den Listen, neueste zuerst (deren Bilder lädt die App zuerst für offline). */
    @Query("SELECT bookId, serverId, kind, coverUrl FROM books WHERE kind = :kind AND $BOOK_LISTED ORDER BY addedAt DESC")
    abstract suspend fun listedCoverRows(kind: BookKind): List<BookCoverRow>

    /** Autoren wie in der Bibliothek (`authorGroups`): je Buch der ganze Eintrag. */
    @Query("SELECT DISTINCT TRIM(author) FROM books WHERE kind = 'BOOK' AND TRIM(author) != '' AND $BOOK_LISTED")
    abstract suspend fun listedAuthors(): List<String>

    /** Ändert sich, sobald Bücher, Folgen oder Abos hinzukommen, wegfallen oder ein anderes Bild bekommen. */
    @Query(
        "SELECT (SELECT COUNT(*) || ':' || IFNULL(MAX(bookId), 0) || ':' || IFNULL(SUM(LENGTH(coverUrl)), 0) FROM books) || '/' || " +
            "(SELECT COUNT(*) || ':' || IFNULL(SUM(LENGTH(imageUrl)), 0) FROM podcast_feeds)",
    )
    abstract fun observeImageStamp(): Flow<String>

    /** Titel, Autor und – bei Hörbüchern wählen viele danach – Sprecher. */
    @Query(
        "SELECT * FROM books WHERE kind = :kind AND (normTitle LIKE :pattern ESCAPE '\\' OR author LIKE :pattern ESCAPE '\\' " +
            "OR narrator LIKE :pattern ESCAPE '\\') AND $BOOK_LISTED ORDER BY normTitle LIMIT :limit",
    )
    abstract suspend fun search(kind: BookKind, pattern: String, limit: Int): List<BookEntity>

    /** Bücher, in deren Serienangabe [pattern] vorkommt; welche Serie genau passt, entscheidet der Aufrufer. */
    @Query("SELECT * FROM books WHERE kind = 'BOOK' AND series LIKE :pattern ESCAPE '\\' AND $BOOK_VISIBLE ORDER BY normTitle")
    abstract suspend fun searchSeriesRows(pattern: String): List<BookEntity>

    /** Autoren von Büchern, deren Name zu [pattern] passt, mit dem kleinsten Buch (für das Cover) und der Anzahl. */
    @Query(
        "SELECT author AS name, MIN(bookId) AS firstBookId, COUNT(*) AS bookCount FROM books " +
            "WHERE kind = 'BOOK' AND author != '' AND author LIKE :pattern ESCAPE '\\' AND $BOOK_VISIBLE " +
            "GROUP BY author ORDER BY author LIMIT :limit",
    )
    abstract suspend fun searchAuthors(pattern: String, limit: Int): List<AuthorHit>

    /** Sprecher von Büchern wie [searchAuthors]. */
    @Query(
        "SELECT narrator AS name, MIN(bookId) AS firstBookId, COUNT(*) AS bookCount FROM books " +
            "WHERE kind = 'BOOK' AND narrator != '' AND narrator LIKE :pattern ESCAPE '\\' AND $BOOK_VISIBLE " +
            "GROUP BY narrator ORDER BY narrator LIMIT :limit",
    )
    abstract suspend fun searchNarrators(pattern: String, limit: Int): List<AuthorHit>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertBook(book: BookEntity): Long

    @Update
    abstract suspend fun updateBook(book: BookEntity)

    /** Legt das Buch an oder aktualisiert es, die lokale ID bleibt stabil. Unverändert bleibt es ungeschrieben (Abgleiche). */
    @Transaction
    open suspend fun upsertBook(book: BookEntity): Long {
        val existing = bookByRemote(book.serverId, book.remoteId)
        return if (existing == null) {
            insertBook(book)
        } else {
            val updated = book.copy(bookId = existing.bookId, detailsLoaded = existing.detailsLoaded && existing.remoteUpdatedAt == book.remoteUpdatedAt)
            if (updated != existing) updateBook(updated)
            existing.bookId
        }
    }

    /** Das Buch ist unverändert und zählt für diesen Abgleich als gesehen. */
    @Query("UPDATE books SET seenInGeneration = :generation WHERE serverId = :serverId AND remoteId = :remoteId")
    abstract suspend fun markSeen(serverId: String, remoteId: String, generation: Long)

    /** Bücher, die der letzte Vollabgleich nicht mehr gesehen hat, sind auf dem Server gelöscht (Mark-and-Sweep). */
    @Query("DELETE FROM books WHERE serverId = :serverId AND seenInGeneration < :generation")
    abstract suspend fun deleteUnseen(serverId: String, generation: Long)

    /** Uhr: Hörbücher, die nicht mehr im Manifest des Handys stehen (Wear-Konzept 16.2), über alle Server. */
    @Query("DELETE FROM books WHERE kind = 'BOOK' AND seenInGeneration < :generation")
    abstract suspend fun deleteUnseenBooks(generation: Long)

    @Query("SELECT MAX(IFNULL((SELECT MAX(seenInGeneration) FROM books), 0) + 1, :nowMs)")
    protected abstract suspend fun nextGeneration(nowMs: Long): Long

    /**
     * Generation für einen Abgleich ohne eigenen Zähler (Bücher und Podcasts auf dem Gerät, Spiegel der Uhr): die Uhrzeit, aber
     * immer größer als jede gespeicherte. Geht die Uhr nach (Zeitabgleich), bliebe sonst bei [deleteUnseen] stehen, was weg ist.
     */
    suspend fun nextGeneration(): Long = nextGeneration(System.currentTimeMillis())

    // --- Dateien und Kapitel ---

    @Query("SELECT * FROM book_tracks WHERE bookId = :bookId ORDER BY trackIndex")
    abstract suspend fun tracks(bookId: Long): List<BookTrackEntity>

    @Query("SELECT * FROM book_tracks WHERE bookId = :bookId ORDER BY trackIndex")
    abstract fun observeTracks(bookId: Long): Flow<List<BookTrackEntity>>

    @Query("SELECT * FROM book_chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    abstract suspend fun chapters(bookId: Long): List<BookChapterEntity>

    @Query("SELECT * FROM book_chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    abstract fun observeChapters(bookId: Long): Flow<List<BookChapterEntity>>

    @Query("DELETE FROM book_tracks WHERE bookId = :bookId")
    abstract suspend fun deleteTracks(bookId: Long)

    @Query("DELETE FROM book_chapters WHERE bookId = :bookId")
    abstract suspend fun deleteChapters(bookId: Long)

    @Insert
    abstract suspend fun insertTracks(tracks: List<BookTrackEntity>)

    @Insert
    abstract suspend fun insertChapters(chapters: List<BookChapterEntity>)

    @Query("UPDATE books SET durationMs = :durationMs WHERE bookId = :bookId")
    abstract suspend fun setBookDuration(bookId: Long, durationMs: Long)

    @Query("UPDATE book_tracks SET durationMs = :durationMs WHERE bookId = :bookId AND trackIndex = 0")
    abstract suspend fun setFirstTrackDuration(bookId: Long, durationMs: Long)

    /** Das letzte Kapitel reicht bis zum Ende des Buchs. */
    @Query(
        "UPDATE book_chapters SET endMs = :durationMs WHERE bookId = :bookId AND chapterIndex = " +
            "(SELECT MAX(chapterIndex) FROM book_chapters WHERE bookId = :bookId) AND startMs < :durationMs",
    )
    abstract suspend fun stretchLastChapter(bookId: Long, durationMs: Long)

    @Transaction
    open suspend fun setSingleFileDuration(bookId: Long, durationMs: Long) {
        setBookDuration(bookId, durationMs)
        setFirstTrackDuration(bookId, durationMs)
        stretchLastChapter(bookId, durationMs)
    }

    @Query("UPDATE books SET detailsLoaded = 1 WHERE bookId = :bookId")
    abstract suspend fun markDetailsLoaded(bookId: Long)

    /**
     * Ersetzt Dateien und Kapitel. Der Download-Stand einer Datei bleibt, wenn sie gleich geblieben ist (gleiche
     * Kennung und Länge) – sonst verlöre jede Metadaten-Änderung auf dem Server den Download des Buchs.
     */
    @Transaction
    open suspend fun replaceDetails(bookId: Long, tracks: List<BookTrackEntity>, chapters: List<BookChapterEntity>) {
        val previous = tracks(bookId).associateBy { it.remoteFileId }
        deleteTracks(bookId)
        deleteChapters(bookId)
        insertTracks(
            tracks.map { track ->
                val old = previous[track.remoteFileId]
                if (old == null || old.downloadState == TrackDownload.NONE || old.durationMs != track.durationMs) {
                    track
                } else {
                    track.copy(downloadState = old.downloadState, filePath = old.filePath, fileSize = old.fileSize)
                }
            },
        )
        insertChapters(chapters)
        markDetailsLoaded(bookId)
    }

    // --- Fortschritt ---

    @Query("SELECT * FROM book_progress")
    abstract fun observeAllProgress(): Flow<List<BookProgressEntity>>

    @Query("SELECT * FROM book_progress WHERE bookId = :bookId")
    abstract fun observeProgress(bookId: Long): Flow<BookProgressEntity?>

    @Query("SELECT * FROM book_progress WHERE bookId = :bookId")
    abstract suspend fun progress(bookId: Long): BookProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertProgress(progress: BookProgressEntity)

    /** Mehrere Stände in einem Zug (eine Änderungsmeldung statt einer je Zeile). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAllProgress(progress: List<BookProgressEntity>)

    @Query("SELECT * FROM book_progress WHERE dirty = 1")
    abstract suspend fun dirtyProgress(): List<BookProgressEntity>

    /** Nur wenn sich der Eintrag seit dem Senden nicht geändert hat; sonst bleibt er für die nächste Runde markiert. */
    @Query("UPDATE book_progress SET dirty = 0 WHERE bookId = :bookId AND updatedAt = :updatedAt")
    abstract suspend fun markClean(bookId: Long, updatedAt: Long)

    @Query("UPDATE book_progress SET speed = :speed WHERE bookId = :bookId")
    abstract suspend fun setSpeed(bookId: Long, speed: Float)

    @Query("SELECT * FROM book_progress WHERE bookId IN (:bookIds)")
    abstract suspend fun progressOf(bookIds: List<Long>): List<BookProgressEntity>

    /** Aller Fortschritt samt dem geräteübergreifenden Schlüssel seines Buchs (Backup, Review K9). */
    @Query("SELECT b.serverId AS serverId, b.remoteId AS remoteId, p.* FROM book_progress p JOIN books b ON b.bookId = p.bookId")
    abstract suspend fun keyedProgress(): List<KeyedProgress>

    // --- Schlüssel der Bücher: (serverId, remoteId) bleibt auf einem neuen Gerät gleich, die bookId nicht ---

    @Query("SELECT bookId, serverId, remoteId FROM books")
    abstract fun observeBookRefs(): Flow<List<BookRef>>

    @Query("SELECT bookId, serverId, remoteId FROM books WHERE bookId IN (:bookIds)")
    abstract suspend fun bookRefsOf(bookIds: List<Long>): List<BookRef>

    @Query("SELECT bookId, serverId, remoteId FROM books WHERE serverId = :serverId AND remoteId IN (:remoteIds)")
    abstract suspend fun bookRefs(serverId: String, remoteIds: List<String>): List<BookRef>

    // --- Downloads der Bücher (je Datei; Folgen laufen über den PodcastDao) ---

    /** Download-Stand aller Bücher mit Dateien, zusammengezählt über deren Tracks. */
    @Query(
        "SELECT t.bookId AS bookId, COUNT(*) AS total, " +
            "SUM(t.downloadState = 'DONE') AS done, " +
            "SUM(t.downloadState = 'RUNNING') AS running, " +
            "SUM(t.downloadState = 'QUEUED') AS queued, " +
            "SUM(t.downloadState = 'FAILED') AS failed, " +
            "SUM(t.downloadState = 'REMOVED') AS removed, " +
            "SUM(t.fileSize) AS bytes " +
            "FROM book_tracks t JOIN books b ON b.bookId = t.bookId WHERE b.kind = 'BOOK' GROUP BY t.bookId",
    )
    abstract fun observeBookDownloads(): Flow<List<BookDownloadRow>>

    /** Von Hand: auch fehlgeschlagene und entfernte Dateien laden wieder; fertige bleiben. */
    @Query("UPDATE book_tracks SET downloadState = 'QUEUED' WHERE bookId = :bookId AND downloadState IN ('NONE', 'FAILED', 'REMOVED')")
    abstract suspend fun queueBookDownload(bookId: Long): Int

    @Query(
        "SELECT t.* FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'BOOK' AND t.downloadState = 'QUEUED' ORDER BY t.bookId, t.trackIndex LIMIT 1",
    )
    abstract suspend fun nextQueuedBookTrack(): BookTrackEntity?

    @Query(
        "SELECT COUNT(*) FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'BOOK' AND t.downloadState IN ('QUEUED', 'RUNNING')",
    )
    abstract suspend fun countPendingBookDownloads(): Int

    @Query("UPDATE book_tracks SET downloadState = 'RUNNING' WHERE bookId = :bookId AND trackIndex = :trackIndex AND downloadState = 'QUEUED'")
    abstract suspend fun markBookTrackRunning(bookId: Long, trackIndex: Int): Int

    @Query(
        "UPDATE book_tracks SET downloadState = 'QUEUED' WHERE downloadState = 'RUNNING' " +
            "AND bookId IN (SELECT bookId FROM books WHERE kind = 'BOOK')",
    )
    abstract suspend fun requeueRunningBookDownloads()

    @Query("UPDATE book_tracks SET downloadState = 'QUEUED' WHERE bookId = :bookId AND trackIndex = :trackIndex AND downloadState = 'RUNNING'")
    abstract suspend fun requeueBookTrack(bookId: Long, trackIndex: Int)

    /** Scheitert eine Datei, ist das Buch nicht brauchbar: die übrigen wartenden Dateien hören auf zu warten. */
    @Query(
        "UPDATE book_tracks SET downloadState = 'FAILED', filePath = NULL, fileSize = 0 " +
            "WHERE bookId = :bookId AND downloadState IN ('QUEUED', 'RUNNING')",
    )
    abstract suspend fun markBookDownloadFailed(bookId: Long)

    /** Uhr, „Erneut versuchen“: gescheiterte Dateien von Büchern und Folgen wieder zum Laden freigeben. @return Anzahl. */
    @Query("UPDATE book_tracks SET downloadState = 'NONE' WHERE downloadState = 'FAILED'")
    abstract suspend fun resetFailedDownloads(): Int

    /** `0`, wenn der Eintrag inzwischen entfernt oder zurückgesetzt wurde. */
    @Query(
        "UPDATE book_tracks SET downloadState = 'DONE', filePath = :path, fileSize = :size " +
            "WHERE bookId = :bookId AND trackIndex = :trackIndex AND downloadState = 'RUNNING'",
    )
    abstract suspend fun markBookTrackDone(bookId: Long, trackIndex: Int, path: String, size: Long): Int

    @Query("UPDATE book_tracks SET downloadState = 'REMOVED', filePath = NULL, fileSize = 0 WHERE bookId = :bookId AND downloadState != 'NONE'")
    abstract suspend fun markBookDownloadRemoved(bookId: Long)

    /** Wartende und laufende Dateien aller Bücher (Speicherplanung der Uhr). */
    @Query(
        "SELECT t.* FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'BOOK' AND t.downloadState IN ('QUEUED', 'RUNNING')",
    )
    abstract suspend fun pendingBookTracks(): List<BookTrackEntity>

    /** Dateien aller Bücher, für das Aufräumen verwaister Dateien. */
    @Query(
        "SELECT t.* FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'BOOK' AND t.filePath IS NOT NULL",
    )
    abstract suspend fun downloadedBookTracks(): List<BookTrackEntity>

    /** Summen über die Dateien aller Bücher und Folgen (Speicheranzeige der Uhr). */
    @Query(TRACK_TOTALS)
    abstract fun observeTrackTotals(): Flow<TrackTotalsRow>

    @Query(TRACK_TOTALS)
    abstract suspend fun trackTotals(): TrackTotalsRow

    /** Alle Bücher und Folgen mit ihren Dateien (Speicher-Ansicht der Uhr: dort liegen nur die gewählten). */
    @Query(
        "SELECT b.bookId AS bookId, b.kind AS kind, b.serverId AS serverId, b.remoteId AS remoteId, b.title AS title, " +
            "b.author AS author, COUNT(t.trackIndex) AS total, COALESCE(SUM(t.downloadState = 'DONE'), 0) AS done, " +
            "COALESCE(SUM(CASE WHEN t.downloadState = 'DONE' THEN t.fileSize ELSE 0 END), 0) AS doneBytes " +
            "FROM books b LEFT JOIN book_tracks t ON t.bookId = b.bookId GROUP BY b.bookId ORDER BY b.normTitle",
    )
    abstract fun observeStoredBooks(): Flow<List<StoredBookRow>>
}

/** Ein Buch bzw. eine Folge mit dem Stand seiner Dateien; [doneBytes] sind die fertig geladenen. */
data class StoredBookRow(
    val bookId: Long,
    val kind: BookKind,
    val serverId: String,
    val remoteId: String,
    val title: String,
    val author: String,
    val total: Int,
    val done: Int,
    val doneBytes: Long,
)

/** [doneBytes]: fertig geladen; [pendingCount]: wartende und laufende Dateien. */
data class TrackTotalsRow(val doneBytes: Long, val pendingCount: Int)

/** Summen über die Dateien eines Buchs; `bytes` sind die bisher fertig geladenen. */
data class BookDownloadRow(
    val bookId: Long,
    val total: Int,
    val done: Int,
    val running: Int,
    val queued: Int,
    val failed: Int,
    val removed: Int,
    val bytes: Long,
)

/** Ein Buch mit dem Schlüssel, der auf einem neuen Gerät gleich bleibt (Review K9). */
data class BookRef(val bookId: Long, val serverId: String, val remoteId: String)

/** Fortschritt mit dem Schlüssel seines Buchs. */
data class KeyedProgress(
    val serverId: String,
    val remoteId: String,
    @Embedded val progress: BookProgressEntity,
)
