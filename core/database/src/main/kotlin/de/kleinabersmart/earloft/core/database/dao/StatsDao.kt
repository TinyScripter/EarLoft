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

/** Listening time per group (podcast or server), summed over all entries. */
data class GroupListeningRow(
    val groupKey: String,
    val groupTitle: String,
    val listenedMs: Long,
    val items: Int,
    val lastListenedAt: Long,
)

/** Listening time of an entry (book or episode), summed over all sources. */
data class ItemListeningRow(val groupKey: String, val itemKey: String, val itemTitle: String, val listenedMs: Long)

/** Listening time on one day, summed over all groups; [savedMs] saved by "Trim silence". */
data class DayListeningRow(val day: Long, val listenedMs: Long, val savedMs: Long = 0)

/** Listening time of a group in a period (from the daily values). */
data class GroupDayRow(val groupKey: String, val listenedMs: Long)

@Dao
abstract class StatsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(stats: List<ListeningStatEntity>)

    /** Inserts missing entries; existing ones (same `source`, `groupKey`, `itemKey`) stay as they are. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAllIfAbsent(stats: List<ListeningStatEntity>)

    /** Like [insertAllIfAbsent], for the days. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertDaysIfAbsent(days: List<ListeningDayEntity>)

    @Query(
        "UPDATE listening_stats SET listenedMs = MAX(listenedMs, :listenedMs), lastListenedAt = MAX(lastListenedAt, :lastListenedAt) " +
            "WHERE source = :source AND groupKey = :groupKey AND itemKey = :itemKey",
    )
    protected abstract suspend fun raiseStat(source: String, groupKey: String, itemKey: String, listenedMs: Long, lastListenedAt: Long)

    @Query(
        "UPDATE listening_days SET listenedMs = MAX(listenedMs, :listenedMs), savedMs = MAX(savedMs, :savedMs) " +
            "WHERE day = :day AND kind = :kind AND groupKey = :groupKey",
    )
    protected abstract suspend fun raiseDay(day: Long, kind: BookKind, groupKey: String, listenedMs: Long, savedMs: Long)

    /**
     * Inserts missing entries; for existing ones (same `source`, `groupKey`, `itemKey`) the larger time and the later listen win.
     * Idempotent, unlike adding up: the same backup applied twice counts once. Statistics of a foreign device that were attached
     * to a local server (review3 Q15) would otherwise be dropped where they overlap with local ones.
     */
    @Transaction
    open suspend fun mergeStats(stats: List<ListeningStatEntity>) {
        insertAllIfAbsent(stats)
        stats.forEach { raiseStat(it.source, it.groupKey, it.itemKey, it.listenedMs, it.lastListenedAt) }
    }

    /** Like [mergeStats], for the days. */
    @Transaction
    open suspend fun mergeDays(days: List<ListeningDayEntity>) {
        insertDaysIfAbsent(days)
        days.forEach { raiseDay(it.day, it.kind, it.groupKey, it.listenedMs, it.savedMs) }
    }

    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM listening_stats WHERE kind = :kind")
    abstract fun observeTotalListenedMs(kind: BookKind): Flow<Long>

    /** The total per group, most listened first. The title is that of the most recently listened row. */
    @Query(
        "SELECT groupKey, " +
            "(SELECT s2.groupTitle FROM listening_stats s2 WHERE s2.groupKey = s.groupKey AND s2.kind = :kind " +
            "ORDER BY s2.lastListenedAt DESC LIMIT 1) AS groupTitle, " +
            "SUM(listenedMs) AS listenedMs, COUNT(DISTINCT itemKey) AS items, MAX(lastListenedAt) AS lastListenedAt " +
            "FROM listening_stats s WHERE kind = :kind GROUP BY groupKey ORDER BY listenedMs DESC",
    )
    abstract fun observeByGroup(kind: BookKind): Flow<List<GroupListeningRow>>

    /** The most listened entries of kind [kind], most listened first. */
    @Query(
        "SELECT groupKey, itemKey, MAX(itemTitle) AS itemTitle, SUM(listenedMs) AS listenedMs FROM listening_stats " +
            "WHERE kind = :kind GROUP BY groupKey, itemKey ORDER BY listenedMs DESC LIMIT :limit",
    )
    abstract fun observeTopItems(kind: BookKind, limit: Int): Flow<List<ItemListeningRow>>

    /** Books listened to the end (all servers and from the device). */
    @Query(
        "SELECT COUNT(*) FROM book_progress p JOIN books b ON b.bookId = p.bookId " +
            "WHERE b.kind = 'BOOK' AND p.finished = 1",
    )
    abstract fun observeFinishedBooks(): Flow<Int>

    /** Episodes listened to the end (all subscriptions, including those from the device). */
    @Query(
        "SELECT COUNT(*) FROM book_progress p JOIN books b ON b.bookId = p.bookId " +
            "WHERE b.kind = 'EPISODE' AND p.finished = 1",
    )
    abstract fun observeFinishedEpisodes(): Flow<Int>

    /**
     * Like [observeFinishedEpisodes], only entries of kind [kind] finished between [fromMs] (inclusive) and [toMs] (exclusive).
     * By `finishedAt`: removing a book from "Continue listening" later changes `updatedAt` (review G31).
     */
    @Query(
        "SELECT COUNT(*) FROM book_progress p JOIN books b ON b.bookId = p.bookId " +
            "WHERE b.kind = :kind AND p.finished = 1 AND COALESCE(p.finishedAt, p.updatedAt) >= :fromMs " +
            "AND COALESCE(p.finishedAt, p.updatedAt) < :toMs",
    )
    abstract fun observeFinishedBetween(kind: BookKind, fromMs: Long, toMs: Long): Flow<Int>

    /** The total per group for the days [fromDay] to [toDay] (both inclusive), most listened first. */
    @Query(
        "SELECT groupKey, SUM(listenedMs) AS listenedMs FROM listening_days " +
            "WHERE kind = :kind AND day BETWEEN :fromDay AND :toDay GROUP BY groupKey ORDER BY listenedMs DESC",
    )
    abstract fun observeGroupDays(kind: BookKind, fromDay: Long, toDay: Long): Flow<List<GroupDayRow>>

    @Query("SELECT * FROM listening_stats")
    abstract suspend fun all(): List<ListeningStatEntity>

    /** Entries of [source] listened to at [sinceMs] or later (watch: only what changed since its last report). */
    @Query("SELECT * FROM listening_stats WHERE source = :source AND lastListenedAt >= :sinceMs")
    abstract suspend fun listenedSince(source: String, sinceMs: Long): List<ListeningStatEntity>

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
     * Adds [stat] to the total of its entry (created if needed) and to the total of its day [day].
     * [ListeningStatEntity.listenedMs] is the increment, [savedMs] the time saved by "Trim silence" in the process.
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

    /** Deletes all numbers of kind [kind] (counted and imported). */
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

    /** Replaces all numbers of the groups [groupKeys] with [stats] and [days] (switching a subscription's source, concept 12.10). */
    @Transaction
    open suspend fun replaceGroups(groupKeys: List<String>, stats: List<ListeningStatEntity>, days: List<ListeningDayEntity>) {
        deleteStatsOfGroups(groupKeys)
        deleteDaysOfGroups(groupKeys)
        upsertAll(stats)
        insertDaysIfAbsent(days)
    }
}
