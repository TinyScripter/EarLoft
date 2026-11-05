package de.kleinabersmart.tinyamp.core.data.repository

import androidx.room.withTransaction
import de.kleinabersmart.tinyamp.core.data.sync.SyncScheduler
import de.kleinabersmart.tinyamp.core.data.sync.replaceAction
import de.kleinabersmart.tinyamp.core.database.TinyAmpDatabase
import de.kleinabersmart.tinyamp.core.database.dao.PlaylistDao
import de.kleinabersmart.tinyamp.core.database.dao.SyncDao
import de.kleinabersmart.tinyamp.core.database.entity.PlaylistEntity
import de.kleinabersmart.tinyamp.core.database.entity.PlaylistEntryEntity
import de.kleinabersmart.tinyamp.core.database.model.PlaylistRow
import de.kleinabersmart.tinyamp.core.model.Playlist
import de.kleinabersmart.tinyamp.core.model.PlaylistDetails
import de.kleinabersmart.tinyamp.core.model.PlaylistEntry
import de.kleinabersmart.tinyamp.core.model.SyncActionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playlists (Konzept 5.4/6.4): lokal oder mit genau einem Server verknüpft. Jede Änderung an einer
 * Server-Playlist reiht ein `UPSERT_PLAYLIST` ein, das den vollständigen Stand sendet.
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val database: TinyAmpDatabase,
    private val playlistDao: PlaylistDao,
    private val syncDao: SyncDao,
    private val scheduler: SyncScheduler,
) {
    fun playlists(): Flow<List<Playlist>> = playlistDao.observePlaylistRows().map { rows -> rows.map { it.toPlaylist() } }

    /** `null`, sobald die Playlist gelöscht ist. */
    fun playlist(playlistId: Long): Flow<PlaylistDetails?> =
        combine(playlistDao.observePlaylistRow(playlistId), playlistDao.observeEntryRows(playlistId)) { row, entries ->
            row?.let {
                PlaylistDetails(
                    playlist = it.toPlaylist(),
                    entries = entries.map { entry -> PlaylistEntry(entry.entryId, entry.song.toSong(), entry.missingOnServer) },
                )
            }
        }

    /** @param serverSourceId Server, auf dem die Playlist angelegt wird; `null` = nur auf diesem Gerät. */
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

    /** Auf dem Server wird sie ebenfalls gelöscht – falls sie dort schon angelegt war. */
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

    /** Führt [block] in einer Transaktion aus und reiht für Server-Playlists das Senden ein. */
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
    )
}
