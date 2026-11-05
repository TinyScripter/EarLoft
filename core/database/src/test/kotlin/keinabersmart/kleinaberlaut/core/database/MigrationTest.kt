package keinabersmart.kleinaberlaut.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), KleinaberlautDatabase::class.java)

    @Test
    fun `v2 to v3 keeps song sources and adds starredAt`() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL("INSERT INTO sources (sourceId, type, name, priority, syncGeneration, lastFullSyncAt) VALUES ('nd', 'SUBSONIC', 'nd', 0, 1, 0)")
            db.execSQL(
                "INSERT INTO albums (albumId, title, sortTitle, normTitle, albumArtistDisplay, normAlbumArtist, isCompilation) " +
                    "VALUES (1, 'A', 'A', 'a', 'B', 'b', 0)",
            )
            db.execSQL(
                "INSERT INTO songs (songId, albumId, title, normTitle, artistDisplay, discNumber, trackNumber, durationMs, isFavorite, favoriteChangedAt, dateAdded) " +
                    "VALUES (1, 1, 'S', 's', 'B', 1, 1, 1000, 0, 0, 0)",
            )
            db.execSQL("INSERT INTO song_sources (uid, songId, sourceId, remoteId, seenInGeneration) VALUES ('nd:1', 1, 'nd', '1', 1)")
        }

        helper.runMigrationsAndValidate(DB_NAME, 3, true).use { db ->
            db.query("SELECT uid, starredAt FROM song_sources").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("nd:1", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
        }
    }

    @Test
    fun `v3 to v4 adds an empty wear_selections table and keeps the library`() {
        helper.createDatabase(DB_NAME, 3).use { db ->
            db.execSQL("INSERT INTO sources (sourceId, type, name, priority, syncGeneration, lastFullSyncAt) VALUES ('nd', 'SUBSONIC', 'nd', 0, 1, 0)")
        }

        helper.runMigrationsAndValidate(DB_NAME, 4, true).use { db ->
            db.query("SELECT COUNT(*) FROM sources").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.execSQL("INSERT INTO wear_selections (`key`, kind, refId, addedAt) VALUES ('favorites', 'FAVORITES', NULL, 1)")
            db.query("SELECT kind, refId FROM wear_selections").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("FAVORITES", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
        }
    }

    @Test
    fun `v15 to v16 keeps existing podcasts as subscriptions`() {
        helper.createDatabase(DB_NAME, 15).use { db ->
            db.execSQL(
                "INSERT INTO podcast_feeds (feedId, url, title, author, description, lastRefreshAt, syncGeneration, autoDownload) " +
                    "VALUES ('f', 'https://example.com/feed.xml', 'T', '', '', 0, 0, 0)",
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, 16, true).use { db ->
            db.query("SELECT subscribed FROM podcast_feeds WHERE feedId = 'f'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    @Test
    fun `v16 to v17 keeps existing podcasts active`() {
        helper.createDatabase(DB_NAME, 16).use { db ->
            db.execSQL(
                "INSERT INTO podcast_feeds (feedId, url, title, author, description, lastRefreshAt, syncGeneration, autoDownload) " +
                    "VALUES ('f', 'https://example.com/feed.xml', 'T', '', '', 0, 0, 0)",
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, 17, true).use { db ->
            db.query("SELECT archived FROM podcast_feeds WHERE feedId = 'f'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun `v17 to v18 adds an empty episode selection for the watch`() {
        helper.createDatabase(DB_NAME, 17).close()

        helper.runMigrationsAndValidate(DB_NAME, 18, true).use { db ->
            db.query("SELECT COUNT(*) FROM wear_books").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val DB_NAME = "migration-test"
    }
}
