package de.kleinabersmart.earloft.core.data.spoken

import android.util.Log
import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.data.refresh.NewBook
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.BookKind
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
    private val localBooks: LocalBooks,
) {
    private val syncMutex = Mutex()

    /** `ok`, wenn alle Server und der Ordner „Audiobooks“ abgeglichen werden konnten. */
    suspend fun syncAll(): BookSyncResult {
        val local = localBooks.sync()
        val results = servers.all().map { syncSafely(it) }
        return BookSyncResult(results.all { it.ok } && local, results.flatMap { it.newBooks })
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

    /** Gibt die Bücher zurück, die es auf dem Server neu gibt (keine beim allerersten Abgleich). */
    private suspend fun sync(server: AbsServer): List<NewBook> = syncMutex.withLock {
        val entity = dao.server(server.serverId) ?: return@withLock emptyList()
        val libraryId = entity.libraryId
            ?: api.libraries(server).firstOrNull { it.mediaType == "book" }?.id
            ?: return@withLock emptyList()
        val generation = entity.syncGeneration + 1
        val firstSync = entity.lastFullSyncAt == 0L
        val known = if (firstSync) emptySet() else dao.booksOf(server.serverId).mapTo(HashSet()) { it.remoteId }
        val fresh = HashSet<String>()
        var page = 0
        while (true) {
            val result = api.libraryItems(server, libraryId, page, PAGE_SIZE)
            result.results.forEach {
                if (it.isMissing || it.isInvalid) {
                    // Fehlen die Dateien nur gerade (Freigabe beim Scan nicht eingehängt), bleibt ein bekanntes Buch
                    // samt Download und Fortschritt stehen; neue kommen erst, wenn sie abspielbar sind.
                    dao.markSeen(server.serverId, it.id, generation)
                    return@forEach
                }
                val entityToStore = it.toEntity(server.serverId, generation)
                if (!firstSync && entityToStore.remoteId !in known) fresh += entityToStore.remoteId
                dao.upsertBook(entityToStore)
            }
            if (result.results.isEmpty() || (page + 1) * PAGE_SIZE >= result.total) break
            page++
        }
        // Erst ungemeldeten Fortschritt hochladen: Gelöschte Bücher nehmen ihn mit.
        progressSync.pushPending()
        // Ein abgebrochener Abgleich kommt nie bis hierher und löscht deshalb nichts.
        dao.deleteUnseen(server.serverId, generation)
        dao.upsertServer(entity.copy(libraryId = libraryId, syncGeneration = generation, lastFullSyncAt = System.currentTimeMillis()))
        progressSync.pullAll(server)
        loadMissingDetails(server)
        if (fresh.isEmpty()) emptyList()
        else dao.booksOf(server.serverId).filter { it.remoteId in fresh }.map { NewBook(it.bookId, it.title, it.author) }
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

    private fun AbsItemDto.toEntity(serverId: String, generation: Long): BookEntity {
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
