package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.LOCAL_BOOK_SERVER_ID

/**
 * A book on the device merged from its disc folders (review5 P35) takes over, when it is first created, what the disc folders had as
 * books of their own ("X/CD 1/"): the newest progress, moved by the length of the discs before it, and the bookmarks of all of them
 * (review5 8.12). Otherwise it would start at 0, and the old books go with the next sweep. A disc is found by its first file in
 * [tracks] of the merged book [bookId] with the key [key]. @return how many disc books it took over.
 */
internal suspend fun BookDao.adoptDiscBooks(bookId: Long, key: String, tracks: List<BookTrackEntity>, durationMs: Long): Int {
    if (!key.endsWith("/")) return 0
    val startOf = tracks.associate { it.remoteFileId to it.startMs }
    val discs = booksOf(LOCAL_BOOK_SERVER_ID)
        .filter { it.bookId != bookId && it.remoteId.length > key.length && it.remoteId.startsWith(key) }
        .mapNotNull { disc -> tracks(disc.bookId).firstOrNull()?.let { startOf[it.remoteFileId] }?.let { disc to it } }
    if (discs.isEmpty()) return 0
    val newest = discs.mapNotNull { (disc, offset) -> progress(disc.bookId)?.let { Triple(disc, offset, it) } }
        .maxByOrNull { it.third.updatedAt }
    if (newest != null) {
        val (disc, offset, progress) = newest
        // A finished disc continues at the start of the next one.
        val position = (offset + if (progress.finished) disc.durationMs else progress.positionMs).coerceAtMost(durationMs)
        val finished = progress.finished && position >= durationMs
        upsertProgress(
            progress.copy(
                bookId = bookId, positionMs = position, durationMs = durationMs, finished = finished, dirty = false, hidden = false,
                finishedAt = progress.finishedAt.takeIf { finished },
            ),
        )
    }
    val offsets = discs.associate { (disc, offset) -> disc.bookId to offset }
    val taken = bookmarksOf(listOf(bookId)).mapTo(HashSet()) { it.positionMs }
    bookmarksOf(offsets.keys.toList()).filter { !it.deleted }.forEach { mark ->
        val position = offsets.getValue(mark.bookId) + mark.positionMs
        if (taken.add(position)) insertBookmark(mark.copy(bookmarkId = 0, bookId = bookId, positionMs = position, remoteTime = null, dirty = false))
    }
    return discs.size
}
