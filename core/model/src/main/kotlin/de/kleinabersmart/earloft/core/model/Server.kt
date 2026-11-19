package de.kleinabersmart.earloft.core.model

/** A configured music server (Navidrome/Subsonic or Jellyfin). */
data class ServerInfo(
    val sourceId: String,
    val name: String,
    val baseUrl: String,
    val username: String?,
    /** Epoch millis of the last full sync, 0 = never. */
    val lastFullSyncAt: Long,
    /** Switched off: no sync, content hidden, nothing deleted. */
    val enabled: Boolean = true,
    val type: SourceType = SourceType.SUBSONIC,
)
