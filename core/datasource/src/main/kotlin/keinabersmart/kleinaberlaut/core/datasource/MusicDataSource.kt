package keinabersmart.kleinaberlaut.core.datasource

import android.graphics.Bitmap
import android.net.Uri
import keinabersmart.kleinaberlaut.core.model.SourceType
import kotlinx.coroutines.flow.Flow

/** Eine Musikquelle (lokal oder Server). Konzept Abschnitt 6.1. */
interface MusicDataSource {
    val sourceId: String
    val type: SourceType

    /** Anzeigename für die Quellenliste. */
    val displayName: String

    /**
     * Liefert die komplette Bibliothek in Batches. Wirft bei Fehlern (z. B. fehlende Berechtigung),
     * damit der Abgleich abbricht, statt die Bibliothek als leer zu interpretieren.
     */
    fun fullScan(): Flow<SourceBatch>

    /** Wird zur Ladezeit vom Player aufgerufen. */
    suspend fun resolvePlayback(remoteId: String): PlaybackLocation

    suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap?
}

data class SourceBatch(val tracks: List<RawTrack>)

sealed interface PlaybackLocation {
    data class LocalFile(val uri: Uri) : PlaybackLocation
    data class Remote(val uri: Uri, val cacheKey: String) : PlaybackLocation
}

data class RawArtist(
    val name: String,
    val mbid: String? = null,
)

/** Track, wie ihn eine Quelle liefert – noch nicht dedupliziert. */
data class RawTrack(
    val remoteId: String,
    val title: String,
    val artistDisplay: String,
    val artists: List<RawArtist>,
    val albumTitle: String,
    val albumArtistDisplay: String,
    val albumArtists: List<RawArtist>,
    val isCompilation: Boolean = false,
    val discNumber: Int = 1,
    val trackNumber: Int = 0,
    val durationMs: Long,
    val year: Int? = null,
    val genre: String? = null,
    val mbidTrack: String? = null,
    val mbidAlbum: String? = null,
    val rgTrackGainDb: Float? = null,
    val rgTrackPeak: Float? = null,
    val rgAlbumGainDb: Float? = null,
    val rgAlbumPeak: Float? = null,
    val coverRemoteId: String? = null,
    val mimeType: String? = null,
    val bitRate: Int? = null,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,
    val dateAdded: Long? = null,
)
