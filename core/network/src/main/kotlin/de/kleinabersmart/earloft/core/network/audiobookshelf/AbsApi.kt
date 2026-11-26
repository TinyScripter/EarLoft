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

/** Error of the Audiobookshelf API; [code] is the HTTP status. */
class AbsException(val code: Int, message: String) : IOException(message) {
    val isAuthError: Boolean get() = code == 401 || code == 403
}

/**
 * The Audiobookshelf REST calls the app needs. The API key is included here in every request; for streams and
 * covers that don't come from here, [AbsAuthInterceptor] adds it.
 */
@Singleton
class AbsApi @Inject constructor(
    private val client: OkHttpClient,
) {
    /** Reachable and an Audiobookshelf? (without login) */
    suspend fun ping(server: AbsServer) {
        val body = execute(Request.Builder().url(server.endpoint("ping").build()).build())
        if (!body.contains("\"success\"")) throw AbsException(0, "Not an Audiobookshelf server")
    }

    /** Checks the API key and returns the account's progress. */
    suspend fun me(server: AbsServer): AbsMeResponse = decode(execute(authorized(server, server.endpoint("api/me").build())))

    /** The account's listening time as the server counts it from the sessions; unchanged as text so it can be cached. */
    suspend fun listeningStats(server: AbsServer): String =
        execute(authorized(server, server.endpoint("api/me/listening-stats").build())).also { AbsStats.parse(it) }

    /**
     * Reports (or updates) a listening session; the server also takes over the position. It answers with the
     * text `OK` (not JSON): the HTTP status is what matters.
     */
    suspend fun syncLocalSession(server: AbsServer, session: AbsLocalSession) {
        val body = JSON.encodeToString(AbsLocalSession.serializer(), session).toRequestBody(JSON_MEDIA_TYPE)
        execute(authorized(server, server.endpoint("api/session/local").build()) { post(body) })
    }

    suspend fun libraries(server: AbsServer): List<AbsLibraryDto> =
        decode<AbsLibrariesResponse>(execute(authorized(server, server.endpoint("api/libraries").build()))).libraries

    /**
     * One page of the entries, sorted by `addedAt`: the order is fixed, new ones are appended at the end. The server computes
     * the start as `page * limit`; with `limit = 1`, `page` is therefore exactly the position (checked on 2.37.2).
     */
    suspend fun libraryItems(server: AbsServer, libraryId: String, page: Int, limit: Int): AbsItemsPage {
        val url = server.endpoint("api/libraries/$libraryId/items")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "addedAt")
            .addQueryParameter("minified", "1")
            .build()
        return decode(execute(authorized(server, url)))
    }

    /** The [limit] entries added last, newest first; to see whether an upload has been scanned without a whole sync. */
    suspend fun newestItems(server: AbsServer, libraryId: String, limit: Int): List<AbsItemDto> {
        val url = server.endpoint("api/libraries/$libraryId/items")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("page", "0")
            .addQueryParameter("sort", "addedAt")
            .addQueryParameter("desc", "1")
            .addQueryParameter("minified", "1")
            .build()
        return decode<AbsItemsPage>(execute(authorized(server, url))).results
    }

    /** Single entry with chapters and files. */
    suspend fun item(server: AbsServer, itemId: String): AbsItemDto {
        val url = server.endpoint("api/items/$itemId").addQueryParameter("expanded", "1").build()
        return decode(execute(authorized(server, url)))
    }

    /** `null` if the server doesn't know any progress for the entry yet. */
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

    /** Remove progress on the server; [progressId] is the ID of the progress entry, not that of the book. */
    suspend fun deleteProgress(server: AbsServer, progressId: String) {
        execute(authorized(server, server.endpoint("api/me/progress/$progressId").build()) { delete() })
    }

    /** Creates a bookmark; Audiobookshelf 2.x answers with the bookmark. */
    suspend fun createBookmark(server: AbsServer, itemId: String, bookmark: AbsBookmarkUpdate) {
        val body = JSON.encodeToString(AbsBookmarkUpdate.serializer(), bookmark).toRequestBody(JSON_MEDIA_TYPE)
        execute(authorized(server, server.endpoint("api/me/item/$itemId/bookmark").build()) { post(body) })
    }

    /** Changes the title of the bookmark at [AbsBookmarkUpdate.time]; 404 if there is none there. */
    suspend fun updateBookmark(server: AbsServer, itemId: String, bookmark: AbsBookmarkUpdate) {
        val body = JSON.encodeToString(AbsBookmarkUpdate.serializer(), bookmark).toRequestBody(JSON_MEDIA_TYPE)
        execute(authorized(server, server.endpoint("api/me/item/$itemId/bookmark").build()) { patch(body) })
    }

    /** Removes the bookmark at [time]; already missing ones (404) count as removed. */
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

    /** Cover bytes (image); `null` if the entry has none. */
    suspend fun cover(server: AbsServer, itemId: String, width: Int): ByteArray? = withContext(Dispatchers.IO) {
        client.newCall(authorized(server, coverUrl(server, itemId, width))).await().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) throw AbsException(response.code, "HTTP ${response.code}")
            response.body.bytes()
        }
    }

    /** Authors of the library (with image path, if the server knows an image). */
    suspend fun authors(server: AbsServer, libraryId: String): List<AbsAuthorDto> =
        decode<AbsAuthorsResponse>(execute(authorized(server, server.endpoint("api/libraries/$libraryId/authors").build()))).authors

    /** Author image (bytes); `null` if the author has none. */
    suspend fun authorImage(server: AbsServer, authorId: String, width: Int): ByteArray? = withContext(Dispatchers.IO) {
        val url = server.endpoint("api/authors/$authorId/image").addQueryParameter("width", width.toString()).build()
        client.newCall(authorized(server, url)).await().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) throw AbsException(response.code, "HTTP ${response.code}")
            response.body.bytes()
        }
    }

    /**
     * Uploads a book (`POST /api/upload`): the server creates `<folder>/<author>/<title>/` with the files and adds it to the
     * library after scanning. Requires the "Upload" permission. An existing target folder is [AbsUploadExists].
     * [onProgress] receives the bytes sent and the total size.
     */
    suspend fun uploadBook(server: AbsServer, upload: AbsUpload, onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }) {
        val total = upload.files.sumOf { it.file.length() }
        var sent = 0L
        val parts = MultipartBody.Builder().setType(MultipartBody.FORM)
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
        // OkHttp writes the body once more when it repeats the request (renewed token after a 401): the bytes count per attempt,
        // otherwise the progress went beyond 100 % and an aborted second attempt counted as "everything sent" (review4 T28).
        val body = object : RequestBody() {
            override fun contentType(): MediaType = parts.contentType()
            override fun contentLength(): Long = parts.contentLength()
            override fun writeTo(sink: BufferedSink) {
                sent = 0
                onProgress(0, total)
                parts.writeTo(sink)
            }
        }
        // Large files over a slow network: limit neither the total nor the write time as long as data is flowing. The server
        // answers only after moving the files into the library (in Docker often a copy): 30 s were too short for a large book
        // (review H15).
        val patient = client.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(UPLOAD_ANSWER_MINUTES, TimeUnit.MINUTES)
            .build()
        val request = authorized(server, server.endpoint("api/upload").build()) { post(body) }
        withContext(Dispatchers.IO) {
            try {
                patient.newCall(request).await().use { response ->
                    if (response.isSuccessful) return@use
                    val text = response.body.string()
                    // The server reports an existing folder as 500 with a hint in the text.
                    if (response.code == 500 && text.contains("exist", ignoreCase = true)) throw AbsUploadExists()
                    throw AbsException(response.code, "HTTP ${response.code}: ${text.take(200)}")
                }
            } catch (e: IOException) {
                // Everything was sent, only the answer is missing: the book is most likely on the server.
                if (e is AbsException || e is AbsUploadExists || total == 0L || sent < total) throw e
                throw AbsUploadUnconfirmed(e)
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
            throw AbsException(0, "Unexpected response: ${e.message}")
        }

    private companion object {
        /** How long the server may take to answer an upload after receiving it. */
        const val UPLOAD_ANSWER_MINUTES = 15L

        val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/** The book already exists on the server (the target folder exists). */
class AbsUploadExists : IOException("The book already exists on the server")

/** All files were sent, but the server's answer did not arrive (timeout, connection lost): the book most likely arrived. */
class AbsUploadUnconfirmed(cause: IOException) : IOException("Upload sent, no answer from the server", cause)

/** A book to upload: [title] and [author] determine the folder, [files] the files in this order. */
data class AbsUpload(
    val libraryId: String,
    val folderId: String,
    val title: String,
    val author: String,
    val series: String? = null,
    val files: List<AbsUploadFile>,
)

/** One file of the upload under the name [name] on the server. */
data class AbsUploadFile(val file: File, val name: String, val mimeType: String)

/** Reads [file] and reports every chunk sent, so an upload can show its progress. */
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

/** Time of a bookmark in the path: whole seconds without ".0", otherwise as a decimal (the server compares numbers). */
internal fun bookmarkTime(time: Double): String =
    if (time % 1.0 == 0.0) time.toLong().toString() else java.math.BigDecimal.valueOf(time).toPlainString()
