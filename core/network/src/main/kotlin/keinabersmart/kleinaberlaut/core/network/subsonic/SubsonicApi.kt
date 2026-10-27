package keinabersmart.kleinaberlaut.core.network.subsonic

import keinabersmart.kleinaberlaut.core.network.await
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.AlbumDto
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.OpenSubsonicExtensionDto
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.SongDto
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.SubsonicEnvelope
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.SubsonicResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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

    /** Original-Datei ohne Transcoding (Gapless, Klangtreue). */
    fun streamUrl(server: SubsonicServer, songId: String): HttpUrl =
        server.endpoint("stream")
            .addQueryParameter("id", songId)
            .addQueryParameter("format", "raw")
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
    ): SubsonicResponse = withContext(Dispatchers.IO) {
        val url = server.endpoint(endpoint).apply(params).build()
        client.newCall(request(server, url)).await().use { response ->
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
