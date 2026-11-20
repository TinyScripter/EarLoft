package de.kleinabersmart.earloft.core.data.podcast

import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.dao.StatsDao
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningStatEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.network.podcast.OpmlFeed
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ardSoundsShowId
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.parseArdSoundsInput
import de.kleinabersmart.earloft.core.network.podcast.normalizeFeedUrl
import de.kleinabersmart.earloft.core.network.podcast.splitCredentials
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a backup import did: newly created podcasts (including archived ones), adopted progress, progress that was newer in the app
 * ([progressKept]) or whose episode is not (or no longer) in the feed ([progressMissing]), the number of statistics entries and of
 * newly saved episodes.
 */
data class DatabaseImportResult(
    val feedsAdded: Int,
    val progressApplied: Int,
    val progressKept: Int,
    val progressMissing: Int,
    val statsImported: Int,
    val starred: Int = 0,
)

/**
 * Imports subscriptions, listening progress and listened time from a database file.
 *
 * - Subscriptions that don't exist yet are created and synced; archived ones as archived subscriptions (an existing subscription in the app stays
 *   as it is). If a show from ARD Sounds is in it as `ardsounds:`, it is a subscription: that's how the export writes it without an
 *   RSS bridge (and older exports always) as archived, because other apps can't subscribe to it.
 * - Progress is matched by feed URL and guid and only adopted if it is newer than the one in the app.
 * - Saved episodes (`Favorites`) are matched the same way and saved; a star in the app stays, none is removed.
 * - The listened time for **all** episodes (including archived subscriptions and episodes no longer in the feed) goes into the
 *   statistics ([ListeningStatEntity]); a repeated import replaces its own rows.
 */
@Singleton
class DatabaseImporter @Inject constructor(
    private val repository: PodcastRepository,
    private val podcastDao: PodcastDao,
    private val bookDao: BookDao,
    private val statsDao: StatsDao,
) {
    /**
     * Never starts downloads (review G26): the subscriptions are synced before their progress is applied, so the automation
     * would load played episodes. With "auto-download new subscriptions" on, it starts with the next regular sync.
     */
    suspend fun import(backup: ImportData): DatabaseImportResult = repository.withoutAutomation { importPaused(backup) }

    private suspend fun importPaused(backup: ImportData): DatabaseImportResult {
        val known = podcastDao.feeds().mapTo(HashSet()) { it.url }
        // If an address is in the file as both active and archived, it counts as active.
        val wanted = backup.feeds
            .mapNotNull { f -> key(f.url)?.let { url -> WantedFeed(url, f.title.ifBlank { url }, f.active || ardSoundsShowId(f.url) != null, f) } }
            .groupBy { it.url }
            .map { (_, same) -> same.firstOrNull { it.active } ?: same.first() }
        // The address from the file: the import extracts credentials in it and stores them (review D6).
        repository.importOpml(wanted.map { OpmlFeed(it.title, it.source.url) }, replace = false)
        val added = wanted.filter { it.url !in known }.mapNotNull { wish ->
            podcastDao.feedByUrl(wish.url)?.also {
                if (!wish.active) podcastDao.setArchived(it.feedId, true)
                // Speed and effects only for new subscriptions; an existing subscription keeps its settings.
                val source = wish.source
                podcastDao.setEffects(it.feedId, source.speed, source.skipSilence, source.volumeBoostDb)
                podcastDao.setSkipIntroOutro(it.feedId, source.skipIntroS, source.skipOutroS)
            }
        }
        // Without episodes there is nothing progress could be matched to.
        added.forEach { repository.refresh(it.feedId) }

        val feedByUrl = (podcastDao.feeds() + added).associateBy { it.url }
        val titles = backup.feeds.associate { (key(it.url) ?: it.url) to it.title }
        val booksByFeed = HashMap<String, Map<String, BookEntity>>()
        var applied = 0
        var kept = 0
        var missing = 0
        val stars = mutableListOf<Long>()
        val stats = mutableListOf<ListeningStatEntity>()

        for (episode in backup.episodes) {
            val feedUrl = key(episode.feedUrl) ?: episode.feedUrl
            val feed = feedByUrl[feedUrl]
            val book = feed?.let { f -> booksByFeed.getOrPut(f.feedId) { bookDao.booksOf(f.feedId).associateBy { it.remoteId } }[episode.guid] }
            if (episode.starred && book != null) stars += book.bookId
            if (episode.played || episode.positionMs > 0) {
                if (book == null) {
                    missing++
                } else if (applyProgress(book, episode)) {
                    applied++
                } else {
                    kept++
                }
            }
            if (episode.listenedMs > 0) {
                stats += ListeningStatEntity(
                    source = ListeningStatEntity.SOURCE_IMPORTED,
                    kind = BookKind.EPISODE,
                    groupKey = feedUrl,
                    groupTitle = titles[feedUrl].orEmpty().ifBlank { feedUrl },
                    itemKey = episode.guid,
                    itemTitle = episode.title,
                    listenedMs = episode.listenedMs,
                    lastListenedAt = episode.lastPlayedAt,
                )
            }
        }
        statsDao.upsertAll(stats)
        val starred = repository.setStarred(stars, starred = true)
        return DatabaseImportResult(added.size, applied, kept, missing, stats.size, starred)
    }

    /** `false` if the app has a newer state and it stays. */
    private suspend fun applyProgress(book: BookEntity, episode: ImportEpisode): Boolean {
        val existing = bookDao.progress(book.bookId)
        if (existing != null && existing.updatedAt > episode.lastPlayedAt) return false
        val duration = book.durationMs.takeIf { it > 0 } ?: episode.durationMs
        val position = when {
            episode.played -> duration
            duration > 0 -> episode.positionMs.coerceIn(0, duration)
            else -> episode.positionMs
        }
        bookDao.upsertProgress(
            BookProgressEntity(
                bookId = book.bookId,
                positionMs = position,
                durationMs = duration,
                finished = episode.played,
                speed = existing?.speed,
                updatedAt = episode.lastPlayedAt,
                skipSilence = existing?.skipSilence,
                volumeBoostDb = existing?.volumeBoostDb,
            ),
        )
        return true
    }

    private data class WantedFeed(val url: String, val title: String, val active: Boolean, val source: ImportFeed)

    /**
     * Subscription URL as in the app: shows from ARD Sounds (also via the RSS bridge) as `ardsounds:<id>`, otherwise the feed
     * without credentials.
     */
    private fun key(url: String): String? = parseArdSoundsInput(url) ?: normalizeFeedUrl(url)?.splitCredentials()?.first?.toString()
}
