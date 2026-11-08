package de.kleinabersmart.earloft.core.model

/** Ein eingerichteter Musikserver (Navidrome/Subsonic oder Jellyfin). */
data class ServerInfo(
    val sourceId: String,
    val name: String,
    val baseUrl: String,
    val username: String?,
    /** Epoch-Millis des letzten vollständigen Abgleichs, 0 = noch nie. */
    val lastFullSyncAt: Long,
    /** Ausgeschaltet: kein Abgleich, Inhalte ausgeblendet, nichts gelöscht. */
    val enabled: Boolean = true,
    val type: SourceType = SourceType.SUBSONIC,
)
