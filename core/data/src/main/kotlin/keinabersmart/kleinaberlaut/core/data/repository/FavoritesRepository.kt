package keinabersmart.kleinaberlaut.core.data.repository

import androidx.room.withTransaction
import keinabersmart.kleinaberlaut.core.data.sync.SyncScheduler
import keinabersmart.kleinaberlaut.core.data.sync.replaceAction
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import keinabersmart.kleinaberlaut.core.database.dao.SyncDao
import keinabersmart.kleinaberlaut.core.model.Song
import keinabersmart.kleinaberlaut.core.model.SyncActionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bringt alle Server-Quellen eines Songs auf den gewünschten Favoriten-Zustand (Konzept 6.4:
 * Sternen eines logischen Songs geht an **alle** Server). Ältere, noch nicht gesendete Änderungen
 * werden ersetzt; stimmt der Server schon überein (z. B. Stern und gleich wieder entfernt), wird
 * nichts gesendet. Innerhalb einer Transaktion aufrufen.
 *
 * @return `true`, wenn danach Aktionen ausstehen.
 */
internal suspend fun SyncDao.pushFavoriteToServers(songId: Long, favorite: Boolean, exceptUid: String? = null): Boolean {
    var pending = false
    for (source in getServerSources(songId)) {
        if (source.uid == exceptUid) continue
        deleteActions(source.sourceId, SyncActionType.SET_FAVORITE, source.uid)
        if ((source.starredAt != null) != favorite) {
            replaceAction(source.sourceId, SyncActionType.SET_FAVORITE, source.uid, favorite.toString())
            pending = true
        }
    }
    return pending
}

@Singleton
class FavoritesRepository @Inject constructor(
    private val database: KleinaberlautDatabase,
    private val libraryDao: LibraryDao,
    private val syncDao: SyncDao,
    private val scheduler: SyncScheduler,
) {
    fun favorites(): Flow<List<Song>> = libraryDao.observeFavoriteSongRows().map { rows -> rows.map { it.toSong() } }

    fun isFavorite(songId: Long): Flow<Boolean> = libraryDao.observeIsFavorite(songId).map { it == true }.distinctUntilChanged()

    suspend fun setFavorite(songId: Long, favorite: Boolean) {
        val pending = database.withTransaction {
            syncDao.setSongFavorite(songId, favorite, System.currentTimeMillis())
            syncDao.pushFavoriteToServers(songId, favorite)
        }
        if (pending) scheduler.enqueue()
    }

    suspend fun toggleFavorite(songId: Long) {
        setFavorite(songId, syncDao.isSongFavorite(songId) != true)
    }
}
