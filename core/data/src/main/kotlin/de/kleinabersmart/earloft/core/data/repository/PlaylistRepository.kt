package de.kleinabersmart.earloft.core.data.repository

import de.kleinabersmart.earloft.core.common.foldedSortKey
import androidx.room.withTransaction
import de.kleinabersmart.earloft.core.data.sync.SyncScheduler
import de.kleinabersmart.earloft.core.data.sync.replaceAction
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.PlaylistDao
import de.kleinabersmart.earloft.core.database.dao.SyncDao
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntryEntity
import de.kleinabersmart.earloft.core.database.model.PlaylistRow
import de.kleinabersmart.earloft.core.model.Playlist
import de.kleinabersmart.earloft.core.model.PlaylistDetails
import de.kleinabersmart.earloft.core.model.PlaylistEntry
import de.kleinabersmart.earloft.core.model.SyncActionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playlists (concept 5.4/6.4): local or linked to exactly one server. Every change to a
 * server playlist enqueues an `UPSERT_PLAYLIST` that sends the complete state.
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val database: EarLoftDatabase,
    private val playlistDao: PlaylistDao,
    private val syncDao: SyncDao,
    private val scheduler: SyncScheduler,
) {
    /** By name without case and accents (review G14). */
    fun playlists(): Flow<List<Playlist>> =
        playlistDao.observePlaylistRows().map { rows -> rows.sortedBy { it.name.foldedSortKey() }.map { it.toPlaylist() } }

    /** `null` once the playlist is deleted. */
    fun playlist(playlistId: Long): Flow<PlaylistDetails?> =
        combine(playlistDao.observePlaylistRow(playlistId), playlistDao.observeEntryRows(playlistId)) { row, entries ->
            row?.let {
                PlaylistDetails(
                    playlist = it.toPlaylist(),
                    entries = entries.map { entry -> PlaylistEntry(entry.entryId, entry.song.toSong(), entry.missingOnServer) },
                )
            }
        }

    /** @param serverSourceId Server on which the playlist is created; `null` = only on this device. */
    suspend fun create(name: String, serverSourceId: String?, songIds: List<Long> = emptyList()): Long =
        change {
            val playlistId = playlistDao.insertPlaylist(
                PlaylistEntity(name = name, boundSourceId = serverSourceId, updatedAt = System.currentTimeMillis()),
            )
            appendEntries(playlistId, songIds)
            playlistId
        }

    suspend fun rename(playlistId: Long, name: String) {
        change {
            playlistDao.rename(playlistId, name, System.currentTimeMillis())
            playlistId
        }
    }

    suspend fun addSongs(playlistId: Long, songIds: List<Long>) {
        if (songIds.isEmpty()) return
        change {
            appendEntries(playlistId, songIds)
            playlistDao.touch(playlistId, System.currentTimeMillis())
            playlistId
        }
    }

    suspend fun removeEntry(playlistId: Long, entryId: Long) {
        change {
            playlistDao.deleteEntry(entryId)
            playlistDao.touch(playlistId, System.currentTimeMillis())
            playlistId
        }
    }

    /**
     * New order: [entryIds] in the desired sequence (entries, not songs – a song can appear more than once).
     * Entries not named by [entryIds] (e.g. added by a sync while sorting) follow them in their order; they used to stay at the
     * front and jumped there on "Save" or "Undo" (review5 P60). A server playlist is resent as a whole.
     */
    suspend fun reorder(playlistId: Long, entryIds: List<Long>) {
        if (entryIds.isEmpty()) return
        change {
            val named = entryIds.toHashSet()
            val order = entryIds + playlistDao.getEntryIds(playlistId).filter { it !in named }
            // After all previous positions, so no position is assigned twice.
            val start = playlistDao.nextPosition(playlistId)
            order.forEachIndexed { index, entryId -> playlistDao.setPosition(playlistId, entryId, start + index) }
            playlistDao.touch(playlistId, System.currentTimeMillis())
            playlistId
        }
    }

    /** It is deleted on the server as well – if it had already been created there. */
    suspend fun delete(playlistId: Long) {
        val queued = database.withTransaction {
            val playlist = playlistDao.getPlaylist(playlistId) ?: return@withTransaction false
            playlistDao.deletePlaylist(playlistId)
            val sourceId = playlist.boundSourceId ?: return@withTransaction false
            val key = playlistId.toString()
            syncDao.deleteActions(sourceId, SyncActionType.UPSERT_PLAYLIST, key)
            val remoteId = playlist.remotePlaylistId ?: return@withTransaction false
            syncDao.replaceAction(sourceId, SyncActionType.DELETE_PLAYLIST, key, remoteId)
            true
        }
        if (queued) scheduler.enqueue()
    }

    /** Runs [block] in a transaction and enqueues sending for server playlists. */
    private suspend fun change(block: suspend () -> Long): Long {
        var queued = false
        val playlistId = database.withTransaction {
            val playlistId = block()
            val sourceId = playlistDao.getPlaylist(playlistId)?.boundSourceId
            if (sourceId != null) {
                syncDao.replaceAction(sourceId, SyncActionType.UPSERT_PLAYLIST, playlistId.toString(), null)
                queued = true
            }
            playlistId
        }
        if (queued) scheduler.enqueue()
        return playlistId
    }

    private suspend fun appendEntries(playlistId: Long, songIds: List<Long>) {
        val start = playlistDao.nextPosition(playlistId)
        playlistDao.insertEntries(
            songIds.mapIndexed { index, songId -> PlaylistEntryEntity(playlistId = playlistId, songId = songId, position = start + index) },
        )
    }

    private fun PlaylistRow.toPlaylist() = Playlist(
        id = playlistId,
        name = name,
        songCount = songCount,
        durationMs = durationMs,
        serverName = serverName,
        unknownSongs = unknownSongs,
    )
}
