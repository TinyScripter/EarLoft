package de.kleinabersmart.earloft.core.data.sync

import androidx.room.withTransaction
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.PlaylistDao
import de.kleinabersmart.earloft.core.database.dao.SyncDao
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntryEntity
import de.kleinabersmart.earloft.core.datasource.RemoteLibraryActions
import de.kleinabersmart.earloft.core.datasource.RemotePlaylist
import de.kleinabersmart.earloft.core.model.SyncActionType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Everything that writes the playlists of a server: the playlist sync and sending local changes ([SyncQueueProcessor]). Without
 * it a sync that reads the server list after the server created a playlist, but before the app stored its server ID, adopts
 * it as a second local playlist (review G16).
 */
internal object PlaylistSyncLock {
    val mutex = Mutex()
}

/**
 * Adopts a server's playlists after the song sync (concept 6.3/6.4). If a playlist was changed
 * on the server since the last sync, the server wins – even against local changes not yet
 * sent – except those whose sending already failed once (half-applied state on the
 * server, see [SyncQueueProcessor]). Playlists deleted on the server disappear locally too.
 */
class PlaylistSyncer @Inject constructor(
    private val database: EarLoftDatabase,
    private val playlistDao: PlaylistDao,
    private val syncDao: SyncDao,
    private val notices: SyncNotices,
) {
    /**
     * @param afterFullSync whether every song of the server is known now: only then are playlists adopted again that were
     * stored incomplete ([applyRemote]); after an intermediate sync they would just be incomplete once more.
     */
    suspend fun sync(sourceId: String, remote: RemoteLibraryActions, afterFullSync: Boolean = true) =
        PlaylistSyncLock.mutex.withLock { syncLocked(sourceId, remote, afterFullSync) }

    private suspend fun syncLocked(sourceId: String, remote: RemoteLibraryActions, afterFullSync: Boolean) {
        val infos = remote.playlists()
        val known = playlistDao.getBoundTo(sourceId)
        val byRemoteId = known.filter { it.remotePlaylistId != null }.associateBy { it.remotePlaylistId }
        val retried = syncDao.getRetriedTargets(sourceId, SyncActionType.UPSERT_PLAYLIST).toSet()

        for (info in infos) {
            val local = byRemoteId[info.remoteId]
            if (local != null && (local.remoteUpdatedAt == info.changedAt || local.playlistId.toString() in retried)) continue
            if (local != null && local.remoteUpdatedAt == null && !afterFullSync) continue
            val playlist = remote.playlist(info.remoteId)
            database.withTransaction { applyRemote(sourceId, local?.playlistId, playlist) }
        }

        val remoteIds = infos.mapTo(HashSet()) { it.remoteId }
        database.withTransaction {
            known.filter { it.remotePlaylistId != null && it.remotePlaylistId !in remoteIds }.forEach {
                playlistDao.deletePlaylist(it.playlistId)
                syncDao.deleteActions(sourceId, SyncActionType.UPSERT_PLAYLIST, it.playlistId.toString())
            }
        }
    }

    /**
     * Writes the server state into playlist [playlistId] (or creates it) and discards pending
     * local changes. Songs that aren't in the library (yet) are dropped. Call within a
     * transaction.
     *
     * If songs are missing, the server state is not marked as adopted (`remoteUpdatedAt` stays empty, review G15): the next
     * full sync, which brings the new songs, adopts the playlist again, and a change in the app doesn't send the shortened
     * list – the server's version wins as after a change there.
     */
    internal suspend fun applyRemote(sourceId: String, playlistId: Long?, playlist: RemotePlaylist): Long {
        val info = playlist.info
        val songIds = playlist.songRemoteIds.mapNotNull { syncDao.getSongSource("$sourceId:$it")?.songId }
        val adopted = info.changedAt.takeIf { songIds.size == playlist.songRemoteIds.size }
        val id = if (playlistId == null) {
            playlistDao.insertPlaylist(
                PlaylistEntity(
                    name = info.name,
                    boundSourceId = sourceId,
                    remotePlaylistId = info.remoteId,
                    updatedAt = info.changedAt,
                    remoteUpdatedAt = adopted,
                ),
            )
        } else {
            playlistDao.applyRemote(playlistId, info.name, info.changedAt, adopted)
            playlistDao.deleteEntries(playlistId)
            playlistId
        }
        playlistDao.insertEntries(songIds.mapIndexed { index, songId -> PlaylistEntryEntity(playlistId = id, songId = songId, position = index) })
        val discarded = syncDao.deleteActions(sourceId, SyncActionType.UPSERT_PLAYLIST, id.toString())
        if (discarded > 0) notices.post(SyncNotice.PlaylistReplacedByServer(info.name))
        return id
    }
}
