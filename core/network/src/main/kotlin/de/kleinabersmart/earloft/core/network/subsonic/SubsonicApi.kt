package de.kleinabersmart.earloft.core.network.subsonic

import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.network.HttpStatusException
import de.kleinabersmart.earloft.core.network.await
import de.kleinabersmart.earloft.core.network.subsonic.dto.AlbumDto
import de.kleinabersmart.earloft.core.network.subsonic.dto.OpenSubsonicExtensionDto
import de.kleinabersmart.earloft.core.network.subsonic.dto.PlaylistDto
import de.kleinabersmart.earloft.core.network.subsonic.dto.SongDto
import de.kleinabersmart.earloft.core.network.subsonic.dto.Starred2Dto
import de.kleinabersmart.earloft.core.network.subsonic.dto.StructuredLyricsDto
import de.kleinabersmart.earloft.core.network.subsonic.dto.SubsonicEnvelope
import de.kleinabersmart.earloft.core.network.subsonic.dto.SubsonicResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** The Subsonic REST calls the app needs. Auth is handled by [SubsonicAuthInterceptor]. */
@Singleton
class SubsonicApi @Inject constructor(
    private val client: OkHttpClient,
) {
    suspend fun ping(server: SubsonicServer): SubsonicResponse = get(server, "ping")

    /** Empty list for servers without OpenSubsonic support. */
    suspend fun openSubsonicExtensions(server: SubsonicServer): List<OpenSubsonicExtensionDto> =
        try {
            get(server, "getOpenSubsonicExtensions").openSubsonicExtensions.orEmpty()
        } catch (e: SubsonicException) {
            if (e.isAuthError) throw e
            emptyList()
        }

    /** Navidrome returns the whole library for an empty search (concept 6.3). */
    suspend fun searchAllSongs(server: SubsonicServer, offset: Int, count: Int): List<SongDto> =
        get(server, "search3") {
            addQueryParameter("query", "")
            addQueryParameter("songCount", count.toString())
            addQueryParameter("songOffset", offset.toString())
            addQueryParameter("albumCount", "0")
            addQueryParameter("artistCount", "0")
        }.searchResult3?.song.orEmpty()

    suspend fun albumList(server: SubsonicServer, type: String, offset: Int, size: Int): List<AlbumDto> =
        get(server, "getAlbumList2") {
            addQueryParameter("type", type)
            addQueryParameter("offset", offset.toString())
            addQueryParameter("size", size.toString())
        }.albumList2?.album.orEmpty()

    suspend fun album(server: SubsonicServer, albumId: String): AlbumDto =
        get(server, "getAlbum") { addQueryParameter("id", albumId) }.album
            ?: throw NotSubsonicException("getAlbum ohne album")

    /** All favorited songs of the account. */
    /** Favorited songs, albums and artists. */
    suspend fun starred(server: SubsonicServer): Starred2Dto =
        get(server, "getStarred2").starred2 ?: Starred2Dto()

    /**
     * Up to [count] songs that match the song, album or artist [id] (`getSimilarSongs`). Navidrome needs an
     * external agent for this (Last.fm); without it, and for servers that don't know the call, empty.
     */
    suspend fun similarSongs(server: SubsonicServer, id: String, count: Int): List<SongDto> =
        try {
            get(server, "getSimilarSongs") {
                addQueryParameter("id", id)
                addQueryParameter("count", count.toString())
            }.similarSongs?.song.orEmpty()
        } catch (e: SubsonicException) {
            if (e.isAuthError) throw e
            emptyList()
        }

    suspend fun star(server: SubsonicServer, id: String, kind: FavoriteKind = FavoriteKind.SONG) {
        get(server, "star") { addQueryParameter(kind.starParameter, id) }
    }

    suspend fun unstar(server: SubsonicServer, id: String, kind: FavoriteKind = FavoriteKind.SONG) {
        get(server, "unstar") { addQueryParameter(kind.starParameter, id) }
    }

    private val FavoriteKind.starParameter: String
        get() = when (this) {
            FavoriteKind.SONG -> "id"
            FavoriteKind.ALBUM -> "albumId"
            FavoriteKind.ARTIST -> "artistId"
        }

    /** Reports a play; [playedAt] is when listening started (ms since epoch). */
    suspend fun scrobble(server: SubsonicServer, songId: String, playedAt: Long) {
        get(server, "scrobble") {
            addQueryParameter("id", songId)
            addQueryParameter("time", playedAt.toString())
            addQueryParameter("submission", "true")
        }
    }

    /** Playlists of the account without songs (own ones and public ones of other users). */
    suspend fun playlists(server: SubsonicServer): List<PlaylistDto> =
        get(server, "getPlaylists").playlists?.playlist.orEmpty()

    suspend fun playlist(server: SubsonicServer, playlistId: String): PlaylistDto =
        get(server, "getPlaylist") { addQueryParameter("id", playlistId) }.playlist
            ?: throw NotSubsonicException("getPlaylist ohne playlist")

    /** @return the ID of the new playlist (Subsonic ≥ 1.14 returns the playlist). */
    suspend fun createPlaylist(server: SubsonicServer, name: String, songIds: List<String>): String =
        send(server, "createPlaylist", listOf("name" to name) + songIds.map { "songId" to it }).playlist?.id
            ?: throw NotSubsonicException("createPlaylist ohne playlist")

    /** First removes the entries at [removeIndices] (relative to the previous state), then appends [addSongIds]. */
    suspend fun updatePlaylist(
        server: SubsonicServer,
        playlistId: String,
        name: String?,
        removeIndices: List<Int>,
        addSongIds: List<String>,
    ) {
        send(
            server,
            "updatePlaylist",
            buildList {
                add("playlistId" to playlistId)
                if (name != null) add("name" to name)
                removeIndices.forEach { add("songIndexToRemove" to it.toString()) }
                addSongIds.forEach { add("songIdToAdd" to it) }
            },
        )
    }

    suspend fun deletePlaylist(server: SubsonicServer, playlistId: String) {
        get(server, "deletePlaylist") { addQueryParameter("id", playlistId) }
    }

    /** OpenSubsonic `getLyricsBySongId`; empty if the server doesn't know the call. */
    suspend fun lyricsBySongId(server: SubsonicServer, songId: String): List<StructuredLyricsDto> =
        try {
            get(server, "getLyricsBySongId") { addQueryParameter("id", songId) }.lyricsList?.structuredLyrics.orEmpty()
        } catch (e: SubsonicException) {
            if (e.isAuthError) throw e
            emptyList()
        }

    /** Classic `getLyrics` (plain text), `null` if nothing was found. */
    suspend fun lyrics(server: SubsonicServer, artist: String, title: String): String? =
        get(server, "getLyrics") {
            addQueryParameter("artist", artist)
            addQueryParameter("title", title)
        }.lyrics?.value?.takeIf { it.isNotBlank() }

    /** Original file without transcoding (gapless, fidelity). */
    fun streamUrl(server: SubsonicServer, songId: String): HttpUrl =
        server.endpoint("stream")
            .addQueryParameter("id", songId)
            .addQueryParameter("format", "raw")
            .build()

    /** Transcoded by the server, e.g. for receivers that don't know the original format. */
    fun transcodedStreamUrl(server: SubsonicServer, songId: String, format: String, maxBitRateKbps: Int): HttpUrl =
        server.endpoint("stream")
            .addQueryParameter("id", songId)
            .addQueryParameter("format", format)
            .addQueryParameter("maxBitRate", maxBitRateKbps.toString())
            .build()

    fun coverArtUrl(server: SubsonicServer, coverArtId: String, sizePx: Int): HttpUrl =
        server.endpoint("getCoverArt")
            .addQueryParameter("id", coverArtId)
            .addQueryParameter("size", sizePx.toString())
            .build()

    /** `null` if the server has no cover. */
    suspend fun coverArt(server: SubsonicServer, coverArtId: String, sizePx: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            client.newCall(request(server, coverArtUrl(server, coverArtId, sizePx))).await().use { response ->
                when {
                    response.code == 404 -> null
                    !response.isSuccessful -> throw HttpStatusException(response.code)
                    response.header("Content-Type")?.startsWith("image/") != true -> null
                    else -> response.body.bytes()
                }
            }
        }

    private suspend fun get(
        server: SubsonicServer,
        endpoint: String,
        params: HttpUrl.Builder.() -> Unit = {},
    ): SubsonicResponse = execute(endpoint, request(server, server.endpoint(endpoint).apply(params).build()))

    /**
     * For calls with many parameters: as a form POST if the server supports `formPost`, otherwise as GET.
     * Callers must split long lists themselves without `formPost`.
     */
    private suspend fun send(server: SubsonicServer, endpoint: String, params: List<Pair<String, String>>): SubsonicResponse {
        if (!server.supportsFormPost) {
            return get(server, endpoint) { params.forEach { (name, value) -> addQueryParameter(name, value) } }
        }
        val body = FormBody.Builder().apply { params.forEach { (name, value) -> add(name, value) } }.build()
        val request = request(server, server.endpoint(endpoint).build()).newBuilder().post(body).build()
        return execute(endpoint, request)
    }

    private suspend fun execute(endpoint: String, request: Request): SubsonicResponse = withContext(Dispatchers.IO) {
        client.newCall(request).await().use { response ->
            val body = response.body.string()
            val envelope = try {
                json.decodeFromString<SubsonicEnvelope>(body)
            } catch (e: SerializationException) {
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                throw NotSubsonicException("Keine Subsonic-Antwort von $endpoint", e)
            } catch (e: IllegalArgumentException) {
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                throw NotSubsonicException("Keine Subsonic-Antwort von $endpoint", e)
            }
            val result = envelope.response
            if (result.status != "ok") {
                throw SubsonicException(result.error?.code ?: SubsonicException.GENERIC, result.error?.message)
            }
            result
        }
    }

    private fun request(server: SubsonicServer, url: HttpUrl): Request =
        Request.Builder()
            .url(url)
            .tag(SubsonicServer::class.java, server)
            .build()

    internal companion object {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
