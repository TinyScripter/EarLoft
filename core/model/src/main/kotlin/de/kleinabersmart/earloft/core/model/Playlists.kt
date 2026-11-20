package de.kleinabersmart.earloft.core.model

data class Playlist(
    val id: Long,
    val name: String,
    val songCount: Int,
    val durationMs: Long,
    /** Server the playlist is synced with; `null` = only on this device. */
    val serverName: String?,
    /** Songs of the server playlist the library doesn't know: missing here, changes only on the server (review G15). */
    val unknownSongs: Int = 0,
)

data class PlaylistEntry(
    val entryId: Long,
    val song: Song,
    /** The song is not on the playlist's server and is missing there (concept 5.4). */
    val missingOnServer: Boolean,
)

data class PlaylistDetails(
    val playlist: Playlist,
    val entries: List<PlaylistEntry>,
)
