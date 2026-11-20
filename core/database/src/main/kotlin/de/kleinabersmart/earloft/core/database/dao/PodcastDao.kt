package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.database.model.EpisodeDuration
import de.kleinabersmart.earloft.core.model.EpisodeFilter
import de.kleinabersmart.earloft.core.model.FeedError
import de.kleinabersmart.earloft.core.model.FeedQueueMode
import de.kleinabersmart.earloft.core.model.TrackDownload
import kotlinx.coroutines.flow.Flow

/** Downloaded episodes of a podcast: count and bytes used. */
data class FeedDownloadRow(val feedId: String, val episodes: Int, val bytes: Long)

/** Downloaded episode that has been listened to the end; [finishedAt] is the time of the last change to its progress. */
data class FinishedDownloadRow(val bookId: Long, val finishedAt: Long, val filePath: String?)

/** Start of an episode's description (see [PodcastDao.descriptionStarts]). */
data class EpisodeDescriptionRow(val bookId: Long, val description: String)

/** Download state of an episode. */
data class EpisodeDownloadRow(val bookId: Long, val state: TrackDownload)

/** Episode of a trial subscription with what matters for cleanup (concept 12.10). */
data class TrialEpisodeRow(
    val bookId: Long,
    val feedId: String,
    val finished: Boolean,
    val hasProgress: Boolean,
    val downloadState: TrackDownload?,
)

/** Episodes of active subscriptions: everything except trial and archived subscriptions (feeds without a row count as subscribed). */
private const val NOT_TRIAL = "serverId NOT IN (SELECT feedId FROM podcast_feeds WHERE subscribed = 0 OR archived = 1)"

/** Columns of the episode lists, **without description** (empty, see [PodcastDao.observeEpisodes]). */
private const val EPISODE_COLUMNS =
    "bookId, serverId, kind, remoteId, title, normTitle, author, narrator, series, '' AS description, durationMs, " +
        "remoteUpdatedAt, addedAt, detailsLoaded, seenInGeneration, coverUrl, chaptersUrl, starredAt, season, episodeNumber, " +
        "episodeType, transcriptUrl"

/**
 * Podcast subscriptions; their episodes are `books` rows with `kind = 'EPISODE'` and `serverId = feedId` (concept 12.1).
 * Trial subscriptions from the preview (`subscribed = 0`, concept 12.10) and archived subscriptions (`archived = 1`) are missing
 * from all subscription queries ([feeds], [observeFeeds], [observeEpisodes]); only the per-`feedId` queries and the explicit
 * "Trial" and "Archived" queries see them.
 */
@Dao
abstract class PodcastDao {

    @Query("SELECT * FROM podcast_feeds WHERE subscribed = 1 AND archived = 0 ORDER BY title COLLATE NOCASE")
    abstract fun observeFeeds(): Flow<List<PodcastFeedEntity>>

    @Query("SELECT * FROM podcast_feeds WHERE subscribed = 1 AND archived = 0")
    abstract suspend fun feeds(): List<PodcastFeedEntity>

    /** The images of all subscriptions, including trial and archived ones: they stay on the device as long as the subscription exists. */
    @Query("SELECT imageUrl FROM podcast_feeds WHERE imageUrl IS NOT NULL")
    abstract suspend fun allImageUrls(): List<String>

    @Query("SELECT * FROM podcast_feeds WHERE subscribed = 1 AND archived = 1 ORDER BY title COLLATE NOCASE")
    abstract fun observeArchivedFeeds(): Flow<List<PodcastFeedEntity>>

    /** Active and archived subscriptions (for the backup: archived ones keep progress and settings). */
    @Query("SELECT * FROM podcast_feeds WHERE subscribed = 1")
    abstract suspend fun keptFeeds(): List<PodcastFeedEntity>

    @Query("UPDATE podcast_feeds SET archived = :archived WHERE feedId = :feedId")
    abstract suspend fun setArchived(feedId: String, archived: Boolean)

    /** Subscriptions and trial subscriptions (for the download overview). */
    @Query("SELECT * FROM podcast_feeds ORDER BY title COLLATE NOCASE")
    abstract fun observeAllFeeds(): Flow<List<PodcastFeedEntity>>

    @Query("UPDATE podcast_feeds SET subscribed = :subscribed WHERE feedId = :feedId")
    abstract suspend fun setSubscribed(feedId: String, subscribed: Boolean)

    /** Turns a trial subscription into a subscription with the default [autoDownload] for new subscriptions (one change for the automation). */
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

    /** Speed and effects for the subscription's episodes; `null` = the section's default. */
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

    /** Another failed sync; the streak starts with the first one ([now]). */
    @Query(
        "UPDATE podcast_feeds SET failedRefreshes = failedRefreshes + 1, lastError = :error, " +
            "failingSince = CASE WHEN failingSince = 0 THEN :now ELSE failingSince END WHERE feedId = :feedId",
    )
    abstract suspend fun recordRefreshFailure(feedId: String, error: FeedError, now: Long)

    /** The feed answered 304: only the fetch state, so that settings changed meanwhile stay (review G2). */
    @Query("UPDATE podcast_feeds SET lastRefreshAt = :now, failedRefreshes = 0, lastError = NULL, failingSince = 0 WHERE feedId = :feedId")
    abstract suspend fun recordNotModified(feedId: String, now: Long)

    /** Podcast of an episode. */
    @Query("SELECT serverId FROM books WHERE bookId = :bookId AND kind = 'EPISODE'")
    abstract suspend fun feedIdOf(bookId: Long): String?

    @Query("UPDATE podcast_feeds SET autoDownloadCount = :count WHERE feedId = :feedId")
    abstract suspend fun setAutoDownloadCount(feedId: String, count: Int?)

    /** IDs of a podcast's episodes, oldest first (with the same date in order of appearance). */
    @Query("SELECT bookId FROM books WHERE kind = 'EPISODE' AND serverId = :feedId ORDER BY addedAt, bookId")
    abstract suspend fun episodeIdsOldestFirst(feedId: String): List<Long>

    /** ID and length of a podcast's episodes, without the show notes ("Mark all as played", review G45). */
    @Query("SELECT bookId, durationMs FROM books WHERE kind = 'EPISODE' AND serverId = :feedId")
    abstract suspend fun episodeDurations(feedId: String): List<EpisodeDuration>

    @Query(
        "SELECT p.bookId FROM book_progress p JOIN books b ON b.bookId = p.bookId " +
            "WHERE b.serverId = :feedId AND p.finished = 1",
    )
    abstract suspend fun finishedEpisodeIds(feedId: String): List<Long>

    @Query("DELETE FROM podcast_feeds WHERE feedId = :feedId")
    abstract suspend fun deleteFeedRow(feedId: String)

    @Query("DELETE FROM books WHERE serverId = :feedId")
    abstract suspend fun deleteEpisodesOf(feedId: String)

    /** Removes the subscription along with its episodes and their progress (foreign keys). */
    @Transaction
    open suspend fun deleteFeed(feedId: String) {
        deleteEpisodesOf(feedId)
        deleteFeedRow(feedId)
    }

    /**
     * All episodes, newest first, **without description** (empty): the "Unplayed" and "Continue listening" lists don't need it,
     * and with over a thousand episodes the texts amount to several MB that would be reloaded several times on every change (OOM).
     */
    @Query(
        "SELECT $EPISODE_COLUMNS FROM books WHERE kind = 'EPISODE' AND $NOT_TRIAL ORDER BY addedAt DESC, bookId DESC",
    )
    abstract fun observeEpisodes(): Flow<List<BookEntity>>

    /** Starred episodes of the active subscriptions, most recently starred first, without description like [observeEpisodes] (the start is read by [descriptionStarts]). */
    @Query(
        "SELECT $EPISODE_COLUMNS FROM books WHERE kind = 'EPISODE' AND starredAt IS NOT NULL AND $NOT_TRIAL " +
            "ORDER BY starredAt DESC, bookId DESC",
    )
    abstract fun observeStarredEpisodes(): Flow<List<BookEntity>>

    /** Stars the episodes ([starredAt] = now) or removes the star (`null`); already starred ones keep their timestamp. */
    @Query("UPDATE books SET starredAt = :starredAt WHERE kind = 'EPISODE' AND bookId IN (:bookIds) AND (:starredAt IS NULL OR starredAt IS NULL)")
    abstract suspend fun setStarred(bookIds: List<Long>, starredAt: Long?): Int

    /** Starred episodes with their key (backup). */
    @Query("SELECT serverId, remoteId, starredAt FROM books WHERE kind = 'EPISODE' AND starredAt IS NOT NULL")
    abstract suspend fun keyedStarredEpisodes(): List<KeyedStarredEpisode>

    /** From the backup: stars the episode if it exists and isn't starred yet; `0` if it doesn't exist (yet). */
    @Query(
        "UPDATE books SET starredAt = CASE WHEN starredAt IS NULL THEN :starredAt ELSE starredAt END " +
            "WHERE kind = 'EPISODE' AND serverId = :serverId AND remoteId = :remoteId",
    )
    abstract suspend fun restoreStar(serverId: String, remoteId: String, starredAt: Long): Int

    /**
     * History: played episodes by last listen, newest first (`listening_stats`, own and imported together),
     * including those that no longer exist – then without [HistoryRow.bookId]. Without a timestamp (some imports) they are missing.
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

    /**
     * The first [chars] characters of the description per episode in [bookIds]: for a short summary in lists, without
     * keeping the full show notes of many episodes in memory (see [observeEpisodes]).
     */
    @Query("SELECT bookId, substr(description, 1, :chars) AS description FROM books WHERE bookId IN (:bookIds)")
    abstract suspend fun descriptionStarts(bookIds: List<Long>, chars: Int): List<EpisodeDescriptionRow>

    /** Like [observeEpisodes], only the episodes of trial subscriptions (for the queue). */
    @Query(
        "SELECT $EPISODE_COLUMNS FROM books WHERE kind = 'EPISODE' AND serverId IN (SELECT feedId FROM podcast_feeds WHERE subscribed = 0) " +
            "ORDER BY addedAt DESC, bookId DESC",
    )
    abstract fun observeTrialEpisodes(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE kind = 'EPISODE' AND serverId = :feedId ORDER BY addedAt DESC, bookId DESC")
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

    /** New address of the audio file, without discarding the download (switch source: same episode, concept 12.10). */
    @Query("UPDATE book_tracks SET remoteFileId = :url WHERE bookId = :bookId AND trackIndex = 0")
    abstract suspend fun moveTrack(bookId: Long, url: String)

    /** New ID (guid) of an episode; the `bookId` and everything attached stays (switch source, concept 12.10). */
    @Query("UPDATE books SET remoteId = :remoteId WHERE bookId = :bookId")
    abstract suspend fun setRemoteId(bookId: Long, remoteId: String)

    /**
     * The audio file of an episode (exactly one track); chapters stay untouched. Download state and file stay, even with
     * a new address: it is the same episode (same guid), and many feeds change the address without new content
     * (token in the address, signed addresses, new tracking prefix). Otherwise a downloaded episode vanished on every
     * such sync, and the automation downloaded it again (review D4). Only a failed one starts over with the new
     * address.
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

    // --- Episode downloads ---

    /** Download state of a podcast's episodes, for the list. */
    @Query(
        "SELECT t.bookId AS bookId, t.downloadState AS state FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.serverId = :feedId AND t.trackIndex = 0",
    )
    abstract fun observeDownloadStates(feedId: String): Flow<List<EpisodeDownloadRow>>

    /** Episodes of all podcasts with a download (finished, waiting, downloading, failed), for the badge on tiles. */
    @Query(
        "SELECT t.bookId AS bookId, t.downloadState AS state FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND t.trackIndex = 0 AND t.downloadState IN ('DONE', 'QUEUED', 'RUNNING', 'FAILED')",
    )
    abstract fun observeEpisodeDownloads(): Flow<List<EpisodeDownloadRow>>

    /** All fully downloaded episodes (of all podcasts), for the context menu. */
    @Query(
        "SELECT t.bookId FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND t.trackIndex = 0 AND t.downloadState = 'DONE'",
    )
    abstract fun observeDownloadedIds(): Flow<List<Long>>

    /** Fully downloaded episodes per podcast, for the download overview. */
    @Query(
        "SELECT b.serverId AS feedId, COUNT(*) AS episodes, COALESCE(SUM(t.fileSize), 0) AS bytes " +
            "FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND t.trackIndex = 0 AND t.downloadState = 'DONE' GROUP BY b.serverId",
    )
    abstract fun observeFeedDownloads(): Flow<List<FeedDownloadRow>>

    /** Fully downloaded episodes of a podcast. */
    @Query(
        "SELECT t.bookId FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.serverId = :feedId AND t.trackIndex = 0 AND t.downloadState = 'DONE'",
    )
    abstract suspend fun downloadedEpisodeIds(feedId: String): List<Long>

    /** Episodes of a podcast with a download, including waiting or running ones. */
    @Query(
        "SELECT t.bookId FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.serverId = :feedId AND t.trackIndex = 0 AND t.downloadState IN ('QUEUED', 'RUNNING', 'DONE')",
    )
    abstract suspend fun episodeIdsWithDownload(feedId: String): List<Long>

    /** Fully downloaded episodes of all podcasts. */
    @Query(
        "SELECT t.bookId FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND t.trackIndex = 0 AND t.downloadState = 'DONE'",
    )
    abstract suspend fun allDownloadedEpisodeIds(): List<Long>

    /** Manual: failed and removed episodes download again too. */
    @Query(
        "UPDATE book_tracks SET downloadState = 'QUEUED', autoDownload = 0 " +
            "WHERE bookId = :bookId AND trackIndex = 0 AND downloadState IN ('NONE', 'FAILED', 'REMOVED')",
    )
    abstract suspend fun queueDownload(bookId: Long): Int

    /**
     * The newest unplayed episodes of a podcast that have never been downloaded (not: removed or failed ones) –
     * only new ones: newer than the newest one listened to the end. The backlog behind it is only downloaded on manual request.
     * Trailers are not downloaded once the subscription has real episodes (just as they are missing from the lists).
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
     * "Only the newest": automatically downloaded (or waiting), never started episodes of the podcast that are older than the
     * newest fully downloaded one. While the new one is still downloading, the old one stays.
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
     * Episode downloads with the key of their episode (backup): everything except "never downloaded". Removed ones only as far as the
     * automation could still download them ([queueLatestUnheard]: newer than the last one played), per subscription the newest [KEEP_REMOVED]:
     * otherwise after a restore all episodes of all subscriptions would be in the backup as removed (review D20).
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

    /** Removed episodes that are newer than the last played one of their subscription. */
    @Query(
        "SELECT b.bookId AS bookId, b.serverId AS serverId, b.remoteId AS remoteId, b.addedAt AS addedAt FROM book_tracks t " +
            "JOIN books b ON b.bookId = t.bookId " +
            "LEFT JOIN (SELECT h.serverId AS feedId, MAX(h.addedAt) AS heardUntil FROM books h JOIN book_progress p ON p.bookId = h.bookId " +
            "WHERE h.kind = 'EPISODE' AND p.finished = 1 GROUP BY h.serverId) f ON f.feedId = b.serverId " +
            "WHERE b.kind = 'EPISODE' AND t.trackIndex = 0 AND t.downloadState = 'REMOVED' AND b.addedAt > COALESCE(f.heardUntil, -1)",
    )
    protected abstract suspend fun removedAfterHeard(): List<RemovedEpisodeRow>

    /** From the backup: episodes whose download was removed there or failed; the automation skips them. */
    @Query("UPDATE book_tracks SET downloadState = 'REMOVED' WHERE trackIndex = 0 AND downloadState = 'NONE' AND bookId IN (:bookIds)")
    abstract suspend fun excludeFromAutomatic(bookIds: List<Long>): Int

    /**
     * From the backup, after the first sync of a restored subscription: whatever is there now and was never downloaded is
     * skipped by the automation. Only what was downloaded on the old device is downloaded; new episodes after that automatically again.
     */
    @Query(
        "UPDATE book_tracks SET downloadState = 'REMOVED' WHERE trackIndex = 0 AND downloadState = 'NONE' AND bookId IN (" +
            "SELECT bookId FROM books WHERE kind = 'EPISODE' AND serverId = :feedId)",
    )
    abstract suspend fun excludeAllFromAutomatic(feedId: String): Int

    /** Episodes of the podcast together with their file (the sync creates both together). */
    @Query(
        "SELECT COUNT(*) FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND b.serverId = :feedId AND t.trackIndex = 0",
    )
    abstract suspend fun episodeCount(feedId: String): Int

    /** From the backup: episodes that were downloaded there and never here. */
    @Query("UPDATE book_tracks SET downloadState = 'QUEUED' WHERE trackIndex = 0 AND downloadState = 'NONE' AND bookId IN (:bookIds)")
    abstract suspend fun queueNeverDownloaded(bookIds: List<Long>): Int

    @Query(
        "SELECT t.* FROM book_tracks t JOIN books b ON b.bookId = t.bookId " +
            "WHERE b.kind = 'EPISODE' AND t.downloadState = 'QUEUED' ORDER BY b.addedAt DESC, t.bookId DESC LIMIT 1",
    )
    abstract suspend fun nextQueuedDownload(): BookTrackEntity?

    /** Like [nextQueuedDownload], the next [limit] (the worker downloads several in parallel). */
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

    /** Waiting and running episode downloads (for the watch's storage budget). */
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

    /** Only a waiting or running download: one removed in the meantime stays removed (the automation doesn't download it again). */
    @Query(
        "UPDATE book_tracks SET downloadState = 'FAILED', filePath = NULL, fileSize = 0 " +
            "WHERE bookId = :bookId AND trackIndex = 0 AND downloadState IN ('QUEUED', 'RUNNING')",
    )
    abstract suspend fun markDownloadFailed(bookId: Long)

    /** `0` if the entry has been removed or reset in the meantime. */
    @Query("UPDATE book_tracks SET downloadState = 'DONE', filePath = :path, fileSize = :size WHERE bookId = :bookId AND trackIndex = 0 AND downloadState = 'RUNNING'")
    abstract suspend fun markDownloadDone(bookId: Long, path: String, size: Long): Int

    /** Removes the download (including a waiting one) and remembers that the automation should not re-download the episode. */
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

    /** Fully downloaded episodes of a podcast that have been listened to the end; starred ones keep their download. */
    @Query(
        "SELECT t.* FROM book_tracks t JOIN books b ON b.bookId = t.bookId JOIN book_progress p ON p.bookId = t.bookId " +
            "WHERE b.serverId = :feedId AND t.downloadState = 'DONE' AND p.finished = 1 AND b.starredAt IS NULL",
    )
    abstract suspend fun finishedDownloads(feedId: String): List<BookTrackEntity>

    /**
     * Episodes the last sync no longer saw are dropped – except started, starred, downloaded ones, those with
     * bookmarks and queued ones ([queued], IDs of the episodes in the queue): whoever is already listening to, has starred,
     * bookmarked, downloaded for the road or queued an episode keeps it even if the feed only carries the newest.
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

/** Download of an episode with the key of its episode (feed, guid). */
data class KeyedEpisodeDownload(val serverId: String, val remoteId: String, val downloadState: TrackDownload)

/** Episode with a removed download ([PodcastDao.keyedEpisodeDownloads]). */
data class RemovedEpisodeRow(val bookId: Long, val serverId: String, val remoteId: String, val addedAt: Long)

/** This many removed episodes per subscription go into the backup: twice as many as the automation downloads at most. */
private const val KEEP_REMOVED = 20

/** Starred episode with its key (feed, guid). */
data class KeyedStarredEpisode(val serverId: String, val remoteId: String, val starredAt: Long)

/**
 * A played episode in the history: feed address and guid as in the statistics; [feedId] and [bookId] are missing if the
 * subscription or the episode no longer exists.
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
