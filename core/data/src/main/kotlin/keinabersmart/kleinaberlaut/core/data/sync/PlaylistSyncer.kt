package keinabersmart.kleinaberlaut.core.data.sync

import androidx.room.withTransaction
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.dao.PlaylistDao
import keinabersmart.kleinaberlaut.core.database.dao.SyncDao
import keinabersmart.kleinaberlaut.core.database.entity.PlaylistEntity
import keinabersmart.kleinaberlaut.core.database.entity.PlaylistEntryEntity
import keinabersmart.kleinaberlaut.core.datasource.RemoteLibraryActions
import keinabersmart.kleinaberlaut.core.datasource.RemotePlaylist
import keinabersmart.kleinaberlaut.core.model.SyncActionType
import javax.inject.Inject

/**
 * Übernimmt die Playlists eines Servers nach dem Song-Abgleich (Konzept 6.3/6.4). Wurde eine Playlist
 * auf dem Server seit dem letzten Abgleich geändert, gewinnt der Server – auch gegen noch nicht
 * gesendete lokale Änderungen. Auf dem Server gelöschte Playlists verschwinden auch lokal.
 */
class PlaylistSyncer @Inject constructor(
    private val database: KleinaberlautDatabase,
    private val playlistDao: PlaylistDao,
    private val syncDao: SyncDao,
    private val notices: SyncNotices,
) {
    suspend fun sync(sourceId: String, remote: RemoteLibraryActions) {
        val infos = remote.playlists()
        val known = playlistDao.getBoundTo(sourceId)
        val byRemoteId = known.filter { it.remotePlaylistId != null }.associateBy { it.remotePlaylistId }

        for (info in infos) {
            val local = byRemoteId[info.remoteId]
            if (local != null && local.remoteUpdatedAt == info.changedAt) continue
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
     * Schreibt den Serverstand in die Playlist [playlistId] (bzw. legt sie an) und verwirft ausstehende
     * lokale Änderungen. Songs, die (noch) nicht in der Bibliothek sind, fallen weg. Innerhalb einer
     * Transaktion aufrufen.
     */
    internal suspend fun applyRemote(sourceId: String, playlistId: Long?, playlist: RemotePlaylist): Long {
        val info = playlist.info
        val id = if (playlistId == null) {
            playlistDao.insertPlaylist(
                PlaylistEntity(
                    name = info.name,
                    boundSourceId = sourceId,
                    remotePlaylistId = info.remoteId,
                    updatedAt = info.changedAt,
                    remoteUpdatedAt = info.changedAt,
                ),
            )
        } else {
            playlistDao.applyRemote(playlistId, info.name, info.changedAt)
            playlistDao.deleteEntries(playlistId)
            playlistId
        }
        val songIds = playlist.songRemoteIds.mapNotNull { syncDao.getSongSource("$sourceId:$it")?.songId }
        playlistDao.insertEntries(songIds.mapIndexed { index, songId -> PlaylistEntryEntity(playlistId = id, songId = songId, position = index) })
        val discarded = syncDao.deleteActions(sourceId, SyncActionType.UPSERT_PLAYLIST, id.toString())
        if (discarded > 0) notices.post(SyncNotice.PlaylistReplacedByServer(info.name))
        return id
    }
}
