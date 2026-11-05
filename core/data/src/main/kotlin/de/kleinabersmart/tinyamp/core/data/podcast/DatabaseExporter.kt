package de.kleinabersmart.tinyamp.core.data.podcast

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import de.kleinabersmart.tinyamp.core.database.dao.BookDao
import de.kleinabersmart.tinyamp.core.database.dao.PodcastDao
import de.kleinabersmart.tinyamp.core.database.dao.StatsDao
import de.kleinabersmart.tinyamp.core.database.entity.BookProgressEntity
import de.kleinabersmart.tinyamp.core.database.entity.ListeningStatEntity
import de.kleinabersmart.tinyamp.core.model.BookKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Was ein Export geschrieben hat: Podcasts (auch archivierte) und Folgen. */
data class DatabaseExportResult(val feeds: Int, val episodes: Int)

/**
 * Schreibt Abos, Hörfortschritt und gehörte Zeit in eine SQLite-Datei mit genau den Tabellen, die [DatabaseReader] liest
 * (`Feeds`, `FeedItems`, `FeedMedia`; dazu leere Hilfstabellen im selben Format). Der Export ist das Gegenstück zum Import.
 *
 * - Podcasts vom Gerät fehlen (kein Feed).
 * - Gehörte Zeit, deren Folge oder Podcast es in der App nicht mehr gibt, steht unter der Feed-Adresse der Statistik
 *   (ein Podcast ohne Abo als archiviert), damit sie nicht verloren geht. Ihr „gehört“-Stand ist nicht bekannt.
 */
@Singleton
class DatabaseExporter @Inject constructor(
    private val podcastDao: PodcastDao,
    private val bookDao: BookDao,
    private val statsDao: StatsDao,
) {
    /** Schreibt nach [file] (eine vorhandene Datei wird ersetzt). */
    suspend fun export(file: File): DatabaseExportResult {
        val feeds = buildFeeds()
        withContext(Dispatchers.IO) {
            file.delete()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.version = FORMAT_VERSION
                db.beginTransaction()
                try {
                    SCHEMA.forEach(db::execSQL)
                    write(db, feeds)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
        return DatabaseExportResult(feeds.size, feeds.sumOf { it.episodes.size })
    }

    private class OutFeed(
        val url: String,
        val title: String,
        val author: String,
        val description: String,
        val imageUrl: String?,
        val autoDownload: Boolean,
        val active: Boolean,
        val episodes: MutableList<OutEpisode> = mutableListOf(),
    )

    private class OutEpisode(
        val guid: String,
        val title: String,
        val description: String,
        val publishedAt: Long,
        val imageUrl: String?,
        val chaptersUrl: String?,
        val audioUrl: String?,
        val mimeType: String?,
        val durationMs: Long,
        val played: Boolean,
        val positionMs: Long,
        val listenedMs: Long,
        val lastPlayedAt: Long,
    )

    private suspend fun buildFeeds(): List<OutFeed> {
        val progress = bookDao.observeAllProgress().first().associateBy { it.bookId }
        val stats = statsDao.all().filter { it.kind == BookKind.EPISODE }.groupBy { it.groupKey to it.itemKey }.toMutableMap()

        val result = LinkedHashMap<String, OutFeed>()
        for (feed in podcastDao.feeds().filter { !it.feedId.startsWith(LOCAL_FEED_PREFIX) }.sortedBy { it.title.lowercase() }) {
            // Sendungen aus ARD Sounds kann keine andere App abonnieren: Sie gehen als archiviert mit, samt Folgen und Fortschritt.
            val out = OutFeed(feed.url, feed.title, feed.author, feed.description, feed.imageUrl, feed.autoDownload, active = feed.hasRssFeed)
            result[feed.url] = out
            for (book in bookDao.booksOf(feed.feedId).filter { it.kind == BookKind.EPISODE }.sortedBy { it.addedAt }) {
                val p = progress[book.bookId]
                val listened = stats.remove(feed.url to book.remoteId)
                val track = bookDao.tracks(book.bookId).firstOrNull()
                out.episodes += OutEpisode(
                    guid = book.remoteId,
                    title = book.title,
                    description = book.description,
                    publishedAt = book.addedAt,
                    imageUrl = book.coverUrl,
                    chaptersUrl = book.chaptersUrl,
                    audioUrl = track?.remoteFileId,
                    mimeType = track?.mimeType,
                    durationMs = book.durationMs,
                    played = p?.finished == true,
                    positionMs = p.openPosition(),
                    listenedMs = listened.totalMs(),
                    lastPlayedAt = maxOf(p?.updatedAt ?: 0, listened.lastAt()),
                )
            }
        }
        // Gehörte Zeit ohne Folge in der App: unter ihrem Podcast, notfalls unter einem archivierten.
        for ((key, rows) in stats) {
            val (groupKey, itemKey) = key
            val out = result.getOrPut(groupKey) {
                OutFeed(groupKey, rows.first().groupTitle, "", "", null, autoDownload = false, active = false)
            }
            out.episodes += OutEpisode(
                guid = itemKey,
                title = rows.first().itemTitle,
                description = "",
                publishedAt = rows.lastAt(),
                imageUrl = null,
                chaptersUrl = null,
                audioUrl = null,
                mimeType = null,
                durationMs = 0,
                played = false,
                positionMs = 0,
                listenedMs = rows.totalMs(),
                lastPlayedAt = rows.lastAt(),
            )
        }
        return result.values.toList()
    }

    private fun write(db: SQLiteDatabase, feeds: List<OutFeed>) {
        var itemId = 0L
        feeds.forEachIndexed { index, feed ->
            val feedId = index + 1L
            db.insertOrThrow(
                "Feeds",
                null,
                ContentValues().apply {
                    put("id", feedId)
                    put("title", feed.title)
                    put("download_url", feed.url)
                    put("description", feed.description)
                    put("author", feed.author)
                    put("image_url", feed.imageUrl)
                    put("type", "rss")
                    put("auto_download", if (feed.autoDownload) 1 else 0)
                    put("keep_updated", if (feed.active) 1 else 0)
                    put("state", if (feed.active) STATE_ACTIVE else STATE_ARCHIVED)
                },
            )
            for (episode in feed.episodes) {
                itemId++
                db.insertOrThrow(
                    "FeedItems",
                    null,
                    ContentValues().apply {
                        put("id", itemId)
                        put("title", episode.title)
                        put("pubDate", episode.publishedAt)
                        put("read", if (episode.played) 1 else 0)
                        put("description", episode.description)
                        put("feed", feedId)
                        put("item_identifier", episode.guid)
                        put("image_url", episode.imageUrl)
                        put("podcastindex_chapter_url", episode.chaptersUrl)
                        put("has_simple_chapters", 0)
                        put("auto_download", 0)
                    },
                )
                db.insertOrThrow(
                    "FeedMedia",
                    null,
                    ContentValues().apply {
                        put("id", itemId)
                        put("feeditem", itemId)
                        put("duration", episode.durationMs)
                        put("download_url", episode.audioUrl)
                        put("mime_type", episode.mimeType)
                        put("downloaded", 0)
                        put("position", episode.positionMs)
                        put("filesize", 0)
                        put("playback_completion_date", if (episode.played) episode.lastPlayedAt else 0)
                        if (episode.listenedMs > 0) put("played_duration", episode.listenedMs)
                        put("has_embedded_picture", 0)
                        put("last_played_time", episode.lastPlayedAt)
                    },
                )
            }
        }
    }

    // Ist die Folge zu Ende gehört, steht die Position am Anfang (so liest sie auch der Import als „gehört“).
    private fun BookProgressEntity?.openPosition(): Long = if (this == null || finished) 0 else positionMs

    private fun List<ListeningStatEntity>?.totalMs(): Long = this?.sumOf { it.listenedMs } ?: 0
    private fun List<ListeningStatEntity>?.lastAt(): Long = this?.maxOfOrNull { it.lastListenedAt } ?: 0

    private companion object {
        const val LOCAL_FEED_PREFIX = "local:"
        const val STATE_ACTIVE = 0
        const val STATE_ARCHIVED = 2

        /** Versionsnummer des Tabellenformats (`PRAGMA user_version`). */
        const val FORMAT_VERSION = 3110000

        val SCHEMA = listOf(
            "CREATE TABLE Feeds (id INTEGER PRIMARY KEY AUTOINCREMENT ,title TEXT,custom_title TEXT,file_url TEXT,download_url TEXT,downloaded INTEGER,link TEXT," +
                "description TEXT,payment_link TEXT,last_update TEXT,language TEXT,author TEXT,image_url TEXT,type TEXT,feed_identifier TEXT," +
                "auto_download INTEGER DEFAULT 1,username TEXT,password TEXT,include_filter TEXT DEFAULT '',exclude_filter TEXT DEFAULT ''," +
                "minimal_duration_filter INTEGER DEFAULT -1,keep_updated INTEGER DEFAULT 1,is_paged INTEGER DEFAULT 0,next_page_link TEXT,hide TEXT," +
                "sort_order TEXT,last_update_failed INTEGER DEFAULT 0,auto_delete_action INTEGER DEFAULT 0,feed_playback_speed REAL DEFAULT -1.0," +
                "feed_volume_adaption INTEGER DEFAULT 0,tags TEXT,feed_skip_intro INTEGER DEFAULT 0,feed_skip_ending INTEGER DEFAULT 0," +
                "episode_notification INTEGER DEFAULT 0,new_episodes_action INTEGER DEFAULT 0, feed_skip_silence INTEGER DEFAULT 1, state INTEGER DEFAULT 0)",
            "CREATE TABLE FeedItems (id INTEGER PRIMARY KEY AUTOINCREMENT ,title TEXT,pubDate INTEGER,read INTEGER,link TEXT,description TEXT," +
                "payment_link TEXT,media INTEGER,feed INTEGER,has_simple_chapters INTEGER,item_identifier TEXT,image_url TEXT,auto_download INTEGER," +
                "podcastindex_chapter_url TEXT, podcastindex_transcript_url TEXT, podcastindex_transcript_type TEXT, social_interact_url TEXT)",
            "CREATE TABLE FeedMedia (id INTEGER PRIMARY KEY AUTOINCREMENT ,duration INTEGER,file_url TEXT,download_url TEXT,downloaded INTEGER," +
                "position INTEGER,filesize INTEGER,mime_type TEXT,playback_completion_date INTEGER,feeditem INTEGER,played_duration INTEGER," +
                "has_embedded_picture INTEGER,last_played_time INTEGER)",
            "CREATE TABLE DownloadLog (id INTEGER PRIMARY KEY AUTOINCREMENT ,feedfile INTEGER,feedfile_type INTEGER,reason INTEGER,successful INTEGER," +
                "completion_date INTEGER,reason_detailed TEXT,title TEXT)",
            "CREATE TABLE Queue(id INTEGER PRIMARY KEY,feeditem INTEGER,feed INTEGER)",
            "CREATE TABLE SimpleChapters (id INTEGER PRIMARY KEY AUTOINCREMENT ,title TEXT,start INTEGER,feeditem INTEGER,link TEXT,image_url TEXT)",
            "CREATE TABLE Favorites(id INTEGER PRIMARY KEY,feeditem INTEGER,feed INTEGER)",
            "CREATE INDEX FeedItems_feed ON FeedItems (feed)",
            "CREATE INDEX FeedItems_pubDate ON FeedItems (pubDate)",
            "CREATE INDEX FeedItems_read ON FeedItems (read)",
            "CREATE INDEX FeedMedia_feeditem ON FeedMedia (feeditem)",
            "CREATE INDEX Queue_feeditem ON Queue (feeditem)",
            "CREATE INDEX SimpleChapters_feeditem ON SimpleChapters (feeditem)",
        )
    }
}
