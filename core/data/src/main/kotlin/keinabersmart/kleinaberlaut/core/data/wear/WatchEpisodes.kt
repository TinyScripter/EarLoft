package keinabersmart.kleinaberlaut.core.data.wear

import androidx.room.withTransaction
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.data.spoken.toModel
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.dao.PodcastDao
import keinabersmart.kleinaberlaut.core.database.dao.WearDao
import keinabersmart.kleinaberlaut.core.database.entity.PodcastFeedEntity
import keinabersmart.kleinaberlaut.core.database.entity.WearBookEntity
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
)

data class WatchPodcastContent(
    val feeds: List<WatchFeedContent> = emptyList(),
    val episodes: List<WatchEpisodeContent> = emptyList(),
)

/**
 * Die Folgen für die Uhr (Wear-Konzept 16.1): einzeln am Handy gewählt (Uhren-Symbol auf der Folgenseite, Folgen-Menü),
 * in der Reihenfolge des Wählens, mit ihren Abos und dem Fortschritt des Handys. Zu Ende gehörte Folgen fallen aus der
 * Auswahl. Die App bildet daraus das Folgen-Manifest für den Data Layer.
 */
@Singleton
class WatchEpisodes @Inject constructor(
    private val database: KleinaberlautDatabase,
    private val wearDao: WearDao,
    private val podcastDao: PodcastDao,
    private val bookDao: BookDao,
    private val podcasts: PodcastRepository,
) {
    fun selectedIds(): Flow<Set<Long>> = wearDao.observeBookIds().map { it.toSet() }

    suspend fun setSelected(bookId: Long, selected: Boolean) {
        if (!selected) {
            wearDao.deleteBook(bookId)
            return
        }
        // Wie „In Warteschlange einfügen“: Eine gehörte Folge gilt wieder als offen, sonst fiele sie sofort wieder heraus.
        podcasts.reopenIfFinished(bookId)
        wearDao.insertBook(WearBookEntity(bookId, addedAt = System.currentTimeMillis()))
    }

    suspend fun clear() = wearDao.deleteAllBooks()

    /** [load], neu bei jeder Änderung der beteiligten Tabellen; gedrosselt wie [WearSelectionRepository.content]. */
    fun content(): Flow<WatchPodcastContent> =
        database.invalidationTracker.createFlow(*OBSERVED_TABLES)
            .conflate()
            .transform {
                emit(load())
                delay(THROTTLE_MS)
            }
            .distinctUntilChanged()

    suspend fun load(): WatchPodcastContent = database.withTransaction {
        wearDao.deleteFinishedBooks()
        val feeds = LinkedHashMap<String, PodcastFeedEntity>()
        val episodes = wearDao.selectedEpisodeIds().mapNotNull { bookId ->
            val book = bookDao.book(bookId) ?: return@mapNotNull null
            val feed = feeds[book.serverId] ?: podcastDao.feed(book.serverId)?.also { feeds[book.serverId] = it } ?: return@mapNotNull null
            val track = podcastDao.trackOf(book.bookId) ?: return@mapNotNull null
            // Folgen vom Gerät: Die Dateikennung ist eine `content://`-Adresse, die nur das Handy öffnen kann.
            val local = isLocalSource(book.serverId)
            WatchEpisodeContent(
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
            )
        }
        WatchPodcastContent(
            feeds = feeds.values.map {
                WatchFeedContent(it.feedId, it.url, it.title, it.author, it.imageUrl?.takeIf { url -> url.startsWith("http") }, it.newestFirst, it.continuous)
            },
            episodes = episodes,
        )
    }

    private companion object {
        const val THROTTLE_MS = 2_000L

        /** Alles, wovon Auswahl, Angaben der Folgen und Fortschritt abhängen. */
        val OBSERVED_TABLES = arrayOf("wear_books", "books", "book_tracks", "book_progress", "podcast_feeds")
    }
}
