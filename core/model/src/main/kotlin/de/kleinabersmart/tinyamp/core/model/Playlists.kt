package de.kleinabersmart.tinyamp.core.model

data class Playlist(
    val id: Long,
    val name: String,
    val songCount: Int,
    val durationMs: Long,
    /** Server, mit dem die Playlist abgeglichen wird; `null` = nur auf diesem Gerät. */
    val serverName: String?,
)

data class PlaylistEntry(
    val entryId: Long,
    val song: Song,
    /** Der Song liegt nicht auf dem Server der Playlist und fehlt dort (Konzept 5.4). */
    val missingOnServer: Boolean,
)

data class PlaylistDetails(
    val playlist: Playlist,
    val entries: List<PlaylistEntry>,
)
