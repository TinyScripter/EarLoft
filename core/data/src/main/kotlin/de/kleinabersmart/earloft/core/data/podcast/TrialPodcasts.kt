package de.kleinabersmart.earloft.core.data.podcast

import de.kleinabersmart.earloft.core.model.TrackDownload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Episode of a trial subscription from the preview, with what matters during cleanup (concept 12.10). */
data class TrialEpisode(
    val bookId: Long,
    val feedId: String,
    val finished: Boolean,
    val hasProgress: Boolean,
    val downloaded: Boolean,
)

/** What is dropped when cleaning up trial subscriptions: position and download of individual episodes, entire trial subscriptions. */
data class TrialCleanup(val episodes: List<Long>, val feeds: List<String>) {
    val isEmpty: Boolean get() = episodes.isEmpty() && feeds.isEmpty()
}

/**
 * Decides what remains of the trial subscriptions (concept 12.10). An episode has a *reference* as long as it is in the queue
 * ([queue] without [hidden]) and not played to the end, or as long as it is loaded in the player ([loadedBookId]).
 * Without a reference it loses its position and download; a trial subscription without an episode with a reference and without an open page ([openFeeds])
 * disappears entirely (then its episodes don't count individually).
 */
fun planTrialCleanup(
    feedIds: Collection<String>,
    episodes: List<TrialEpisode>,
    queue: List<Long>,
    hidden: Set<Long>,
    loadedBookId: Long?,
    openFeeds: Set<String>,
): TrialCleanup {
    val queued = queue.toSet() - hidden
    fun TrialEpisode.hasClaim() = bookId == loadedBookId || (bookId in queued && !finished)
    val claimedFeeds = episodes.filter { it.hasClaim() }.mapTo(HashSet()) { it.feedId }
    val goneFeeds = feedIds.filter { it !in claimedFeeds && it !in openFeeds }
    val gone = goneFeeds.toSet()
    val forget = episodes.filter { it.feedId !in gone && !it.hasClaim() && (it.hasProgress || it.downloaded) }.map { it.bookId }
    return TrialCleanup(forget, goneFeeds)
}

/** The file exists, is downloading or waiting to download (an aborted partial file counts too). */
internal fun TrackDownload?.isDownloadPresent(): Boolean =
    this != null && this != TrackDownload.NONE && this != TrackDownload.REMOVED

/**
 * Which trial subscriptions currently have an open page (podcast page, info, search): as long as one is open, the
 * trial subscription stays, even without an episode in the queue. The pages register when opened and unregister when closed.
 */
@Singleton
class TrialPodcastPages @Inject constructor() {
    private val counts = MutableStateFlow<Map<String, Int>>(emptyMap())

    val open: StateFlow<Map<String, Int>> = counts

    fun opened(feedId: String) = counts.update { it + (feedId to (it[feedId] ?: 0) + 1) }

    fun closed(feedId: String) = counts.update {
        val left = (it[feedId] ?: 0) - 1
        if (left > 0) it + (feedId to left) else it - feedId
    }
}
