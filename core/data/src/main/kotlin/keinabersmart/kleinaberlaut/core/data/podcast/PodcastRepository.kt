package keinabersmart.kleinaberlaut.core.data.podcast

import android.text.Html
import android.util.Log
import keinabersmart.kleinaberlaut.core.common.normalizeForMerge
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.dao.PodcastDao
import keinabersmart.kleinaberlaut.core.database.entity.BookChapterEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookTrackEntity
import keinabersmart.kleinaberlaut.core.database.entity.PodcastFeedEntity
import keinabersmart.kleinaberlaut.core.data.spoken.toModel
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.model.PodcastFeed
import keinabersmart.kleinaberlaut.core.model.TrackDownload
import keinabersmart.kleinaberlaut.core.network.podcast.FeedFetch
import keinabersmart.kleinaberlaut.core.network.podcast.ParsedEpisode
import keinabersmart.kleinaberlaut.core.network.podcast.ParsedFeed
import keinabersmart.kleinaberlaut.core.network.podcast.PodcastFeedApi
import keinabersmart.kleinaberlaut.core.network.podcast.PodcastFeedException
import keinabersmart.kleinaberlaut.core.network.podcast.normalizeFeedUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SubscribeResult {
    data class Added(val feedId: String) : SubscribeResult
    data class AlreadySubscribed(val feedId: String) : SubscribeResult
    data object InvalidUrl : SubscribeResult
    data object Unreachable : SubscribeResult
    data object NotAFeed : SubscribeResult
}

/** Ergebnis eines Feed-Abgleichs: `null` bei Fehler, sonst die Folgen, die neu im Feed stehen. */
data class RefreshResult(val newEpisodeIds: List<Long>)

/** Podcast-Abos und ihre Folgen (Konzept 12.1): abonnieren, abgleichen, Kapitel nachladen. */
@Singleton
class PodcastRepository @Inject constructor(
    private val dao: PodcastDao,
    private val bookDao: BookDao,
    private val api: PodcastFeedApi,
) {
    private val mutex = Mutex()

    private val _refreshed = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Meldet die Id jedes Podcasts, dessen Feed gerade abgeglichen wurde (auch ohne Änderung), für die Download-Automatik. */
    val refreshed: SharedFlow<String> = _refreshed.asSharedFlow()

    val feeds: Flow<List<PodcastFeed>> = dao.observeFeeds().map { list -> list.map { it.toModel() } }

    fun feed(feedId: String): Flow<PodcastFeed?> = dao.observeFeed(feedId).map { it?.toModel() }

    /** Download-Zustand je Folge eines Podcasts (Folgen ohne Eintrag sind nicht geladen). */
    fun downloadStates(feedId: String): Flow<Map<Long, TrackDownload>> =
        dao.observeDownloadStates(feedId).map { rows -> rows.associate { it.bookId to it.state } }

    /** Folgen eines Podcasts, neueste zuerst, mit Fortschritt. */
    fun episodes(feedId: String): Flow<List<BookWithProgress>> =
        combine(dao.observeEpisodesOf(feedId), bookDao.observeAllProgress()) { episodes, progress ->
            val byBook = progress.associateBy { it.bookId }
            episodes.map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) }
        }

    /**
     * „Weiterhören“: angefangene Folgen mit der zuletzt gehörten ganz vorn, dahinter die neuen, noch nicht
     * angefangenen Folgen der letzten [NEW_DAYS] Tage (neueste zuerst).
     */
    fun continueListening(now: () -> Long = System::currentTimeMillis): Flow<List<BookWithProgress>> =
        combine(dao.observeEpisodes(), bookDao.observeAllProgress()) { episodes, progress ->
            val byBook = progress.associateBy { it.bookId }
            val started = episodes
                .mapNotNull { e -> byBook[e.bookId]?.takeIf { !it.finished && it.positionMs > 0 }?.let { e to it } }
                .sortedByDescending { (_, p) -> p.updatedAt }
                .map { (e, p) -> BookWithProgress(e.toModel(), p.toModel()) }
            val since = now() - NEW_DAYS * DAY_MS
            val fresh = episodes
                .filter { e -> (byBook[e.bookId]?.positionMs ?: 0L) == 0L && byBook[e.bookId]?.finished != true && e.addedAt >= since }
                .take(MAX_NEW)
                .map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) }
            started + fresh
        }

    suspend fun subscribe(input: String): SubscribeResult {
        val url = normalizeFeedUrl(input) ?: return SubscribeResult.InvalidUrl
        dao.feedByUrl(url.toString())?.let { return SubscribeResult.AlreadySubscribed(it.feedId) }
        val fetched = try {
            api.fetch(url) as FeedFetch.Fetched
        } catch (e: CancellationException) {
            throw e
        } catch (e: PodcastFeedException) {
            Log.w(TAG, "Kein Feed: $url", e)
            return SubscribeResult.NotAFeed
        } catch (e: IOException) {
            Log.w(TAG, "Feed nicht erreichbar: $url", e)
            return SubscribeResult.Unreachable
        }
        val feed = PodcastFeedEntity(feedId = UUID.randomUUID().toString(), url = url.toString(), title = fetched.feed.title.ifBlank { url.host })
        mutex.withLock { apply(feed, fetched) }
        return SubscribeResult.Added(feed.feedId)
    }

    suspend fun unsubscribe(feedId: String) = dao.deleteFeed(feedId)

    suspend fun setAutoDownload(feedId: String, enabled: Boolean) = dao.setAutoDownload(feedId, enabled)

    suspend fun setNewestFirst(feedId: String, newestFirst: Boolean) = dao.setNewestFirst(feedId, newestFirst)

    suspend fun setContinuous(feedId: String, continuous: Boolean) = dao.setContinuous(feedId, continuous)

    /** Die Folge, mit der „Alle abspielen“ beginnt: die erste ungehörte in der Reihenfolge des Podcasts. */
    suspend fun firstToPlay(feedId: String): Long? {
        val feed = dao.feed(feedId) ?: return null
        return nextEpisode(playOrder(feed), dao.finishedEpisodeIds(feedId).toSet())
    }

    /**
     * Die Folge, die nach dem Ende von [bookId] von selbst startet. `null` bei Hörbüchern, wenn das Weiterspielen für
     * den Podcast aus ist oder nichts Ungehörtes mehr folgt.
     */
    suspend fun autoNextAfter(bookId: Long): Long? {
        val book = bookDao.book(bookId)?.takeIf { it.kind == BookKind.EPISODE } ?: return null
        val feed = dao.feed(book.serverId)?.takeIf { it.continuous } ?: return null
        return nextEpisode(playOrder(feed), dao.finishedEpisodeIds(feed.feedId).toSet(), bookId)
    }

    private suspend fun playOrder(feed: PodcastFeedEntity): List<Long> =
        dao.episodeIdsOldestFirst(feed.feedId).let { if (feed.newestFirst) it.asReversed() else it }

    /** Gleicht alle Abos ab; `true`, wenn kein Feed fehlgeschlagen ist. */
    suspend fun refreshAll(): Boolean = dao.feeds().map { refresh(it.feedId) != null }.all { it }

    /** Gleicht einen Feed ab. `null`, wenn er nicht erreichbar oder unlesbar ist (die alten Folgen bleiben). */
    suspend fun refresh(feedId: String): RefreshResult? = mutex.withLock {
        val feed = dao.feed(feedId) ?: return@withLock RefreshResult(emptyList())
        val fetched = try {
            api.fetch(feed.url.toHttpUrlOrThrow(), feed.etag, feed.lastModified)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Abgleich von ${feed.title} fehlgeschlagen", e)
            return@withLock null
        }
        when (fetched) {
            FeedFetch.NotModified -> {
                dao.upsertFeed(feed.copy(lastRefreshAt = System.currentTimeMillis()))
                RefreshResult(emptyList())
            }
            is FeedFetch.Fetched -> apply(feed, fetched)
        }.also { _refreshed.tryEmit(feedId) }
    }

    private suspend fun apply(feed: PodcastFeedEntity, fetched: FeedFetch.Fetched): RefreshResult {
        val parsed = fetched.feed
        val generation = feed.syncGeneration + 1
        val known = bookDao.booksOf(feed.feedId).associateBy { it.remoteId }
        val title = parsed.title.ifBlank { feed.title }
        val now = System.currentTimeMillis()
        val newIds = mutableListOf<Long>()
        // Der Feed führt die neueste Folge zuerst; die erste Folge eines doppelten guid gewinnt.
        parsed.episodes.distinctBy { it.guid }.forEach { episode ->
            val existing = known[episode.guid]
            val id = bookDao.upsertBook(episode.toEntity(feed.feedId, title, parsed, existing, generation, now))
            if (existing == null) newIds += id
            val track = BookTrackEntity(id, 0, episode.audioUrl, 0, episode.durationMs.takeIf { it > 0 } ?: existing?.durationMs ?: 0, episode.mimeType)
            // Der Download-Stand gehört nicht zum Feed und darf keinen Schreibvorgang auslösen.
            val stored = bookDao.tracks(id).map { it.copy(downloadState = TrackDownload.NONE, filePath = null, fileSize = 0) }
            if (stored != listOf(track)) dao.setTrack(track)
        }
        dao.deleteUnseenEpisodes(feed.feedId, generation)
        dao.upsertFeed(
            feed.copy(
                title = title,
                author = parsed.author,
                description = parsed.description.toPlainText(),
                imageUrl = parsed.imageUrl,
                etag = fetched.etag,
                lastModified = fetched.lastModified,
                lastRefreshAt = now,
                syncGeneration = generation,
            ),
        )
        return RefreshResult(newIds)
    }

    /** Lädt die Kapitelliste der Folge, falls der Feed eine anbietet und sie noch fehlt. Fehler sind unkritisch. */
    suspend fun ensureChapters(bookId: Long) {
        val book = bookDao.book(bookId) ?: return
        val url = book.chaptersUrl ?: return
        if (book.kind != BookKind.EPISODE || bookDao.chapters(bookId).isNotEmpty()) return
        val chapters = try {
            api.chapters(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Kapitel von ${book.title} nicht abrufbar", e)
            return
        }
        if (chapters.isEmpty()) return
        val lastStart = chapters.last().startMs
        val end = book.durationMs.takeIf { it > lastStart } ?: (lastStart + 1)
        bookDao.insertChapters(
            chapters.mapIndexed { index, chapter ->
                BookChapterEntity(bookId, index, chapter.title, chapter.startMs, chapters.getOrNull(index + 1)?.startMs ?: end)
            },
        )
    }

    private fun ParsedEpisode.toEntity(
        feedId: String,
        feedTitle: String,
        feed: ParsedFeed,
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
        description = description.toPlainText(),
        durationMs = durationMs.takeIf { it > 0 } ?: existing?.durationMs ?: 0,
        // Ohne Datum im Feed bleibt der Zeitpunkt, an dem die Folge zuerst aufgetaucht ist.
        addedAt = publishedAt.takeIf { it > 0 } ?: existing?.addedAt ?: now,
        // Dateien kommen aus dem Feed; es gibt keinen Server, der sie nachliefern müsste.
        detailsLoaded = true,
        seenInGeneration = generation,
        coverUrl = imageUrl ?: feed.imageUrl,
        chaptersUrl = chaptersUrl,
    )

    private fun String.toHttpUrlOrThrow() = normalizeFeedUrl(this) ?: throw IOException("Ungültige Feed-Adresse: $this")

    private companion object {
        const val TAG = "PodcastRepository"
        const val NEW_DAYS = 14L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val MAX_NEW = 20
    }
}

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
)

/** Beschreibungen kommen als HTML; die Oberfläche zeigt reinen Text. */
internal fun String.toPlainText(): String =
    if (contains('<') || contains('&')) Html.fromHtml(this, Html.FROM_HTML_MODE_COMPACT).toString().trim() else trim()
