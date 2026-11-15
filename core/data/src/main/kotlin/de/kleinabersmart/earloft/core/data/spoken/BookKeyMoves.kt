package de.kleinabersmart.earloft.core.data.spoken

import dagger.Lazy
import de.kleinabersmart.earloft.core.data.backup.UserDataBackup
import de.kleinabersmart.earloft.core.data.podcast.rekeyStats
import de.kleinabersmart.earloft.core.database.dao.StatsDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bekommt ein Buch einen neuen Schlüssel (`remoteId`, etwa bei ARD Sounds über [ArdSoundsBooks.movedKey][de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsBooks.movedKey]),
 * zieht alles mit, was es außerhalb von `books` unter dem alten führt: Warteschlange und „zuletzt gespielt“ ([BookKeyRenamer]),
 * die Statistik (Gruppe = Server, Eintrag = `remoteId`) und den gesicherten Fortschritt in `user.db`.
 */
fun interface BookKeyMoves {
    /** [renames]: alte → neue `remoteId` von Büchern des Servers [serverId]; ihre `bookId` bleibt. */
    suspend fun moved(serverId: String, renames: Map<String, String>)
}

@Singleton
internal class BookKeyFollower @Inject constructor(
    private val statsDao: StatsDao,
    /** `Lazy`: [UserDataBackup] braucht selbst [ArdBooks]. */
    private val userDataBackup: Lazy<UserDataBackup>,
    private val renamers: Lazy<Set<@JvmSuppressWildcards BookKeyRenamer>>,
) : BookKeyMoves {
    override suspend fun moved(serverId: String, renames: Map<String, String>) {
        if (renames.isEmpty()) return
        val groups = listOf(serverId)
        // Die Tage zählen je Server, nicht je Buch: Sie bleiben, wie sie sind.
        statsDao.replaceGroups(groups, rekeyStats(statsDao.statsOfGroups(groups), serverId, serverId, renames), statsDao.daysOfGroups(groups))
        val keys = renames.entries.associate { (old, new) -> BookKeys.encode(serverId, old) to BookKeys.encode(serverId, new) }
        renamers.get().forEach { it.renameBookKeys(keys) }
        userDataBackup.get().forgetProgress(serverId, renames.keys)
    }
}
