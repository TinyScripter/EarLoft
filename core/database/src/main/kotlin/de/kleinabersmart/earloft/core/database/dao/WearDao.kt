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
    /** Uhr: `remoteId`s der Songs von [sourceId], die fertig geladen sind. */
    @Query(
        "SELECT ss.remoteId FROM downloads d JOIN song_sources ss ON ss.uid = d.sourceUid " +
            "WHERE ss.sourceId = :sourceId AND d.state = 'DONE'",
    )
    suspend fun doneDownloadRemoteIds(sourceId: String): List<String>

    /** In der Reihenfolge des Hinzufügens (bestimmt die Download-Reihenfolge auf der Uhr). */
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

    // --- Handy: einzeln gewählte Folgen (Wear-Konzept 16.1) ---

    @Query("SELECT bookId FROM wear_books")
    fun observeBookIds(): Flow<List<Long>>

    /** Erneutes Wählen behält den ursprünglichen Zeitpunkt (Reihenfolge auf der Uhr). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBook(book: WearBookEntity)

    @Query("DELETE FROM wear_books WHERE bookId = :bookId")
    suspend fun deleteBook(bookId: Long)

    @Query("DELETE FROM wear_books")
    suspend fun deleteAllBooks()

    /** Nur die Folgen bzw. nur die Bücher („Podcasts entfernen“ bzw. „Bücher entfernen“ auf der Uhr). */
    @Query("DELETE FROM wear_books WHERE bookId IN (SELECT bookId FROM books WHERE kind = :kind)")
    suspend fun deleteBooksOfKind(kind: BookKind)

    /** Zu Ende gehörte Folgen fallen von der Uhr (Entscheidung des Nutzers, 2026-11-05). */
    @Query("DELETE FROM wear_books WHERE bookId IN (SELECT bookId FROM book_progress WHERE finished)")
    suspend fun deleteFinishedBooks(): Int

    /**
     * Gewählte Folgen und Bücher in der Reihenfolge des Wählens. Folgen archivierter Abos und Bücher ausgeschalteter Server
     * bleiben gewählt, aber von der Uhr fern; Bücher vom Gerät (`local…`) und aus ARD Sounds haben keinen Server.
     */
    @Query(
        """
        SELECT w.bookId FROM wear_books w
        JOIN books b ON b.bookId = w.bookId
        LEFT JOIN podcast_feeds f ON f.feedId = b.serverId
        LEFT JOIN spoken_servers s ON s.serverId = b.serverId
        WHERE (b.kind = 'EPISODE' AND f.feedId IS NOT NULL AND NOT f.archived)
           OR (b.kind = 'BOOK' AND (s.enabled OR b.serverId LIKE 'local%' OR b.serverId = '$ARD_BOOK_SERVER_ID'))
        ORDER BY w.addedAt, w.bookId
        """,
    )
    suspend fun selectedSpokenIds(): List<Long>

    // --- Handy: Auswahlen auflösen (Wear-Konzept 5) ---

    /**
     * Entfernt Auswahlen, deren Album bzw. Playlist es nicht mehr gibt (Abgleich, Playlist gelöscht,
     * Server entfernt). Nur am Handy – auf der Uhr zeigt `refId` auf deren eigene Geräte-Playlist.
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

    /** Wie die Favoriten-Ansicht: zuletzt favorisierte zuerst. */
    @Query("SELECT songId FROM songs WHERE isFavorite ORDER BY favoriteChangedAt DESC")
    suspend fun getFavoriteSongIds(): List<Long>

    /** Die Songs der favorisierten Alben, zuletzt favorisiertes Album zuerst, darin in Albumreihenfolge. */
    @Query(
        """
        SELECT s.songId FROM songs s JOIN albums a ON a.albumId = s.albumId
        WHERE a.isFavorite
        ORDER BY a.favoriteChangedAt DESC, a.albumId, s.discNumber, s.trackNumber, s.title
        """,
    )
    suspend fun getFavoriteAlbumSongIds(): List<Long>

    /** Max. ~900 IDs pro Aufruf (SQLite-Variablenlimit). */
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

    /** Alle Quellen der Songs samt fertigem Download (für die Datei, die das Handy der Uhr schicken kann). */
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

    // --- Uhr ---

    @Query("SELECT uid, songId FROM song_sources WHERE uid IN (:uids)")
    suspend fun getSongIdsOfSources(uids: List<String>): List<SourceSongIdRow>

    /** Server-Quellen, von denen die Uhr direkt laden kann (nicht die Pseudo-Quelle `phone`). Max. ~900 IDs. */
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

    /** Die Pseudo-Quelle `phone` (Typ `PHONE`): Songs, die nur das Handy schicken kann (Fallback, Wear-Konzept 8.4). Max. ~900 IDs. */
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

    /** Die Quellen der wartenden und laufenden Downloads: Ihr Platz ist schon eingeplant. */
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
     * Fertig/gesamt/fehlgeschlagen, belegte Bytes und der geladene Anteil laufender Songs je Auswahl, in deren Reihenfolge
     * (auf der Uhr zeigt `refId` auf die Geräte-Playlist). Songs in mehreren Auswahlen zählen bei jeder.
     */
    @Query(
        """
        SELECT w.`key` AS `key`,
               COUNT(DISTINCT e.songId) AS total,
               COUNT(DISTINCT CASE WHEN d.state = 'DONE' THEN e.songId END) AS done,
               COUNT(DISTINCT CASE WHEN d.state = 'FAILED' THEN e.songId END) AS failed,
               (SELECT COALESCE(SUM(d2.bytesTotal), 0) FROM downloads d2
                WHERE d2.state = 'DONE' AND d2.songId IN (SELECT songId FROM playlist_entries WHERE playlistId = w.refId)) AS bytes,
               (SELECT COALESCE(SUM(CAST(d3.bytesDone AS REAL) / d3.bytesTotal), 0) FROM downloads d3
                WHERE d3.state = 'RUNNING' AND d3.bytesTotal > 0
                  AND d3.songId IN (SELECT songId FROM playlist_entries WHERE playlistId = w.refId)) AS runningFraction
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
