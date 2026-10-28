package keinabersmart.kleinaberlaut.core.wearsync

/**
 * Pfade der Wearable Data Layer API (Wear-Konzept 4). DataItems tragen Zustand (zuletzt gültiger
 * Stand, wird nach Wiederverbindung zugestellt), Messages Ereignisse (nur bei Erreichbarkeit),
 * Channels Dateien.
 */
object WearPaths {
    /**
     * DataItem, Handy → Uhr: [WearPhoneInfoDto], beim Start der Handy-App geschrieben. Zweiter Weg
     * neben der Capability, um die Handy-App zu erkennen (Capabilities laufen über die Play-Dienste
     * und kommen z. B. zwischen unterschiedlich signierten Emulator-Images nicht an).
     */
    const val PHONE_INFO = "/kal/phone"

    /** DataItem, Uhr → Handy: [WearWatchInfoDto] – Gegenstück zu [PHONE_INFO], damit das Handy die Uhr-App erkennt. */
    const val WATCH_INFO = "/kal/watch"

    /** DataItem, Handy → Uhr: [WearServersDto] (ohne Passwörter). */
    const val SERVERS = "/kal/servers"

    /** DataItem mit Asset, Handy → Uhr: [WearManifest] (gzip, siehe [WearCodec.encodeManifest]). */
    const val MANIFEST = "/kal/manifest"

    /** DataItem, Handy → Uhr: [WearCatalogDto] – was sich auf der Uhr auswählen lässt. */
    const val CATALOG = "/kal/catalog"

    /** DataItem, Handy → Uhr: [WearSettingsDto]. */
    const val SETTINGS = "/kal/settings"

    /** DataItem, Uhr → Handy: [WearStatusDto]. */
    const val WATCH_STATUS = "/kal/watch-status"

    /** DataItem je Paket, Uhr → Handy: [WearPlaysDto]; das Handy löscht es nach dem Import. */
    const val PLAYS_PREFIX = "/kal/plays/"

    /** Message, Uhr → Handy: [WearSelectRequest]. */
    const val SELECT = "/kal/select"

    /** Message, Uhr → Handy, ohne Inhalt: alle Auswahlen entfernen („Alles entfernen“ auf der Uhr). */
    const val CLEAR_SELECTIONS = "/kal/select/clear"

    /** Message, Uhr → Handy: [WearCredentialsRequest]. */
    const val CREDENTIALS_REQUEST = "/kal/credentials/request"

    /** Message, Handy → Uhr: [WearCredentialsDto]. */
    const val CREDENTIALS = "/kal/credentials"

    /** Channel, von der Uhr geöffnet: `/kal/file/<sourceUid>`; das Handy schreibt die Datei im Original. */
    const val FILE_CHANNEL_PREFIX = "/kal/file/"

    fun plays(packetId: String) = "$PLAYS_PREFIX$packetId"

    fun fileChannel(sourceUid: String) = "$FILE_CHANNEL_PREFIX$sourceUid"
}

/** Capabilities aus `res/values/wear.xml` der beiden Apps. */
object WearCapabilities {
    /** Die Handy-App ist installiert. */
    const val PHONE_APP = "kal_phone_app"

    /** Die Uhr-App ist installiert. */
    const val WATCH_APP = "kal_watch_app"
}

/** Schlüssel innerhalb eines DataItems bzw. einer DataMap. */
object WearDataKeys {
    /** [WearProtocol.VERSION] des Absenders. */
    const val PROTOCOL = "protocol"

    /** JSON-Nutzlast (bzw. Asset beim Manifest). */
    const val PAYLOAD = "payload"

    /** [WearCodec.hash] des Manifests – nur bei Änderung neu veröffentlichen. */
    const val HASH = "hash"
}

object WearProtocol {
    /**
     * Wird erhöht, wenn sich die Bedeutung der Nachrichten inkompatibel ändert. Neue optionale
     * Felder brauchen das nicht: Der [WearCodec] ignoriert unbekannte Felder.
     */
    const val VERSION = 1
}
