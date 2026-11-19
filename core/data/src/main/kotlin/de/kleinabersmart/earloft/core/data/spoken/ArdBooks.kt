package de.kleinabersmart.earloft.core.data.spoken

import android.util.Log
import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.data.refresh.NewBook
import de.kleinabersmart.earloft.core.data.refresh.NewShow
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.ARD_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdBook
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdBookSummary
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdCategory
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdShow
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsBooks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A subscribed show or series from ARD Sounds: its new books come into the library on their own. */
data class ArdSubscription(val showId: String, val title: String)

/** Result of a sync of the books from ARD Sounds: `ok` if nothing failed, and those newly added to the library. */
data class ArdRefresh(val ok: Boolean, val newBooks: List<NewBook>)

/**
 * Audiobooks and radio plays from ARD Sounds (`books_improvements.md` M9). A book comes into the library as soon as it is played,
 * downloaded or added ([add]) or belongs to a subscribed show ([subscribe]); like the others it lives in `books` (`serverId = `[ARD_BOOK_SERVER_ID], `remoteId` = the
 * ID from [ArdSoundsBooks]), one file per part with its URL and one chapter. The sync ([refresh]) picks up new parts,
 * keeps every part of started books in its place, even if ARD Sounds no longer lists it, and never removes a book.
 */
@Singleton
class ArdBooks @Inject constructor(
    private val dao: BookDao,
    private val api: ArdSoundsBooks,
    private val settings: SpokenSettings,
    private val covers: BookCovers,
    private val keyMoves: BookKeyMoves,
) {
    private val mutex = Mutex()

    /** One sync after the other: if the worker and a quick sync ran at the same time, [takeNew] would count a book as new twice. */
    private val refreshMutex = Mutex()

    /** Switch Settings → Books → Sources. */
    val enabled: Flow<Boolean> get() = settings.ardSoundsBooks

    /** The books from ARD Sounds in the library: ID at ARD Sounds → `bookId`. */
    val library: Flow<Map<String, Long>> =
        dao.observeBookRefsOf(ARD_BOOK_SERVER_ID).map { refs -> refs.associate { it.remoteId to it.bookId } }.distinctUntilChanged()

    /** The subscribed shows. */
    val subscriptions: Flow<List<ArdSubscription>> get() = settings.ardSubscriptions

    suspend fun shows(category: ArdCategory): List<ArdShow> = api.shows(category)

    suspend fun showBooks(showId: String): List<ArdBookSummary> = api.showBooks(showId)

    suspend fun search(term: String): List<ArdBookSummary> = api.search(term)

    /** The book for the preview, without storing it; `null` if ARD Sounds doesn't list it (anymore). */
    suspend fun preview(key: String): ArdBook? = api.book(key)

    /**
     * Adds the book [key] to the library (or updates it) and returns its `bookId`. [known] saves the fetch if
     * the preview already loaded it; [addedAt] and [saved] (the parts on the old device, [decodeArdParts]) come from a
     * backup. `null` if ARD Sounds doesn't list it (anymore). @throws IOException without a connection.
     */
    internal suspend fun add(key: String, known: ArdBook? = null, addedAt: Long? = null, saved: List<ArdBookPart>): Long? = mutex.withLock {
        dao.bookByRemote(ARD_BOOK_SERVER_ID, key)?.let { return@withLock it.bookId }
        val book = known ?: api.book(key) ?: return@withLock null
        // ARD Sounds has assigned the parts differently: the book now has a different name and may already be there.
        dao.bookByRemote(ARD_BOOK_SERVER_ID, book.key)?.let { return@withLock it.bookId }
        store(book, existing = null, addedAt = addedAt, saved = saved)
    }

    /**
     * Brings back the backed-up book [key] ([add] with the details from the backup). If ARD Sounds now assigns its titles
     * differently ([fetch]), it still comes into the library under [key]: the backup's progress and bookmarks refer to that,
     * and it gets the new key on the next [refresh] like any other book. If it is already in the library under the new one,
     * it stays that way, and it is only queried again once per [GONE_RECHECK_MS]. If ARD Sounds confirmedly no longer knows it
     * ([confirmMissing]), likewise and not on every start; without a connection (@throws IOException) that doesn't count as "no longer
     * there". `null` if it was not fetched.
     */
    internal suspend fun restore(key: String, addedAt: Long?, saved: List<ArdBookPart>): Long? {
        val now = System.currentTimeMillis()
        val checked = settings.ardGoneChecks()[key]
        if (checked != null && now - checked < GONE_RECHECK_MS) return null
        val here = dao.bookByRemote(ARD_BOOK_SERVER_ID, key)?.bookId
        val found = if (here == null) fetch(key) { saved.mapNotNull { it.track.remoteItemId } } else null
        val elsewhere = found?.key?.takeIf { it != key }?.let { moved ->
            dao.bookByRemote(ARD_BOOK_SERVER_ID, moved)?.also { Log.i(TAG, "$key is now $moved at ARD Sounds, which is already in the library") }
        }
        val bookId = here ?: elsewhere?.bookId ?: found?.let { add(key, known = it.copy(key = key), addedAt = addedAt, saved = saved) }
        val missing = settings.ardMissing()
        val gone = bookId == null && confirmMissing(key, missing, now)
        when {
            gone || elsewhere != null -> settings.setArdGoneCheck(key, now)
            bookId != null && checked != null -> settings.setArdGoneCheck(key, null)
        }
        if ((bookId != null || gone) && key in missing) settings.setArdMissing(key, null)
        return bookId
    }

    /** Like the other [add], without a backup. */
    suspend fun add(key: String, known: ArdBook? = null): Long? = add(key, known, addedAt = null, saved = emptyList())

    /**
     * Subscribes to the show [showId]: whatever appears there from now on comes into the library (on [refresh]); the books that already
     * exist don't. @throws IOException without a connection.
     */
    suspend fun subscribe(showId: String, title: String) {
        settings.subscribeArd(showId, title, api.showBooks(showId).map { it.key })
    }

    suspend fun unsubscribe(showId: String) = settings.unsubscribeArd(showId)

    /** Removes the book from the library, along with progress and bookmarks; the listening time stays in the statistics. */
    suspend fun remove(bookId: Long) = mutex.withLock {
        val book = dao.book(bookId) ?: return@withLock
        if (book.serverId == ARD_BOOK_SERVER_ID) dao.deleteBook(bookId)
    }

    /**
     * Syncs the books from ARD Sounds in the library and fetches the new ones of the subscribed shows (only with the switch
     * enabled). `ok` is `false` if something couldn't be fetched; the rest is synced anyway. An expired book
     * that ARD Sounds was asked about recently is not queried ([GONE_RECHECK_MS]).
     */
    suspend fun refresh(): ArdRefresh = refreshMutex.withLock {
        if (!settings.ardSoundsBooks.first()) return@withLock ArdRefresh(ok = true, newBooks = emptyList())
        var ok = true
        val checked = settings.ardGoneChecks()
        val missing = settings.ardMissing()
        for (existing in dao.booksOf(ARD_BOOK_SERVER_ID)) {
            val now = System.currentTimeMillis()
            if (existing.isExpired(now) && checked[existing.remoteId]?.let { now - it < GONE_RECHECK_MS } == true) continue
            try {
                // If ARD Sounds assigns the titles differently, the book now has a different name; it keeps everything.
                val book = fetch(existing.remoteId) { dao.tracks(existing.bookId).mapNotNull { it.remoteItemId } }
                val moved = mutex.withLock {
                    // Removed in the meantime: don't create it again.
                    val current = dao.book(existing.bookId) ?: return@withLock null
                    // A book that shouldn't have expired yet only counts as gone after a second empty response.
                    val gone = book == null && (current.isExpired(now) || confirmMissing(current.remoteId, missing, now))
                    // Back again or now gone: the note of the first empty response has served its purpose.
                    if ((book != null || gone) && current.remoteId in missing) settings.setArdMissing(current.remoteId, null)
                    val moved = when {
                        book == null -> {
                            if (gone) markGone(current)
                            null
                        }
                        // It is already in the library under the new key: this one stays as it is.
                        book.key != current.remoteId && dao.bookByRemote(ARD_BOOK_SERVER_ID, book.key) != null -> {
                            Log.i(TAG, "${current.title} is now ${book.key} at ARD Sounds, which is already in the library")
                            null
                        }
                        else -> {
                            store(book, current)
                            (current.remoteId to book.key).takeIf { book.key != current.remoteId }
                        }
                    }
                    // Expired, even after the sync: don't ask again for a week.
                    val expired = dao.book(existing.bookId)?.isExpired(System.currentTimeMillis()) == true
                    if (expired) settings.setArdGoneCheck(existing.remoteId, now) else if (existing.remoteId in checked) settings.setArdGoneCheck(existing.remoteId, null)
                    moved
                }
                // Outside the lock: carrying over takes the backup's lock, and the backup in turn calls [restore].
                // Statistics, queue and backup only know the book by its key.
                moved?.let { keyMoves.moved(ARD_BOOK_SERVER_ID, mapOf(it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "${existing.title} from ARD Sounds not available", e)
                ok = false
            }
        }
        val fresh = mutableListOf<NewBook>()
        for (subscription in settings.ardSubscriptions.first()) {
            try {
                fresh += takeNew(subscription)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "${subscription.title} from ARD Sounds not available", e)
                ok = false
            }
        }
        ArdRefresh(ok, fresh)
    }

    private fun BookEntity.isExpired(now: Long) = availableUntil?.let { it <= now } == true

    /**
     * The book [key] at ARD Sounds, even if it is now named differently ([ArdSoundsBooks.movedKey]; [itemIds] are the IDs
     * of its titles, only queried if [key] finds nothing). `null` if ARD Sounds doesn't list it (anymore).
     */
    private suspend fun fetch(key: String, itemIds: suspend () -> List<String>): ArdBook? =
        api.book(key) ?: api.movedKey(key, itemIds())?.let { api.book(it) }

    /**
     * Whether ARD Sounds really no longer lists the book [key]. A single empty response can be a glitch, and a book counts
     * as gone for a week afterwards ([GONE_RECHECK_MS]): it is only noted ([missing]); only a second one, at the earliest
     * [CONFIRM_GONE_MS] later, confirms it. The caller clears the note when the book is back or confirmed gone.
     */
    private suspend fun confirmMissing(key: String, missing: Map<String, Long>, now: Long): Boolean {
        val since = missing[key]
        if (since == null) settings.setArdMissing(key, now)
        return since != null && now - since >= CONFIRM_GONE_MS
    }

    /**
     * Adds the books of a subscribed show that the sync didn't know yet (at most [MAX_NEW_PER_CHECK], the rest
     * next time). Anyone who removes a book from the library doesn't get it back: it counts as known. A book without parts at ARD
     * Sounds (announced) stays unknown until it has some. @throws IOException if something couldn't be fetched.
     */
    private suspend fun takeNew(subscription: ArdSubscription): List<NewBook> {
        val seen = settings.ardSeen(subscription.showId)
        val unseen = api.showBooks(subscription.showId).filter { it.key !in seen }
        val taken = mutableListOf<NewBook>()
        val handled = mutableListOf<String>()
        try {
            for (summary in unseen) {
                if (taken.size >= MAX_NEW_PER_CHECK) break
                if (dao.bookByRemote(ARD_BOOK_SERVER_ID, summary.key) != null) {
                    handled += summary.key
                    continue
                }
                val bookId = add(summary.key) ?: continue
                taken += NewBook(bookId, summary.title, summary.author, NewShow(subscription.showId, subscription.title))
                handled += summary.key
            }
        } finally {
            settings.addArdSeen(subscription.showId, handled)
        }
        return taken
    }

    /**
     * A book without published parts (not even under a new key): it stays with all its parts, the ones not downloaded now count as no longer available
     * and the book as expired.
     */
    private suspend fun markGone(existing: BookEntity) {
        val now = System.currentTimeMillis()
        val parts = mergeArdParts(knownParts(existing.bookId), offered = emptyList(), now)
        val until = ardAvailableUntil(parts, whenAllLoaded = existing.availableUntil?.coerceAtMost(now) ?: now)
        if (until != existing.availableUntil) dao.updateBook(existing.copy(availableUntil = until))
        saveParts(existing.bookId, parts)
    }

    /**
     * Stores the book. A started book (progress, bookmarks or downloads) keeps its parts in their place and
     * gets new ones appended at the end ([mergeArdParts]): parts expire individually at ARD Sounds, and otherwise position,
     * bookmarks, chapters and the mapping of downloaded files would shift. Likewise one from a backup with the old device's parts
     * ([saved]). A book not started yet takes the parts as they are.
     */
    private suspend fun store(book: ArdBook, existing: BookEntity?, addedAt: Long? = null, saved: List<ArdBookPart> = emptyList()): Long {
        val known = existing?.let { knownParts(it.bookId) }.orEmpty()
        val parts = when {
            existing != null && known.isNotEmpty() && isStarted(existing.bookId, known) -> mergeArdParts(known, book.parts, System.currentTimeMillis())
            existing == null && saved.isNotEmpty() -> mergeArdParts(saved, book.parts, System.currentTimeMillis())
            else -> freshArdParts(book.parts)
        }
        val entity = BookEntity(
            bookId = existing?.bookId ?: 0,
            serverId = ARD_BOOK_SERVER_ID,
            kind = BookKind.BOOK,
            remoteId = book.key,
            title = book.title,
            normTitle = book.title.normalizeForMerge(),
            author = book.author,
            narrator = book.narrator,
            description = book.description,
            durationMs = parts.sumOf { it.track.durationMs },
            addedAt = existing?.addedAt ?: addedAt ?: System.currentTimeMillis(),
            // The files come with the book; the servers' sync shouldn't try to fetch them.
            detailsLoaded = true,
            coverUrl = book.imageUrl,
            webUrl = book.webUrl,
            publisher = book.publisher.ifEmpty { null },
            genres = book.category?.let(::genreOf),
            availableUntil = ardAvailableUntil(parts, whenAllLoaded = book.availableUntil),
        )
        val bookId = if (existing == null) dao.upsertBook(entity) else existing.bookId.also { if (entity != existing) dao.updateBook(entity) }
        saveParts(bookId, parts)
        if (existing != null && existing.coverUrl != book.imageUrl) covers.invalidate(bookId)
        return bookId
    }

    /** The parts of the book in the library, with the chapter names (with only one part there is no chapter). */
    private suspend fun knownParts(bookId: Long): List<ArdBookPart> {
        val titles = dao.chapters(bookId).associate { it.chapterIndex to it.title }
        return dao.tracks(bookId).sortedBy { it.trackIndex }.map { ArdBookPart(it, titles[it.trackIndex].orEmpty(), it.unavailableSince) }
    }

    private suspend fun isStarted(bookId: Long, known: List<ArdBookPart>): Boolean {
        if (known.any { it.track.downloadState in STARTED_DOWNLOADS }) return true
        val progress = dao.progress(bookId)
        if (progress != null && (progress.finished || progress.positionMs > 0)) return true
        return dao.bookmarksOf(listOf(bookId)).any { !it.deleted }
    }

    /** Writes files and chapters (one per part, from two parts on) if something changed. */
    private suspend fun saveParts(bookId: Long, parts: List<ArdBookPart>) {
        val tracks = parts.map { it.track.copy(bookId = bookId) }
        val chapters = if (parts.size < 2) {
            emptyList()
        } else {
            parts.mapIndexed { index, part ->
                BookChapterEntity(bookId, index, part.title, part.track.startMs, part.track.startMs + part.track.durationMs)
            }
        }
        // The download state doesn't count: [BookDao.replaceDetails] keeps it for unchanged files anyway.
        fun layout(list: List<BookTrackEntity>) =
            list.sortedBy { it.trackIndex }.map {
                listOf(it.trackIndex, it.remoteFileId, it.startMs, it.durationMs, it.mimeType, it.unavailableSince, it.remoteItemId)
            }
        if (layout(dao.tracks(bookId)) != layout(tracks) || dao.chapters(bookId).sortedBy { it.chapterIndex } != chapters) {
            dao.replaceDetails(bookId, tracks, chapters)
        }
    }

    private companion object {
        const val TAG = "ArdBooks"
        const val MAX_NEW_PER_CHECK = 20

        /** How long an expired book is not queried again at ARD Sounds (it rarely comes back). */
        const val GONE_RECHECK_MS = 7 * 24 * 60 * 60 * 1000L

        /** At the earliest this long after the first empty response, a second one confirms that ARD Sounds no longer lists a book. */
        const val CONFIRM_GONE_MS = 6 * 60 * 60 * 1000L

        /** Downloads that make a book count as started. */
        val STARTED_DOWNLOADS = setOf(TrackDownload.DONE, TrackDownload.QUEUED, TrackDownload.RUNNING)

        /** The category as genre, as ARD Sounds names it. */
        fun genreOf(category: ArdCategory): String = when (category) {
            ArdCategory.AUDIOBOOK -> "Hörbuch"
            ArdCategory.RADIO_PLAY -> "Hörspiel"
        }
    }
}
