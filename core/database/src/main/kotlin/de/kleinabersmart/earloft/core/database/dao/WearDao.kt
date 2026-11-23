package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import de.kleinabersmart.earloft.core.database.entity.WearBookEntity
import de.kleinabersmart.earloft.core.database.entity.WearSelectionEntity
import de.kleinabersmart.earloft.core.database.model.SourceSongIdRow
import de.kleinabersmart.earloft.core.database.model.WearCandidateRow
import de.kleinabersmart.earloft.core.database.model.WearSelectionProgressRow
import de.kleinabersmart.earloft.core.database.model.WearSongArtistRow
import de.kleinabersmart.earloft.core.database.model.WearSongRow
import de.kleinabersmart.earloft.core.database.model.WearSourceRow
import de.kleinabersmart.earloft.core.model.ARD_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.model.BookKind
import kotlinx.coroutines.flow.Flow

@Dao
interface WearDao {
    /** Watch: `remoteId`s of the songs from [sourceId] that are fully downloaded. */
    @Query(
        "SELECT ss.remoteId FROM downloads d JOIN song_sources ss ON ss.uid = d.sourceUid " +
            "WHERE ss.sourceId = :sourceId AND d.state = 'DONE'",
    )
    suspend fun doneDownloadRemoteIds(sourceId: String): List<String>

    /** In the order they were added (determines the download order on the watch). */
    @Query("SELECT * FROM wear_selections ORDER BY addedAt, `key`")
    fun observeSelections(): Flow<List<WearSelectionEntity>>

    @Query("SELECT * FROM wear_selections ORDER BY addedAt, `key`")
    suspend fun getSelections(): List<WearSelectionEntity>

    @Query("SELECT * FROM wear_selections WHERE `key` = :key")
    suspend fun getSelection(key: String): WearSelectionEntity?

    @Upsert
    suspend fun upsert(selection: WearSelectionEntity)

    @Query("DELETE FROM wear_selections WHERE `key` = :key")
    suspend fun delete(key: String): Int

    @Query("DELETE FROM wear_selections")
    suspend fun deleteAll()

    // --- Phone: individually selected episodes (Wear concept 16.1) ---

    @Query("SELECT bookId FROM wear_books")
    fun observeBookIds(): Flow<List<Long>>

    /** Selecting again keeps the original timestamp (order on the watch). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBook(book: WearBookEntity)

    @Query("DELETE FROM wear_books WHERE bookId = :bookId")
    suspend fun deleteBook(bookId: Long)

    @Query("DELETE FROM wear_books")
    suspend fun deleteAllBooks()

    /** Only the episodes or only the books ("Remove podcasts" or "Remove books" on the watch). */
    @Query("DELETE FROM wear_books WHERE bookId IN (SELECT bookId FROM books WHERE kind = :kind)")
    suspend fun deleteBooksOfKind(kind: BookKind)

    /**
     * Episodes and audiobooks listened to the end drop off the watch (user's decision, 2026-11-05; for books confirmed on
     * 2026-11-20, review G46): a finished book is rarely heard again right away and is the largest file on the watch.
     */
    @Query("DELETE FROM wear_books WHERE bookId IN (SELECT bookId FROM book_progress WHERE finished)")
    suspend fun deleteFinishedBooks(): Int

    /**
     * Selected episodes and books in the order they were selected. Episodes of archived subscriptions and books of disabled servers
     * or of deselected Audiobookshelf libraries (review G46) stay selected but are kept off the watch; books from the device
     * (`local…`) and from ARD Sounds have no server.
     */
    @Query(
        """
        SELECT w.bookId FROM wear_books w
        JOIN books b ON b.bookId = w.bookId
        LEFT JOIN podcast_feeds f ON f.feedId = b.serverId
        LEFT JOIN spoken_servers s ON s.serverId = b.serverId
        WHERE (b.kind = 'EPISODE' AND f.feedId IS NOT NULL AND NOT f.archived)
           OR (b.kind = 'BOOK' AND (b.serverId LIKE 'local%' OR b.serverId = '$ARD_BOOK_SERVER_ID' OR (s.enabled AND
               (b.libraryId IS NULL OR instr(',' || s.hiddenLibraries || ',', ',' || b.libraryId || ',') = 0))))
        ORDER BY w.addedAt, w.bookId
        """,
    )
    suspend fun selectedSpokenIds(): List<Long>

    // --- Phone: resolving selections (Wear concept 5) ---

    /**
     * Removes selections whose album or playlist no longer exists (sync, playlist deleted,
     * server removed). Only on the phone – on the watch `refId` points to its own device playlist.
     */
    @Query(
        """
        DELETE FROM wear_selections
        WHERE (kind = 'ALBUM' AND refId NOT IN (SELECT albumId FROM albums))
           OR (kind = 'PLAYLIST' AND refId NOT IN (SELECT playlistId FROM playlists))
        """,
    )
    suspend fun deleteDangling(): Int


    @Query("SELECT title FROM albums WHERE albumId = :albumId")
    suspend fun getAlbumTitle(albumId: Long): String?

    @Query("SELECT songId FROM songs WHERE albumId = :albumId ORDER BY discNumber, trackNumber, title")
    suspend fun getAlbumSongIds(albumId: Long): List<Long>

    /** Like the favorites view: most recently favorited first. */
    @Query("SELECT songId FROM songs WHERE isFavorite ORDER BY favoriteChangedAt DESC")
    suspend fun getFavoriteSongIds(): List<Long>

    /** The songs of the favorited albums, most recently favorited album first, within that in album order. */
    @Query(
        """
        SELECT s.songId FROM songs s JOIN albums a ON a.albumId = s.albumId
        WHERE a.isFavorite
        ORDER BY a.favoriteChangedAt DESC, a.albumId, s.discNumber, s.trackNumber, s.title
        """,
    )
    suspend fun getFavoriteAlbumSongIds(): List<Long>

    /** Max. ~900 IDs per call (SQLite variable limit). */
    @Query(
        """
        SELECT s.songId, s.title, s.artistDisplay, s.discNumber, s.trackNumber, s.durationMs,
               COALESCE(s.year, a.year) AS year, s.genre, s.mbid,
               a.title AS albumTitle, a.albumArtistDisplay, a.isCompilation, a.mbid AS albumMbid
        FROM songs s JOIN albums a ON a.albumId = s.albumId
        WHERE s.songId IN (:songIds)
        """,
    )
    suspend fun getSongs(songIds: List<Long>): List<WearSongRow>

    @Query(
        """
        SELECT sa.songId, ar.name FROM song_artists sa JOIN artists ar ON ar.artistId = sa.artistId
        WHERE sa.songId IN (:songIds)
        ORDER BY sa.songId, sa.position
        """,
    )
    suspend fun getSongArtists(songIds: List<Long>): List<WearSongArtistRow>

    /** All sources of the songs together with a finished download (for the file the phone can send to the watch). */
    @Query(
        """
        SELECT ss.uid, ss.songId, ss.sourceId, ss.remoteId, ss.coverRemoteId, src.type AS sourceType,
               src.priority AS sourcePriority, ss.mimeType, ss.bitRate, ss.sizeBytes,
               ss.rgTrackGainDb, ss.rgTrackPeak, ss.rgAlbumGainDb, ss.rgAlbumPeak,
               d.filePath AS downloadPath, d.bytesTotal AS downloadBytes
        FROM song_sources ss
        JOIN sources src ON src.sourceId = ss.sourceId
        LEFT JOIN downloads d ON d.sourceUid = ss.uid AND d.state = 'DONE' AND d.filePath IS NOT NULL
        WHERE ss.songId IN (:songIds)
        """,
    )
    suspend fun getSources(songIds: List<Long>): List<WearSourceRow>

    // --- Watch ---

    @Query("SELECT uid, songId FROM song_sources WHERE uid IN (:uids)")
    suspend fun getSongIdsOfSources(uids: List<String>): List<SourceSongIdRow>

    /** Server sources the watch can download from directly (not the pseudo source `phone`). Max. ~900 IDs. */
    @Query(
        """
        SELECT ss.uid, ss.songId, src.type AS sourceType, src.priority AS sourcePriority, ss.bitRate, ss.sizeBytes, s.durationMs
        FROM song_sources ss
        JOIN sources src ON src.sourceId = ss.sourceId
        JOIN songs s ON s.songId = ss.songId
        WHERE ss.songId IN (:songIds) AND src.type NOT IN $NOT_SERVER_TYPES
        """,
    )
    suspend fun getServerCandidates(songIds: List<Long>): List<WearCandidateRow>

    /** The pseudo source `phone` (type `PHONE`): songs only the phone can send (fallback, Wear concept 8.4). Max. ~900 IDs. */
    @Query(
        """
        SELECT ss.uid, ss.songId, src.type AS sourceType, src.priority AS sourcePriority, ss.bitRate, ss.sizeBytes, s.durationMs
        FROM song_sources ss
        JOIN sources src ON src.sourceId = ss.sourceId
        JOIN songs s ON s.songId = ss.songId
        WHERE ss.songId IN (:songIds) AND src.type = 'PHONE'
        """,
    )
    suspend fun getPhoneCandidates(songIds: List<Long>): List<WearCandidateRow>

    /** The sources of the waiting and running downloads: their space is already planned for. */
    @Query(
        """
        SELECT ss.uid, ss.songId, src.type AS sourceType, src.priority AS sourcePriority, ss.bitRate, ss.sizeBytes, s.durationMs
        FROM downloads d
        JOIN song_sources ss ON ss.uid = d.sourceUid
        JOIN sources src ON src.sourceId = ss.sourceId
        JOIN songs s ON s.songId = ss.songId
        WHERE d.state IN ('QUEUED', 'RUNNING')
        """,
    )
    suspend fun getPendingCandidates(): List<WearCandidateRow>

    @Query("SELECT COALESCE(SUM(bytesTotal), 0) FROM downloads WHERE state = 'DONE'")
    suspend fun getDoneBytes(): Long

    /**
     * Done/total/failed, bytes used and the downloaded share of running songs per selection, in their order
     * (on the watch `refId` points to the device playlist). Songs in several selections count for each.
     */
    @Query(
        """
        SELECT w.`key` AS `key`,
               COUNT(DISTINCT e.songId) AS total,
               COUNT(DISTINCT CASE WHEN d.state = 'DONE' THEN e.songId END) AS done,
               COUNT(DISTINCT CASE WHEN d.state = 'FAILED' THEN e.songId END) AS failed,
               (SELECT COALESCE(SUM(d2.bytesTotal), 0) FROM downloads d2
                WHERE d2.state = 'DONE' AND d2.sourceUid IN (SELECT ss2.uid FROM song_sources ss2
                  JOIN playlist_entries e2 ON e2.songId = ss2.songId WHERE e2.playlistId = w.refId)) AS bytes,
               (SELECT COALESCE(SUM(CAST(d3.bytesDone AS REAL) / d3.bytesTotal), 0) FROM downloads d3
                WHERE d3.state = 'RUNNING' AND d3.bytesTotal > 0
                  AND d3.sourceUid IN (SELECT ss3.uid FROM song_sources ss3
                    JOIN playlist_entries e3 ON e3.songId = ss3.songId WHERE e3.playlistId = w.refId)) AS runningFraction
        FROM wear_selections w
        LEFT JOIN playlist_entries e ON e.playlistId = w.refId
        LEFT JOIN song_sources ss ON ss.songId = e.songId
        LEFT JOIN downloads d ON d.sourceUid = ss.uid
        GROUP BY w.`key`
        ORDER BY w.addedAt, w.`key`
        """,
    )
    fun observeSelectionProgress(): Flow<List<WearSelectionProgressRow>>
}
