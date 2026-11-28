package de.kleinabersmart.earloft.core.data.wear

import androidx.room.withTransaction
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
import de.kleinabersmart.earloft.core.data.spoken.BookSyncer
import de.kleinabersmart.earloft.core.data.spoken.isUnavailable
import de.kleinabersmart.earloft.core.data.spoken.toModel
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.dao.WearDao
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.database.entity.WearBookEntity
import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookProgress
import de.kleinabersmart.earloft.core.model.EffectOverrides
import de.kleinabersmart.earloft.core.model.isLocalSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import javax.inject.Inject
import javax.inject.Singleton

/** A subscription as the watch needs it (Wear concept 16.1). */
data class WatchFeedContent(
    val feedId: String,
    val url: String,
    val title: String,
    val author: String,
    val imageUrl: String?,
    val newestFirst: Boolean,
    val continuous: Boolean,
    /** Speed and effects of the subscription (concept 12.4); `null` = section default. */
    val effects: EffectOverrides = EffectOverrides(),
    val skipIntroS: Int = 0,
    val skipOutroS: Int = 0,
)

/** An episode for the watch with everything it needs for downloading and playback. */
data class WatchEpisodeContent(
    /** ID on the phone (for the selection in the settings); isn't sent to the watch. */
    val bookId: Long,
    val feedId: String,
    val guid: String,
    val title: String,
    val publishedAt: Long,
    val durationMs: Long,
    /** Address in the feed; `null` for episodes from the device (those only exist via the file channel). */
    val audioUrl: String?,
    val mimeType: String?,
    val coverUrl: String?,
    val progress: BookProgress?,
    val chapters: List<BookChapter> = emptyList(),
    val chaptersUrl: String? = null,
)

/** An audiobook for the watch (Wear concept 16.2). */
data class WatchBookContent(
    /** ID on the phone (for the selection in the settings); isn't sent to the watch. */
    val bookId: Long,
    val serverId: String,
    val remoteId: String,
    val title: String,
    val author: String,
    val narrator: String,
    val durationMs: Long,
    val tracks: List<WatchBookTrackContent>,
    val progress: BookProgress?,
    val chapters: List<BookChapter> = emptyList(),
)

/** A file of the book; [fileId] is the identifier at Audiobookshelf, `null` for books from the device (only via the file channel). */
data class WatchBookTrackContent(
    val index: Int,
    val fileId: String?,
    val startMs: Long,
    val durationMs: Long,
    val mimeType: String?,
    /** Not downloaded on the phone and no longer available at the source: the watch doesn't download it and skips it. */
    val unavailable: Boolean = false,
)

data class WatchSpokenContent(
    val feeds: List<WatchFeedContent> = emptyList(),
    val episodes: List<WatchEpisodeContent> = emptyList(),
    val books: List<WatchBookContent> = emptyList(),
    /** Dropped out of the selection because they were listened to the end on the phone ([WatchFinishedKeys]). */
    val finished: List<WatchSpokenKey> = emptyList(),
)

/**
 * Episodes and audiobooks for the watch (Wear concept 16.1/16.2): individually selected on the phone (watch icon on the episode's
 * or book's page, long-press menu), in the order of selection, with the episodes' subscriptions and the phone's progress.
 * Items listened to the end drop out of the selection. The app builds the episode manifest for the Data Layer from it.
 */
@Singleton
class WatchSpoken @Inject constructor(
    private val database: EarLoftDatabase,
    private val wearDao: WearDao,
    private val podcastDao: PodcastDao,
    private val bookDao: BookDao,
    private val podcasts: PodcastRepository,
    private val bookSyncer: BookSyncer,
    private val progressSync: BookProgressSync,
    private val finishedKeys: WatchFinishedKeys,
) {
    fun selectedIds(): Flow<Set<Long>> = wearDao.observeBookIds().map { it.toSet() }

    suspend fun setSelected(bookId: Long, selected: Boolean) {
        if (!selected) {
            wearDao.deleteBook(bookId)
            return
        }
        val book = bookDao.book(bookId) ?: return
        // Like "Add to queue": played items count as open again, otherwise they would drop out again immediately.
        if (book.kind == BookKind.EPISODE) {
            podcasts.reopenIfFinished(bookId)
            // So the watch gets the chapters without having to fetch them from the feed itself.
            podcasts.ensureChapters(bookId)
        } else {
            if (bookDao.progress(bookId)?.finished == true) progressSync.record(bookId, 0, book.durationMs, finished = false, force = true)
            // The file list only comes with the details; without a connection the next opening of the book catches up.
            bookSyncer.ensureDetails(bookId)
        }
        wearDao.insertBook(WearBookEntity(bookId, addedAt = System.currentTimeMillis()))
    }

    /** Like [setSelected], the episode or book by server or subscription and identifier (watch's storage view). */
    suspend fun setSelected(serverId: String, remoteId: String, selected: Boolean) {
        val book = bookDao.bookByRemote(serverId, remoteId) ?: return
        setSelected(book.bookId, selected)
    }

    /** Clears the selection, with [kind] only the episodes or only the books. */
    suspend fun clear(kind: BookKind? = null) = if (kind == null) wearDao.deleteAllBooks() else wearDao.deleteBooksOfKind(kind)

    /** [load], emitted anew on every change of the involved tables; throttled like [WearSelectionRepository.content]. */
    fun content(): Flow<WatchSpokenContent> =
        database.invalidationTracker.createFlow(*OBSERVED_TABLES)
            .conflate()
            .transform {
                emit(load())
                delay(THROTTLE_MS)
            }
            .distinctUntilChanged()

    suspend fun load(): WatchSpokenContent = database.withTransaction {
        finishedKeys.add(wearDao.finishedKeyedBooks().map { WatchSpokenKey(it.serverId, it.remoteId) })
        wearDao.deleteFinishedBooks()
        val feeds = LinkedHashMap<String, PodcastFeedEntity>()
        val episodes = mutableListOf<WatchEpisodeContent>()
        val books = mutableListOf<WatchBookContent>()
        for (bookId in wearDao.selectedSpokenIds()) {
            val book = bookDao.book(bookId) ?: continue
            if (book.kind == BookKind.EPISODE) {
                val feed = feeds[book.serverId] ?: podcastDao.feed(book.serverId)?.also { feeds[book.serverId] = it } ?: continue
                episodes += episodeContent(book, feed) ?: continue
            } else {
                books += bookContent(book) ?: continue
            }
        }
        WatchSpokenContent(
            feeds = feeds.values.map {
                WatchFeedContent(
                    it.feedId, it.url, it.title, it.author, it.imageUrl?.takeIf { url -> url.startsWith("http") }, it.newestFirst, it.continuous,
                    EffectOverrides(it.speed, it.skipSilence, it.volumeBoostDb), it.skipIntroS, it.skipOutroS,
                )
            },
            episodes = episodes,
            books = books,
            // Selected again since: it is open again and goes to the watch as usual.
            finished = finishedKeys.current() - (episodes.map { WatchSpokenKey(it.feedId, it.guid) } + books.map { WatchSpokenKey(it.serverId, it.remoteId) })
                .toSet(),
        )
    }

    private suspend fun episodeContent(book: BookEntity, feed: PodcastFeedEntity): WatchEpisodeContent? {
        val track = podcastDao.trackOf(book.bookId) ?: return null
        // Episodes from the device: the file identifier is a `content://` address that only the phone can open.
        val local = isLocalSource(book.serverId)
        return WatchEpisodeContent(
            bookId = book.bookId,
            feedId = book.serverId,
            guid = book.remoteId,
            title = book.title,
            publishedAt = book.addedAt,
            durationMs = book.durationMs.takeIf { it > 0 } ?: track.durationMs,
            audioUrl = track.remoteFileId.takeUnless { local },
            mimeType = track.mimeType,
            // Images of episodes from the device are `earloft://` addresses of this phone; the watch can't do anything with them.
            coverUrl = (book.coverUrl ?: feed.imageUrl)?.takeIf { it.startsWith("http") },
            progress = bookDao.progress(book.bookId)?.toModel(),
            chapters = bookDao.chapters(book.bookId).map { it.toModel() },
            chaptersUrl = book.chaptersUrl?.takeIf { it.startsWith("http") },
        )
    }

    /** `null` as long as the file list is missing (details not loaded yet). */
    private suspend fun bookContent(book: BookEntity): WatchBookContent? {
        val tracks = bookDao.tracks(book.bookId).takeIf { it.isNotEmpty() } ?: return null
        // Books from the device: the file identifier is an address on this phone.
        val local = isLocalSource(book.serverId)
        return WatchBookContent(
            bookId = book.bookId,
            serverId = book.serverId,
            remoteId = book.remoteId,
            title = book.title,
            author = book.author,
            narrator = book.narrator,
            durationMs = book.durationMs,
            tracks = tracks.map {
                WatchBookTrackContent(it.trackIndex, it.remoteFileId.takeUnless { local }, it.startMs, it.durationMs, it.mimeType, it.isUnavailable)
            },
            progress = bookDao.progress(book.bookId)?.toModel(),
            chapters = bookDao.chapters(book.bookId).map { it.toModel() },
        )
    }

    private companion object {
        const val THROTTLE_MS = 2_000L

        /** Everything the selection, the details of episodes and books, and progress depend on. */
        val OBSERVED_TABLES = arrayOf("wear_books", "books", "book_tracks", "book_chapters", "book_progress", "podcast_feeds", "spoken_servers")
    }
}
