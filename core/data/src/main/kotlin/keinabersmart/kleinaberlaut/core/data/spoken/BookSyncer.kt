package keinabersmart.kleinaberlaut.core.data.spoken

import android.util.Log
import keinabersmart.kleinaberlaut.core.common.normalizeForMerge
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.entity.BookChapterEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookTrackEntity
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsApi
import keinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsItemDto
import keinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsServer
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

    /** `true`, wenn alle Server und der Ordner „Audiobooks“ abgeglichen werden konnten. */
    suspend fun syncAll(): Boolean {
        val local = localBooks.sync()
        return servers.all().map { syncSafely(it) }.all { it } && local
    }

    suspend fun syncSafely(server: AbsServer): Boolean =
        try {
            sync(server)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Abgleich mit ${server.baseUrl} fehlgeschlagen", e)
            false
        }

    private suspend fun sync(server: AbsServer) = syncMutex.withLock {
        val entity = dao.server(server.serverId) ?: return@withLock
        val libraryId = entity.libraryId
            ?: api.libraries(server).firstOrNull { it.mediaType == "book" }?.id
            ?: return@withLock
        val generation = entity.syncGeneration + 1
        var page = 0
        while (true) {
            val result = api.libraryItems(server, libraryId, page, PAGE_SIZE)
            result.results
                .filter { !it.isMissing && !it.isInvalid }
                .forEach { dao.upsertBook(it.toEntity(server.serverId, generation)) }
            if (result.results.isEmpty() || (page + 1) * PAGE_SIZE >= result.total) break
            page++
        }
        // Ein abgebrochener Abgleich kommt nie bis hierher und löscht deshalb nichts.
        dao.deleteUnseen(server.serverId, generation)
        dao.upsertServer(entity.copy(libraryId = libraryId, syncGeneration = generation, lastFullSyncAt = System.currentTimeMillis()))
        progressSync.pushPending()
        progressSync.pullAll(server)
        loadMissingDetails(server)
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
            durationMs = (media.duration * 1000).roundToLong(),
            remoteUpdatedAt = updatedAt,
            addedAt = addedAt,
            seenInGeneration = generation,
        )
    }

    private companion object {
        const val TAG = "BookSyncer"
        const val PAGE_SIZE = 100
        const val DETAIL_PARALLELISM = 4
    }
}
