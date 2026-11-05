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

    /**
     * Channel, von der Uhr geöffnet: `/kal/file/<sourceUid>` bzw. für eine Folge `/kal/file/`[WearEpisodeFile.uid], für
     * eine Buchdatei `/kal/file/`[WearBookFile.uid], für das Bild einer Folge oder eines Buchs `/kal/file/`[WearCoverFile.uid];
     * das Handy schreibt die Datei im Original.
     */
    const val FILE_CHANNEL_PREFIX = "/kal/file/"

    /** DataItem mit Asset, Handy → Uhr: [WearPodcastManifest] (gzip) – Folgen und Bücher für die Uhr (Wear-Konzept 16.1/16.2). */
    const val PODCASTS = "/kal/podcasts"

    /** DataItem je Paket, Uhr → Handy: [WearEpisodeReportDto]; das Handy löscht es nach dem Import. */
    const val EPISODES_PREFIX = "/kal/episodes/"

    // ---- Fernsteuerung des Handys von der Uhr aus ----

    /** DataItem, Handy → Uhr: [WearRemoteStateDto] – was das Handy gerade spielt. */
    const val REMOTE_STATE = "/kal/remote/state"

    /** DataItem, Handy → Uhr: Cover des laufenden Albums bzw. Buchs, siehe [WearCodec.encodeCover]. */
    const val REMOTE_COVER = "/kal/remote/cover"

    /** DataItem mit Asset, Handy → Uhr: [WearRemoteLibraryDto] (gzip) – Alben und Playlists des Handys. */
    const val REMOTE_LIBRARY = "/kal/remote/library"

    /**
     * Message, Uhr → Handy, ohne Inhalt: „Ich zeige die Fernsteuerung.“ Nur dann schreibt das Handy Zustand, Cover und
     * Bibliotheksindex (siehe [WearRemoteSubscription]); die Uhr erneuert die Anmeldung, solange die App offen ist.
     */
    const val REMOTE_SUBSCRIBE = "/kal/remote/subscribe"

    /** Message, Uhr → Handy: [WearRemoteCommand]. */
    const val REMOTE_COMMAND = "/kal/remote/command"

    /** Message, Uhr → Handy: [WearRemotePlayRequest]. */
    const val REMOTE_PLAY = "/kal/remote/play"

    /** Message, Uhr → Handy: [WearRemoteTracksRequest]. */
    const val REMOTE_TRACKS_REQUEST = "/kal/remote/tracks"

    /** Message, Handy → Uhr: [WearRemoteTracksResult] (gzip, siehe [WearCodec.encodeCompressed]). */
    const val REMOTE_TRACKS_RESULT = "/kal/remote/tracks/result"

    fun plays(packetId: String) = "$PLAYS_PREFIX$packetId"

    fun episodes(packetId: String) = "$EPISODES_PREFIX$packetId"

    fun fileChannel(sourceUid: String) = "$FILE_CHANNEL_PREFIX$sourceUid"
}

/**
 * Kennung einer Folge im Datei-Kanal: `episode:<feedId>:<guid>`, beide Teile URL-kodiert (eine guid kann beliebige
 * Zeichen enthalten, auch `:` und `/`).
 */
object WearEpisodeFile {
    private const val PREFIX = "episode:"

    fun uid(feedId: String, guid: String): String = "$PREFIX${encode(feedId)}:${encode(guid)}"

    /** @return (feedId, guid); `null`, wenn [uid] keine Folge bezeichnet. */
    fun parse(uid: String): Pair<String, String>? {
        if (!uid.startsWith(PREFIX)) return null
        val parts = uid.removePrefix(PREFIX).split(':')
        if (parts.size != 2) return null
        return decode(parts[0]) to decode(parts[1])
    }

    // Die Varianten mit `Charset` gibt es erst ab API 33.
    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

    private fun decode(value: String) = java.net.URLDecoder.decode(value, "UTF-8")
}

/** Kennung einer Buchdatei im Datei-Kanal (16.2): `book:<serverId>:<remoteId>:<index>`, die ersten beiden Teile URL-kodiert. */
object WearBookFile {
    private const val PREFIX = "book:"

    data class Ref(val serverId: String, val remoteId: String, val trackIndex: Int)

    fun uid(serverId: String, remoteId: String, trackIndex: Int): String = "$PREFIX${encode(serverId)}:${encode(remoteId)}:$trackIndex"

    /** `null`, wenn [uid] keine Buchdatei bezeichnet. */
    fun parse(uid: String): Ref? {
        if (!uid.startsWith(PREFIX)) return null
        val parts = uid.removePrefix(PREFIX).split(':')
        if (parts.size != 3) return null
        val index = parts[2].toIntOrNull() ?: return null
        return Ref(decode(parts[0]), decode(parts[1]), index)
    }

    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

    private fun decode(value: String) = java.net.URLDecoder.decode(value, "UTF-8")
}

/**
 * Kennung des Bilds einer Folge oder eines Buchs im Datei-Kanal: `cover:<serverId>:<remoteId>` (bei Folgen `feedId` und
 * guid), beide Teile URL-kodiert. Für Bilder, die die Uhr nicht selbst laden kann (Dateien vom Gerät des Handys, Server
 * nicht erreichbar).
 */
object WearCoverFile {
    private const val PREFIX = "cover:"

    fun uid(serverId: String, remoteId: String): String = "$PREFIX${encode(serverId)}:${encode(remoteId)}"

    /** @return (serverId, remoteId); `null`, wenn [uid] kein Bild bezeichnet. */
    fun parse(uid: String): Pair<String, String>? {
        if (!uid.startsWith(PREFIX)) return null
        val parts = uid.removePrefix(PREFIX).split(':')
        if (parts.size != 2) return null
        return decode(parts[0]) to decode(parts[1])
    }

    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

    private fun decode(value: String) = java.net.URLDecoder.decode(value, "UTF-8")
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
