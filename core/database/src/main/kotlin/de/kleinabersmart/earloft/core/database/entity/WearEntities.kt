package de.kleinabersmart.earloft.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import de.kleinabersmart.earloft.core.model.WearSelectionKind

/**
 * What should go onto the watch (Wear concept 5). On the phone the user's selection, on the watch the
 * selections of the most recently received manifest.
 *
 * No foreign key: depending on [kind], [refId] points to `albums` or `playlists`. If the target
 * disappears, the row is cleaned up during the sync or when the playlist is deleted.
 */
@Entity(tableName = "wear_selections")
data class WearSelectionEntity(
    /** [WearSelectionKind.key], e.g. `album:12`. */
    @PrimaryKey val key: String,
    val kind: WearSelectionKind,
    /** `albumId` or `playlistId` on the phone; `null` for favorites. */
    val refId: Long?,
    val addedAt: Long,
)

/**
 * Episodes selected individually for the watch (Wear concept 16.1), phone only. For now only podcast episodes; books follow
 * as a separate stage. If the episode disappears (subscription ended, dropped from the feed), the row goes with it.
 */
@Entity(
    tableName = "wear_books",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["bookId"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class WearBookEntity(
    @PrimaryKey val bookId: Long,
    /** Order on the watch (download and "Continue listening"). */
    val addedAt: Long,
)
