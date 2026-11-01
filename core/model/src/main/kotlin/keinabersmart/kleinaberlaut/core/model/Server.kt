package keinabersmart.kleinaberlaut.core.model

/** Ein eingerichteter Navidrome-/Subsonic-Server. */
data class ServerInfo(
    val sourceId: String,
    val name: String,
    val baseUrl: String,
    val username: String?,
    /** Epoch-Millis des letzten vollständigen Abgleichs, 0 = noch nie. */
    val lastFullSyncAt: Long,
    /** Ausgeschaltet: kein Abgleich, Inhalte ausgeblendet, nichts gelöscht. */
    val enabled: Boolean = true,
)
