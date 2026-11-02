package keinabersmart.kleinaberlaut.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import keinabersmart.kleinaberlaut.core.database.entity.ListeningStatEntity
import kotlinx.coroutines.flow.Flow

/** Gehörte Zeit je Gruppe (Podcast bzw. Server), summiert über alle Einträge. */
data class GroupListeningRow(
    val groupKey: String,
    val groupTitle: String,
    val listenedMs: Long,
    val items: Int,
    val lastListenedAt: Long,
)

@Dao
abstract class StatsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(stats: List<ListeningStatEntity>)

    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM listening_stats")
    abstract fun observeTotalListenedMs(): Flow<Long>

    /** Pro Gruppe die Summe, die meistgehörte zuerst. Der Titel ist der der zuletzt gehörten Zeile. */
    @Query(
        "SELECT groupKey, " +
            "(SELECT s2.groupTitle FROM listening_stats s2 WHERE s2.groupKey = s.groupKey ORDER BY s2.lastListenedAt DESC LIMIT 1) AS groupTitle, " +
            "SUM(listenedMs) AS listenedMs, COUNT(*) AS items, MAX(lastListenedAt) AS lastListenedAt " +
            "FROM listening_stats s GROUP BY groupKey ORDER BY listenedMs DESC",
    )
    abstract fun observeByGroup(): Flow<List<GroupListeningRow>>

    @Query("SELECT * FROM listening_stats")
    abstract suspend fun all(): List<ListeningStatEntity>
}
