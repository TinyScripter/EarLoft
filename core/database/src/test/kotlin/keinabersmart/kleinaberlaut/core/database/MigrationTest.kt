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

    private companion object {
        const val DB_NAME = "migration-test"
    }
}
