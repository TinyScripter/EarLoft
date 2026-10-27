package keinabersmart.kleinaberlaut.core.datasource

import android.graphics.Bitmap
import android.net.Uri
import keinabersmart.kleinaberlaut.core.model.Lyrics
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

/**
 * Nutzerdaten, die eine Server-Quelle hält (Favoriten, Scrobbles, Playlists). Fehler werden als
 * Ausnahmen gemeldet: `IOException` = später erneut versuchen.
 */
interface RemoteLibraryActions {
    suspend fun setFavorite(remoteId: String, favorite: Boolean)

    /** [playedAt] = Beginn des Hörens in ms seit Epoche. */
    suspend fun scrobble(remoteId: String, playedAt: Long)

    /** Eigene, änderbare Playlists des Kontos, ohne Songs. */
    suspend fun playlists(): List<RemotePlaylistInfo>

    suspend fun playlist(remoteId: String): RemotePlaylist

    /** @return der Stand danach. */
    suspend fun createPlaylist(name: String, songRemoteIds: List<String>): RemotePlaylistInfo

    /** Ersetzt Name und Songs von [current]. @return der Stand danach. */
    suspend fun replacePlaylist(current: RemotePlaylist, name: String, songRemoteIds: List<String>): RemotePlaylistInfo

    suspend fun deletePlaylist(remoteId: String)
}

/** Quellen, die Songtexte liefern. Fehler wie bei [RemoteLibraryActions]; `IOException` = später erneut versuchen. */
interface RemoteLyrics {
    /** [artist] und [title] dienen Servern ohne OpenSubsonic als Suchbegriff. [Lyrics.None], wenn es keinen Text gibt. */
    suspend fun lyrics(remoteId: String, artist: String, title: String): Lyrics
}

/** [changedAt] = letzte Änderung auf dem Server (ms) – dient der Konflikterkennung. */
data class RemotePlaylistInfo(
    val remoteId: String,
    val name: String,
    val changedAt: Long,
)

/** [songRemoteIds] in Reihenfolge, Duplikate möglich. */
data class RemotePlaylist(
    val info: RemotePlaylistInfo,
    val songRemoteIds: List<String>,
)

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
    /** Vom Server favorisiert seit (ms); lokale Quellen kennen keine Favoriten. */
    val starredAt: Long? = null,
)
