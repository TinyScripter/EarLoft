package de.kleinabersmart.kleinaberlaut.core.data.repository

import de.kleinabersmart.kleinaberlaut.core.data.source.MusicDataSources
import de.kleinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import de.kleinabersmart.kleinaberlaut.core.database.model.PlayableSourceRow
import de.kleinabersmart.kleinaberlaut.core.datasource.ExternalPlayback
import de.kleinabersmart.kleinaberlaut.core.model.SourceType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Was ein Cast-Empfänger zum Abspielen eines Songs braucht. [streamUrl] und [coverUrl] enthalten
 * Zugangsdaten – nicht speichern, nicht loggen.
 */
class CastMedia(val streamUrl: String, val mimeType: String, val coverUrl: String?) {
    override fun toString() = "CastMedia(mimeType=$mimeType)"
}

/**
 * Löst Songs für einen Cast-Empfänger auf. Der Empfänger lädt selbst und kennt weder `kal://` noch
 * Dateien auf dem Handy, deshalb kommen nur Server-Quellen in Frage – auch für heruntergeladene Songs.
 * Reihenfolge der Server wie bei der Wiedergabe ([SourcePreference]).
 */
@Singleton
class CastMediaResolver @Inject constructor(
    private val libraryDao: LibraryDao,
    private val registry: MusicDataSources,
) {
    /** Songs ohne nutzbare Server-Quelle fehlen im Ergebnis. */
    suspend fun resolve(songIds: Collection<Long>): Map<Long, CastMedia> {
        val result = HashMap<Long, CastMedia>()
        songIds.distinct().chunked(SQLITE_MAX_VARIABLES).forEach { chunk ->
            libraryDao.getPlayableSourcesOfSongs(chunk)
                .filter { it.sourceType == SourceType.SUBSONIC }
                .groupBy { it.songId }
                .forEach { (songId, rows) ->
                    rows.sortedWith(SourcePreference).firstNotNullOfOrNull { castMedia(it) }?.let { result[songId] = it }
                }
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
        )
    }

    private companion object {
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
}
