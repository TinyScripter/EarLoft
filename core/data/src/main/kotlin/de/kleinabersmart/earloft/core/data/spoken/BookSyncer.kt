package de.kleinabersmart.earloft.core.data.spoken

import android.util.Log
import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.data.refresh.NewBook
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.SpokenAuthorEntity
import de.kleinabersmart.earloft.core.database.entity.SpokenLibraryEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.parseGenres
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsItemDto
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/** Result of a book sync: `ok` if nothing failed, and the new books. */
data class BookSyncResult(val ok: Boolean, val newBooks: List<NewBook>)

/**
 * Syncs the library of an Audiobookshelf server (concept 12.1/12.3): create and update books, remove deleted
 * ones via mark-and-sweep, then adopt the account's progress and load missing chapters.
 */
@Singleton
class BookSyncer @Inject constructor(
    private val dao: BookDao,
    private val servers: AudiobookshelfServers,
    private val api: AbsApi,
    private val progressSync: BookProgressSync,
    private val bookmarks: Bookmarks,
    private val localBooks: LocalBooks,
    private val ardBooks: ArdBooks,
    private val uploads: ArdUploadAdoption,
) {
    private val syncMutex = Mutex()

    /** `ok` if all servers, the "Audiobooks" folder and the books from ARD Sounds could be synced. */
    suspend fun syncAll(): BookSyncResult = coroutineScope {
        // With folder access, the "Audiobooks" folder queries the provider once per book folder; the servers don't wait for it.
        val local = async { localBooks.sync() }
        val results = servers.all().map { syncSafely(it) }
        val ard = ardBooks.refresh()
        BookSyncResult(results.all { it.ok } && local.await() && ard.ok, results.flatMap { it.newBooks } + ard.newBooks)
    }

    suspend fun syncSafely(server: AbsServer): BookSyncResult =
        try {
            val fresh = sync(server)
            // An uploaded book from ARD Sounds that the server now reports gets its state.
            uploads.settle(server.serverId)
            BookSyncResult(ok = true, newBooks = fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Abgleich mit ${server.baseUrl} fehlgeschlagen", e)
            BookSyncResult(ok = false, newBooks = emptyList())
        }

    /**
     * Returns the books that are new on the server (none on the very first sync). All book libraries are synced
     * except the deselected ones (`books_improvements.md` M3); the books of a library that was just selected don't count as new.
     * The books of deselected libraries stay untouched; only those of a library the server no longer has are dropped.
     */
    private suspend fun sync(server: AbsServer): List<NewBook> = syncMutex.withLock {
        val entity = dao.server(server.serverId) ?: return@withLock emptyList()
        val libraries = api.libraries(server).filter { it.mediaType == "book" }
        dao.replaceLibraries(server.serverId, libraries.mapIndexed { index, it -> SpokenLibraryEntity(server.serverId, it.id, it.name, index) })
        val (shown, hidden) = libraries.partition { it.id !in entity.hiddenLibraryIds }
        if (shown.isEmpty()) return@withLock emptyList()
        val generation = entity.syncGeneration + 1
        val firstSync = entity.lastFullSyncAt == 0L
        val stored = if (firstSync) emptyList() else dao.booksOf(server.serverId)
        val known = stored.mapTo(HashSet()) { it.remoteId }
        // Only libraries the last sync already read: the books of a deselected one are still there with an older generation.
        val syncedBefore = stored.filter { it.seenInGeneration == entity.syncGeneration }.mapNotNullTo(HashSet()) { it.libraryId }
        val fresh = HashSet<String>()
        for (library in shown) {
            val countsAsNew = library.id in syncedBefore
            // If a book disappears along the way (deletion, scan on the server), start over once; twice in a row aborts the
            // sync without deleting anything (review D3, like R8 for Jellyfin/Emby).
            if (!scanLibrary(server, library.id, generation) { if (countsAsNew && it !in known) fresh += it } &&
                !scanLibrary(server, library.id, generation) { if (countsAsNew && it !in known) fresh += it }
            ) {
                throw IOException("${library.name} hat sich während des Abgleichs geändert")
            }
        }
        // First upload unreported progress: deleted books take it with them.
        progressSync.pushPending()
        bookmarks.pushPending()
        // An aborted sync never gets this far and therefore deletes nothing.
        if (hidden.isEmpty()) dao.deleteUnseen(server.serverId, generation)
        else dao.deleteUnseenOutside(server.serverId, generation, hidden.map { it.id })
        // Re-read: switch and selection may have changed during the sync.
        val current = dao.server(server.serverId) ?: entity
        dao.upsertServer(current.copy(libraryId = shown.first().id, syncGeneration = generation, lastFullSyncAt = System.currentTimeMillis()))
        bookmarks.pull(server.serverId) { progressSync.pullAll(server).bookmarks }
        syncAuthors(server, shown.map { it.id })
        loadMissingDetails(server)
        if (fresh.isEmpty()) emptyList()
        else dao.booksOf(server.serverId).filter { it.remoteId in fresh }.map { NewBook(it.bookId, it.title, it.author) }
    }

    /**
     * Authors of the visible libraries with their description for the author page (`books_improvements.md` M8). If the
     * fetch fails, the previous ones stay: the authors are an extra and shouldn't make the book sync fail.
     */
    private suspend fun syncAuthors(server: AbsServer, libraryIds: List<String>) {
        val authors = try {
            libraryIds.flatMap { api.authors(server, it) }
        } catch (e: IOException) {
            Log.w(TAG, "Autoren von ${server.baseUrl} nicht abrufbar", e)
            return
        }
        dao.replaceAuthors(
            server.serverId,
            authors.distinctBy { it.id }.map {
                SpokenAuthorEntity(server.serverId, it.id, it.name, it.name.normalizeForMerge(), it.description.orNullIfBlank())
            },
        )
    }

    /** Chapters and files of all books that don't have them yet (a few at a time). */
    private suspend fun loadMissingDetails(server: AbsServer) = coroutineScope {
        val permits = Semaphore(DETAIL_PARALLELISM)
        dao.booksOf(server.serverId)
            .filter { !it.detailsLoaded }
            .map { book -> async { permits.withPermit { ensureDetails(book.bookId) } } }
            .awaitAll()
    }

    /** Loads a book's chapters and files if they're missing. `false` if they can't be obtained. */
    suspend fun ensureDetails(bookId: Long): Boolean {
        val book = dao.book(bookId) ?: return false
        if (book.detailsLoaded) return true
        val server = servers.server(book.serverId) ?: return false
        val item = try {
            api.item(server, book.remoteId)
        } catch (e: IOException) {
            Log.w(TAG, "Details von ${book.title} nicht abrufbar", e)
            return false
        }
        var start = 0L
        val tracks = item.media.audioFiles
            .filter { !it.exclude && !it.invalid }
            .sortedBy { it.index }
            .mapIndexed { index, file ->
                val duration = (file.duration * 1000).roundToLong()
                BookTrackEntity(bookId, index, file.ino, start, duration).also { start += duration }
            }
        val chapters = item.media.chapters
            .sortedBy { it.start }
            .mapIndexed { index, chapter ->
                BookChapterEntity(bookId, index, chapter.title, (chapter.start * 1000).roundToLong(), (chapter.end * 1000).roundToLong())
            }
        dao.replaceDetails(bookId, tracks, chapters)
        return true
    }

    private fun AbsItemDto.toEntity(serverId: String, libraryId: String, generation: Long): BookEntity {
        val metadata = media.metadata
        return BookEntity(
            serverId = serverId,
            kind = BookKind.BOOK,
            remoteId = id,
            title = metadata.title,
            normTitle = metadata.title.normalizeForMerge(),
            author = metadata.authorName.orEmpty(),
            narrator = metadata.narratorName.orEmpty(),
            series = metadata.seriesName.orEmpty(),
            description = metadata.description.orEmpty(),
            isbn = metadata.isbn?.trim()?.ifEmpty { null },
            subtitle = metadata.subtitle.orNullIfBlank(),
            publishedYear = metadata.publishedYear.orNullIfBlank(),
            publisher = metadata.publisher.orNullIfBlank(),
            language = metadata.language.orNullIfBlank(),
            genres = parseGenres(metadata.genres).joinToString(", ").ifEmpty { null },
            libraryId = libraryId,
            durationMs = (media.duration * 1000).roundToLong(),
            remoteUpdatedAt = updatedAt,
            addedAt = addedAt,
            seenInGeneration = generation,
        )
    }

    private fun String?.orNullIfBlank(): String? = this?.trim()?.ifEmpty { null }

    /**
     * Reads a library page by page (by `addedAt`, new ones go to the end) and writes each book with [generation].
     * After each page the last entry of the previous one is still in its place; otherwise something before it disappeared and the
     * first one of this page would be missing – `deleteUnseen` would delete it along with its download. The check happens after
     * the page: a shift before it is noticed as well, one after it causes at most an unnecessary restart. What the aborted pass
     * has already seen counts as seen: a book that was just deleted is thus only dropped on the next sync. [onStored] receives the
     * identifier of every written book. @return `false` on a shift.
     */
    private suspend fun scanLibrary(server: AbsServer, libraryId: String, generation: Long, onStored: (String) -> Unit): Boolean {
        var page = 0
        var lastId: String? = null
        while (true) {
            val result = api.libraryItems(server, libraryId, page, PAGE_SIZE)
            if (lastId != null && api.libraryItems(server, libraryId, page * PAGE_SIZE - 1, 1).results.firstOrNull()?.id != lastId) {
                Log.i(TAG, "Bibliothek $libraryId hat sich während des Abgleichs verschoben")
                return false
            }
            result.results.forEach {
                if (it.isMissing || it.isInvalid) {
                    // If the files are only temporarily missing (share not mounted during the scan), a known book stays
                    // along with its download and progress; new ones only come once they are playable.
                    dao.markSeen(server.serverId, it.id, generation)
                    return@forEach
                }
                val entityToStore = it.toEntity(server.serverId, libraryId, generation)
                dao.upsertBook(entityToStore)
                onStored(entityToStore.remoteId)
            }
            lastId = result.results.lastOrNull()?.id ?: lastId
            if (result.results.isEmpty() || (page + 1) * PAGE_SIZE >= result.total) return true
            page++
        }
    }

    private companion object {
        const val TAG = "BookSyncer"
        const val PAGE_SIZE = 100
        const val DETAIL_PARALLELISM = 4
    }
}
