package de.kleinabersmart.tinyamp.core.data.podcast

import de.kleinabersmart.tinyamp.core.database.dao.BookDao
import de.kleinabersmart.tinyamp.core.database.dao.PodcastDao
import de.kleinabersmart.tinyamp.core.database.dao.StatsDao
import de.kleinabersmart.tinyamp.core.database.entity.BookEntity
import de.kleinabersmart.tinyamp.core.database.entity.BookProgressEntity
import de.kleinabersmart.tinyamp.core.database.entity.ListeningStatEntity
import de.kleinabersmart.tinyamp.core.model.BookKind
import de.kleinabersmart.tinyamp.core.network.podcast.OpmlFeed
import de.kleinabersmart.tinyamp.core.network.podcast.normalizeFeedUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Was ein Backup-Import getan hat: neu abonnierte Podcasts, übernommene Fortschritte, Fortschritte, die in der App neuer
 * waren ([progressKept]) oder deren Folge nicht (mehr) im Feed steht ([progressMissing]), und die Zahl der Statistik-Einträge.
 */
data class DatabaseImportResult(
    val feedsAdded: Int,
    val progressApplied: Int,
    val progressKept: Int,
    val progressMissing: Int,
    val statsImported: Int,
)

/**
 * Übernimmt aus einer Datenbank-Datei die Abos, den Hörfortschritt und die gehörte Zeit.
 *
 * - Aktive Abos, die es noch nicht gibt, werden angelegt und abgeglichen; archivierte nie.
 * - Fortschritt wird über Feed-Adresse und guid zugeordnet und nur übernommen, wenn er neuer ist als der in der App.
 * - Die gehörte Zeit wandert für **alle** Folgen (auch archivierter Abos und nicht mehr im Feed stehender Folgen) in die
 *   Statistik ([ListeningStatEntity]); ein erneuter Import ersetzt seine eigenen Zeilen.
 */
@Singleton
class DatabaseImporter @Inject constructor(
    private val repository: PodcastRepository,
    private val podcastDao: PodcastDao,
    private val bookDao: BookDao,
    private val statsDao: StatsDao,
) {
    suspend fun import(backup: ImportData): DatabaseImportResult {
        val known = podcastDao.feeds().mapTo(HashSet()) { it.url }
        val active = backup.feeds.filter { it.active }.mapNotNull { f -> key(f.url)?.let { url -> OpmlFeed(f.title.ifBlank { url }, url) } }
        repository.importOpml(active, replace = false)
        val feeds = podcastDao.feeds()
        val added = feeds.filter { it.url !in known }
        // Ohne Folgen gibt es nichts, dem sich ein Fortschritt zuordnen ließe.
        added.forEach { repository.refresh(it.feedId) }

        val feedByUrl = feeds.associateBy { it.url }
        val titles = backup.feeds.associate { (key(it.url) ?: it.url) to it.title }
        val booksByFeed = HashMap<String, Map<String, BookEntity>>()
        var applied = 0
        var kept = 0
        var missing = 0
        val stats = mutableListOf<ListeningStatEntity>()

        for (episode in backup.episodes) {
            val feedUrl = key(episode.feedUrl) ?: episode.feedUrl
            if (episode.played || episode.positionMs > 0) {
                val feed = feedByUrl[feedUrl]
                val book = feed?.let { f -> booksByFeed.getOrPut(f.feedId) { bookDao.booksOf(f.feedId).associateBy { it.remoteId } }[episode.guid] }
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
        return DatabaseImportResult(added.size, applied, kept, missing, stats.size)
    }

    /** `false`, wenn die App einen neueren Stand hat und er bleibt. */
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
                speed = existing?.speed ?: 1f,
                updatedAt = episode.lastPlayedAt,
            ),
        )
        return true
    }

    private fun key(url: String): String? = normalizeFeedUrl(url)?.toString()
}
