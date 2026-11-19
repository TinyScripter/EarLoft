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
 * Bookmarks in books and episodes (concept 12.6): locally immediately, for books of an Audiobookshelf server also there –
 * reported like progress, caught up offline by [BookProgressWorker], adopted during sync
 * ([BookProgressSync.pullAll] → [pull]). Episodes and books from the device stay on the device. Audiobookshelf has
 * no notes; they stay local.
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
     * Reports ([push]) and adoption ([pull]) run one after the other: otherwise the adoption would work with a server state
     * from before a concurrent report, deleting a bookmark that was just reported (including its note) or bringing
     * back one that was just deleted.
     */
    private val serverMutex = Mutex()

    fun of(bookId: Long): Flow<List<Bookmark>> = dao.observeBookmarks(bookId).map { list -> list.map { it.toModel() } }

    /** All bookmarks in books or episodes ([kind]), newest first. */
    fun all(kind: BookKind): Flow<List<BookmarkEntry>> = dao.observeAllBookmarks(kind).map { list -> list.map { it.toEntry() } }

    /** Sets a bookmark at [positionMs]; `null` if the book no longer exists. */
    suspend fun add(bookId: Long, positionMs: Long, title: String, note: String = ""): Long? {
        val book = dao.book(bookId) ?: return null
        val synced = syncsWithServer(book)
        // Audiobookshelf identifies bookmarks by time; whole seconds compare reliably.
        val position = positionMs.coerceAtLeast(0).let { if (synced) it / 1000 * 1000 else it }
        val now = System.currentTimeMillis()
        val id = dao.insertBookmark(
            BookmarkEntity(bookId = bookId, positionMs = position, title = title, note = note, createdAt = now, updatedAt = now, dirty = synced),
        )
        if (synced) push(id)
        return id
    }

    /** Change title and note; only a new title goes to the server. */
    suspend fun edit(bookmarkId: Long, title: String, note: String) {
        val bookmark = dao.bookmark(bookmarkId) ?: return
        if (bookmark.title == title && bookmark.note == note) return
        val titleChanged = bookmark.title != title && bookmark.remoteTime != null
        dao.updateBookmark(
            bookmark.copy(title = title, note = note, updatedAt = System.currentTimeMillis(), dirty = bookmark.dirty || titleChanged),
        )
        if (titleChanged) push(bookmarkId)
    }

    /** Deletes; what the server already knows stays marked as deleted until it's reported. */
    suspend fun delete(bookmarkId: Long) {
        val bookmark = dao.bookmark(bookmarkId) ?: return
        if (bookmark.remoteTime == null) {
            dao.deleteBookmark(bookmarkId)
            return
        }
        dao.updateBookmark(bookmark.copy(deleted = true, dirty = true, updatedAt = System.currentTimeMillis()))
        push(bookmarkId)
    }

    /** Undo after [delete]: if it's still there (not reported), it's valid again; otherwise it's created anew. */
    suspend fun restore(bookmark: Bookmark) {
        val stored = dao.bookmark(bookmark.id)
        if (stored != null) {
            dao.updateBookmark(stored.copy(deleted = false, dirty = stored.remoteTime != null || stored.dirty, updatedAt = System.currentTimeMillis()))
            push(bookmark.id)
        } else {
            add(bookmark.bookId, bookmark.positionMs, bookmark.title, bookmark.note)
        }
    }

    /** `true` if nothing is pending anymore. */
    suspend fun push(bookmarkId: Long): Boolean = serverMutex.withLock { pushLocked(bookmarkId) }

    private suspend fun pushLocked(bookmarkId: Long): Boolean {
        val bookmark = dao.bookmark(bookmarkId) ?: return true
        if (!bookmark.dirty) return true
        // Watch: no connection to Audiobookshelf (Wear concept 16.2).
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
                    // Deleted on another device: recreate it, the change here is newer.
                    if (e.code != 404) throw e
                    api.createBookmark(server, book.remoteId, AbsBookmarkUpdate(time, bookmark.title))
                }
            }
            dao.markBookmarkClean(bookmarkId, time, bookmark.updatedAt)
            true
        } catch (e: IOException) {
            Log.w(TAG, "Bookmark in ${book.title} not reported", e)
            BookProgressWorker.schedule(context)
            false
        }
    }

    /** Reports everything pending. `true` if nothing is open anymore. */
    suspend fun pushPending(): Boolean = dao.dirtyBookmarks().map { push(it.bookmarkId) }.all { it }

    /**
     * Fetches the account's bookmarks with [fetch] (from `/api/me`) and adopts them for the books of [serverId]: new ones are
     * added, titles are aligned, and what the server no longer knows is dropped – unless it still has unreported changes
     * here. Bookmarks of books that don't exist here (yet) are left out. Fetching and adopting don't run
     * concurrently with a report ([serverMutex]); [fetch] must therefore not report any bookmarks itself.
     */
    suspend fun pull(serverId: String, fetch: suspend () -> List<AbsBookmarkDto>) = serverMutex.withLock {
        applyRemoteLocked(serverId, fetch())
    }

    /** Like [pull] with an already fetched state. */
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
                    // Created here, the report went through, the response didn't: the same position.
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

/** A bookmark along with title and author (for episodes the podcast) of its book or episode (collected list). */
data class BookmarkEntry(val bookmark: Bookmark, val bookTitle: String, val bookAuthor: String, val kind: BookKind)

internal fun BookmarkEntity.toModel() = Bookmark(bookmarkId, bookId, positionMs, title, note, createdAt)

private fun BookmarkWithBook.toEntry() = BookmarkEntry(bookmark.toModel(), bookTitle, bookAuthor, kind)
