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

    /** Ein Gerät: Hauptdatenbank [db] und dieselbe [user]-Datenbank (die beim Gerätewechsel mitkommt). */
    private fun backupFor(db: EarLoftDatabase, ard: ArdBooks? = null) = UserDataBackup(
        context = context,
        database = db,
        userDao = user.userDataDao(),
        sourceDao = db.sourceDao(),
        bookDao = db.bookDao(),
        podcastDao = db.podcastDao(),
        statsDao = db.statsDao(),
        music = MusicBackup(db.backupDao(), db.playlistDao(), db.syncDao()),
        restoreNotice = RestoreNotice(context, { error("nicht gebraucht") }, { error("nicht gebraucht") }),
        registry = Lazy<DataSourceRegistry> { error("nicht gebraucht") },
        ardBooks = { ard ?: error("nicht gebraucht") },
        scope = TestScope(),
    )

    private fun ardAnswer() = MockResponse.Builder().body(
        """{"data":{"items":{"nodes":[{"id":"1","coreId":"urn:ard:publication:1","title":"Goldstrand (1/1)","groupId":"urn:ard:show:gs",
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
        main.sourceDao().upsert(SourceEntity(sourceId = "local", type = SourceType.LOCAL, name = "Gerät"))
        main.bookDao().upsertServer(SpokenServerEntity("abs", "ABS", "https://abs.example", libraryId = "lib", syncGeneration = 3))
        main.podcastDao().upsertFeed(
            PodcastFeedEntity("feed-1", "https://example.org/feed", "Logbuch", etag = "\"x\"", lastRefreshAt = 999, autoDownload = true),
        )
        main.podcastDao().upsertFeed(PodcastFeedEntity("${LOCAL_PODCAST_PREFIX}eigen", "${LOCAL_PODCAST_PREFIX}eigen", "Eigener Ordner"))
        main.statsDao().addListening(
            ListeningStatEntity(
                source = ListeningStatEntity.SOURCE_APP, kind = BookKind.EPISODE, groupKey = "https://example.org/feed",
                groupTitle = "Logbuch", itemKey = "guid-1", itemTitle = "Folge 1", listenedMs = 60_000, lastListenedAt = 1_000,
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
                groupTitle = "Logbuch", itemKey = "guid-1", itemTitle = "Folge 1", listenedMs = 30_000, lastListenedAt = 2_000,
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
                groupTitle = "Logbuch", itemKey = "guid-1", itemTitle = "Folge 1", listenedMs = 30_000, lastListenedAt = 2_000,
            ),
            day = 20_000,
        )
        main.podcastDao().deleteFeed("feed-1")
        backup.mirror(setOf("listening_stats", "listening_days"))

        val dao = user.userDataDao()
        assertEquals(90_000L, dao.listeningStats().single().listenedMs)
        // Die Abos wurden nicht neu gelesen; das holt die nächste Änderung an ihnen bzw. der nächste volle Abgleich nach.
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
    fun `restoring keeps what the main database already has`() = runTest {
        fillMain()
        backupFor(main).mirror()
        main.sourceDao().upsert(main.sourceDao().get("nav")!!.copy(name = "Neu benannt"))
        main.statsDao().clear(BookKind.EPISODE)
        main.statsDao().addListening(
            ListeningStatEntity(
                source = ListeningStatEntity.SOURCE_APP, kind = BookKind.EPISODE, groupKey = "https://example.org/feed",
                groupTitle = "Logbuch", itemKey = "guid-1", itemTitle = "Folge 1", listenedMs = 5_000, lastListenedAt = 3_000,
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

        assertEquals("Neu benannt", main.sourceDao().get("nav")!!.name)
        assertEquals(5_000L, main.statsDao().all().single().listenedMs)
        // Der Tag aus der Sicherung fehlte und kommt dazu; der neue bleibt.
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
            // Das Buch fehlt noch: Es wartet samt Fortschritt.
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
                // Aus: ARD Sounds wird nicht gefragt, das Zurückholen wartet.
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
            // Fehlende Verbindung heißt nicht „es gibt das Buch nicht mehr“: Beim nächsten Mal wird wieder gefragt.
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
            server.enqueue(MockResponse.Builder().body("""{"data":{"items":{"nodes":[]}}}""").build())

            assertTrue(backup.fetchArdBooks(saved))
            val asked = server.requestCount
            assertTrue(asked > 0)
            assertTrue(ardKey in settings.ardGoneChecks())

            // Nächster Start: keine Anfrage, das Buch bleibt gesichert.
            assertTrue(backup.fetchArdBooks(saved))
            assertEquals(asked, server.requestCount)
            assertEquals(1, backup.applyArdBooks(saved).size)

            // Nach einer Woche wird wieder gefragt; ist das Buch zurück, kommt es und der Merker verschwindet.
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
    fun `removing an ARD book here also removes it from the backup`() = runTest {
        val ardKey = "group:urn:ard:show:gs"
        val bookId = book(main, ARD_BOOK_SERVER_ID, ardKey, BookKind.BOOK)
        val backup = backupFor(main)
        backup.mirror()

        main.bookDao().deleteBook(bookId)
        backup.mirror()

        assertTrue(user.userDataDao().ardBooks().isEmpty())
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
        val local = book(main, LOCAL_BOOK_SERVER_ID, "Autor/Buch", BookKind.BOOK)
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
            mapOf(("feed-1" to "guid-1") to 100L, (LOCAL_BOOK_SERVER_ID to "Autor/Buch") to 200L, ("abs" to "item-2") to 400L),
            saved,
        )

        // Gemeldet: Der Server hat ihn, die Sicherung braucht ihn nicht mehr.
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
            // Vor dem ersten Abgleich gibt es keine Folgen: Nichts wird angewendet, und der Spiegel löscht nichts.
            assertEquals(3, backup.applyProgress(user.userDataDao().progress()).size)
            backup.mirror()
            assertEquals(3, user.userDataDao().progress().size)

            // Der Abgleich legt die Folgen mit neuen Ids an; eine davon wurde hier schon weiter gehört.
            book(newDevice, "feed-0", "andere")
            val first = book(newDevice, "feed-1", "guid-1")
            val second = book(newDevice, "feed-1", "guid-2")
            newDevice.bookDao().upsertProgress(progress(second, 999, updatedAt = 99))
            val pending = backup.applyProgress(user.userDataDao().progress())

            assertEquals(listOf("item-1"), pending.map { it.remoteId })
            assertEquals(100L, newDevice.bookDao().progress(first)!!.positionMs)
            assertEquals(999L, newDevice.bookDao().progress(second)!!.positionMs)

            // Der Audiobookshelf-Stand war noch nicht gemeldet und bleibt es auch nach dem Anwenden.
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
        val local = book(main, LOCAL_BOOK_SERVER_ID, "Autor/Buch", BookKind.BOOK)
        main.bookDao().upsertProgress(progress(local, 200, updatedAt = 20).copy(hidden = true))
        backupFor(main).mirror()
        assertTrue(user.userDataDao().progress().single().hidden)

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        try {
            val again = book(newDevice, LOCAL_BOOK_SERVER_ID, "Autor/Buch", BookKind.BOOK)
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
        // Die Folge fällt aus dem Feed: Ihr Fortschritt bleibt gesichert, falls sie wiederkommt.
        main.podcastDao().deleteEpisodesOf("feed-1")
        backup.mirror()
        assertEquals(1, user.userDataDao().progress().size)

        main.podcastDao().deleteFeed("feed-1")
        backup.mirror()
        assertTrue(user.userDataDao().progress().isEmpty())
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

        // Nicht mehr gemerkt: fällt aus der Sicherung (nur Teil `books` gelesen).
        main.podcastDao().setStarred(listOf(second), null)
        backup.mirror(setOf("books"))
        assertEquals(listOf("guid-1"), user.userDataDao().episodeStars().map { it.remoteId })

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        try {
            val restoring = backupFor(newDevice)
            val saved = user.userDataDao().episodeStars()
            // Noch ohne Folge: wartet.
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
        main.bookDao().insertBookmark(bookmark(episode, 10_000, "Folge", note = "Notiz"))
        main.bookDao().insertBookmark(bookmark(episode, 20_000, "Gelöscht", deleted = true))
        main.bookDao().insertBookmark(bookmark(absBook, 30_000, "Neu", dirty = true))
        val known = main.bookDao().insertBookmark(bookmark(absBook, 40_000, "Bekannt", remoteTime = 40.0))
        main.bookDao().insertBookmark(bookmark(absBook, 50_000, "Mit Notiz", note = "nur hier", remoteTime = 50.0))
        main.bookDao().insertBookmark(bookmark(absBook, 60_000, "Umbenannt", remoteTime = 60.0, dirty = true))
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
        assertEquals("Notiz", user.userDataDao().bookmarks().single { it.remoteId == "guid-1" }.note)

        // Hier gelöscht: auch aus der Sicherung. Bekommt das bekannte eine Notiz, kommt es dazu.
        main.bookDao().deleteBookmark(main.bookDao().bookmarksOf(listOf(episode)).single { it.positionMs == 10_000L }.bookmarkId)
        main.bookDao().updateBookmark(main.bookDao().bookmark(known)!!.copy(note = "später"))
        backup.mirror(setOf("bookmarks"))
        val saved = user.userDataDao().bookmarks().map { it.key }.toSet()
        assertFalse(BookmarkKey("feed-1", "guid-1", 10_000) in saved)
        assertTrue(BookmarkKey("abs", "item-1", 40_000) in saved)

        // Mit dem Server fallen seine Lesezeichen aus der Sicherung.
        main.bookDao().deleteServer("abs")
        backup.mirror()
        assertTrue(user.userDataDao().bookmarks().none { it.serverId == "abs" })
    }

    @Test
    fun `bookmarks wait for their book on a new device, Audiobookshelf ones not yet on the server are reported again`() = runTest {
        fillMain()
        val episode = book(main, "feed-1", "guid-1")
        val absBook = book(main, "abs", "item-1", BookKind.BOOK)
        main.bookDao().insertBookmark(bookmark(episode, 10_000, "Folge", note = "Notiz"))
        main.bookDao().insertBookmark(bookmark(absBook, 30_000, "Neu", dirty = true))
        main.bookDao().insertBookmark(bookmark(absBook, 50_000, "Mit Notiz", note = "nur hier", remoteTime = 50.0))
        backupFor(main).mirror()

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        try {
            val backup = backupFor(newDevice)
            val saved = user.userDataDao().snapshot()
            backup.restore(saved)
            // Vor dem Abgleich gibt es die Bücher nicht: Alles wartet, und der Spiegel löscht nichts.
            assertEquals(3, backup.applyBookmarks(saved.bookmarks.values).size)
            backup.mirror()
            assertEquals(3, user.userDataDao().bookmarks().size)

            // Die Bücher kommen mit neuen Ids; der Abgleich hat das Lesezeichen mit Notiz schon geholt, ohne Notiz.
            book(newDevice, "feed-0", "andere")
            val newEpisode = book(newDevice, "feed-1", "guid-1")
            val newAbsBook = book(newDevice, "abs", "item-1", BookKind.BOOK)
            newDevice.bookDao().insertBookmark(bookmark(newAbsBook, 50_000, "Vom Server", remoteTime = 50.0))
            assertTrue(backup.applyBookmarks(saved.bookmarks.values).isEmpty())

            val episodeMarks = newDevice.bookDao().bookmarksOf(listOf(newEpisode))
            assertEquals(listOf("Folge" to "Notiz"), episodeMarks.map { it.title to it.note })
            assertFalse(episodeMarks.single().dirty)
            val absMarks = newDevice.bookDao().bookmarksOf(listOf(newAbsBook)).associateBy { it.positionMs }
            assertEquals(2, absMarks.size)
            // Noch nicht beim Server: wird wieder gemeldet.
            assertTrue(absMarks.getValue(30_000).dirty)
            assertNull(absMarks.getValue(30_000).remoteTime)
            // Schon beim Server: bleibt, bekommt nur die Notiz.
            assertEquals("Vom Server", absMarks.getValue(50_000).title)
            assertEquals("nur hier", absMarks.getValue(50_000).note)
            assertFalse(absMarks.getValue(50_000).dirty)

            // Ein zweites Anwenden legt nichts doppelt an.
            assertTrue(backup.applyBookmarks(saved.bookmarks.values).isEmpty())
            assertEquals(3, newDevice.bookDao().bookmarksOf(listOf(newEpisode, newAbsBook)).size)
        } finally {
            newDevice.close()
        }
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
