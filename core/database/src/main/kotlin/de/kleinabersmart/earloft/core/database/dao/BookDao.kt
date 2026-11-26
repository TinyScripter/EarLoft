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
import de.kleinabersmart.earloft.core.database.entity.BookmarkEntity
import de.kleinabersmart.earloft.core.database.entity.EpisodePersonEntity
import de.kleinabersmart.earloft.core.database.entity.SpokenAuthorEntity
import de.kleinabersmart.earloft.core.database.entity.SpokenLibraryEntity
import de.kleinabersmart.earloft.core.database.entity.SpokenServerEntity
import de.kleinabersmart.earloft.core.database.model.BookCoverRow
import de.kleinabersmart.earloft.core.database.model.BookPeopleRow
import de.kleinabersmart.earloft.core.database.model.BookSearchRow
import de.kleinabersmart.earloft.core.database.model.BookSeriesRow
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.ChapterSource
import de.kleinabersmart.earloft.core.model.TrackDownload
import kotlinx.coroutines.flow.Flow

/**
 * Books (and episodes) of disabled Audiobookshelf servers are hidden, as are books of deselected libraries
 * (they stay stored until the library is selected again); podcast feeds are not in `spoken_servers`.
 */
private const val BOOK_VISIBLE = "serverId NOT IN (SELECT serverId FROM spoken_servers WHERE enabled = 0) AND (libraryId IS NULL OR " +
    "NOT EXISTS (SELECT 1 FROM spoken_servers s WHERE s.serverId = books.serverId AND " +
    "instr(',' || s.hiddenLibraries || ',', ',' || books.libraryId || ',') > 0))"

// Episodes of trial subscriptions from the podcast preview (concept 12.10) have a page but appear in no list or search;
// neither do episodes of archived subscriptions.
private const val BOOK_LISTED =
    "$BOOK_VISIBLE AND serverId NOT IN (SELECT feedId FROM podcast_feeds WHERE subscribed = 0 OR archived = 1)"

private const val TRACK_TOTALS =
    "SELECT COALESCE(SUM(CASE WHEN downloadState = 'DONE' THEN fileSize ELSE 0 END), 0) AS doneBytes, " +
        "COALESCE(SUM(downloadState IN ('QUEUED', 'RUNNING')), 0) AS pendingCount, " +
        "COALESCE(SUM(downloadState = 'FAILED'), 0) AS failedCount FROM book_tracks"

@Dao
abstract class BookDao {

    // --- Servers ---

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

    @Query("UPDATE spoken_servers SET hiddenLibraries = :hidden WHERE serverId = :serverId")
    abstract suspend fun setHiddenLibraries(serverId: String, hidden: String)

    @Query("SELECT * FROM spoken_libraries ORDER BY serverId, position")
    abstract fun observeLibraries(): Flow<List<SpokenLibraryEntity>>

    @Query("SELECT * FROM spoken_libraries WHERE serverId = :serverId ORDER BY position")
    abstract suspend fun libraries(serverId: String): List<SpokenLibraryEntity>

    @Query("DELETE FROM spoken_libraries WHERE serverId = :serverId")
    abstract suspend fun deleteLibraries(serverId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertLibraries(libraries: List<SpokenLibraryEntity>)

    /** The server's book libraries, as it currently names them. */
    @Transaction
    open suspend fun replaceLibraries(serverId: String, libraries: List<SpokenLibraryEntity>) {
        deleteLibraries(serverId)
        insertLibraries(libraries)
    }

    @Query("DELETE FROM spoken_authors WHERE serverId = :serverId")
    abstract suspend fun deleteAuthors(serverId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAuthors(authors: List<SpokenAuthorEntity>)

    /** The authors of the server's synced libraries. */
    @Transaction
    open suspend fun replaceAuthors(serverId: String, authors: List<SpokenAuthorEntity>) {
        deleteAuthors(serverId)
        insertAuthors(authors)
    }

    /** Authors of this name on the enabled servers (a name can appear on several). */
    @Query(
        "SELECT * FROM spoken_authors WHERE normName = :normName AND " +
            "serverId NOT IN (SELECT serverId FROM spoken_servers WHERE enabled = 0) ORDER BY serverId",
    )
    abstract fun observeAuthors(normName: String): Flow<List<SpokenAuthorEntity>>

    @Query("DELETE FROM spoken_servers WHERE serverId = :serverId")
    abstract suspend fun deleteServerRow(serverId: String)

    @Query("DELETE FROM books WHERE serverId = :serverId")
    abstract suspend fun deleteBooksOf(serverId: String)

    /** After a full sync of an Audiobookshelf server; `0` if it was removed in the meantime (an upsert would bring it back). */
    @Query("UPDATE spoken_servers SET libraryId = :libraryId, syncGeneration = :generation, lastFullSyncAt = :syncedAt WHERE serverId = :serverId")
    abstract suspend fun finishFullSync(serverId: String, libraryId: String, generation: Long, syncedAt: Long): Int

    @Transaction
    open suspend fun deleteServer(serverId: String) {
        deleteBooksOf(serverId)
        deleteLibraries(serverId)
        deleteAuthors(serverId)
        deleteServerRow(serverId)
    }

    // --- Books ---

    /** Books only: episodes are in the same table, and with their show notes that would quickly be dozens of MB per observer. */
    @Query("SELECT * FROM books WHERE kind = 'BOOK' AND $BOOK_LISTED ORDER BY normTitle")
    abstract fun observeBooks(): Flow<List<BookEntity>>

    /** Whether [observeBooks] has anything, without loading the books (review4 Y21). */
    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE kind = 'BOOK' AND $BOOK_LISTED)")
    abstract fun observeHasBooks(): Flow<Boolean>

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

    /** The IDs of a source's books (ARD Sounds catalog: which books are already in the library?). */
    @Query("SELECT bookId, serverId, remoteId FROM books WHERE serverId = :serverId")
    abstract fun observeBookRefsOf(serverId: String): Flow<List<BookRef>>

    /** The books of a source with the time since which they have been in the library (backup). */
    @Query("SELECT remoteId, addedAt FROM books WHERE serverId = :serverId")
    abstract suspend fun keyedBooksOf(serverId: String): List<KeyedBook>

    /** The files of all books of [serverId] with the name of their chapter, per book in their order. */
    @Query(
        "SELECT b.remoteId AS remoteId, t.trackIndex AS trackIndex, t.remoteFileId AS remoteFileId, t.durationMs AS durationMs, " +
            "t.mimeType AS mimeType, t.unavailableSince AS unavailableSince, c.title AS title, t.remoteItemId AS remoteItemId " +
            "FROM books b JOIN book_tracks t ON t.bookId = b.bookId " +
            "LEFT JOIN book_chapters c ON c.bookId = t.bookId AND c.chapterIndex = t.trackIndex " +
            "WHERE b.serverId = :serverId ORDER BY b.remoteId, t.trackIndex",
    )
    abstract suspend fun keyedPartsOf(serverId: String): List<KeyedBookPart>

    /** A book together with files, chapters, progress and bookmarks (cascade); the statistics stay. */
    @Query("DELETE FROM books WHERE bookId = :bookId")
    abstract suspend fun deleteBook(bookId: Long)

    /** All books and episodes, including hidden ones: their images stay on the device as long as they exist. */
    @Query("SELECT bookId, serverId, kind, coverUrl FROM books")
    abstract suspend fun coverRows(): List<BookCoverRow>

    /** Books or episodes [kind] in the lists, newest first (the app loads their images first for offline use). */
    @Query("SELECT bookId, serverId, kind, coverUrl FROM books WHERE kind = :kind AND $BOOK_LISTED ORDER BY addedAt DESC")
    abstract suspend fun listedCoverRows(kind: BookKind): List<BookCoverRow>

    /** Author entries of the books in the lists, the whole entry per book; `bookAuthors` splits the names. */
    @Query("SELECT DISTINCT TRIM(author) FROM books WHERE kind = 'BOOK' AND TRIM(author) != '' AND $BOOK_LISTED")
    abstract suspend fun listedAuthors(): List<String>

    /** Changes as soon as books, episodes or subscriptions are added, removed or get a different image. */
    @Query(
        "SELECT (SELECT COUNT(*) || ':' || IFNULL(MAX(bookId), 0) || ':' || IFNULL(SUM(LENGTH(coverUrl)), 0) FROM books) || '/' || " +
            "(SELECT COUNT(*) || ':' || IFNULL(SUM(LENGTH(imageUrl)), 0) FROM podcast_feeds)",
    )
    abstract fun observeImageStamp(): Flow<String>

    /**
     * What the search compares for all listed entries of [kind]: title, author and – many choose audiobooks by this –
     * narrator. Compared normalized in Kotlin (review G4): only `normTitle` is normalized in the database, a `LIKE` with the
     * normalized search term would never find "Stephen King" or "Jörg" in `author`.
     */
    @Query("SELECT bookId, normTitle, author, narrator FROM books WHERE kind = :kind AND $BOOK_LISTED")
    abstract suspend fun searchRows(kind: BookKind): List<BookSearchRow>

    @Query("SELECT * FROM books WHERE bookId IN (:bookIds)")
    abstract suspend fun booksByIds(bookIds: List<Long>): List<BookEntity>

    /** Books in a series, with the whole series entry; the caller decides which series matches. */
    @Query("SELECT bookId, title, series FROM books WHERE kind = 'BOOK' AND series != '' AND $BOOK_VISIBLE")
    abstract suspend fun seriesRows(): List<BookSeriesRow>

    /**
     * Books with an author entry, with the whole entry; the caller decides which of the names matches (a book can have
     * several authors).
     */
    @Query("SELECT bookId, author AS names FROM books WHERE kind = 'BOOK' AND author != '' AND $BOOK_VISIBLE")
    abstract suspend fun authorRows(): List<BookPeopleRow>

    /** Narrators like [authorRows]. */
    @Query("SELECT bookId, narrator AS names FROM books WHERE kind = 'BOOK' AND narrator != '' AND $BOOK_VISIBLE")
    abstract suspend fun narratorRows(): List<BookPeopleRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertBook(book: BookEntity): Long

    @Update
    abstract suspend fun updateBook(book: BookEntity)

    /**
     * Inserts or updates the book; the local ID stays stable. If unchanged, it is not written (syncs).
     * The star ([BookEntity.starredAt]) belongs to the user and stays.
     */
    @Transaction
    open suspend fun upsertBook(book: BookEntity): Long {
        val existing = bookByRemote(book.serverId, book.remoteId)
        return if (existing == null) {
            insertBook(book)
        } else {
            val updated = book.copy(
                bookId = existing.bookId,
                detailsLoaded = existing.detailsLoaded && existing.remoteUpdatedAt == book.remoteUpdatedAt,
                starredAt = existing.starredAt,
            )
            if (updated != existing) updateBook(updated)
            existing.bookId
        }
    }

    /** The book is unchanged and counts as seen for this sync. */
    @Query("UPDATE books SET seenInGeneration = :generation WHERE serverId = :serverId AND remoteId = :remoteId")
    abstract suspend fun markSeen(serverId: String, remoteId: String, generation: Long)

    /** Books the last full sync no longer saw have been deleted on the server (mark-and-sweep). */
    @Query("SELECT COUNT(*) FROM books WHERE serverId = :serverId")
    abstract suspend fun countBooksOf(serverId: String): Int

    /** Entries of all sources whose ID starts with [prefix] (the podcasts on the device). */
    @Query("SELECT COUNT(*) FROM books WHERE serverId LIKE :prefix || '%'")
    abstract suspend fun countBooksWithServerPrefix(prefix: String): Int

    @Query("DELETE FROM books WHERE serverId = :serverId AND seenInGeneration < :generation")
    abstract suspend fun deleteUnseen(serverId: String, generation: Long)

    /**
     * Like [deleteUnseen], but the books of the libraries [keptLibraries] stay: deselected ones are not synced and
     * keep their books including downloads, progress and bookmarks (hidden as with a disabled server).
     */
    @Query(
        "DELETE FROM books WHERE serverId = :serverId AND seenInGeneration < :generation AND " +
            "(libraryId IS NULL OR libraryId NOT IN (:keptLibraries))",
    )
    abstract suspend fun deleteUnseenOutside(serverId: String, generation: Long, keptLibraries: List<String>)

    /** Watch: audiobooks that are no longer in the phone's manifest (Wear concept 16.2), across all servers. */
    @Query("DELETE FROM books WHERE kind = 'BOOK' AND seenInGeneration < :generation")
    abstract suspend fun deleteUnseenBooks(generation: Long)

    @Query("SELECT MAX(IFNULL((SELECT MAX(seenInGeneration) FROM books), 0) + 1, :nowMs)")
    protected abstract suspend fun nextGeneration(nowMs: Long): Long

    /**
     * Generation for a sync without its own counter (books and podcasts on the device, watch mirror): the clock time, but
     * always greater than any stored one. If the clock is behind (time sync), [deleteUnseen] would otherwise keep what is gone.
     */
    suspend fun nextGeneration(): Long = nextGeneration(System.currentTimeMillis())

    // --- Files and chapters ---

    @Query("SELECT * FROM book_tracks WHERE bookId = :bookId ORDER BY trackIndex")
    abstract suspend fun tracks(bookId: Long): List<BookTrackEntity>

    @Query("SELECT * FROM book_tracks WHERE bookId = :bookId ORDER BY trackIndex")
    abstract fun observeTracks(bookId: Long): Flow<List<BookTrackEntity>>

    @Query("SELECT * FROM book_chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    abstract suspend fun chapters(bookId: Long): List<BookChapterEntity>

    @Query("SELECT * FROM book_chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    abstract fun observeChapters(bookId: Long): Flow<List<BookChapterEntity>>

    @Query("SELECT * FROM episode_persons WHERE bookId = :bookId ORDER BY personIndex")
    abstract fun observePersons(bookId: Long): Flow<List<EpisodePersonEntity>>

    /** Contributors of all episodes of a subscription, for the sync (one query instead of one per episode). */
    @Query(
        "SELECT p.* FROM episode_persons p JOIN books b ON b.bookId = p.bookId " +
            "WHERE b.serverId = :serverId ORDER BY p.bookId, p.personIndex",
    )
    abstract suspend fun personsOf(serverId: String): List<EpisodePersonEntity>

    @Query("DELETE FROM episode_persons WHERE bookId = :bookId")
    abstract suspend fun deletePersons(bookId: Long)

    @Insert
    abstract suspend fun insertPersons(persons: List<EpisodePersonEntity>)

    @Transaction
    open suspend fun replacePersons(bookId: Long, persons: List<EpisodePersonEntity>) {
        deletePersons(bookId)
        if (persons.isNotEmpty()) insertPersons(persons)
    }

    @Query("DELETE FROM book_tracks WHERE bookId = :bookId")
    abstract suspend fun deleteTracks(bookId: Long)

    /** Marks a file as no longer available ([since]) or available again (`null`); its download state stays. */
    @Query("UPDATE book_tracks SET unavailableSince = :since WHERE bookId = :bookId AND trackIndex = :trackIndex")
    abstract suspend fun setTrackUnavailable(bookId: Long, trackIndex: Int, since: Long?)

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

    /** The last chapter extends to the end of the book. */
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

    @Query("SELECT chapterSource FROM books WHERE bookId = :bookId")
    abstract suspend fun chapterSource(bookId: Long): ChapterSource?

    @Query("UPDATE books SET chapterSource = :source WHERE bookId = :bookId")
    abstract suspend fun setChapterSource(bookId: Long, source: ChapterSource?)

    /** Replaces an episode's chapters and remembers their source; empty [chapters] means the source has none. */
    @Transaction
    open suspend fun replaceChapters(bookId: Long, chapters: List<BookChapterEntity>, source: ChapterSource) {
        deleteChapters(bookId)
        insertChapters(chapters)
        setChapterSource(bookId, source)
    }

    /**
     * Replaces files and chapters. A file's download state stays if it has remained the same (same
     * ID and duration) – otherwise every metadata change on the server would lose the book's download.
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
                    track.copy(downloadState = old.downloadState, filePath = old.filePath, fileSize = old.fileSize, autoDownload = old.autoDownload)
                }
            },
        )
        insertChapters(chapters)
        markDetailsLoaded(bookId)
    }

    // --- Progress ---

    @Query("SELECT * FROM book_progress")
    abstract fun observeAllProgress(): Flow<List<BookProgressEntity>>

    @Query("SELECT * FROM book_progress WHERE bookId = :bookId")
    abstract fun observeProgress(bookId: Long): Flow<BookProgressEntity?>

    @Query("SELECT * FROM book_progress WHERE bookId = :bookId")
    abstract suspend fun progress(bookId: Long): BookProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertProgress(progress: BookProgressEntity)

    /**
     * Sets only position, state and flags of an existing entry; speed and effects stay as they currently are
     * (a read-and-write-back with [upsertProgress] would overwrite a change in between, [setEffects]).
     * `finishedAt` remembers when the book was finished: it stays as long as the state remains finished (even if `updatedAt`
     * advances e.g. through [hidden] or a sync), and is cleared on resuming.
     * @return 0 if there is no entry yet.
     */
    @Query(
        """
        UPDATE book_progress SET positionMs = :positionMs, durationMs = :durationMs,
            finishedAt = CASE WHEN :finished = 0 THEN NULL WHEN finished = 1 THEN COALESCE(finishedAt, updatedAt) ELSE :updatedAt END,
            finished = :finished, updatedAt = :updatedAt, dirty = :dirty, hidden = :hidden
        WHERE bookId = :bookId
        """,
    )
    abstract suspend fun updatePosition(
        bookId: Long,
        positionMs: Long,
        durationMs: Long,
        finished: Boolean,
        updatedAt: Long,
        dirty: Boolean,
        hidden: Boolean,
    ): Int

    /** Several states in one go (one change notification instead of one per row). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAllProgress(progress: List<BookProgressEntity>)

    @Query("SELECT * FROM book_progress WHERE dirty = 1")
    abstract suspend fun dirtyProgress(): List<BookProgressEntity>

    /** Only if the entry hasn't changed since it was sent; otherwise it stays marked for the next round. */
    @Query("UPDATE book_progress SET dirty = 0 WHERE bookId = :bookId AND updatedAt = :updatedAt")
    abstract suspend fun markClean(bookId: Long, updatedAt: Long)

    /** Own speed and own effects; `null` = those of the subscription or section. */
    @Query("UPDATE book_progress SET speed = :speed, skipSilence = :skipSilence, volumeBoostDb = :volumeBoostDb WHERE bookId = :bookId")
    abstract suspend fun setEffects(bookId: Long, speed: Float?, skipSilence: Boolean?, volumeBoostDb: Int?)

    @Query("SELECT * FROM book_progress WHERE bookId IN (:bookIds)")
    abstract suspend fun progressOf(bookIds: List<Long>): List<BookProgressEntity>

    /** All progress together with the cross-device key of its book (backup, review K9). */
    @Query("SELECT b.serverId AS serverId, b.remoteId AS remoteId, p.* FROM book_progress p JOIN books b ON b.bookId = p.bookId")
    abstract suspend fun keyedProgress(): List<KeyedProgress>

    /**
     * Like [keyedProgress], without episodes of trial subscriptions: those are not backed up, and their progress would stay in the
     * backup for good once the cleaner has deleted the trial (review4 T25).
     */
    @Query(
        "SELECT b.serverId AS serverId, b.remoteId AS remoteId, p.* FROM book_progress p JOIN books b ON b.bookId = p.bookId " +
            "WHERE b.serverId NOT IN (SELECT feedId FROM podcast_feeds WHERE subscribed = 0)",
    )
    abstract suspend fun keyedProgressToBackUp(): List<KeyedProgress>

    // --- Bookmarks (concept 12.6) ---

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND deleted = 0 ORDER BY positionMs, createdAt")
    abstract fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>>

    /**
     * All bookmarks in books or episodes ([kind]) with their book, newest first (collected list); without those of hidden
     * servers and libraries.
     */
    @Query(
        "SELECT books.title AS bookTitle, books.author AS bookAuthor, books.kind AS kind, m.* FROM bookmarks m " +
            "JOIN books ON books.bookId = m.bookId WHERE m.deleted = 0 AND books.kind = :kind AND $BOOK_VISIBLE " +
            "ORDER BY m.createdAt DESC",
    )
    abstract fun observeAllBookmarks(kind: BookKind): Flow<List<BookmarkWithBook>>

    @Query("SELECT * FROM bookmarks WHERE bookmarkId = :bookmarkId")
    abstract suspend fun bookmark(bookmarkId: Long): BookmarkEntity?

    /** Also deleted ones the server still knows (sync). */
    @Query("SELECT * FROM bookmarks WHERE bookId IN (:bookIds)")
    abstract suspend fun bookmarksOf(bookIds: List<Long>): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE dirty = 1")
    abstract suspend fun dirtyBookmarks(): List<BookmarkEntity>

    @Insert
    abstract suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Update
    abstract suspend fun updateBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE bookmarkId = :bookmarkId")
    abstract suspend fun deleteBookmark(bookmarkId: Long)

    /** Deletion reported to the server; if restored in the meantime, it stays. */
    @Query("DELETE FROM bookmarks WHERE bookmarkId = :bookmarkId AND deleted = 1 AND updatedAt = :updatedAt")
    abstract suspend fun deleteDeletedBookmark(bookmarkId: Long, updatedAt: Long)

    /** Reported; a change that is newer by now stays `dirty`. */
    @Query("UPDATE bookmarks SET dirty = 0, remoteTime = :remoteTime WHERE bookmarkId = :bookmarkId AND updatedAt = :updatedAt")
    abstract suspend fun markBookmarkClean(bookmarkId: Long, remoteTime: Double?, updatedAt: Long)

    /** All bookmarks together with the cross-device key of their book (backup). */
    @Query("SELECT b.serverId AS serverId, b.remoteId AS remoteId, m.* FROM bookmarks m JOIN books b ON b.bookId = m.bookId")
    abstract suspend fun keyedBookmarks(): List<KeyedBookmark>

    // --- Book keys: (serverId, remoteId) stays the same on a new device, the bookId does not ---

    @Query("SELECT bookId, serverId, remoteId FROM books")
    abstract fun observeBookRefs(): Flow<List<BookRef>>

    @Query("SELECT bookId, serverId, remoteId FROM books WHERE bookId IN (:bookIds)")
    abstract suspend fun bookRefsOf(bookIds: List<Long>): List<BookRef>

    @Query("SELECT bookId, serverId, remoteId FROM books WHERE serverId = :serverId AND remoteId IN (:remoteIds)")
    abstract suspend fun bookRefs(serverId: String, remoteIds: List<String>): List<BookRef>

    // --- Book downloads (per file; episodes go through PodcastDao) ---

    /**
     * Download state of all books with files, summed over their tracks. Parts that ARD Sounds no longer carries count only
     * when downloaded or downloading: without them, a book counts as fully downloaded when everything available is there.
     */
    @Query(
        "SELECT t.bookId AS bookId, COUNT(*) AS total, " +
            "SUM(t.downloadState = 'DONE') AS done, " +
            "SUM(t.downloadState = 'RUNNING') AS running, " +
            "SUM(t.downloadState = 'QUEUED') AS queued, " +
            "SUM(t.downloadState = 'FAILED') AS failed, " +
            "SUM(t.downloadState = 'REMOVED') AS removed, " +
            "SUM(t.fileSize) AS bytes " +
            "FROM book_tracks t JOIN books b ON b.bookId = t.bookId WHERE b.kind = 'BOOK' " +
            "AND (t.unavailableSince IS NULL OR t.downloadState IN ('DONE', 'QUEUED', 'RUNNING')) GROUP BY t.bookId",
    )
    abstract fun observeBookDownloads(): Flow<List<BookDownloadRow>>

    /**
     * Manual: failed and removed files download again too; finished ones stay, no-longer-available ones are missing. What the
     * automation has already queued counts as manual from now on (`autoDownload = 0`) and may thus download over cellular.
     */
    @Query(
        "UPDATE book_tracks SET downloadState = CASE WHEN downloadState = 'RUNNING' THEN 'RUNNING' ELSE 'QUEUED' END, autoDownload = 0 " +
            "WHERE bookId = :bookId AND unavailableSince IS NULL " +
            "AND (downloadState IN ('NONE', 'FAILED', 'REMOVED') OR (downloadState IN ('QUEUED', 'RUNNING') AND autoDownload = 1))",
    )
    abstract suspend fun queueBookDownload(bookId: Long): Int

    /**
     * Automation (continue listening, next volume): only never-downloaded files, and not if the user removed the book's
     * download or a file failed – only a manual "Download" brings that back. `autoDownload = 1`: these
     * files only download over Wi-Fi, even if cellular is allowed for books (see [nextQueuedManualBookTrack]).
     */
    @Query(
        "UPDATE book_tracks SET downloadState = 'QUEUED', autoDownload = 1 WHERE bookId = :bookId AND downloadState = 'NONE' AND unavailableSince IS NULL " +
            "AND NOT EXISTS (SELECT 1 FROM book_tracks r WHERE r.bookId = :bookId AND r.downloadState IN ('REMOVED', 'FAILED') " +
            "AND r.unavailableSince IS NULL)",
    )
    abstract suspend fun queueBookDownloadAutomatic(bookId: Long): Int

    /** Files of finished books (one row per file) with the time the book was finished. */
    @Query(
        "SELECT t.bookId AS bookId, COALESCE(p.finishedAt, p.updatedAt) AS finishedAt, t.filePath AS filePath FROM book_tracks t " +
            "JOIN books b ON b.bookId = t.bookId JOIN book_progress p ON p.bookId = t.bookId " +
            "WHERE b.kind = 'BOOK' AND t.downloadState = 'DONE' AND p.finished = 1",
    )
    abstract fun observeFinishedBookDownloads(): Flow<List<FinishedDownloadRow>>

    /** The next waiting file, manually queued ones first. */
    @Query(
        "SELECT t.* FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'BOOK' AND t.downloadState = 'QUEUED' ORDER BY t.autoDownload, t.bookId, t.trackIndex LIMIT 1",
    )
    abstract suspend fun nextQueuedBookTrack(): BookTrackEntity?

    /** Like [nextQueuedBookTrack], only manually queued ones: the automation doesn't download over cellular. */
    @Query(
        "SELECT t.* FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'BOOK' AND t.downloadState = 'QUEUED' AND t.autoDownload = 0 ORDER BY t.bookId, t.trackIndex LIMIT 1",
    )
    abstract suspend fun nextQueuedManualBookTrack(): BookTrackEntity?

    @Query(
        "SELECT COUNT(*) FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'BOOK' AND t.downloadState IN ('QUEUED', 'RUNNING')",
    )
    abstract suspend fun countPendingBookDownloads(): Int

    /** Whether files of this book or episode are still waiting or running. */
    @Query("SELECT EXISTS(SELECT 1 FROM book_tracks WHERE bookId = :bookId AND downloadState IN ('QUEUED', 'RUNNING'))")
    abstract suspend fun hasWaitingTracks(bookId: Long): Boolean

    /** Waiting and running files that the user queued themselves. */
    @Query(
        "SELECT COUNT(*) FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'BOOK' AND t.downloadState IN ('QUEUED', 'RUNNING') AND t.autoDownload = 0",
    )
    abstract suspend fun countPendingManualBookDownloads(): Int

    @Query("UPDATE book_tracks SET downloadState = 'RUNNING' WHERE bookId = :bookId AND trackIndex = :trackIndex AND downloadState = 'QUEUED'")
    abstract suspend fun markBookTrackRunning(bookId: Long, trackIndex: Int): Int

    @Query(
        "UPDATE book_tracks SET downloadState = 'QUEUED' WHERE downloadState = 'RUNNING' " +
            "AND bookId IN (SELECT bookId FROM books WHERE kind = 'BOOK')",
    )
    abstract suspend fun requeueRunningBookDownloads()

    @Query("UPDATE book_tracks SET downloadState = 'QUEUED' WHERE bookId = :bookId AND trackIndex = :trackIndex AND downloadState = 'RUNNING'")
    abstract suspend fun requeueBookTrack(bookId: Long, trackIndex: Int)

    /**
     * All still-waiting files of the book fail with: rejected login (doesn't change by waiting), on the
     * watch a file that is also missing on the phone.
     */
    @Query(
        "UPDATE book_tracks SET downloadState = 'FAILED', filePath = NULL, fileSize = 0 " +
            "WHERE bookId = :bookId AND downloadState IN ('QUEUED', 'RUNNING')",
    )
    abstract suspend fun markBookDownloadFailed(bookId: Long)

    /** Only this file fails; the others keep downloading, and the book is then partially downloaded. */
    @Query(
        "UPDATE book_tracks SET downloadState = 'FAILED', filePath = NULL, fileSize = 0 " +
            "WHERE bookId = :bookId AND trackIndex = :trackIndex AND downloadState IN ('QUEUED', 'RUNNING')",
    )
    abstract suspend fun markBookTrackFailed(bookId: Long, trackIndex: Int)

    /**
     * Watch: the storage ran full while this file (of a book or an episode) was arriving. Back to "never downloaded", so the
     * planner queues it again within its budget once there is room, instead of it staying failed.
     */
    @Query(
        "UPDATE book_tracks SET downloadState = 'NONE', filePath = NULL, fileSize = 0 " +
            "WHERE bookId = :bookId AND trackIndex = :trackIndex AND downloadState IN ('QUEUED', 'RUNNING')",
    )
    abstract suspend fun unqueueTrack(bookId: Long, trackIndex: Int)

    /** Watch, "Retry": releases failed files of books and episodes for downloading again. @return count. */
    @Query("UPDATE book_tracks SET downloadState = 'NONE' WHERE downloadState = 'FAILED'")
    abstract suspend fun resetFailedDownloads(): Int

    /** `0` if the entry has been removed or reset in the meantime. */
    @Query(
        "UPDATE book_tracks SET downloadState = 'DONE', filePath = :path, fileSize = :size " +
            "WHERE bookId = :bookId AND trackIndex = :trackIndex AND downloadState = 'RUNNING'",
    )
    abstract suspend fun markBookTrackDone(bookId: Long, trackIndex: Int, path: String, size: Long): Int

    /** File paths of the book's other tracks: a new download must not overwrite any of them. */
    @Query("SELECT filePath FROM book_tracks WHERE bookId = :bookId AND trackIndex != :trackIndex AND filePath IS NOT NULL")
    abstract suspend fun otherTrackFiles(bookId: Long, trackIndex: Int): List<String>

    @Query("UPDATE book_tracks SET downloadState = 'REMOVED', filePath = NULL, fileSize = 0 WHERE bookId = :bookId AND downloadState != 'NONE'")
    abstract suspend fun markBookDownloadRemoved(bookId: Long)

    /** Waiting and running files of all books (watch storage planning). */
    @Query(
        "SELECT t.* FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'BOOK' AND t.downloadState IN ('QUEUED', 'RUNNING')",
    )
    abstract suspend fun pendingBookTracks(): List<BookTrackEntity>

    /** Files of all books, for cleaning up orphaned files. */
    @Query(
        "SELECT t.* FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'BOOK' AND t.filePath IS NOT NULL",
    )
    abstract suspend fun downloadedBookTracks(): List<BookTrackEntity>

    /** Totals over the files of all books and episodes (watch storage display). */
    @Query(TRACK_TOTALS)
    abstract fun observeTrackTotals(): Flow<TrackTotalsRow>

    @Query(TRACK_TOTALS)
    abstract suspend fun trackTotals(): TrackTotalsRow

    /** All books and episodes with their files (watch storage view: only the selected ones are there). */
    @Query(
        "SELECT b.bookId AS bookId, b.kind AS kind, b.serverId AS serverId, b.remoteId AS remoteId, b.title AS title, " +
            "b.author AS author, COUNT(t.trackIndex) AS total, COALESCE(SUM(t.downloadState = 'DONE'), 0) AS done, " +
            "COALESCE(SUM(CASE WHEN t.downloadState = 'DONE' THEN t.fileSize ELSE 0 END), 0) AS doneBytes " +
            "FROM books b LEFT JOIN book_tracks t ON t.bookId = b.bookId AND (t.unavailableSince IS NULL OR t.downloadState = 'DONE') " +
            "GROUP BY b.bookId ORDER BY b.normTitle",
    )
    abstract fun observeStoredBooks(): Flow<List<StoredBookRow>>
}

/** A book or episode with the state of its files; [doneBytes] are the fully downloaded ones. */
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

/** [doneBytes]: fully downloaded; [pendingCount]: waiting and running files. */
data class TrackTotalsRow(val doneBytes: Long, val pendingCount: Int, val failedCount: Int = 0)

/** Totals over a book's files; `bytes` are those fully downloaded so far. */
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

/** A book with the key that stays the same on a new device (review K9). */
data class BookRef(val bookId: Long, val serverId: String, val remoteId: String)

/** A book of a source with its key and the time since which it has been in the library. */
data class KeyedBook(val remoteId: String, val addedAt: Long)

/** A file of a book with the book's key and the name of its chapter (for backing up books from ARD Sounds). */
data class KeyedBookPart(
    val remoteId: String,
    val trackIndex: Int,
    val remoteFileId: String,
    val durationMs: Long,
    val mimeType: String?,
    val unavailableSince: Long?,
    val title: String?,
    val remoteItemId: String? = null,
)

/** Progress with the key of its book. */
data class KeyedProgress(
    val serverId: String,
    val remoteId: String,
    @Embedded val progress: BookProgressEntity,
)

/** Bookmark with title and author (for episodes, the podcast) of its book or episode. */
data class BookmarkWithBook(
    val bookTitle: String,
    val bookAuthor: String,
    val kind: BookKind,
    @Embedded val bookmark: BookmarkEntity,
)

/** Bookmark with the key of its book. */
data class KeyedBookmark(
    val serverId: String,
    val remoteId: String,
    @Embedded val bookmark: BookmarkEntity,
)
