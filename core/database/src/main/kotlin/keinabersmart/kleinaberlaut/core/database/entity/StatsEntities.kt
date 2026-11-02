package keinabersmart.kleinaberlaut.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import keinabersmart.kleinaberlaut.core.model.BookKind

/**
 * Gehörte Zeit je Folge bzw. Buch (Grundlage der Statistik). Die Zeilen sind absichtlich von `books` entkoppelt: Fällt eine
 * Folge aus dem Feed oder wird ein Abo beendet, bleibt die gehörte Zeit erhalten. Es gibt eine Summe samt letztem Hördatum
 * je Eintrag, keine einzelnen Hörsitzungen.
 *
 * Gruppe = Podcast (Feed-Adresse) bzw. Server, Eintrag = Folge (guid) bzw. Buch. `source` trennt Fremdimporte
 * ([SOURCE_IMPORTED]) von dem, was die App selbst zählt ([SOURCE_APP]); `(source, groupKey, itemKey)` ist eindeutig, ein
 * erneuter Import ersetzt also seine eigenen Zeilen.
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
    /** Insgesamt gehörte Zeit (Echtzeit, nicht Inhaltszeit) in ms. */
    val listenedMs: Long,
    /** Unix-ms des letzten Hörens; 0, wenn unbekannt. */
    val lastListenedAt: Long,
) {
    companion object {
        const val SOURCE_APP = "app"
        const val SOURCE_IMPORTED = "imported"
    }
}
