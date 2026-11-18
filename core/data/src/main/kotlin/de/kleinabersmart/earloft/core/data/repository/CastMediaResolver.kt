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
 * Was ein Cast-Empfänger zum Abspielen eines Songs braucht. [streamUrl] und [coverUrl] enthalten
 * Zugangsdaten – nicht speichern, nicht loggen. [sourceId]: der Musikserver, von dem der Empfänger lädt
 * ([PhoneCastServer.SOURCE_ID] für Dateien vom Handy, `null` bei Büchern und Folgen).
 */
class CastMedia(val streamUrl: String, val mimeType: String, val coverUrl: String?, val sourceId: String? = null) {
    override fun toString() = "CastMedia(mimeType=$mimeType, sourceId=$sourceId)"
}

/**
 * Löst Songs für einen Cast-Empfänger auf. Der Empfänger lädt selbst und kennt kein `earloft://`; zuerst kommen deshalb
 * Server-Quellen in Frage – auch für heruntergeladene Songs, denn der Server bleibt erreichbar, wenn das Handy schläft oder
 * das WLAN verlässt. Reihenfolge der Server wie bei der Wiedergabe ([SourcePreference]). Hat ein Song keinen erreichbaren
 * Server, liefert ihn das Handy selbst aus ([PhoneCastServer], Chromecast Etappe 2): die Datei auf dem Gerät, sonst der
 * Download. Was der Empfänger nicht abspielen kann, fällt weg (umwandeln kann nur ein Server).
 */
@Singleton
class CastMediaResolver @Inject constructor(
    private val libraryDao: LibraryDao,
    private val downloadDao: DownloadDao,
    private val registry: MusicDataSources,
    private val phone: PhoneCastServer,
) {
    /**
     * Songs ohne nutzbare Quelle fehlen im Ergebnis. [excludedSources]: Server, die der Empfänger in dieser Sitzung
     * nicht erreicht hat (auch [PhoneCastServer.SOURCE_ID]); Songs weichen auf den nächsten aus.
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

    /** Die Datei auf dem Gerät (lokale Quelle), sonst der fertige Download – jeweils nur in einem Format für den Empfänger. */
    private suspend fun phoneFile(songId: Long, rows: List<PlayableSourceRow>): Pair<Uri, String>? {
        for (row in rows.filter { it.sourceType == SourceType.LOCAL }.sortedWith(SourcePreference)) {
            val mimeType = CastFormats.playableMimeType(row.mimeType) ?: continue
            val location = try {
                registry[row.sourceId]?.resolvePlayback(row.remoteId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Quelle ${row.uid} nicht nutzbar", e)
                null
            }
            if (location is PlaybackLocation.LocalFile) return location.uri to mimeType
        }
        val server = rows.filter { it.sourceType.isServer }.sortedWith(SourcePreference)
        if (server.isEmpty()) return null
        val file = downloadDao.getDoneFilePaths(songId).map(::File).firstOrNull { it.isFile } ?: return null
        // Ein umgewandelter Download hat das Format seiner Endung, sonst das der Server-Datei.
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

/** Formate, die Cast-Empfänger selbst abspielen (Google Cast „Supported Media“); alles andere wandelt der Server um. */
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

    /** Der MIME-Typ für den Empfänger, `null` = muss umgewandelt werden (auch bei unbekanntem Typ). */
    fun playableMimeType(mimeType: String?): String? =
        mimeType?.lowercase()?.substringBefore(';')?.trim()?.takeIf { it in PLAYABLE }

    /** Der MIME-Typ eines Downloads nach seiner Endung (siehe `DownloadStorage.extensionFor`); `null` bei unbekannter. */
    fun mimeTypeOfFileName(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "m4b", "mp4", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> null
    }
}
