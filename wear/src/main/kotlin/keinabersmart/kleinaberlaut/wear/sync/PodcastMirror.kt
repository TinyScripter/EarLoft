package keinabersmart.kleinaberlaut.wear.sync

import android.util.Log
import androidx.room.withTransaction
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.dao.PodcastDao
import keinabersmart.kleinaberlaut.core.database.entity.BookEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookProgressEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookTrackEntity
import keinabersmart.kleinaberlaut.core.database.entity.PodcastFeedEntity
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.wearsync.WearEpisodeDto
import keinabersmart.kleinaberlaut.core.wearsync.WearEpisodeFile
import keinabersmart.kleinaberlaut.core.wearsync.WearPodcastManifest
import java.util.Locale
import javax.inject.Inject

/**
 * Übernimmt das Folgen-Manifest des Handys in die Datenbank der Uhr (Wear-Konzept 16.1): Abos und Folgen mit denselben
 * Schlüsseln wie am Handy (`feedId`, guid), je Folge die Audio-Adresse als einzige Datei. Der Fortschritt des Handys gilt,
 * wenn er neuer ist. Was nicht mehr im Manifest steht, verschwindet samt Fortschritt; die Dateien räumt der
 * Download-Auftrag auf ([keinabersmart.kleinaberlaut.core.download.EpisodeStorage.deleteOrphans]).
 */
class PodcastMirror @Inject constructor(
    private val database: KleinaberlautDatabase,
    private val podcastDao: PodcastDao,
    private val bookDao: BookDao,
    private val reported: ReportedEpisodes,
) {
    /** @return die IDs der Folgen auf der Uhr in der Reihenfolge des Handys (für die Warteschlange). */
    suspend fun apply(manifest: WearPodcastManifest): List<Long> = database.withTransaction {
        val generation = System.currentTimeMillis()
        val feeds = manifest.feeds.associateBy { it.feedId }
        manifest.feeds.forEach { dto ->
            val existing = podcastDao.feed(dto.feedId)
            podcastDao.upsertFeed(
                (existing ?: PodcastFeedEntity(feedId = dto.feedId, url = dto.url, title = dto.title)).copy(
                    url = dto.url,
                    title = dto.title,
                    author = dto.author,
                    imageUrl = dto.imageUrl,
                    newestFirst = dto.newestFirst,
                    continuous = dto.continuous,
                    // Die Uhr fragt Feeds nie selbst ab und lädt nichts von sich aus.
                    autoDownload = false,
                ),
            )
        }
        val ids = manifest.episodes.filter { it.feedId in feeds }.map { dto ->
            val bookId = bookDao.upsertBook(dto.toEntity(feedTitle = feeds.getValue(dto.feedId).title, generation))
            podcastDao.setTrack(
                BookTrackEntity(
                    bookId = bookId,
                    trackIndex = 0,
                    // Folgen vom Gerät des Handys haben keine Adresse; die Kennung des Datei-Kanals hält den Eintrag eindeutig.
                    remoteFileId = dto.audioUrl ?: WearEpisodeFile.uid(dto.feedId, dto.guid),
                    startMs = 0,
                    durationMs = dto.durationMs,
                    mimeType = dto.mimeType,
                ),
            )
            dto.progress?.let { remote ->
                val local = bookDao.progress(bookId)
                if (local == null || remote.updatedAt > local.updatedAt) {
                    bookDao.upsertProgress(
                        BookProgressEntity(bookId, remote.positionMs, remote.durationMs, remote.finished, remote.speed, remote.updatedAt),
                    )
                    // Kommt vom Handy: nicht als Neuigkeit zurückmelden.
                    reported.markProgress(dto.feedId, dto.guid, remote.updatedAt)
                }
            }
            bookId
        }
        val removedFeeds = podcastDao.feeds().map { it.feedId }.filter { it !in feeds }
        removedFeeds.forEach { podcastDao.deleteFeed(it) }
        feeds.keys.forEach { bookDao.deleteUnseen(it, generation) }
        Log.d(TAG, "Folgen übernommen: ${ids.size} in ${feeds.size} Abos, ${removedFeeds.size} Abos entfernt")
        ids
    }

    private fun WearEpisodeDto.toEntity(feedTitle: String, generation: Long) = BookEntity(
        serverId = feedId,
        kind = BookKind.EPISODE,
        remoteId = guid,
        title = title,
        // Die Uhr sucht nicht; ein einfacher Schlüssel genügt.
        normTitle = title.lowercase(Locale.ROOT),
        author = feedTitle,
        durationMs = durationMs,
        addedAt = publishedAt,
        detailsLoaded = true,
        seenInGeneration = generation,
        coverUrl = coverUrl,
    )

    private companion object {
        const val TAG = "PodcastMirror"
    }
}
