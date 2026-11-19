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
 * Books from the "Audiobooks" folder on the device (concept 12.1). Like a server's books they live in `books`
 * (`serverId = `[LOCAL_BOOK_SERVER_ID], `remoteId` = path below "Audiobooks/"); the files are stored with their
 * `content://` address in `book_tracks.remoteFileId`. Progress stays local; there is no server for it.
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
     * Syncs the books on the device with the database: create new ones, re-read changed ones, delete ones that
     * disappeared. Unchanged books only cost the MediaStore query. `false` if the sync wasn't possible
     * (then everything stays as it is).
     */
    suspend fun sync(): Boolean = mutex.withLock {
        if (!settings.useLocalBooks.first()) return@withLock true
        val generation = dao.nextGeneration()
        val scanned = try {
            scanner.scan { key, fingerprint ->
                dao.bookByRemote(LOCAL_BOOK_SERVER_ID, key)?.let { it.remoteUpdatedAt == fingerprint && it.detailsLoaded } == true
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission for the books on the device", e)
            return@withLock false
        } catch (e: IOException) {
            Log.w(TAG, "Books on the device not readable", e)
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
        // An aborted or failed sync never gets this far and therefore deletes nothing.
        dao.deleteUnseen(LOCAL_BOOK_SERVER_ID, generation)
        true
    }

    /** Disabled: the books disappear along with their progress (like local music). */
    suspend fun remove() = mutex.withLock { dao.deleteBooksOf(LOCAL_BOOK_SERVER_ID) }

    private val folderSharedState by lazy { MutableStateFlow(scanner.folders.isShared()) }

    /**
     * The "Audiobooks" folder is shared: cover, `desc.txt`, `reader.txt` and `metadata.opf` of the book folders are taken into
     * account (`books_improvements.md` M7).
     */
    val folderShared: StateFlow<Boolean> get() = folderSharedState

    /** The folder picker starts here. */
    val folderPickerStart: Uri get() = scanner.folders.initialUri

    /**
     * Adopts the chosen folder access and re-reads the books (changed companion files change the fingerprint). `false`
     * if it isn't "Audiobooks".
     */
    suspend fun shareFolder(tree: Uri): Boolean {
        val shared = scanner.folders.share(tree)
        folderSharedState.value = scanner.folders.isShared()
        if (shared) sync()
        return shared
    }

    /** Revokes the folder access; on re-reading, the books lose what came from the companion files. */
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
                // Cover from the book folder (folder access), otherwise the embedded one of the first file (`BookCovers`).
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
        // The cover is in the file or the folder; if something changed, the cached one is no longer valid.
        covers.invalidate(bookId)
    }

    private companion object {
        const val TAG = "LocalBooks"
    }
}
