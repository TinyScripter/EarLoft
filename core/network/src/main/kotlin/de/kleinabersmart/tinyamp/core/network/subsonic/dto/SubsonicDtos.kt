package de.kleinabersmart.tinyamp.core.network.subsonic.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Nur die Felder, die die App nutzt. Alles ist optional, weil sich Server (und Navidrome-Versionen)
 * darin unterscheiden, was sie mitschicken.
 */

@Serializable
data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicResponse,
)

@Serializable
data class SubsonicResponse(
    val status: String,
    val version: String? = null,
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean = false,
    val error: SubsonicErrorDto? = null,
    val openSubsonicExtensions: List<OpenSubsonicExtensionDto>? = null,
    val searchResult3: SearchResult3Dto? = null,
    val albumList2: AlbumList2Dto? = null,
    val album: AlbumDto? = null,
    val starred2: Starred2Dto? = null,
    val playlists: PlaylistsDto? = null,
    val playlist: PlaylistDto? = null,
    val lyricsList: LyricsListDto? = null,
    val lyrics: LyricsDto? = null,
)

@Serializable
data class SubsonicErrorDto(
    val code: Int,
    val message: String? = null,
)

@Serializable
data class OpenSubsonicExtensionDto(
    val name: String,
    val versions: List<Int> = emptyList(),
)

@Serializable
data class SearchResult3Dto(
    val song: List<SongDto> = emptyList(),
)

@Serializable
data class AlbumList2Dto(
    val album: List<AlbumDto> = emptyList(),
)

@Serializable
data class Starred2Dto(
    val song: List<SongDto> = emptyList(),
)

@Serializable
data class ArtistRefDto(
    val id: String? = null,
    val name: String,
    val musicBrainzId: String? = null,
)

@Serializable
data class ReplayGainDto(
    val trackGain: Float? = null,
    val albumGain: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
)

@Serializable
data class SongDto(
    val id: String,
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val albumId: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val size: Long? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    /** Sekunden. */
    val duration: Int? = null,
    /** kbit/s. */
    val bitRate: Int? = null,
    /** ISO-8601. */
    val created: String? = null,
    /** ISO-8601, gesetzt wenn favorisiert. */
    val starred: String? = null,
    val isDir: Boolean = false,
    val type: String? = null,
    val musicBrainzId: String? = null,
    val replayGain: ReplayGainDto? = null,
    val artists: List<ArtistRefDto> = emptyList(),
    val displayArtist: String? = null,
    val albumArtists: List<ArtistRefDto> = emptyList(),
    val displayAlbumArtist: String? = null,
)

@Serializable
data class AlbumDto(
    val id: String,
    val name: String? = null,
    val artist: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val year: Int? = null,
    val created: String? = null,
    val musicBrainzId: String? = null,
    val isCompilation: Boolean = false,
    val displayArtist: String? = null,
    val artists: List<ArtistRefDto> = emptyList(),
    val song: List<SongDto> = emptyList(),
)

@Serializable
data class PlaylistsDto(
    val playlist: List<PlaylistDto> = emptyList(),
)

@Serializable
data class PlaylistDto(
    val id: String,
    val name: String,
    val songCount: Int? = null,
    /** ISO-8601. */
    val changed: String? = null,
    val owner: String? = null,
    /** OpenSubsonic: z. B. Navidromes Smart-Playlists lassen sich nicht ändern. */
    val readonly: Boolean = false,
    val entry: List<SongDto> = emptyList(),
)

@Serializable
data class LyricsListDto(
    val structuredLyrics: List<StructuredLyricsDto> = emptyList(),
)

@Serializable
data class StructuredLyricsDto(
    val lang: String? = null,
    /** Entscheidet synchronisiert/statisch – nicht das Vorhandensein von `start`. */
    val synced: Boolean = false,
    val offset: Long = 0,
    val line: List<LyricLineDto> = emptyList(),
)

@Serializable
data class LyricLineDto(
    val start: Long? = null,
    val value: String = "",
)

/** Klassisches `getLyrics`. */
@Serializable
data class LyricsDto(
    val artist: String? = null,
    val title: String? = null,
    val value: String? = null,
)
