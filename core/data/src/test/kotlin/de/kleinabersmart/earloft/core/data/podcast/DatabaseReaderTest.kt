package de.kleinabersmart.earloft.core.data.podcast

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `reads feeds and only the episodes with progress or listening time`() {
        val file = folder.newFile("backup.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE Feeds (id INTEGER PRIMARY KEY, title TEXT, custom_title TEXT, download_url TEXT, state INTEGER)")
            db.execSQL("CREATE TABLE FeedItems (id INTEGER PRIMARY KEY, title TEXT, read INTEGER, media INTEGER, feed INTEGER, item_identifier TEXT)")
            db.execSQL(
                "CREATE TABLE FeedMedia (id INTEGER PRIMARY KEY, duration INTEGER, position INTEGER, playback_completion_date INTEGER, " +
                    "feeditem INTEGER, played_duration INTEGER, last_played_time INTEGER)",
            )
            db.execSQL("INSERT INTO Feeds VALUES (1, 'Original', 'Mein Titel', 'https://a.example/feed', 0), (2, 'Alt', NULL, 'https://b.example/feed', 2)")
            db.execSQL(
                "INSERT INTO FeedItems VALUES (1, 'Gehört', 1, 1, 1, 'g1'), (2, 'Angefangen', 0, 2, 1, 'g2'), (3, 'Unberührt', 0, 3, 1, 'g3'), (4, 'Archiv', 1, 4, 2, 'g4')",
            )
            db.execSQL(
                "INSERT INTO FeedMedia VALUES (1, 600000, 0, 5000, 1, 590000, 4000), (2, 600000, 100000, 0, 2, 100000, 7000), " +
                    "(3, 600000, 0, 0, 3, 0, 0), (4, 300000, 0, 0, 4, 250000, 0)",
            )
        }

        val backup = DatabaseReader.read(file)

        assertEquals(
            listOf(ImportFeed("https://a.example/feed", "Mein Titel", true), ImportFeed("https://b.example/feed", "Alt", false)),
            backup.feeds,
        )
        assertEquals(listOf("g1", "g2", "g4"), backup.episodes.map { it.guid })
        val played = backup.episodes.first()
        assertEquals(true, played.played)
        assertEquals(590_000, played.listenedMs)
        // Das jüngere von „zuletzt gespielt“ und „Ende erreicht“.
        assertEquals(5_000, played.lastPlayedAt)
        assertEquals(100_000, backup.episodes[1].positionMs)
    }

    @Test
    fun `playback settings of a subscription map to the app's values`() {
        val file = folder.newFile("backup.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE Feeds (id INTEGER PRIMARY KEY, title TEXT, custom_title TEXT, download_url TEXT, state INTEGER, " +
                    "feed_playback_speed REAL, feed_skip_silence INTEGER, feed_volume_adaption INTEGER, feed_skip_intro INTEGER, feed_skip_ending INTEGER)",
            )
            db.execSQL("CREATE TABLE FeedItems (id INTEGER PRIMARY KEY, title TEXT, read INTEGER, media INTEGER, feed INTEGER, item_identifier TEXT)")
            db.execSQL(
                "CREATE TABLE FeedMedia (id INTEGER PRIMARY KEY, duration INTEGER, position INTEGER, playback_completion_date INTEGER, " +
                    "feeditem INTEGER, played_duration INTEGER, last_played_time INTEGER)",
            )
            db.execSQL(
                "INSERT INTO Feeds VALUES (1, 'Eigen', NULL, 'https://a.example/feed', 0, 1.75, 0, 4, 20, 40), " +
                    "(2, 'Vorgabe', NULL, 'https://b.example/feed', 0, -1.0, 1, 0, 0, 0), " +
                    "(3, 'Leiser', NULL, 'https://c.example/feed', 0, -1.0, 2, 2, 0, 0)",
            )
        }

        val feeds = DatabaseReader.read(file).feeds

        assertEquals(ImportFeed("https://a.example/feed", "Eigen", true, 1.75f, false, 6, 20, 40), feeds[0])
        // Stufe 0 der Lautstärke ist ausdrücklich „unverändert“, nicht die Vorgabe des Bereichs.
        assertEquals(ImportFeed("https://b.example/feed", "Vorgabe", true, volumeBoostDb = 0), feeds[1])
        // Leiser gibt es in der App nicht: Vorgabe; Stille ab Stufe 2 an.
        assertEquals(ImportFeed("https://c.example/feed", "Leiser", true, skipSilence = true), feeds[2])
    }

    @Test
    fun `other files are rejected`() {
        val text = folder.newFile("kein.db").apply { writeText("das ist keine datenbank") }
        val empty = folder.newFile("leer.db")
        SQLiteDatabase.openOrCreateDatabase(empty, null).use { it.execSQL("CREATE TABLE x (a INTEGER)") }

        assertThrows(DatabaseFileException::class.java) { DatabaseReader.read(text) }
        assertThrows(DatabaseFileException::class.java) { DatabaseReader.read(empty) }
    }
}
