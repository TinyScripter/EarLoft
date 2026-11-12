package de.kleinabersmart.earloft.core.network.audiobookshelf

import de.kleinabersmart.earloft.core.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Fehler der Audiobookshelf-API; [code] ist der HTTP-Status. */
class AbsException(val code: Int, message: String) : IOException(message) {
    val isAuthError: Boolean get() = code == 401 || code == 403
}

/**
 * Die Audiobookshelf-REST-Aufrufe, die die App braucht. Der API-Key steht hier in jeder Anfrage; für Streams und
 * Cover, die nicht von hier stammen, setzt ihn [AbsAuthInterceptor].
 */
@Singleton
class AbsApi @Inject constructor(
    private val client: OkHttpClient,
) {
    /** Erreichbar und ein Audiobookshelf? (ohne Anmeldung) */
    suspend fun ping(server: AbsServer) {
        val body = execute(Request.Builder().url(server.endpoint("ping").build()).build())
        if (!body.contains("\"success\"")) throw AbsException(0, "Kein Audiobookshelf-Server")
    }

    /** Prüft den API-Key und liefert den Fortschritt des Kontos. */
    suspend fun me(server: AbsServer): AbsMeResponse = decode(execute(authorized(server, server.endpoint("api/me").build())))

    /** Hörzeit des Kontos wie der Server sie aus den Sitzungen zählt; unverändert als Text, damit sie zwischengespeichert werden kann. */
    suspend fun listeningStats(server: AbsServer): String =
        execute(authorized(server, server.endpoint("api/me/listening-stats").build())).also { AbsStats.parse(it) }

    /**
     * Meldet (oder aktualisiert) eine Hörsitzung; der Server übernimmt dabei auch die Position. Er antwortet mit dem
     * Text `OK` (kein JSON): maßgeblich ist der HTTP-Status.
     */
    suspend fun syncLocalSession(server: AbsServer, session: AbsLocalSession) {
        val body = JSON.encodeToString(AbsLocalSession.serializer(), session).toRequestBody(JSON_MEDIA_TYPE)
        execute(authorized(server, server.endpoint("api/session/local").build()) { post(body) })
    }

    suspend fun libraries(server: AbsServer): List<AbsLibraryDto> =
        decode<AbsLibrariesResponse>(execute(authorized(server, server.endpoint("api/libraries").build()))).libraries

    suspend fun libraryItems(server: AbsServer, libraryId: String, page: Int, limit: Int): AbsItemsPage {
        val url = server.endpoint("api/libraries/$libraryId/items")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("minified", "1")
            .build()
        return decode(execute(authorized(server, url)))
    }

    /** Einzelner Eintrag mit Kapiteln und Dateien. */
    suspend fun item(server: AbsServer, itemId: String): AbsItemDto {
        val url = server.endpoint("api/items/$itemId").addQueryParameter("expanded", "1").build()
        return decode(execute(authorized(server, url)))
    }

    /** `null`, wenn der Server für den Eintrag noch keinen Fortschritt kennt. */
    suspend fun progress(server: AbsServer, itemId: String): AbsMediaProgressDto? =
        try {
            decode(execute(authorized(server, server.endpoint("api/me/progress/$itemId").build())))
        } catch (e: AbsException) {
            if (e.code == 404) null else throw e
        }

    suspend fun updateProgress(server: AbsServer, itemId: String, update: AbsProgressUpdate) {
        val body = JSON.encodeToString(AbsProgressUpdate.serializer(), update).toRequestBody(JSON_MEDIA_TYPE)
        execute(authorized(server, server.endpoint("api/me/progress/$itemId").build()) { patch(body) })
    }

    /** Fortschritt auf dem Server entfernen; [progressId] ist die ID des Fortschritts-Eintrags, nicht die des Buchs. */
    suspend fun deleteProgress(server: AbsServer, progressId: String) {
        execute(authorized(server, server.endpoint("api/me/progress/$progressId").build()) { delete() })
    }

    fun fileUrl(server: AbsServer, itemId: String, fileId: String): HttpUrl =
        server.endpoint("api/items/$itemId/file/$fileId").build()

    fun coverUrl(server: AbsServer, itemId: String, width: Int): HttpUrl =
        server.endpoint("api/items/$itemId/cover").addQueryParameter("width", width.toString()).build()

    /** Cover-Bytes (Bild); `null`, wenn der Eintrag keines hat. */
    suspend fun cover(server: AbsServer, itemId: String, width: Int): ByteArray? = withContext(Dispatchers.IO) {
        client.newCall(authorized(server, coverUrl(server, itemId, width))).await().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) throw AbsException(response.code, "HTTP ${response.code}")
            response.body.bytes()
        }
    }

    /** Autoren der Bibliothek (mit Bildpfad, falls der Server ein Bild kennt). */
    suspend fun authors(server: AbsServer, libraryId: String): List<AbsAuthorDto> =
        decode<AbsAuthorsResponse>(execute(authorized(server, server.endpoint("api/libraries/$libraryId/authors").build()))).authors

    /** Autorenbild (Bytes); `null`, wenn der Autor keines hat. */
    suspend fun authorImage(server: AbsServer, authorId: String, width: Int): ByteArray? = withContext(Dispatchers.IO) {
        val url = server.endpoint("api/authors/$authorId/image").addQueryParameter("width", width.toString()).build()
        client.newCall(authorized(server, url)).await().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) throw AbsException(response.code, "HTTP ${response.code}")
            response.body.bytes()
        }
    }

    private fun authorized(server: AbsServer, url: HttpUrl, configure: Request.Builder.() -> Request.Builder = { this }): Request =
        Request.Builder().url(url).header("Authorization", "Bearer ${server.token}").configure().build()

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).await().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw AbsException(response.code, "HTTP ${response.code}: ${text.take(200)}")
            text
        }
    }

    private inline fun <reified T> decode(text: String): T =
        try {
            JSON.decodeFromString(text)
        } catch (e: SerializationException) {
            throw AbsException(0, "Unerwartete Antwort: ${e.message}")
        }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
