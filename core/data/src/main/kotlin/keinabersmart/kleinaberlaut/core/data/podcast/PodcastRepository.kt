package keinabersmart.kleinaberlaut.core.data.podcast

import android.text.Html
import android.util.Log
import keinabersmart.kleinaberlaut.core.common.normalizeForMerge
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.dao.PodcastDao
import keinabersmart.kleinaberlaut.core.database.entity.BookChapterEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookProgressEntity
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
import kotlinx.coroutines.flow.flowOf
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

/** Ein Abo mit einer Auswahl seiner Folgen. */
data class FeedEpisodes(val feed: PodcastFeed, val episodes: List<BookWithProgress>)

/** Ein Podcast mit seinen fertig geladenen Folgen (Anzahl, belegte Bytes). */
data class FeedDownload(val feed: PodcastFeed, val episodes: Int, val bytes: Long)

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

    /** Podcasts, von denen mindestens eine Folge geladen ist, nach Titel. */
    fun feedDownloads(): Flow<List<FeedDownload>> = combine(feeds, dao.observeFeedDownloads()) { feeds, rows ->
        val byFeed = rows.associateBy { it.feedId }
        feeds.mapNotNull { feed -> byFeed[feed.id]?.let { FeedDownload(feed, it.episodes, it.bytes) } }
    }

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

    /** Pro Abo (nach Titel) die Folgen, die noch nicht zu Ende gehört sind, neueste zuerst; Abos ohne solche Folgen entfallen. */
    fun unheardByFeed(): Flow<List<FeedEpisodes>> =
        combine(dao.observeFeeds(), dao.observeEpisodes(), bookDao.observeAllProgress()) { feeds, episodes, progress ->
            val byBook = progress.associateBy { it.bookId }
            val byFeed = episodes
                .filter { byBook[it.bookId]?.finished != true }
                .groupBy { it.serverId }
            feeds.mapNotNull { feed ->
                byFeed[feed.feedId]?.let { list ->
                    FeedEpisodes(feed.toModel(), list.map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) })
                }
            }
        }

    /**
     * „Weiterhören“ (die Warteschlange der Podcasts): ganz vorn die Folgen aus [order] (nicht zu Ende gehörte, in dieser
     * Reihenfolge), dahinter von selbst die angefangenen Folgen mit der zuletzt gehörten zuerst und die neuen, noch nicht
     * angefangenen Folgen der letzten [NEW_DAYS] Tage (neueste zuerst). Folgen in [hidden] fehlen.
     */
    fun continueListening(
        order: Flow<List<Long>> = flowOf(emptyList()),
        hidden: Flow<Set<Long>> = flowOf(emptySet()),
        now: () -> Long = System::currentTimeMillis,
    ): Flow<List<BookWithProgress>> =
        combine(dao.observeEpisodes(), bookDao.observeAllProgress(), order, hidden) { episodes, progress, orderIds, hiddenIds ->
            val byBook = progress.associateBy { it.bookId }
            val started = episodes
                .filter { it.bookId !in hiddenIds }
                .mapNotNull { e -> byBook[e.bookId]?.takeIf { !it.finished && it.positionMs > 0 }?.let { e to it } }
                .sortedByDescending { (_, p) -> p.updatedAt }
                .map { (e, p) -> BookWithProgress(e.toModel(), p.toModel()) }
            val since = now() - NEW_DAYS * DAY_MS
            val fresh = episodes
                .filter { e ->
                    e.bookId !in hiddenIds && (byBook[e.bookId]?.positionMs ?: 0L) == 0L &&
                        byBook[e.bookId]?.finished != true && e.addedAt >= since
                }
                .take(MAX_NEW)
                .map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) }
            val byId = episodes.associateBy { it.bookId }
            val ordered = orderIds.mapNotNull { id ->
                byId[id]?.takeIf { id !in hiddenIds && byBook[id]?.finished != true }
                    ?.let { BookWithProgress(it.toModel(), byBook[id]?.toModel()) }
            }
            val placed = ordered.mapTo(HashSet()) { it.book.id }
            ordered + (started + fresh).filter { it.book.id !in placed }
        }

    suspend fun isEpisode(bookId: Long): Boolean = bookDao.book(bookId)?.kind == BookKind.EPISODE

    /** Eine zu Ende gehörte Folge, die neu startet, gilt wieder als offen (sie beginnt ohnehin von vorn). */
    suspend fun reopenIfFinished(bookId: Long) {
        if (bookDao.progress(bookId)?.finished == true) setPlayed(bookId, played = false)
    }

    /** Ids aller fertig geladenen Folgen. */
    fun downloadedIds(): Flow<Set<Long>> = dao.observeDownloadedIds().map { it.toSet() }

    /** Markiert die Folge als gespielt (Position ans Ende) oder als ungespielt (Position 0). Nur lokal, es gibt keinen Server. */
    suspend fun setPlayed(bookId: Long, played: Boolean) {
        val book = bookDao.book(bookId) ?: return
        val old = bookDao.progress(bookId)
        val duration = book.durationMs.takeIf { it > 0 } ?: old?.durationMs ?: 0
        bookDao.upsertProgress(
            BookProgressEntity(
                bookId = bookId,
                positionMs = if (played) duration else 0,
                durationMs = duration,
                finished = played,
                speed = old?.speed ?: 1f,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Setzt die Folge an den Anfang zurück (auch den Zustand „gespielt“). */
    suspend fun resetPosition(bookId: Long) = setPlayed(bookId, played = false)

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

    /**
     * Die ungehörte Folge, die in der Reihenfolge des Podcasts auf [bookId] folgt und in die Zeile „Weiterhören“
     * eingereiht wird. `null` bei Hörbüchern, wenn das Einreihen für den Podcast aus ist oder nichts Ungehörtes mehr folgt.
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
                description = parsed.description.toDescriptionHtml(),
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
        description = description.toDescriptionHtml(),
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

/**
 * Beschreibungen kommen als HTML und bleiben mit ihren Tags erhalten; die Oberfläche setzt sie um. Manche Feeds maskieren
 * das HTML doppelt (nach dem Einlesen steht `&lt;p&gt;` statt `<p>` da): Das wird hier einmal aufgelöst.
 */
internal fun String.toDescriptionHtml(): String {
    val text = trim()
    return if ('<' !in text && text.contains("&lt;")) Html.fromHtml(text, Html.FROM_HTML_MODE_COMPACT).toString().trim() else text
}
