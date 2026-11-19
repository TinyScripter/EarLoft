package de.kleinabersmart.earloft.core.model

data class Playlist(
    val id: Long,
    val name: String,
    val songCount: Int,
    val durationMs: Long,
    /** Server the playlist is synced with; `null` = only on this device. */
    val serverName: String?,
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
