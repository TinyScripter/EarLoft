package de.kleinabersmart.earloft.wear.sync

import android.util.Log
import androidx.room.withTransaction
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.download.BookStorage
import de.kleinabersmart.earloft.core.download.EpisodeStorage
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.wearsync.WearBookDto
import de.kleinabersmart.earloft.core.wearsync.WearBookFile
import de.kleinabersmart.earloft.core.wearsync.WearChapterDto
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeDto
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeFile
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeProgressDto
import de.kleinabersmart.earloft.core.wearsync.WearPodcastManifest
import java.util.Locale
import javax.inject.Inject

/**
 * Übernimmt das Folgen-Manifest des Handys in die Datenbank der Uhr (Wear-Konzept 16.1/16.2): Abos und Folgen mit denselben
 * Schlüsseln wie am Handy (`feedId`, guid), je Folge die Audio-Adresse als einzige Datei; Hörbücher mit `serverId` und
 * `remoteId` und ihrer Dateiliste. Der Fortschritt des Handys gilt, wenn er neuer ist. Was nicht mehr im Manifest steht,
 * verschwindet samt Fortschritt und Datei.
 */
class PodcastMirror @Inject constructor(
    private val database: EarLoftDatabase,
    private val podcastDao: PodcastDao,
    private val bookDao: BookDao,
    private val reported: ReportedEpisodes,
    private val episodeStorage: EpisodeStorage,
    private val bookStorage: BookStorage,
) {
    /** @return die IDs der Folgen und Bücher auf der Uhr in der Reihenfolge des Handys (für die Warteschlange). */
    suspend fun apply(manifest: WearPodcastManifest): List<Long> {
        val result = applyRows(manifest)
        // Erst nach der Transaktion: Eine gerade laufende Übertragung einer entfernten Folge verwirft ihre Datei selbst.
        result.removedEpisodeFiles.forEach { (bookId, path) -> episodeStorage.delete(bookId, path) }
        result.removedBooks.forEach { bookStorage.delete(it) }
        return result.ids
    }

    private class Applied(val ids: List<Long>, val removedEpisodeFiles: List<Pair<Long, String>>, val removedBooks: Set<Long>)

    private suspend fun applyRows(manifest: WearPodcastManifest): Applied = database.withTransaction {
        val generation = bookDao.nextGeneration()
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
                    speed = dto.speed,
                    skipSilence = dto.skipSilence,
                    volumeBoostDb = dto.volumeBoostDb,
                    skipIntroS = dto.skipIntroS,
                    skipOutroS = dto.skipOutroS,
                ),
            )
        }
        val episodeIds = manifest.episodes.filter { it.feedId in feeds }.map { dto ->
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
            // Ohne Kapitel vom Handy bleiben die, die die Uhr über den Kapitel-Link selbst geholt hat.
            if (dto.chapters.isNotEmpty()) applyChapters(bookId, dto.chapters)
            applyProgress(bookId, dto.feedId, dto.guid, dto.progress)
            bookId
        }
        val bookIds = manifest.books.filter { it.tracks.isNotEmpty() }.map { dto ->
            val bookId = bookDao.upsertBook(dto.toEntity(generation))
            applyTracks(bookId, dto)
            applyChapters(bookId, dto.chapters)
            applyProgress(bookId, dto.serverId, dto.remoteId, dto.progress)
            bookId
        }
        val keptEpisodes = episodeIds.toSet()
        val keptBooks = bookIds.toSet()
        val removedEpisodeFiles = podcastDao.downloadedTracks().filter { it.bookId !in keptEpisodes }
            .mapNotNull { track -> track.filePath?.let { track.bookId to it } }
        val removedBooks = bookDao.downloadedBookTracks().map { it.bookId }.filterTo(HashSet()) { it !in keptBooks }
        val removedFeeds = podcastDao.feeds().map { it.feedId }.filter { it !in feeds }
        removedFeeds.forEach { podcastDao.deleteFeed(it) }
        feeds.keys.forEach { bookDao.deleteUnseen(it, generation) }
        bookDao.deleteUnseenBooks(generation)
        Log.d(
            TAG,
            "Übernommen: ${episodeIds.size} Folgen in ${feeds.size} Abos, ${bookIds.size} Bücher; " +
                "${removedFeeds.size} Abos, ${removedEpisodeFiles.size} Folgen-Dateien und ${removedBooks.size} Bücher entfernt",
        )
        Applied(episodeIds + bookIds, removedEpisodeFiles, removedBooks)
    }

    private suspend fun applyProgress(bookId: Long, serverId: String, remoteId: String, progress: WearEpisodeProgressDto?) {
        val remote = progress ?: return
        val local = bookDao.progress(bookId)
        if (local == null || remote.updatedAt > local.updatedAt) {
            bookDao.upsertProgress(
                BookProgressEntity(
                    bookId, remote.positionMs, remote.durationMs, remote.finished, remote.speed, remote.updatedAt,
                    skipSilence = remote.skipSilence,
                    volumeBoostDb = remote.volumeBoostDb,
                ),
            )
            // Kommt vom Handy: nicht als Neuigkeit zurückmelden.
            reported.markProgress(serverId, remoteId, remote.updatedAt)
        }
    }

    /** Gleiche Dateiliste: geladene Dateien bleiben. Sonst neu (alte Dateien räumt der Download-Auftrag als verwaist weg). */
    private suspend fun applyTracks(bookId: Long, dto: WearBookDto) {
        val tracks = dto.tracks.map { track ->
            BookTrackEntity(
                bookId = bookId,
                trackIndex = track.index,
                // Bücher vom Gerät des Handys haben keine Kennung bei Audiobookshelf; die des Datei-Kanals hält den Eintrag eindeutig.
                remoteFileId = track.fileId ?: WearBookFile.uid(dto.serverId, dto.remoteId, track.index),
                startMs = track.startMs,
                durationMs = track.durationMs,
                mimeType = track.mimeType,
            )
        }
        val existing = bookDao.tracks(bookId)
        if (existing.map { it.trackIndex to it.remoteFileId } == tracks.map { it.trackIndex to it.remoteFileId }) return
        bookDao.deleteTracks(bookId)
        bookDao.insertTracks(tracks)
    }

    private suspend fun applyChapters(bookId: Long, chapters: List<WearChapterDto>) {
        val entities = chapters.mapIndexed { index, chapter -> BookChapterEntity(bookId, index, chapter.title, chapter.startMs, chapter.endMs) }
        if (bookDao.chapters(bookId) == entities) return
        bookDao.deleteChapters(bookId)
        bookDao.insertChapters(entities)
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
        chaptersUrl = chaptersUrl,
    )

    private fun WearBookDto.toEntity(generation: Long) = BookEntity(
        serverId = serverId,
        kind = BookKind.BOOK,
        remoteId = remoteId,
        title = title,
        normTitle = title.lowercase(Locale.ROOT),
        author = author,
        narrator = narrator,
        durationMs = durationMs,
        // Die Dateiliste kommt mit dem Manifest; die Uhr fragt Audiobookshelf nie nach Details.
        detailsLoaded = true,
        seenInGeneration = generation,
    )

    private companion object {
        const val TAG = "PodcastMirror"
    }
}
