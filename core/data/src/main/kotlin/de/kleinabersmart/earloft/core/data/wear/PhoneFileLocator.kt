package de.kleinabersmart.earloft.core.data.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.source.MusicDataSources
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.dao.WearDao
import de.kleinabersmart.earloft.core.database.model.WearSourceRow
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.model.isLocalSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A file on the phone, ready to be sent to the watch: [length] bytes from [input]. */
class PhoneFile(val length: Long, val input: InputStream)

/**
 * Finds for a source the file the phone can send to the watch in the original (Wear concept 8.4):
 * the local file or a finished download, never a stream. The watch names any `sourceUid` of the
 * song; the phone decides which source provides the file.
 */
@Singleton
class PhoneFileLocator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wearDao: WearDao,
    private val sources: MusicDataSources,
    private val bookDao: BookDao,
    private val podcastDao: PodcastDao,
    private val covers: BookCovers,
) {
    /** @return `null` if the source is unknown or the phone has no file for the song. */
    suspend fun open(sourceUid: String): PhoneFile? {
        val songId = wearDao.getSongIdsOfSources(listOf(sourceUid)).firstOrNull()?.songId ?: return null
        val candidates = wearDao.getSources(listOf(songId))
        // Local first: the file is on the phone, a download would be a copy of it.
        candidates.filter { it.sourceType == SourceType.LOCAL }.forEach { openLocal(it)?.let { file -> return file } }
        candidates.forEach { openDownload(it)?.let { file -> return file } }
        return null
    }

    /**
     * The file of a podcast episode (Wear concept 16.1): the finished download or, for episodes from the device, the file itself.
     * @return `null` if the phone doesn't know the episode or has no file.
     */
    suspend fun openEpisode(feedId: String, guid: String): PhoneFile? {
        val book = bookDao.bookByRemote(feedId, guid)?.takeIf { it.kind == BookKind.EPISODE } ?: return null
        val track = podcastDao.trackOf(book.bookId) ?: return null
        track.filePath?.let(::File)?.takeIf { track.downloadState == TrackDownload.DONE && it.isFile }?.let { return PhoneFile(it.length(), it.inputStream()) }
        if (!isLocalSource(feedId)) return null
        return openContent(Uri.parse(track.remoteFileId), knownLength = null, label = "$feedId/$guid")
    }

    /**
     * A file of an audiobook (Wear concept 16.2): the finished download or, for books from the device, the file itself.
     * @return `null` if the phone doesn't know the book or has no file.
     */
    suspend fun openBookTrack(serverId: String, remoteId: String, trackIndex: Int): PhoneFile? {
        val book = bookDao.bookByRemote(serverId, remoteId)?.takeIf { it.kind == BookKind.BOOK } ?: return null
        val track = bookDao.tracks(book.bookId).firstOrNull { it.trackIndex == trackIndex } ?: return null
        track.filePath?.let(::File)?.takeIf { track.downloadState == TrackDownload.DONE && it.isFile }?.let { return PhoneFile(it.length(), it.inputStream()) }
        if (!isLocalSource(serverId)) return null
        return openContent(Uri.parse(track.remoteFileId), knownLength = null, label = "$serverId/$remoteId/$trackIndex")
    }

    /**
     * The image of an episode or book (Wear concept 16.1/16.2) as the phone shows it: for episodes without their own image that
     * of the subscription. @return `null` if the phone doesn't know the book or has no image.
     */
    suspend fun openCover(serverId: String, remoteId: String): PhoneFile? {
        val book = bookDao.bookByRemote(serverId, remoteId) ?: return null
        val bytes = covers.coverBytes(book.bookId) ?: return null
        return PhoneFile(bytes.size.toLong(), ByteArrayInputStream(bytes))
    }

    private suspend fun openLocal(source: WearSourceRow): PhoneFile? {
        val location = sources[source.sourceId]?.resolvePlayback(source.remoteId) as? PlaybackLocation.LocalFile ?: return null
        return openContent(location.uri, source.sizeBytes, source.uid)
    }

    private fun openContent(uri: Uri, knownLength: Long?, label: String): PhoneFile? = try {
        val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
        val length = descriptor?.length?.takeIf { it >= 0 } ?: knownLength
        if (descriptor == null || length == null) {
            descriptor?.close()
            null
        } else {
            PhoneFile(length, descriptor.createInputStream())
        }
    } catch (e: FileNotFoundException) {
        Log.w(TAG, "Lokale Datei $label nicht lesbar", e)
        null
    } catch (e: SecurityException) {
        Log.w(TAG, "Kein Zugriff auf $label", e)
        null
    }

    private fun openDownload(source: WearSourceRow): PhoneFile? {
        val file = source.downloadPath?.let(::File)?.takeIf { it.isFile } ?: return null
        return PhoneFile(file.length(), file.inputStream())
    }

    private companion object {
        const val TAG = "PhoneFileLocator"
    }
}
