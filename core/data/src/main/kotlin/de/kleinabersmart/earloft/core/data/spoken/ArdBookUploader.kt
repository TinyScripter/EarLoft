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

/** Wohin ein Buch hochgeladen werden kann: Bibliothek und Ordner eines Audiobookshelf-Servers, in denen das Konto hochladen darf. */
data class UploadTarget(
    val serverId: String,
    val serverName: String,
    val libraryId: String,
    val libraryName: String,
    val folderId: String,
    /** Pfad des Ordners auf dem Server; nur gesetzt, wenn die Bibliothek mehrere hat (zur Unterscheidung). */
    val folderPath: String? = null,
)

sealed interface UploadResult {
    /**
     * Hochgeladen. [newBookId] ist das Buch auf dem Server in dieser App (Fortschritt, Lesezeichen und Tempo sind dorthin
     * übernommen); `null`, wenn der Server es noch nicht gemeldet hat – es erscheint dann bei einem späteren Abgleich, der den
     * Stand übernimmt ([ArdUploadAdoption]).
     */
    data class Done(val newBookId: Long?) : UploadResult

    /** Nicht alle Dateien des Buchs liegen auf dem Gerät. */
    data object NotDownloaded : UploadResult

    /** Das Buch gibt es auf dem Server schon (der Ordner Autor/Titel besteht). */
    data object Exists : UploadResult

    /** Das Konto darf nicht hochladen (mehr). */
    data object NoPermission : UploadResult

    /**
     * Der Server hat mit einem Serverfehler (HTTP 5xx) geantwortet. Meist kann Audiobookshelf den Ordner nicht anlegen, weil der
     * Bibliotheksordner für den Server schreibgeschützt ist: Er meldet das nur als „Internal Server Error“.
     */
    data object ServerError : UploadResult

    data object Failed : UploadResult
}

/**
 * Lädt ein Buch aus ARD Sounds zu Audiobookshelf hoch (`books_improvements.md` M9, Ausnahme von „Serververwaltung bewusst nicht“)
 * und übernimmt danach, was der Nutzer am Buch angelegt hat (Fortschritt, Tempo und Effekte, Lesezeichen; [ArdUploadAdoption]). Das Paket sind die
 * geladenen Teile als `01 - Teil.mp3`, dazu `cover.jpg`, `metadata.opf` (Titel, Autor, Sprecher, Beschreibung, Sender, Genres),
 * `desc.txt` (Beschreibung) und `reader.txt` (Sprecher), die Audiobookshelf beim Scannen liest. Die OPF-Datei braucht es, weil die
 * Tags der Dateien sonst Titel und Autor setzen (etwa „Autorin: Titel (1/9)“ vom Sender): In der Standard-Rangfolge von
 * Audiobookshelf schlagen sie den Ordnernamen, die OPF-Datei schlägt sie.
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
    /** Gibt es einen eingeschalteten Server? (Der Knopf „In Audiobookshelf hochladen“ erscheint nur dann.) */
    suspend fun hasServer(): Boolean = servers.all().isNotEmpty()

    /**
     * Die Ziele, in die das Konto hochladen darf: alle gezeigten Buch-Bibliotheken der eingeschalteten Server mit ihren Ordnern.
     * Nicht erreichbare Server und Konten ohne das Recht fehlen.
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
            Log.w(TAG, "Ziele auf ${server.baseUrl} nicht abrufbar", e)
            emptyList()
        }
    }

    /** Wie viele Bytes der Upload von [bookId] umfasst (die Dateien des Buchs); 0, wenn nicht alle geladen sind. */
    suspend fun uploadBytes(bookId: Long): Long {
        val files = downloadedFiles(bookId) ?: return 0
        return files.sumOf { it.second.length() }
    }

    /** Wie viele Teile von [bookId] fehlen werden: nicht geladen und bei ARD Sounds nicht mehr abrufbar. */
    suspend fun missingParts(bookId: Long): Int = dao.tracks(bookId).count { it.isUnavailable }

    /**
     * Lädt [bookId] nach [target] hoch; [onProgress] bekommt gesendete Bytes und Gesamtgröße. Danach wird gewartet (bis etwa eine
     * Minute), dass der Server das Buch meldet, und der Stand übernommen; bricht der Aufrufer das Warten ab oder meldet der Server
     * nichts, holt das ein späterer Abgleich nach. Das Buch aus ARD Sounds bleibt unberührt: Entfernen und Wechseln der Seite
     * entscheidet der Aufrufer (es kann gerade im Player liegen).
     */
    suspend fun upload(bookId: Long, target: UploadTarget, onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }): UploadResult {
        val book = dao.book(bookId)?.takeIf { isArdSoundsBook(it.serverId) } ?: return UploadResult.Failed
        val files = downloadedFiles(bookId) ?: return UploadResult.NotDownloaded
        val server = servers.server(target.serverId) ?: return UploadResult.Failed
        // Der lokale Stand kann alt sein: Was andere inzwischen hochgeladen haben, darf danach nicht für dieses Buch gehalten werden.
        syncer.syncSafely(server)
        val onServer = dao.booksOf(target.serverId)
        // Neuere Versionen von Audiobookshelf prüfen nicht, ob der Ordner schon besteht, und mischten die Dateien: vorher selbst prüfen.
        if (onServer.any { it.libraryId == target.libraryId && it.normTitle == book.normTitle && sameAuthor(it.author, book.author) }) {
            return UploadResult.Exists
        }
        val work = File(context.cacheDir, "upload-$bookId").apply { deleteRecursively(); mkdirs() }
        try {
            val parts = withContext(Dispatchers.IO) { packageFor(book, files, work) }
            try {
                api.uploadBook(
                    server,
                    AbsUpload(target.libraryId, target.folderId, book.title, book.author.ifBlank { UNKNOWN_AUTHOR }, files = parts),
                    onProgress,
                )
            } catch (e: AbsUploadExists) {
                return UploadResult.Exists
            } catch (e: AbsException) {
                Log.w(TAG, "Upload von ${book.title} abgelehnt", e)
                return when {
                    e.isAuthError -> UploadResult.NoPermission
                    e.code >= SERVER_ERROR -> UploadResult.ServerError
                    else -> UploadResult.Failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Upload von ${book.title} fehlgeschlagen", e)
                return UploadResult.Failed
            }
        } finally {
            work.deleteRecursively()
        }
        val sentMs = dao.tracks(bookId).filterNot { it.isUnavailable }.sumOf { it.durationMs }
        // Das Buch liegt auf dem Server: Die Übernahme darf nicht mehr verloren gehen.
        withContext(NonCancellable) { adoption.remember(book, target.serverId, target.libraryId, sentMs, onServer) }
        return UploadResult.Done(awaitOnServer(bookId, server))
    }

    /**
     * Die Dateien des Buchs in Reihenfolge mit ihrer Spur, wenn alle geladen sind, sonst `null`. Teile, die ARD Sounds nicht mehr
     * führt und die nie geladen wurden, fehlen (ohne sie ließe sich ein solches Buch nie hochladen).
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
     * Wartet, bis der Server das hochgeladene Buch meldet (er scannt es erst nach dem Upload); der Abgleich übernimmt dann den Stand.
     * Das Buch auf dem Server, `null`, wenn er es noch nicht meldet.
     */
    private suspend fun awaitOnServer(bookId: Long, server: AbsServer): Long? {
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_MS)
            syncer.syncSafely(server)
            adoption.adoptedTo(bookId)?.let { return it }
        }
        return null
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
        const val RETRY_MS = 5_000L
    }
}

/**
 * Die Angaben von [book] als OPF 2.0, wie Audiobookshelf sie aus `metadata.opf` liest. ARD Sounds führt keine Sprache; die
 * Hörbücher dort sind deutsch.
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

/** [text] für XML: Zeichen mit Bedeutung maskiert, Steuerzeichen (in XML 1.0 verboten) entfernt. */
private fun xml(text: String): String =
    text.filter { it >= ' ' || it == '\t' || it == '\n' || it == '\r' }.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/**
 * [positionMs] im Buch ohne die Teile [missing]: Was nach einem fehlenden Teil liegt, rückt um dessen Länge vor; eine Stelle in einem
 * fehlenden Teil wird zum Anfang des nächsten.
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
