package de.kleinabersmart.earloft.core.wearsync

/**
 * Paths of the Wearable Data Layer API (Wear concept 4). DataItems carry state (last valid
 * state, delivered after reconnecting), messages carry events (only when reachable),
 * channels carry files.
 */
object WearPaths {
    /**
     * DataItem, phone → watch: [WearPhoneInfoDto], written when the phone app starts. A second way
     * besides the capability to detect the phone app (capabilities go through Play services
     * and, e.g., don't arrive between differently signed emulator images).
     */
    const val PHONE_INFO = "/earloft/phone"

    /** DataItem, watch → phone: [WearWatchInfoDto] – counterpart to [PHONE_INFO], so the phone detects the watch app. */
    const val WATCH_INFO = "/earloft/watch"

    /** DataItem, phone → watch: [WearServersDto] (without passwords). */
    const val SERVERS = "/earloft/servers"

    /** DataItem with asset, phone → watch: [WearManifest] (gzip, see [WearCodec.encodeManifest]). */
    const val MANIFEST = "/earloft/manifest"

    /** DataItem, phone → watch: [WearSettingsDto]. */
    const val SETTINGS = "/earloft/settings"

    /** DataItem, watch → phone: [WearStatusDto]. */
    const val WATCH_STATUS = "/earloft/watch-status"

    /**
     * DataItem, watch → phone: [WearPhoneWantedDto] – the watch's download job is waiting because the phone was unreachable
     * several times. The Data Layer delivers it as soon as both are connected again; the phone answers with [PHONE_BACK].
     * That way the watch notices the phone's return even where capabilities don't arrive (review V1).
     */
    const val PHONE_WANTED = "/earloft/phone-wanted"

    /** Message, phone → watch: the [WearPhoneWantedDto] from [PHONE_WANTED], returned unchanged. */
    const val PHONE_BACK = "/earloft/phone-back"

    /** DataItem per package, watch → phone: [WearPlaysDto]; the phone deletes it after import. */
    const val PLAYS_PREFIX = "/earloft/plays/"

    /** Message, watch → phone: [WearSelectRequest]. */
    const val SELECT = "/earloft/select"

    /** Message, watch → phone: [WearSelectSpokenRequest] – choose an episode or book for the watch (Wear concept 17). */
    const val SELECT_SPOKEN = "/earloft/select/spoken"

    /**
     * Message, watch → phone: [WearClearRequest] – remove all selections or those of one kind (watch's storage view).
     * Without content: everything.
     */
    const val CLEAR_SELECTIONS = "/earloft/select/clear"

    /** Message, watch → phone: [WearCredentialsRequest]. */
    const val CREDENTIALS_REQUEST = "/earloft/credentials/request"

    /** Message, phone → watch: [WearCredentialsDto]. */
    const val CREDENTIALS = "/earloft/credentials"

    /**
     * Channel opened by the watch: `/earloft/file/<sourceUid>`, or for an episode `/earloft/file/`[WearEpisodeFile.uid], for
     * a book file `/earloft/file/`[WearBookFile.uid], for the image of an episode or book `/earloft/file/`[WearCoverFile.uid],
     * for the thumbnail of an album, subscription, book or episode of the phone `/earloft/file/`[WearThumbnailFile.uid]; the
     * phone writes the original file (thumbnails scaled down).
     */
    const val FILE_CHANNEL_PREFIX = "/earloft/file/"

    /**
     * Channel opened by the watch to resume an aborted transfer: `/earloft/file-from/<offset>/<sourceUid>`.
     * As with [FILE_CHANNEL_PREFIX], the phone first sends the full length, then only the bytes from `<offset>`. A separate
     * prefix instead of a suffix on the identifier: an older phone doesn't know it and doesn't answer at all (the watch waits
     * and tries later), instead of sending the file from the start and corrupting the partial file.
     */
    const val FILE_FROM_CHANNEL_PREFIX = "/earloft/file-from/"

    /** DataItem with asset, phone → watch: [WearPodcastManifest] (gzip) – episodes and books for the watch (Wear concept 16.1/16.2). */
    const val PODCASTS = "/earloft/podcasts"

    /** DataItem per package, watch → phone: [WearEpisodeReportDto]; the phone deletes it after import. */
    const val EPISODES_PREFIX = "/earloft/episodes/"

    // ---- Remote control of the phone from the watch ----

    /** DataItem, phone → watch: [WearRemoteStateDto] – what the phone is currently playing. */
    const val REMOTE_STATE = "/earloft/remote/state"

    /** DataItem, phone → watch: cover of the current album or book, see [WearCodec.encodeCover]. */
    const val REMOTE_COVER = "/earloft/remote/cover"

    /** DataItem, phone → watch: [WearRemoteChaptersDto] (gzip) – chapters of the current book or episode. */
    const val REMOTE_CHAPTERS = "/earloft/remote/chapters"

    /** DataItem with asset, phone → watch: [WearRemoteLibraryDto] (gzip) – the phone's albums, artists, playlists, subscriptions and books. */
    const val REMOTE_LIBRARY = "/earloft/remote/library"

    /**
     * Message, watch → phone, without content: "I'm showing the remote control." Only then does the phone write state, cover and
     * library index (see [WearRemoteSubscription]); the watch renews the subscription while the app is open.
     */
    const val REMOTE_SUBSCRIBE = "/earloft/remote/subscribe"

    /** Message, watch → phone: [WearRemoteCommand]. */
    const val REMOTE_COMMAND = "/earloft/remote/command"

    /** Message, watch → phone: [WearRemotePlayRequest]. */
    const val REMOTE_PLAY = "/earloft/remote/play"

    /** Message, watch → phone: [WearRemoteTracksRequest]. */
    const val REMOTE_TRACKS_REQUEST = "/earloft/remote/tracks"

    /** Message, phone → watch: [WearRemoteTracksResult] (gzip, see [WearCodec.encodeCompressed]). */
    const val REMOTE_TRACKS_RESULT = "/earloft/remote/tracks/result"

    /** Message, watch → phone: [WearRemoteEpisodesRequest]. */
    const val REMOTE_EPISODES_REQUEST = "/earloft/remote/episodes"

    /** Message, phone → watch: [WearRemoteEpisodesResult] (gzip). */
    const val REMOTE_EPISODES_RESULT = "/earloft/remote/episodes/result"

    fun plays(packetId: String) = "$PLAYS_PREFIX$packetId"

    fun episodes(packetId: String) = "$EPISODES_PREFIX$packetId"

    fun fileChannel(sourceUid: String) = "$FILE_CHANNEL_PREFIX$sourceUid"

    /** Channel for [sourceUid] from byte [from]; for `0` the regular [fileChannel]. */
    fun fileChannel(sourceUid: String, from: Long): String =
        if (from <= 0) fileChannel(sourceUid) else "$FILE_FROM_CHANNEL_PREFIX$from/$sourceUid"

    /** Identifier and start offset of a file channel; `null` for other paths. */
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

/** Request in the file channel: the file [sourceUid] from byte [from]. */
data class FileChannelRequest(val sourceUid: String, val from: Long)

/**
 * Identifier of an episode in the file channel: `episode:<feedId>:<guid>`, both parts URL-encoded (a guid may contain any
 * characters, including `:` and `/`).
 */
object WearEpisodeFile {
    private const val PREFIX = "episode:"

    fun uid(feedId: String, guid: String): String = "$PREFIX${encode(feedId)}:${encode(guid)}"

    /** @return (feedId, guid); `null` if [uid] doesn't denote an episode. */
    fun parse(uid: String): Pair<String, String>? {
        if (!uid.startsWith(PREFIX)) return null
        val parts = uid.removePrefix(PREFIX).split(':')
        if (parts.size != 2) return null
        return decode(parts[0]) to decode(parts[1])
    }

    // The variants with `Charset` only exist from API 33.
    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

    private fun decode(value: String) = java.net.URLDecoder.decode(value, "UTF-8")
}

/** Identifier of a book file in the file channel (16.2): `book:<serverId>:<remoteId>:<index>`, the first two parts URL-encoded. */
object WearBookFile {
    private const val PREFIX = "book:"

    data class Ref(val serverId: String, val remoteId: String, val trackIndex: Int)

    fun uid(serverId: String, remoteId: String, trackIndex: Int): String = "$PREFIX${encode(serverId)}:${encode(remoteId)}:$trackIndex"

    /** `null` if [uid] doesn't denote a book file. */
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
 * Identifier of the image of an episode or book in the file channel: `cover:<serverId>:<remoteId>` (for episodes `feedId` and
 * guid), both parts URL-encoded. For images the watch can't load itself (files from the phone's device, server
 * unreachable).
 */
object WearCoverFile {
    private const val PREFIX = "cover:"

    fun uid(serverId: String, remoteId: String): String = "$PREFIX${encode(serverId)}:${encode(remoteId)}"

    /** @return (serverId, remoteId); `null` if [uid] doesn't denote an image. */
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
 * Identifier of a thumbnail in the file channel for the watch's lists in the "Phone" section (Wear concept 17):
 * `thumb:album:<albumId>` (phone's ID), `thumb:feed:<feedId>` (subscription image) or `thumb:book:<serverId>:<remoteId>`
 * (book or episode, falling back to the subscription image); texts URL-encoded. The phone sends a small JPEG ([SIZE_PX]).
 */
object WearThumbnailFile {
    private const val PREFIX = "thumb:"

    /** Longest side of the image; the watch shows it at 40 dp. */
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

    /** `null` if [uid] doesn't denote a thumbnail. */
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

/** Capabilities from `res/values/wear.xml` of both apps. */
object WearCapabilities {
    /** The phone app is installed. */
    const val PHONE_APP = "earloft_phone_app"

    /** The watch app is installed. */
    const val WATCH_APP = "earloft_watch_app"
}

/** Keys within a DataItem or DataMap. */
object WearDataKeys {
    /** [WearProtocol.VERSION] of the sender. */
    const val PROTOCOL = "protocol"

    /** JSON payload (or asset for the manifest). */
    const val PAYLOAD = "payload"

    /** [WearCodec.hash] of the manifest – only republish on change. */
    const val HASH = "hash"
}

object WearProtocol {
    /**
     * Incremented when the meaning of the messages changes incompatibly. New optional
     * fields don't need that: the [WearCodec] ignores unknown fields.
     */
    const val VERSION = 1
}
