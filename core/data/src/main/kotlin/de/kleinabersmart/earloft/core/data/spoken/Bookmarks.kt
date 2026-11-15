package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.BookmarkWithBook
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookmarkEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.Bookmark
import de.kleinabersmart.earloft.core.model.isLocalSource
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsBookmarkDto
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsBookmarkUpdate
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lesezeichen in Büchern und Folgen (Konzept 12.6): lokal sofort, bei Büchern eines Audiobookshelf-Servers auch dort –
 * gemeldet wie der Fortschritt, offline nachgeholt von [BookProgressWorker], übernommen beim Abgleich
 * ([BookProgressSync.pullAll] → [pull]). Folgen und Bücher vom Gerät bleiben auf dem Gerät. Audiobookshelf kennt
 * keine Notizen; sie bleiben lokal.
 */
@Singleton
class Bookmarks @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: BookDao,
    private val servers: AudiobookshelfServers,
    private val api: AbsApi,
    private val policy: PlaybackPolicy,
) {
    /**
     * Meldungen ([push]) und Übernahme ([pull]) laufen nacheinander: Sonst arbeitete die Übernahme mit einem Stand des Servers
     * von vor einer gleichzeitigen Meldung, löschte ein gerade gemeldetes Lesezeichen (samt Notiz) bzw. holte ein gerade
     * gelöschtes zurück.
     */
    private val serverMutex = Mutex()

    fun of(bookId: Long): Flow<List<Bookmark>> = dao.observeBookmarks(bookId).map { list -> list.map { it.toModel() } }

    /** Alle Lesezeichen, neueste zuerst. */
    fun all(): Flow<List<BookmarkEntry>> = dao.observeAllBookmarks().map { list -> list.map { it.toEntry() } }

    /** Setzt ein Lesezeichen bei [positionMs]; `null`, wenn es das Buch nicht mehr gibt. */
    suspend fun add(bookId: Long, positionMs: Long, title: String, note: String = ""): Long? {
        val book = dao.book(bookId) ?: return null
        val synced = syncsWithServer(book)
        // Audiobookshelf erkennt Lesezeichen an der Zeit; ganze Sekunden vergleichen sich sicher.
        val position = positionMs.coerceAtLeast(0).let { if (synced) it / 1000 * 1000 else it }
        val now = System.currentTimeMillis()
        val id = dao.insertBookmark(
            BookmarkEntity(bookId = bookId, positionMs = position, title = title, note = note, createdAt = now, updatedAt = now, dirty = synced),
        )
        if (synced) push(id)
        return id
    }

    /** Titel und Notiz ändern; nur ein neuer Titel geht an den Server. */
    suspend fun edit(bookmarkId: Long, title: String, note: String) {
        val bookmark = dao.bookmark(bookmarkId) ?: return
        if (bookmark.title == title && bookmark.note == note) return
        val titleChanged = bookmark.title != title && bookmark.remoteTime != null
        dao.updateBookmark(
            bookmark.copy(title = title, note = note, updatedAt = System.currentTimeMillis(), dirty = bookmark.dirty || titleChanged),
        )
        if (titleChanged) push(bookmarkId)
    }

    /** Löscht; was der Server schon kennt, bleibt bis zur Meldung als gelöscht stehen. */
    suspend fun delete(bookmarkId: Long) {
        val bookmark = dao.bookmark(bookmarkId) ?: return
        if (bookmark.remoteTime == null) {
            dao.deleteBookmark(bookmarkId)
            return
        }
        dao.updateBookmark(bookmark.copy(deleted = true, dirty = true, updatedAt = System.currentTimeMillis()))
        push(bookmarkId)
    }

    /** Rückgängig nach [delete]: steht es noch (nicht gemeldet), gilt es wieder; sonst neu angelegt. */
    suspend fun restore(bookmark: Bookmark) {
        val stored = dao.bookmark(bookmark.id)
        if (stored != null) {
            dao.updateBookmark(stored.copy(deleted = false, dirty = stored.remoteTime != null || stored.dirty, updatedAt = System.currentTimeMillis()))
            push(bookmark.id)
        } else {
            add(bookmark.bookId, bookmark.positionMs, bookmark.title, bookmark.note)
        }
    }

    /** `true`, wenn nichts mehr aussteht. */
    suspend fun push(bookmarkId: Long): Boolean = serverMutex.withLock { pushLocked(bookmarkId) }

    private suspend fun pushLocked(bookmarkId: Long): Boolean {
        val bookmark = dao.bookmark(bookmarkId) ?: return true
        if (!bookmark.dirty) return true
        // Uhr: keine Verbindung zu Audiobookshelf (Wear-Konzept 16.2).
        if (!policy.syncsWithBookServers) return true
        val book = dao.book(bookmark.bookId) ?: return true
        val server = servers.server(book.serverId) ?: return false
        return try {
            val remoteTime = bookmark.remoteTime
            val time = remoteTime ?: (bookmark.positionMs / 1000.0)
            when {
                bookmark.deleted -> {
                    if (remoteTime != null) api.deleteBookmark(server, book.remoteId, remoteTime)
                    dao.deleteDeletedBookmark(bookmarkId, bookmark.updatedAt)
                    return true
                }
                remoteTime == null -> api.createBookmark(server, book.remoteId, AbsBookmarkUpdate(time, bookmark.title))
                else -> try {
                    api.updateBookmark(server, book.remoteId, AbsBookmarkUpdate(time, bookmark.title))
                } catch (e: AbsException) {
                    // Auf einem anderen Gerät gelöscht: wieder anlegen, die Änderung hier ist neuer.
                    if (e.code != 404) throw e
                    api.createBookmark(server, book.remoteId, AbsBookmarkUpdate(time, bookmark.title))
                }
            }
            dao.markBookmarkClean(bookmarkId, time, bookmark.updatedAt)
            true
        } catch (e: IOException) {
            Log.w(TAG, "Lesezeichen in ${book.title} nicht gemeldet", e)
            BookProgressWorker.schedule(context)
            false
        }
    }

    /** Meldet alles, was aussteht. `true`, wenn nichts mehr offen ist. */
    suspend fun pushPending(): Boolean = dao.dirtyBookmarks().map { push(it.bookmarkId) }.all { it }

    /**
     * Holt die Lesezeichen des Kontos mit [fetch] (aus `/api/me`) und übernimmt sie für die Bücher von [serverId]: neue kommen
     * hinzu, Titel gleichen sich an, und was der Server nicht mehr kennt, fällt weg – außer es hat hier noch ungemeldete
     * Änderungen. Lesezeichen von Büchern, die es hier (noch) nicht gibt, bleiben außen vor. Abruf und Übernahme laufen nicht
     * gleichzeitig mit einer Meldung ([serverMutex]); [fetch] darf deshalb selbst keine Lesezeichen melden.
     */
    suspend fun pull(serverId: String, fetch: suspend () -> List<AbsBookmarkDto>) = serverMutex.withLock {
        applyRemoteLocked(serverId, fetch())
    }

    /** Wie [pull] mit einem schon geholten Stand. */
    suspend fun applyRemote(serverId: String, remote: List<AbsBookmarkDto>) = pull(serverId) { remote }

    private suspend fun applyRemoteLocked(serverId: String, remote: List<AbsBookmarkDto>) {
        val books = dao.booksOf(serverId).filter { it.kind == BookKind.BOOK }
        if (books.isEmpty()) return
        val byRemoteId = books.associateBy { it.remoteId }
        val local = dao.bookmarksOf(books.map { it.bookId }).groupBy { it.bookId }
        val remoteByBook = remote.mapNotNull { dto -> byRemoteId[dto.libraryItemId]?.let { it.bookId to dto } }.groupBy({ it.first }, { it.second })
        for (book in books) {
            val mine = local[book.bookId].orEmpty()
            val theirs = remoteByBook[book.bookId].orEmpty()
            for (dto in theirs) {
                val match = mine.firstOrNull { it.remoteTime == dto.time }
                    // Hier angelegt, Meldung ging durch, Antwort nicht: dieselbe Stelle.
                    ?: mine.firstOrNull { it.remoteTime == null && it.positionMs == (dto.time * 1000).roundToLong() }
                when {
                    match == null -> {
                        val createdAt = dto.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                        dao.insertBookmark(
                            BookmarkEntity(
                                bookId = book.bookId,
                                positionMs = (dto.time * 1000).roundToLong(),
                                title = dto.title,
                                createdAt = createdAt,
                                updatedAt = createdAt,
                                remoteTime = dto.time,
                            ),
                        )
                    }
                    match.dirty -> Unit
                    match.title != dto.title || match.remoteTime != dto.time ->
                        dao.updateBookmark(match.copy(title = dto.title, remoteTime = dto.time))
                }
            }
            val remoteTimes = theirs.mapTo(HashSet()) { it.time }
            mine.filter { it.remoteTime != null && !it.dirty && it.remoteTime !in remoteTimes }
                .forEach { dao.deleteBookmark(it.bookmarkId) }
        }
    }

    private suspend fun syncsWithServer(book: BookEntity): Boolean =
        book.kind == BookKind.BOOK && !isLocalSource(book.serverId) && servers.server(book.serverId) != null

    private companion object {
        const val TAG = "Bookmarks"
    }
}

/** Ein Lesezeichen samt dem Titel seines Buchs bzw. seiner Folge (gesammelte Liste). */
data class BookmarkEntry(val bookmark: Bookmark, val bookTitle: String, val kind: BookKind)

internal fun BookmarkEntity.toModel() = Bookmark(bookmarkId, bookId, positionMs, title, note, createdAt)

private fun BookmarkWithBook.toEntry() = BookmarkEntry(bookmark.toModel(), bookTitle, kind)
