package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.BookDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Key of a book or episode that still fits on a new device: `(serverId, remoteId)` instead of the `bookId`,
 * which a new device assigns anew (backup, review K9). For settings files that come along with the backup.
 */
@Singleton
class BookKeys @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val bookDao: BookDao,
) {
    suspend fun keyOf(bookId: Long): String? = bookDao.bookRefsOf(listOf(bookId)).singleOrNull()?.let { encode(it.serverId, it.remoteId) }

    /** The `bookId` here; `null` as long as the book doesn't exist (anymore). */
    suspend fun idOf(key: String): Long? {
        val parts = key.split(SEPARATOR, limit = 2).takeIf { it.size == 2 } ?: return null
        return bookDao.bookRefs(parts[0], listOf(parts[1])).singleOrNull()?.bookId
    }

    /**
     * Key for a `bookId` from an older file. If this installation created the database itself, the
     * file came with a backup and the id belongs to another device: `null`.
     */
    suspend fun keyOfLegacyId(bookId: Long): String? =
        keyOf(bookId).takeUnless { EarLoftDatabase.createdOnThisInstall(context) }

    companion object {
        /** Control character that occurs neither in a guid (XML) nor in a path or a server identifier. */
        const val SEPARATOR = "\u001F"

        fun encode(serverId: String, remoteId: String) = serverId + SEPARATOR + remoteId
    }
}
