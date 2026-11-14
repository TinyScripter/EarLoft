package de.kleinabersmart.earloft.core.network.audiobookshelf

import de.kleinabersmart.earloft.core.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.source
import java.io.File
import java.util.concurrent.TimeUnit
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

    /** Legt ein Lesezeichen an; Audiobookshelf 2.x antwortet mit dem Lesezeichen. */
    suspend fun createBookmark(server: AbsServer, itemId: String, bookmark: AbsBookmarkUpdate) {
        val body = JSON.encodeToString(AbsBookmarkUpdate.serializer(), bookmark).toRequestBody(JSON_MEDIA_TYPE)
        execute(authorized(server, server.endpoint("api/me/item/$itemId/bookmark").build()) { post(body) })
    }

    /** Ändert den Titel des Lesezeichens bei [AbsBookmarkUpdate.time]; 404, wenn es dort keines gibt. */
    suspend fun updateBookmark(server: AbsServer, itemId: String, bookmark: AbsBookmarkUpdate) {
        val body = JSON.encodeToString(AbsBookmarkUpdate.serializer(), bookmark).toRequestBody(JSON_MEDIA_TYPE)
        execute(authorized(server, server.endpoint("api/me/item/$itemId/bookmark").build()) { patch(body) })
    }

    /** Entfernt das Lesezeichen bei [time]; schon fehlende (404) gelten als entfernt. */
    suspend fun deleteBookmark(server: AbsServer, itemId: String, time: Double) {
        try {
            execute(authorized(server, server.endpoint("api/me/item/$itemId/bookmark/${bookmarkTime(time)}").build()) { delete() })
        } catch (e: AbsException) {
            if (e.code != 404) throw e
        }
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

    /**
     * Lädt ein Buch hoch (`POST /api/upload`): Der Server legt `<Ordner>/<Autor>/<Titel>/` mit den Dateien an und nimmt es nach
     * dem Scannen in die Bibliothek auf. Braucht das Recht „Hochladen“. Ein vorhandener Zielordner ist [AbsUploadExists].
     * [onProgress] bekommt die gesendeten Bytes und die Gesamtgröße.
     */
    suspend fun uploadBook(server: AbsServer, upload: AbsUpload, onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }) {
        val total = upload.files.sumOf { it.file.length() }
        var sent = 0L
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("title", upload.title)
            .addFormDataPart("author", upload.author)
            .apply { upload.series?.let { addFormDataPart("series", it) } }
            .addFormDataPart("library", upload.libraryId)
            .addFormDataPart("folder", upload.folderId)
            .apply {
                upload.files.forEachIndexed { index, part ->
                    addFormDataPart(index.toString(), part.name, ProgressBody(part.file, part.mimeType.toMediaType()) { bytes ->
                        sent += bytes
                        onProgress(sent, total)
                    })
                }
            }
            .build()
        // Große Dateien über ein langsames Netz: weder Gesamt- noch Schreibzeit begrenzen, solange Daten fließen.
        val patient = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
        val request = authorized(server, server.endpoint("api/upload").build()) { post(body) }
        withContext(Dispatchers.IO) {
            patient.newCall(request).await().use { response ->
                if (response.isSuccessful) return@use
                val text = response.body.string()
                // Der Server meldet einen vorhandenen Ordner als 500 mit Hinweis im Text.
                if (response.code == 500 && text.contains("exist", ignoreCase = true)) throw AbsUploadExists()
                throw AbsException(response.code, "HTTP ${response.code}: ${text.take(200)}")
            }
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

/** Das Buch ist auf dem Server schon vorhanden (der Zielordner besteht). */
class AbsUploadExists : IOException("Das Buch gibt es auf dem Server schon")

/** Ein Buch zum Hochladen: [title] und [author] bestimmen den Ordner, [files] die Dateien in dieser Reihenfolge. */
data class AbsUpload(
    val libraryId: String,
    val folderId: String,
    val title: String,
    val author: String,
    val series: String? = null,
    val files: List<AbsUploadFile>,
)

/** Eine Datei des Uploads unter dem Namen [name] auf dem Server. */
data class AbsUploadFile(val file: File, val name: String, val mimeType: String)

/** Liest [file] und meldet jedes gesendete Stück, damit ein Upload seinen Fortschritt zeigen kann. */
private class ProgressBody(private val file: File, private val type: MediaType, private val onBytes: (Long) -> Unit) : RequestBody() {
    override fun contentType(): MediaType = type
    override fun contentLength(): Long = file.length()
    override fun writeTo(sink: BufferedSink) {
        file.source().use { source ->
            val buffer = Buffer()
            while (true) {
                val read = source.read(buffer, CHUNK_BYTES)
                if (read == -1L) break
                sink.write(buffer, read)
                onBytes(read)
            }
        }
    }

    private companion object {
        const val CHUNK_BYTES = 64L * 1024
    }
}

/** Zeit eines Lesezeichens im Pfad: ganze Sekunden ohne „.0“, sonst als Dezimalzahl (der Server vergleicht Zahlen). */
internal fun bookmarkTime(time: Double): String =
    if (time % 1.0 == 0.0) time.toLong().toString() else java.math.BigDecimal.valueOf(time).toPlainString()
