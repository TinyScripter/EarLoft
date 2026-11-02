package keinabersmart.kleinaberlaut.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
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
    /** Insgesamt gehörte Zeit in ms, gemessen am Inhalt (eine Stunde Inhalt zählt eine Stunde, egal bei welcher Geschwindigkeit). */
    val listenedMs: Long,
    /** Unix-ms des letzten Hörens; 0, wenn unbekannt. */
    val lastListenedAt: Long,
) {
    companion object {
        const val SOURCE_APP = "app"
        const val SOURCE_IMPORTED = "imported"
    }
}

/**
 * Gehörte Zeit je Tag und Gruppe, nur von der App selbst gezählt (importierte Werte kennen keinen Tag). Grundlage für
 * Verläufe; [day] ist der lokale Kalendertag als `LocalDate.toEpochDay()`.
 */
@Entity(tableName = "listening_days", primaryKeys = ["day", "kind", "groupKey"])
data class ListeningDayEntity(
    val day: Long,
    val kind: BookKind,
    val groupKey: String,
    /** Gehörte Zeit in ms, gemessen am Inhalt (eine Stunde Inhalt zählt eine Stunde, egal bei welcher Geschwindigkeit). */
    val listenedMs: Long,
)

/**
 * Letzter Abruf einer Server-Statistik (Audiobookshelf), unverändert als JSON, damit die Seite offline den letzten Stand
 * zeigt. [kind] benennt die Art des Abrufs (heute nur `listening`).
 */
@Entity(tableName = "server_stats_cache", primaryKeys = ["serverId", "kind"])
data class ServerStatsCacheEntity(
    val serverId: String,
    val kind: String,
    val json: String,
    val fetchedAt: Long,
)

/**
 * Eine Hörsitzung eines Buchs, die dem Server gemeldet wird (Audiobookshelf zählt seine Statistik aus Sitzungen).
 * Die Werte sind je Sitzung aufsummiert; [dirty]: seit der letzten erfolgreichen Meldung geändert.
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
    /** Gehörte Inhaltszeit der Sitzung in ms. */
    val timeListeningMs: Long,
    /** Buchposition beim Start und zuletzt, in ms. */
    val startPositionMs: Long,
    val currentPositionMs: Long,
    val durationMs: Long,
    val dirty: Boolean = true,
)
