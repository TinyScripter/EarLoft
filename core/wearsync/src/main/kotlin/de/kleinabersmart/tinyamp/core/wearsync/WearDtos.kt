package de.kleinabersmart.tinyamp.core.wearsync

import de.kleinabersmart.tinyamp.core.model.ReplayGainMode
import de.kleinabersmart.tinyamp.core.model.SourceType
import de.kleinabersmart.tinyamp.core.model.SpokenMode
import de.kleinabersmart.tinyamp.core.model.WearSelectionKind
import kotlinx.serialization.Serializable

// Nachrichten zwischen Handy und Uhr (Wear-Konzept 4). Neue Felder immer mit Standardwert anlegen,
// damit ältere Gegenstellen sie ignorieren bzw. weglassen können.

// ---- Manifest (Handy → Uhr) ----

/** Soll-Zustand der Uhr: Auswahlen und die aufgelösten Songs (Wear-Konzept 4.1). */
@Serializable
data class WearManifest(
    val selections: List<WearSelectionDto> = emptyList(),
    val songs: List<WearSongDto> = emptyList(),
)

@Serializable
data class WearSelectionDto(
    /** [WearSelectionKind.key] mit den IDs des Handys – nur als Schlüssel, nicht als ID auf der Uhr. */
    val key: String,
    val kind: WearSelectionKind,
    val title: String,
    /** Reihenfolge der Auswahl; Duplikate bei Playlists erlaubt. Verweist auf [WearSongDto.key]. */
    val songKeys: List<String>,
)

@Serializable
data class WearSongDto(
    /** Bevorzugte `sourceUid` am Handy – nur Verweis innerhalb des Manifests. */
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
    /** Server-Quellen, von denen die Uhr direkt laden kann (leer = nur über das Handy). */
    val serverSources: List<WearServerSourceDto> = emptyList(),
    /** `null` = das Handy hat keine Datei (weder lokal noch heruntergeladen). */
    val phoneFile: PhoneFileDto? = null,
)

@Serializable
data class WearServerSourceDto(
    /** `sourceId:remoteId`, wie am Handy. */
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
    /** `sourceUid` am Handy, über die die Uhr die Datei anfordert. */
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

// ---- Server, Katalog, Einstellungen (Handy → Uhr) ----

/** Die Handy-App ist installiert und spricht [protocol] (siehe [WearPaths.PHONE_INFO]). */
@Serializable
data class WearPhoneInfoDto(
    val protocol: Int = WearProtocol.VERSION,
    val appVersion: String? = null,
)

/** Die Uhr-App ist installiert und spricht [protocol] (siehe [WearPaths.WATCH_INFO]). */
@Serializable
data class WearWatchInfoDto(
    val protocol: Int = WearProtocol.VERSION,
    val appVersion: String? = null,
)

/**
 * Server ohne Secrets; das Passwort bzw. der API-Schlüssel kommt nur per [WearCredentialsDto] auf Anfrage.
 * [servers]: die Subsonic-Server. [otherServers]: alle anderen Musikserver (Jellyfin, Emby, Konzept 14) – eigene Liste, weil
 * ältere Uhren jeden Eintrag in [servers] als Subsonic spiegeln; diese Liste kennen sie nicht.
 * [spokenServers]: die eingeschalteten Audiobookshelf-Server (Bücher auf der Uhr, Wear-Konzept 16.2).
 */
@Serializable
data class WearServersDto(
    val servers: List<WearServerDto> = emptyList(),
    val otherServers: List<WearServerDto> = emptyList(),
    val spokenServers: List<WearSpokenServerDto> = emptyList(),
)

/** Ein Audiobookshelf-Server; [serverId] ist auf beiden Geräten dieselbe. */
@Serializable
data class WearSpokenServerDto(
    val serverId: String,
    val name: String,
    val baseUrl: String,
)

@Serializable
data class WearServerDto(
    val sourceId: String,
    /** Name des `SourceType`; als Text, damit die Uhr unbekannte Typen überspringen kann. */
    val type: String = SourceType.SUBSONIC.name,
    val name: String,
    val baseUrl: String,
    val username: String,
    val priority: Int = 0,
    val formPost: Boolean = false,
    /** Ändert sich mit den Zugangsdaten am Handy (HMAC, verrät nichts über sie); `null`, wenn das Handy keine hat. */
    val credentialsStamp: String? = null,
)

enum class WearQuality {
    /** Der Server transkodiert beim Download (Opus mit 128 kbit/s). */
    OPUS_128,

    /** Die Datei wie auf dem Server. */
    ORIGINAL,
}

@Serializable
data class WearSettingsDto(
    val quality: WearQuality = WearQuality.OPUS_128,
    /** Feste Obergrenze für Downloads; `null` = nur [minFreeBytes] gilt (W1). */
    val storageLimitBytes: Long? = null,
    /** So viel Speicher bleibt auf der Uhr mindestens frei (W1: 1 GB). */
    val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
    /** Vom Server nur am Ladegerät laden (Standard) oder auch ohne. */
    val requireCharging: Boolean = true,
    /** Wie am Handy (W2). */
    val replayGainMode: ReplayGainMode = ReplayGainMode.AUTO,
    val preAmpDb: Float = 0f,
    /** Eingeschaltete Bereiche wie am Handy (Allgemein → Bereiche); die Uhr zeigt nur diese. */
    val areas: List<SpokenMode> = SpokenMode.entries,
    /** Sprungweiten der Spulen-Knöpfe wie am Handy (Konzept 12.4), in Sekunden; Standard wie dort. */
    val bookSeekBackSeconds: Int = 10,
    val bookSeekForwardSeconds: Int = 30,
    val podcastSeekBackSeconds: Int = 10,
    val podcastSeekForwardSeconds: Int = 30,
) {
    /** Dieselben Einstellungen ohne die Sprungweiten: Nur Änderungen daran betreffen die Downloads der Uhr. */
    fun withoutSeek(): WearSettingsDto = copy(
        bookSeekBackSeconds = 0,
        bookSeekForwardSeconds = 0,
        podcastSeekBackSeconds = 0,
        podcastSeekForwardSeconds = 0,
    )

    companion object {
        const val DEFAULT_MIN_FREE_BYTES = 1_000_000_000L
    }
}

// ---- Podcast-Folgen auf der Uhr (Wear-Konzept 16.1) ----

/**
 * Die am Handy für die Uhr gewählten Folgen und Bücher (16.2) je in der Reihenfolge des Wählens, mit den Abos der Folgen
 * (Handy → Uhr, gzip-Asset).
 */
@Serializable
data class WearPodcastManifest(
    val feeds: List<WearPodcastFeedDto> = emptyList(),
    val episodes: List<WearEpisodeDto> = emptyList(),
    val books: List<WearBookDto> = emptyList(),
)

/**
 * Ein Hörbuch (Wear-Konzept 16.2). [serverId] ist der Audiobookshelf-Server bzw. bei Büchern vom Gerät des Handys dessen
 * Kennung; zusammen mit [remoteId] auf beiden Geräten derselbe Schlüssel.
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

/** Ein Kapitel; Beginn und Ende als Position im ganzen Buch bzw. in der Folge. */
@Serializable
data class WearChapterDto(val title: String, val startMs: Long, val endMs: Long)

/**
 * Eine Datei des Buchs. [fileId] ist die Kennung bei Audiobookshelf; `null` bei Büchern vom Gerät des Handys (die gibt es
 * nur über den Datei-Kanal, [WearBookFile]).
 */
@Serializable
data class WearBookTrackDto(
    val index: Int,
    val fileId: String? = null,
    val startMs: Long = 0,
    val durationMs: Long = 0,
    val mimeType: String? = null,
)

/** Ein Abo; [feedId] und [url] sind die des Handys, damit Fortschritt und Statistik auf beiden Geräten passen. */
@Serializable
data class WearPodcastFeedDto(
    val feedId: String,
    val url: String,
    val title: String,
    val author: String = "",
    val imageUrl: String? = null,
    val newestFirst: Boolean = false,
    val continuous: Boolean = true,
)

@Serializable
data class WearEpisodeDto(
    val feedId: String,
    val guid: String,
    val title: String,
    /** Veröffentlichung (bzw. erstes Auftauchen), wie `books.addedAt` am Handy. */
    val publishedAt: Long = 0,
    val durationMs: Long = 0,
    /** Adresse der Audiodatei im Feed; `null` bei Folgen vom Handy selbst (nur über den Datei-Kanal). */
    val audioUrl: String? = null,
    val mimeType: String? = null,
    val coverUrl: String? = null,
    val progress: WearEpisodeProgressDto? = null,
    /** Kapitel, die das Handy kennt (aus dem Kapitel-Link des Feeds oder den Tags der Datei). */
    val chapters: List<WearChapterDto> = emptyList(),
    /** Kapitel-Link des Feeds: Fehlen [chapters], holt die Uhr sie beim Abspielen selbst. */
    val chaptersUrl: String? = null,
)

@Serializable
data class WearEpisodeProgressDto(
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean = false,
    val speed: Float = 1f,
    /** Zeitpunkt der letzten Änderung; die neuere Seite gewinnt. */
    val updatedAt: Long,
)

/** Was auf der Uhr gehört wurde (Uhr → Handy); das Handy löscht das DataItem nach dem Import. */
@Serializable
data class WearEpisodeReportDto(
    /** Gegen doppelten Import, wie bei [WearPlaysDto]. */
    val packetId: String,
    val progress: List<WearEpisodeStateDto> = emptyList(),
    val listening: List<WearEpisodeListeningDto> = emptyList(),
)

/** Bei Büchern (16.2) steht in [feedId] die `serverId` und in [guid] die `remoteId` des Buchs. */
@Serializable
data class WearEpisodeStateDto(val feedId: String, val guid: String, val progress: WearEpisodeProgressDto)

/** [listenedMs] Inhaltszeit seit der letzten Meldung, gehört bis [at]. Schlüssel wie bei [WearEpisodeStateDto]. */
@Serializable
data class WearEpisodeListeningDto(val feedId: String, val guid: String, val listenedMs: Long, val at: Long)

// ---- Uhr → Handy ----

@Serializable
data class WearStatusDto(
    val usedBytes: Long = 0,
    val freeBytes: Long = 0,
    val limitBytes: Long? = null,
    val selections: List<WearSelectionStatusDto> = emptyList(),
    /** Titel, die wegen der Speichergrenze nicht eingereiht sind. */
    val missingForStorage: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class WearSelectionStatusDto(
    val key: String,
    val done: Int,
    val total: Int,
    val failed: Int = 0,
)

/** Wiedergaben auf der Uhr; das Handy übernimmt sie in den Hörverlauf und scrobbelt (Wear-Konzept 10). */
@Serializable
data class WearPlaysDto(
    /** Gegen doppelten Import, falls das Handy vor dem Löschen des DataItems abstürzt. */
    val packetId: String,
    val plays: List<WearPlayDto>,
)

@Serializable
data class WearPlayDto(
    val songUid: String,
    val playedAt: Long,
    val listenedMs: Long,
)

/** Auswahl von der Uhr aus ändern: Album, Playlist oder Favoriten des Handys (Schalter „Auf der Uhr“, Wear-Konzept 17). */
@Serializable
data class WearSelectRequest(
    val kind: WearSelectionKind,
    val refId: Long? = null,
    val selected: Boolean,
)

@Serializable
data class WearCredentialsRequest(val sourceId: String)

/**
 * [deviceId]: bei Jellyfin und Emby das Gerät, zu dem der Token gehört; die Uhr weist sich damit aus wie das Handy.
 * `null` bei anderen Servern und bei Anmeldungen von vorher (dann gilt `tinyamp-<sourceId>`).
 */
@Serializable
data class WearCredentialsDto(val sourceId: String, val password: String, val deviceId: String? = null)

// ---- Fernsteuerung des Handys von der Uhr aus ----

/** Was das Handy spielt: Musik oder Gesprochenes (Konzept 12). */
@Serializable
enum class WearRemoteMediaKind { MUSIC, BOOK, EPISODE }

/**
 * Was das Handy gerade spielt (Handy → Uhr). Die Position rechnet die Uhr mit [positionAt] weiter.
 * Bei Büchern und Folgen sind [positionMs] und [durationMs] Zeiten im ganzen Buch (nicht in der Datei), [artist] ist
 * der Autor bzw. der Podcast.
 */
@Serializable
data class WearRemoteStateDto(
    val hasMedia: Boolean = false,
    val kind: WearRemoteMediaKind = WearRemoteMediaKind.MUSIC,
    val title: String = "",
    val artist: String = "",
    /** Album-ID am Handy (nur Musik). */
    val albumId: Long? = null,
    /** Buch- bzw. Folgen-ID am Handy (nur Gesprochenes). */
    val bookId: Long? = null,
    val albumTitle: String = "",
    val durationMs: Long = 0,
    /** Position zum Zeitpunkt [sampledAt] (Handy-Uhrzeit, Millisekunden seit 1970). */
    val positionMs: Long = 0,
    val sampledAt: Long = 0,
    val isPlaying: Boolean = false,
    /** Wiedergabetempo; bei Musik immer 1. */
    val speed: Float = 1f,
    /** Sprungweiten der Spulen-Knöpfe am Handy in Sekunden (nur Gesprochenes). */
    val seekBackSeconds: Int = 10,
    val seekForwardSeconds: Int = 30,
    val shuffle: Boolean = false,
    /** Akzentfarbe des Albums (ARGB); `null`, solange sie fehlt. */
    val seedColor: Int? = null,
    /** Medienlautstärke des Handys, 0..1. */
    val volume: Float = 0f,
    /** Höchste Lautstärkestufe des Handys; [volume] ist ein Vielfaches von 1/[volumeMax]. */
    val volumeMax: Int = 15,
) {
    val isSpoken: Boolean get() = kind != WearRemoteMediaKind.MUSIC

    /**
     * Schlüssel des Covers unter [WearPaths.REMOTE_COVER]: die Album-ID, bei Gesprochenem die negative Buch-ID
     * (Room-IDs sind positiv, die beiden kommen sich so nicht in die Quere).
     */
    val coverKey: Long? get() = if (isSpoken) bookId?.let { -it } else albumId

    /** Position zum Zeitpunkt [now]: läuft die Wiedergabe, zählt sie seit [sampledAt] im Tempo [speed] weiter (höchstens bis zum Ende). */
    fun positionAt(now: Long): Long {
        if (!isPlaying || sampledAt == 0L) return positionMs
        val elapsed = ((now - sampledAt).coerceAtLeast(0) * speed.toDouble()).toLong()
        val position = positionMs + elapsed
        return if (durationMs > 0) position.coerceAtMost(durationMs) else position
    }
}

/**
 * Alben, Künstler und Playlists, Podcast-Abos und Hörbücher des Handys, damit die Uhr sie anzeigen kann (Handy → Uhr, als
 * gzip-Asset). Die Folgen eines Abos fragt die Uhr einzeln ab ([WearRemoteEpisodesRequest]).
 */
@Serializable
data class WearRemoteLibraryDto(
    val albums: List<WearRemoteAlbumDto> = emptyList(),
    val playlists: List<WearRemotePlaylistDto> = emptyList(),
    val favoritesCount: Int = 0,
    val artists: List<WearRemoteArtistDto> = emptyList(),
    /** Aktive Abos in der Reihenfolge des Handys. */
    val podcasts: List<WearRemotePodcastDto> = emptyList(),
    /** Hörbücher, angefangene zuerst (zuletzt gehörtes vorn), dann nach Titel. */
    val books: List<WearRemoteBookDto> = emptyList(),
)

/** Ein Podcast-Abo des Handys; [feedId] wie am Handy und auf der Uhr. */
@Serializable
data class WearRemotePodcastDto(val feedId: String, val title: String, val author: String = "")

/**
 * Ein Hörbuch oder eine Folge des Handys. [id] ist die Buch-ID am Handy (zum Abspielen und Auswählen), [serverId] und
 * [remoteId] erkennen es auf der Uhr wieder (bei Folgen Abo und guid). [author] ist bei Folgen der Podcast. [started]:
 * angefangen und nicht zu Ende gehört.
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

/** Die Uhr fragt die Folgen eines Abos ab ([WearPaths.REMOTE_EPISODES_REQUEST]). */
@Serializable
data class WearRemoteEpisodesRequest(val requestId: String, val feedId: String)

/**
 * Antwort auf [WearRemoteEpisodesRequest]: angefangene Folgen zuerst (zuletzt gehörte vorn), dann die neuesten [MAX_NEWEST];
 * [found] = `false`, wenn das Abo am Handy nicht mehr existiert.
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
 * Folge oder Buch des Handys für die Uhr auswählen bzw. abwählen (Schalter „Auf der Uhr“, Wear-Konzept 17); [bookId] am Handy.
 * Die Speicher-Ansicht der Uhr kennt nur Server bzw. Abo und Kennung aus dem Folgen-Manifest: dann [serverId] und [remoteId]
 * statt [bookId].
 */
@Serializable
data class WearSelectSpokenRequest(
    val bookId: Long = 0,
    val selected: Boolean,
    val serverId: String? = null,
    val remoteId: String? = null,
)

/** Was „Entfernen“ in der Speicher-Ansicht der Uhr leert: alles oder nur Musik, Folgen bzw. Bücher. */
@Serializable
enum class WearClearScope { ALL, MUSIC, PODCASTS, BOOKS }

@Serializable
data class WearClearRequest(val scope: WearClearScope = WearClearScope.ALL)

/** Ein Künstler des Handys mit seinen Alben (Albumkünstler, IDs aus [WearRemoteLibraryDto.albums]). */
@Serializable
data class WearRemoteArtistDto(val id: Long, val name: String, val albumIds: List<Long>)

@Serializable
data class WearRemoteAlbumDto(val id: Long, val title: String, val artist: String, val songCount: Int)

@Serializable
data class WearRemotePlaylistDto(val id: Long, val name: String, val songCount: Int)

/**
 * Welche Titelliste am Handy gemeint ist. [CONTINUE] spielt ein Buch bzw. eine Folge ([WearRemotePlayRequest.refId], wie in
 * der App inkl. Fortsetzen); der Name stammt vom früheren „Weiterhören“ der Uhr (seit 2026-11-05 in Podcasts/Bücher
 * aufgegangen), Titellisten gibt es dafür nicht.
 */
@Serializable
enum class WearRemoteListKind { ALBUM, PLAYLIST, FAVORITES, ALL, CONTINUE }

enum class WearRemoteAction {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    TOGGLE_SHUFFLE,

    /** [WearRemoteCommand.value] = Position in Millisekunden, bei Gesprochenem im ganzen Buch. */
    SEEK,

    /** Gesprochenes: 30 s zurück bzw. 10 s vor (wie in der App). */
    SKIP_BACK,
    SKIP_FORWARD,

    /** [WearRemoteCommand.value] = Schritte der Medienlautstärke, negativ = leiser. */
    VOLUME_STEPS,
}

@Serializable
data class WearRemoteCommand(val action: WearRemoteAction, val value: Long = 0)

/**
 * Das Handy soll eine Titelliste abspielen, ab [startIndex] bzw. dem Titel [startSongId] (falls die Liste sich verschoben hat).
 * Bei [WearRemoteListKind.CONTINUE] ist [refId] das Buch bzw. die Folge, die starten soll.
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

/** Antwort auf [WearRemoteTracksRequest]; [found] = `false`, wenn Album bzw. Playlist am Handy nicht mehr existiert. */
@Serializable
data class WearRemoteTracksResult(
    val requestId: String,
    val found: Boolean = true,
    val title: String = "",
    val tracks: List<WearRemoteTrackDto> = emptyList(),
    /** Die Liste war länger als [MAX_TRACKS] und wurde gekürzt. */
    val truncated: Boolean = false,
) {
    companion object {
        /** Eine Message verträgt rund 100 KB; mit gzip passen so viele Titel sicher hinein. */
        const val MAX_TRACKS = 1000
    }
}

/** Ein Eintrag einer Titelliste. */
@Serializable
data class WearRemoteTrackDto(val songId: Long, val title: String, val artist: String)

/** Wie lange eine Anmeldung ([WearPaths.REMOTE_SUBSCRIBE]) gilt und wie oft die Uhr sie erneuert (mit Reserve für verlorene Nachrichten). */
object WearRemoteSubscription {
    const val TTL_MS = 90_000L
    const val RENEW_MS = 30_000L
}
