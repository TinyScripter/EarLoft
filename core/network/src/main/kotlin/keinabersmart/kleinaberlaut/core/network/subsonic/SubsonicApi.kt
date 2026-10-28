package keinabersmart.kleinaberlaut.core.network.subsonic

import keinabersmart.kleinaberlaut.core.network.await
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.AlbumDto
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.OpenSubsonicExtensionDto
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.PlaylistDto
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.SongDto
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.StructuredLyricsDto
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.SubsonicEnvelope
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.SubsonicResponse
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

/** Die Subsonic-REST-Aufrufe, die die App braucht. Auth übernimmt [SubsonicAuthInterceptor]. */
@Singleton
class SubsonicApi @Inject constructor(
    private val client: OkHttpClient,
) {
    suspend fun ping(server: SubsonicServer): SubsonicResponse = get(server, "ping")

    /** Leere Liste bei Servern ohne OpenSubsonic-Unterstützung. */
    suspend fun openSubsonicExtensions(server: SubsonicServer): List<OpenSubsonicExtensionDto> =
        try {
            get(server, "getOpenSubsonicExtensions").openSubsonicExtensions.orEmpty()
        } catch (e: SubsonicException) {
            if (e.isAuthError) throw e
            emptyList()
        }

    /** Navidrome liefert bei leerer Suche die gesamte Bibliothek (Konzept 6.3). */
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

    /** Alle favorisierten Songs des Kontos. */
    suspend fun starredSongs(server: SubsonicServer): List<SongDto> =
        get(server, "getStarred2").starred2?.song.orEmpty()

    suspend fun star(server: SubsonicServer, songId: String) {
        get(server, "star") { addQueryParameter("id", songId) }
    }

    suspend fun unstar(server: SubsonicServer, songId: String) {
        get(server, "unstar") { addQueryParameter("id", songId) }
    }

    /** Meldet eine Wiedergabe; [playedAt] ist der Beginn des Hörens (ms seit Epoche). */
    suspend fun scrobble(server: SubsonicServer, songId: String, playedAt: Long) {
        get(server, "scrobble") {
            addQueryParameter("id", songId)
            addQueryParameter("time", playedAt.toString())
            addQueryParameter("submission", "true")
        }
    }

    /** Playlists des Kontos ohne Songs (eigene und öffentliche anderer Nutzer). */
    suspend fun playlists(server: SubsonicServer): List<PlaylistDto> =
        get(server, "getPlaylists").playlists?.playlist.orEmpty()

    suspend fun playlist(server: SubsonicServer, playlistId: String): PlaylistDto =
        get(server, "getPlaylist") { addQueryParameter("id", playlistId) }.playlist
            ?: throw NotSubsonicException("getPlaylist ohne playlist")

    /** @return die ID der neuen Playlist (Subsonic ≥ 1.14 liefert die Playlist zurück). */
    suspend fun createPlaylist(server: SubsonicServer, name: String, songIds: List<String>): String =
        send(server, "createPlaylist", listOf("name" to name) + songIds.map { "songId" to it }).playlist?.id
            ?: throw NotSubsonicException("createPlaylist ohne playlist")

    /** Entfernt erst die Einträge an [removeIndices] (bezogen auf den Stand vorher), hängt dann [addSongIds] an. */
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

    /** OpenSubsonic `getLyricsBySongId`; leer, wenn der Server den Aufruf nicht kennt. */
    suspend fun lyricsBySongId(server: SubsonicServer, songId: String): List<StructuredLyricsDto> =
        try {
            get(server, "getLyricsBySongId") { addQueryParameter("id", songId) }.lyricsList?.structuredLyrics.orEmpty()
        } catch (e: SubsonicException) {
            if (e.isAuthError) throw e
            emptyList()
        }

    /** Klassisches `getLyrics` (reiner Text), `null` wenn nichts gefunden wurde. */
    suspend fun lyrics(server: SubsonicServer, artist: String, title: String): String? =
        get(server, "getLyrics") {
            addQueryParameter("artist", artist)
            addQueryParameter("title", title)
        }.lyrics?.value?.takeIf { it.isNotBlank() }

    /** Original-Datei ohne Transcoding (Gapless, Klangtreue). */
    fun streamUrl(server: SubsonicServer, songId: String): HttpUrl =
        server.endpoint("stream")
            .addQueryParameter("id", songId)
            .addQueryParameter("format", "raw")
            .build()

    /** Vom Server umgewandelt, z. B. für Empfänger, die das Originalformat nicht kennen. */
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

    /** `null`, wenn der Server kein Cover hat. */
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
     * Für Aufrufe mit vielen Parametern: als Formular-POST, wenn der Server `formPost` kann, sonst als GET.
     * Aufrufer müssen lange Listen ohne `formPost` selbst aufteilen.
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
            .tag(SubsonicCredentials::class.java, server.credentials)
            .build()

    internal companion object {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
