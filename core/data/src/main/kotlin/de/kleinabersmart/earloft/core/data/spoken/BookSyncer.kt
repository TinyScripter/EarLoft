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

/** Ergebnis eines Bücher-Abgleichs: `ok`, wenn nichts fehlgeschlagen ist, und die neuen Bücher. */
data class BookSyncResult(val ok: Boolean, val newBooks: List<NewBook>)

/**
 * Gleicht die Bibliothek eines Audiobookshelf-Servers ab (Konzept 12.1/12.3): Bücher anlegen und aktualisieren,
 * Gelöschtes per Mark-and-Sweep entfernen, danach den Fortschritt des Kontos übernehmen und fehlende Kapitel laden.
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
) {
    private val syncMutex = Mutex()

    /** `ok`, wenn alle Server, der Ordner „Audiobooks“ und die Bücher aus ARD Sounds abgeglichen werden konnten. */
    suspend fun syncAll(): BookSyncResult {
        val local = localBooks.sync()
        val results = servers.all().map { syncSafely(it) }
        val ard = ardBooks.refresh()
        return BookSyncResult(results.all { it.ok } && local && ard.ok, results.flatMap { it.newBooks } + ard.newBooks)
    }

    suspend fun syncSafely(server: AbsServer): BookSyncResult =
        try {
            BookSyncResult(ok = true, newBooks = sync(server))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Abgleich mit ${server.baseUrl} fehlgeschlagen", e)
            BookSyncResult(ok = false, newBooks = emptyList())
        }

    /**
     * Gibt die Bücher zurück, die es auf dem Server neu gibt (keine beim allerersten Abgleich). Abgeglichen werden alle Buch-
     * Bibliotheken außer den abgewählten (`books_improvements.md` M3); die Bücher einer gerade erst gewählten zählen nicht als neu.
     * Die Bücher abgewählter Bibliotheken bleiben unberührt stehen; nur die einer Bibliothek, die der Server nicht mehr hat, fallen weg.
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
        // Nur Bibliotheken, die schon der letzte Abgleich gelesen hat: Die Bücher einer abgewählten stehen noch mit älterer Generation da.
        val syncedBefore = stored.filter { it.seenInGeneration == entity.syncGeneration }.mapNotNullTo(HashSet()) { it.libraryId }
        val fresh = HashSet<String>()
        for (library in shown) {
            var page = 0
            while (true) {
                val result = api.libraryItems(server, library.id, page, PAGE_SIZE)
                result.results.forEach {
                    if (it.isMissing || it.isInvalid) {
                        // Fehlen die Dateien nur gerade (Freigabe beim Scan nicht eingehängt), bleibt ein bekanntes Buch
                        // samt Download und Fortschritt stehen; neue kommen erst, wenn sie abspielbar sind.
                        dao.markSeen(server.serverId, it.id, generation)
                        return@forEach
                    }
                    val entityToStore = it.toEntity(server.serverId, library.id, generation)
                    if (library.id in syncedBefore && entityToStore.remoteId !in known) fresh += entityToStore.remoteId
                    dao.upsertBook(entityToStore)
                }
                if (result.results.isEmpty() || (page + 1) * PAGE_SIZE >= result.total) break
                page++
            }
        }
        // Erst ungemeldeten Fortschritt hochladen: Gelöschte Bücher nehmen ihn mit.
        progressSync.pushPending()
        bookmarks.pushPending()
        // Ein abgebrochener Abgleich kommt nie bis hierher und löscht deshalb nichts.
        if (hidden.isEmpty()) dao.deleteUnseen(server.serverId, generation)
        else dao.deleteUnseenOutside(server.serverId, generation, hidden.map { it.id })
        // Neu gelesen: Schalter und Auswahl können sich während des Abgleichs geändert haben.
        val current = dao.server(server.serverId) ?: entity
        dao.upsertServer(current.copy(libraryId = shown.first().id, syncGeneration = generation, lastFullSyncAt = System.currentTimeMillis()))
        bookmarks.applyRemote(server.serverId, progressSync.pullAll(server).bookmarks)
        syncAuthors(server, shown.map { it.id })
        loadMissingDetails(server)
        if (fresh.isEmpty()) emptyList()
        else dao.booksOf(server.serverId).filter { it.remoteId in fresh }.map { NewBook(it.bookId, it.title, it.author) }
    }

    /**
     * Autoren der gezeigten Bibliotheken mit ihrer Beschreibung für die Autorenseite (`books_improvements.md` M8). Scheitert der
     * Abruf, bleiben die bisherigen: Die Autoren sind Beiwerk und sollen den Abgleich der Bücher nicht scheitern lassen.
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

    /** Kapitel und Dateien aller Bücher, die sie noch nicht haben (wenige gleichzeitig). */
    private suspend fun loadMissingDetails(server: AbsServer) = coroutineScope {
        val permits = Semaphore(DETAIL_PARALLELISM)
        dao.booksOf(server.serverId)
            .filter { !it.detailsLoaded }
            .map { book -> async { permits.withPermit { ensureDetails(book.bookId) } } }
            .awaitAll()
    }

    /** Lädt Kapitel und Dateien eines Buchs, falls sie fehlen. `false`, wenn sie nicht zu bekommen sind. */
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

    private companion object {
        const val TAG = "BookSyncer"
        const val PAGE_SIZE = 100
        const val DETAIL_PARALLELISM = 4
    }
}
