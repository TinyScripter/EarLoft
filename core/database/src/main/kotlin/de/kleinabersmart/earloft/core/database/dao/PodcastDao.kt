package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import kotlinx.coroutines.flow.Flow
import de.kleinabersmart.earloft.core.model.EpisodeFilter
import de.kleinabersmart.earloft.core.model.FeedError
import de.kleinabersmart.earloft.core.model.FeedQueueMode
import de.kleinabersmart.earloft.core.model.TrackDownload

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

/** Spalten der Folgenlisten, **ohne Beschreibung** (leer, siehe [PodcastDao.observeEpisodes]). */
private const val EPISODE_COLUMNS =
    "bookId, serverId, kind, remoteId, title, normTitle, author, narrator, series, '' AS description, durationMs, " +
        "remoteUpdatedAt, addedAt, detailsLoaded, seenInGeneration, coverUrl, chaptersUrl, starredAt, season, episodeNumber, " +
        "episodeType, transcriptUrl"

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

    /** Die Bilder aller Abos, auch der Probe- und archivierten: Sie bleiben auf dem Gerät, solange es das Abo gibt. */
    @Query("SELECT imageUrl FROM podcast_feeds WHERE imageUrl IS NOT NULL")
    abstract suspend fun allImageUrls(): List<String>

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

    /** Macht aus einem Probeabo ein Abo mit der Vorgabe [autoDownload] für neue Abos (eine Änderung für die Automatik). */
    @Query("UPDATE podcast_feeds SET subscribed = 1, autoDownload = :autoDownload WHERE feedId = :feedId")
    abstract suspend fun subscribeTrial(feedId: String, autoDownload: Boolean)

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

    /** Tempo und Effekte für die Folgen des Abos; `null` = Vorgabe des Bereichs. */
    @Query("UPDATE podcast_feeds SET speed = :speed, skipSilence = :skipSilence, volumeBoostDb = :volumeBoostDb WHERE feedId = :feedId")
    abstract suspend fun setEffects(feedId: String, speed: Float?, skipSilence: Boolean?, volumeBoostDb: Int?)

    @Query("UPDATE podcast_feeds SET skipIntroS = :introS, skipOutroS = :outroS WHERE feedId = :feedId")
    abstract suspend fun setSkipIntroOutro(feedId: String, introS: Int, outroS: Int)

    @Query("UPDATE podcast_feeds SET episodeFilter = :filter, listOldestFirst = :oldestFirst WHERE feedId = :feedId")
    abstract suspend fun setEpisodeList(feedId: String, filter: EpisodeFilter, oldestFirst: Boolean)

    @Query("UPDATE podcast_feeds SET queueMode = :mode WHERE feedId = :feedId")
    abstract suspend fun setQueueMode(feedId: String, mode: FeedQueueMode)

    @Query("UPDATE podcast_feeds SET keepEpisodes = :keep WHERE feedId = :feedId")
    abstract suspend fun setKeepEpisodes(feedId: String, keep: Int)

    /** Ein weiterer fehlgeschlagener Abgleich; die Serie beginnt mit dem ersten ([now]). */
    @Query(
        "UPDATE podcast_feeds SET failedRefreshes = failedRefreshes + 1, lastError = :error, " +
            "failingSince = CASE WHEN failingSince = 0 THEN :now ELSE failingSince END WHERE feedId = :feedId",
    )
    abstract suspend fun recordRefreshFailure(feedId: String, error: FeedError, now: Long)

    /** Podcast einer Folge. */
    @Query("SELECT serverId FROM books WHERE bookId = :bookId AND kind = 'EPISODE'")
    abstract suspend fun feedIdOf(bookId: Long): String?

    @Query("UPDATE podcast_feeds SET autoDownloadCount = :count WHERE feedId = :feedId")
    abstract suspend fun setAutoDownloadCount(feedId: String, count: Int?)

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
        "SELECT $EPISODE_COLUMNS FROM books WHERE kind = 'EPISODE' AND $NOT_TRIAL ORDER BY addedAt DESC",
    )
    abstract fun observeEpisodes(): Flow<List<BookEntity>>

    /** Gemerkte Folgen der aktiven Abos, zuletzt gemerkte zuerst, ohne Beschreibung wie [observeEpisodes]. */
    @Query(
        "SELECT $EPISODE_COLUMNS FROM books WHERE kind = 'EPISODE' AND starredAt IS NOT NULL AND $NOT_TRIAL " +
            "ORDER BY starredAt DESC, bookId DESC",
    )
    abstract fun observeStarredEpisodes(): Flow<List<BookEntity>>

    /** Merkt die Folgen ([starredAt] = jetzt) bzw. nimmt den Stern weg (`null`); schon gemerkte behalten ihren Zeitpunkt. */
    @Query("UPDATE books SET starredAt = :starredAt WHERE kind = 'EPISODE' AND bookId IN (:bookIds) AND (:starredAt IS NULL OR starredAt IS NULL)")
    abstract suspend fun setStarred(bookIds: List<Long>, starredAt: Long?): Int

    /** Gemerkte Folgen samt Schlüssel (Backup). */
    @Query("SELECT serverId, remoteId, starredAt FROM books WHERE kind = 'EPISODE' AND starredAt IS NOT NULL")
    abstract suspend fun keyedStarredEpisodes(): List<KeyedStarredEpisode>

    /** Aus der Sicherung: merkt die Folge, wenn sie da und noch nicht gemerkt ist; `0`, wenn es sie (noch) nicht gibt. */
    @Query(
        "UPDATE books SET starredAt = CASE WHEN starredAt IS NULL THEN :starredAt ELSE starredAt END " +
            "WHERE kind = 'EPISODE' AND serverId = :serverId AND remoteId = :remoteId",
    )
    abstract suspend fun restoreStar(serverId: String, remoteId: String, starredAt: Long): Int

    /**
     * Verlauf: gehörte Folgen nach dem letzten Hören, neueste zuerst (`listening_stats`, eigene und importierte zusammen),
     * auch solche, die es nicht mehr gibt – dann ohne [HistoryRow.bookId]. Ohne Zeitpunkt (manche Importe) fehlen sie.
     */
    @Query(
        "SELECT s.groupKey AS groupKey, s.itemKey AS itemKey, MAX(s.groupTitle) AS groupTitle, MAX(s.itemTitle) AS itemTitle, " +
            "SUM(s.listenedMs) AS listenedMs, MAX(s.lastListenedAt) AS lastListenedAt, f.feedId AS feedId, b.bookId AS bookId, " +
            "COALESCE(b.coverUrl, f.imageUrl) AS imageUrl, b.durationMs AS durationMs " +
            "FROM listening_stats s LEFT JOIN podcast_feeds f ON f.url = s.groupKey AND f.subscribed = 1 " +
            "LEFT JOIN books b ON b.kind = 'EPISODE' AND b.serverId = f.feedId AND b.remoteId = s.itemKey " +
            "WHERE s.kind = 'EPISODE' AND s.lastListenedAt > 0 GROUP BY s.groupKey, s.itemKey " +
            "ORDER BY lastListenedAt DESC LIMIT :limit",
    )
    abstract fun observeHistory(limit: Int): Flow<List<HistoryRow>>

    /** Wie [observeEpisodes], nur die Folgen der Probeabos (für die Warteschlange). */
    @Query(
        "SELECT $EPISODE_COLUMNS FROM books WHERE kind = 'EPISODE' AND serverId IN (SELECT feedId FROM podcast_feeds WHERE subscribed = 0) " +
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

    /** Neue Adresse der Audiodatei, ohne den Download zu verwerfen (Quelle wechseln: dieselbe Folge, Konzept 12.10). */
    @Query("UPDATE book_tracks SET remoteFileId = :url WHERE bookId = :bookId AND trackIndex = 0")
    abstract suspend fun moveTrack(bookId: Long, url: String)

    /** Neue Kennung (guid) einer Folge; die `bookId` und alles daran bleibt (Quelle wechseln, Konzept 12.10). */
    @Query("UPDATE books SET remoteId = :remoteId WHERE bookId = :bookId")
    abstract suspend fun setRemoteId(bookId: Long, remoteId: String)

    /**
     * Die Audiodatei einer Folge (genau ein Titel); Kapitel bleiben unberührt. Download-Zustand und Datei bleiben, auch bei
     * einer neuen Adresse: Es ist dieselbe Folge (gleiche guid), und viele Feeds wechseln die Adresse ohne neuen Inhalt
     * (Token in der Adresse, signierte Adressen, neuer Tracking-Präfix). Sonst verschwand ein geladener Download bei jedem
     * solchen Abgleich, und die Automatik lud ihn neu (Review D4). Nur ein gescheiterter fängt mit der neuen Adresse von
     * vorn an.
     */
    @Transaction
    open suspend fun setTrack(track: BookTrackEntity) {
        val old = trackOf(track.bookId)
        when {
            old == null -> insertTracks(listOf(track))
            old.remoteFileId != track.remoteFileId && old.downloadState == TrackDownload.FAILED -> {
                deleteTracks(track.bookId)
                insertTracks(listOf(track))
            }
            else -> {
                if (old.remoteFileId != track.remoteFileId) moveTrack(track.bookId, track.remoteFileId)
                updateTrackMeta(track.bookId, track.durationMs, track.mimeType)
            }
        }
    }

    // --- Downloads der Folgen ---

    /** Download-Zustand der Folgen eines Podcasts, für die Liste. */
    @Query(
        "SELECT t.bookId AS bookId, t.downloadState AS state FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.serverId = :feedId AND t.trackIndex = 0",
    )
    abstract fun observeDownloadStates(feedId: String): Flow<List<EpisodeDownloadRow>>

    /** Folgen aller Podcasts mit Download (fertig, wartend, ladend, fehlgeschlagen), für die Markierung auf Kacheln. */
    @Query(
        "SELECT t.bookId AS bookId, t.downloadState AS state FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND t.trackIndex = 0 AND t.downloadState IN ('DONE', 'QUEUED', 'RUNNING', 'FAILED')",
    )
    abstract fun observeEpisodeDownloads(): Flow<List<EpisodeDownloadRow>>

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
    @Query(
        "UPDATE book_tracks SET downloadState = 'QUEUED', autoDownload = 0 " +
            "WHERE bookId = :bookId AND trackIndex = 0 AND downloadState IN ('NONE', 'FAILED', 'REMOVED')",
    )
    abstract suspend fun queueDownload(bookId: Long): Int

    /**
     * Die neuesten ungehörten Folgen eines Podcasts, die noch nie geladen wurden (nicht: entfernte oder fehlgeschlagene) –
     * nur neue: neuer als die neueste zu Ende gehörte. Den Rückstand dahinter lädt nur, wer ihn von Hand anfordert. Trailer
     * lädt sie nicht, sobald das Abo echte Folgen hat (wie sie in den Listen fehlen).
     */
    @Query(
        "UPDATE book_tracks SET downloadState = 'QUEUED', autoDownload = 1 WHERE trackIndex = 0 AND downloadState = 'NONE' AND bookId IN (" +
            "SELECT bookId FROM books WHERE kind = 'EPISODE' AND serverId = :feedId " +
            "AND bookId NOT IN (SELECT bookId FROM book_progress WHERE finished = 1) " +
            "AND NOT (COALESCE(episodeType, '') = 'TRAILER' AND EXISTS (SELECT 1 FROM books x WHERE x.kind = 'EPISODE' AND x.serverId = :feedId " +
            "AND COALESCE(x.episodeType, '') != 'TRAILER')) " +
            "AND addedAt > (SELECT COALESCE(MAX(h.addedAt), -1) FROM books h JOIN book_progress p ON p.bookId = h.bookId " +
            "WHERE h.serverId = :feedId AND p.finished = 1) " +
            "ORDER BY addedAt DESC, bookId DESC LIMIT :count)",
    )
    abstract suspend fun queueLatestUnheard(feedId: String, count: Int): Int

    /**
     * „Nur die neueste“: automatisch geladene (oder wartende), nie angefangene Folgen des Podcasts, die älter sind als die
     * neueste fertig geladene. Solange die neue noch lädt, bleibt die alte.
     */
    @Query(
        "SELECT t.bookId FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND b.serverId = :feedId AND t.trackIndex = 0 AND t.autoDownload = 1 " +
            "AND t.downloadState IN ('QUEUED', 'DONE') " +
            "AND t.bookId NOT IN (SELECT bookId FROM book_progress WHERE positionMs > 0 OR finished = 1) AND b.starredAt IS NULL " +
            "AND b.addedAt < (SELECT MAX(n.addedAt) FROM books n JOIN book_tracks d ON d.bookId = n.bookId " +
            "WHERE n.serverId = :feedId AND n.kind = 'EPISODE' AND d.trackIndex = 0 AND d.downloadState = 'DONE')",
    )
    abstract suspend fun supersededAutomaticDownloads(feedId: String): List<Long>

    /**
     * Downloads der Folgen samt Schlüssel ihrer Folge (Backup): alles außer „nie geladen“. Entfernte nur, soweit die Automatik sie
     * noch laden könnte ([queueLatestUnheard]: neuer als die zuletzt gehörte), je Abo die neuesten [KEEP_REMOVED]: Nach einer
     * Wiederherstellung stehen sonst alle Folgen aller Abos als entfernt in der Sicherung (Review D20).
     */
    @Transaction
    open suspend fun keyedEpisodeDownloads(): List<KeyedEpisodeDownload> =
        keyedDownloadsExceptRemoved() + removedAfterHeard()
            .groupBy { it.serverId }
            .flatMap { (_, rows) -> rows.sortedWith(compareByDescending<RemovedEpisodeRow> { it.addedAt }.thenByDescending { it.bookId }).take(KEEP_REMOVED) }
            .map { KeyedEpisodeDownload(it.serverId, it.remoteId, TrackDownload.REMOVED) }

    @Query(
        "SELECT b.serverId AS serverId, b.remoteId AS remoteId, t.downloadState AS downloadState FROM book_tracks t " +
            "JOIN books b ON b.bookId = t.bookId WHERE b.kind = 'EPISODE' AND t.trackIndex = 0 AND t.downloadState NOT IN ('NONE', 'REMOVED')",
    )
    protected abstract suspend fun keyedDownloadsExceptRemoved(): List<KeyedEpisodeDownload>

    /** Entfernte Folgen, die neuer sind als die zuletzt gehörte ihres Abos. */
    @Query(
        "SELECT b.bookId AS bookId, b.serverId AS serverId, b.remoteId AS remoteId, b.addedAt AS addedAt FROM book_tracks t " +
            "JOIN books b ON b.bookId = t.bookId " +
            "LEFT JOIN (SELECT h.serverId AS feedId, MAX(h.addedAt) AS heardUntil FROM books h JOIN book_progress p ON p.bookId = h.bookId " +
            "WHERE h.kind = 'EPISODE' AND p.finished = 1 GROUP BY h.serverId) f ON f.feedId = b.serverId " +
            "WHERE b.kind = 'EPISODE' AND t.trackIndex = 0 AND t.downloadState = 'REMOVED' AND b.addedAt > COALESCE(f.heardUntil, -1)",
    )
    protected abstract suspend fun removedAfterHeard(): List<RemovedEpisodeRow>

    /** Aus der Sicherung: Folgen, deren Download dort entfernt wurde oder fehlschlug; die Automatik lässt sie aus. */
    @Query("UPDATE book_tracks SET downloadState = 'REMOVED' WHERE trackIndex = 0 AND downloadState = 'NONE' AND bookId IN (:bookIds)")
    abstract suspend fun excludeFromAutomatic(bookIds: List<Long>): Int

    /**
     * Aus der Sicherung, nach dem ersten Abgleich eines zurückgespielten Abos: Was jetzt da ist und nie geladen wurde, lässt die
     * Automatik aus. Geladen wird nur, was auf dem alten Gerät geladen war; neue Folgen danach wieder automatisch.
     */
    @Query(
        "UPDATE book_tracks SET downloadState = 'REMOVED' WHERE trackIndex = 0 AND downloadState = 'NONE' AND bookId IN (" +
            "SELECT bookId FROM books WHERE kind = 'EPISODE' AND serverId = :feedId)",
    )
    abstract suspend fun excludeAllFromAutomatic(feedId: String): Int

    /** Folgen des Podcasts samt ihrer Datei (der Abgleich legt beides zusammen an). */
    @Query(
        "SELECT COUNT(*) FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND b.serverId = :feedId AND t.trackIndex = 0",
    )
    abstract suspend fun episodeCount(feedId: String): Int

    /** Aus der Sicherung: Folgen, die dort geladen waren und hier noch nie. */
    @Query("UPDATE book_tracks SET downloadState = 'QUEUED' WHERE trackIndex = 0 AND downloadState = 'NONE' AND bookId IN (:bookIds)")
    abstract suspend fun queueNeverDownloaded(bookIds: List<Long>): Int

    @Query(
        "SELECT t.* FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND t.downloadState = 'QUEUED' ORDER BY b.addedAt DESC, t.bookId DESC LIMIT 1",
    )
    abstract suspend fun nextQueuedDownload(): BookTrackEntity?

    /** Wie [nextQueuedDownload], die nächsten [limit] (der Worker lädt mehrere parallel). */
    @Query(
        "SELECT t.* FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND t.downloadState = 'QUEUED' ORDER BY b.addedAt DESC, t.bookId DESC LIMIT :limit",
    )
    abstract suspend fun nextQueuedDownloads(limit: Int): List<BookTrackEntity>

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

    /** Nur ein wartender oder laufender Download: Ein inzwischen entfernter bleibt entfernt (die Automatik lädt ihn nicht wieder). */
    @Query(
        "UPDATE book_tracks SET downloadState = 'FAILED', filePath = NULL, fileSize = 0 " +
            "WHERE bookId = :bookId AND trackIndex = 0 AND downloadState IN ('QUEUED', 'RUNNING')",
    )
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
            "WHERE b.kind = 'EPISODE' AND t.downloadState = 'DONE' AND p.finished = 1 AND b.starredAt IS NULL",
    )
    abstract fun observeFinishedDownloads(): Flow<List<FinishedDownloadRow>>

    /** Fertig geladene Folgen eines Podcasts, die zu Ende gehört sind; gemerkte behalten ihren Download. */
    @Query(
        "SELECT t.* FROM book_tracks t JOIN books b ON b.bookId = t.bookId JOIN book_progress p ON p.bookId = t.bookId " +
            "WHERE b.serverId = :feedId AND t.downloadState = 'DONE' AND p.finished = 1 AND b.starredAt IS NULL",
    )
    abstract suspend fun finishedDownloads(feedId: String): List<BookTrackEntity>

    /**
     * Folgen, die der letzte Abgleich nicht mehr gesehen hat, fallen weg – außer angefangenen, gemerkten, geladenen, mit
     * Lesezeichen und eingereihten ([queued], Kennungen der Folgen in der Warteschlange): wer eine Folge schon hört, gemerkt, mit
     * einem Lesezeichen versehen, für unterwegs geladen oder eingereiht hat, behält sie auch dann, wenn der Feed nur die neuesten führt.
     */
    @Query(
        "DELETE FROM books WHERE serverId = :feedId AND seenInGeneration < :generation AND starredAt IS NULL " +
            "AND remoteId NOT IN (:queued) " +
            "AND bookId NOT IN (SELECT bookId FROM book_progress WHERE positionMs > 0) " +
            "AND bookId NOT IN (SELECT bookId FROM bookmarks WHERE deleted = 0) " +
            "AND bookId NOT IN (SELECT bookId FROM book_tracks WHERE downloadState IN ('QUEUED', 'RUNNING', 'DONE'))",
    )
    abstract suspend fun deleteUnseenEpisodes(feedId: String, generation: Long, queued: List<String>)
}

/** Download einer Folge mit dem Schlüssel ihrer Folge (Feed, guid). */
data class KeyedEpisodeDownload(val serverId: String, val remoteId: String, val downloadState: TrackDownload)

/** Folge mit entferntem Download ([PodcastDao.keyedEpisodeDownloads]). */
data class RemovedEpisodeRow(val bookId: Long, val serverId: String, val remoteId: String, val addedAt: Long)

/** So viele entfernte Folgen je Abo kommen in die Sicherung: doppelt so viele, wie die Automatik höchstens lädt. */
private const val KEEP_REMOVED = 20

/** Gemerkte Folge mit ihrem Schlüssel (Feed, guid). */
data class KeyedStarredEpisode(val serverId: String, val remoteId: String, val starredAt: Long)

/**
 * Eine gehörte Folge im Verlauf: Feed-Adresse und guid wie in der Statistik; [feedId] und [bookId] fehlen, wenn es das Abo
 * bzw. die Folge nicht mehr gibt.
 */
data class HistoryRow(
    val groupKey: String,
    val itemKey: String,
    val groupTitle: String,
    val itemTitle: String,
    val listenedMs: Long,
    val lastListenedAt: Long,
    val feedId: String?,
    val bookId: Long?,
    val imageUrl: String?,
    val durationMs: Long?,
)
