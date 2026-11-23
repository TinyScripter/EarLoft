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
 * Applies the phone's episode manifest to the watch's database (Wear concept 16.1/16.2): subscriptions and episodes with the same
 * keys as on the phone (`feedId`, guid), for each episode the audio address as the only file; audiobooks with `serverId` and
 * `remoteId` and their file list. The phone's progress applies if it is newer. Whatever is no longer in the manifest
 * disappears along with its progress and file.
 */
class PodcastMirror @Inject constructor(
    private val database: EarLoftDatabase,
    private val podcastDao: PodcastDao,
    private val bookDao: BookDao,
    private val reported: ReportedEpisodes,
    private val episodeStorage: EpisodeStorage,
    private val bookStorage: BookStorage,
) {
    /** @return the IDs of the episodes and books on the watch in the phone's order (for the queue). */
    suspend fun apply(manifest: WearPodcastManifest): List<Long> {
        val result = applyRows(manifest)
        // What is no longer on the watch needs no mark of what was reported (review2 J95).
        reported.retainProgress(manifest.episodes.map { it.feedId to it.guid } + manifest.books.map { it.serverId to it.remoteId })
        // Only after the transaction: a running transfer of a removed episode discards its file itself.
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
                    // The watch never refreshes feeds itself and downloads nothing on its own.
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
                    // Episodes from the phone's device have no address; the file channel's identifier keeps the entry unique.
                    remoteFileId = dto.audioUrl ?: WearEpisodeFile.uid(dto.feedId, dto.guid),
                    startMs = 0,
                    durationMs = dto.durationMs,
                    mimeType = dto.mimeType,
                ),
            )
            // Without chapters from the phone, the ones the watch fetched itself via the chapter link stay.
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
            "Applied: ${episodeIds.size} episodes in ${feeds.size} subscriptions, ${bookIds.size} books; " +
                "removed ${removedFeeds.size} subscriptions, ${removedEpisodeFiles.size} episode files and ${removedBooks.size} books",
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
            // Comes from the phone: do not report it back as news.
            reported.markProgress(serverId, remoteId, remote.updatedAt)
        }
    }

    /**
     * Same file list: downloaded files stay, only what is no longer available changes. Otherwise new (the download job
     * cleans up old files as orphaned).
     */
    private suspend fun applyTracks(bookId: Long, dto: WearBookDto) {
        val now = System.currentTimeMillis()
        val tracks = dto.tracks.map { track ->
            BookTrackEntity(
                bookId = bookId,
                trackIndex = track.index,
                // Books from the phone's device have no identifier at Audiobookshelf; the file channel's identifier keeps the entry unique.
                remoteFileId = track.fileId ?: WearBookFile.uid(dto.serverId, dto.remoteId, track.index),
                startMs = track.startMs,
                durationMs = track.durationMs,
                mimeType = track.mimeType,
                unavailableSince = now.takeIf { track.unavailable },
            )
        }
        val existing = bookDao.tracks(bookId)
        if (existing.map { it.trackIndex to it.remoteFileId } == tracks.map { it.trackIndex to it.remoteFileId }) {
            existing.zip(tracks).filter { (old, new) -> (old.unavailableSince != null) != (new.unavailableSince != null) }
                .forEach { (old, new) -> bookDao.setTrackUnavailable(bookId, old.trackIndex, new.unavailableSince) }
            return
        }
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
        // The watch does not search; a simple key is enough.
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
        // The file list comes with the manifest; the watch never asks Audiobookshelf for details.
        detailsLoaded = true,
        seenInGeneration = generation,
    )

    private companion object {
        const val TAG = "PodcastMirror"
    }
}
