package keinabersmart.kleinaberlaut.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind

/**
 * Was auf die Uhr soll (Wear-Konzept 5). Am Handy die Auswahl des Nutzers, auf der Uhr die
 * Auswahlen des zuletzt empfangenen Manifests.
 *
 * Kein Fremdschlüssel: [refId] zeigt je nach [kind] auf `albums` oder `playlists`. Verschwindet
 * das Ziel, wird die Zeile beim Abgleich bzw. beim Löschen der Playlist aufgeräumt.
 */
@Entity(tableName = "wear_selections")
data class WearSelectionEntity(
    /** [WearSelectionKind.key], z. B. `album:12`. */
    @PrimaryKey val key: String,
    val kind: WearSelectionKind,
    /** `albumId` bzw. `playlistId` am Handy; `null` für Favoriten. */
    val refId: Long?,
    val addedAt: Long,
)
