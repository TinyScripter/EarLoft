package keinabersmart.kleinaberlaut.core.data.wear

import keinabersmart.kleinaberlaut.core.data.podcast.ContinueListening
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.dao.PodcastDao
import keinabersmart.kleinaberlaut.core.database.entity.PodcastFeedEntity
import keinabersmart.kleinaberlaut.core.model.BookProgress
import keinabersmart.kleinaberlaut.core.model.TrackDownload
import keinabersmart.kleinaberlaut.core.model.isLocalSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import java.io.File
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
    val feedId: String,
    val guid: String,
    val title: String,
    val publishedAt: Long,
    val durationMs: Long,
    /** Adresse im Feed; `null` bei Folgen vom Gerät (die gibt es nur über den Datei-Kanal). */
    val audioUrl: String?,
    val mimeType: String?,
    val coverUrl: String?,
    val phoneHasFile: Boolean,
    val sizeBytes: Long?,
    val progress: BookProgress?,
)

data class WatchPodcastContent(
    val feeds: List<WatchFeedContent> = emptyList(),
    val episodes: List<WatchEpisodeContent> = emptyList(),
)

/**
 * Die Folgen für die Uhr (Wear-Konzept 16.1): die ersten n aus „Weiterhören“ in dessen Reihenfolge, mit ihren Abos und dem
 * Fortschritt des Handys. Die App bildet daraus das Folgen-Manifest für den Data Layer.
 */
@Singleton
class WatchEpisodes @Inject constructor(
    private val continueListening: ContinueListening,
    private val podcastDao: PodcastDao,
    private val bookDao: BookDao,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun content(count: Flow<Int>): Flow<WatchPodcastContent> =
        combine(continueListening.episodes, count) { list, n -> list.take(n.coerceAtLeast(0)) }
            .mapLatest { list ->
                val feeds = LinkedHashMap<String, PodcastFeedEntity>()
                val episodes = list.mapNotNull { entry ->
                    val book = bookDao.book(entry.book.id) ?: return@mapNotNull null
                    val feed = feeds[book.serverId] ?: podcastDao.feed(book.serverId)?.also { feeds[book.serverId] = it } ?: return@mapNotNull null
                    val track = podcastDao.trackOf(book.bookId) ?: return@mapNotNull null
                    val downloaded = track.downloadState == TrackDownload.DONE && track.filePath?.let { File(it).isFile } == true
                    // Folgen vom Gerät: Die Dateikennung ist eine `content://`-Adresse, die nur das Handy öffnen kann.
                    val local = isLocalSource(book.serverId)
                    WatchEpisodeContent(
                        feedId = book.serverId,
                        guid = book.remoteId,
                        title = book.title,
                        publishedAt = book.addedAt,
                        durationMs = book.durationMs.takeIf { it > 0 } ?: track.durationMs,
                        audioUrl = track.remoteFileId.takeUnless { local },
                        mimeType = track.mimeType,
                        coverUrl = book.coverUrl ?: feed.imageUrl,
                        phoneHasFile = local || downloaded,
                        sizeBytes = track.fileSize.takeIf { downloaded && it > 0 },
                        progress = entry.progress,
                    )
                }
                WatchPodcastContent(
                    feeds = feeds.values.map {
                        WatchFeedContent(it.feedId, it.url, it.title, it.author, it.imageUrl, it.newestFirst, it.continuous)
                    },
                    episodes = episodes,
                )
            }
}
