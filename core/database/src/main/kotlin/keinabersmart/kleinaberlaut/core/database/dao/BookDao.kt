package keinabersmart.kleinaberlaut.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import keinabersmart.kleinaberlaut.core.database.entity.BookChapterEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookProgressEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookTrackEntity
import keinabersmart.kleinaberlaut.core.database.entity.SpokenServerEntity
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT * FROM books ORDER BY normTitle")
    abstract fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE bookId = :bookId")
    abstract fun observeBook(bookId: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE bookId = :bookId")
    abstract suspend fun book(bookId: Long): BookEntity?

    @Query("SELECT * FROM books WHERE serverId = :serverId AND remoteId = :remoteId")
    abstract suspend fun bookByRemote(serverId: String, remoteId: String): BookEntity?

    @Query("SELECT * FROM books WHERE serverId = :serverId")
    abstract suspend fun booksOf(serverId: String): List<BookEntity>

    @Query("SELECT * FROM books WHERE normTitle LIKE :pattern ESCAPE '\\' OR author LIKE :pattern ESCAPE '\\' ORDER BY normTitle LIMIT :limit")
    abstract suspend fun search(pattern: String, limit: Int): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertBook(book: BookEntity): Long

    @Update
    abstract suspend fun updateBook(book: BookEntity)

    /** Legt das Buch an oder aktualisiert es, die lokale ID bleibt stabil. */
    @Transaction
    open suspend fun upsertBook(book: BookEntity): Long {
        val existing = bookByRemote(book.serverId, book.remoteId)
        return if (existing == null) {
            insertBook(book)
        } else {
            updateBook(book.copy(bookId = existing.bookId, detailsLoaded = existing.detailsLoaded && existing.remoteUpdatedAt == book.remoteUpdatedAt))
            existing.bookId
        }
    }

    /** Bücher, die der letzte Vollabgleich nicht mehr gesehen hat, sind auf dem Server gelöscht (Mark-and-Sweep). */
    @Query("DELETE FROM books WHERE serverId = :serverId AND seenInGeneration < :generation")
    abstract suspend fun deleteUnseen(serverId: String, generation: Long)

    // --- Dateien und Kapitel ---

    @Query("SELECT * FROM book_tracks WHERE bookId = :bookId ORDER BY trackIndex")
    abstract suspend fun tracks(bookId: Long): List<BookTrackEntity>

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

    @Query("UPDATE books SET detailsLoaded = 1 WHERE bookId = :bookId")
    abstract suspend fun markDetailsLoaded(bookId: Long)

    @Transaction
    open suspend fun replaceDetails(bookId: Long, tracks: List<BookTrackEntity>, chapters: List<BookChapterEntity>) {
        deleteTracks(bookId)
        deleteChapters(bookId)
        insertTracks(tracks)
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

    @Query("SELECT * FROM book_progress WHERE dirty = 1")
    abstract suspend fun dirtyProgress(): List<BookProgressEntity>

    /** Nur wenn sich der Eintrag seit dem Senden nicht geändert hat; sonst bleibt er für die nächste Runde markiert. */
    @Query("UPDATE book_progress SET dirty = 0 WHERE bookId = :bookId AND updatedAt = :updatedAt")
    abstract suspend fun markClean(bookId: Long, updatedAt: Long)

    @Query("UPDATE book_progress SET speed = :speed WHERE bookId = :bookId")
    abstract suspend fun setSpeed(bookId: Long, speed: Float)
}
