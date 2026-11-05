package de.kleinabersmart.kleinaberlaut.core.data.podcast

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import de.kleinabersmart.kleinaberlaut.core.database.entity.BookProgressEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.ListeningStatEntity
import de.kleinabersmart.kleinaberlaut.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.kleinaberlaut.core.network.podcast.ardsounds.ArdSoundsApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseImporterTest {

    private lateinit var db: KleinaberlautDatabase
    private lateinit var repository: PodcastRepository
    private lateinit var importer: DatabaseImporter
    private lateinit var feedId: String

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KleinaberlautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(FEED.toResponseBody("application/rss+xml".toMediaType()))
                    .build()
            }
            .build()
        repository = PodcastRepository(db.podcastDao(), db.bookDao(), PodcastFeedApi(client), ArdSoundsApi(client))
        importer = DatabaseImporter(repository, db.podcastDao(), db.bookDao(), db.statsDao())
        feedId = (repository.subscribe(FEED_URL) as SubscribeResult.Added).feedId
    }

    @After
    fun tearDown() = db.close()

    private suspend fun bookId(guid: String) = db.bookDao().booksOf(feedId).single { it.remoteId == guid }.bookId

    private fun episode(guid: String, played: Boolean = false, position: Long = 0, listened: Long = 0, at: Long = 1_000, feed: String = FEED_URL) =
        ImportEpisode(feed, guid, "Titel $guid", 600_000, position, played, listened, at)

    private val backup = ImportData(
        feeds = listOf(ImportFeed(FEED_URL, "Test", active = true), ImportFeed(OLD_URL, "Altes Abo", active = false)),
        episodes = listOf(
            episode("guid-A", played = true, listened = 500_000, at = 2_000),
            episode("guid-B", position = 120_000, listened = 130_000, at = 3_000),
            episode("guid-weg", played = true, listened = 60_000),
            episode("old-1", played = true, listened = 1_000_000, feed = OLD_URL),
        ),
    )

    @Test
    fun `progress is applied to known episodes and listening time is kept for all`() = runTest {
        val result = importer.import(backup)

        assertEquals(DatabaseImportResult(feedsAdded = 0, progressApplied = 2, progressKept = 0, progressMissing = 2, statsImported = 4), result)
        val a = db.bookDao().progress(bookId("guid-A"))!!
        assertEquals(true, a.finished)
        assertEquals(600_000, a.positionMs)
        assertEquals(2_000, a.updatedAt)
        val b = db.bookDao().progress(bookId("guid-B"))!!
        assertEquals(false, b.finished)
        assertEquals(120_000, b.positionMs)
        val stats = db.statsDao().all()
        assertEquals(1_690_000, stats.sumOf { it.listenedMs })
        assertEquals(setOf(ListeningStatEntity.SOURCE_IMPORTED), stats.map { it.source }.toSet())
        assertEquals("Altes Abo", stats.single { it.itemKey == "old-1" }.groupTitle)
        // Das archivierte Abo wird nicht abonniert.
        assertNull(db.podcastDao().feedByUrl(OLD_URL))
    }

    @Test
    fun `newer progress in the app stays and a second import does not duplicate statistics`() = runTest {
        val a = bookId("guid-A")
        db.bookDao().upsertProgress(BookProgressEntity(a, positionMs = 42_000, durationMs = 600_000, finished = false, updatedAt = 9_999))

        val first = importer.import(backup)
        val second = importer.import(backup)

        assertEquals(1, first.progressKept)
        assertEquals(42_000, db.bookDao().progress(a)!!.positionMs)
        assertEquals(4, second.statsImported)
        assertEquals(4, db.statsDao().all().size)
        assertNotNull(db.bookDao().progress(bookId("guid-B")))
    }

    private companion object {
        const val FEED_URL = "https://example.com/feed.xml"
        const val OLD_URL = "https://old.example.com/feed.xml"
        val FEED = """<rss version="2.0"><channel><title>Test</title>
            <item><title>A</title><guid>guid-A</guid><enclosure url="https://example.com/A.mp3" type="audio/mpeg"/></item>
            <item><title>B</title><guid>guid-B</guid><enclosure url="https://example.com/B.mp3" type="audio/mpeg"/></item>
            </channel></rss>"""
    }
}
