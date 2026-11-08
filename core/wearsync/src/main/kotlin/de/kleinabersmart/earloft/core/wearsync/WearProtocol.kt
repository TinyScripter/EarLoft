package de.kleinabersmart.earloft.core.wearsync

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
    const val PHONE_INFO = "/earloft/phone"

    /** DataItem, Uhr → Handy: [WearWatchInfoDto] – Gegenstück zu [PHONE_INFO], damit das Handy die Uhr-App erkennt. */
    const val WATCH_INFO = "/earloft/watch"

    /** DataItem, Handy → Uhr: [WearServersDto] (ohne Passwörter). */
    const val SERVERS = "/earloft/servers"

    /** DataItem mit Asset, Handy → Uhr: [WearManifest] (gzip, siehe [WearCodec.encodeManifest]). */
    const val MANIFEST = "/earloft/manifest"

    /** DataItem, Handy → Uhr: [WearSettingsDto]. */
    const val SETTINGS = "/earloft/settings"

    /** DataItem, Uhr → Handy: [WearStatusDto]. */
    const val WATCH_STATUS = "/earloft/watch-status"

    /** DataItem je Paket, Uhr → Handy: [WearPlaysDto]; das Handy löscht es nach dem Import. */
    const val PLAYS_PREFIX = "/earloft/plays/"

    /** Message, Uhr → Handy: [WearSelectRequest]. */
    const val SELECT = "/earloft/select"

    /** Message, Uhr → Handy: [WearSelectSpokenRequest] – Folge oder Buch für die Uhr wählen (Wear-Konzept 17). */
    const val SELECT_SPOKEN = "/earloft/select/spoken"

    /**
     * Message, Uhr → Handy: [WearClearRequest] – alle Auswahlen bzw. die einer Art entfernen (Speicher-Ansicht der Uhr).
     * Ohne Inhalt: alles.
     */
    const val CLEAR_SELECTIONS = "/earloft/select/clear"

    /** Message, Uhr → Handy: [WearCredentialsRequest]. */
    const val CREDENTIALS_REQUEST = "/earloft/credentials/request"

    /** Message, Handy → Uhr: [WearCredentialsDto]. */
    const val CREDENTIALS = "/earloft/credentials"

    /**
     * Channel, von der Uhr geöffnet: `/earloft/file/<sourceUid>` bzw. für eine Folge `/earloft/file/`[WearEpisodeFile.uid], für
     * eine Buchdatei `/earloft/file/`[WearBookFile.uid], für das Bild einer Folge oder eines Buchs `/earloft/file/`[WearCoverFile.uid],
     * für das Vorschaubild eines Albums, Abos, Buchs oder einer Folge des Handys `/earloft/file/`[WearThumbnailFile.uid]; das
     * Handy schreibt die Datei im Original (Vorschaubilder verkleinert).
     */
    const val FILE_CHANNEL_PREFIX = "/earloft/file/"

    /**
     * Channel, von der Uhr geöffnet, um eine abgebrochene Übertragung fortzusetzen: `/earloft/file-from/<offset>/<sourceUid>`.
     * Das Handy schickt wie bei [FILE_CHANNEL_PREFIX] erst die ganze Länge, dann nur die Bytes ab `<offset>`. Ein eigenes
     * Präfix statt eines Zusatzes an der Kennung: Ein älteres Handy kennt es nicht und antwortet gar nicht (die Uhr wartet
     * ab und versucht es später), statt die Datei von vorn zu schicken und die Teildatei zu beschädigen.
     */
    const val FILE_FROM_CHANNEL_PREFIX = "/earloft/file-from/"

    /** DataItem mit Asset, Handy → Uhr: [WearPodcastManifest] (gzip) – Folgen und Bücher für die Uhr (Wear-Konzept 16.1/16.2). */
    const val PODCASTS = "/earloft/podcasts"

    /** DataItem je Paket, Uhr → Handy: [WearEpisodeReportDto]; das Handy löscht es nach dem Import. */
    const val EPISODES_PREFIX = "/earloft/episodes/"

    // ---- Fernsteuerung des Handys von der Uhr aus ----

    /** DataItem, Handy → Uhr: [WearRemoteStateDto] – was das Handy gerade spielt. */
    const val REMOTE_STATE = "/earloft/remote/state"

    /** DataItem, Handy → Uhr: Cover des laufenden Albums bzw. Buchs, siehe [WearCodec.encodeCover]. */
    const val REMOTE_COVER = "/earloft/remote/cover"

    /** DataItem mit Asset, Handy → Uhr: [WearRemoteLibraryDto] (gzip) – Alben, Künstler, Playlists, Abos und Bücher des Handys. */
    const val REMOTE_LIBRARY = "/earloft/remote/library"

    /**
     * Message, Uhr → Handy, ohne Inhalt: „Ich zeige die Fernsteuerung.“ Nur dann schreibt das Handy Zustand, Cover und
     * Bibliotheksindex (siehe [WearRemoteSubscription]); die Uhr erneuert die Anmeldung, solange die App offen ist.
     */
    const val REMOTE_SUBSCRIBE = "/earloft/remote/subscribe"

    /** Message, Uhr → Handy: [WearRemoteCommand]. */
    const val REMOTE_COMMAND = "/earloft/remote/command"

    /** Message, Uhr → Handy: [WearRemotePlayRequest]. */
    const val REMOTE_PLAY = "/earloft/remote/play"

    /** Message, Uhr → Handy: [WearRemoteTracksRequest]. */
    const val REMOTE_TRACKS_REQUEST = "/earloft/remote/tracks"

    /** Message, Handy → Uhr: [WearRemoteTracksResult] (gzip, siehe [WearCodec.encodeCompressed]). */
    const val REMOTE_TRACKS_RESULT = "/earloft/remote/tracks/result"

    /** Message, Uhr → Handy: [WearRemoteEpisodesRequest]. */
    const val REMOTE_EPISODES_REQUEST = "/earloft/remote/episodes"

    /** Message, Handy → Uhr: [WearRemoteEpisodesResult] (gzip). */
    const val REMOTE_EPISODES_RESULT = "/earloft/remote/episodes/result"

    fun plays(packetId: String) = "$PLAYS_PREFIX$packetId"

    fun episodes(packetId: String) = "$EPISODES_PREFIX$packetId"

    fun fileChannel(sourceUid: String) = "$FILE_CHANNEL_PREFIX$sourceUid"

    /** Kanal für [sourceUid] ab Byte [from]; bei `0` der gewöhnliche [fileChannel]. */
    fun fileChannel(sourceUid: String, from: Long): String =
        if (from <= 0) fileChannel(sourceUid) else "$FILE_FROM_CHANNEL_PREFIX$from/$sourceUid"

    /** Kennung und Startversatz eines Datei-Kanals; `null` für andere Pfade. */
    fun parseFileChannel(path: String): FileChannelRequest? = when {
        path.startsWith(FILE_CHANNEL_PREFIX) -> FileChannelRequest(path.removePrefix(FILE_CHANNEL_PREFIX), 0)
        path.startsWith(FILE_FROM_CHANNEL_PREFIX) -> {
            val rest = path.removePrefix(FILE_FROM_CHANNEL_PREFIX)
            val from = rest.substringBefore('/').toLongOrNull()
            val sourceUid = rest.substringAfter('/', missingDelimiterValue = "")
            if (from == null || from < 0 || sourceUid.isEmpty()) null else FileChannelRequest(sourceUid, from)
        }
        else -> null
    }
}

/** Anfrage im Datei-Kanal: die Datei [sourceUid] ab Byte [from]. */
data class FileChannelRequest(val sourceUid: String, val from: Long)

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

/**
 * Kennung eines Vorschaubilds im Datei-Kanal für die Listen der Uhr im Bereich „Telefon“ (Wear-Konzept 17):
 * `thumb:album:<albumId>` (ID des Handys), `thumb:feed:<feedId>` (Bild des Abos) bzw. `thumb:book:<serverId>:<remoteId>`
 * (Buch oder Folge, mit Rückfall auf das Bild des Abos); Texte URL-kodiert. Das Handy schickt ein kleines JPEG ([SIZE_PX]).
 */
object WearThumbnailFile {
    private const val PREFIX = "thumb:"

    /** Längste Seite des Bilds; die Uhr zeigt es mit 40 dp. */
    const val SIZE_PX = 120

    sealed interface Ref {
        data class Album(val albumId: Long) : Ref

        data class Feed(val feedId: String) : Ref

        data class Book(val serverId: String, val remoteId: String) : Ref
    }

    fun uid(ref: Ref): String = PREFIX + when (ref) {
        is Ref.Album -> "album:${ref.albumId}"
        is Ref.Feed -> "feed:${encode(ref.feedId)}"
        is Ref.Book -> "book:${encode(ref.serverId)}:${encode(ref.remoteId)}"
    }

    /** `null`, wenn [uid] kein Vorschaubild bezeichnet. */
    fun parse(uid: String): Ref? {
        if (!uid.startsWith(PREFIX)) return null
        val parts = uid.removePrefix(PREFIX).split(':')
        return when {
            parts.size == 2 && parts[0] == "album" -> parts[1].toLongOrNull()?.let { Ref.Album(it) }
            parts.size == 2 && parts[0] == "feed" -> Ref.Feed(decode(parts[1]))
            parts.size == 3 && parts[0] == "book" -> Ref.Book(decode(parts[1]), decode(parts[2]))
            else -> null
        }
    }

    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

    private fun decode(value: String) = java.net.URLDecoder.decode(value, "UTF-8")
}

/** Capabilities aus `res/values/wear.xml` der beiden Apps. */
object WearCapabilities {
    /** Die Handy-App ist installiert. */
    const val PHONE_APP = "earloft_phone_app"

    /** Die Uhr-App ist installiert. */
    const val WATCH_APP = "earloft_watch_app"
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
