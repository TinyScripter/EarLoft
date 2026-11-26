package de.kleinabersmart.earloft.core.wearsync

import de.kleinabersmart.earloft.core.model.ReplayGainMode
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import kotlinx.serialization.Serializable

// Messages between phone and watch (Wear concept 4). Always add new fields with a default value,
// so that older counterparts can ignore or omit them.

// ---- Manifest (phone → watch) ----

/** Target state of the watch: selections and the resolved songs (Wear concept 4.1). */
@Serializable
data class WearManifest(
    val selections: List<WearSelectionDto> = emptyList(),
    val songs: List<WearSongDto> = emptyList(),
)

@Serializable
data class WearSelectionDto(
    /** [WearSelectionKind.key] with the phone's IDs – only as a key, not as an ID on the watch. */
    val key: String,
    /**
     * `null`: a kind this watch version doesn't know (from a newer phone app); the watch skips the selection instead of
     * failing to read the whole manifest (review G29).
     */
    val kind: WearSelectionKind? = null,
    val title: String,
    /** Order of the selection; duplicates allowed for playlists. Refers to [WearSongDto.key]. */
    val songKeys: List<String>,
)

@Serializable
data class WearSongDto(
    /** Preferred `sourceUid` on the phone – only a reference within the manifest. */
    val key: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val artistDisplay: String,
    val album: String,
    val albumArtist: String,
    val isCompilation: Boolean = false,
    val discNumber: Int = 1,
    val trackNumber: Int = 0,
    val durationMs: Long,
    val year: Int? = null,
    val genre: String? = null,
    val mbid: String? = null,
    val albumMbid: String? = null,
    val replayGain: ReplayGainDto? = null,
    /** Server sources the watch can download from directly (empty = only via the phone). */
    val serverSources: List<WearServerSourceDto> = emptyList(),
    /** `null` = the phone has no file (neither local nor downloaded). */
    val phoneFile: PhoneFileDto? = null,
)

@Serializable
data class WearServerSourceDto(
    /** `sourceId:remoteId`, as on the phone. */
    val uid: String,
    val sourceId: String,
    val remoteId: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val bitRate: Int? = null,
    val coverRemoteId: String? = null,
)

@Serializable
data class PhoneFileDto(
    /** `sourceUid` on the phone via which the watch requests the file. */
    val uid: String,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
)

@Serializable
data class ReplayGainDto(
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
)

// ---- Servers, catalog, settings (phone → watch) ----

/** The phone app is installed and speaks [protocol] (see [WearPaths.PHONE_INFO]). */
@Serializable
data class WearPhoneInfoDto(
    val protocol: Int = WearProtocol.VERSION,
    val appVersion: String? = null,
)

/** The watch app is installed and speaks [protocol] (see [WearPaths.WATCH_INFO]). */
@Serializable
data class WearWatchInfoDto(
    val protocol: Int = WearProtocol.VERSION,
    val appVersion: String? = null,
)

/** Since [since] (watch time, ms) the watch's download job has been waiting for the phone ([WearPaths.PHONE_WANTED]). */
@Serializable
data class WearPhoneWantedDto(val since: Long)

/**
 * Servers without secrets; the password or API key only comes via [WearCredentialsDto] on request.
 * [servers]: the Subsonic servers. [otherServers]: all other music servers (Jellyfin, Emby, concept 14) – a separate list because
 * older watches mirror every entry in [servers] as Subsonic; they don't know this list.
 * [spokenServers]: the enabled Audiobookshelf servers (books on the watch, Wear concept 16.2).
 */
@Serializable
data class WearServersDto(
    val servers: List<WearServerDto> = emptyList(),
    val otherServers: List<WearServerDto> = emptyList(),
    val spokenServers: List<WearSpokenServerDto> = emptyList(),
)

/** An Audiobookshelf server; [serverId] is the same on both devices. */
@Serializable
data class WearSpokenServerDto(
    val serverId: String,
    val name: String,
    val baseUrl: String,
    /** Like [WearServerDto.credentialsStamp], for the API key. */
    val credentialsStamp: String? = null,
)

@Serializable
data class WearServerDto(
    val sourceId: String,
    /** Name of the `SourceType`; as text so that the watch can skip unknown types. */
    val type: String = SourceType.SUBSONIC.name,
    val name: String,
    val baseUrl: String,
    val username: String,
    val priority: Int = 0,
    val formPost: Boolean = false,
    /** Changes with the credentials on the phone (HMAC, reveals nothing about them); `null` if the phone has none. */
    val credentialsStamp: String? = null,
)

enum class WearQuality {
    /** The server transcodes on download (Opus at 128 kbit/s). */
    OPUS_128,

    /** The file as on the server. */
    ORIGINAL,
}

@Serializable
data class WearSettingsDto(
    val quality: WearQuality = WearQuality.OPUS_128,
    /** Fixed upper limit for downloads; `null` = only [minFreeBytes] applies (W1). */
    val storageLimitBytes: Long? = null,
    /** At least this much storage stays free on the watch (W1: 1 GB). */
    val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
    /** Download from the server only while charging (default) or also without. */
    val requireCharging: Boolean = true,
    /** As on the phone (W2). */
    val replayGainMode: ReplayGainMode = ReplayGainMode.AUTO,
    val preAmpDb: Float = 0f,
    /** Enabled areas as on the phone (General → Areas); the watch only shows these. */
    val areas: List<SpokenMode> = SpokenMode.entries,
    /** Skip distances of the seek buttons as on the phone (concept 12.4), in seconds; defaults as there. */
    val bookSeekBackSeconds: Int = 10,
    val bookSeekForwardSeconds: Int = 30,
    val podcastSeekBackSeconds: Int = 10,
    val podcastSeekForwardSeconds: Int = 30,
    /** Defaults for speed and effects per area as on the phone (concept 12.4). */
    val bookSpeed: Float = 1f,
    val bookSkipSilence: Boolean = false,
    val bookVolumeBoostDb: Int = 0,
    val podcastSpeed: Float = 1f,
    val podcastSkipSilence: Boolean = false,
    val podcastVolumeBoostDb: Int = 0,
) {
    /** The same settings without the playback ones (skip distances, speed, effects): only the rest affects downloads. */
    fun withoutSeek(): WearSettingsDto = copy(
        bookSeekBackSeconds = 0,
        bookSeekForwardSeconds = 0,
        podcastSeekBackSeconds = 0,
        podcastSeekForwardSeconds = 0,
        bookSpeed = 0f,
        bookSkipSilence = false,
        bookVolumeBoostDb = 0,
        podcastSpeed = 0f,
        podcastSkipSilence = false,
        podcastVolumeBoostDb = 0,
    )

    /**
     * Whether a download running under [previous] has to start over: only quality (another file) and the charger rule (it may no
     * longer run now) change what it does. The rest only changes the plan (storage limits, areas) or playback (ReplayGain, pre-amp);
     * restarting for those cost the song loaded so far, on every move of the pre-amp slider (review4 T63).
     */
    fun restartsDownloadsAfter(previous: WearSettingsDto): Boolean =
        quality != previous.quality || requireCharging != previous.requireCharging

    companion object {
        const val DEFAULT_MIN_FREE_BYTES = 1_000_000_000L
    }
}

// ---- Podcast episodes on the watch (Wear concept 16.1) ----

/**
 * The episodes and books (16.2) chosen on the phone for the watch, each in the order chosen, with the episodes' subscriptions
 * (phone → watch, gzip asset).
 */
@Serializable
data class WearPodcastManifest(
    val feeds: List<WearPodcastFeedDto> = emptyList(),
    val episodes: List<WearEpisodeDto> = emptyList(),
    val books: List<WearBookDto> = emptyList(),
)

/**
 * An audiobook (Wear concept 16.2). [serverId] is the Audiobookshelf server or, for books from the phone's device, its
 * identifier; together with [remoteId] the same key on both devices.
 */
@Serializable
data class WearBookDto(
    val serverId: String,
    val remoteId: String,
    val title: String,
    val author: String = "",
    val narrator: String = "",
    val durationMs: Long = 0,
    val tracks: List<WearBookTrackDto> = emptyList(),
    val progress: WearEpisodeProgressDto? = null,
    val chapters: List<WearChapterDto> = emptyList(),
)

/**
 * A chapter; start and end as a position in the whole book or episode. [unavailable]: lies in a file that is no longer
 * available (only set in the remote control, [WearRemoteChaptersDto]).
 */
@Serializable
data class WearChapterDto(val title: String, val startMs: Long, val endMs: Long, val unavailable: Boolean = false)

/**
 * A file of the book. [fileId] is the identifier at Audiobookshelf; `null` for books from the phone's device (those are only
 * available via the file channel, [WearBookFile]).
 */
@Serializable
data class WearBookTrackDto(
    val index: Int,
    val fileId: String? = null,
    val startMs: Long = 0,
    val durationMs: Long = 0,
    val mimeType: String? = null,
    /** Not downloaded on the phone and no longer available at the source (an expired part from ARD Sounds): don't download, skip. */
    val unavailable: Boolean = false,
)

/** A subscription; [feedId] and [url] are the phone's, so that progress and statistics match on both devices. */
@Serializable
data class WearPodcastFeedDto(
    val feedId: String,
    val url: String,
    val title: String,
    val author: String = "",
    val imageUrl: String? = null,
    val newestFirst: Boolean = false,
    val continuous: Boolean = true,
    /** Speed and effects of the subscription as on the phone (concept 12.4); `null` = the area's default ([WearSettingsDto]). */
    val speed: Float? = null,
    val skipSilence: Boolean? = null,
    val volumeBoostDb: Int? = null,
    val skipIntroS: Int = 0,
    val skipOutroS: Int = 0,
)

@Serializable
data class WearEpisodeDto(
    val feedId: String,
    val guid: String,
    val title: String,
    /** Publication (or first appearance), like `books.addedAt` on the phone. */
    val publishedAt: Long = 0,
    val durationMs: Long = 0,
    /** Address of the audio file in the feed; `null` for episodes from the phone itself (only via the file channel). */
    val audioUrl: String? = null,
    val mimeType: String? = null,
    val coverUrl: String? = null,
    val progress: WearEpisodeProgressDto? = null,
    /** Chapters the phone knows (from the feed's chapter link or the file's tags). */
    val chapters: List<WearChapterDto> = emptyList(),
    /** The feed's chapter link: if [chapters] is missing, the watch fetches them itself on playback. */
    val chaptersUrl: String? = null,
)

@Serializable
data class WearEpisodeProgressDto(
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean = false,
    /** Own speed; `null` = that of the subscription or area (then missing from the JSON, older counterparts read 1.0). */
    val speed: Float? = null,
    /** Time of the last change; the newer side wins. */
    val updatedAt: Long,
    /** Own effects; `null` = those of the subscription or area. */
    val skipSilence: Boolean? = null,
    val volumeBoostDb: Int? = null,
)

/** What was listened to on the watch (watch → phone); the phone deletes the DataItem after import. */
@Serializable
data class WearEpisodeReportDto(
    /** Against duplicate import, as with [WearPlaysDto]. */
    val packetId: String,
    val progress: List<WearEpisodeStateDto> = emptyList(),
    val listening: List<WearEpisodeListeningDto> = emptyList(),
)

/** For books (16.2) [feedId] holds the book's `serverId` and [guid] its `remoteId`. */
@Serializable
data class WearEpisodeStateDto(val feedId: String, val guid: String, val progress: WearEpisodeProgressDto)

/** [listenedMs] content time since the last report, listened until [at]. Key as in [WearEpisodeStateDto]. */
@Serializable
data class WearEpisodeListeningDto(val feedId: String, val guid: String, val listenedMs: Long, val at: Long)

// ---- Watch → phone ----

@Serializable
data class WearStatusDto(
    val usedBytes: Long = 0,
    val freeBytes: Long = 0,
    val limitBytes: Long? = null,
    val selections: List<WearSelectionStatusDto> = emptyList(),
    /** Titles not enqueued because of the storage limit. */
    val missingForStorage: Int = 0,
    /** The last error as text in the watch's language: only for phones that don't know [lastErrorCode] yet. */
    val lastError: String? = null,
    val updatedAt: Long = 0,
    /** The last error as [WearDownloadError] name; the phone shows it in its own language (review2 Z20). */
    val lastErrorCode: String? = null,
)

/** Download errors of the watch, as reported to the phone ([WearStatusDto.lastErrorCode]); new ones only at the end. */
enum class WearDownloadError {
    SERVER_VIA_PHONE,
    SERVER_REFUSED_VIA_PHONE,
    SERVER_NO_PHONE_COPY,
    PHONE_NO_COPY,
    STORAGE_FULL,
    PHONE_UNREACHABLE,
    ;

    companion object {
        /** `null` for a code of a newer watch app; the phone then shows its text. */
        fun of(code: String?): WearDownloadError? = entries.firstOrNull { it.name == code }
    }
}

@Serializable
data class WearSelectionStatusDto(
    val key: String,
    val done: Int,
    val total: Int,
    val failed: Int = 0,
)

/** Plays on the watch; the phone adds them to the listening history and scrobbles (Wear concept 10). */
@Serializable
data class WearPlaysDto(
    /** Against duplicate import in case the phone crashes before deleting the DataItem. */
    val packetId: String,
    val plays: List<WearPlayDto>,
)

@Serializable
data class WearPlayDto(
    val songUid: String,
    val playedAt: Long,
    val listenedMs: Long,
)

/** Change a selection from the watch: album, playlist or favorites of the phone (switch "On the watch", Wear concept 17). */
@Serializable
data class WearSelectRequest(
    val kind: WearSelectionKind,
    val refId: Long? = null,
    val selected: Boolean,
)

@Serializable
data class WearCredentialsRequest(val sourceId: String)

/**
 * [deviceId]: for Jellyfin and Emby the device the token belongs to; the watch identifies itself with it like the phone.
 * `null` for other servers and for earlier sign-ins (then `earloft-<sourceId>` applies).
 */
@Serializable
data class WearCredentialsDto(val sourceId: String, val password: String, val deviceId: String? = null)

// ---- Remote control of the phone from the watch ----

/** What the phone plays: music or spoken content (concept 12). */
@Serializable
enum class WearRemoteMediaKind { MUSIC, BOOK, EPISODE }

/**
 * What the phone is currently playing (phone → watch). The watch advances the position with [positionAt].
 * For books and episodes [positionMs] and [durationMs] are times in the whole book (not in the file), [artist] is
 * the author or the podcast.
 */
@Serializable
data class WearRemoteStateDto(
    val hasMedia: Boolean = false,
    val kind: WearRemoteMediaKind = WearRemoteMediaKind.MUSIC,
    val title: String = "",
    val artist: String = "",
    /** Album ID on the phone (music only). */
    val albumId: Long? = null,
    /** Book or episode ID on the phone (spoken content only). */
    val bookId: Long? = null,
    val albumTitle: String = "",
    val durationMs: Long = 0,
    /** Position at time [sampledAt] (phone clock, milliseconds since 1970). */
    val positionMs: Long = 0,
    val sampledAt: Long = 0,
    val isPlaying: Boolean = false,
    /** Playback speed; always 1 for music. */
    val speed: Float = 1f,
    /** Skip distances of the phone's seek buttons in seconds (spoken content only). */
    val seekBackSeconds: Int = 10,
    val seekForwardSeconds: Int = 30,
    val shuffle: Boolean = false,
    /** The phone's `Player.REPEAT_MODE_*`; older phones report nothing (= off). */
    val repeatMode: Int = 0,
    /** Accent color of the album (ARGB); `null` while it is missing. */
    val seedColor: Int? = null,
    /** The phone's media volume, 0..1. */
    val volume: Float = 0f,
    /** The phone's highest volume step; [volume] is a multiple of 1/[volumeMax]. */
    val volumeMax: Int = 15,
    /** Remaining time of the phone's sleep timer (real time) at time [sampledAt]; `null` while none is running. */
    val sleepTimerRemainingMs: Long? = null,
    /** The timer runs until the end of the chapter or episode; it then stands still while paused. */
    val sleepTimerToEnd: Boolean = false,
    /** The phone accepts the sleep timer commands; older phones report nothing, then the watch shows no button. */
    val sleepTimerSupported: Boolean = false,
) {
    val isSpoken: Boolean get() = kind != WearRemoteMediaKind.MUSIC

    /** Whether the sleep timer's remaining time keeps running: always with a fixed duration, "until the end" only during playback. */
    val sleepTimerCounting: Boolean get() = !sleepTimerToEnd || isPlaying

    /** Remaining time of the sleep timer at time [now] (phone clock like [sampledAt]), not below 0; `null` without a timer. */
    fun sleepTimerRemainingAt(now: Long): Long? {
        val remaining = sleepTimerRemainingMs ?: return null
        if (!sleepTimerCounting || sampledAt == 0L) return remaining
        return (remaining - (now - sampledAt).coerceAtLeast(0)).coerceAtLeast(0)
    }

    /**
     * The phone must report again because, with this report, the watch computed a different remaining time at [now] than the timer
     * ([remainingMs], [toEnd]): started, ended, extended, seeked into another chapter, speed changed. Small
     * deviations from the timer's one-second tick don't count.
     */
    fun sleepTimerDiffers(now: Long, remainingMs: Long?, toEnd: Boolean): Boolean {
        val shown = sleepTimerRemainingAt(now)
        if (remainingMs == null || shown == null) return (remainingMs == null) != (shown == null)
        return toEnd != sleepTimerToEnd || kotlin.math.abs(remainingMs - shown) > SLEEP_TIMER_DRIFT_MS
    }

    /**
     * Key of the cover under [WearPaths.REMOTE_COVER]: the album ID, for spoken content the negative book ID
     * (Room IDs are positive, so the two don't get in each other's way).
     */
    val coverKey: Long? get() = if (isSpoken) bookId?.let { -it } else albumId

    /** Position at time [now]: while playing, it advances from [sampledAt] at speed [speed] (at most up to the end). */
    fun positionAt(now: Long): Long {
        if (!isPlaying || sampledAt == 0L) return positionMs
        val elapsed = ((now - sampledAt).coerceAtLeast(0) * speed.toDouble()).toLong()
        val position = positionMs + elapsed
        return if (durationMs > 0) position.coerceAtMost(durationMs) else position
    }

    private companion object {
        /** The timer reports about every second; only a larger deviation means it has changed. */
        const val SLEEP_TIMER_DRIFT_MS = 2_500L
    }
}

/**
 * Chapters of the book or episode the phone is playing (phone → watch, [WearPaths.REMOTE_CHAPTERS]); written once per book,
 * not with every state report. The watch computes the current chapter from the extrapolated position
 * ([WearRemoteStateDto.positionAt]); it only takes the list if [bookId] matches [WearRemoteStateDto.bookId].
 */
@Serializable
data class WearRemoteChaptersDto(val bookId: Long, val chapters: List<WearChapterDto> = emptyList())

/**
 * The phone's albums, artists and playlists, podcast subscriptions and audiobooks, so the watch can show them (phone → watch, as a
 * gzip asset). The watch requests a subscription's episodes individually ([WearRemoteEpisodesRequest]).
 */
@Serializable
data class WearRemoteLibraryDto(
    val albums: List<WearRemoteAlbumDto> = emptyList(),
    val playlists: List<WearRemotePlaylistDto> = emptyList(),
    val favoritesCount: Int = 0,
    val artists: List<WearRemoteArtistDto> = emptyList(),
    /** Active subscriptions in the phone's order. */
    val podcasts: List<WearRemotePodcastDto> = emptyList(),
    /** Audiobooks, started ones first (last listened at the front), then by title. */
    val books: List<WearRemoteBookDto> = emptyList(),
    /** Favorite albums or artists (IDs from [albums] or [artists]), most recently favorited first; older phones send none. */
    val favoriteAlbumIds: List<Long> = emptyList(),
    val favoriteArtistIds: List<Long> = emptyList(),
    /**
     * The phone serves the podcast queue ([WearRemoteEpisodesRequest.QUEUE_FEED_ID]); older phones don't know it
     * and answer "not found" (review V18).
     */
    val hasPodcastQueue: Boolean = false,
)

/** A podcast subscription of the phone; [feedId] as on the phone and on the watch. */
@Serializable
data class WearRemotePodcastDto(val feedId: String, val title: String, val author: String = "")

/**
 * An audiobook or episode of the phone. [id] is the book ID on the phone (for playing and selecting), [serverId] and
 * [remoteId] identify it on the watch (for episodes: subscription and guid). For episodes [author] is the podcast. [started]:
 * started and not finished.
 */
@Serializable
data class WearRemoteBookDto(
    val id: Long,
    val title: String,
    val author: String,
    val serverId: String,
    val remoteId: String,
    val started: Boolean = false,
)

/**
 * The watch requests a subscription's episodes ([WearPaths.REMOTE_EPISODES_REQUEST]). With [QUEUE_FEED_ID] instead of a subscription it requests the
 * podcast queue (in its order); older phones answer that with `found = false`.
 */
@Serializable
data class WearRemoteEpisodesRequest(val requestId: String, val feedId: String) {
    companion object {
        /** Not a real feed (feed IDs are addresses). */
        const val QUEUE_FEED_ID = "earloft:queue"
    }
}

/**
 * Response to [WearRemoteEpisodesRequest]: started episodes first (last listened at the front), then the newest [MAX_NEWEST];
 * [found] = `false` if the subscription no longer exists on the phone.
 */
@Serializable
data class WearRemoteEpisodesResult(
    val requestId: String,
    val found: Boolean = true,
    val title: String = "",
    val episodes: List<WearRemoteBookDto> = emptyList(),
) {
    companion object {
        const val MAX_NEWEST = 30
    }
}

/**
 * Select or deselect an episode or book of the phone for the watch (switch "On the watch", Wear concept 17); [bookId] on the phone.
 * The watch's storage view only knows server or subscription and identifier from the episode manifest: then [serverId] and [remoteId]
 * instead of [bookId].
 */
@Serializable
data class WearSelectSpokenRequest(
    val bookId: Long = 0,
    val selected: Boolean,
    val serverId: String? = null,
    val remoteId: String? = null,
)

/** What "Remove" clears in the watch's storage view: everything or only music, episodes or books. */
@Serializable
enum class WearClearScope { ALL, MUSIC, PODCASTS, BOOKS }

@Serializable
data class WearClearRequest(val scope: WearClearScope = WearClearScope.ALL)

/** An artist of the phone with their albums (album artist, IDs from [WearRemoteLibraryDto.albums]). */
@Serializable
data class WearRemoteArtistDto(val id: Long, val name: String, val albumIds: List<Long>)

@Serializable
data class WearRemoteAlbumDto(val id: Long, val title: String, val artist: String, val songCount: Int)

@Serializable
data class WearRemotePlaylistDto(val id: Long, val name: String, val songCount: Int)

/**
 * Which title list on the phone is meant. [CONTINUE] plays a book or episode ([WearRemotePlayRequest.refId], as in
 * the app incl. resume); the name comes from the watch's former "Continue listening" (merged into Podcasts/Books
 * since 2026-11-05), there are no title lists for it.
 */
@Serializable
enum class WearRemoteListKind { ALBUM, PLAYLIST, FAVORITES, ALL, CONTINUE }

enum class WearRemoteAction {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    TOGGLE_SHUFFLE,

    /** [WearRemoteCommand.value] = position in milliseconds, for spoken content in the whole book. */
    SEEK,

    /** Spoken content: 30 s back or 10 s forward (as in the app). */
    SKIP_BACK,
    SKIP_FORWARD,

    /** [WearRemoteCommand.value] = steps of the media volume, negative = quieter. */
    VOLUME_STEPS,

    /** Repeat: off → all → one → off. Older phones discard the unknown command. */
    CYCLE_REPEAT,

    /**
     * Sleep timer on the phone: [SLEEP_TIMER_START] with [WearRemoteCommand.value] = minutes, [SLEEP_TIMER_TO_END] until the end of the
     * chapter or episode, [SLEEP_TIMER_CANCEL] ends it. Only if the phone reports [WearRemoteStateDto.sleepTimerSupported].
     */
    SLEEP_TIMER_START,
    SLEEP_TIMER_TO_END,
    SLEEP_TIMER_CANCEL,
}

@Serializable
data class WearRemoteCommand(val action: WearRemoteAction, val value: Long = 0)

/**
 * The phone should play a title list, from [startIndex] or the title [startSongId] (in case the list has shifted).
 * With [WearRemoteListKind.CONTINUE], [refId] is the book or episode to start.
 */
@Serializable
data class WearRemotePlayRequest(
    val kind: WearRemoteListKind,
    val refId: Long? = null,
    val startSongId: Long? = null,
    val startIndex: Int = 0,
    val shuffle: Boolean = false,
)

@Serializable
data class WearRemoteTracksRequest(val requestId: String, val kind: WearRemoteListKind, val refId: Long? = null)

/** Response to [WearRemoteTracksRequest]; [found] = `false` if the album or playlist no longer exists on the phone. */
@Serializable
data class WearRemoteTracksResult(
    val requestId: String,
    val found: Boolean = true,
    val title: String = "",
    val tracks: List<WearRemoteTrackDto> = emptyList(),
    /** The list was longer than [MAX_TRACKS] and was truncated. */
    val truncated: Boolean = false,
) {
    companion object {
        /** A message holds about 100 KB; with gzip this many titles fit safely. */
        const val MAX_TRACKS = 1000
    }
}

/** An entry of a title list. */
@Serializable
data class WearRemoteTrackDto(val songId: Long, val title: String, val artist: String)

/** How long a subscription ([WearPaths.REMOTE_SUBSCRIBE]) is valid and how often the watch renews it (with slack for lost messages). */
object WearRemoteSubscription {
    const val TTL_MS = 90_000L
    const val RENEW_MS = 30_000L
}
