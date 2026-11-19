package de.kleinabersmart.earloft.core.data.spoken

import dagger.Lazy
import de.kleinabersmart.earloft.core.data.backup.UserDataBackup
import de.kleinabersmart.earloft.core.data.podcast.rekeyStats
import de.kleinabersmart.earloft.core.database.dao.StatsDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When a book gets a new key (`remoteId`, e.g. on ARD Sounds via [ArdSoundsBooks.movedKey][de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsBooks.movedKey]),
 * moves along everything that refers to it under the old one outside of `books`: queue and "recently played" ([BookKeyRenamer]),
 * the statistics (group = server, entry = `remoteId`) and the backed-up progress in `user.db`.
 */
fun interface BookKeyMoves {
    /** [renames]: old → new `remoteId` of books of server [serverId]; their `bookId` stays. */
    suspend fun moved(serverId: String, renames: Map<String, String>)
}

@Singleton
internal class BookKeyFollower @Inject constructor(
    private val statsDao: StatsDao,
    /** `Lazy`: [UserDataBackup] itself needs [ArdBooks]. */
    private val userDataBackup: Lazy<UserDataBackup>,
    private val renamers: Lazy<Set<@JvmSuppressWildcards BookKeyRenamer>>,
) : BookKeyMoves {
    override suspend fun moved(serverId: String, renames: Map<String, String>) {
        if (renames.isEmpty()) return
        val groups = listOf(serverId)
        // The days count per server, not per book: they stay as they are.
        statsDao.replaceGroups(groups, rekeyStats(statsDao.statsOfGroups(groups), serverId, serverId, renames), statsDao.daysOfGroups(groups))
        val keys = renames.entries.associate { (old, new) -> BookKeys.encode(serverId, old) to BookKeys.encode(serverId, new) }
        renamers.get().forEach { it.renameBookKeys(keys) }
        userDataBackup.get().forgetProgress(serverId, renames.keys)
    }
}
