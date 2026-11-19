package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import de.kleinabersmart.earloft.core.database.entity.PlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.QueueItemEntity
import kotlinx.coroutines.flow.Flow

/** Persisted queue and listening history. */
@Dao
abstract class PlaybackDao {

    @Query("SELECT songId FROM queue_items ORDER BY position")
    abstract suspend fun getQueueSongIds(): List<Long>

    @Query("SELECT songId FROM queue_items ORDER BY position")
    abstract fun observeQueueSongIds(): Flow<List<Long>>

    @Query("DELETE FROM queue_items")
    protected abstract suspend fun clearQueue()

    @Insert
    protected abstract suspend fun insertQueueItems(items: List<QueueItemEntity>)

    /** Replaces the queue; songs that don't exist (anymore) are skipped. */
    @Transaction
    open suspend fun replaceQueue(songIds: List<Long>) {
        clearQueue()
        val existing = songIds.chunked(900).flatMap { existingSongIds(it) }.toSet()
        insertQueueItems(
            songIds.filter { it in existing }.mapIndexed { index, songId -> QueueItemEntity(index, songId) },
        )
    }

    @Query("SELECT songId FROM songs WHERE songId IN (:songIds)")
    protected abstract suspend fun existingSongIds(songIds: List<Long>): List<Long>

    @Insert
    protected abstract suspend fun insertPlayEvent(event: PlayEventEntity)

    @Query("SELECT EXISTS (SELECT 1 FROM songs WHERE songId = :songId)")
    protected abstract suspend fun songExists(songId: Long): Boolean

    /**
     * Only if the song still exists: a server that was just removed takes its songs with it, and the player reports them afterwards.
     * @return `false` if the song is missing.
     */
    @Transaction
    open suspend fun insertPlayEventIfSongExists(event: PlayEventEntity): Boolean {
        if (!songExists(event.songId)) return false
        insertPlayEvent(event)
        return true
    }
}
