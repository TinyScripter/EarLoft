package de.kleinabersmart.kleinaberlaut.core.data.spoken

import android.util.Log
import de.kleinabersmart.kleinaberlaut.core.common.normalizeForMerge
import de.kleinabersmart.kleinaberlaut.core.database.dao.BookDao
import de.kleinabersmart.kleinaberlaut.core.database.dao.PodcastDao
import de.kleinabersmart.kleinaberlaut.core.database.entity.BookChapterEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.BookEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.BookTrackEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.kleinaberlaut.core.datasource.local.LocalBookScanner
import de.kleinabersmart.kleinaberlaut.core.datasource.local.LocalEpisode
import de.kleinabersmart.kleinaberlaut.core.datasource.local.LocalEpisodeDetails
import de.kleinabersmart.kleinaberlaut.core.datasource.local.LocalPodcast
import de.kleinabersmart.kleinaberlaut.core.model.AppUris
import de.kleinabersmart.kleinaberlaut.core.model.BookKind
import de.kleinabersmart.kleinaberlaut.core.model.LOCAL_PODCAST_PREFIX
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Podcast-Dateien aus dem Ordner „Podcasts“ auf dem Gerät (Konzept 12.1). Jeder Podcast ist ein Abo ohne Feed
 * (`feedId = `[LOCAL_PODCAST_PREFIX]`<Kennung des Namens>`, `url` = `feedId`), jede Datei eine Folge (`books` mit
 * `kind = EPISODE`, `remoteId` = Pfad ab „Podcasts/“, Datei als `content://`-Adresse in `book_tracks`). So gelten
 * Weiterhören, Warteschlange, Fortschritt und „Nächste Folge“ wie bei Feeds; Abgleich und Downloads entfallen.
 */
@Singleton
class LocalPodcasts @Inject constructor(
    private val bookDao: BookDao,
    private val dao: PodcastDao,
    private val scanner: LocalBookScanner,
    private val settings: SpokenSettings,
    private val covers: BookCovers,
) {
    private val mutex = Mutex()

    /**
     * Gleicht die Dateien in „Podcasts“ mit der Datenbank ab (Neues anlegen, Geändertes neu einlesen, Verschwundenes samt
     * Fortschritt löschen). `false`, wenn das nicht möglich war; dann bleibt alles, wie es ist.
     */
    suspend fun sync(): Boolean = mutex.withLock {
        if (!settings.useLocalPodcasts.first()) return@withLock true
        val generation = System.currentTimeMillis()
        val scanned = try {
            scanner.scanPodcasts { podcast, key, fingerprint ->
                bookDao.bookByRemote(feedIdOf(podcast), key)?.let { it.remoteUpdatedAt == fingerprint && it.detailsLoaded } == true
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Keine Berechtigung für die Podcasts auf dem Gerät", e)
            return@withLock false
        } catch (e: IOException) {
            Log.w(TAG, "Podcasts auf dem Gerät nicht lesbar", e)
            return@withLock false
        }
        scanned.forEach { store(it, generation) }
        // Podcasts ohne Dateien fallen weg. Ein fehlgeschlagener Abgleich kommt nie bis hierher und löscht deshalb nichts.
        val present = scanned.mapTo(HashSet()) { feedIdOf(it.name) }
        dao.feeds().filter { it.feedId.startsWith(LOCAL_PODCAST_PREFIX) && it.feedId !in present }.forEach { dao.deleteFeed(it.feedId) }
        true
    }

    /** Ausgeschaltet: die Podcasts vom Gerät verschwinden samt Fortschritt (wie die lokale Musik). */
    suspend fun remove() = mutex.withLock {
        dao.feeds().filter { it.feedId.startsWith(LOCAL_PODCAST_PREFIX) }.forEach { dao.deleteFeed(it.feedId) }
    }

    private suspend fun store(podcast: LocalPodcast, generation: Long) {
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
        bookDao.deleteUnseen(feedId, generation)

        val existing = dao.feed(feedId)
        // Das Bild des Podcasts ist das Cover einer seiner Folgen, solange diese Folge noch da ist.
        val keepImage = existing?.imageUrl?.let(AppUris::parseBookCoverId)?.let { bookDao.book(it) } != null
        val image = if (keepImage) existing.imageUrl else newestWithCover?.let { AppUris.bookCover(it.first) } ?: existing?.imageUrl
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
        // Das Cover steckt in der Datei; hat sie sich geändert, gilt das zwischengespeicherte nicht mehr.
        covers.invalidate(bookId)
        return bookId
    }

    private fun feedIdOf(name: String): String =
        LOCAL_PODCAST_PREFIX + UUID.nameUUIDFromBytes(name.trim().lowercase().toByteArray())

    private companion object {
        const val TAG = "LocalPodcasts"
    }
}
