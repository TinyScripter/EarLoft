package de.kleinabersmart.earloft.core.data.repository

import android.net.Uri
import android.util.Log
import de.kleinabersmart.earloft.core.data.source.MusicDataSources
import de.kleinabersmart.earloft.core.database.dao.DownloadDao
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.model.PlayableSourceRow
import de.kleinabersmart.earloft.core.datasource.ExternalPlayback
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a cast receiver needs to play a song. [streamUrl] and [coverUrl] contain
 * credentials – don't store, don't log. [sourceId]: the music server the receiver loads from
 * ([PhoneCastServer.SOURCE_ID] for files from the phone, `null` for books and episodes).
 */
class CastMedia(val streamUrl: String, val mimeType: String, val coverUrl: String?, val sourceId: String? = null) {
    override fun toString() = "CastMedia(mimeType=$mimeType, sourceId=$sourceId)"
}

/**
 * Resolves songs for a cast receiver. The receiver loads by itself and doesn't know `earloft://`; therefore server
 * sources come first – even for downloaded songs, because the server stays reachable when the phone sleeps or
 * leaves the Wi-Fi. Server order as for playback ([SourcePreference]). If a song has no reachable
 * server, the phone serves it itself ([PhoneCastServer], Chromecast stage 2): the file on the device, otherwise the
 * download. Whatever the receiver can't play is dropped (only a server can transcode).
 */
@Singleton
class CastMediaResolver @Inject constructor(
    private val libraryDao: LibraryDao,
    private val downloadDao: DownloadDao,
    private val registry: MusicDataSources,
    private val phone: PhoneCastServer,
) {
    /**
     * Songs without a usable source are missing from the result. [excludedSources]: servers the receiver couldn't reach
     * in this session (including [PhoneCastServer.SOURCE_ID]); songs fall back to the next one.
     */
    suspend fun resolve(songIds: Collection<Long>, excludedSources: Set<String> = emptySet()): Map<Long, CastMedia> {
        val result = HashMap<Long, CastMedia>()
        val forPhone = HashMap<Long, List<PlayableSourceRow>>()
        songIds.distinct().chunked(SQLITE_MAX_VARIABLES).forEach { chunk ->
            libraryDao.getPlayableSourcesOfSongs(chunk)
                .groupBy { it.songId }
                .forEach { (songId, rows) ->
                    val server = rows.filter { it.sourceType.isServer && it.sourceId !in excludedSources }
                        .sortedWith(SourcePreference)
                        .firstNotNullOfOrNull { castMedia(it) }
                    if (server != null) result[songId] = server else forPhone[songId] = rows
                }
        }
        if (forPhone.isNotEmpty() && PhoneCastServer.SOURCE_ID !in excludedSources) {
            val albums = forPhone.keys.chunked(SQLITE_MAX_VARIABLES)
                .flatMap { libraryDao.getAlbumIdsOfSongs(it) }
                .associate { it.songId to it.albumId }
            forPhone.forEach { (songId, rows) -> fromPhone(songId, rows, albums[songId])?.let { result[songId] = it } }
        }
        return result
    }

    private suspend fun castMedia(row: PlayableSourceRow): CastMedia? {
        val source = registry[row.sourceId] as? ExternalPlayback ?: return null
        val playable = CastFormats.playableMimeType(row.mimeType)
        return CastMedia(
            streamUrl = source.externalStreamUrl(row.remoteId, if (playable == null) CastFormats.TRANSCODE_TARGET else null),
            mimeType = playable ?: CastFormats.TRANSCODED_MIME_TYPE,
            coverUrl = source.externalCoverUrl(row.coverRemoteId ?: row.remoteId, COVER_SIZE_PX),
            sourceId = row.sourceId,
        )
    }

    private suspend fun fromPhone(songId: Long, rows: List<PlayableSourceRow>, albumId: Long?): CastMedia? {
        val (uri, mimeType) = phoneFile(songId, rows) ?: return null
        val url = phone.fileUrl(uri, mimeType) ?: return null
        return CastMedia(url, mimeType, albumId?.let(phone::albumCoverUrl), PhoneCastServer.SOURCE_ID)
    }

    /** The file on the device (local source), otherwise the finished download – each only in a format for the receiver. */
    private suspend fun phoneFile(songId: Long, rows: List<PlayableSourceRow>): Pair<Uri, String>? {
        for (row in rows.filter { it.sourceType == SourceType.LOCAL }.sortedWith(SourcePreference)) {
            val mimeType = CastFormats.playableMimeType(row.mimeType) ?: continue
            val location = try {
                registry[row.sourceId]?.resolvePlayback(row.remoteId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Source ${row.uid} not usable", e)
                null
            }
            if (location is PlaybackLocation.LocalFile) return location.uri to mimeType
        }
        val server = rows.filter { it.sourceType.isServer }.sortedWith(SourcePreference)
        if (server.isEmpty()) return null
        val file = downloadDao.getDoneFilePaths(songId).map(::File).firstOrNull { it.isFile } ?: return null
        // A transcoded download has the format of its extension, otherwise that of the server file.
        val mimeType = CastFormats.mimeTypeOfFileName(file.name)
            ?: server.firstNotNullOfOrNull { CastFormats.playableMimeType(it.mimeType) }
            ?: return null
        return Uri.fromFile(file) to mimeType
    }

    private companion object {
        const val TAG = "CastMediaResolver"
        const val SQLITE_MAX_VARIABLES = 900
        const val COVER_SIZE_PX = 512
    }
}

/** Formats that cast receivers play themselves (Google Cast "Supported Media"); the server transcodes everything else. */
object CastFormats {
    val TRANSCODE_TARGET = "mp3" to 320
    const val TRANSCODED_MIME_TYPE = "audio/mpeg"

    private val PLAYABLE = setOf(
        "audio/mpeg", "audio/mp3",
        "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac", "audio/aacp",
        "audio/flac", "audio/x-flac",
        "audio/ogg", "audio/opus", "audio/vorbis", "audio/webm",
        "audio/wav", "audio/x-wav", "audio/wave",
    )

    /** The MIME type for the receiver, `null` = must be transcoded (also for an unknown type). */
    fun playableMimeType(mimeType: String?): String? =
        mimeType?.lowercase()?.substringBefore(';')?.trim()?.takeIf { it in PLAYABLE }

    /** The MIME type of a download by its extension (see `DownloadStorage.extensionFor`); `null` if unknown. */
    fun mimeTypeOfFileName(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "m4b", "mp4", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> null
    }
}
