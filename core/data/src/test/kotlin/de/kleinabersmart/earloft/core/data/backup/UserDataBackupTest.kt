package de.kleinabersmart.earloft.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dagger.Lazy
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.data.spoken.ArdBooks
import de.kleinabersmart.earloft.core.data.spoken.testArdBooks
import de.kleinabersmart.earloft.core.model.ARD_BOOK_SERVER_ID
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.UserDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.BookmarkEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningDayEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningStatEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.database.entity.SpokenServerEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.LOCAL_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.model.LOCAL_PODCAST_PREFIX
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UserDataBackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var main: EarLoftDatabase
    private lateinit var user: UserDatabase

    @Before
    fun setUp() {
        main = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        user = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        main.close()
        user.close()
    }

    /** A device: main database [db] and the same [user] database (which comes along when switching devices). */
    private fun backupFor(db: EarLoftDatabase, ard: ArdBooks? = null) = UserDataBackup(
        context = context,
        database = db,
        userDao = user.userDataDao(),
        sourceDao = db.sourceDao(),
        bookDao = db.bookDao(),
        podcastDao = db.podcastDao(),
        statsDao = db.statsDao(),
        music = MusicBackup(db.backupDao(), db.playlistDao(), db.syncDao()),
        restoreNotice = RestoreNotice(context, { error("not needed") }, { error("not needed") }),
        registry = Lazy<DataSourceRegistry> { error("not needed") },
        ardBooks = { ard ?: error("not needed") },
        scope = TestScope(),
    )

    private fun ardAnswer(group: String = "urn:ard:show:gs") = MockResponse.Builder().body(
        """{"data":{"items":{"nodes":[{"id":"1","coreId":"urn:ard:publication:1","title":"Goldstrand (1/1)","groupId":"$group",
         "episodeNumber":1,"maxGroupNumber":1,"duration":1000,"publishDate":"2026-11-01T10:00:00+01:00",
         "group":{"type":"MULTIPART","title":"Katerina Poladjan: Goldstrand","count":1},
         "audios":[{"url":"https://a.example/1.mp3","downloadUrl":null,"allowDownload":false,"mimeType":"audio/mp3"}],
         "programSet":{"id":"1","coreId":"urn:ard:show:gs","title":"Katerina Poladjan: Goldstrand","publicationService":{"title":"MDR KULTUR"},
                       "editorialCategoryId":"42914713","editorialCategoriesList":[{"id":"42914713"}]}}]}}}""",
    ).build()

    private suspend fun fillMain() {
        main.sourceDao().upsert(
            SourceEntity(
                sourceId = "nav", type = SourceType.SUBSONIC, name = "Navidrome", baseUrl = "https://nav.example",
                username = "sebi", priority = 2, syncGeneration = 7, lastFullSyncAt = 123,
            ),
        )
        main.sourceDao().upsert(SourceEntity(sourceId = "local", type = SourceType.LOCAL, name = "Device"))
        main.bookDao().upsertServer(SpokenServerEntity("abs", "ABS", "https://abs.example", libraryId = "lib", syncGeneration = 3))
        main.podcastDao().upsertFeed(
            PodcastFeedEntity("feed-1", "https://example.org/feed", "Logbook", etag = "\"x\"", lastRefreshAt = 999, autoDownload = true),
        )
        main.podcastDao().upsertFeed(PodcastFeedEntity("${LOCAL_PODCAST_PREFIX}own", "${LOCAL_PODCAST_PREFIX}own", "Own folder"))
        main.statsDao().addListening(
            ListeningStatEntity(
                source = ListeningStatEntity.SOURCE_APP, kind = BookKind.EPISODE, groupKey = "https://example.org/feed",
                groupTitle = "Logbook", itemKey = "guid-1", itemTitle = "Episode 1", listenedMs = 60_000, lastListenedAt = 1_000,
            ),
            day = 20_000,
        )
    }

    @Test
    fun `mirror copies servers, feeds and statistics without sync state, local entries left out`() = runTest {
        fillMain()
        backupFor(main).mirror()

        val dao = user.userDataDao()
        assertEquals(listOf("nav"), dao.servers().map { it.sourceId })
        assertEquals("sebi", dao.servers().single().username)
        assertEquals(listOf("abs"), dao.spokenServers().map { it.serverId })
        assertEquals(listOf("feed-1"), dao.podcasts().map { it.feedId })
        assertTrue(dao.podcasts().single().autoDownload)
        assertEquals(60_000L, dao.listeningStats().single().listenedMs)
        assertEquals(60_000L, dao.listeningDays().single().listenedMs)
    }

    @Test
    fun `later mirrors write changes and deletions`() = runTest {
        fillMain()
        val backup = backupFor(main)
        backup.mirror()

        main.statsDao().addListening(
            ListeningStatEntity(
                source = ListeningStatEntity.SOURCE_APP, kind = BookKind.EPISODE, groupKey = "https://example.org/feed",
                groupTitle = "Logbook", itemKey = "guid-1", itemTitle = "Episode 1", listenedMs = 30_000, lastListenedAt = 2_000,
            ),
            day = 20_000,
        )
        main.podcastDao().deleteFeed("feed-1")
        main.sourceDao().setSyncGeneration("nav", 8, 456)
        backup.mirror()

        val dao = user.userDataDao()
        assertEquals(90_000L, dao.listeningStats().single().listenedMs)
        assertEquals(90_000L, dao.listeningDays().single().listenedMs)
        assertTrue(dao.podcasts().isEmpty())
        assertEquals(1, dao.servers().size)
    }

    @Test
    fun `a mirror after a change reads only the changed parts`() = runTest {
        fillMain()
        val backup = backupFor(main)
        backup.mirror()

        main.statsDao().addListening(
            ListeningStatEntity(
                source = ListeningStatEntity.SOURCE_APP, kind = BookKind.EPISODE, groupKey = "https://example.org/feed",
                groupTitle = "Logbook", itemKey = "guid-1", itemTitle = "Episode 1", listenedMs = 30_000, lastListenedAt = 2_000,
            ),
            day = 20_000,
        )
        main.podcastDao().deleteFeed("feed-1")
        backup.mirror(setOf("listening_stats", "listening_days"))

        val dao = user.userDataDao()
        assertEquals(90_000L, dao.listeningStats().single().listenedMs)
        // The subscriptions weren't re-read; the next change to them or the next full sync catches up.
        assertEquals(listOf("feed-1"), dao.podcasts().map { it.feedId })
        backup.mirror(setOf("podcast_feeds"))
        assertTrue(dao.podcasts().isEmpty())
    }

    @Test
    fun `a new device gets the saved data back with fresh sync state`() = runTest {
        fillMain()
        backupFor(main).mirror()

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        try {
            val backup = backupFor(newDevice)
            backup.restore(UserDataSnapshot.ofUser(
                user.userDataDao().servers(),
                user.userDataDao().spokenServers(),
                user.userDataDao().podcasts(),
                user.userDataDao().listeningStats(),
                user.userDataDao().listeningDays(),
            ))

            val source = newDevice.sourceDao().get("nav")!!
            assertEquals("https://nav.example", source.baseUrl)
            assertEquals(2, source.priority)
            assertEquals(0L, source.syncGeneration)
            assertEquals(0L, source.lastFullSyncAt)
            assertNull(newDevice.sourceDao().get("local"))
            assertEquals(0L, newDevice.bookDao().server("abs")!!.syncGeneration)
            val feed = newDevice.podcastDao().feed("feed-1")!!
            assertTrue(feed.autoDownload)
            assertNull(feed.etag)
            assertEquals(0L, feed.lastRefreshAt)
            assertEquals(60_000L, newDevice.statsDao().all().single().listenedMs)
            assertEquals(60_000L, newDevice.statsDao().allDays().single().listenedMs)
        } finally {
            newDevice.close()
        }
    }

    @Test
    fun `only subscriptions a restore created count as restored, also after a restart`() = runTest {
        fillMain()
        // Added here (OPML import offline) and not synced yet: no restored subscription.
        main.podcastDao().upsertFeed(PodcastFeedEntity("feed-2", "https://example.org/other", "Other"))
        val backup = backupFor(main)
        backup.mirror()
        val saved = UserDataSnapshot.ofUser(
            user.userDataDao().servers(),
            user.userDataDao().spokenServers(),
            user.userDataDao().podcasts(),
            user.userDataDao().listeningStats(),
            user.userDataDao().listeningDays(),
        )
        assertEquals(emptyList<String>(), backup.pendingOf(saved).restoredFeeds)

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        try {
            backupFor(newDevice).restore(saved)
            // A new instance, as after a restart before the first sync.
            assertEquals(setOf("feed-1", "feed-2"), backupFor(newDevice).pendingOf(saved).restoredFeeds.toSet())
        } finally {
            newDevice.close()
        }
    }

    @Test
    fun `a trial subscription with the address of a backed-up one becomes a real subscription`() = runTest {
        fillMain()
        backupFor(main).mirror()
        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        try {
            newDevice.podcastDao().upsertFeed(PodcastFeedEntity("trial", "https://example.org/feed", "Logbook", subscribed = false))

            backupFor(newDevice).restore(UserDataSnapshot.ofUser(
                user.userDataDao().servers(),
                user.userDataDao().spokenServers(),
                user.userDataDao().podcasts(),
                user.userDataDao().listeningStats(),
                user.userDataDao().listeningDays(),
            ))

            val feed = newDevice.podcastDao().feed("trial")!!
            assertTrue(feed.subscribed)
            assertTrue("Automatic download from the backup", feed.autoDownload)
            assertNull(newDevice.podcastDao().feed("feed-1"))
        } finally {
            newDevice.close()
        }
    }

    @Test
    fun `restoring keeps what the main database already has`() = runTest {
        fillMain()
        backupFor(main).mirror()
        main.sourceDao().upsert(main.sourceDao().get("nav")!!.copy(name = "Renamed"))
        main.statsDao().clear(BookKind.EPISODE)
        main.statsDao().addListening(
            ListeningStatEntity(
                source = ListeningStatEntity.SOURCE_APP, kind = BookKind.EPISODE, groupKey = "https://example.org/feed",
                groupTitle = "Logbook", itemKey = "guid-1", itemTitle = "Episode 1", listenedMs = 5_000, lastListenedAt = 3_000,
            ),
            day = 20_001,
        )

        backupFor(main).restore(UserDataSnapshot.ofUser(
            user.userDataDao().servers(),
            user.userDataDao().spokenServers(),
            user.userDataDao().podcasts(),
            user.userDataDao().listeningStats(),
            user.userDataDao().listeningDays(),
        ))

        assertEquals("Renamed", main.sourceDao().get("nav")!!.name)
        assertEquals(5_000L, main.statsDao().all().single().listenedMs)
        // The day from the backup was missing and is added; the new one stays.
        assertEquals(setOf(20_000L, 20_001L), main.statsDao().allDays().map { it.day }.toSet())
    }

    @Test
    fun `books from ARD Sounds are saved with progress and come back on a new device`() = runTest {
        val ardKey = "group:urn:ard:show:gs"
        val bookId = book(main, ARD_BOOK_SERVER_ID, ardKey, BookKind.BOOK)
        main.bookDao().updateBook(main.bookDao().book(bookId)!!.copy(addedAt = 4_242))
        main.bookDao().upsertProgress(progress(bookId, 500, updatedAt = 50))
        backupFor(main).mirror()

        val dao = user.userDataDao()
        assertEquals(listOf(ardKey to 4_242L), dao.ardBooks().map { it.remoteId to it.addedAt })
        assertEquals(500L, dao.progress().single().positionMs)

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(ardAnswer())
            val backup = backupFor(newDevice, testArdBooks(context, newDevice, server.url("/graphql")))
            val saved = dao.snapshot()
            // The book is still missing: it waits along with its progress.
            assertEquals(1, backup.applyArdBooks(saved.ardBooks.values).size)
            assertEquals(1, backup.applyProgress(saved.progress.values).size)

            assertTrue(backup.fetchArdBooks(saved.ardBooks.values))

            assertTrue(backup.applyArdBooks(saved.ardBooks.values).isEmpty())
            assertTrue(backup.applyProgress(saved.progress.values).isEmpty())
            val restored = newDevice.bookDao().keyedBooksOf(ARD_BOOK_SERVER_ID).single()
            assertEquals(ardKey, restored.remoteId)
            assertEquals(4_242L, restored.addedAt)
            val restoredId = newDevice.bookDao().bookByRemote(ARD_BOOK_SERVER_ID, ardKey)!!.bookId
            assertEquals(500L, newDevice.bookDao().progressOf(listOf(restoredId)).single().positionMs)
        } finally {
            newDevice.close()
            server.close()
        }
    }

    @Test
    fun `an ARD book whose group got a new id comes back with its progress`() = runTest {
        val ardKey = "group:urn:ard:show:gs"
        val bookId = book(main, ARD_BOOK_SERVER_ID, ardKey, BookKind.BOOK)
        main.bookDao().insertTracks(
            listOf(BookTrackEntity(bookId, 0, "https://a.example/1.mp3", 0, 1_000_000, "audio/mpeg", remoteItemId = "urn:ard:publication:1")),
        )
        main.bookDao().upsertProgress(progress(bookId, 500, updatedAt = 50))
        backupFor(main).mirror()

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        val server = MockWebServer().apply { start() }
        try {
            // Nothing under the old group; the backed-up title is now in a different one.
            server.enqueue(MockResponse.Builder().body("""{"data":{"items":{"nodes":[]}}}""").build())
            server.enqueue(ardAnswer(group = "urn:ard:show:new"))
            server.enqueue(ardAnswer(group = "urn:ard:show:new"))
            val backup = backupFor(newDevice, testArdBooks(context, newDevice, server.url("/graphql")))
            val saved = user.userDataDao().snapshot()

            assertTrue(backup.fetchArdBooks(saved.ardBooks.values))

            assertEquals(3, server.requestCount)
            assertTrue(backup.applyArdBooks(saved.ardBooks.values).isEmpty())
            assertTrue(backup.applyProgress(saved.progress.values).isEmpty())
            val restored = newDevice.bookDao().bookByRemote(ARD_BOOK_SERVER_ID, ardKey)!!
            assertEquals(500L, newDevice.bookDao().progressOf(listOf(restored.bookId)).single().positionMs)
        } finally {
            newDevice.close()
            server.close()
        }
    }

    @Test
    fun `ARD books are only fetched once the source is switched on`() = runTest {
        book(main, ARD_BOOK_SERVER_ID, "group:urn:ard:show:gs", BookKind.BOOK)
        backupFor(main).mirror()

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(ardAnswer())
            val settings = SpokenSettings(context)
            settings.setArdSoundsBooks(false)
            val backup = backupFor(newDevice, testArdBooks(context, newDevice, server.url("/graphql"), settings))
            val saved = user.userDataDao().snapshot().ardBooks.values

            withContext(Dispatchers.Default) {
                val restoring = launch { backup.restoreArdBooks { saved } }
                delay(300)
                // Off: ARD Sounds isn't queried, the restore waits.
                assertEquals(0, server.requestCount)
                assertTrue(restoring.isActive)

                settings.setArdSoundsBooks(true)
                withTimeout(10_000) { restoring.join() }
            }
            assertEquals(1, server.requestCount)
            assertEquals(1, newDevice.bookDao().keyedBooksOf(ARD_BOOK_SERVER_ID).size)
        } finally {
            newDevice.close()
            server.close()
        }
    }

    @Test
    fun `without a connection the ARD book is not fetched and stays to be restored`() = runTest {
        val dao = user.userDataDao()
        book(main, ARD_BOOK_SERVER_ID, "group:urn:ard:show:gs", BookKind.BOOK)
        backupFor(main).mirror()

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        val server = MockWebServer().apply { start() }
        val url = server.url("/graphql")
        server.close()
        try {
            val backup = backupFor(newDevice, testArdBooks(context, newDevice, url))
            val saved = dao.snapshot()

            assertFalse(backup.fetchArdBooks(saved.ardBooks.values))

            assertEquals(1, backup.applyArdBooks(saved.ardBooks.values).size)
            assertTrue(newDevice.bookDao().keyedBooksOf(ARD_BOOK_SERVER_ID).isEmpty())
            // A missing connection doesn't mean "the book no longer exists": it is queried again next time.
            assertTrue(SpokenSettings(context).ardGoneChecks().isEmpty())
        } finally {
            newDevice.close()
        }
    }

    @Test
    fun `an ARD book that ARD Sounds no longer has is asked for once a week, not at every start`() = runTest {
        val ardKey = "group:urn:ard:show:gs"
        book(main, ARD_BOOK_SERVER_ID, ardKey, BookKind.BOOK)
        backupFor(main).mirror()

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        val server = MockWebServer().apply { start() }
        try {
            val settings = SpokenSettings(context)
            val backup = backupFor(newDevice, testArdBooks(context, newDevice, server.url("/graphql"), settings))
            val saved = user.userDataDao().snapshot().ardBooks.values
            val empty = MockResponse.Builder().body("""{"data":{"items":{"nodes":[]}}}""").build()
            server.enqueue(empty)

            // An empty response alone can be a hiccup: it is queried again on the next start (review F6).
            assertTrue(backup.fetchArdBooks(saved))
            assertTrue(server.requestCount > 0)
            assertTrue(settings.ardGoneChecks().isEmpty())
            assertTrue(ardKey in settings.ardMissing())
            // If a later one confirms it, the book counts as gone.
            settings.setArdMissing(ardKey, System.currentTimeMillis() - 7 * 60 * 60 * 1000L)
            server.enqueue(empty)
            assertTrue(backup.fetchArdBooks(saved))
            val asked = server.requestCount
            assertTrue(ardKey in settings.ardGoneChecks())
            assertTrue(settings.ardMissing().isEmpty())

            // Next start: no query, the book stays backed up.
            assertTrue(backup.fetchArdBooks(saved))
            assertEquals(asked, server.requestCount)
            assertEquals(1, backup.applyArdBooks(saved).size)

            // After a week it's queried again; if the book is back, it comes and the marker disappears.
            settings.setArdGoneCheck(ardKey, 0L)
            server.enqueue(ardAnswer())
            assertTrue(backup.fetchArdBooks(saved))
            assertTrue(server.requestCount > asked)
            assertTrue(backup.applyArdBooks(saved).isEmpty())
            assertTrue(settings.ardGoneChecks().isEmpty())
        } finally {
            newDevice.close()
            server.close()
        }
    }

    @Test
    fun `removing an ARD book here also removes it, its progress and bookmarks from the backup`() = runTest {
        val ardKey = "group:urn:ard:show:gs"
        val bookId = book(main, ARD_BOOK_SERVER_ID, ardKey, BookKind.BOOK)
        main.bookDao().upsertProgress(progress(bookId, 5_000, updatedAt = 10))
        main.bookDao().insertBookmark(BookmarkEntity(bookId = bookId, positionMs = 4_000, title = "Here", createdAt = 1, updatedAt = 1))
        val backup = backupFor(main)
        backup.mirror()
        assertEquals(1, user.userDataDao().progress().size)
        assertEquals(1, user.userDataDao().bookmarks().size)

        main.bookDao().deleteBook(bookId)
        backup.mirror()

        assertTrue(user.userDataDao().ardBooks().isEmpty())
        // Otherwise they would apply again if the book is added months later (review2 J24).
        assertTrue(user.userDataDao().progress().isEmpty())
        assertTrue(user.userDataDao().bookmarks().isEmpty())
    }

    @Test
    fun `a first apply that fails keeps the backup waiting, the mirror deletes nothing that isn't here yet`() = runTest {
        val ardKey = "group:urn:ard:show:gs"
        val bookId = book(main, ARD_BOOK_SERVER_ID, ardKey, BookKind.BOOK)
        main.bookDao().upsertProgress(progress(bookId, 5_000, updatedAt = 10))
        backupFor(main).mirror()

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        try {
            val backup = backupFor(newDevice)
            // The ARD book isn't fetched yet, and the first apply at startup fails (e.g. full disk).
            backup.applyFirst(user.userDataDao().snapshot()) { throw android.database.sqlite.SQLiteFullException("full") }
            backup.mirror()

            // review3 Q7: without anything waiting the mirror deleted the book along with its progress.
            assertEquals(listOf(ardKey), user.userDataDao().ardBooks().map { it.remoteId })
            assertEquals(5_000L, user.userDataDao().progress().single().positionMs)
        } finally {
            newDevice.close()
        }
    }

    private suspend fun book(db: EarLoftDatabase, serverId: String, remoteId: String, kind: BookKind = BookKind.EPISODE) =
        db.bookDao().upsertBook(
            BookEntity(serverId = serverId, kind = kind, remoteId = remoteId, title = remoteId, normTitle = remoteId, author = "", durationMs = 1_000_000),
        )

    private fun progress(bookId: Long, positionMs: Long, updatedAt: Long, dirty: Boolean = false) =
        BookProgressEntity(bookId, positionMs = positionMs, durationMs = 1_000_000, updatedAt = updatedAt, dirty = dirty)

    @Test
    fun `progress is saved by book key, Audiobookshelf only while not reported`() = runTest {
        fillMain()
        val episode = book(main, "feed-1", "guid-1")
        val local = book(main, LOCAL_BOOK_SERVER_ID, "Author/Book", BookKind.BOOK)
        val reported = book(main, "abs", "item-1", BookKind.BOOK)
        val unreported = book(main, "abs", "item-2", BookKind.BOOK)
        main.bookDao().upsertProgress(progress(episode, 100, updatedAt = 10))
        main.bookDao().upsertProgress(progress(local, 200, updatedAt = 20))
        main.bookDao().upsertProgress(progress(reported, 300, updatedAt = 30))
        main.bookDao().upsertProgress(progress(unreported, 400, updatedAt = 40, dirty = true))
        val backup = backupFor(main)
        backup.mirror()

        val saved = user.userDataDao().progress().associate { (it.serverId to it.remoteId) to it.positionMs }
        assertEquals(
            mapOf(("feed-1" to "guid-1") to 100L, (LOCAL_BOOK_SERVER_ID to "Author/Book") to 200L, ("abs" to "item-2") to 400L),
            saved,
        )

        // Reported: the server has it, the backup no longer needs it.
        main.bookDao().markClean(unreported, updatedAt = 40)
        backup.mirror()
        assertFalse(user.userDataDao().progress().any { it.remoteId == "item-2" })
    }

    @Test
    fun `progress waits for its book on a new device and only wins when newer`() = runTest {
        fillMain()
        main.bookDao().upsertProgress(progress(book(main, "feed-1", "guid-1"), 100, updatedAt = 10))
        main.bookDao().upsertProgress(progress(book(main, "feed-1", "guid-2"), 200, updatedAt = 20))
        main.bookDao().upsertProgress(progress(book(main, "abs", "item-1", BookKind.BOOK), 300, updatedAt = 30, dirty = true))
        backupFor(main).mirror()

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        try {
            val backup = backupFor(newDevice)
            backup.restore(UserDataSnapshot.ofUser(
                user.userDataDao().servers(),
                user.userDataDao().spokenServers(),
                user.userDataDao().podcasts(),
                user.userDataDao().listeningStats(),
                user.userDataDao().listeningDays(),
            ))
            // Before the first sync there are no episodes: nothing is applied, and the mirror deletes nothing.
            assertEquals(3, backup.applyProgress(user.userDataDao().progress()).size)
            backup.mirror()
            assertEquals(3, user.userDataDao().progress().size)

            // The sync creates the episodes with new ids; one of them was already played further here.
            book(newDevice, "feed-0", "other")
            val first = book(newDevice, "feed-1", "guid-1")
            val second = book(newDevice, "feed-1", "guid-2")
            newDevice.bookDao().upsertProgress(progress(second, 999, updatedAt = 99))
            val pending = backup.applyProgress(user.userDataDao().progress())

            assertEquals(listOf("item-1"), pending.map { it.remoteId })
            assertEquals(100L, newDevice.bookDao().progress(first)!!.positionMs)
            assertEquals(999L, newDevice.bookDao().progress(second)!!.positionMs)

            // The Audiobookshelf state wasn't reported yet and stays so even after applying.
            val absBook = book(newDevice, "abs", "item-1", BookKind.BOOK)
            assertTrue(backup.applyProgress(pending).isEmpty())
            assertTrue(newDevice.bookDao().progress(absBook)!!.dirty)
        } finally {
            newDevice.close()
        }
    }

    @Test
    fun `a book taken out of continue listening stays out on a new device`() = runTest {
        fillMain()
        val local = book(main, LOCAL_BOOK_SERVER_ID, "Author/Book", BookKind.BOOK)
        main.bookDao().upsertProgress(progress(local, 200, updatedAt = 20).copy(hidden = true))
        backupFor(main).mirror()
        assertTrue(user.userDataDao().progress().single().hidden)

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        try {
            val again = book(newDevice, LOCAL_BOOK_SERVER_ID, "Author/Book", BookKind.BOOK)
            assertTrue(backupFor(newDevice).applyProgress(user.userDataDao().progress()).isEmpty())
            assertTrue(newDevice.bookDao().progress(again)!!.hidden)
        } finally {
            newDevice.close()
        }
    }

    @Test
    fun `ending a subscription drops its saved progress, also the not yet applied`() = runTest {
        fillMain()
        val backup = backupFor(main)
        main.bookDao().upsertProgress(progress(book(main, "feed-1", "guid-1"), 100, updatedAt = 10))
        backup.mirror()
        // The episode drops out of the feed: its progress stays backed up in case it comes back.
        main.podcastDao().deleteEpisodesOf("feed-1")
        backup.mirror()
        assertEquals(1, user.userDataDao().progress().size)

        main.podcastDao().deleteFeed("feed-1")
        backup.mirror()
        assertTrue(user.userDataDao().progress().isEmpty())
    }

    @Test
    fun `progress of trial subscriptions is not backed up, and the leftovers of a deleted trial are dropped`() = runTest {
        fillMain()
        main.podcastDao().upsertFeed(PodcastFeedEntity("trial", "https://example.org/trial", "Trial", subscribed = false))
        main.bookDao().upsertProgress(progress(book(main, "trial", "guid-t"), 100, updatedAt = 10))
        main.bookDao().upsertProgress(progress(book(main, "feed-1", "guid-1"), 100, updatedAt = 10))
        // Left over from an older version: progress of a trial the cleaner has deleted since.
        user.openHelper.writableDatabase.execSQL(
            "INSERT INTO book_progress (serverId, remoteId, positionMs, durationMs, finished, speed, updatedAt, dirty, hidden) " +
                "VALUES ('gone', 'guid-g', 5, 1000, 0, NULL, 1, 0, 0)",
        )

        backupFor(main).mirror()

        assertEquals(listOf("feed-1"), user.userDataDao().progress().map { it.serverId })
    }

    @Test
    fun `starred episodes are saved, wait for their episode on a new device and go with their subscription`() = runTest {
        fillMain()
        val first = book(main, "feed-1", "guid-1")
        val second = book(main, "feed-1", "guid-2")
        main.podcastDao().setStarred(listOf(first), 100)
        main.podcastDao().setStarred(listOf(second), 200)
        val backup = backupFor(main)
        backup.mirror()
        assertEquals(mapOf("guid-1" to 100L, "guid-2" to 200L), user.userDataDao().episodeStars().associate { it.remoteId to it.starredAt })

        // No longer remembered: drops out of the backup (only the `books` part read).
        main.podcastDao().setStarred(listOf(second), null)
        backup.mirror(setOf("books"))
        assertEquals(listOf("guid-1"), user.userDataDao().episodeStars().map { it.remoteId })

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        try {
            val restoring = backupFor(newDevice)
            val saved = user.userDataDao().episodeStars()
            // No episode yet: waits.
            assertEquals(saved, restoring.applyEpisodeStars(saved))
            val again = book(newDevice, "feed-1", "guid-1")
            assertTrue(restoring.applyEpisodeStars(saved).isEmpty())
            assertEquals(100L, newDevice.bookDao().book(again)!!.starredAt)
        } finally {
            newDevice.close()
        }

        main.podcastDao().deleteFeed("feed-1")
        backup.mirror()
        assertTrue(user.userDataDao().episodeStars().isEmpty())
    }

    private fun bookmark(
        bookId: Long,
        positionMs: Long,
        title: String,
        note: String = "",
        remoteTime: Double? = null,
        dirty: Boolean = false,
        deleted: Boolean = false,
    ) = BookmarkEntity(
        bookId = bookId, positionMs = positionMs, title = title, note = note, createdAt = positionMs, updatedAt = positionMs,
        remoteTime = remoteTime, dirty = dirty, deleted = deleted,
    )

    @Test
    fun `bookmarks are saved by book key, Audiobookshelf only what the server does not fully know`() = runTest {
        fillMain()
        val episode = book(main, "feed-1", "guid-1")
        val absBook = book(main, "abs", "item-1", BookKind.BOOK)
        main.bookDao().insertBookmark(bookmark(episode, 10_000, "Episode", note = "Note"))
        main.bookDao().insertBookmark(bookmark(episode, 20_000, "Deleted", deleted = true))
        main.bookDao().insertBookmark(bookmark(absBook, 30_000, "New", dirty = true))
        val known = main.bookDao().insertBookmark(bookmark(absBook, 40_000, "Known", remoteTime = 40.0))
        main.bookDao().insertBookmark(bookmark(absBook, 50_000, "With note", note = "only here", remoteTime = 50.0))
        main.bookDao().insertBookmark(bookmark(absBook, 60_000, "Renamed", remoteTime = 60.0, dirty = true))
        val backup = backupFor(main)
        backup.mirror()

        assertEquals(
            setOf(
                BookmarkKey("feed-1", "guid-1", 10_000),
                BookmarkKey("abs", "item-1", 30_000),
                BookmarkKey("abs", "item-1", 50_000),
                BookmarkKey("abs", "item-1", 60_000),
            ),
            user.userDataDao().bookmarks().map { it.key }.toSet(),
        )
        assertEquals("Note", user.userDataDao().bookmarks().single { it.remoteId == "guid-1" }.note)

        // Deleted here: also from the backup. If the known one gets a note, it's added.
        main.bookDao().deleteBookmark(main.bookDao().bookmarksOf(listOf(episode)).single { it.positionMs == 10_000L }.bookmarkId)
        main.bookDao().updateBookmark(main.bookDao().bookmark(known)!!.copy(note = "later"))
        backup.mirror(setOf("bookmarks"))
        val saved = user.userDataDao().bookmarks().map { it.key }.toSet()
        assertFalse(BookmarkKey("feed-1", "guid-1", 10_000) in saved)
        assertTrue(BookmarkKey("abs", "item-1", 40_000) in saved)

        // With the server, its bookmarks drop out of the backup.
        main.bookDao().deleteServer("abs")
        backup.mirror()
        assertTrue(user.userDataDao().bookmarks().none { it.serverId == "abs" })
    }

    @Test
    fun `bookmarks wait for their book on a new device, Audiobookshelf ones not yet on the server are reported again`() = runTest {
        fillMain()
        val episode = book(main, "feed-1", "guid-1")
        val absBook = book(main, "abs", "item-1", BookKind.BOOK)
        main.bookDao().insertBookmark(bookmark(episode, 10_000, "Episode", note = "Note"))
        main.bookDao().insertBookmark(bookmark(absBook, 30_000, "New", dirty = true))
        main.bookDao().insertBookmark(bookmark(absBook, 50_000, "With note", note = "only here", remoteTime = 50.0))
        backupFor(main).mirror()

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        try {
            val backup = backupFor(newDevice)
            val saved = user.userDataDao().snapshot()
            backup.restore(saved)
            // Before the sync the books don't exist: everything waits, and the mirror deletes nothing.
            assertEquals(3, backup.applyBookmarks(saved.bookmarks.values).size)
            backup.mirror()
            assertEquals(3, user.userDataDao().bookmarks().size)

            // The books come with new ids; the sync already fetched the bookmark with a note, without the note.
            book(newDevice, "feed-0", "other")
            val newEpisode = book(newDevice, "feed-1", "guid-1")
            val newAbsBook = book(newDevice, "abs", "item-1", BookKind.BOOK)
            newDevice.bookDao().insertBookmark(bookmark(newAbsBook, 50_000, "From server", remoteTime = 50.0))
            assertTrue(backup.applyBookmarks(saved.bookmarks.values).isEmpty())

            val episodeMarks = newDevice.bookDao().bookmarksOf(listOf(newEpisode))
            assertEquals(listOf("Episode" to "Note"), episodeMarks.map { it.title to it.note })
            assertFalse(episodeMarks.single().dirty)
            val absMarks = newDevice.bookDao().bookmarksOf(listOf(newAbsBook)).associateBy { it.positionMs }
            assertEquals(2, absMarks.size)
            // Not yet on the server: gets reported again.
            assertTrue(absMarks.getValue(30_000).dirty)
            assertNull(absMarks.getValue(30_000).remoteTime)
            // Already on the server: stays, only gets the note.
            assertEquals("From server", absMarks.getValue(50_000).title)
            assertEquals("only here", absMarks.getValue(50_000).note)
            assertFalse(absMarks.getValue(50_000).dirty)

            // Applying a second time creates nothing twice.
            assertTrue(backup.applyBookmarks(saved.bookmarks.values).isEmpty())
            assertEquals(3, newDevice.bookDao().bookmarksOf(listOf(newEpisode, newAbsBook)).size)
        } finally {
            newDevice.close()
        }
    }

    /** [fillMain] without the music server: restoring that one would reload the source registry, which these tests leave out. */
    private suspend fun fillMainWithoutMusic() {
        fillMain()
        main.sourceDao().delete("nav")
    }

    private fun noBackupFile(name: String) = File(context.noBackupFilesDir, name)

    @Test
    fun `a main database recreated empty does not empty the backup, it gets the backup back`() = runTest {
        fillMainWithoutMusic()
        backupFor(main).mirror()

        // Room recreated the main database (e.g. after corruption) without the creation time telling: the first mirror of the
        // next start finds no server, subscription or statistics at all.
        val recreated = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        try {
            backupFor(recreated).mirror()

            val dao = user.userDataDao()
            assertEquals(listOf("abs"), dao.spokenServers().map { it.serverId })
            assertEquals(listOf("feed-1"), dao.podcasts().map { it.feedId })
            assertEquals(60_000L, dao.listeningStats().single().listenedMs)
            assertEquals("https://abs.example", recreated.bookDao().server("abs")!!.baseUrl)
            assertEquals("https://example.org/feed", recreated.podcastDao().feed("feed-1")!!.url)
            assertEquals(60_000L, recreated.statsDao().all().single().listenedMs)
        } finally {
            recreated.close()
        }
    }

    @Test
    fun `a main database created after the last restore gets the backup back instead of being mirrored`() = runTest {
        fillMainWithoutMusic()
        val backup = backupFor(main)
        backup.mirror()
        val restored = noBackupFile("user_data_restored")
        val created = noBackupFile(EarLoftDatabase.CREATED_MARKER)
        try {
            restored.createNewFile()
            restored.setLastModified(1_000_000)
            // Recreated while the app runs: the earlier mirror in this process does not hold the backup back.
            main.podcastDao().deleteFeed("feed-1")
            main.bookDao().deleteServer("abs")
            created.createNewFile()
            created.setLastModified(2_000_000)

            backup.mirror()

            val dao = user.userDataDao()
            assertEquals(listOf("feed-1"), dao.podcasts().map { it.feedId })
            assertEquals(listOf("abs"), dao.spokenServers().map { it.serverId })
            assertTrue(main.podcastDao().feed("feed-1") != null)
            assertTrue(main.bookDao().server("abs") != null)
            assertTrue(restored.lastModified() > created.lastModified())

            // Restored once: from now on removals are mirrored again.
            main.podcastDao().deleteFeed("feed-1")
            backup.mirror()
            assertTrue(dao.podcasts().isEmpty())
        } finally {
            restored.delete()
            created.delete()
        }
    }

    @Test
    fun `removing everything while the app runs is mirrored`() = runTest {
        fillMainWithoutMusic()
        val backup = backupFor(main)
        backup.mirror()

        main.podcastDao().deleteFeed("feed-1")
        main.bookDao().deleteServer("abs")
        main.statsDao().clear(BookKind.EPISODE)
        backup.mirror()

        val dao = user.userDataDao()
        assertTrue(dao.podcasts().isEmpty())
        assertTrue(dao.spokenServers().isEmpty())
        assertTrue(dao.listeningStats().isEmpty())
        assertTrue(main.podcastDao().feed("feed-1") == null)
    }

    @Test
    fun `unchanged data produces no changes`() {
        val snapshot = UserDataSnapshot.ofMain(
            sources = listOf(SourceEntity(sourceId = "nav", type = SourceType.SUBSONIC, name = "Navidrome")),
            spokenServers = emptyList(),
            feeds = emptyList(),
            listeningStats = emptyList(),
            listeningDays = listOf(ListeningDayEntity(1, BookKind.BOOK, "abs", 10)),
        )
        val synced = snapshot.copy(
            servers = snapshot.servers.mapValues { it.value.copy() },
        )
        assertTrue(synced.changesSince(snapshot).isEmpty)
    }
}
