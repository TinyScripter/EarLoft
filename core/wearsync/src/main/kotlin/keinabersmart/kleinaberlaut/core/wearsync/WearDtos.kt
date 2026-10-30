package keinabersmart.kleinaberlaut.core.wearsync

import keinabersmart.kleinaberlaut.core.model.ReplayGainMode
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
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

/** Server ohne Secrets; das Passwort kommt nur per [WearCredentialsDto] auf Anfrage. */
@Serializable
data class WearServersDto(val servers: List<WearServerDto> = emptyList())

@Serializable
data class WearServerDto(
    val sourceId: String,
    val name: String,
    val baseUrl: String,
    val username: String,
    val priority: Int = 0,
    val formPost: Boolean = false,
)

/** Was sich auf der Uhr auswählen lässt (nur Favoriten und Playlists, Wear-Konzept 5). */
@Serializable
data class WearCatalogDto(
    val favoritesCount: Int = 0,
    val favoritesSelected: Boolean = false,
    val playlists: List<WearCatalogPlaylistDto> = emptyList(),
)

@Serializable
data class WearCatalogPlaylistDto(
    val playlistId: Long,
    val name: String,
    val songCount: Int,
    /** Name des Servers bei Server-Playlists, `null` = Geräte-Playlist des Handys. */
    val serverName: String? = null,
    val selected: Boolean = false,
)

enum class WearQuality {
    /** Navidrome transkodiert beim Download (`format=opus&maxBitRate=128`). */
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
) {
    companion object {
        const val DEFAULT_MIN_FREE_BYTES = 1_000_000_000L
    }
}

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

/** Auswahl von der Uhr aus ändern (nur Favoriten und Playlists). */
@Serializable
data class WearSelectRequest(
    val kind: WearSelectionKind,
    val refId: Long? = null,
    val selected: Boolean,
)

@Serializable
data class WearCredentialsRequest(val sourceId: String)

@Serializable
data class WearCredentialsDto(val sourceId: String, val password: String)

// ---- Fernsteuerung des Handys von der Uhr aus ----

/** Was das Handy gerade spielt (Handy → Uhr). Die Position rechnet die Uhr mit [positionAt] weiter. */
@Serializable
data class WearRemoteStateDto(
    val hasMedia: Boolean = false,
    val title: String = "",
    val artist: String = "",
    /** Album-ID am Handy; gehört zum Cover unter [WearPaths.REMOTE_COVER]. */
    val albumId: Long? = null,
    val albumTitle: String = "",
    val durationMs: Long = 0,
    /** Position zum Zeitpunkt [sampledAt] (Handy-Uhrzeit, Millisekunden seit 1970). */
    val positionMs: Long = 0,
    val sampledAt: Long = 0,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    /** Akzentfarbe des Albums (ARGB); `null`, solange sie fehlt. */
    val seedColor: Int? = null,
    /** Medienlautstärke des Handys, 0..1. */
    val volume: Float = 0f,
    /** Höchste Lautstärkestufe des Handys; [volume] ist ein Vielfaches von 1/[volumeMax]. */
    val volumeMax: Int = 15,
) {
    /** Position zum Zeitpunkt [now]: läuft die Wiedergabe, zählt sie seit [sampledAt] weiter (höchstens bis zum Ende). */
    fun positionAt(now: Long): Long {
        if (!isPlaying || sampledAt == 0L) return positionMs
        val elapsed = (now - sampledAt).coerceAtLeast(0)
        val position = positionMs + elapsed
        return if (durationMs > 0) position.coerceAtMost(durationMs) else position
    }
}

/** Alben und Playlists des Handys, damit die Uhr sie anzeigen kann (Handy → Uhr, als gzip-Asset). */
@Serializable
data class WearRemoteLibraryDto(
    val albums: List<WearRemoteAlbumDto> = emptyList(),
    val playlists: List<WearRemotePlaylistDto> = emptyList(),
    val favoritesCount: Int = 0,
)

@Serializable
data class WearRemoteAlbumDto(val id: Long, val title: String, val artist: String, val songCount: Int)

@Serializable
data class WearRemotePlaylistDto(val id: Long, val name: String, val songCount: Int)

/** Welche Titelliste am Handy gemeint ist. */
@Serializable
enum class WearRemoteListKind { ALBUM, PLAYLIST, FAVORITES, ALL }

enum class WearRemoteAction {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    TOGGLE_SHUFFLE,

    /** [WearRemoteCommand.value] = Position in Millisekunden. */
    SEEK,

    /** [WearRemoteCommand.value] = Schritte der Medienlautstärke, negativ = leiser. */
    VOLUME_STEPS,
}

@Serializable
data class WearRemoteCommand(val action: WearRemoteAction, val value: Long = 0)

/** Das Handy soll eine Titelliste abspielen, ab [startIndex] bzw. dem Titel [startSongId] (falls die Liste sich verschoben hat). */
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

@Serializable
data class WearRemoteTrackDto(val songId: Long, val title: String, val artist: String)
