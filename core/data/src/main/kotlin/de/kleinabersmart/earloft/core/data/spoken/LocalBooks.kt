package de.kleinabersmart.earloft.core.data.spoken

import android.util.Log
import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.datasource.local.LocalBook
import de.kleinabersmart.earloft.core.datasource.local.LocalBookDetails
import de.kleinabersmart.earloft.core.datasource.local.LocalBookScanner
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.LOCAL_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.model.parseGenres
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bücher aus dem Ordner „Audiobooks“ auf dem Gerät (Konzept 12.1). Sie liegen wie die Bücher eines Servers in `books`
 * (`serverId = `[LOCAL_BOOK_SERVER_ID], `remoteId` = Pfad ab „Audiobooks/“); die Dateien stehen mit ihrer
 * `content://`-Adresse in `book_tracks.remoteFileId`. Fortschritt bleibt lokal, es gibt keinen Server dazu.
 */
@Singleton
class LocalBooks @Inject constructor(
    private val dao: BookDao,
    private val scanner: LocalBookScanner,
    private val settings: SpokenSettings,
    private val covers: BookCovers,
) {
    private val mutex = Mutex()

    /**
     * Gleicht die Bücher auf dem Gerät mit der Datenbank ab: Neues anlegen, Geändertes neu einlesen, Verschwundenes
     * löschen. Unveränderte Bücher kosten nur die MediaStore-Abfrage. `false`, wenn der Abgleich nicht möglich war
     * (dann bleibt alles, wie es ist).
     */
    suspend fun sync(): Boolean = mutex.withLock {
        if (!settings.useLocalBooks.first()) return@withLock true
        val generation = dao.nextGeneration()
        val scanned = try {
            scanner.scan { key, fingerprint ->
                dao.bookByRemote(LOCAL_BOOK_SERVER_ID, key)?.let { it.remoteUpdatedAt == fingerprint && it.detailsLoaded } == true
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Keine Berechtigung für die Bücher auf dem Gerät", e)
            return@withLock false
        } catch (e: IOException) {
            Log.w(TAG, "Bücher auf dem Gerät nicht lesbar", e)
            return@withLock false
        }
        for (book in scanned) {
            val details = book.details
            if (details == null) {
                dao.markSeen(LOCAL_BOOK_SERVER_ID, book.key, generation)
            } else {
                store(book, details, generation)
            }
        }
        // Ein abgebrochener oder fehlgeschlagener Abgleich kommt nie bis hierher und löscht deshalb nichts.
        dao.deleteUnseen(LOCAL_BOOK_SERVER_ID, generation)
        true
    }

    /** Ausgeschaltet: die Bücher verschwinden samt Fortschritt (wie die lokale Musik). */
    suspend fun remove() = mutex.withLock { dao.deleteBooksOf(LOCAL_BOOK_SERVER_ID) }

    private val folderSharedState by lazy { MutableStateFlow(scanner.folders.isShared()) }

    /**
     * Der Ordner „Audiobooks“ ist freigegeben: Cover, `desc.txt`, `reader.txt` und `metadata.opf` der Buchordner zählen mit
     * (`books_improvements.md` M7).
     */
    val folderShared: StateFlow<Boolean> get() = folderSharedState

    /** Hier beginnt die Ordnerauswahl. */
    val folderPickerStart: Uri get() = scanner.folders.initialUri

    /**
     * Übernimmt die gewählte Freigabe und liest die Bücher neu (geänderte Begleitdateien ändern den Fingerabdruck). `false`,
     * wenn sie nicht „Audiobooks“ ist.
     */
    suspend fun shareFolder(tree: Uri): Boolean {
        val shared = scanner.folders.share(tree)
        folderSharedState.value = scanner.folders.isShared()
        if (shared) sync()
        return shared
    }

    /** Nimmt die Freigabe zurück; die Bücher verlieren beim neuen Einlesen, was aus den Begleitdateien kam. */
    suspend fun unshareFolder() {
        scanner.folders.unshare()
        folderSharedState.value = false
        sync()
    }

    private suspend fun store(book: LocalBook, details: LocalBookDetails, generation: Long) {
        val bookId = dao.upsertBook(
            BookEntity(
                serverId = LOCAL_BOOK_SERVER_ID,
                kind = BookKind.BOOK,
                remoteId = book.key,
                title = details.title,
                normTitle = details.title.normalizeForMerge(),
                author = details.author,
                narrator = details.narrator,
                durationMs = details.durationMs,
                remoteUpdatedAt = book.fingerprint,
                addedAt = book.addedAtMs,
                seenInGeneration = generation,
                subtitle = details.subtitle,
                publishedYear = details.year?.toString(),
                description = details.description.orEmpty(),
                series = details.series.orEmpty(),
                publisher = details.publisher,
                language = details.language,
                isbn = details.isbn,
                genres = parseGenres(details.genres).joinToString(", ").ifEmpty { null },
                // Cover aus dem Buchordner (Ordnerfreigabe), sonst das eingebettete der ersten Datei (`BookCovers`).
                coverUrl = details.coverUri,
            ),
        )
        val tracks = details.tracks.mapIndexed { index, track ->
            BookTrackEntity(bookId, index, track.uri, track.startMs, track.durationMs, track.mimeType)
        }
        val chapters = details.chapters.mapIndexed { index, chapter ->
            BookChapterEntity(bookId, index, chapter.title, chapter.startMs, chapter.endMs)
        }
        dao.replaceDetails(bookId, tracks, chapters)
        // Das Cover steckt in der Datei oder im Ordner; hat sich etwas geändert, gilt das zwischengespeicherte nicht mehr.
        covers.invalidate(bookId)
    }

    private companion object {
        const val TAG = "LocalBooks"
    }
}
