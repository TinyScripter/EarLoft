package de.kleinabersmart.earloft.core.datasource

import android.graphics.Bitmap
import android.net.Uri
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.model.Lyrics
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.flow.Flow

/** A music source (local or server). Concept section 6.1. */
interface MusicDataSource {
    val sourceId: String
    val type: SourceType

    /** Display name for the source list. */
    val displayName: String

    /**
     * Delivers the complete library in batches. Throws on errors (e.g. missing permission)
     * so the sync aborts instead of interpreting the library as empty.
     */
    fun fullScan(): Flow<SourceBatch>

    /**
     * Like [fullScan]; [isUnchanged] says whether the source already knows a track with exactly this `lastModified`.
     * Sources that have to do expensive extra reads for new tracks (local tags) skip that for known ones.
     */
    fun fullScan(isUnchanged: suspend (remoteId: String, lastModified: Long) -> Boolean): Flow<SourceBatch> = fullScan()

    /** Called by the player at load time. */
    suspend fun resolvePlayback(remoteId: String): PlaybackLocation

    suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap?
}

/**
 * User data held by a server source (favorites, scrobbles, playlists). Errors are reported as
 * exceptions: `IOException` = retry later.
 */
interface RemoteLibraryActions {
    /** Depending on [kind], [remoteId] is the ID of a song, album or artist. */
    suspend fun setFavorite(remoteId: String, favorite: Boolean, kind: FavoriteKind = FavoriteKind.SONG)

    /** [playedAt] = start of listening in ms since epoch. */
    suspend fun scrobble(remoteId: String, playedAt: Long)

    /** The account's own editable playlists, without songs. */
    suspend fun playlists(): List<RemotePlaylistInfo>

    suspend fun playlist(remoteId: String): RemotePlaylist

    /** @return the state afterwards. */
    suspend fun createPlaylist(name: String, songRemoteIds: List<String>): RemotePlaylistInfo

    /** Replaces name and songs of [current]. @return the state afterwards. */
    suspend fun replacePlaylist(current: RemotePlaylist, name: String, songRemoteIds: List<String>): RemotePlaylistInfo

    suspend fun deletePlaylist(remoteId: String)
}

/**
 * Reports a just-counted play like one of the server's players, so the server's statistics and plugins (Last.fm,
 * ListenBrainz) see it as a play. Late submissions (listened offline, plays from the watch) still go through
 * [RemoteLibraryActions.scrobble] with the correct timestamp. Errors: `IOException`.
 */
interface RemotePlaybackReporting {
    /** [durationMs] = duration of the song; it is reported as listened to the end. */
    suspend fun reportPlayback(remoteId: String, durationMs: Long)
}

/**
 * Cheap intermediate sync for servers (instead of the full sync): picks up newly added albums and the
 * favorite state that other clients have changed. Only the full sync finds deleted or changed songs.
 */
interface RemoteQuickSync {
    /** Songs of the albums that arrived on the server after [since] (ms since epoch). */
    suspend fun tracksAddedSince(since: Long): List<RawTrack>

    /** Everything the account has favorited. */
    suspend fun starred(): RemoteStarred
}

/** Per kind, `remoteId` → time of the star (ms; 0 = unknown). */
data class RemoteStarred(
    val songs: Map<String, Long>,
    val albums: Map<String, Long> = emptyMap(),
    val artists: Map<String, Long> = emptyMap(),
)

/** Sources that provide lyrics. Errors as with [RemoteLibraryActions]; `IOException` = retry later. */
interface RemoteLyrics {
    /** [artist] and [title] serve as the search term for servers without OpenSubsonic. [Lyrics.None] if there are no lyrics. */
    suspend fun lyrics(remoteId: String, artist: String, title: String): Lyrics
}

/** Servers that suggest songs matching a song (mix). Errors: exceptions; empty if the server knows nothing. */
interface RemoteSimilarSongs {
    /** `remoteId`s of up to [count] songs that match the song [remoteId], in the server's order. */
    suspend fun similarSongs(remoteId: String, count: Int): List<String>
}

/** Sources whose files embed lyrics. Read errors count as "no lyrics". */
interface LocalLyrics {
    suspend fun embeddedLyrics(remoteId: String): Lyrics
}

/**
 * Sources whose songs another device on the network can fetch itself (Chromecast). The URLs contain
 * credentials and must neither be stored nor logged.
 */
interface ExternalPlayback {
    /** Original file, or converted by the server with [transcodeTo] (format, bitrate in kbit/s). */
    fun externalStreamUrl(remoteId: String, transcodeTo: Pair<String, Int>? = null): String

    fun externalCoverUrl(coverRemoteId: String, sizePx: Int): String
}

/**
 * Servers that convert a file when it is fetched (downloads for the watch, Wear concept 8.2). As with
 * [MusicDataSource.resolvePlayback], the shared OkHttpClient handles auth; the URL contains no credentials.
 */
interface RemoteTranscoding {
    /** [codec] e.g. `opus`, [maxKbps] highest bitrate in kbit/s. */
    fun transcodedPlayback(remoteId: String, codec: String, maxKbps: Int): PlaybackLocation.Remote
}

/** [changedAt] = last change on the server (ms) – used for conflict detection. */
data class RemotePlaylistInfo(
    val remoteId: String,
    val name: String,
    val changedAt: Long,
)

/** [songRemoteIds] in order, duplicates possible. */
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
    /** ID of the artist on the server (for favorites); local sources have none. */
    val remoteId: String? = null,
)

/** Track as a source delivers it – not yet deduplicated. */
data class RawTrack(
    val remoteId: String,
    val title: String,
    val artistDisplay: String,
    val artists: List<RawArtist>,
    val albumTitle: String,
    val albumArtistDisplay: String,
    val albumArtists: List<RawArtist>,
    /** ID of the album on the server (for favorites); local sources have none. */
    val albumRemoteId: String? = null,
    val isCompilation: Boolean = false,
    val discNumber: Int = 1,
    val trackNumber: Int = 0,
    val durationMs: Long,
    val year: Int? = null,
    /** The first genre. */
    val genre: String? = null,
    /** All genres if the source names several (OpenSubsonic, Jellyfin/Emby); otherwise empty, then [genre] applies. */
    val genres: List<String> = emptyList(),
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
    /** Favorited by the server since (ms); local sources have no favorites. */
    val starredAt: Long? = null,
) {
    /** All genres without empty ones, the first one at the front. */
    val allGenres: List<String>
        get() = genres.ifEmpty { listOfNotNull(genre) }.map { it.trim() }.filter { it.isNotEmpty() }
}
