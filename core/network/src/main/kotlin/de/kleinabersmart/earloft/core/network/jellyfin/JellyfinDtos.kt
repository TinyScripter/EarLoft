package de.kleinabersmart.earloft.core.network.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /System/Info/Public` – ohne Anmeldung; [productName] unterscheidet Jellyfin von Emby (das keinen nennt). */
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

/** Ein Eintrag der Bibliothek (`BaseItemDto`): Song (`Audio`), Playlist oder Bibliotheksansicht. */
@Serializable
data class JfItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("MediaType") val mediaType: String? = null,
    /** Nur mit `fields=Etag`; bei Playlists ändert Jellyfin ihn mit Name und Einträgen (12.4 gemessen). */
    @SerialName("Etag") val etag: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("AlbumArtists") val albumArtists: List<JfNameId> = emptyList(),
    @SerialName("ArtistItems") val artistItems: List<JfNameId> = emptyList(),
    @SerialName("Artists") val artists: List<String> = emptyList(),
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    /** 1 Tick = 100 ns. */
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("ProviderIds") val providerIds: Map<String, String> = emptyMap(),
    /** Vom Server gemessene Lautheit (LUFS-Scan), in dB wie ReplayGain; ohne Peak. */
    @SerialName("NormalizationGain") val normalizationGain: Float? = null,
    @SerialName("AlbumNormalizationGain") val albumNormalizationGain: Float? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("MediaSources") val mediaSources: List<JfMediaSource> = emptyList(),
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("UserData") val userData: JfUserData? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    /** Nur in den Einträgen einer Emby-Playlist: die Nummer des Eintrags zum Entfernen. */
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
    /** Bit/s. */
    @SerialName("Bitrate") val bitrate: Int? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Id") val id: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<JfMediaStream> = emptyList(),
)

/** Emby führt Songtexte als Untertitel: eingebettet mit dem Text in [extradata], als `.lrc` daneben extern. */
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

/** `GET /Playlists/{id}`: die Song-IDs in Reihenfolge (Duplikate möglich). */
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

/** Ersetzt Name und Songs auf einmal (Jellyfin ab 10.9). */
@Serializable
data class JfUpdatePlaylist(
    @SerialName("Name") val name: String,
    @SerialName("Ids") val ids: List<String>,
)

/** `Sessions/Playing` und `Sessions/Playing/Stopped`; [positionTicks] in 100 ns. Emby verlangt [playSessionId]. */
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
    /** Ticks (100 ns), um die der Text gegenüber dem Ton verschoben ist (wie `[offset:]` in LRC, positiv = früher). */
    @SerialName("Offset") val offset: Long? = null,
)

@Serializable
data class JfLyricLine(
    @SerialName("Text") val text: String = "",
    /** Ticks (100 ns) ab Songbeginn; fehlt bei nicht synchronisierten Texten. */
    @SerialName("Start") val start: Long? = null,
)
