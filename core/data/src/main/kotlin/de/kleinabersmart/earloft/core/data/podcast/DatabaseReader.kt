package de.kleinabersmart.earloft.core.data.podcast

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.io.IOException

/** Keine lesbare Import-Datenbank (keine SQLite-Datei oder ohne die erwarteten Tabellen). */
class DatabaseFileException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Ein Abo des Backups. [active]: abonniert; sonst archiviert (nur für die Statistik von Belang). Tempo und Effekte wie in
 * `podcast_feeds` (`null` = Vorgabe des Bereichs), siehe [ExchangeEffects].
 */
data class ImportFeed(
    val url: String,
    val title: String,
    val active: Boolean,
    val speed: Float? = null,
    val skipSilence: Boolean? = null,
    val volumeBoostDb: Int? = null,
    val skipIntroS: Int = 0,
    val skipOutroS: Int = 0,
)

/**
 * Tempo und Effekte eines Abos im Austauschformat (Spalten der Tabelle `Feeds`): Tempo −1 = Vorgabe der App; Stille
 * 0 = aus, 1 = Vorgabe, ab 2 = an; Lautstärke 0 = unverändert, 1 und 2 = leiser (gibt es hier nicht, gilt als Vorgabe),
 * 3 bis 5 = lauter in drei Stufen; Intro und Ende in Sekunden.
 */
internal object ExchangeEffects {
    const val SPEED_DEFAULT = -1.0
    const val SILENCE_OFF = 0
    const val SILENCE_DEFAULT = 1
    const val SILENCE_ON = 2
    const val VOLUME_UNCHANGED = 0

    /** Fehlt die Spalte ganz, steht nichts fest; ein Code, den [boostOf] als Vorgabe liest. */
    const val VOLUME_UNKNOWN = 1

    /** Stufen 3, 4, 5 und ihre Entsprechung in dB. */
    private val boostSteps = listOf(3 to 3, 4 to 6, 5 to 9)

    fun speedOf(value: Double): Float? = value.takeIf { it > 0 }?.toFloat()?.coerceIn(MIN_SPEED, MAX_SPEED)

    fun silenceOf(code: Int): Boolean? = when (code) {
        SILENCE_OFF -> false
        SILENCE_DEFAULT -> null
        else -> code > SILENCE_DEFAULT
    }

    /** Stufe 0 ist ausdrücklich „unverändert“ (0 dB), nicht die Vorgabe des Bereichs; 1 und 2 sowie Unbekanntes gelten als Vorgabe. */
    fun boostOf(code: Int): Int? = if (code == VOLUME_UNCHANGED) 0 else boostSteps.firstOrNull { it.first == code }?.second

    fun speedCode(speed: Float?): Double = speed?.toDouble() ?: SPEED_DEFAULT

    fun silenceCode(skipSilence: Boolean?): Int = when (skipSilence) {
        null -> SILENCE_DEFAULT
        true -> SILENCE_ON
        false -> SILENCE_OFF
    }

    /** Die nächsthöhere Stufe; ohne Anheben (auch „Vorgabe“) unverändert. */
    fun boostCode(db: Int?): Int =
        if (db == null || db <= 0) VOLUME_UNCHANGED else boostSteps.firstOrNull { db <= it.second }?.first ?: boostSteps.last().first

    private const val MIN_SPEED = 0.5f
    private const val MAX_SPEED = 3f
}

/**
 * Eine Folge, zu der das Backup etwas weiß: gehört, angefangen, mit gehörter Zeit oder gemerkt ([starred], Tabelle `Favorites`).
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
    val starred: Boolean = false,
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

    private fun readFeeds(db: SQLiteDatabase): List<ImportFeed> {
        // Ältere Dateien kennen nicht jede Spalte der Wiedergabe; dann gilt die Vorgabe.
        val columns = db.rawQuery("PRAGMA table_info(Feeds)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        fun column(name: String, fallback: String) = if (name in columns) name else "$fallback AS $name"
        val select = listOf(
            "download_url", "title", "custom_title", "state",
            column("feed_playback_speed", ExchangeEffects.SPEED_DEFAULT.toString()),
            column("feed_skip_silence", ExchangeEffects.SILENCE_DEFAULT.toString()),
            column("feed_volume_adaption", ExchangeEffects.VOLUME_UNKNOWN.toString()),
            column("feed_skip_intro", "0"),
            column("feed_skip_ending", "0"),
        ).joinToString()
        return db.rawQuery("SELECT $select FROM Feeds", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val url = c.getString(0)?.trim().orEmpty()
                    if (url.isEmpty()) continue
                    val title = c.getString(2)?.takeIf { it.isNotBlank() } ?: c.getString(1).orEmpty()
                    add(
                        ImportFeed(
                            url,
                            title,
                            active = c.getInt(3) == STATE_ACTIVE,
                            speed = ExchangeEffects.speedOf(c.getDouble(4)),
                            skipSilence = ExchangeEffects.silenceOf(c.getInt(5)),
                            volumeBoostDb = ExchangeEffects.boostOf(c.getInt(6)),
                            skipIntroS = c.getInt(7).coerceAtLeast(0),
                            skipOutroS = c.getInt(8).coerceAtLeast(0),
                        ),
                    )
                }
            }
        }
    }

    private fun readEpisodes(db: SQLiteDatabase): List<ImportEpisode> {
        // Ältere Dateien haben keine Tabelle `Favorites`.
        val favorites = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'Favorites'", null).use { it.moveToFirst() }
        val starred = if (favorites) "EXISTS (SELECT 1 FROM Favorites s WHERE s.feeditem = i.id)" else "0"
        return db.rawQuery(
            "SELECT f.download_url, i.item_identifier, i.title, i.read, m.duration, m.position, m.played_duration, " +
                "m.playback_completion_date, m.last_played_time, $starred AS starred " +
                "FROM FeedItems i JOIN Feeds f ON f.id = i.feed LEFT JOIN FeedMedia m ON m.feeditem = i.id " +
                "WHERE i.read = 1 OR m.position > 0 OR m.played_duration > 0 OR $starred",
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
                            starred = c.getInt(9) == 1,
                        ),
                    )
                }
            }
        }
    }
}
