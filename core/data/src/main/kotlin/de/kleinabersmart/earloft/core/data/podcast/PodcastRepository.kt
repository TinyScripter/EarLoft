package de.kleinabersmart.earloft.core.data.podcast

import de.kleinabersmart.earloft.core.common.foldedSortKey
import android.text.Html
import android.util.Log
import androidx.room.withTransaction
import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.EpisodePersonEntity
import de.kleinabersmart.earloft.core.model.EpisodeType
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.data.refresh.NewEpisodes
import de.kleinabersmart.earloft.core.data.spoken.toModel
import de.kleinabersmart.earloft.core.datasource.local.AudioFileChapters
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.ChapterSource
import de.kleinabersmart.earloft.core.model.EffectOverrides
import de.kleinabersmart.earloft.core.model.EpisodeFilter
import de.kleinabersmart.earloft.core.model.FeedError
import de.kleinabersmart.earloft.core.model.FeedQueueMode
import de.kleinabersmart.earloft.core.model.FeedStats
import de.kleinabersmart.earloft.core.model.keepNewest
import de.kleinabersmart.earloft.core.model.LOCAL_PODCAST_PREFIX
import de.kleinabersmart.earloft.core.model.PodcastFeed
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.network.HttpStatusException
import de.kleinabersmart.earloft.core.network.podcast.FeedAuth
import de.kleinabersmart.earloft.core.network.podcast.FeedFetch
import de.kleinabersmart.earloft.core.network.podcast.Opml
import de.kleinabersmart.earloft.core.network.podcast.ParsedChapter
import de.kleinabersmart.earloft.core.network.podcast.OpmlFeed
import de.kleinabersmart.earloft.core.network.podcast.ParsedEpisode
import de.kleinabersmart.earloft.core.network.podcast.ParsedFeed
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedException
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ardSoundsBridgeUrl
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ardSoundsShowId
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.parseArdSoundsInput
import de.kleinabersmart.earloft.core.network.podcast.normalizeFeedUrl
import de.kleinabersmart.earloft.core.network.podcast.splitCredentials
import de.kleinabersmart.earloft.core.network.podcast.directory.feedKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SubscribeResult {
    data class Added(val feedId: String) : SubscribeResult
    data class AlreadySubscribed(val feedId: String) : SubscribeResult

    /** The subscription already exists but is archived; it can be brought back with [PodcastRepository.reactivate]. */
    data class Archived(val feedId: String, val title: String) : SubscribeResult
    data object InvalidUrl : SubscribeResult
    data object Unreachable : SubscribeResult
    data object NotAFeed : SubscribeResult

    /**
     * The feed requires a login (HTTP 401, concept 12.10 "Protected feeds"); [rejected]: the credentials sent along
     * were rejected.
     */
    data class CredentialsNeeded(val rejected: Boolean) : SubscribeResult
}

/** What an OPML import did: new subscriptions, skipped duplicates, subscriptions removed when replacing. */
data class OpmlImportResult(val added: Int, val skipped: Int, val removed: Int)

/** Result of a feed sync: `null` on error, otherwise the episodes that are new in the feed. */
data class RefreshResult(val newEpisodeIds: List<Long>)

/**
 * Result of [PodcastRepository.refreshAll]: `ok` if no feed failed, and the subscriptions with new episodes.
 * [allFailed]: no feed was reachable – more likely the network than individual dead feeds. [moved]: titles of the subscriptions that moved to a new
 * address.
 */
data class PodcastRefreshSummary(
    val ok: Boolean,
    val newEpisodes: List<NewEpisodes>,
    val allFailed: Boolean = false,
    val moved: List<String> = emptyList(),
) {
    val newCount: Int get() = newEpisodes.sumOf { it.count }
}

/** A podcast with its fully downloaded episodes (count, bytes used). */
data class FeedDownload(val feed: PodcastFeed, val episodes: Int, val bytes: Long)

/**
 * A played episode in the history; title as last played. [feedId] and [bookId] are missing if the subscription or the episode no
 * longer exists; [listenedMs] is the content time.
 */
data class EpisodeHistoryEntry(
    val title: String,
    val podcast: String,
    val lastListenedAt: Long,
    val listenedMs: Long,
    val feedId: String?,
    val bookId: Long?,
    val imageUrl: String?,
    val durationMs: Long,
    val finished: Boolean,
)

/** Podcast subscriptions and their episodes (concept 12.1): subscribe, sync, load chapters. */
@Singleton
class PodcastRepository @Inject constructor(
    private val database: EarLoftDatabase,
    private val dao: PodcastDao,
    private val bookDao: BookDao,
    private val api: PodcastFeedApi,
    private val ardSounds: ArdSoundsApi,
    private val settings: PodcastSettings,
    private val fileChapters: AudioFileChapters,
    // Lazy: switching the source in turn needs this repository.
    private val sourceSwitch: dagger.Lazy<PodcastSourceSwitch>,
    private val credentials: FeedCredentials,
    private val queue: EpisodeQueue,
) {
    private val mutex = Mutex()

    private val _refreshed = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Emits the ID of every podcast whose feed was just synced (even without changes), for the download automation. */
    val refreshed: SharedFlow<String> = _refreshed.asSharedFlow()

    private val importsRunning = AtomicInteger(0)

    /**
     * `true` while a database import runs: it creates and syncs subscriptions before the listening progress is applied, and must
     * never start downloads (review G26). The automation picks them up with the next regular sync.
     */
    val automationPaused: Boolean get() = importsRunning.get() > 0

    /** Runs [block] with the download automation paused ([automationPaused]). */
    suspend fun <T> withoutAutomation(block: suspend () -> T): T {
        importsRunning.incrementAndGet()
        try {
            return block()
        } finally {
            importsRunning.decrementAndGet()
        }
    }

    /** By title without case and accents (review G14); the other orders of the subscription list sort this list stably. */
    val feeds: Flow<List<PodcastFeed>> = dao.observeFeeds().map { list -> list.sortedBy { it.title.foldedSortKey() }.map { it.toModel() } }

    /** Subscriptions whose title or author contains [query] (ignoring case and accents). */
    suspend fun searchFeeds(query: String): List<PodcastFeed> {
        val needle = query.normalizeForMerge()
        if (needle.isEmpty()) return emptyList()
        return feeds.first().filter { it.title.normalizeForMerge().contains(needle) || it.author.normalizeForMerge().contains(needle) }
    }

    /** Podcasts with at least one downloaded episode, by title. */
    fun feedDownloads(): Flow<List<FeedDownload>> = combine(dao.observeAllFeeds(), dao.observeFeedDownloads()) { feeds, rows ->
        val byFeed = rows.associateBy { it.feedId }
        feeds.mapNotNull { feed -> byFeed[feed.feedId]?.let { FeedDownload(feed.toModel(), it.episodes, it.bytes) } }
    }

    /** Number of fully downloaded episodes per podcast (badge on the subscription tiles); podcasts without any are missing. */
    fun downloadedEpisodeCounts(): Flow<Map<String, Int>> =
        dao.observeFeedDownloads().map { rows -> rows.associate { it.feedId to it.episodes } }.distinctUntilChanged()

    /** Download state of all episodes with a download (badge on the tiles); episodes without an entry are not downloaded. */
    fun episodeDownloadStates(): Flow<Map<Long, TrackDownload>> =
        dao.observeEpisodeDownloads().map { rows -> rows.associate { it.bookId to it.state } }.distinctUntilChanged()

    fun feed(feedId: String): Flow<PodcastFeed?> = dao.observeFeed(feedId).map { it?.toModel() }

    /** Archived subscriptions by title (Settings → Podcasts). */
    val archivedFeeds: Flow<List<PodcastFeed>> = dao.observeArchivedFeeds().map { list -> list.map { it.toModel() } }

    /** The subscriptions by the [feedKey] of their address, to recognize directory hits as already subscribed. */
    val subscribedByKey: Flow<Map<String, String>> = feeds.map { list -> list.filter { !it.isLocal }.associate { feedKey(it.url) to it.id } }

    /** Download state per episode of a podcast (episodes without an entry are not downloaded). */
    fun downloadStates(feedId: String): Flow<Map<Long, TrackDownload>> =
        dao.observeDownloadStates(feedId).map { rows -> rows.associate { it.bookId to it.state } }

    /**
     * Episodes of a podcast, newest first, with progress; without those hidden by "Keep episodes" ([keepNewest]), and
     * without trailers once there are real episodes ([withoutTrailers]).
     */
    fun episodes(feedId: String): Flow<List<BookWithProgress>> =
        combine(dao.observeEpisodesOf(feedId), bookDao.observeAllProgress(), dao.observeFeed(feedId)) { episodes, progress, feed ->
            val byBook = progress.associateBy { it.bookId }
            episodes
                .withoutTrailers(byBook)
                .keepNewest({ feedId }, { feed?.keepEpisodes ?: 0 }) { byBook[it.bookId].isUntouched && it.starredAt == null }
                .map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) }
        }

    /**
     * Per subscription the newest episode, the last played time and the number of new episodes ([FeedStats.newEpisodes], without those
     * hidden by "Keep episodes"), for sorting and tiles.
     */
    fun feedStats(): Flow<Map<String, FeedStats>> =
        combine(dao.observeFeeds(), dao.observeEpisodes(), bookDao.observeAllProgress()) { feeds, episodes, progress ->
            val byBook = progress.associateBy { it.bookId }
            episodes.visibleIn(feeds, byBook).groupBy { it.serverId }.mapValues { (_, list) ->
                FeedStats(
                    newestEpisodeAt = list.first().addedAt,
                    lastPlayedAt = list.maxOf { e -> byBook[e.bookId]?.takeUnless { it.isUntouched }?.updatedAt ?: 0L },
                    // The list is newest first: everything before the first started or played episode.
                    newEpisodes = list.takeWhile { byBook[it.bookId].isUntouched }.size,
                )
            }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /**
     * The library's "New" tab: the newest episodes of all subscriptions by publication, including played ones (unlike "Recently
     * added" on Home); whatever "Keep episodes" hides and trailers next to real episodes are missing as everywhere. With the
     * beginning of the show notes ([DescriptionStarts]).
     */
    fun latestEpisodes(limit: Int = LATEST_LIMIT): Flow<List<BookWithProgress>> = flow {
        val starts = DescriptionStarts()
        emitAll(
            combine(dao.observeFeeds(), dao.observeEpisodes(), bookDao.observeAllProgress()) { feeds, episodes, progress ->
                val byBook = progress.associateBy { it.bookId }
                starts.of(episodes, episodes.visibleIn(feeds, byBook).take(limit))
                    .map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) }
            },
        )
    }.flowOn(Dispatchers.Default) // Over all episodes and all progress, every 15 s during playback.

    /** Beginning of the show notes of [bookIds] (as in [DescriptionStarts]), e.g. for searching the queue (review G22). */
    suspend fun descriptionStarts(bookIds: List<Long>): Map<Long, String> =
        bookIds.chunked(MAX_ARGS).flatMap { dao.descriptionStarts(it, NOTES_CHARS) }.associate { it.bookId to it.description }

    /**
     * Beginning of the show notes ([NOTES_CHARS] characters) for a short summary in lists spanning several subscriptions:
     * [PodcastDao.observeEpisodes] and [PodcastDao.observeStarredEpisodes] omit the description, here it is added only for
     * the episodes shown. It is re-read when the database delivers a new episode list or the selection
     * changes, not on every save of the listening progress (every 15 s during playback). One instance per collection.
     */
    private inner class DescriptionStarts {
        private var source: List<BookEntity>? = null
        private var ids = emptyList<Long>()
        private var notes = emptyMap<Long, String>()

        /** [shown] (from [episodes], as delivered by the database) with the beginning of their show notes. */
        suspend fun of(episodes: List<BookEntity>, shown: List<BookEntity>): List<BookEntity> {
            val shownIds = shown.map { it.bookId }
            if (episodes !== source || shownIds != ids) {
                notes = shownIds.chunked(MAX_ARGS)
                    .flatMap { dao.descriptionStarts(it, NOTES_CHARS) }
                    .associate { it.bookId to it.description }
                source = episodes
                ids = shownIds
            }
            return shown.map { it.copy(description = notes[it.bookId].orEmpty()) }
        }
    }

    /**
     * "Recently added" on Home: the newest episodes of all subscriptions by publication, without those played to the end. Episodes from
     * "Continue listening" stay in, because new episodes appear there on their own anyway, just in queue order.
     */
    fun recentEpisodes(limit: Int = RECENT_LIMIT): Flow<List<BookWithProgress>> =
        combine(dao.observeFeeds(), dao.observeEpisodes(), bookDao.observeAllProgress()) { feeds, episodes, progress ->
            val byBook = progress.associateBy { it.bookId }
            episodes.visibleIn(feeds, byBook).asSequence()
                .filter { byBook[it.bookId]?.finished != true }
                .sortedByDescending { it.addedAt }
                .take(limit)
                .map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) }
                .toList()
        }

    /**
     * "Continue listening" (the podcast queue): at the very front the episodes from [order] (not played to the end, in this
     * order), behind them automatically the started episodes with the most recently played first and the new, not yet
     * started episodes of the last [NEW_DAYS] days (newest first) – depending on the subscription's [FeedQueueMode] all, only its
     * newest or none. Episodes in [hidden] are missing.
     */
    fun continueListening(
        order: Flow<List<Long>> = flowOf(emptyList()),
        hidden: Flow<Set<Long>> = flowOf(emptySet()),
        now: () -> Long = System::currentTimeMillis,
    ): Flow<List<BookWithProgress>> =
        combine(
            combine(dao.observeEpisodes(), dao.observeTrialEpisodes(), dao.observeFeeds(), ::Triple),
            bookDao.observeAllProgress(),
            order,
            hidden,
        ) { (episodes, trial, feeds), progress, orderIds, hiddenIds ->
            val byBook = progress.associateBy { it.bookId }
            val modes = feeds.associate { it.feedId to it.queueMode }
            val visible = episodes.visibleIn(feeds, byBook)
            // The newest episode of each subscription (the list is sorted by date descending), for "Only the newest"; a trailer doesn't
            // count here if there are real episodes.
            val newest = HashMap<String, Long>()
            visible.forEach { newest.putIfAbsent(it.serverId, it.bookId) }
            val started = episodes
                .filter { it.bookId !in hiddenIds }
                .mapNotNull { e -> byBook[e.bookId]?.takeIf { !it.finished && it.positionMs > 0 }?.let { e to it } }
                .sortedByDescending { (_, p) -> p.updatedAt }
                .map { (e, p) -> BookWithProgress(e.toModel(), p.toModel()) }
            val since = now() - NEW_DAYS * DAY_MS
            val fresh = visible
                .filter { e -> e.bookId !in hiddenIds && byBook[e.bookId].isUntouched && e.addedAt >= since }
                .filter { e ->
                    when (modes[e.serverId]) {
                        FeedQueueMode.MANUAL -> false
                        FeedQueueMode.NEWEST_ONLY -> newest[e.serverId] == e.bookId
                        FeedQueueMode.ALL_NEW, null -> true
                    }
                }
                .take(MAX_NEW)
                .map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) }
            // Episodes of trial subscriptions only appear here, where they were explicitly queued (concept 12.10).
            val byId = (episodes + trial).associateBy { it.bookId }
            val ordered = orderIds.mapNotNull { id ->
                byId[id]?.takeIf { id !in hiddenIds && byBook[id]?.finished != true }
                    ?.let { BookWithProgress(it.toModel(), byBook[id]?.toModel()) }
            }
            val placed = ordered.mapTo(HashSet()) { it.book.id }
            ordered + (started + fresh).filter { it.book.id !in placed }
        }

    suspend fun isEpisode(bookId: Long): Boolean = bookDao.book(bookId)?.kind == BookKind.EPISODE

    /** An episode played to the end that restarts counts as open again (it starts from the beginning anyway). */
    suspend fun reopenIfFinished(bookId: Long) {
        if (bookDao.progress(bookId)?.finished == true) setPlayed(bookId, played = false)
    }

    /** IDs of all fully downloaded episodes. */
    fun downloadedIds(): Flow<Set<Long>> = dao.observeDownloadedIds().map { it.toSet() }

    /** Saved episodes of the subscriptions ("Saved" tab), most recently saved first, without description. */
    fun starredEpisodes(): Flow<List<BookWithProgress>> = flow {
        val starts = DescriptionStarts()
        emitAll(
            combine(dao.observeStarredEpisodes(), bookDao.observeAllProgress()) { episodes, progress ->
                val byBook = progress.associateBy { it.bookId }
                starts.of(episodes, episodes).map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) }
            },
        )
    }

    /**
     * Saves the episodes or removes the star. Saved episodes stay even if the feed no longer lists them, and their
     * download is not cleaned up; already saved ones keep their timestamp. @return Number of changed episodes.
     */
    suspend fun setStarred(bookIds: Collection<Long>, starred: Boolean): Int =
        bookIds.distinct().chunked(MAX_ARGS).sumOf { dao.setStarred(it, if (starred) System.currentTimeMillis() else null) }

    /**
     * History of played episodes ([limit] most recently played), from the statistics: including episodes no longer in the feed
     * and those of ended subscriptions; the episode itself only as long as it exists.
     */
    fun history(limit: Int = HISTORY_LIMIT): Flow<List<EpisodeHistoryEntry>> =
        combine(dao.observeHistory(limit), bookDao.observeAllProgress()) { rows, progress ->
            val byBook = progress.associateBy { it.bookId }
            rows.map { row ->
                EpisodeHistoryEntry(
                    title = row.itemTitle,
                    podcast = row.groupTitle,
                    lastListenedAt = row.lastListenedAt,
                    listenedMs = row.listenedMs,
                    feedId = row.feedId,
                    bookId = row.bookId,
                    imageUrl = row.imageUrl,
                    durationMs = row.durationMs ?: 0,
                    finished = row.bookId?.let { byBook[it]?.finished } == true,
                )
            }
        }.distinctUntilChanged()

    /** Marks the episode as played (position at the end) or as unplayed (position 0). Local only, there is no server. */
    suspend fun setPlayed(bookId: Long, played: Boolean) {
        val book = bookDao.book(bookId) ?: return
        bookDao.upsertProgress(playedProgress(book, bookDao.progress(bookId), played, System.currentTimeMillis()))
    }

    /**
     * Like [setPlayed] for several episodes at once (multi-select and swiping, concept 12.5). The result restores the
     * previous state (position, speed, "played") for "Undo".
     */
    suspend fun setPlayed(bookIds: Collection<Long>, played: Boolean): suspend () -> Unit {
        val now = System.currentTimeMillis()
        val before = bookIds.distinct().associateWith { bookDao.progress(it) }
        val rows = before.mapNotNull { (id, old) -> bookDao.book(id)?.let { playedProgress(it, old, played, now) } }
        if (rows.isNotEmpty()) bookDao.upsertAllProgress(rows)
        return {
            // As the newest change, otherwise the backed-up state (`user.db`) would overwrite the restore.
            val restoredAt = System.currentTimeMillis()
            before.values.filterNotNull().map { it.copy(updatedAt = restoredAt) }.takeIf { it.isNotEmpty() }?.let { bookDao.upsertAllProgress(it) }
            before.filterValues { it == null }.keys.forEach { dao.deleteProgress(it) }
        }
    }

    /**
     * "Mark all as played": all still open episodes of the subscription at once; returns their count. In one transaction
     * and without the show notes: a position saved in between isn't overwritten, and 2000 episodes are a few queries
     * instead of 4000 (review G45).
     */
    suspend fun setAllPlayed(feedId: String): Int = database.withTransaction {
        val now = System.currentTimeMillis()
        val episodes = dao.episodeDurations(feedId)
        val progress = episodes.map { it.bookId }.chunked(MAX_ARGS).flatMap { bookDao.progressOf(it) }.associateBy { it.bookId }
        val rows = episodes
            .filter { progress[it.bookId]?.finished != true }
            .map { playedProgress(it.bookId, it.durationMs, progress[it.bookId], played = true, now) }
        if (rows.isNotEmpty()) bookDao.upsertAllProgress(rows)
        rows.size
    }

    private fun playedProgress(book: BookEntity, old: BookProgressEntity?, played: Boolean, now: Long) =
        playedProgress(book.bookId, book.durationMs, old, played, now)

    private fun playedProgress(bookId: Long, durationMs: Long, old: BookProgressEntity?, played: Boolean, now: Long): BookProgressEntity {
        val duration = durationMs.takeIf { it > 0 } ?: old?.durationMs ?: 0
        return BookProgressEntity(
            bookId = bookId,
            positionMs = if (played) duration else 0,
            durationMs = duration,
            finished = played,
            speed = old?.speed,
            updatedAt = now,
            skipSilence = old?.skipSilence,
            volumeBoostDb = old?.volumeBoostDb,
        )
    }

    /** Resets the episode to the beginning (including the "played" state). */
    suspend fun resetPosition(bookId: Long) = setPlayed(bookId, played = false)

    /**
     * Subscribes to the feed at [input]. An `http://` address is first silently tried over HTTPS and stored that way
     * on success; most hosts serve both. Only if that fails is it subscribed unencrypted.
     * An archived subscription with the same address stays archived and reports [SubscribeResult.Archived]. A new subscription downloads
     * automatically if [PodcastSettings.autoDownloadNew] is on.
     */
    suspend fun subscribe(input: String, auth: Credentials? = null): SubscribeResult {
        val entered = FeedAddress.of(input, auth) ?: return SubscribeResult.InvalidUrl
        knownFeed(entered)?.let { feed ->
            if (feed.archived) return SubscribeResult.Archived(feed.feedId, feed.title)
            if (feed.subscribed) return SubscribeResult.AlreadySubscribed(feed.feedId)
            // From the preview: episodes, progress, downloads and queue stay; it just becomes a subscription.
            subscribeTrial(feed.feedId)
            return SubscribeResult.Added(feed.feedId)
        }
        return fetchAndAdd(entered, subscribed = true)
    }

    /**
     * Opens the preview of the feed at [input] (concept 12.10): if it already exists (subscription or trial subscription), its ID –
     * a trial subscription is synced again in the process; otherwise it is loaded and created as a trial subscription (`subscribed = false`).
     * Result as with [subscribe]: [SubscribeResult.Added] or [SubscribeResult.AlreadySubscribed] carry the ID, an
     * archived subscription reports [SubscribeResult.Archived] (without sync). [imageUrl] is the image from the directory; it
     * applies as long as the feed itself names none.
     */
    suspend fun openPreview(input: String, imageUrl: String? = null): SubscribeResult {
        val entered = FeedAddress.of(input) ?: return SubscribeResult.InvalidUrl
        knownFeed(entered)?.let { feed ->
            if (feed.archived) return SubscribeResult.Archived(feed.feedId, feed.title)
            if (feed.subscribed) return SubscribeResult.AlreadySubscribed(feed.feedId)
            refresh(feed.feedId)
            return SubscribeResult.Added(feed.feedId)
        }
        return fetchAndAdd(entered, subscribed = false, imageUrl)
    }

    /** Turns the trial subscription [feedId] into a subscription, with the default for new subscriptions for the download automation. */
    suspend fun subscribeTrial(feedId: String) = dao.subscribeTrial(feedId, settings.autoDownloadNew.first())

    /** Username and password as entered by the user for a protected feed. */
    data class Credentials(val username: String, val password: String) {
        override fun toString() = "Credentials($username)"
    }

    /** Credentials of the subscription (only valid on the host they were entered for). */
    fun credentials(feedId: String): Flow<FeedAuth?> = credentials.observe(feedId)

    /**
     * A protected feed that permanently moved to another host and rejected the login there: subscription → new address.
     * The credentials rightly don't apply on the new host; newly entered ones belong there (review D25).
     */
    private val deniedMoves = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The address for which the subscription needs credentials, if it isn't its own (moved, see [deniedMoves]). */
    fun credentialsAddress(feedId: String): Flow<String?> = deniedMoves.map { it[feedId] }.distinctUntilChanged()

    /**
     * Stores new credentials for the subscription under its current address – or the one it moved to
     * ([credentialsAddress]) – (`null` deletes them) and syncs it right away. `null` if the subsequent sync failed.
     */
    suspend fun setCredentials(feedId: String, auth: Credentials?): RefreshResult? {
        val feed = dao.feed(feedId) ?: return null
        val scope = normalizeFeedUrl(deniedMoves.value[feedId] ?: feed.url) ?: return null
        credentials.set(feedId, auth?.let { FeedAuth(scope, it.username, it.password) })
        return refresh(feedId)
    }

    /** After "Switch source" with credentials in the new address. */
    internal suspend fun storeCredentials(feedId: String, auth: FeedAuth) = credentials.set(feedId, auth)

    /** Stored credentials of the subscription, for a new source (they only apply if it is on the same host). */
    internal suspend fun storedCredentials(feedId: String): FeedAuth? = credentials.forFeed(feedId)

    /**
     * Subscription address from an input: an RSS/Atom feed or a show from ARD Sounds (subscription address `ardsounds:<id>`
     * or link to the show page, concept 12.10). Credentials in the address (`https://name:password@host/…`) are
     * extracted; entered [auth] takes precedence.
     */
    private sealed interface FeedAddress {
        data class Http(val url: HttpUrl, val auth: FeedAuth? = null) : FeedAddress
        data class ArdSounds(val url: String) : FeedAddress

        companion object {
            fun of(input: String, auth: Credentials? = null): FeedAddress? =
                parseArdSoundsInput(input)?.let(::ArdSounds) ?: normalizeFeedUrl(input)?.let { raw ->
                    val (url, inUrl) = raw.splitCredentials()
                    Http(url, auth?.let { FeedAuth(url, it.username, it.password) } ?: inUrl)
                }
        }
    }

    /** The stored feed for [entered], also under the `https://` variant of an `http://` address. */
    private suspend fun knownFeed(entered: FeedAddress): PodcastFeedEntity? = when (entered) {
        is FeedAddress.ArdSounds -> dao.feedByUrl(entered.url)
        is FeedAddress.Http -> {
            val secure = if (entered.url.isHttps) null else entered.url.newBuilder().scheme("https").build()
            listOfNotNull(secure, entered.url).firstNotNullOfOrNull { dao.feedByUrl(it.toString()) }
        }
    }

    /** Loads the feed and creates it. */
    private suspend fun fetchAndAdd(entered: FeedAddress, subscribed: Boolean, imageUrl: String? = null): SubscribeResult =
        when (val source = fetchSource(entered)) {
            is SourceFetch.Loaded -> add(source.url, source.fallbackTitle, source.fetched, subscribed, imageUrl, source.auth)
            is SourceFetch.Failed -> source.result
        }

    /**
     * Loads the feed or show at [input] without creating anything (switch source, concept 12.10). As when
     * subscribing, an `http://` address is first silently tried over HTTPS. [stored]: the subscription's credentials; they are only
     * sent along if the new source is on the same host and the address itself carries none.
     */
    internal suspend fun fetchSource(input: String, stored: FeedAuth? = null): SourceFetch {
        val entered = FeedAddress.of(input) ?: return SourceFetch.Failed(SubscribeResult.InvalidUrl)
        // Only on the same host: the HTTPS attempt of an `http://` address pins the credentials to its host.
        val withStored = if (entered is FeedAddress.Http && entered.auth == null) {
            entered.copy(auth = stored?.takeIf { it.scope.host == entered.url.host })
        } else {
            entered
        }
        return fetchSource(withStored)
    }

    /**
     * An `http://` address is first silently tried over HTTPS and used that way on success; only if that fails,
     * unencrypted.
     */
    private suspend fun fetchSource(entered: FeedAddress): SourceFetch {
        if (entered is FeedAddress.ArdSounds) {
            val showId = checkNotNull(ardSoundsShowId(entered.url))
            return fetchForSubscribe(entered.url, "ARD Sounds") { FeedFetch.Fetched(ardSounds.fetch(showId), null, null) }
        }
        val url = (entered as FeedAddress.Http).url
        val auth = entered.auth
        val secure = if (url.isHttps) null else url.newBuilder().scheme("https").build()
        if (secure != null) {
            // Under HTTPS, the credentials only apply there (no going back to HTTP).
            val secureAuth = auth?.copy(scope = secure)
            val attempt = fetchForSubscribe(secure.toString(), secure.host, secureAuth) { api.fetch(secure, auth = secureAuth) }
            // If the host requires a login over HTTPS, it exists there; unencrypted, the password would be readable.
            if (attempt is SourceFetch.Loaded || (attempt as SourceFetch.Failed).result is SubscribeResult.CredentialsNeeded) {
                return attempt
            }
            Log.i(TAG, "No HTTPS at ${secure.host}, loading unencrypted")
        }
        return fetchForSubscribe(url.toString(), url.host, auth) { api.fetch(url, auth = auth) }
    }

    /**
     * Loaded source at its final address [url]; [fallbackTitle] applies if it names no title. [auth]: the
     * credentials it was loaded with.
     */
    internal sealed interface SourceFetch {
        data class Loaded(
            val url: String,
            val fallbackTitle: String,
            val fetched: FeedFetch.Fetched,
            val auth: FeedAuth? = null,
        ) : SourceFetch
        data class Failed(val result: SubscribeResult) : SourceFetch
    }

    /** [source] only names the host in the log: private feeds carry their access token in the path or query. */
    private suspend fun fetchForSubscribe(
        url: String,
        source: String,
        auth: FeedAuth? = null,
        fetch: suspend () -> FeedFetch,
    ): SourceFetch = try {
        SourceFetch.Loaded(url, source, fetch() as FeedFetch.Fetched, auth)
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpStatusException) {
        Log.w(TAG, "Feed not loaded: $source (HTTP ${e.status})")
        if (e.status == 401) {
            SourceFetch.Failed(SubscribeResult.CredentialsNeeded(rejected = auth != null))
        } else {
            SourceFetch.Failed(SubscribeResult.Unreachable)
        }
    } catch (e: PodcastFeedException) {
        Log.w(TAG, "Not a feed: $source", e)
        SourceFetch.Failed(SubscribeResult.NotAFeed)
    } catch (e: IOException) {
        Log.w(TAG, "Feed unreachable: $source", e)
        SourceFetch.Failed(SubscribeResult.Unreachable)
    }

    private suspend fun add(
        url: String,
        fallbackTitle: String,
        fetched: FeedFetch.Fetched,
        subscribed: Boolean,
        imageUrl: String?,
        auth: FeedAuth?,
    ): SubscribeResult {
        val feed = PodcastFeedEntity(
            feedId = UUID.randomUUID().toString(),
            url = url,
            title = fetched.feed.title.ifBlank { fallbackTitle },
            imageUrl = imageUrl,
            autoDownload = subscribed && settings.autoDownloadNew.first(),
            subscribed = subscribed,
        )
        // Before creating: the download automation of a new subscription needs them immediately.
        if (auth != null) credentials.set(feed.feedId, auth)
        val existing = mutex.withLock {
            // Checked again under the lock: while loading, a parallel subscribe, OPML import or preview may have created the
            // address, and the replacing write would silently delete that subscription (review2 J29).
            dao.feedByUrl(url) ?: run {
                apply(feed, fetched, create = true)
                null
            }
        } ?: return SubscribeResult.Added(feed.feedId)
        if (auth != null) credentials.set(feed.feedId, null)
        return when {
            existing.archived -> SubscribeResult.Archived(existing.feedId, existing.title)
            existing.subscribed -> SubscribeResult.AlreadySubscribed(existing.feedId)
            subscribed -> {
                subscribeTrial(existing.feedId)
                SubscribeResult.Added(existing.feedId)
            }
            else -> SubscribeResult.Added(existing.feedId)
        }
    }

    suspend fun unsubscribe(feedId: String) = dao.deleteFeed(feedId)

    /**
     * Archives the subscription: it disappears from all lists and is no longer synced; episodes, progress and
     * statistics stay. The caller deletes the downloads (`EpisodeDownloads.removeAllOf`).
     */
    suspend fun archive(feedId: String) = dao.setArchived(feedId, true)

    /**
     * Brings back an archived subscription and syncs it right away. Whatever was published since archiving thus counts
     * as known and doesn't trigger a notification. `false` if the sync failed (the subscription is active anyway).
     */
    suspend fun reactivate(feedId: String): Boolean {
        dao.setArchived(feedId, false)
        return refresh(feedId) != null
    }

    /** IDs of the trial subscriptions (concept 12.10). */
    val trialFeedIds: Flow<List<String>> = dao.observeTrialFeedIds()

    /**
     * Those of [bookIds] that belong to a trial subscription: they keep a download and their progress only while they are in the
     * queue (`planTrialCleanup`).
     */
    suspend fun trialEpisodeIds(bookIds: Collection<Long>): Set<Long> =
        bookIds.distinct().chunked(MAX_ARGS).flatMapTo(HashSet()) { dao.trialEpisodeIds(it) }

    /** Episodes of the trial subscriptions with progress and download state, for cleanup. */
    val trialEpisodes: Flow<List<TrialEpisode>> = dao.observeTrialEpisodeStates().map { rows ->
        rows.map { TrialEpisode(it.bookId, it.feedId, it.finished, it.hasProgress, it.downloadState.isDownloadPresent()) }
    }

    /** Forgets the position of an episode (`EpisodeDownloads` deletes its file). */
    suspend fun forgetProgress(bookId: Long) = dao.deleteProgress(bookId)

    /**
     * The subscriptions as OPML text. Shows from ARD Sounds are listed as an RSS bridge feed under [rssBridge] (concept
     * 12.10); without a bridge they are missing, like the podcasts from the device, which have no feed.
     */
    suspend fun exportOpml(title: String, createdAt: String, rssBridge: String?): String =
        Opml.write(
            dao.feeds().filter { !it.isLocal }.sortedBy { it.title.lowercase() }.mapNotNull { feed ->
                val showId = ardSoundsShowId(feed.url) ?: return@mapNotNull OpmlFeed(feed.title, feed.url)
                rssBridge?.let { ardSoundsBridgeUrl(it, showId) }?.let { OpmlFeed(feed.title, it) }
            },
            title,
            createdAt,
        )

    /**
     * Adopts the subscriptions from [feeds]. Subscriptions that already exist (or appear several times in the file) are skipped.
     * With [replace], all other subscriptions are removed first (including episodes and listening progress); podcasts from the device stay, because
     * an OPML file can't contain them, as do ARD Sounds subscriptions if the file contains none (without an RSS bridge
     * EarLoft doesn't export them). A file without subscriptions replaces nothing. RSS bridge feeds for ARD Sounds (and links to show pages) become
     * subscriptions `ardsounds:<id>` again, which work without the bridge. Credentials in an address
     * (`https://name:password@host/feed`) are extracted and stored as when subscribing, not part of the subscription address
     * (otherwise they would end up in exports, the backup and on the watch, review D6). The episodes of the new subscriptions only come with the
     * next sync.
     *
     * A subscription counts as already there as when subscribing ([knownFeed]): also under the `https://` variant of an
     * `http://` address in the file (review J2). [reactivateArchived] `false`: an archived subscription stays archived (database import,
     * whose file states itself whether a subscription is archived).
     */
    suspend fun importOpml(feeds: List<OpmlFeed>, replace: Boolean, reactivateArchived: Boolean = true): OpmlImportResult = mutex.withLock {
        val auths = HashMap<String, FeedAuth>()
        val wanted = opmlWanted(feeds, auths)
        var removed = 0
        if (replace) {
            replacedBy(wanted).forEach {
                dao.deleteFeed(it.feedId)
                removed++
            }
        }
        val fresh = wanted.filter { (_, stored) ->
            stored == null || !stored.subscribed || (reactivateArchived && stored.archived)
        }
        val autoDownload = settings.autoDownloadNew.first()
        fresh.forEach { (feed, stored) ->
            // A trial or archived subscription with the same address becomes a subscription (again) (the address is unique).
            // Only the trial subscription gets the default for new subscriptions; the archived one keeps its setting.
            val feedId = if (stored != null) {
                if (!stored.subscribed) dao.subscribeTrial(stored.feedId, autoDownload)
                if (reactivateArchived) dao.setArchived(stored.feedId, false)
                stored.feedId
            } else {
                UUID.randomUUID().toString().also { feedId ->
                    dao.upsertFeed(PodcastFeedEntity(feedId = feedId, url = feed.url, title = feed.title, autoDownload = autoDownload))
                }
            }
            // Already stored (entered) credentials take precedence.
            auths[feed.url]?.let { auth -> if (credentials.forFeed(feedId) == null) credentials.set(feedId, auth) }
        }
        OpmlImportResult(added = fresh.size, skipped = feeds.size - fresh.size, removed = removed)
    }

    /** How many subscriptions [importOpml] with `replace` would remove for [feeds] (for the confirmation). */
    suspend fun opmlReplaceRemovals(feeds: List<OpmlFeed>): Int = replacedBy(opmlWanted(feeds, HashMap())).size

    /** The stored subscription for [url] as when subscribing (also the `https://` variant of an `http://` address); `null` if there is none. */
    suspend fun storedFeed(url: String): PodcastFeedEntity? = FeedAddress.of(url)?.let { knownFeed(it) }

    /**
     * The subscriptions from [feeds] with the address they get here and the stored feed they match (or `null`). Credentials in an
     * address go to [auths]; entries for the same subscription count once.
     */
    private suspend fun opmlWanted(feeds: List<OpmlFeed>, auths: MutableMap<String, FeedAuth>): List<Pair<OpmlFeed, PodcastFeedEntity?>> =
        feeds.mapNotNull { feed ->
            val address = FeedAddress.of(feed.url) ?: return@mapNotNull null
            val imported = when (address) {
                is FeedAddress.ArdSounds -> feed.copy(url = address.url)
                is FeedAddress.Http -> feed.copy(url = address.url.toString()).also { imported ->
                    address.auth?.let { auths.putIfAbsent(imported.url, it) }
                }
            }
            imported to knownFeed(address)
        }.distinctBy { (feed, stored) -> stored?.feedId ?: feed.url }

    /**
     * What "replace" removes: all other active subscriptions; podcasts from the device stay, as do ARD Sounds subscriptions if
     * the file contains none. A file without subscriptions replaces nothing.
     */
    private suspend fun replacedBy(wanted: List<Pair<OpmlFeed, PodcastFeedEntity?>>): List<PodcastFeedEntity> {
        if (wanted.isEmpty()) return emptyList()
        val keep = wanted.mapNotNullTo(HashSet()) { it.second?.feedId }
        val fileHasArdSounds = wanted.any { ardSoundsShowId(it.first.url) != null }
        return dao.feeds().filter { !it.isLocal && it.feedId !in keep && (fileHasArdSounds || ardSoundsShowId(it.url) == null) }
    }

    suspend fun setAutoDownload(feedId: String, enabled: Boolean) = dao.setAutoDownload(feedId, enabled)

    suspend fun setNewestFirst(feedId: String, newestFirst: Boolean) = dao.setNewestFirst(feedId, newestFirst)

    suspend fun setContinuous(feedId: String, continuous: Boolean) = dao.setContinuous(feedId, continuous)

    suspend fun setNotifyNew(feedId: String, enabled: Boolean) = dao.setNotifyNew(feedId, enabled)

    suspend fun setEpisodeList(feedId: String, filter: EpisodeFilter, oldestFirst: Boolean) =
        dao.setEpisodeList(feedId, filter, oldestFirst)

    suspend fun setQueueMode(feedId: String, mode: FeedQueueMode) = dao.setQueueMode(feedId, mode)

    suspend fun setKeepEpisodes(feedId: String, keep: Int) = dao.setKeepEpisodes(feedId, keep.coerceAtLeast(0))

    /** `null` = default from the settings. */
    suspend fun setAutoDownloadCount(feedId: String, count: Int?) = dao.setAutoDownloadCount(feedId, count?.coerceAtLeast(1))

    /**
     * The unplayed episode that follows [bookId] in the podcast's order and is queued in the "Continue listening"
     * row. `null` for audiobooks, if queuing is off for the podcast, or if nothing unplayed follows.
     */
    suspend fun autoNextAfter(bookId: Long): Long? {
        val book = bookDao.book(bookId)?.takeIf { it.kind == BookKind.EPISODE } ?: return null
        // Trial subscriptions don't queue anything on their own: the queue only holds what was explicitly played or downloaded.
        val feed = dao.feed(book.serverId)?.takeIf { it.continuous && it.subscribed } ?: return null
        return nextEpisode(playOrder(feed), dao.finishedEpisodeIds(feed.feedId).toSet(), bookId)
    }

    private suspend fun playOrder(feed: PodcastFeedEntity): List<Long> =
        dao.episodeIdsOldestFirst(feed.feedId).let { if (feed.newestFirst) it.asReversed() else it }

    /**
     * Syncs all subscriptions, with [which] only some of them (the stale ones when opening the app). The episodes of a subscription being synced
     * for the first time don't count as new (otherwise every fresh subscription would report its entire episode list).
     */
    suspend fun refreshAll(which: (PodcastFeedEntity) -> Boolean = { true }): PodcastRefreshSummary {
        val failures = mutableMapOf<String, FeedError>()
        val news = mutableListOf<NewEpisodes>()
        val moved = mutableListOf<String>()
        val feeds = dao.feeds().filter { !it.isLocal && which(it) }
        feeds.forEach { feed ->
            when (val outcome = mutex.withLock { refreshLocked(feed.feedId) }) {
                is RefreshOutcome.Failed -> failures[feed.feedId] = outcome.error
                is RefreshOutcome.Done -> {
                    val ids = outcome.result.newEpisodeIds
                    if (feed.lastRefreshAt > 0 && ids.isNotEmpty()) {
                        news += NewEpisodes(feed.feedId, feed.title, ids, feed.notifyNew, feed.autoDownload)
                    }
                    if (follow(feed.feedId, outcome.movedTo)) moved += feed.title
                }
            }
        }
        val allFailed = failures.isNotEmpty() && failures.size == feeds.size
        // If no feed is reachable, it's the network, not the subscriptions: that doesn't count as their failure.
        val now = System.currentTimeMillis()
        failures.filterValues { !allFailed || it != FeedError.UNREACHABLE }
            .forEach { (feedId, error) -> dao.recordRefreshFailure(feedId, error, now) }
        return PodcastRefreshSummary(ok = failures.isEmpty(), newEpisodes = news, allFailed = allFailed, moved = moved)
    }

    /**
     * Syncs a feed. `null` if it is unreachable or unreadable (the old episodes stay, the failure
     * counts toward the subscription's error status). If the feed announces a new address, the subscription moves there afterwards.
     */
    suspend fun refresh(feedId: String): RefreshResult? =
        when (val outcome = mutex.withLock { refreshLocked(feedId) }) {
            is RefreshOutcome.Failed -> {
                dao.recordRefreshFailure(feedId, outcome.error, System.currentTimeMillis())
                null
            }
            is RefreshOutcome.Done -> outcome.result.also { follow(feedId, outcome.movedTo) }
        }

    /** Result of a fetch: adopted (with announced new address) or failed. */
    private sealed interface RefreshOutcome {
        data class Done(val result: RefreshResult, val movedTo: String? = null) : RefreshOutcome
        data class Failed(val error: FeedError) : RefreshOutcome
    }

    private suspend fun refreshLocked(feedId: String): RefreshOutcome {
        val feed = dao.feed(feedId) ?: return RefreshOutcome.Done(RefreshResult(emptyList()))
        // Podcasts from the device have no feed; [LocalPodcasts] syncs their episodes.
        if (feed.isLocal) return RefreshOutcome.Done(RefreshResult(emptyList()))
        val fetched = try {
            fetch(feed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Sync of ${feed.title} failed", e)
            val moved = (e as? HttpStatusException)?.takeIf { it.status == 401 }?.movedTo
            deniedMoves.update { if (moved != null) it + (feedId to moved) else it - feedId }
            return RefreshOutcome.Failed(feedError(e))
        }
        deniedMoves.update { it - feedId }
        val result = when (fetched) {
            is FeedFetch.NotModified -> {
                dao.recordNotModified(feedId, System.currentTimeMillis())
                RefreshResult(emptyList())
            }
            is FeedFetch.Fetched -> apply(feed, fetched)
        }
        _refreshed.tryEmit(feedId)
        val movedTo = fetched.movedTo ?: (fetched as? FeedFetch.Fetched)?.feed?.newFeedUrl
        return RefreshOutcome.Done(result, movedTo?.takeIf { !sameAddress(it, feed.url) })
    }

    /** Moves the subscription to the new address [movedTo] (concept 12.10 "Feed move"); `true` if it moved. */
    private suspend fun follow(feedId: String, movedTo: String?): Boolean =
        movedTo != null && sourceSwitch.get().follow(feedId, movedTo)

    /** Shows from ARD Sounds come via GraphQL, without a conditional fetch; an error there only affects them. */
    private suspend fun fetch(feed: PodcastFeedEntity): FeedFetch {
        ardSoundsShowId(feed.url)?.let { return FeedFetch.Fetched(ardSounds.fetch(it), etag = null, lastModified = null) }
        return api.fetch(feed.url.toHttpUrlOrThrow(), feed.etag, feed.lastModified, credentials.forFeed(feed.feedId))
    }

    /**
     * Runs [block] while no sync is running (switch source, concept 12.10): a sync of the old source
     * in between would bring back the old IDs as new episodes.
     */
    internal suspend fun <T> withFeedLock(block: suspend () -> T): T = mutex.withLock { block() }

    /** Adopts [fetched] for [feed]; only inside [withFeedLock]. */
    internal suspend fun applyLocked(feed: PodcastFeedEntity, fetched: FeedFetch.Fetched): RefreshResult =
        apply(feed, fetched).also { _refreshed.tryEmit(feed.feedId) }

    /**
     * In one transaction: an ARD show brings hundreds of episodes; written individually, each episode was its own commit and
     * its own UI notification (5 s for 352 episodes on the emulator, now 0.3 s).
     */
    private suspend fun apply(feed: PodcastFeedEntity, fetched: FeedFetch.Fetched, create: Boolean = false): RefreshResult {
        // Queued episodes stay even if the feed no longer lists them; the order lives outside the database.
        val queued = queue.queuedRemoteIds(feed.feedId).take(MAX_ARGS)
        val renamed = mutableMapOf<String, String>()
        var url = feed.url
        val result = database.withTransaction {
            // Read again: settings, "Subscribe" or "Archive" changed during the fetch must not be overwritten with the state
            // from before it, and a subscription ended meanwhile must not come back (review G2).
            val current = dao.feed(feed.feedId) ?: feed.takeIf { create }
            url = current?.url ?: url
            if (current == null) RefreshResult(emptyList()) else applyInTransaction(current, fetched, queued, renamed)
        }
        if (renamed.isNotEmpty()) sourceSwitch.get().episodesRenamed(feed.feedId, url, renamed)
        return result
    }

    private suspend fun applyInTransaction(
        feed: PodcastFeedEntity,
        fetched: FeedFetch.Fetched,
        queued: List<String>,
        renamed: MutableMap<String, String>,
    ): RefreshResult {
        val parsed = fetched.feed
        val generation = feed.syncGeneration + 1
        val known = bookDao.booksOf(feed.feedId).associateBy { it.remoteId }
        val title = parsed.title.ifBlank { feed.title }
        // Some feeds name no image (or an empty one, e.g. those of the rbb stations): then the known one stays – including the one from
        // the directory –, otherwise that of the newest episode.
        val image = parsed.imageUrl ?: feed.imageUrl ?: parsed.episodes.firstNotNullOfOrNull { it.imageUrl }
        val now = System.currentTimeMillis()
        val newIds = mutableListOf<Long>()
        val knownPersons = bookDao.personsOf(feed.feedId).groupBy { it.bookId }
        // Episodes without <guid> are identified by their audio address. A new tracking prefix or token would make every one
        // of them new (notification, auto-download) and leave the started ones behind as duplicates (review G27): such an
        // episode takes over a known one without guid that the feed no longer lists, with the same title and date or file.
        val listed = parsed.episodes.mapTo(HashSet()) { it.guid }
        val unlisted = known.values.filterTo(mutableListOf()) { it.remoteId !in listed && it.remoteId.isWebAddress() }
        // The feed lists the newest episode first; the first episode of a duplicate guid wins.
        parsed.episodes.distinctBy { it.guid }.forEach { episode ->
            val existing = known[episode.guid] ?: unlisted.takeIf { episode.guid == episode.audioUrl }
                ?.firstOrNull { it.isSameEpisodeAs(episode) }
                ?.let { previous ->
                    unlisted.remove(previous)
                    dao.setRemoteId(previous.bookId, episode.guid)
                    renamed[previous.remoteId] = episode.guid
                    previous.copy(remoteId = episode.guid)
                }
            val id = bookDao.upsertBook(episode.toEntity(feed.feedId, title, image, existing, generation, now))
            if (existing == null) newIds += id
            // Contributors come back with every sync; only what changed is written.
            val persons = episode.persons.mapIndexed { index, p -> EpisodePersonEntity(id, index, p.name, p.role, p.group, p.imageUrl, p.url) }
            if (knownPersons[id].orEmpty() != persons) bookDao.replacePersons(id, persons)
            val track = BookTrackEntity(id, 0, episode.audioUrl, 0, episode.durationMs.takeIf { it > 0 } ?: existing?.durationMs ?: 0, episode.mimeType)
            // The download state isn't part of the feed and must not trigger a write.
            val stored = bookDao.tracks(id).map { it.copy(downloadState = TrackDownload.NONE, filePath = null, fileSize = 0) }
            if (stored != listOf(track)) dao.setTrack(track)
            if (episode.chapters.isNotEmpty()) storeChapters(id, episode.chapters, ChapterSource.FEED, track.durationMs, existing?.chapterSource)
        }
        if (shrinksPlausibly(known.values, parsed.episodes.mapTo(HashSet()) { it.guid } + renamed.keys)) {
            dao.deleteUnseenEpisodes(feed.feedId, generation, queued)
        } else {
            Log.w(TAG, "${feed.title}: feed lists ${parsed.episodes.size} episodes, sweep postponed")
        }
        dao.upsertFeed(
            feed.copy(
                title = title,
                author = parsed.author,
                description = parsed.description.toDescriptionHtml(),
                imageUrl = image,
                websiteUrl = parsed.websiteUrl,
                podcastGuid = parsed.podcastGuid,
                fundingUrl = parsed.fundingUrl,
                fundingLabel = parsed.fundingLabel.takeIf { parsed.fundingUrl != null },
                etag = fetched.etag,
                lastModified = fetched.lastModified,
                lastRefreshAt = now,
                syncGeneration = generation,
            ).refreshSucceeded(),
        )
        return RefreshResult(newIds)
    }

    /**
     * Before playback (concept 12.5 "Chapter sources"): loads the episode's chapter list (JSON) if the feed offers one
     * and it isn't loaded yet – it takes precedence over chapters from the feed and from the file. If the episode then has no
     * source at all, they come from the downloaded file ([chaptersFromFile]). Errors are non-critical.
     *
     * Startup waits at most [CHAPTERS_TIMEOUT_MS] for it. An empty or rejected list (404 …) is only requested again after
     * [CHAPTERS_ANSWERED_RETRY_MS], an unreachable one after [CHAPTERS_OFFLINE_RETRY_MS]; a new address
     * in the feed immediately ([chapterMisses]).
     */
    suspend fun ensureChapters(bookId: Long, now: () -> Long = System::currentTimeMillis) {
        val book = bookDao.book(bookId) ?: return
        if (book.kind != BookKind.EPISODE) return
        val url = book.chaptersUrl
        val miss = chapterMisses[bookId]?.takeIf { it.url == url }
        if (url != null && book.chapterSource != ChapterSource.JSON && (miss == null || now() >= miss.retryAtMs)) {
            // On IO: the deadline runs in real time, even under the virtual time of the tests.
            val chapters = withContext(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(CHAPTERS_TIMEOUT_MS) { api.chapters(url) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpStatusException) {
                    Log.w(TAG, "Chapters of ${book.title} rejected", e)
                    // An overloaded server (5xx, 429) counts like no connection.
                    if (e.status >= 500 || e.status == 429) null else emptyList()
                } catch (e: IOException) {
                    Log.w(TAG, "Chapters of ${book.title} not available", e)
                    null
                }
            }
            if (!chapters.isNullOrEmpty()) {
                chapterMisses.remove(bookId)
                storeChapters(bookId, chapters, ChapterSource.JSON, book.durationMs, book.chapterSource)
                return
            }
            val retryIn = if (chapters == null) CHAPTERS_OFFLINE_RETRY_MS else CHAPTERS_ANSWERED_RETRY_MS
            chapterMisses[bookId] = ChapterMiss(url, now() + retryIn)
        }
        if (book.chapterSource == null) chaptersFromFile(bookId)
    }

    /** A chapter list that was last empty, rejected or unreachable; in memory only (one attempt per process). */
    private data class ChapterMiss(val url: String, val retryAtMs: Long)

    private val chapterMisses = ConcurrentHashMap<Long, ChapterMiss>()

    /**
     * Chapters from the episode's downloaded file (ID3 `CHAP`, MP4) if it has none from another source yet; after the
     * download and before playback. A file without chapters is also noted so it isn't read every time.
     */
    suspend fun chaptersFromFile(bookId: Long) {
        val book = bookDao.book(bookId)?.takeIf { it.kind == BookKind.EPISODE && it.chapterSource == null } ?: return
        // Chapters without a noted source come from a chapter list (before schema v29, or v30 for reloading); they stay.
        if (bookDao.chapters(bookId).isNotEmpty()) return
        val track = bookDao.tracks(bookId).firstOrNull { it.downloadState == TrackDownload.DONE } ?: return
        val file = track.filePath?.let(::File)?.takeIf { it.isFile } ?: return
        val chapters = fileChapters.read(file, track.mimeType, book.durationMs)
        // A higher-ranked source may have written in the meantime (sync, JSON); then its list stays.
        if (bookDao.chapterSource(bookId) != null) return
        bookDao.replaceChapters(
            bookId,
            chapters.mapIndexed { index, chapter -> BookChapterEntity(bookId, index, chapter.title, chapter.startMs, chapter.endMs) },
            ChapterSource.FILE,
        )
    }

    /**
     * Writes chapters from [source], provided the existing ones ([current]) don't have a higher rank; identical chapters are not
     * rewritten (the feed brings them again on every sync). Each chapter ends at the start of the next, the last one
     * at the end of the episode.
     */
    private suspend fun storeChapters(bookId: Long, chapters: List<ParsedChapter>, source: ChapterSource, durationMs: Long, current: ChapterSource?) {
        if (current != null && current > source) return
        val lastStart = chapters.last().startMs
        val end = durationMs.takeIf { it > lastStart } ?: (lastStart + 1)
        val entities = chapters.mapIndexed { index, chapter ->
            BookChapterEntity(
                bookId,
                index,
                chapter.title.ifBlank { "${index + 1}" },
                chapter.startMs,
                chapters.getOrNull(index + 1)?.startMs ?: end,
                imageUrl = chapter.imageUrl,
                url = chapter.url,
            )
        }
        if (current == source && bookDao.chapters(bookId) == entities) return
        bookDao.replaceChapters(bookId, entities, source)
    }

    private fun String.isWebAddress() = startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

    /** For episodes without guid (review G27): same title and the same date or the same file name in the address. */
    private fun BookEntity.isSameEpisodeAs(episode: ParsedEpisode): Boolean {
        if (episode.title.isBlank() || normTitle != episode.title.normalizeForMerge()) return false
        fun fileName(url: String) = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        return (episode.publishedAt > 0 && addedAt == episode.publishedAt) || fileName(remoteId) == fileName(episode.audioUrl)
    }

    private fun ParsedEpisode.toEntity(
        feedId: String,
        feedTitle: String,
        feedImage: String?,
        existing: BookEntity?,
        generation: Long,
        now: Long,
    ) = BookEntity(
        serverId = feedId,
        kind = BookKind.EPISODE,
        remoteId = guid,
        title = title.ifBlank { guid },
        normTitle = title.normalizeForMerge(),
        author = feedTitle,
        description = description.toDescriptionHtml(),
        durationMs = durationMs.takeIf { it > 0 } ?: existing?.durationMs ?: 0,
        // Without a date in the feed, the time the episode first appeared stays.
        addedAt = publishedAt.takeIf { it > 0 } ?: existing?.addedAt ?: now,
        // Files come from the feed; there is no server that would have to deliver them later.
        detailsLoaded = true,
        seenInGeneration = generation,
        coverUrl = imageUrl ?: feedImage,
        chaptersUrl = chaptersUrl,
        webUrl = webUrl,
        chapterSource = existing?.chapterSource,
        season = season,
        episodeNumber = episodeNumber,
        episodeType = episodeType,
        transcriptUrl = transcriptUrl,
        transcriptType = transcriptType,
    )

    private fun String.toHttpUrlOrThrow() = normalizeFeedUrl(this) ?: throw IOException("Invalid feed address: $this")

    private companion object {
        const val TAG = "PodcastRepository"
        const val NEW_DAYS = 14L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val MAX_NEW = 20

        /** As for books (`BookRepository.recentlyAdded`). */
        const val RECENT_LIMIT = 12

        /** "New" tab: enough to browse through without loading an archive with thousands of episodes into one list. */
        const val LATEST_LIMIT = 100

        /** Beginning of the show notes for the summary ([DescriptionStarts]); HTML included, enough for two lines of text. */
        const val NOTES_CHARS = 2_000

        /** Length of the history: enough to find things again without loading every episode ever played. */
        const val HISTORY_LIMIT = 200

        /** SQLite limit for parameters per query, with margin. */
        const val MAX_ARGS = 500

        /** How long starting an episode waits for its chapter list (like `BookProgressSync.PULL_TIMEOUT_MS`). */
        const val CHAPTERS_TIMEOUT_MS = 3_000L

        /** Empty or rejected: some feeds add the chapters a few hours after the episode. */
        const val CHAPTERS_ANSWERED_RETRY_MS = 6 * 60 * 60 * 1000L

        /** Unreachable or too slow. */
        const val CHAPTERS_OFFLINE_RETRY_MS = 10 * 60 * 1000L
    }
}

private val PodcastFeedEntity.isLocal: Boolean get() = feedId.startsWith(LOCAL_PODCAST_PREFIX)

/** A subscription with an RSS/Atom feed, i.e. neither from the device nor from ARD Sounds; only those are known to OPML and other apps. */
internal val PodcastFeedEntity.hasRssFeed: Boolean get() = !isLocal && ardSoundsShowId(url) == null

private fun PodcastFeedEntity.toModel() = PodcastFeed(
    id = feedId,
    url = url,
    title = title,
    author = author,
    description = description,
    imageUrl = imageUrl,
    autoDownload = autoDownload,
    lastRefreshAt = lastRefreshAt,
    newestFirst = newestFirst,
    continuous = continuous,
    notifyNew = notifyNew,
    subscribed = subscribed,
    archived = archived,
    effects = EffectOverrides(speed, skipSilence, volumeBoostDb),
    skipIntroS = skipIntroS,
    skipOutroS = skipOutroS,
    episodeFilter = episodeFilter,
    listOldestFirst = listOldestFirst,
    queueMode = queueMode,
    keepEpisodes = keepEpisodes,
    autoDownloadCount = autoDownloadCount,
    failedRefreshes = failedRefreshes,
    refreshError = lastError,
    failingSince = failingSince,
    fundingUrl = fundingUrl,
    fundingLabel = fundingLabel.orEmpty(),
)

/** A successful sync ends the streak of failures. */
private fun PodcastFeedEntity.refreshSucceeded() = copy(failedRefreshes = 0, lastError = null, failingSince = 0)

/** Reason for a failed fetch, for the subscription's error status. */
internal fun feedError(e: IOException): FeedError = when {
    e is PodcastFeedException -> FeedError.NOT_A_FEED
    e is HttpStatusException && (e.status == 404 || e.status == 410) -> FeedError.GONE
    e is HttpStatusException && (e.status == 401 || e.status == 403) -> FeedError.DENIED
    e is HttpStatusException -> FeedError.SERVER
    e is SSLException -> FeedError.TLS
    else -> FeedError.UNREACHABLE
}

/**
 * Trailers (`itunes:episodeType`) are dropped once their subscription has real episodes in the list (concept 12.5 "Podcasting 2.0");
 * started, played and saved ones stay.
 */
internal fun List<BookEntity>.withoutTrailers(byBook: Map<Long, BookProgressEntity>): List<BookEntity> {
    if (none { it.episodeType == EpisodeType.TRAILER }) return this
    val withEpisodes = filter { it.episodeType != EpisodeType.TRAILER }.mapTo(HashSet()) { it.serverId }
    return filter { it.episodeType != EpisodeType.TRAILER || it.serverId !in withEpisodes || !byBook[it.bookId].isUntouched || it.starredAt != null }
}

/** Never started and not marked as played. */
private val BookProgressEntity?.isUntouched: Boolean get() = this == null || (!finished && positionMs <= 0)

/** Episodes (newest first) without those hidden by their subscription's "Keep episodes". */
private fun List<BookEntity>.visibleIn(feeds: List<PodcastFeedEntity>, byBook: Map<Long, BookProgressEntity>): List<BookEntity> {
    val keep = feeds.filter { it.keepEpisodes > 0 }.associate { it.feedId to it.keepEpisodes }
    val shown = withoutTrailers(byBook)
    if (keep.isEmpty()) return shown
    return shown.keepNewest({ it.serverId }, { keep[it] ?: 0 }) { byBook[it.bookId].isUntouched && it.starredAt == null }
}

/**
 * Descriptions come as HTML and keep their tags; the UI renders them. Some feeds escape
 * the HTML twice (after parsing, `&lt;p&gt;` appears instead of `<p>`): this is resolved once here.
 */
internal fun String.toDescriptionHtml(): String {
    val text = trim()
    return if ('<' !in text && text.contains("&lt;")) Html.fromHtml(text, Html.FROM_HTML_MODE_COMPACT).toString().trim() else text
}

/**
 * Whether the sweep of unlisted episodes may run for this sync (review G3). A feed that suddenly lists no episodes (a fault at
 * the publisher) never sweeps. If more than half of the episodes the last listing sync saw are missing, the sweep waits one
 * sync: the episodes the feed still lists get the new generation, so the next sync compares against the shrunk list and only
 * then removes the rest. A publisher really trimming the archive thus takes effect one sync later.
 *
 * The comparison uses the newest generation an episode carries, not the feed's: an empty sync raises the feed's generation
 * without giving it to any episode, and a partly recovered feed right after it would otherwise sweep at once.
 */
internal fun shrinksPlausibly(known: Collection<BookEntity>, listed: Set<String>): Boolean {
    if (listed.isEmpty()) return false
    val lastGeneration = known.maxOfOrNull { it.seenInGeneration } ?: return true
    val lastListed = known.filter { it.seenInGeneration == lastGeneration }
    val missing = lastListed.count { it.remoteId !in listed }
    return missing * 2 <= lastListed.size
}
