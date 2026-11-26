package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.model.isArdSoundsBook
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsException
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServer
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsUpload
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsUploadExists
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsUploadUnconfirmed
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsUploadFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Where a book can be uploaded: library and folder of an Audiobookshelf server in which the account may upload. */
data class UploadTarget(
    val serverId: String,
    val serverName: String,
    val libraryId: String,
    val libraryName: String,
    val folderId: String,
    /** Path of the folder on the server; only set if the library has several (to tell them apart). */
    val folderPath: String? = null,
)

sealed interface UploadResult {
    /**
     * Uploaded. [newBookId] is the book on the server in this app (progress, bookmarks and speed have been
     * carried over); `null` if the server hasn't reported it yet – it then appears on a later sync, which carries over the
     * state ([ArdUploadAdoption]).
     */
    data class Done(val newBookId: Long?) : UploadResult

    /** Not all of the book's files are on the device. */
    data object NotDownloaded : UploadResult

    /** The book already exists on the server (the folder author/title exists). */
    data object Exists : UploadResult

    /** The account may not upload (anymore). */
    data object NoPermission : UploadResult

    /**
     * The server responded with a server error (HTTP 5xx). Usually Audiobookshelf can't create the folder because the
     * library folder is read-only for the server: it only reports this as "Internal Server Error".
     */
    data object ServerError : UploadResult

    data object Failed : UploadResult
}

/**
 * Uploads a book from ARD Sounds to Audiobookshelf (`books_improvements.md` M9, exception to "deliberately no server management")
 * and then carries over what the user created on the book (progress, speed and effects, bookmarks; [ArdUploadAdoption]). The package consists of the
 * downloaded parts as `01 - Part.mp3`, plus `cover.jpg`, `metadata.opf` (title, author, narrator, description, broadcaster, genres),
 * `desc.txt` (description) and `reader.txt` (narrator), which Audiobookshelf reads when scanning. The OPF file is needed because the
 * files' tags would otherwise set title and author (e.g. "Author: Title (1/9)" from the broadcaster): in Audiobookshelf's default precedence
 * they beat the folder name, the OPF file beats them.
 */
@Singleton
class ArdBookUploader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: BookDao,
    private val servers: AudiobookshelfServers,
    private val api: AbsApi,
    private val syncer: BookSyncer,
    private val covers: BookCovers,
    private val adoption: ArdUploadAdoption,
) {
    /** Is there an enabled server? (The "Upload to Audiobookshelf" button only appears then.) */
    suspend fun hasServer(): Boolean = servers.all().isNotEmpty()

    /**
     * The targets the account may upload to: all shown book libraries of the enabled servers with their folders.
     * Unreachable servers and accounts without the permission are missing.
     */
    suspend fun targets(): List<UploadTarget> = servers.all().flatMap { server ->
        try {
            if (!api.me(server).canUpload) return@flatMap emptyList()
            val hidden = dao.server(server.serverId)?.hiddenLibraryIds.orEmpty()
            val name = dao.server(server.serverId)?.name ?: server.baseUrl.toString()
            api.libraries(server).filter { it.mediaType == "book" && it.id !in hidden }.flatMap { library ->
                library.folders.map { folder ->
                    UploadTarget(
                        server.serverId, name, library.id, library.name, folder.id,
                        folderPath = folder.fullPath.takeIf { library.folders.size > 1 },
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Targets on ${server.baseUrl} not available", e)
            emptyList()
        }
    }

    /** How many bytes the upload of [bookId] comprises (the book's files); 0 if not all are downloaded. */
    suspend fun uploadBytes(bookId: Long): Long {
        val files = downloadedFiles(bookId) ?: return 0
        return files.sumOf { it.second.length() }
    }

    /** How many parts of [bookId] will be missing: not downloaded and no longer available at ARD Sounds. */
    suspend fun missingParts(bookId: Long): Int = dao.tracks(bookId).count { it.isUnavailable }

    /**
     * Uploads [bookId] to [target]; [onProgress] receives the bytes sent and the total size. Afterwards it waits (up to about a
     * minute) for the server to report the book, and the state is carried over; if the caller cancels the wait or the server reports
     * nothing, a later sync catches up. The book from ARD Sounds stays untouched: removing it and switching the page
     * is up to the caller (it may currently be in the player).
     */
    suspend fun upload(bookId: Long, target: UploadTarget, onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }): UploadResult {
        val book = dao.book(bookId)?.takeIf { isArdSoundsBook(it.serverId) } ?: return UploadResult.Failed
        val files = downloadedFiles(bookId) ?: return UploadResult.NotDownloaded
        val server = servers.server(target.serverId) ?: return UploadResult.Failed
        // The local state may be stale: whatever others have uploaded in the meantime must not be mistaken for this book afterwards.
        syncer.syncSafely(server)
        val onServer = dao.booksOf(target.serverId)
        // Newer versions of Audiobookshelf don't check whether the folder already exists and would mix the files: check beforehand ourselves.
        if (onServer.any { it.libraryId == target.libraryId && it.normTitle == book.normTitle && sameAuthor(it.author, book.author) }) {
            return UploadResult.Exists
        }
        val work = File(context.cacheDir, "upload-$bookId").apply { deleteRecursively(); mkdirs() }
        try {
            val parts = withContext(Dispatchers.IO) { packageFor(book, files, work) }
            try {
                api.uploadBook(
                    // The sync may have renewed the token; the copy from before would go out with the old one (review4 T28).
                    servers.server(target.serverId) ?: server,
                    AbsUpload(target.libraryId, target.folderId, book.title, book.author.ifBlank { UNKNOWN_AUTHOR }, files = parts),
                    onProgress,
                )
            } catch (e: AbsUploadExists) {
                return UploadResult.Exists
            } catch (e: AbsUploadUnconfirmed) {
                // Treated as arrived (review H15): otherwise the carry-over never happened and a new attempt reported "exists".
                // If the book does not show up after all, the remembered carry-over expires by itself.
                Log.w(TAG, "No answer after uploading ${book.title}, assuming it arrived", e)
            } catch (e: AbsException) {
                Log.w(TAG, "Upload of ${book.title} rejected", e)
                return when {
                    e.isAuthError -> UploadResult.NoPermission
                    e.code >= SERVER_ERROR -> UploadResult.ServerError
                    else -> UploadResult.Failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Upload of ${book.title} failed", e)
                return UploadResult.Failed
            }
        } finally {
            work.deleteRecursively()
        }
        val sentMs = dao.tracks(bookId).filterNot { it.isUnavailable }.sumOf { it.durationMs }
        // The book is on the server: the carry-over must not get lost anymore.
        withContext(NonCancellable) { adoption.remember(book, target.serverId, target.libraryId, sentMs, onServer) }
        return UploadResult.Done(awaitOnServer(book, server, target.libraryId))
    }

    /**
     * The book's files in order with their track if all are downloaded, otherwise `null`. Parts that ARD Sounds no longer
     * lists and that were never downloaded are missing (without that, such a book could never be uploaded).
     */
    private suspend fun downloadedFiles(bookId: Long): List<Pair<Int, File>>? {
        val tracks = dao.tracks(bookId).filterNot { it.isUnavailable }
        if (tracks.isEmpty() || tracks.any { it.downloadState != TrackDownload.DONE }) return null
        return tracks.map { track -> track.trackIndex to (track.filePath?.let(::File)?.takeIf { it.isFile } ?: return null) }
    }

    private suspend fun packageFor(book: BookEntity, audio: List<Pair<Int, File>>, work: File): List<AbsUploadFile> {
        val titles = dao.chapters(book.bookId).associate { it.chapterIndex to it.title }
        val width = audio.size.toString().length.coerceAtLeast(2)
        val parts = audio.mapIndexed { index, (trackIndex, file) ->
            val name = if (audio.size == 1) book.title else titles[trackIndex] ?: "Teil ${index + 1}"
            val numbered = if (audio.size == 1) name else "${(index + 1).toString().padStart(width, '0')} - $name"
            AbsUploadFile(file, "${safe(numbered)}.${file.extension.ifEmpty { "mp3" }}", mimeOf(file.extension))
        }
        val extras = mutableListOf<AbsUploadFile>()
        val opf = File(work, "metadata.opf").apply { writeText(opfOf(book)) }
        extras += AbsUploadFile(opf, "metadata.opf", "application/oebps-package+xml")
        covers.cover(book.bookId)?.let { bitmap ->
            val cover = File(work, "cover.jpg")
            cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, it) }
            extras += AbsUploadFile(cover, "cover.jpg", "image/jpeg")
        }
        if (book.description.isNotBlank()) {
            val file = File(work, "desc.txt").apply { writeText(book.description) }
            extras += AbsUploadFile(file, "desc.txt", "text/plain")
        }
        if (book.narrator.isNotBlank()) {
            val file = File(work, "reader.txt").apply { writeText(book.narrator) }
            extras += AbsUploadFile(file, "reader.txt", "text/plain")
        }
        return parts + extras
    }

    /**
     * Waits until the server reports the uploaded book (it only scans it after the upload); the sync then carries over the state.
     * The book on the server, `null` if it doesn't report it yet. Each attempt only looks at the newest entries of the library;
     * a whole sync only once the book is there, or in the last attempt (review2 J31: otherwise up to 12 full syncs in a minute,
     * which also hold up all other syncs).
     */
    private suspend fun awaitOnServer(book: BookEntity, server: AbsServer, libraryId: String): Long? {
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_MS)
            if (attempt < ATTEMPTS - 1 && !scanned(book, server, libraryId)) return@repeat
            syncer.syncSafely(server)
            adoption.adoptedTo(book.bookId)?.let { return it }
        }
        return null
    }

    /** Whether the server lists [book] among the entries added last to [libraryId]. */
    private suspend fun scanned(book: BookEntity, server: AbsServer, libraryId: String): Boolean = try {
        api.newestItems(server, libraryId, NEWEST_ITEMS).any { it.media.metadata.title.normalizeForMerge() == book.normTitle }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "Newest entries of $libraryId not available", e)
        false
    }

    private fun sameAuthor(a: String, b: String): Boolean = a.normalizeForMerge() == b.normalizeForMerge()

    private fun safe(name: String): String = name.replace(Regex("""[\\/:*?"<>|]"""), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "Teil" }

    private fun mimeOf(extension: String): String = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "m4b", "mp4", "aac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }

    private companion object {
        const val TAG = "ArdBookUploader"
        const val UNKNOWN_AUTHOR = "ARD"
        const val COVER_QUALITY = 90
        const val SERVER_ERROR = 500
        const val ATTEMPTS = 12
        const val NEWEST_ITEMS = 10
        const val RETRY_MS = 5_000L
    }
}

/**
 * The details of [book] as OPF 2.0, as Audiobookshelf reads them from `metadata.opf`. ARD Sounds doesn't list a language; the
 * audiobooks there are German.
 */
internal fun opfOf(book: BookEntity): String {
    val fields = buildList {
        add("<dc:title>${xml(book.title)}</dc:title>")
        if (book.author.isNotBlank()) add("<dc:creator opf:role=\"aut\">${xml(book.author)}</dc:creator>")
        if (book.narrator.isNotBlank()) add("<dc:creator opf:role=\"nrt\">${xml(book.narrator)}</dc:creator>")
        if (book.description.isNotBlank()) add("<dc:description>${xml(book.description)}</dc:description>")
        book.publisher?.takeIf { it.isNotBlank() }?.let { add("<dc:publisher>${xml(it)}</dc:publisher>") }
        book.publishedYear?.takeIf { it.isNotBlank() }?.let { add("<dc:date>${xml(it)}</dc:date>") }
        add("<dc:language>${xml(book.language?.takeIf { it.isNotBlank() } ?: "de")}</dc:language>")
        book.genres?.split(", ")?.filter { it.isNotBlank() }?.forEach { add("<dc:subject>${xml(it)}</dc:subject>") }
    }
    return buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\">\n")
        append("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:opf=\"http://www.idpf.org/2007/opf\">\n")
        fields.forEach { append("    ").append(it).append('\n') }
        append("  </metadata>\n</package>\n")
    }
}

/** [text] for XML: characters with special meaning escaped, control characters (forbidden in XML 1.0) removed. */
private fun xml(text: String): String =
    text.filter { it >= ' ' || it == '\t' || it == '\n' || it == '\r' }.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/**
 * [positionMs] in the book without the parts [missing]: whatever lies after a missing part moves forward by its length; a position in a
 * missing part becomes the start of the next one.
 */
internal fun positionWithout(positionMs: Long, missing: List<BookTrackEntity>): Long {
    val shift = missing.sumOf { track ->
        when {
            positionMs >= track.startMs + track.durationMs -> track.durationMs
            positionMs > track.startMs -> positionMs - track.startMs
            else -> 0L
        }
    }
    return (positionMs - shift).coerceAtLeast(0)
}
