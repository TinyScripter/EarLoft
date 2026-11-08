package de.kleinabersmart.earloft.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import de.kleinabersmart.earloft.core.model.WearSelectionKind

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

/**
 * Einzeln für die Uhr gewählte Folgen (Wear-Konzept 16.1), nur am Handy. Vorerst nur Podcast-Folgen; Bücher folgen
 * als eigene Etappe. Verschwindet die Folge (Abo beendet, aus dem Feed gefallen), geht die Zeile mit.
 */
@Entity(
    tableName = "wear_books",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["bookId"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class WearBookEntity(
    @PrimaryKey val bookId: Long,
    /** Reihenfolge auf der Uhr (Download und „Weiterhören“). */
    val addedAt: Long,
)
