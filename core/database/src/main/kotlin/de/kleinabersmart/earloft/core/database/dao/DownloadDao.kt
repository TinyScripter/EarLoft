package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.model.AlbumDownloadRow
import de.kleinabersmart.earloft.core.database.model.AlbumDownloadSummaryRow
import de.kleinabersmart.earloft.core.database.model.DownloadTotalsRow
import de.kleinabersmart.earloft.core.database.model.SongOfflineRow
import kotlinx.coroutines.flow.Flow

/**
 * Access to the `downloads` table (concept 5.3).
 *
 * Downloads are always linked to songs via `song_sources`, not via the stored
 * `songId`: a sync can assign a source to a different song.
 */
/**
 * The song (alias `s`) is wanted by the favorites automation: a favorite itself, from a favorited album or – with
 * `:withArtists` – by a favorited artist (all of their albums and the songs they sing or play on other albums,
 * as on the artist page).
 */
private const val FAVORITE_WANTED = """(
    s.isFavorite = 1
    OR EXISTS (SELECT 1 FROM albums fal WHERE fal.albumId = s.albumId AND fal.isFavorite = 1)
    OR (:withArtists AND (
        EXISTS (SELECT 1 FROM album_artists faa JOIN artists far ON far.artistId = faa.artistId
                WHERE faa.albumId = s.albumId AND far.isFavorite = 1)
        OR EXISTS (SELECT 1 FROM song_artists fsa JOIN artists fsr ON fsr.artistId = fsa.artistId
                   WHERE fsa.songId = s.songId AND fsr.isFavorite = 1)
    ))
)"""

@Dao
interface DownloadDao {

    /** Existing entries (including failed ones) stay unchanged. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(downloads: List<DownloadEntity>)

    @Query("SELECT * FROM downloads WHERE sourceUid = :sourceUid")
    suspend fun get(sourceUid: String): DownloadEntity?

    @Query("SELECT * FROM song_sources WHERE uid = :uid")
    suspend fun getSongSource(uid: String): SongSourceEntity?

    /** Songs among [songIds] that already have a download (in whatever state). */
    @Query(
        """
        SELECT DISTINCT ss.songId FROM downloads d JOIN song_sources ss ON ss.uid = d.sourceUid
        WHERE ss.songId IN (:songIds)
        """,
    )
    suspend fun getSongIdsWithDownload(songIds: List<Long>): List<Long>

    /** Oldest waiting download (order of queuing). */
    @Query("SELECT * FROM downloads WHERE state = 'QUEUED' ORDER BY rowid LIMIT 1")
    suspend fun nextQueued(): DownloadEntity?

    /** Like [nextQueued], but only sources of type `PHONE` (watch: songs only the phone can send). */
    @Query(
        """
        SELECT d.* FROM downloads d
        JOIN song_sources ss ON ss.uid = d.sourceUid
        JOIN sources src ON src.sourceId = ss.sourceId
        WHERE d.state = 'QUEUED' AND src.type = 'PHONE'
        ORDER BY d.rowid LIMIT 1
        """,
    )
    suspend fun nextQueuedFromPhone(): DownloadEntity?

    /** The waiting downloads from [sourceId]. */
    @Query(
        """
        SELECT d.sourceUid FROM downloads d JOIN song_sources ss ON ss.uid = d.sourceUid
        WHERE d.state = 'QUEUED' AND ss.sourceId = :sourceId
        """,
    )
    suspend fun queuedUidsOfSource(sourceId: String): List<String>

    /** Like [nextQueued], but without sources of type `PHONE`. */
    @Query(
        """
        SELECT d.* FROM downloads d
        JOIN song_sources ss ON ss.uid = d.sourceUid
        JOIN sources src ON src.sourceId = ss.sourceId
        WHERE d.state = 'QUEUED' AND src.type != 'PHONE'
        ORDER BY d.rowid LIMIT 1
        """,
    )
    suspend fun nextQueuedFromServers(): DownloadEntity?

    @Query("UPDATE downloads SET state = 'RUNNING', bytesDone = 0 WHERE sourceUid = :sourceUid AND state = 'QUEUED'")
    suspend fun markRunning(sourceUid: String): Int

    /** @return 0 if the download has been removed in the meantime – then abort. */
    @Query(
        """
        UPDATE downloads SET bytesDone = :bytesDone, bytesTotal = :bytesTotal
        WHERE sourceUid = :sourceUid AND state = 'RUNNING'
        """,
    )
    suspend fun updateProgress(sourceUid: String, bytesDone: Long, bytesTotal: Long?): Int

    /** @return 0 if the download has been removed in the meantime – then delete the file. */
    @Query(
        """
        UPDATE downloads SET state = 'DONE', filePath = :filePath, bytesDone = :sizeBytes, bytesTotal = :sizeBytes
        WHERE sourceUid = :sourceUid AND state = 'RUNNING'
        """,
    )
    suspend fun markDone(sourceUid: String, filePath: String, sizeBytes: Long): Int

    @Query("UPDATE downloads SET state = 'FAILED' WHERE sourceUid = :sourceUid AND state = 'RUNNING'")
    suspend fun markFailed(sourceUid: String)

    /** Back into the queue, e.g. when the network is gone or the worker was cancelled. */
    @Query("UPDATE downloads SET state = 'QUEUED', bytesDone = 0 WHERE sourceUid = :sourceUid AND state = 'RUNNING'")
    suspend fun requeue(sourceUid: String)

    /** Requeues downloads that got stuck after process death. */
    @Query("UPDATE downloads SET state = 'QUEUED', bytesDone = 0 WHERE state = 'RUNNING'")
    suspend fun requeueRunning()

    @Query("UPDATE downloads SET state = 'QUEUED', bytesDone = 0 WHERE state = 'FAILED'")
    suspend fun retryFailed(): Int

    @Query("UPDATE downloads SET state = 'QUEUED', bytesDone = 0 WHERE state = 'FAILED' AND sourceUid IN (:sourceUids)")
    suspend fun retryFailed(sourceUids: List<String>): Int

    /** Rejected credentials: all waiting downloads of the server at once, instead of requesting each one individually. */
    @Query("UPDATE downloads SET state = 'FAILED' WHERE state = 'QUEUED' AND sourceUid LIKE :sourceId || ':%'")
    suspend fun failQueuedOfSource(sourceId: String): Int

    /** After new credentials: requeues the server's failed downloads. */
    @Query("UPDATE downloads SET state = 'QUEUED', bytesDone = 0 WHERE state = 'FAILED' AND sourceUid LIKE :sourceId || ':%'")
    suspend fun retryFailedOfSource(sourceId: String): Int

    @Query("SELECT COUNT(*) FROM downloads WHERE state IN ('QUEUED', 'RUNNING')")
    suspend fun countPending(): Int

    // --- Removal ---

    @Query("SELECT * FROM downloads WHERE sourceUid IN (SELECT uid FROM song_sources WHERE songId IN (:songIds))")
    suspend fun getForSongs(songIds: List<Long>): List<DownloadEntity>

    @Query(
        """
        SELECT * FROM downloads WHERE sourceUid IN (
            SELECT ss.uid FROM song_sources ss JOIN songs s ON s.songId = ss.songId WHERE s.albumId = :albumId
        )
        """,
    )
    suspend fun getForAlbum(albumId: Long): List<DownloadEntity>

    @Query("SELECT * FROM downloads")
    suspend fun getAll(): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE sourceUid IN (:sourceUids)")
    suspend fun delete(sourceUids: List<String>)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()

    // --- Favorite downloads (smart downloads) ---

    /** Songs the favorites automation should download (see [FAVORITE_WANTED]). */
    @Query("SELECT s.songId FROM songs s WHERE $FAVORITE_WANTED")
    suspend fun getFavoriteSongIds(withArtists: Boolean): List<Long>

    @Query("SELECT s.songId FROM songs s WHERE $FAVORITE_WANTED")
    fun observeFavoriteSongIds(withArtists: Boolean): Flow<List<Long>>

    /** Downloads made automatically (because of a favorite) whose song the automation no longer wants. */
    @Query(
        """
        SELECT d.* FROM downloads d
        JOIN song_sources ss ON ss.uid = d.sourceUid
        JOIN songs s ON s.songId = ss.songId
        WHERE d.pinnedBy = :pinnedBy AND NOT $FAVORITE_WANTED
        """,
    )
    suspend fun getPinnedByWithoutFavorite(pinnedBy: String, withArtists: Boolean): List<DownloadEntity>

    /** A download the user has now requested themselves no longer belongs to the automation. */
    @Query(
        """
        UPDATE downloads SET pinnedBy = :to
        WHERE pinnedBy = :from AND sourceUid IN (SELECT uid FROM song_sources WHERE songId IN (:songIds))
        """,
    )
    suspend fun repinForSongs(from: String, to: String, songIds: List<Long>)

    @Query("UPDATE downloads SET pinnedBy = :to WHERE pinnedBy = :from")
    suspend fun repinAll(from: String, to: String)

    // --- Download an entire server ---

    @Query("SELECT DISTINCT songId FROM song_sources WHERE sourceId = :sourceId")
    suspend fun getSongIdsOfSource(sourceId: String): List<Long>

    /** All occurring `pinnedBy` values with this prefix, e.g. `server:`. */
    @Query("SELECT DISTINCT pinnedBy FROM downloads WHERE pinnedBy LIKE :prefix || '%'")
    suspend fun getPinnedByWithPrefix(prefix: String): List<String>

    // --- Automatically download newest and most played albums ---

    /** The most recently added visible albums with songs that have no file on the device (only those count when downloading). */
    @Query(
        """
        SELECT a.albumId FROM albums a
        JOIN songs s ON s.albumId = a.albumId AND $SONG_VISIBLE AND NOT $SONG_LOCAL
        GROUP BY a.albumId
        ORDER BY MAX(s.dateAdded) DESC, a.albumId DESC
        LIMIT :limit
        """,
    )
    fun observeNewestServerAlbumIds(limit: Int): Flow<List<Long>>

    /**
     * Like the "Most played" shelf: the albums played most often since [since] with at least [minPlays] plays, on a
     * tie the most recently played first; only songs without a file on the device are counted.
     */
    @Query(
        """
        SELECT a.albumId FROM albums a
        JOIN songs s ON s.albumId = a.albumId AND $SONG_VISIBLE AND NOT $SONG_LOCAL
        JOIN play_events pe ON pe.songId = s.songId AND pe.playedAt >= :since
        GROUP BY a.albumId
        HAVING COUNT(*) >= :minPlays
        ORDER BY COUNT(*) DESC, MAX(pe.playedAt) DESC
        LIMIT :limit
        """,
    )
    fun observeMostPlayedServerAlbumIds(since: Long, minPlays: Int, limit: Int): Flow<List<Long>>

    /**
     * Downloads with [pinnedBy] whose song belongs to none of the [albumIds]. Downloads of disabled servers are missing: their
     * albums only drop out of the selection because they are hidden, and while disabled everything is kept.
     */
    @Query(
        """
        SELECT d.* FROM downloads d
        JOIN song_sources ss ON ss.uid = d.sourceUid
        JOIN sources src ON src.sourceId = ss.sourceId AND src.enabled = 1
        JOIN songs s ON s.songId = ss.songId
        WHERE d.pinnedBy = :pinnedBy AND s.albumId NOT IN (:albumIds)
        """,
    )
    suspend fun getPinnedByOutsideAlbums(pinnedBy: String, albumIds: List<Long>): List<DownloadEntity>

    /** Like [getPinnedByOutsideAlbums], but only what the favorites automation wants; that is handed over to [to]. */
    @Query(
        """
        UPDATE downloads SET pinnedBy = :to
        WHERE pinnedBy = :from AND sourceUid IN (
            SELECT ss.uid FROM song_sources ss JOIN songs s ON s.songId = ss.songId
            WHERE $FAVORITE_WANTED AND s.albumId NOT IN (:albumIds)
        )
        """,
    )
    suspend fun repinFavoritesOutsideAlbums(from: String, to: String, albumIds: List<Long>, withArtists: Boolean)

    // --- Playback ---

    /** Fully downloaded files of a song (usually at most one). */
    @Query(
        """
        SELECT d.filePath FROM downloads d JOIN song_sources ss ON ss.uid = d.sourceUid
        WHERE ss.songId = :songId AND d.state = 'DONE' AND d.filePath IS NOT NULL
        """,
    )
    suspend fun getDoneFilePaths(songId: Long): List<String>

    /** All songs with a finished file (watch: "Shuffle all"). */
    @Query(
        """
        SELECT DISTINCT ss.songId FROM downloads d JOIN song_sources ss ON ss.uid = d.sourceUid
        WHERE d.state = 'DONE' AND d.filePath IS NOT NULL
        """,
    )
    suspend fun getDoneSongIds(): List<Long>

    /** Albums of which at least one song is fully downloaded (watch cover files). */
    @Query(
        """
        SELECT DISTINCT s.albumId FROM downloads d
        JOIN song_sources ss ON ss.uid = d.sourceUid
        JOIN songs s ON s.songId = ss.songId
        WHERE d.state = 'DONE'
        """,
    )
    suspend fun getAlbumIdsWithDoneDownload(): List<Long>

    // --- Display ---

    /**
     * Offline state of all songs of an album: whether there is a local source and, if any, the download.
     * If a song has several downloads (rare), one of them is returned.
     */
    @Query(
        """
        SELECT s.songId,
               EXISTS(
                   SELECT 1 FROM song_sources ls JOIN sources src ON src.sourceId = ls.sourceId
                   WHERE ls.songId = s.songId AND src.type = 'LOCAL'
               ) AS isLocal,
               d.state, d.bytesDone, d.bytesTotal
        FROM songs s
        LEFT JOIN song_sources ss ON ss.songId = s.songId AND ss.uid IN (SELECT sourceUid FROM downloads)
        LEFT JOIN downloads d ON d.sourceUid = ss.uid
        WHERE s.albumId = :albumId
        GROUP BY s.songId
        """,
    )
    fun observeOfflineRowsOfAlbum(albumId: Long): Flow<List<SongOfflineRow>>

    /** Like [observeOfflineRowsOfAlbum], for all albums whose album artist is [artistId]. */
    @Query(
        """
        SELECT s.songId,
               EXISTS(
                   SELECT 1 FROM song_sources ls JOIN sources src ON src.sourceId = ls.sourceId
                   WHERE ls.songId = s.songId AND src.type = 'LOCAL'
               ) AS isLocal,
               d.state, d.bytesDone, d.bytesTotal
        FROM songs s
        LEFT JOIN song_sources ss ON ss.songId = s.songId AND ss.uid IN (SELECT sourceUid FROM downloads)
        LEFT JOIN downloads d ON d.sourceUid = ss.uid
        WHERE s.albumId IN (SELECT albumId FROM album_artists WHERE artistId = :artistId)
        GROUP BY s.songId
        """,
    )
    fun observeOfflineRowsOfArtist(artistId: Long): Flow<List<SongOfflineRow>>

    @Query("SELECT albumId FROM album_artists WHERE artistId = :artistId")
    suspend fun getAlbumIdsOfArtist(artistId: Long): List<Long>

    /**
     * Download state of all songs with a download (one per song); local songs come from [observeLocalSongIds].
     * Together this gives the offline state of every song without loading the whole library.
     */
    @Query(
        """
        SELECT ss.songId AS songId, 0 AS isLocal, d.state, d.bytesDone, d.bytesTotal
        FROM downloads d JOIN song_sources ss ON ss.uid = d.sourceUid
        GROUP BY ss.songId
        """,
    )
    fun observeDownloadRows(): Flow<List<SongOfflineRow>>

    @Query(
        """
        SELECT DISTINCT ss.songId FROM song_sources ss JOIN sources src ON src.sourceId = ss.sourceId
        WHERE src.type = 'LOCAL'
        """,
    )
    fun observeLocalSongIds(): Flow<List<Long>>

    /** Albums with at least one download; songs without a download are counted in `songCount` too. */
    @Query(
        """
        SELECT a.albumId, a.title, a.albumArtistDisplay, a.year,
               (SELECT COUNT(*) FROM songs WHERE albumId = a.albumId) AS songCount,
               SUM(d.state = 'DONE') AS doneCount,
               SUM(d.state IN ('QUEUED', 'RUNNING')) AS pendingCount,
               SUM(d.state = 'FAILED') AS failedCount,
               SUM(CASE WHEN d.state = 'DONE' THEN d.bytesTotal ELSE 0 END) AS doneBytes
        FROM downloads d
        JOIN song_sources ss ON ss.uid = d.sourceUid
        JOIN songs s ON s.songId = ss.songId
        JOIN albums a ON a.albumId = s.albumId
        GROUP BY a.albumId
        ORDER BY a.sortTitle COLLATE NOCASE
        """,
    )
    fun observeAlbumRows(): Flow<List<AlbumDownloadRow>>

    /** Download state of all albums with at least one download, for the badge on tiles and rows. */
    @Query(
        """
        SELECT s.albumId,
               (SELECT COUNT(*) FROM songs WHERE albumId = s.albumId) AS songCount,
               (SELECT COUNT(DISTINCT ls.songId) FROM song_sources ls
                    JOIN sources src ON src.sourceId = ls.sourceId
                    JOIN songs ld ON ld.songId = ls.songId
                WHERE ld.albumId = s.albumId AND src.type = 'LOCAL') AS localCount,
               SUM(d.state = 'DONE') AS doneCount,
               SUM(d.state IN ('QUEUED', 'RUNNING')) AS pendingCount,
               SUM(d.state = 'FAILED') AS failedCount
        FROM downloads d
        JOIN song_sources ss ON ss.uid = d.sourceUid
        JOIN songs s ON s.songId = ss.songId
        GROUP BY s.albumId
        """,
    )
    fun observeAlbumSummaries(): Flow<List<AlbumDownloadSummaryRow>>

    @Query(
        """
        SELECT SUM(state = 'DONE') AS doneCount,
               SUM(state IN ('QUEUED', 'RUNNING')) AS pendingCount,
               SUM(state = 'FAILED') AS failedCount,
               COALESCE(SUM(CASE WHEN state = 'DONE' THEN bytesTotal ELSE 0 END), 0) AS doneBytes,
               COALESCE(SUM(CASE WHEN state = 'RUNNING' THEN bytesDone ELSE 0 END), 0) AS runningBytesDone,
               COALESCE(SUM(CASE WHEN state = 'RUNNING' THEN bytesTotal ELSE 0 END), 0) AS runningBytesTotal
        FROM downloads
        """,
    )
    fun observeTotals(): Flow<DownloadTotalsRow>
}
