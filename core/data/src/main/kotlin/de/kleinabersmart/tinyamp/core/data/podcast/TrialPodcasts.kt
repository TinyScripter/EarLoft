package de.kleinabersmart.tinyamp.core.data.podcast

import de.kleinabersmart.tinyamp.core.model.TrackDownload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Folge eines Probeabos aus der Vorschau, mit dem, was beim Aufräumen zählt (Konzept 12.10). */
data class TrialEpisode(
    val bookId: Long,
    val feedId: String,
    val finished: Boolean,
    val hasProgress: Boolean,
    val downloaded: Boolean,
)

/** Was beim Aufräumen der Probeabos wegfällt: Stelle und Download einzelner Folgen, ganze Probeabos. */
data class TrialCleanup(val episodes: List<Long>, val feeds: List<String>) {
    val isEmpty: Boolean get() = episodes.isEmpty() && feeds.isEmpty()
}

/**
 * Entscheidet, was von den Probeabos bleibt (Konzept 12.10). Eine Folge hat *Bezug*, solange sie in der Warteschlange
 * ([queue] ohne [hidden]) steht und nicht zu Ende gehört ist, oder solange sie im Player geladen ist ([loadedBookId]).
 * Ohne Bezug verliert sie Stelle und Download; ein Probeabo ohne Folge mit Bezug und ohne offene Seite ([openFeeds])
 * verschwindet ganz (dann zählen seine Folgen nicht einzeln).
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

/** Datei liegt vor, wird geladen oder wartet darauf (auch ein abgebrochener Rest zählt). */
internal fun TrackDownload?.isDownloadPresent(): Boolean =
    this != null && this != TrackDownload.NONE && this != TrackDownload.REMOVED

/**
 * Welche Probeabos gerade eine offene Seite haben (Podcast-Seite, Info, Suche): Solange eine offen ist, bleibt das
 * Probeabo, auch ohne Folge in der Warteschlange. Die Seiten zählen sich beim Öffnen hinzu und beim Schließen ab.
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
