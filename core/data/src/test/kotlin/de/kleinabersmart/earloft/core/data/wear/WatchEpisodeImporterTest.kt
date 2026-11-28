package de.kleinabersmart.earloft.core.data.wear

import de.kleinabersmart.earloft.core.data.spoken.testAudiobookshelfServers
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.spoken.AbsSessionReporter
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
import de.kleinabersmart.earloft.core.data.stats.StatsRepository
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchEpisodeImporterTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var importer: WatchEpisodeImporter
    private lateinit var sessions: AbsSessionReporter
    private var episodeId = 0L
    private var bookId = 0L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.podcastDao().upsertFeed(PodcastFeedEntity(FEED_ID, FEED_URL, "Podcast"))
        episodeId = db.bookDao().insertBook(
            BookEntity(serverId = FEED_ID, kind = BookKind.EPISODE, remoteId = GUID, title = "Episode", normTitle = "episode", author = "Podcast", durationMs = 3_600_000),
        )
        bookId = db.bookDao().insertBook(
            BookEntity(serverId = ABS_ID, kind = BookKind.BOOK, remoteId = ITEM_ID, title = "Book", normTitle = "book", author = "Author", durationMs = 36_000_000),
        )
        // Without a key no server is registered: sending fails immediately, without network.
        val secrets = object : SecretStore {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val api = AbsApi(OkHttpClient())
        val servers = testAudiobookshelfServers(db.bookDao(), secrets, api)
        val policy = PlaybackPolicy()
        val stats = StatsRepository(db.statsDao(), db.bookDao(), db.podcastDao())
        sessions = AbsSessionReporter(context, db.absSessionDao(), db.bookDao(), servers, api, policy)
        importer = WatchEpisodeImporter(
            context,
            db,
            db.bookDao(),
            stats,
            BookProgressSync(context, db.bookDao(), servers, api, policy),
            sessions,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun progress(positionMs: Long, updatedAt: Long, finished: Boolean = false) =
        WatchEpisodeProgress(FEED_ID, GUID, positionMs, 3_600_000, finished, speed = 1.5f, updatedAt = updatedAt)

    @Test
    fun `newer progress from the watch wins`() = runTest {
        db.bookDao().upsertProgress(BookProgressEntity(episodeId, 60_000, 3_600_000, updatedAt = 1_000))

        assertEquals(1, importer.import("p1", listOf(progress(600_000, updatedAt = 2_000, finished = true)), emptyList()))

        val stored = db.bookDao().progress(episodeId)!!
        assertEquals(600_000, stored.positionMs)
        assertTrue(stored.finished)
        assertEquals(1.5f, stored.speed)
        assertEquals(2_000, stored.updatedAt)
    }

    @Test
    fun `progress from the watch keeps the effects set on the phone and the time a book was finished`() = runTest {
        db.bookDao().upsertProgress(
            BookProgressEntity(
                episodeId, 3_000_000, 3_600_000, finished = true, speed = 2f, updatedAt = 1_000, hidden = true,
                skipSilence = true, volumeBoostDb = 3, finishedAt = 500,
            ),
        )

        // The watch still has the tempo it got earlier (1.5) and no effects of its own.
        assertEquals(1, importer.import("p1", listOf(progress(600_000, updatedAt = 2_000, finished = true)), emptyList()))

        val stored = db.bookDao().progress(episodeId)!!
        assertEquals(600_000, stored.positionMs)
        assertEquals(2f, stored.speed)
        assertEquals(true, stored.skipSilence)
        assertEquals(3, stored.volumeBoostDb)
        assertEquals("finished since then, not since the watch reported", 500L, stored.finishedAt)
        assertEquals("playing again brings the book back into the lists", false, stored.hidden)
    }

    @Test
    fun `older progress from the watch is discarded`() = runTest {
        db.bookDao().upsertProgress(BookProgressEntity(episodeId, 60_000, 3_600_000, updatedAt = 5_000))

        assertEquals(0, importer.import("p1", listOf(progress(600_000, updatedAt = 4_000)), emptyList()))

        assertEquals(60_000, db.bookDao().progress(episodeId)!!.positionMs)
    }

    @Test
    fun `listened time counts once per packet`() = runTest {
        val listening = listOf(WatchEpisodeListening(FEED_ID, GUID, listenedMs = 90_000, at = 1_000))

        importer.import("p1", emptyList(), listening)
        importer.import("p1", emptyList(), listening)
        importer.import("p2", emptyList(), listening)

        val row = db.statsDao().all().single()
        assertEquals(FEED_URL, row.groupKey)
        assertEquals(GUID, row.itemKey)
        assertEquals(180_000, row.listenedMs)
    }

    @Test
    fun `a book from the watch goes to the server as pending progress and listening session`() = runTest {
        val progress = WatchEpisodeProgress(ABS_ID, ITEM_ID, 1_200_000, 36_000_000, finished = false, speed = 1f, updatedAt = 9_000)
        val listening = listOf(WatchEpisodeListening(ABS_ID, ITEM_ID, listenedMs = 300_000, at = 9_000))

        assertEquals(1, importer.import("p1", listOf(progress), listening))

        val stored = db.bookDao().progress(bookId)!!
        assertEquals(1_200_000, stored.positionMs)
        // Not reachable: stays open, the worker delivers later.
        assertTrue(stored.dirty)
        val session = db.absSessionDao().dirty().single()
        assertEquals(bookId, session.bookId)
        assertEquals(300_000, session.timeListeningMs)
        assertEquals(1_200_000, session.currentPositionMs)
        val stat = db.statsDao().all().single()
        assertEquals(BookKind.BOOK, stat.kind)
        assertEquals(ABS_ID, stat.groupKey)
        assertEquals(300_000, stat.listenedMs)
    }

    // A deadlock blocks threads in the database, which no coroutine timeout ends: JUnit's timeout fails the test instead.
    @Test(timeout = 20_000)
    fun `a book packet from the watch and playback on the phone record sessions side by side`() = runBlocking {
        // review3 Q1: the session reporter takes its lock and then writes; inside the import's transaction that waited for the
        // lock while the phone's playback held it and waited for the database.
        val listening = listOf(WatchEpisodeListening(ABS_ID, ITEM_ID, listenedMs = 1_000, at = 9_000))
        val phone = launch(Dispatchers.IO) {
            repeat(ROUNDS) { sessions.record(bookId, 1_000, 600_000, 36_000_000, now = 9_000) }
        }
        val watch = launch(Dispatchers.IO) {
            repeat(ROUNDS) { importer.import("p$it", emptyList(), listening) }
        }
        joinAll(phone, watch)
        assertEquals(2L * ROUNDS * 1_000, db.absSessionDao().dirty().sumOf { it.timeListeningMs })
    }

    @Test
    fun `episodes stay without server progress`() = runTest {
        importer.import("p1", listOf(progress(600_000, updatedAt = 2_000)), emptyList())
        assertEquals(false, db.bookDao().progress(episodeId)!!.dirty)
        assertTrue(db.absSessionDao().dirty().isEmpty())
    }

    @Test
    fun `unknown episodes are skipped`() = runTest {
        val unknown = WatchEpisodeProgress(FEED_ID, "other", 1, 2, false, 1f, 3)
        assertEquals(0, importer.import("p1", listOf(unknown), listOf(WatchEpisodeListening(FEED_ID, "other", 5_000, 1))))
        assertTrue(db.statsDao().all().isEmpty())
    }

    @Test
    fun `after a restore a packet with episodes not known yet waits for them`() = runTest {
        val listening = listOf(WatchEpisodeListening(FEED_ID, GUID, 5_000, 1), WatchEpisodeListening(FEED_ID, "other", 5_000, 1))

        assertEquals(null, importer.import("p1", listOf(progress(600_000, updatedAt = 2_000)), listening, deferUnknown = true))
        assertEquals(null, db.bookDao().progress(episodeId))
        assertTrue(db.statsDao().all().isEmpty())
        // Once the restore lets go, the same packet comes again and is imported as usual.
        assertEquals(1, importer.import("p1", listOf(progress(600_000, updatedAt = 2_000)), listening))
        assertEquals(5_000L, db.statsDao().all().single().listenedMs)
    }

    private companion object {
        const val FEED_ID = "feed-1"
        const val FEED_URL = "https://example.org/feed.xml"
        const val GUID = "guid-A"
        const val ABS_ID = "abs-1"
        const val ITEM_ID = "item-1"
        const val ROUNDS = 200
    }
}
