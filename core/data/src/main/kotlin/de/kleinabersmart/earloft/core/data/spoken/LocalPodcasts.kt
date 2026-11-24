package de.kleinabersmart.earloft.core.data.spoken

import android.util.Log
import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.datasource.local.LocalBookScanner
import de.kleinabersmart.earloft.core.data.sync.SweepGuard
import de.kleinabersmart.earloft.core.datasource.local.LocalEpisode
import de.kleinabersmart.earloft.core.datasource.local.LocalEpisodeDetails
import de.kleinabersmart.earloft.core.datasource.local.LocalPodcast
import de.kleinabersmart.earloft.core.datasource.local.LocalScan
import de.kleinabersmart.earloft.core.model.AppUris
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.LOCAL_PODCAST_PREFIX
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Podcast files from the "Podcasts" folder on the device (concept 12.1). Each podcast is a subscription without a feed
 * (`feedId = `[LOCAL_PODCAST_PREFIX]`<identifier of the name>`, `url` = `feedId`), each file an episode (`books` with
 * `kind = EPISODE`, `remoteId` = path below "Podcasts/", file as `content://` address in `book_tracks`). That way
 * continue listening, queue, progress and "Next episode" work as with feeds; sync and downloads don't apply.
 */
@Singleton
class LocalPodcasts internal constructor(
    private val bookDao: BookDao,
    private val dao: PodcastDao,
    /** [LocalBookScanner.scanPodcasts]; tests hand in the files directly instead of the MediaStore. */
    private val scanPodcasts: suspend (isUnchanged: suspend (String, String, Long) -> Boolean) -> LocalScan<LocalPodcast>,
    private val settings: SpokenSettings,
    /** [BookCovers.invalidate]. */
    private val invalidateCover: (bookId: Long) -> Unit,
    private val sweepGuard: SweepGuard,
) {
    @Inject constructor(
        bookDao: BookDao,
        dao: PodcastDao,
        scanner: LocalBookScanner,
        settings: SpokenSettings,
        covers: BookCovers,
        sweepGuard: SweepGuard,
    ) : this(bookDao, dao, scanner::scanPodcasts, settings, covers::invalidate, sweepGuard)

    private val mutex = Mutex()

    /**
     * Syncs the files in "Podcasts" with the database (create new ones, re-read changed ones, delete ones that disappeared
     * along with their progress). `false` if that wasn't possible; then everything stays as it is.
     */
    suspend fun sync(): Boolean = mutex.withLock {
        if (!settings.useLocalPodcasts.first()) return@withLock true
        val generation = bookDao.nextGeneration()
        val known = bookDao.countBooksWithServerPrefix(LOCAL_PODCAST_PREFIX)
        val scan = try {
            scanPodcasts { podcast, key, fingerprint ->
                bookDao.bookByRemote(feedIdOf(podcast), key)?.let { it.remoteUpdatedAt == fingerprint && it.detailsLoaded } == true
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission for the podcasts on the device", e)
            return@withLock false
        } catch (e: IOException) {
            Log.w(TAG, "Podcasts on the device not readable", e)
            return@withLock false
        }
        val scanned = scan.items
        // A missing storage volume or a suspicious drop (media index being rebuilt) removes nothing (review G6): progress
        // would go with the episodes.
        val sweep = scan.complete && sweepGuard.allows(SWEEP_AREA, known, scanned.sumOf { it.episodes.size })
        if (!sweep) Log.w(TAG, "Removal of podcasts on the device postponed")
        scanned.forEach { store(it, generation, sweep) }
        // Podcasts without files are dropped. A failed sync never gets this far and therefore deletes nothing.
        if (sweep) {
            val present = scanned.mapTo(HashSet()) { feedIdOf(it.name) }
            dao.feeds().filter { it.feedId.startsWith(LOCAL_PODCAST_PREFIX) && it.feedId !in present }.forEach { dao.deleteFeed(it.feedId) }
        }
        true
    }

    /** Disabled: the podcasts from the device disappear along with their progress (like local music). */
    suspend fun remove() = mutex.withLock {
        dao.feeds().filter { it.feedId.startsWith(LOCAL_PODCAST_PREFIX) }.forEach { dao.deleteFeed(it.feedId) }
    }

    private suspend fun store(podcast: LocalPodcast, generation: Long, sweep: Boolean) {
        val feedId = feedIdOf(podcast.name)
        var newestWithCover: Pair<Long, Long>? = null
        for (episode in podcast.episodes) {
            val details = episode.details
            if (details == null) {
                bookDao.markSeen(feedId, episode.key, generation)
                continue
            }
            val bookId = storeEpisode(feedId, podcast.name, episode, details, generation)
            if (details.hasCover && details.publishedAtMs >= (newestWithCover?.second ?: Long.MIN_VALUE)) {
                newestWithCover = bookId to details.publishedAtMs
            }
        }
        if (sweep) bookDao.deleteUnseen(feedId, generation)

        val existing = dao.feed(feedId)
        // The podcast's image is the cover of one of its episodes, as long as that episode is still there.
        val imageEpisode = existing?.imageUrl?.let(AppUris::parseBookCoverId)
        val keepImage = imageEpisode?.let { bookDao.book(it) } != null
        val image = when {
            keepImage -> existing.imageUrl
            newestWithCover != null -> AppUris.bookCover(newestWithCover.first)
            // The image's episode is gone and no file read now has a cover (unchanged files aren't read): the newest
            // remaining episode, whose file may carry one, instead of the cover of a deleted episode.
            imageEpisode != null -> bookDao.booksOf(feedId).maxByOrNull { it.addedAt }?.let { AppUris.bookCover(it.bookId) }
            else -> existing?.imageUrl
        }
        dao.upsertFeed(
            existing?.copy(title = podcast.name, imageUrl = image, lastRefreshAt = generation, syncGeneration = generation)
                ?: PodcastFeedEntity(feedId = feedId, url = feedId, title = podcast.name, imageUrl = image, lastRefreshAt = generation, syncGeneration = generation),
        )
    }

    private suspend fun storeEpisode(feedId: String, podcast: String, episode: LocalEpisode, details: LocalEpisodeDetails, generation: Long): Long {
        val bookId = bookDao.upsertBook(
            BookEntity(
                serverId = feedId,
                kind = BookKind.EPISODE,
                remoteId = episode.key,
                title = details.title,
                normTitle = details.title.normalizeForMerge(),
                author = podcast,
                description = details.description,
                durationMs = details.track.durationMs,
                remoteUpdatedAt = episode.fingerprint,
                addedAt = details.publishedAtMs,
                detailsLoaded = true,
                seenInGeneration = generation,
            ),
        )
        val track = BookTrackEntity(bookId, 0, details.track.uri, 0, details.track.durationMs, details.track.mimeType)
        val chapters = details.chapters.mapIndexed { index, chapter ->
            BookChapterEntity(bookId, index, chapter.title, chapter.startMs, chapter.endMs)
        }
        bookDao.replaceDetails(bookId, listOf(track), chapters)
        // The cover is in the file; if it changed, the cached one is no longer valid.
        invalidateCover(bookId)
        return bookId
    }

    private fun feedIdOf(name: String): String =
        LOCAL_PODCAST_PREFIX + UUID.nameUUIDFromBytes(name.trim().lowercase().toByteArray())

    private companion object {
        const val TAG = "LocalPodcasts"
        const val SWEEP_AREA = "podcasts"
    }
}
