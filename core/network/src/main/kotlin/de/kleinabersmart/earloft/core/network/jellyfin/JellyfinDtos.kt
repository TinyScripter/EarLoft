package de.kleinabersmart.earloft.core.network.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /System/Info/Public` – without login; [productName] distinguishes Jellyfin from Emby (which gives none). */
@Serializable
data class JfPublicSystemInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("ProductName") val productName: String? = null,
    @SerialName("Id") val id: String? = null,
)

@Serializable
data class JfAuthenticate(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
) {
    override fun toString() = "JfAuthenticate($username)"
}

@Serializable
data class JfAuthResult(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("User") val user: JfUser,
) {
    override fun toString() = "JfAuthResult(user=${user.id})"
}

@Serializable
data class JfUser(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class JfItemsPage(
    @SerialName("Items") val items: List<JfItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

/** An entry of the library (`BaseItemDto`): song (`Audio`), playlist or library view. */
@Serializable
data class JfItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("MediaType") val mediaType: String? = null,
    /** Only with `fields=Etag`; for playlists Jellyfin changes it with name and entries (measured on 12.4). */
    @SerialName("Etag") val etag: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("AlbumArtists") val albumArtists: List<JfNameId> = emptyList(),
    @SerialName("ArtistItems") val artistItems: List<JfNameId> = emptyList(),
    @SerialName("Artists") val artists: List<String> = emptyList(),
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    /** 1 tick = 100 ns. */
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("ProviderIds") val providerIds: Map<String, String> = emptyMap(),
    /** Loudness measured by the server (LUFS scan), in dB like ReplayGain; without peak. */
    @SerialName("NormalizationGain") val normalizationGain: Float? = null,
    @SerialName("AlbumNormalizationGain") val albumNormalizationGain: Float? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("MediaSources") val mediaSources: List<JfMediaSource> = emptyList(),
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("UserData") val userData: JfUserData? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    /** Only in the entries of an Emby playlist: the entry's number for removal. */
    @SerialName("PlaylistItemId") val playlistItemId: String? = null,
)

@Serializable
data class JfNameId(
    @SerialName("Name") val name: String = "",
    @SerialName("Id") val id: String? = null,
)

@Serializable
data class JfMediaSource(
    @SerialName("Size") val size: Long? = null,
    /** bit/s. */
    @SerialName("Bitrate") val bitrate: Int? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Id") val id: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<JfMediaStream> = emptyList(),
)

/** Emby keeps lyrics as subtitles: embedded with the text in [extradata], as an external `.lrc` next to the file. */
@Serializable
data class JfMediaStream(
    @SerialName("Type") val type: String? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Index") val index: Int? = null,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("Extradata") val extradata: String? = null,
)

@Serializable
data class JfUserData(
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
)

/** `GET /Playlists/{id}`: the song IDs in order (duplicates possible). */
@Serializable
data class JfPlaylist(
    @SerialName("ItemIds") val itemIds: List<String> = emptyList(),
)

@Serializable
data class JfPlaylistPermissions(
    @SerialName("CanEdit") val canEdit: Boolean = false,
)

@Serializable
data class JfCreatePlaylist(
    @SerialName("Name") val name: String,
    @SerialName("Ids") val ids: List<String>,
    @SerialName("UserId") val userId: String,
    @SerialName("MediaType") val mediaType: String = "Audio",
    @SerialName("IsPublic") val isPublic: Boolean = false,
)

/** Replaces name and songs at once (Jellyfin 10.9+). */
@Serializable
data class JfUpdatePlaylist(
    @SerialName("Name") val name: String,
    @SerialName("Ids") val ids: List<String>,
)

/** `Sessions/Playing` and `Sessions/Playing/Stopped`; [positionTicks] in 100 ns. Emby requires [playSessionId]. */
@Serializable
data class JfPlaybackReport(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("PlaySessionId") val playSessionId: String,
    @SerialName("PlayMethod") val playMethod: String = "DirectPlay",
)

@Serializable
data class JfCreated(
    @SerialName("Id") val id: String,
)

@Serializable
data class JfLyrics(
    @SerialName("Metadata") val metadata: JfLyricMetadata? = null,
    @SerialName("Lyrics") val lyrics: List<JfLyricLine> = emptyList(),
)

@Serializable
data class JfLyricMetadata(
    @SerialName("IsSynced") val isSynced: Boolean? = null,
    /** Ticks (100 ns) by which the text is shifted relative to the audio (like `[offset:]` in LRC, positive = earlier). */
    @SerialName("Offset") val offset: Long? = null,
)

@Serializable
data class JfLyricLine(
    @SerialName("Text") val text: String = "",
    /** Ticks (100 ns) from the start of the song; missing for unsynced lyrics. */
    @SerialName("Start") val start: Long? = null,
)
