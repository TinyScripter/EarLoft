package de.kleinabersmart.earloft.core.playback

import de.kleinabersmart.earloft.core.data.repository.ResolvedPlayback
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a song is played from: file on the device, download (copy of a server file) or stream from [sourceId];
 * [transcodedTo] is format and bitrate (kbit/s) if the server transcodes the stream.
 */
data class PlayedFrom(val kind: Kind, val sourceId: String?, val transcodedTo: Pair<String, Int>? = null) {
    enum class Kind { DEVICE, DOWNLOAD, STREAM }
}

/**
 * Which source [SongDataSource] last opened per song (concept 7.2: decided only on load). Only for
 * display (title info); app and playback run in the same process. Not persisted: after a restart only the
 * next load knows.
 */
@Singleton
class PlayingSources @Inject constructor() {
    private val opened = MutableStateFlow<Map<Long, PlayedFrom>>(emptyMap())

    val bySong: StateFlow<Map<Long, PlayedFrom>> = opened

    internal fun record(songId: Long, playback: ResolvedPlayback) {
        val from = when {
            playback.location is PlaybackLocation.Remote -> PlayedFrom(PlayedFrom.Kind.STREAM, playback.sourceId, playback.transcodedTo)
            // Downloads have no source (they live on the device, independent of the server), local files do.
            playback.sourceId == null -> PlayedFrom(PlayedFrom.Kind.DOWNLOAD, null)
            else -> PlayedFrom(PlayedFrom.Kind.DEVICE, playback.sourceId)
        }
        opened.update { current ->
            if (current[songId] == from) current else (current - songId).plus(songId to from).toList().takeLast(MAX_SONGS).toMap()
        }
    }

    private companion object {
        /** Nobody needs more than the titles around the current one. */
        const val MAX_SONGS = 50
    }
}
