package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import de.kleinabersmart.earloft.core.database.entity.ListeningDayEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningStatEntity
import de.kleinabersmart.earloft.core.database.entity.ServerStatsCacheEntity
import de.kleinabersmart.earloft.core.model.BookKind
import kotlinx.coroutines.flow.Flow

/** Gehörte Zeit je Gruppe (Podcast bzw. Server), summiert über alle Einträge. */
data class GroupListeningRow(
    val groupKey: String,
    val groupTitle: String,
    val listenedMs: Long,
    val items: Int,
    val lastListenedAt: Long,
)

/** Gehörte Zeit eines Eintrags (Buch bzw. Folge), summiert über alle Quellen. */
data class ItemListeningRow(val groupKey: String, val itemKey: String, val itemTitle: String, val listenedMs: Long)

/** Gehörte Zeit an einem Tag, summiert über alle Gruppen; [savedMs] durch „Stille kürzen“ gespart. */
data class DayListeningRow(val day: Long, val listenedMs: Long, val savedMs: Long = 0)

/** Gehörte Zeit einer Gruppe in einem Zeitraum (aus den Tageswerten). */
data class GroupDayRow(val groupKey: String, val listenedMs: Long)

@Dao
abstract class StatsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(stats: List<ListeningStatEntity>)

    /** Legt fehlende Einträge an; vorhandene (gleiches `source`, `groupKey`, `itemKey`) bleiben, wie sie sind. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAllIfAbsent(stats: List<ListeningStatEntity>)

    /** Wie [insertAllIfAbsent], für die Tage. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertDaysIfAbsent(days: List<ListeningDayEntity>)

    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM listening_stats WHERE kind = :kind")
    abstract fun observeTotalListenedMs(kind: BookKind): Flow<Long>

    /** Pro Gruppe die Summe, die meistgehörte zuerst. Der Titel ist der der zuletzt gehörten Zeile. */
    @Query(
        "SELECT groupKey, " +
            "(SELECT s2.groupTitle FROM listening_stats s2 WHERE s2.groupKey = s.groupKey AND s2.kind = :kind " +
            "ORDER BY s2.lastListenedAt DESC LIMIT 1) AS groupTitle, " +
            "SUM(listenedMs) AS listenedMs, COUNT(DISTINCT itemKey) AS items, MAX(lastListenedAt) AS lastListenedAt " +
            "FROM listening_stats s WHERE kind = :kind GROUP BY groupKey ORDER BY listenedMs DESC",
    )
    abstract fun observeByGroup(kind: BookKind): Flow<List<GroupListeningRow>>

    /** Die meistgehörten Einträge der Art [kind], die meistgehörten zuerst. */
    @Query(
        "SELECT groupKey, itemKey, MAX(itemTitle) AS itemTitle, SUM(listenedMs) AS listenedMs FROM listening_stats " +
            "WHERE kind = :kind GROUP BY groupKey, itemKey ORDER BY listenedMs DESC LIMIT :limit",
    )
    abstract fun observeTopItems(kind: BookKind, limit: Int): Flow<List<ItemListeningRow>>

    /** Zu Ende gehörte Bücher (alle Server und vom Gerät). */
    @Query(
        "SELECT COUNT(*) FROM book_progress p JOIN books b ON b.bookId = p.bookId " +
            "WHERE b.kind = 'BOOK' AND p.finished = 1",
    )
    abstract fun observeFinishedBooks(): Flow<Int>

    /** Zu Ende gehörte Folgen (alle Abos, auch die vom Gerät). */
    @Query(
        "SELECT COUNT(*) FROM book_progress p JOIN books b ON b.bookId = p.bookId " +
            "WHERE b.kind = 'EPISODE' AND p.finished = 1",
    )
    abstract fun observeFinishedEpisodes(): Flow<Int>

    /** Wie [observeFinishedEpisodes], nur Einträge der Art [kind], deren Fortschritt in [fromMs] (einschließlich) bis [toMs] (ausschließlich) zuletzt geändert wurde. */
    @Query(
        "SELECT COUNT(*) FROM book_progress p JOIN books b ON b.bookId = p.bookId " +
            "WHERE b.kind = :kind AND p.finished = 1 AND p.updatedAt >= :fromMs AND p.updatedAt < :toMs",
    )
    abstract fun observeFinishedBetween(kind: BookKind, fromMs: Long, toMs: Long): Flow<Int>

    /** Pro Gruppe die Summe der Tage [fromDay] bis [toDay] (jeweils einschließlich), die meistgehörte zuerst. */
    @Query(
        "SELECT groupKey, SUM(listenedMs) AS listenedMs FROM listening_days " +
            "WHERE kind = :kind AND day BETWEEN :fromDay AND :toDay GROUP BY groupKey ORDER BY listenedMs DESC",
    )
    abstract fun observeGroupDays(kind: BookKind, fromDay: Long, toDay: Long): Flow<List<GroupDayRow>>

    @Query("SELECT * FROM listening_stats")
    abstract suspend fun all(): List<ListeningStatEntity>

    @Query(
        "SELECT day, SUM(listenedMs) AS listenedMs, SUM(savedMs) AS savedMs FROM listening_days " +
            "WHERE kind = :kind AND day >= :sinceDay GROUP BY day",
    )
    abstract fun observeDays(kind: BookKind, sinceDay: Long): Flow<List<DayListeningRow>>

    @Query("SELECT * FROM listening_days")
    abstract suspend fun allDays(): List<ListeningDayEntity>

    @Query(
        "UPDATE listening_stats SET listenedMs = listenedMs + :ms, lastListenedAt = :at, groupTitle = :groupTitle, itemTitle = :itemTitle " +
            "WHERE source = :source AND groupKey = :groupKey AND itemKey = :itemKey",
    )
    protected abstract suspend fun addToStat(
        source: String,
        groupKey: String,
        itemKey: String,
        groupTitle: String,
        itemTitle: String,
        ms: Long,
        at: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertStat(stat: ListeningStatEntity): Long

    @Query(
        "UPDATE listening_days SET listenedMs = listenedMs + :ms, savedMs = savedMs + :savedMs " +
            "WHERE day = :day AND kind = :kind AND groupKey = :groupKey",
    )
    protected abstract suspend fun addToDay(day: Long, kind: BookKind, groupKey: String, ms: Long, savedMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertDay(day: ListeningDayEntity): Long

    /**
     * Zählt [stat] zur Summe seines Eintrags (wird bei Bedarf angelegt) und zur Summe seines Tages [day] hinzu.
     * [ListeningStatEntity.listenedMs] ist der Zuwachs, [savedMs] die dabei durch „Stille kürzen“ gesparte Zeit.
     */
    @Transaction
    open suspend fun addListening(stat: ListeningStatEntity, day: Long, savedMs: Long = 0) {
        val updated = addToStat(
            stat.source, stat.groupKey, stat.itemKey, stat.groupTitle, stat.itemTitle, stat.listenedMs, stat.lastListenedAt,
        )
        if (updated == 0) insertStat(stat)
        if (addToDay(day, stat.kind, stat.groupKey, stat.listenedMs, savedMs) == 0) {
            insertDay(ListeningDayEntity(day, stat.kind, stat.groupKey, stat.listenedMs, savedMs))
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putServerStats(cache: ServerStatsCacheEntity)

    @Query("SELECT * FROM server_stats_cache WHERE serverId = :serverId AND kind = :kind")
    abstract fun observeServerStats(serverId: String, kind: String): Flow<ServerStatsCacheEntity?>

    @Query("SELECT * FROM server_stats_cache WHERE serverId = :serverId AND kind = :kind")
    abstract suspend fun serverStats(serverId: String, kind: String): ServerStatsCacheEntity?

    @Query("DELETE FROM listening_stats WHERE kind = :kind")
    protected abstract suspend fun deleteStats(kind: BookKind)

    @Query("DELETE FROM listening_days WHERE kind = :kind")
    protected abstract suspend fun deleteDays(kind: BookKind)

    /** Löscht alle Zahlen der Art [kind] (gezählte und importierte). */
    @Transaction
    open suspend fun clear(kind: BookKind) {
        deleteStats(kind)
        deleteDays(kind)
    }

    @Query("SELECT * FROM listening_stats WHERE groupKey IN (:groupKeys)")
    abstract suspend fun statsOfGroups(groupKeys: List<String>): List<ListeningStatEntity>

    @Query("SELECT * FROM listening_days WHERE groupKey IN (:groupKeys)")
    abstract suspend fun daysOfGroups(groupKeys: List<String>): List<ListeningDayEntity>

    @Query("DELETE FROM listening_stats WHERE groupKey IN (:groupKeys)")
    protected abstract suspend fun deleteStatsOfGroups(groupKeys: List<String>)

    @Query("DELETE FROM listening_days WHERE groupKey IN (:groupKeys)")
    protected abstract suspend fun deleteDaysOfGroups(groupKeys: List<String>)

    /** Ersetzt alle Zahlen der Gruppen [groupKeys] durch [stats] und [days] (Quelle eines Abos wechseln, Konzept 12.10). */
    @Transaction
    open suspend fun replaceGroups(groupKeys: List<String>, stats: List<ListeningStatEntity>, days: List<ListeningDayEntity>) {
        deleteStatsOfGroups(groupKeys)
        deleteDaysOfGroups(groupKeys)
        upsertAll(stats)
        insertDaysIfAbsent(days)
    }
}
