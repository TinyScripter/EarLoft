package keinabersmart.kleinaberlaut.core.data.podcast

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.io.IOException

/** Keine lesbare Import-Datenbank (keine SQLite-Datei oder ohne die erwarteten Tabellen). */
class DatabaseFileException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Ein Abo des Backups. [active]: abonniert; sonst archiviert (nur für die Statistik von Belang). */
data class ImportFeed(val url: String, val title: String, val active: Boolean)

/**
 * Eine Folge, zu der das Backup etwas weiß: gehört, angefangen oder mit gehörter Zeit.
 * [lastPlayedAt] ist das jüngere von „zuletzt gespielt“ und „Ende erreicht“ (0, wenn unbekannt).
 */
data class ImportEpisode(
    val feedUrl: String,
    val guid: String,
    val title: String,
    val durationMs: Long,
    val positionMs: Long,
    val played: Boolean,
    val listenedMs: Long,
    val lastPlayedAt: Long,
)

data class ImportData(val feeds: List<ImportFeed>, val episodes: List<ImportEpisode>)

/**
 * Liest eine SQLite-Datenbank mit Abos und Hörverlauf schreibgeschützt. Nur Tabellen und Spalten, die für
 * Abos, Hörfortschritt und gehörte Zeit nötig sind; alles andere (Kapitel, Download-Protokoll, Warteschlange) bleibt unbeachtet.
 */
object DatabaseReader {
    // Abo-Zustand 0 = abonniert; alles andere gilt als archiviert (z. B. durch ein neues Abo ersetzt).
    private const val STATE_ACTIVE = 0

    fun read(file: File): ImportData {
        val db = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: SQLiteException) {
            throw DatabaseFileException("Keine Datenbank", e)
        }
        return db.use {
            try {
                ImportData(readFeeds(it), readEpisodes(it))
            } catch (e: SQLiteException) {
                throw DatabaseFileException("Unerwartete Tabellen", e)
            }
        }
    }

    private fun readFeeds(db: SQLiteDatabase): List<ImportFeed> =
        db.rawQuery("SELECT download_url, title, custom_title, state FROM Feeds", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val url = c.getString(0)?.trim().orEmpty()
                    if (url.isEmpty()) continue
                    val title = c.getString(2)?.takeIf { it.isNotBlank() } ?: c.getString(1).orEmpty()
                    add(ImportFeed(url, title, active = c.getInt(3) == STATE_ACTIVE))
                }
            }
        }

    private fun readEpisodes(db: SQLiteDatabase): List<ImportEpisode> =
        db.rawQuery(
            "SELECT f.download_url, i.item_identifier, i.title, i.read, m.duration, m.position, m.played_duration, " +
                "m.playback_completion_date, m.last_played_time " +
                "FROM FeedItems i JOIN Feeds f ON f.id = i.feed LEFT JOIN FeedMedia m ON m.feeditem = i.id " +
                "WHERE i.read = 1 OR m.position > 0 OR m.played_duration > 0",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val url = c.getString(0)?.trim().orEmpty()
                    val guid = c.getString(1)?.trim().orEmpty()
                    if (url.isEmpty() || guid.isEmpty()) continue
                    add(
                        ImportEpisode(
                            feedUrl = url,
                            guid = guid,
                            title = c.getString(2).orEmpty(),
                            played = c.getInt(3) == 1,
                            durationMs = c.getLong(4),
                            positionMs = c.getLong(5),
                            listenedMs = c.getLong(6),
                            lastPlayedAt = maxOf(c.getLong(7), c.getLong(8)),
                        ),
                    )
                }
            }
        }
}
