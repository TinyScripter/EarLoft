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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Was ein Backup-Import getan hat: neu angelegte Podcasts (auch archivierte), übernommene Fortschritte, Fortschritte, die in der App neuer
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
 * - Abos, die es noch nicht gibt, werden angelegt und abgeglichen; archivierte als archivierte Abos (ein Abo der App bleibt,
 *   wie es ist). Steht eine Sendung aus ARD Sounds als `ardsounds:` darin, ist sie ein Abo: So schreibt der Export sie ohne
 *   RSS-Bridge (und ältere Exporte immer) als archiviert, weil andere Apps sie nicht abonnieren können.
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
        // Steht eine Adresse aktiv und archiviert in der Datei, gilt sie als aktiv.
        val wanted = backup.feeds
            .mapNotNull { f -> key(f.url)?.let { url -> WantedFeed(url, f.title.ifBlank { url }, f.active || ardSoundsShowId(f.url) != null) } }
            .groupBy { it.url }
            .map { (_, same) -> same.firstOrNull { it.active } ?: same.first() }
        repository.importOpml(wanted.map { OpmlFeed(it.title, it.url) }, replace = false)
        val added = wanted.filter { it.url !in known }.mapNotNull { wish ->
            podcastDao.feedByUrl(wish.url)?.also { if (!wish.active) podcastDao.setArchived(it.feedId, true) }
        }
        // Ohne Folgen gibt es nichts, dem sich ein Fortschritt zuordnen ließe.
        added.forEach { repository.refresh(it.feedId) }

        val feedByUrl = (podcastDao.feeds() + added).associateBy { it.url }
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
                speed = existing?.speed,
                updatedAt = episode.lastPlayedAt,
                skipSilence = existing?.skipSilence,
                volumeBoostDb = existing?.volumeBoostDb,
            ),
        )
        return true
    }

    private data class WantedFeed(val url: String, val title: String, val active: Boolean)

    /** Abo-Adresse wie in der App: Sendungen aus ARD Sounds (auch über die RSS-Bridge) als `ardsounds:<Kennung>`, sonst der Feed. */
    private fun key(url: String): String? = parseArdSoundsInput(url) ?: normalizeFeedUrl(url)?.toString()
}
