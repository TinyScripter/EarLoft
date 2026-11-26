package de.kleinabersmart.earloft.core.data.podcast

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.io.IOException

/** Not a readable import database (not an SQLite file or without the expected tables). */
class DatabaseFileException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A subscription in the backup. [active]: subscribed; otherwise archived (only relevant for statistics). Speed and effects as in
 * `podcast_feeds` (`null` = section default), see [ExchangeEffects].
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
    /** "Keep up to date" (column `keep_updated`, missing = on). */
    val keepUpdated: Boolean = true,
)

/**
 * Speed and effects of a subscription in the exchange format (columns of the `Feeds` table): speed −1 = app default; silence
 * 0 = off, 1 = default, 2 and up = on; volume 0 = unchanged, 1 and 2 = quieter (not supported here, treated as default),
 * 3 to 5 = louder in three levels; intro and outro in seconds.
 */
internal object ExchangeEffects {
    const val SPEED_DEFAULT = -1.0
    const val SILENCE_OFF = 0
    const val SILENCE_DEFAULT = 1
    const val SILENCE_ON = 2
    const val VOLUME_UNCHANGED = 0

    /** If the column is missing entirely, nothing is set; a code that [boostOf] reads as default. */
    const val VOLUME_UNKNOWN = 1

    /** Levels 3, 4, 5 and their equivalent in dB. */
    private val boostSteps = listOf(3 to 3, 4 to 6, 5 to 9)

    fun speedOf(value: Double): Float? = value.takeIf { it > 0 }?.toFloat()?.coerceIn(MIN_SPEED, MAX_SPEED)

    fun silenceOf(code: Int): Boolean? = when (code) {
        SILENCE_OFF -> false
        SILENCE_DEFAULT -> null
        else -> code > SILENCE_DEFAULT
    }

    /** Level 0 is explicitly "unchanged" (0 dB), not the section default; 1 and 2 as well as unknown values count as default. */
    fun boostOf(code: Int): Int? = if (code == VOLUME_UNCHANGED) 0 else boostSteps.firstOrNull { it.first == code }?.second

    fun speedCode(speed: Float?): Double = speed?.toDouble() ?: SPEED_DEFAULT

    fun silenceCode(skipSilence: Boolean?): Int = when (skipSilence) {
        null -> SILENCE_DEFAULT
        true -> SILENCE_ON
        false -> SILENCE_OFF
    }

    /** The next higher level; without boost (including "default") unchanged. */
    fun boostCode(db: Int?): Int =
        if (db == null || db <= 0) VOLUME_UNCHANGED else boostSteps.firstOrNull { db <= it.second }?.first ?: boostSteps.last().first

    private const val MIN_SPEED = 0.5f
    private const val MAX_SPEED = 3f
}

/**
 * An episode the backup knows something about: played, started, with listened time or saved ([starred], table `Favorites`).
 * [lastPlayedAt] is the more recent of "last played" and "end reached" (0 if unknown).
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
 * Reads an SQLite database with subscriptions and listening history read-only. Only tables and columns needed for
 * subscriptions, listening progress and listened time; everything else (chapters, download log, queue) is ignored.
 */
object DatabaseReader {
    // Subscription state 0 = subscribed; everything else counts as archived (e.g. replaced by a new subscription).
    private const val STATE_ACTIVE = 0

    fun read(file: File): ImportData {
        val db = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: SQLiteException) {
            throw DatabaseFileException("Not a database", e)
        }
        return db.use {
            try {
                ImportData(readFeeds(it), readEpisodes(it))
            } catch (e: SQLiteException) {
                throw DatabaseFileException("Unexpected tables", e)
            }
        }
    }

    private fun readFeeds(db: SQLiteDatabase): List<ImportFeed> {
        // Older files don't have every playback column; then the default applies.
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
            column("keep_updated", "1"),
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
                            keepUpdated = c.getInt(9) != 0,
                        ),
                    )
                }
            }
        }
    }

    private fun readEpisodes(db: SQLiteDatabase): List<ImportEpisode> {
        // Older files have no `Favorites` table.
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
