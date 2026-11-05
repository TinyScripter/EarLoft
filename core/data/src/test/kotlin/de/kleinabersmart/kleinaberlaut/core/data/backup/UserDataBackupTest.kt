package de.kleinabersmart.kleinaberlaut.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dagger.Lazy
import de.kleinabersmart.kleinaberlaut.core.data.source.DataSourceRegistry
import de.kleinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import de.kleinabersmart.kleinaberlaut.core.database.UserDatabase
import de.kleinabersmart.kleinaberlaut.core.database.entity.BookEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.BookProgressEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.ListeningDayEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.ListeningStatEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.SourceEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.SpokenServerEntity
import de.kleinabersmart.kleinaberlaut.core.model.BookKind
import de.kleinabersmart.kleinaberlaut.core.model.LOCAL_BOOK_SERVER_ID
import de.kleinabersmart.kleinaberlaut.core.model.LOCAL_PODCAST_PREFIX
import de.kleinabersmart.kleinaberlaut.core.model.SourceType
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
    private lateinit var main: KleinaberlautDatabase
    private lateinit var user: UserDatabase

    @Before
    fun setUp() {
        main = Room.inMemoryDatabaseBuilder(context, KleinaberlautDatabase::class.java).allowMainThreadQueries().build()
        user = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        main.close()
        user.close()
    }

    /** Ein Gerät: Hauptdatenbank [db] und dieselbe [user]-Datenbank (die beim Gerätewechsel mitkommt). */
    private fun backupFor(db: KleinaberlautDatabase) = UserDataBackup(
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
        scope = TestScope(),
    )

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
    fun `a new device gets the saved data back with fresh sync state`() = runTest {
        fillMain()
        backupFor(main).mirror()

        val newDevice = Room.inMemoryDatabaseBuilder(context, KleinaberlautDatabase::class.java).allowMainThreadQueries().build()
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

    private suspend fun book(db: KleinaberlautDatabase, serverId: String, remoteId: String, kind: BookKind = BookKind.EPISODE) =
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

        val newDevice = Room.inMemoryDatabaseBuilder(context, KleinaberlautDatabase::class.java).allowMainThreadQueries().build()
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
