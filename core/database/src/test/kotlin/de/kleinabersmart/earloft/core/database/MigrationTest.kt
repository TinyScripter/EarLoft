package de.kleinabersmart.earloft.core.database

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
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), EarLoftDatabase::class.java)

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

    @Test
    fun `v18 to v19 drops the cast address and keeps the server`() {
        helper.createDatabase(DB_NAME, 18).use { db ->
            db.execSQL(
                "INSERT INTO sources (sourceId, type, name, baseUrl, username, priority, syncGeneration, lastFullSyncAt, enabled, castBaseUrl) " +
                    "VALUES ('s', 'SUBSONIC', 'Navidrome', 'https://music.example.com/', 'anna', 0, 3, 0, 1, 'http://192.168.0.10:4533/')",
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, 19, true).use { db ->
            db.query("SELECT name, baseUrl, syncGeneration FROM sources WHERE sourceId = 's'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Navidrome", cursor.getString(0))
                assertEquals("https://music.example.com/", cursor.getString(1))
                assertEquals(3, cursor.getInt(2))
            }
        }
    }

    @Test
    fun `v19 to v21 keeps podcasts and episodes, drops the feed validators and adds empty links`() {
        helper.createDatabase(DB_NAME, 19).use { db ->
            db.execSQL(
                "INSERT INTO podcast_feeds (feedId, url, title, author, description, etag, lastModified, lastRefreshAt, syncGeneration, autoDownload) " +
                    "VALUES ('f', 'https://example.com/feed.xml', 'Logbuch', '', '', '\"abc\"', 'Sat, 01 Nov 2026 10:00:00 GMT', 5, 2, 1)",
            )
            db.execSQL(
                "INSERT INTO books (bookId, serverId, kind, remoteId, title, normTitle, author, narrator, series, description, durationMs, " +
                    "remoteUpdatedAt, addedAt, detailsLoaded, seenInGeneration) " +
                    "VALUES (7, 'f', 'EPISODE', 'guid-1', 'Folge 1', 'folge 1', 'Logbuch', '', '', '', 60000, 0, 1, 1, 2)",
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, 20, true).close()
        helper.runMigrationsAndValidate(DB_NAME, 21, true).use { db ->
            db.query("SELECT title, etag, lastModified, websiteUrl, syncGeneration FROM podcast_feeds WHERE feedId = 'f'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Logbuch", cursor.getString(0))
                // Ohne Validatoren lädt der nächste Abgleich den Feed ganz und holt so die Webseiten.
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
                assertEquals(2, cursor.getInt(4))
            }
            db.query("SELECT title, durationMs, webUrl, isbn FROM books WHERE bookId = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Folge 1", cursor.getString(0))
                assertEquals(60_000, cursor.getInt(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
            }
        }
    }

    @Test
    fun `v29 to v30 keeps chapters and reloads chapter lists and feeds`() {
        helper.createDatabase(DB_NAME, 29).use { db ->
            db.execSQL(
                "INSERT INTO podcast_feeds (feedId, url, title, author, description, etag, lastModified, lastRefreshAt, syncGeneration, autoDownload) " +
                    "VALUES ('f', 'https://example.com/feed.xml', 'Lage', '', '', '\"abc\"', 'Sat, 01 Nov 2026 10:00:00 GMT', 5, 2, 0)",
            )
            for ((id, source) in listOf(7 to "'JSON'", 8 to "'FEED'", 9 to "NULL")) {
                db.execSQL(
                    "INSERT INTO books (bookId, serverId, kind, remoteId, title, normTitle, author, narrator, series, description, durationMs, " +
                        "remoteUpdatedAt, addedAt, detailsLoaded, seenInGeneration, chapterSource) " +
                        "VALUES ($id, 'f', 'EPISODE', 'guid-$id', 'Folge', 'folge', '', '', '', '', 60000, 0, 1, 1, 2, $source)",
                )
                db.execSQL("INSERT INTO book_chapters (bookId, chapterIndex, title, startMs, endMs) VALUES ($id, 0, 'Intro', 0, 60000)")
            }
        }

        helper.runMigrationsAndValidate(DB_NAME, 30, true).use { db ->
            db.query("SELECT bookId, chapterSource FROM books ORDER BY bookId").use { cursor ->
                val sources = buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1)) }
                // Die Kapitelliste wird beim nächsten Abspielen neu geladen, jetzt mit Bild und Webseite.
                assertEquals(listOf(7L to null, 8L to "FEED", 9L to null), sources)
            }
            db.query("SELECT COUNT(*), COUNT(imageUrl), COUNT(url) FROM book_chapters").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
            }
            db.query("SELECT etag, lastModified FROM podcast_feeds WHERE feedId = 'f'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }
        }
    }

    private companion object {
        const val DB_NAME = "migration-test"
    }
}

/** `user.db`: Auto-Migrationen v1 → v6 ([UserDatabase]); die Sicherung eines alten Geräts muss auf einem neuen lesbar bleiben. */
@RunWith(RobolectricTestRunner::class)
class UserMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), UserDatabase::class.java)

    @Test
    fun `v1 to v6 keeps servers and podcasts and adds the newer tables empty`() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO servers (sourceId, type, name, baseUrl, username, priority, enabled) " +
                    "VALUES ('nav', 'SUBSONIC', 'Navidrome', 'https://nav.example/', 'anna', 2, 1)",
            )
            db.execSQL(
                "INSERT INTO podcasts (feedId, url, title, author, autoDownload, newestFirst, continuous, notifyNew) " +
                    "VALUES ('f', 'https://example.com/feed.xml', 'Logbuch', '', 1, 0, 1, 1)",
            )
        }

        for (version in 2..6) helper.runMigrationsAndValidate(DB_NAME, version, true).close()
        helper.runMigrationsAndValidate(DB_NAME, 6, true).use { db ->
            db.query("SELECT name, baseUrl, username, priority FROM servers WHERE sourceId = 'nav'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Navidrome", cursor.getString(0))
                assertEquals("https://nav.example/", cursor.getString(1))
                assertEquals("anna", cursor.getString(2))
                assertEquals(2, cursor.getInt(3))
            }
            db.query("SELECT title, autoDownload FROM podcasts WHERE feedId = 'f'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Logbuch", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
            for (table in listOf("book_progress", "playlists", "playlist_entries", "favorites", "play_events", "wear_selections")) {
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(table, 0, cursor.getInt(0))
                }
            }
        }
    }

    @Test
    fun `v5 to v6 drops the cast address and keeps the server`() {
        helper.createDatabase(DB_NAME, 5).use { db ->
            db.execSQL(
                "INSERT INTO servers (sourceId, type, name, baseUrl, username, priority, enabled, castBaseUrl) " +
                    "VALUES ('nav', 'SUBSONIC', 'Navidrome', 'https://nav.example/', 'anna', 0, 1, 'http://192.168.0.10:4533/')",
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, 6, true).use { db ->
            db.query("SELECT name, baseUrl FROM servers WHERE sourceId = 'nav'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Navidrome", cursor.getString(0))
                assertEquals("https://nav.example/", cursor.getString(1))
            }
        }
    }

    @Test
    fun `v7 to v8 keeps progress and adds empty episode downloads`() {
        helper.createDatabase(DB_NAME, 7).use { db ->
            db.execSQL(
                "INSERT INTO book_progress (serverId, remoteId, positionMs, durationMs, finished, speed, updatedAt, dirty) " +
                    "VALUES ('f', 'guid', 1000, 2000, 1, 1.0, 10, 0)",
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, 8, true).use { db ->
            db.query("SELECT finished FROM book_progress WHERE serverId = 'f' AND remoteId = 'guid'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM episode_downloads").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val DB_NAME = "user-migration-test"
    }
}
