package keinabersmart.kleinaberlaut.core.data.wear

import androidx.room.withTransaction
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.data.spoken.BookProgressSync
import keinabersmart.kleinaberlaut.core.data.spoken.BookSyncer
import keinabersmart.kleinaberlaut.core.data.spoken.toModel
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.dao.PodcastDao
import keinabersmart.kleinaberlaut.core.database.dao.WearDao
import keinabersmart.kleinaberlaut.core.database.entity.BookEntity
import keinabersmart.kleinaberlaut.core.database.entity.PodcastFeedEntity
import keinabersmart.kleinaberlaut.core.database.entity.WearBookEntity
import keinabersmart.kleinaberlaut.core.model.BookChapter
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.BookProgress
import keinabersmart.kleinaberlaut.core.model.isLocalSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import javax.inject.Inject
import javax.inject.Singleton

/** Ein Abo, wie die Uhr es braucht (Wear-Konzept 16.1). */
data class WatchFeedContent(
    val feedId: String,
    val url: String,
    val title: String,
    val author: String,
    val imageUrl: String?,
    val newestFirst: Boolean,
    val continuous: Boolean,
)

/** Eine Folge für die Uhr mit allem, was sie zum Laden und Abspielen braucht. */
data class WatchEpisodeContent(
    /** ID am Handy (für die Auswahl in den Einstellungen); geht nicht an die Uhr. */
    val bookId: Long,
    val feedId: String,
    val guid: String,
    val title: String,
    val publishedAt: Long,
    val durationMs: Long,
    /** Adresse im Feed; `null` bei Folgen vom Gerät (die gibt es nur über den Datei-Kanal). */
    val audioUrl: String?,
    val mimeType: String?,
    val coverUrl: String?,
    val progress: BookProgress?,
    val chapters: List<BookChapter> = emptyList(),
    val chaptersUrl: String? = null,
)

/** Ein Hörbuch für die Uhr (Wear-Konzept 16.2). */
data class WatchBookContent(
    /** ID am Handy (für die Auswahl in den Einstellungen); geht nicht an die Uhr. */
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

/** Eine Datei des Buchs; [fileId] ist die Kennung bei Audiobookshelf, `null` bei Büchern vom Gerät (nur über den Datei-Kanal). */
data class WatchBookTrackContent(
    val index: Int,
    val fileId: String?,
    val startMs: Long,
    val durationMs: Long,
    val mimeType: String?,
)

data class WatchSpokenContent(
    val feeds: List<WatchFeedContent> = emptyList(),
    val episodes: List<WatchEpisodeContent> = emptyList(),
    val books: List<WatchBookContent> = emptyList(),
)

/**
 * Folgen und Hörbücher für die Uhr (Wear-Konzept 16.1/16.2): einzeln am Handy gewählt (Uhr-Symbol auf der Seite der Folge
 * bzw. des Buchs, Langklick-Menü), in der Reihenfolge des Wählens, mit den Abos der Folgen und dem Fortschritt des Handys.
 * Zu Ende Gehörtes fällt aus der Auswahl. Die App bildet daraus das Folgen-Manifest für den Data Layer.
 */
@Singleton
class WatchSpoken @Inject constructor(
    private val database: KleinaberlautDatabase,
    private val wearDao: WearDao,
    private val podcastDao: PodcastDao,
    private val bookDao: BookDao,
    private val podcasts: PodcastRepository,
    private val bookSyncer: BookSyncer,
    private val progressSync: BookProgressSync,
) {
    fun selectedIds(): Flow<Set<Long>> = wearDao.observeBookIds().map { it.toSet() }

    suspend fun setSelected(bookId: Long, selected: Boolean) {
        if (!selected) {
            wearDao.deleteBook(bookId)
            return
        }
        val book = bookDao.book(bookId) ?: return
        // Wie „In Warteschlange einfügen“: Gehörtes gilt wieder als offen, sonst fiele es sofort wieder heraus.
        if (book.kind == BookKind.EPISODE) {
            podcasts.reopenIfFinished(bookId)
            // Damit die Uhr die Kapitel mitbekommt, ohne sie selbst aus dem Feed holen zu müssen.
            podcasts.ensureChapters(bookId)
        } else {
            if (bookDao.progress(bookId)?.finished == true) progressSync.record(bookId, 0, book.durationMs, finished = false, force = true)
            // Die Dateiliste kommt erst mit den Details; ohne Verbindung holt sie das nächste Öffnen des Buchs nach.
            bookSyncer.ensureDetails(bookId)
        }
        wearDao.insertBook(WearBookEntity(bookId, addedAt = System.currentTimeMillis()))
    }

    suspend fun clear() = wearDao.deleteAllBooks()

    /** [load], neu bei jeder Änderung der beteiligten Tabellen; gedrosselt wie [WearSelectionRepository.content]. */
    fun content(): Flow<WatchSpokenContent> =
        database.invalidationTracker.createFlow(*OBSERVED_TABLES)
            .conflate()
            .transform {
                emit(load())
                delay(THROTTLE_MS)
            }
            .distinctUntilChanged()

    suspend fun load(): WatchSpokenContent = database.withTransaction {
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
                WatchFeedContent(it.feedId, it.url, it.title, it.author, it.imageUrl?.takeIf { url -> url.startsWith("http") }, it.newestFirst, it.continuous)
            },
            episodes = episodes,
            books = books,
        )
    }

    private suspend fun episodeContent(book: BookEntity, feed: PodcastFeedEntity): WatchEpisodeContent? {
        val track = podcastDao.trackOf(book.bookId) ?: return null
        // Folgen vom Gerät: Die Dateikennung ist eine `content://`-Adresse, die nur das Handy öffnen kann.
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
            // Bilder der Folgen vom Gerät sind `kal://`-Adressen dieses Handys; die Uhr kann damit nichts anfangen.
            coverUrl = (book.coverUrl ?: feed.imageUrl)?.takeIf { it.startsWith("http") },
            progress = bookDao.progress(book.bookId)?.toModel(),
            chapters = bookDao.chapters(book.bookId).map { it.toModel() },
            chaptersUrl = book.chaptersUrl?.takeIf { it.startsWith("http") },
        )
    }

    /** `null`, solange die Dateiliste fehlt (Details noch nicht geladen). */
    private suspend fun bookContent(book: BookEntity): WatchBookContent? {
        val tracks = bookDao.tracks(book.bookId).takeIf { it.isNotEmpty() } ?: return null
        // Bücher vom Gerät: Die Dateikennung ist eine Adresse auf diesem Handy.
        val local = isLocalSource(book.serverId)
        return WatchBookContent(
            bookId = book.bookId,
            serverId = book.serverId,
            remoteId = book.remoteId,
            title = book.title,
            author = book.author,
            narrator = book.narrator,
            durationMs = book.durationMs,
            tracks = tracks.map { WatchBookTrackContent(it.trackIndex, it.remoteFileId.takeUnless { local }, it.startMs, it.durationMs, it.mimeType) },
            progress = bookDao.progress(book.bookId)?.toModel(),
            chapters = bookDao.chapters(book.bookId).map { it.toModel() },
        )
    }

    private companion object {
        const val THROTTLE_MS = 2_000L

        /** Alles, wovon Auswahl, Angaben der Folgen und Bücher und Fortschritt abhängen. */
        val OBSERVED_TABLES = arrayOf("wear_books", "books", "book_tracks", "book_chapters", "book_progress", "podcast_feeds", "spoken_servers")
    }
}
