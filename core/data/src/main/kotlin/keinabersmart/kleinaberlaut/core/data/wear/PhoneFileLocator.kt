package keinabersmart.kleinaberlaut.core.data.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.data.source.MusicDataSources
import keinabersmart.kleinaberlaut.core.data.spoken.BookCovers
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.dao.PodcastDao
import keinabersmart.kleinaberlaut.core.database.dao.WearDao
import keinabersmart.kleinaberlaut.core.database.model.WearSourceRow
import keinabersmart.kleinaberlaut.core.datasource.PlaybackLocation
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.SourceType
import keinabersmart.kleinaberlaut.core.model.TrackDownload
import keinabersmart.kleinaberlaut.core.model.isLocalSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Eine Datei auf dem Handy, bereit zum Senden an die Uhr: [length] Bytes aus [input]. */
class PhoneFile(val length: Long, val input: InputStream)

/**
 * Findet zu einer Quelle die Datei, die das Handy der Uhr im Original schicken kann (Wear-Konzept 8.4):
 * die lokale Datei oder ein fertiger Download, nie ein Stream. Die Uhr nennt irgendeine `sourceUid` des
 * Songs; welche Quelle die Datei liefert, entscheidet das Handy.
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
    /** @return `null`, wenn die Quelle unbekannt ist oder das Handy keine Datei zum Song hat. */
    suspend fun open(sourceUid: String): PhoneFile? {
        val songId = wearDao.getSongIdsOfSources(listOf(sourceUid)).firstOrNull()?.songId ?: return null
        val candidates = wearDao.getSources(listOf(songId))
        // Lokal zuerst: Die Datei liegt auf dem Handy, ein Download wäre eine Kopie davon.
        candidates.filter { it.sourceType == SourceType.LOCAL }.forEach { openLocal(it)?.let { file -> return file } }
        candidates.forEach { openDownload(it)?.let { file -> return file } }
        return null
    }

    /**
     * Die Datei einer Podcast-Folge (Wear-Konzept 16.1): der fertige Download bzw. bei Folgen vom Gerät die Datei selbst.
     * @return `null`, wenn das Handy die Folge nicht kennt oder keine Datei hat.
     */
    suspend fun openEpisode(feedId: String, guid: String): PhoneFile? {
        val book = bookDao.bookByRemote(feedId, guid)?.takeIf { it.kind == BookKind.EPISODE } ?: return null
        val track = podcastDao.trackOf(book.bookId) ?: return null
        track.filePath?.let(::File)?.takeIf { track.downloadState == TrackDownload.DONE && it.isFile }?.let { return PhoneFile(it.length(), it.inputStream()) }
        if (!isLocalSource(feedId)) return null
        return openContent(Uri.parse(track.remoteFileId), knownLength = null, label = "$feedId/$guid")
    }

    /**
     * Eine Datei eines Hörbuchs (Wear-Konzept 16.2): der fertige Download bzw. bei Büchern vom Gerät die Datei selbst.
     * @return `null`, wenn das Handy das Buch nicht kennt oder keine Datei hat.
     */
    suspend fun openBookTrack(serverId: String, remoteId: String, trackIndex: Int): PhoneFile? {
        val book = bookDao.bookByRemote(serverId, remoteId)?.takeIf { it.kind == BookKind.BOOK } ?: return null
        val track = bookDao.tracks(book.bookId).firstOrNull { it.trackIndex == trackIndex } ?: return null
        track.filePath?.let(::File)?.takeIf { track.downloadState == TrackDownload.DONE && it.isFile }?.let { return PhoneFile(it.length(), it.inputStream()) }
        if (!isLocalSource(serverId)) return null
        return openContent(Uri.parse(track.remoteFileId), knownLength = null, label = "$serverId/$remoteId/$trackIndex")
    }

    /**
     * Das Bild einer Folge oder eines Buchs (Wear-Konzept 16.1/16.2), wie es das Handy zeigt: bei Folgen ohne eigenes Bild das
     * des Abos. @return `null`, wenn das Handy das Buch nicht kennt oder kein Bild hat.
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
