package keinabersmart.kleinaberlaut.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import keinabersmart.kleinaberlaut.core.database.entity.BookEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookTrackEntity
import keinabersmart.kleinaberlaut.core.database.entity.PodcastFeedEntity
import kotlinx.coroutines.flow.Flow
import keinabersmart.kleinaberlaut.core.model.TrackDownload

/** Geladene Folgen eines Podcasts: Anzahl und belegte Bytes. */
data class FeedDownloadRow(val feedId: String, val episodes: Int, val bytes: Long)

/** Geladene Folge, die zu Ende gehört ist; [finishedAt] ist der Zeitpunkt der letzten Änderung ihres Fortschritts. */
data class FinishedDownloadRow(val bookId: Long, val finishedAt: Long, val filePath: String?)

/** Download-Zustand einer Folge. */
data class EpisodeDownloadRow(val bookId: Long, val state: TrackDownload)

/** Folge eines Probeabos mit dem, was beim Aufräumen zählt (Konzept 12.10). */
data class TrialEpisodeRow(
    val bookId: Long,
    val feedId: String,
    val finished: Boolean,
    val hasProgress: Boolean,
    val downloadState: TrackDownload?,
)

/** Folgen aktiver Abos: alles außer Probeabos und archivierten Abos (Feeds ohne Zeile zählen als abonniert). */
private const val NOT_TRIAL = "serverId NOT IN (SELECT feedId FROM podcast_feeds WHERE subscribed = 0 OR archived = 1)"

/**
 * Podcast-Abos; ihre Folgen sind `books`-Zeilen mit `kind = 'EPISODE'` und `serverId = feedId` (Konzept 12.1).
 * Probeabos aus der Vorschau (`subscribed = 0`, Konzept 12.10) und archivierte Abos (`archived = 1`) fehlen in allen
 * Abo-Abfragen ([feeds], [observeFeeds], [observeEpisodes]); nur die Abfragen je `feedId` und die ausdrücklichen
 * „Trial“- bzw. „Archived“-Abfragen sehen sie.
 */
@Dao
abstract class PodcastDao {

    @Query("SELECT * FROM podcast_feeds WHERE subscribed = 1 AND archived = 0 ORDER BY title COLLATE NOCASE")
    abstract fun observeFeeds(): Flow<List<PodcastFeedEntity>>

    @Query("SELECT * FROM podcast_feeds WHERE subscribed = 1 AND archived = 0")
    abstract suspend fun feeds(): List<PodcastFeedEntity>

    @Query("SELECT * FROM podcast_feeds WHERE subscribed = 1 AND archived = 1 ORDER BY title COLLATE NOCASE")
    abstract fun observeArchivedFeeds(): Flow<List<PodcastFeedEntity>>

    /** Aktive und archivierte Abos (für das Backup: Archivierte behalten Fortschritt und Einstellungen). */
    @Query("SELECT * FROM podcast_feeds WHERE subscribed = 1")
    abstract suspend fun keptFeeds(): List<PodcastFeedEntity>

    @Query("UPDATE podcast_feeds SET archived = :archived WHERE feedId = :feedId")
    abstract suspend fun setArchived(feedId: String, archived: Boolean)

    /** Abos und Probeabos (für die Download-Übersicht). */
    @Query("SELECT * FROM podcast_feeds ORDER BY title COLLATE NOCASE")
    abstract fun observeAllFeeds(): Flow<List<PodcastFeedEntity>>

    @Query("UPDATE podcast_feeds SET subscribed = :subscribed WHERE feedId = :feedId")
    abstract suspend fun setSubscribed(feedId: String, subscribed: Boolean)

    @Query("SELECT feedId FROM podcast_feeds WHERE subscribed = 0")
    abstract fun observeTrialFeedIds(): Flow<List<String>>

    @Query(
        "SELECT b.bookId AS bookId, b.serverId AS feedId, COALESCE(p.finished, 0) AS finished, " +
            "(p.bookId IS NOT NULL) AS hasProgress, t.downloadState AS downloadState FROM books b " +
            "JOIN podcast_feeds f ON f.feedId = b.serverId AND f.subscribed = 0 " +
            "LEFT JOIN book_progress p ON p.bookId = b.bookId " +
            "LEFT JOIN book_tracks t ON t.bookId = b.bookId AND t.trackIndex = 0",
    )
    abstract fun observeTrialEpisodeStates(): Flow<List<TrialEpisodeRow>>

    @Query("DELETE FROM book_progress WHERE bookId = :bookId")
    abstract suspend fun deleteProgress(bookId: Long)

    @Query("SELECT * FROM podcast_feeds WHERE feedId = :feedId")
    abstract suspend fun feed(feedId: String): PodcastFeedEntity?

    @Query("SELECT * FROM podcast_feeds WHERE feedId = :feedId")
    abstract fun observeFeed(feedId: String): Flow<PodcastFeedEntity?>

    @Query("SELECT * FROM podcast_feeds WHERE url = :url")
    abstract suspend fun feedByUrl(url: String): PodcastFeedEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertFeed(feed: PodcastFeedEntity)

    @Query("UPDATE podcast_feeds SET autoDownload = :enabled WHERE feedId = :feedId")
    abstract suspend fun setAutoDownload(feedId: String, enabled: Boolean)

    @Query("UPDATE podcast_feeds SET newestFirst = :newestFirst WHERE feedId = :feedId")
    abstract suspend fun setNewestFirst(feedId: String, newestFirst: Boolean)

    @Query("UPDATE podcast_feeds SET notifyNew = :enabled WHERE feedId = :feedId")
    abstract suspend fun setNotifyNew(feedId: String, enabled: Boolean)

    @Query("UPDATE podcast_feeds SET continuous = :continuous WHERE feedId = :feedId")
    abstract suspend fun setContinuous(feedId: String, continuous: Boolean)

    /** Ids der Folgen eines Podcasts, älteste zuerst (bei gleichem Datum in der Reihenfolge des Auftauchens). */
    @Query("SELECT bookId FROM books WHERE kind = 'EPISODE' AND serverId = :feedId ORDER BY addedAt, bookId")
    abstract suspend fun episodeIdsOldestFirst(feedId: String): List<Long>

    @Query(
        "SELECT p.bookId FROM book_progress p JOIN books b ON b.bookId = p.bookId " +
            "WHERE b.serverId = :feedId AND p.finished = 1",
    )
    abstract suspend fun finishedEpisodeIds(feedId: String): List<Long>

    @Query("DELETE FROM podcast_feeds WHERE feedId = :feedId")
    abstract suspend fun deleteFeedRow(feedId: String)

    @Query("DELETE FROM books WHERE serverId = :feedId")
    abstract suspend fun deleteEpisodesOf(feedId: String)

    /** Entfernt das Abo samt Folgen und deren Fortschritt (Fremdschlüssel). */
    @Transaction
    open suspend fun deleteFeed(feedId: String) {
        deleteEpisodesOf(feedId)
        deleteFeedRow(feedId)
    }

    /**
     * Alle Folgen, neueste zuerst, **ohne Beschreibung** (leer): Die Listen „Ungehört“ und „Weiterhören“ brauchen sie nicht,
     * und bei über tausend Folgen sind die Texte mehrere MB, die bei jeder Änderung mehrfach neu geladen würden (OOM).
     */
    @Query(
        "SELECT bookId, serverId, kind, remoteId, title, normTitle, author, narrator, series, '' AS description, durationMs, " +
            "remoteUpdatedAt, addedAt, detailsLoaded, seenInGeneration, coverUrl, chaptersUrl " +
            "FROM books WHERE kind = 'EPISODE' AND $NOT_TRIAL ORDER BY addedAt DESC",
    )
    abstract fun observeEpisodes(): Flow<List<BookEntity>>

    /** Wie [observeEpisodes], nur die Folgen der Probeabos (für die Warteschlange). */
    @Query(
        "SELECT bookId, serverId, kind, remoteId, title, normTitle, author, narrator, series, '' AS description, durationMs, " +
            "remoteUpdatedAt, addedAt, detailsLoaded, seenInGeneration, coverUrl, chaptersUrl " +
            "FROM books WHERE kind = 'EPISODE' AND serverId IN (SELECT feedId FROM podcast_feeds WHERE subscribed = 0) " +
            "ORDER BY addedAt DESC",
    )
    abstract fun observeTrialEpisodes(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE kind = 'EPISODE' AND serverId = :feedId ORDER BY addedAt DESC")
    abstract fun observeEpisodesOf(feedId: String): Flow<List<BookEntity>>

    @Query("SELECT remoteId FROM books WHERE serverId = :feedId")
    abstract suspend fun knownGuids(feedId: String): List<String>

    @Query("DELETE FROM book_tracks WHERE bookId = :bookId")
    abstract suspend fun deleteTracks(bookId: Long)

    @Insert
    abstract suspend fun insertTracks(tracks: List<BookTrackEntity>)

    @Query("SELECT * FROM book_tracks WHERE bookId = :bookId AND trackIndex = 0")
    abstract suspend fun trackOf(bookId: Long): BookTrackEntity?

    @Query("UPDATE book_tracks SET durationMs = :durationMs, mimeType = :mimeType WHERE bookId = :bookId AND trackIndex = 0")
    abstract suspend fun updateTrackMeta(bookId: Long, durationMs: Long, mimeType: String?)

    /**
     * Die Audiodatei einer Folge (genau ein Titel); Kapitel bleiben unberührt. Bleibt die Adresse gleich, behalten
     * Download-Zustand und Datei ihren Stand; bei einer neuen Adresse fängt der Download von vorn an.
     */
    @Transaction
    open suspend fun setTrack(track: BookTrackEntity) {
        val old = trackOf(track.bookId)
        if (old != null && old.remoteFileId == track.remoteFileId) {
            updateTrackMeta(track.bookId, track.durationMs, track.mimeType)
        } else {
            deleteTracks(track.bookId)
            insertTracks(listOf(track))
        }
    }

    // --- Downloads der Folgen ---

    /** Download-Zustand der Folgen eines Podcasts, für die Liste. */
    @Query(
        "SELECT t.bookId AS bookId, t.downloadState AS state FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.serverId = :feedId AND t.trackIndex = 0",
    )
    abstract fun observeDownloadStates(feedId: String): Flow<List<EpisodeDownloadRow>>

    /** Alle fertig geladenen Folgen (aller Podcasts), für das Kontextmenü. */
    @Query(
        "SELECT t.bookId FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND t.trackIndex = 0 AND t.downloadState = 'DONE'",
    )
    abstract fun observeDownloadedIds(): Flow<List<Long>>

    /** Fertig geladene Folgen je Podcast, für die Download-Übersicht. */
    @Query(
        "SELECT b.serverId AS feedId, COUNT(*) AS episodes, COALESCE(SUM(t.fileSize), 0) AS bytes " +
            "FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND t.trackIndex = 0 AND t.downloadState = 'DONE' GROUP BY b.serverId",
    )
    abstract fun observeFeedDownloads(): Flow<List<FeedDownloadRow>>

    /** Fertig geladene Folgen eines Podcasts. */
    @Query(
        "SELECT t.bookId FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.serverId = :feedId AND t.trackIndex = 0 AND t.downloadState = 'DONE'",
    )
    abstract suspend fun downloadedEpisodeIds(feedId: String): List<Long>

    /** Folgen eines Podcasts mit Download, auch wartendem oder laufendem. */
    @Query(
        "SELECT t.bookId FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.serverId = :feedId AND t.trackIndex = 0 AND t.downloadState IN ('QUEUED', 'RUNNING', 'DONE')",
    )
    abstract suspend fun episodeIdsWithDownload(feedId: String): List<Long>

    /** Fertig geladene Folgen aller Podcasts. */
    @Query(
        "SELECT t.bookId FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND t.trackIndex = 0 AND t.downloadState = 'DONE'",
    )
    abstract suspend fun allDownloadedEpisodeIds(): List<Long>

    /** Von Hand: auch fehlgeschlagene und entfernte Folgen laden wieder. */
    @Query("UPDATE book_tracks SET downloadState = 'QUEUED' WHERE bookId = :bookId AND trackIndex = 0 AND downloadState IN ('NONE', 'FAILED', 'REMOVED')")
    abstract suspend fun queueDownload(bookId: Long): Int

    /** Die neuesten ungehörten Folgen eines Podcasts, die noch nie geladen wurden (nicht: entfernte oder fehlgeschlagene). */
    @Query(
        "UPDATE book_tracks SET downloadState = 'QUEUED' WHERE trackIndex = 0 AND downloadState = 'NONE' AND bookId IN (" +
            "SELECT bookId FROM books WHERE kind = 'EPISODE' AND serverId = :feedId " +
            "AND bookId NOT IN (SELECT bookId FROM book_progress WHERE finished = 1) " +
            "ORDER BY addedAt DESC, bookId DESC LIMIT :count)",
    )
    abstract suspend fun queueLatestUnheard(feedId: String, count: Int): Int

    @Query(
        "SELECT t.* FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND t.downloadState = 'QUEUED' ORDER BY b.addedAt DESC, t.bookId DESC LIMIT 1",
    )
    abstract suspend fun nextQueuedDownload(): BookTrackEntity?

    @Query(
        "SELECT COUNT(*) FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND t.downloadState IN ('QUEUED', 'RUNNING')",
    )
    abstract suspend fun countPendingDownloads(): Int

    /** Wartende und laufende Downloads von Folgen (für das Speicherbudget der Uhr). */
    @Query(
        "SELECT t.* FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND t.downloadState IN ('QUEUED', 'RUNNING')",
    )
    abstract suspend fun pendingTracks(): List<BookTrackEntity>

    @Query("UPDATE book_tracks SET downloadState = 'RUNNING' WHERE bookId = :bookId AND trackIndex = 0 AND downloadState = 'QUEUED'")
    abstract suspend fun markDownloadRunning(bookId: Long): Int

    @Query(
        "UPDATE book_tracks SET downloadState = 'QUEUED' WHERE downloadState = 'RUNNING' " +
            "AND bookId IN (SELECT bookId FROM books WHERE kind = 'EPISODE')",
    )
    abstract suspend fun requeueRunningDownloads()

    @Query("UPDATE book_tracks SET downloadState = 'QUEUED' WHERE bookId = :bookId AND trackIndex = 0 AND downloadState = 'RUNNING'")
    abstract suspend fun requeueDownload(bookId: Long)

    @Query("UPDATE book_tracks SET downloadState = 'FAILED', filePath = NULL, fileSize = 0 WHERE bookId = :bookId AND trackIndex = 0")
    abstract suspend fun markDownloadFailed(bookId: Long)

    /** `0`, wenn der Eintrag inzwischen entfernt oder zurückgesetzt wurde. */
    @Query("UPDATE book_tracks SET downloadState = 'DONE', filePath = :path, fileSize = :size WHERE bookId = :bookId AND trackIndex = 0 AND downloadState = 'RUNNING'")
    abstract suspend fun markDownloadDone(bookId: Long, path: String, size: Long): Int

    /** Entfernt den Download (auch einen wartenden) und merkt, dass die Automatik die Folge nicht neu laden soll. */
    @Query("UPDATE book_tracks SET downloadState = 'REMOVED', filePath = NULL, fileSize = 0 WHERE bookId = :bookId AND trackIndex = 0 AND downloadState != 'NONE'")
    abstract suspend fun markDownloadRemoved(bookId: Long)

    @Query(
        "SELECT t.* FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND (t.downloadState = 'DONE' OR t.filePath IS NOT NULL)",
    )
    abstract suspend fun downloadedTracks(): List<BookTrackEntity>

    @Query(
        "SELECT t.bookId AS bookId, p.updatedAt AS finishedAt, t.filePath AS filePath FROM book_tracks t " +
            "JOIN books b ON b.bookId = t.bookId JOIN book_progress p ON p.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND t.downloadState = 'DONE' AND p.finished = 1",
    )
    abstract fun observeFinishedDownloads(): Flow<List<FinishedDownloadRow>>

    /** Fertig geladene Folgen eines Podcasts, die zu Ende gehört sind. */
    @Query(
        "SELECT t.* FROM book_tracks t JOIN books b ON b.bookId = t.bookId JOIN book_progress p ON p.bookId = t.bookId " +
            "WHERE b.serverId = :feedId AND t.downloadState = 'DONE' AND p.finished = 1",
    )
    abstract suspend fun finishedDownloads(feedId: String): List<BookTrackEntity>

    /**
     * Folgen, die der letzte Abgleich nicht mehr gesehen hat, fallen weg – außer angefangenen: wer eine Folge schon
     * hört, behält sie auch dann, wenn der Feed nur die neuesten führt.
     */
    @Query(
        "DELETE FROM books WHERE serverId = :feedId AND seenInGeneration < :generation " +
            "AND bookId NOT IN (SELECT bookId FROM book_progress WHERE positionMs > 0)",
    )
    abstract suspend fun deleteUnseenEpisodes(feedId: String, generation: Long)
}
