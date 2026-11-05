package de.kleinabersmart.kleinaberlaut.core.data.spoken

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import de.kleinabersmart.kleinaberlaut.core.database.dao.BookDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schlüssel eines Buchs bzw. einer Folge, der auf einem neuen Gerät noch passt: `(serverId, remoteId)` statt der `bookId`,
 * die ein neues Gerät neu vergibt (Backup, Review K9). Für Einstellungsdateien, die mit dem Backup mitkommen.
 */
@Singleton
class BookKeys @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val bookDao: BookDao,
) {
    suspend fun keyOf(bookId: Long): String? = bookDao.bookRefsOf(listOf(bookId)).singleOrNull()?.let { encode(it.serverId, it.remoteId) }

    /** Die `bookId` hier; `null`, solange es das Buch nicht (mehr) gibt. */
    suspend fun idOf(key: String): Long? {
        val parts = key.split(SEPARATOR, limit = 2).takeIf { it.size == 2 } ?: return null
        return bookDao.bookRefs(parts[0], listOf(parts[1])).singleOrNull()?.bookId
    }

    /**
     * Schlüssel für eine `bookId` aus einer älteren Datei. Hat diese Installation die Datenbank selbst angelegt, kam die
     * Datei mit einem Backup und die Id gehört zu einem anderen Gerät: `null`.
     */
    suspend fun keyOfLegacyId(bookId: Long): String? =
        keyOf(bookId).takeUnless { KleinaberlautDatabase.createdOnThisInstall(context) }

    companion object {
        /** Steuerzeichen, das weder in einer guid (XML) noch in einem Pfad oder einer Server-Kennung vorkommt. */
        const val SEPARATOR = "\u001F"

        fun encode(serverId: String, remoteId: String) = serverId + SEPARATOR + remoteId
    }
}
