package de.kleinabersmart.earloft.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import de.kleinabersmart.earloft.core.model.BookKind

/**
 * Listening time per episode or book (basis of the statistics). The rows are deliberately decoupled from `books`: if an
 * episode drops out of the feed or a subscription ends, the listening time is kept. There is one total with the last listen
 * date per entry, no individual listening sessions.
 *
 * Group = podcast (feed address) or server, entry = episode (guid) or book. `source` separates external imports
 * ([SOURCE_IMPORTED]) from what the app counts itself ([SOURCE_APP]); `(source, groupKey, itemKey)` is unique, so a
 * repeated import replaces its own rows.
 */
@Entity(
    tableName = "listening_stats",
    indices = [
        Index("source", "groupKey", "itemKey", unique = true),
        Index("lastListenedAt"),
    ],
)
data class ListeningStatEntity(
    @PrimaryKey(autoGenerate = true) val statId: Long = 0,
    val source: String,
    val kind: BookKind,
    val groupKey: String,
    val groupTitle: String,
    val itemKey: String,
    val itemTitle: String,
    /** Total listening time in ms, measured by content (an hour of content counts as an hour, regardless of speed). */
    val listenedMs: Long,
    /** Unix ms of the last listen; 0 if unknown. */
    val lastListenedAt: Long,
) {
    companion object {
        const val SOURCE_APP = "app"
        const val SOURCE_IMPORTED = "imported"
    }
}

/**
 * Listening time per day and group, only counted by the app itself (imported values have no day). Basis for
 * trends; [day] is the local calendar day as `LocalDate.toEpochDay()`.
 */
@Entity(tableName = "listening_days", primaryKeys = ["day", "kind", "groupKey"])
data class ListeningDayEntity(
    val day: Long,
    val kind: BookKind,
    val groupKey: String,
    /** Listening time in ms, measured by content (an hour of content counts as an hour, regardless of speed). */
    val listenedMs: Long,
    /**
     * Time saved by "Trim silence" in ms (schema v26): the skipped silence divided by the speed, i.e.
     * wall-clock time. The silence itself counts as content in [listenedMs].
     */
    @ColumnInfo(defaultValue = "0") val savedMs: Long = 0,
)

/**
 * Last fetch of a server statistic (Audiobookshelf), unchanged as JSON so the page shows the last state
 * offline. [kind] names the type of fetch (currently only `listening`).
 */
@Entity(tableName = "server_stats_cache", primaryKeys = ["serverId", "kind"])
data class ServerStatsCacheEntity(
    val serverId: String,
    val kind: String,
    val json: String,
    val fetchedAt: Long,
)

/**
 * A listening session of a book that is reported to the server (Audiobookshelf computes its statistics from sessions).
 * The values are summed per session; [dirty]: changed since the last successful report.
 */
@Entity(
    tableName = "abs_sessions",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["bookId"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("bookId"), Index("dirty")],
)
data class AbsSessionEntity(
    @PrimaryKey val sessionId: String,
    val bookId: Long,
    val startedAt: Long,
    val updatedAt: Long,
    /** Listened content time of the session in ms. */
    val timeListeningMs: Long,
    /** Book position at start and most recently, in ms. */
    val startPositionMs: Long,
    val currentPositionMs: Long,
    val durationMs: Long,
    val dirty: Boolean = true,
)
