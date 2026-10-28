package keinabersmart.kleinaberlaut.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import keinabersmart.kleinaberlaut.core.database.entity.DownloadEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongSourceEntity
import keinabersmart.kleinaberlaut.core.database.model.AlbumDownloadRow
import keinabersmart.kleinaberlaut.core.database.model.DownloadTotalsRow
import keinabersmart.kleinaberlaut.core.database.model.SongOfflineRow
import kotlinx.coroutines.flow.Flow

/**
 * Zugriff auf die Tabelle `downloads` (Konzept 5.3).
 *
 * Downloads werden immer über `song_sources` mit Songs verknüpft, nicht über die gespeicherte
 * `songId`: Ein Abgleich kann eine Quelle einem anderen Song zuordnen.
 */
@Dao
interface DownloadDao {

    /** Vorhandene Einträge (auch fehlgeschlagene) bleiben unverändert. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(downloads: List<DownloadEntity>)

    @Query("SELECT * FROM downloads WHERE sourceUid = :sourceUid")
    suspend fun get(sourceUid: String): DownloadEntity?

    @Query("SELECT * FROM song_sources WHERE uid = :uid")
    suspend fun getSongSource(uid: String): SongSourceEntity?

    /** Songs unter [songIds], die schon einen Download haben (egal in welchem Zustand). */
    @Query(
        """
        SELECT DISTINCT ss.songId FROM downloads d JOIN song_sources ss ON ss.uid = d.sourceUid
        WHERE ss.songId IN (:songIds)
        """,
    )
    suspend fun getSongIdsWithDownload(songIds: List<Long>): List<Long>

    /** Ältester wartender Download (Reihenfolge des Einreihens). */
    @Query("SELECT * FROM downloads WHERE state = 'QUEUED' ORDER BY rowid LIMIT 1")
    suspend fun nextQueued(): DownloadEntity?

    /** Wie [nextQueued], aber nur Quellen vom Typ `PHONE` (Uhr: Songs, die nur das Handy schicken kann). */
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

    /** Die wartenden Downloads aus [sourceId]. */
    @Query(
        """
        SELECT d.sourceUid FROM downloads d JOIN song_sources ss ON ss.uid = d.sourceUid
        WHERE d.state = 'QUEUED' AND ss.sourceId = :sourceId
        """,
    )
    suspend fun queuedUidsOfSource(sourceId: String): List<String>

    /** Wie [nextQueued], aber ohne Quellen vom Typ `PHONE`. */
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

    /** @return 0, wenn der Download inzwischen entfernt wurde – dann abbrechen. */
    @Query(
        """
        UPDATE downloads SET bytesDone = :bytesDone, bytesTotal = :bytesTotal
        WHERE sourceUid = :sourceUid AND state = 'RUNNING'
        """,
    )
    suspend fun updateProgress(sourceUid: String, bytesDone: Long, bytesTotal: Long?): Int

    /** @return 0, wenn der Download inzwischen entfernt wurde – dann die Datei löschen. */
    @Query(
        """
        UPDATE downloads SET state = 'DONE', filePath = :filePath, bytesDone = :sizeBytes, bytesTotal = :sizeBytes
        WHERE sourceUid = :sourceUid AND state = 'RUNNING'
        """,
    )
    suspend fun markDone(sourceUid: String, filePath: String, sizeBytes: Long): Int

    @Query("UPDATE downloads SET state = 'FAILED' WHERE sourceUid = :sourceUid AND state = 'RUNNING'")
    suspend fun markFailed(sourceUid: String)

    /** Zurück in die Warteschlange, z. B. wenn das Netz weg ist oder der Worker abgebrochen wurde. */
    @Query("UPDATE downloads SET state = 'QUEUED', bytesDone = 0 WHERE sourceUid = :sourceUid AND state = 'RUNNING'")
    suspend fun requeue(sourceUid: String)

    /** Nach einem Prozess-Tod hängengebliebene Downloads wieder einreihen. */
    @Query("UPDATE downloads SET state = 'QUEUED', bytesDone = 0 WHERE state = 'RUNNING'")
    suspend fun requeueRunning()

    @Query("UPDATE downloads SET state = 'QUEUED', bytesDone = 0 WHERE state = 'FAILED'")
    suspend fun retryFailed(): Int

    @Query("UPDATE downloads SET state = 'QUEUED', bytesDone = 0 WHERE state = 'FAILED' AND sourceUid IN (:sourceUids)")
    suspend fun retryFailed(sourceUids: List<String>): Int

    @Query("SELECT COUNT(*) FROM downloads WHERE state IN ('QUEUED', 'RUNNING')")
    suspend fun countPending(): Int

    // --- Entfernen ---

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

    // --- Favoriten-Downloads (Smart-Downloads) ---

    @Query("SELECT songId FROM songs WHERE isFavorite = 1")
    suspend fun getFavoriteSongIds(): List<Long>

    @Query("SELECT songId FROM songs WHERE isFavorite = 1")
    fun observeFavoriteSongIds(): Flow<List<Long>>

    /** Automatisch (wegen eines Favoriten) geladene Downloads, deren Song kein Favorit mehr ist. */
    @Query(
        """
        SELECT d.* FROM downloads d
        JOIN song_sources ss ON ss.uid = d.sourceUid
        JOIN songs s ON s.songId = ss.songId
        WHERE d.pinnedBy = :pinnedBy AND s.isFavorite = 0
        """,
    )
    suspend fun getPinnedByWithoutFavorite(pinnedBy: String): List<DownloadEntity>

    /** Ein Download, den der Nutzer nun selbst angefordert hat, gehört nicht mehr der Automatik. */
    @Query(
        """
        UPDATE downloads SET pinnedBy = :to
        WHERE pinnedBy = :from AND sourceUid IN (SELECT uid FROM song_sources WHERE songId IN (:songIds))
        """,
    )
    suspend fun repinForSongs(from: String, to: String, songIds: List<Long>)

    @Query("UPDATE downloads SET pinnedBy = :to WHERE pinnedBy = :from")
    suspend fun repinAll(from: String, to: String)

    // --- Wiedergabe ---

    /** Fertig geladene Dateien eines Songs (meist höchstens eine). */
    @Query(
        """
        SELECT d.filePath FROM downloads d JOIN song_sources ss ON ss.uid = d.sourceUid
        WHERE ss.songId = :songId AND d.state = 'DONE' AND d.filePath IS NOT NULL
        """,
    )
    suspend fun getDoneFilePaths(songId: Long): List<String>

    /** Alle Songs mit fertiger Datei (Uhr: „Alles zufällig“). */
    @Query(
        """
        SELECT DISTINCT ss.songId FROM downloads d JOIN song_sources ss ON ss.uid = d.sourceUid
        WHERE d.state = 'DONE' AND d.filePath IS NOT NULL
        """,
    )
    suspend fun getDoneSongIds(): List<Long>

    /** Alben, von denen mindestens ein Song fertig geladen ist (Cover-Dateien der Uhr). */
    @Query(
        """
        SELECT DISTINCT s.albumId FROM downloads d
        JOIN song_sources ss ON ss.uid = d.sourceUid
        JOIN songs s ON s.songId = ss.songId
        WHERE d.state = 'DONE'
        """,
    )
    suspend fun getAlbumIdsWithDoneDownload(): List<Long>

    // --- Anzeige ---

    /**
     * Offline-Zustand aller Songs eines Albums: ob es eine lokale Quelle gibt und ggf. der Download.
     * Hat ein Song mehrere Downloads (selten), wird einer davon geliefert.
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

    /**
     * Download-Zustand aller Songs mit Download (einer je Song); lokale Songs liefert [observeLocalSongIds].
     * Zusammen ergibt das den Offline-Zustand jedes Songs, ohne die ganze Bibliothek zu laden.
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

    /** Alben mit mindestens einem Download; Songs ohne Download zählen in `songCount` mit. */
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
