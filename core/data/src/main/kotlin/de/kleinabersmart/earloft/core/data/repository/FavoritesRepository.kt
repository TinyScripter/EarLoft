package de.kleinabersmart.earloft.core.data.repository

import androidx.room.withTransaction
import de.kleinabersmart.earloft.core.data.sync.SyncScheduler
import de.kleinabersmart.earloft.core.data.sync.replaceAction
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.dao.SyncDao
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.Artist
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.core.model.SyncActionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings all server sources of a song to the desired favorite state (concept 6.4:
 * starring a logical song goes to **all** servers). Older, not yet sent changes
 * are replaced; if the server already agrees (e.g. starred and immediately removed again),
 * nothing is sent. Call within a transaction.
 *
 * @return `true` if actions are pending afterwards.
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
    private val database: EarLoftDatabase,
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

    /** Favorite albums, most recently favorited first. */
    fun favoriteAlbums(): Flow<List<Album>> = libraryDao.observeFavoriteAlbumRows().map { rows -> rows.map { it.toAlbum() } }

    fun favoriteArtists(): Flow<List<Artist>> = libraryDao.observeFavoriteArtistRows().map { rows -> rows.map { it.toArtist() } }

    fun favoriteAlbumIds(): Flow<Set<Long>> = libraryDao.observeFavoriteAlbumIds().map { it.toSet() }.distinctUntilChanged()

    fun favoriteArtistIds(): Flow<Set<Long>> = libraryDao.observeFavoriteArtistIds().map { it.toSet() }.distinctUntilChanged()

    fun isAlbumFavorite(albumId: Long): Flow<Boolean> =
        libraryDao.observeIsAlbumFavorite(albumId).map { it == true }.distinctUntilChanged()

    fun isArtistFavorite(artistId: Long): Flow<Boolean> =
        libraryDao.observeIsArtistFavorite(artistId).map { it == true }.distinctUntilChanged()

    /** Like [setFavorite] for albums ([FavoriteKind.ALBUM]) and artists ([FavoriteKind.ARTIST]); the songs stay as they are. */
    suspend fun setCollectionFavorite(kind: FavoriteKind, id: Long, favorite: Boolean) {
        require(kind != FavoriteKind.SONG)
        val pending = database.withTransaction {
            syncDao.setCollectionFavorite(kind, id, favorite, System.currentTimeMillis())
            syncDao.pushCollectionFavoriteToServers(kind, id, favorite)
        }
        if (pending) scheduler.enqueue()
    }

    suspend fun toggleCollectionFavorite(kind: FavoriteKind, id: Long) {
        setCollectionFavorite(kind, id, syncDao.isCollectionFavorite(kind, id) != true)
    }
}
