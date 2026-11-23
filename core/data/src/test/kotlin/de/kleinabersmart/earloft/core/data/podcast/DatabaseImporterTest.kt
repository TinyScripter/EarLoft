package de.kleinabersmart.earloft.core.data.podcast

import androidx.room.Room
import de.kleinabersmart.earloft.core.datasource.local.AudioFileChapters
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningStatEntity
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseImporterTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var repository: PodcastRepository
    private lateinit var importer: DatabaseImporter
    private lateinit var feedId: String

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
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
        repository = PodcastRepository(db, db.podcastDao(), db.bookDao(), PodcastFeedApi(client), ArdSoundsApi(client), PodcastSettings(ApplicationProvider.getApplicationContext()), AudioFileChapters(ApplicationProvider.getApplicationContext()), { error("no source switch in tests") }, testFeedCredentials(db), EpisodeQueue(ApplicationProvider.getApplicationContext(), db.bookDao()))
        importer = DatabaseImporter(repository, db.podcastDao(), db.bookDao(), db.statsDao())
        feedId = (repository.subscribe(FEED_URL) as SubscribeResult.Added).feedId
    }

    @After
    fun tearDown() = db.close()

    private suspend fun bookId(guid: String) = db.bookDao().booksOf(feedId).single { it.remoteId == guid }.bookId

    private fun episode(guid: String, played: Boolean = false, position: Long = 0, listened: Long = 0, at: Long = 1_000, feed: String = FEED_URL) =
        ImportEpisode(feed, guid, "Title $guid", 600_000, position, played, listened, at)

    private val backup = ImportData(
        feeds = listOf(ImportFeed(FEED_URL, "Test", active = true), ImportFeed(OLD_URL, "Old subscription", active = false)),
        episodes = listOf(
            episode("guid-A", played = true, listened = 500_000, at = 2_000),
            episode("guid-B", position = 120_000, listened = 130_000, at = 3_000),
            episode("guid-gone", played = true, listened = 60_000),
            episode("old-1", played = true, listened = 1_000_000, feed = OLD_URL),
        ),
    )

    @Test
    fun `progress is applied to known episodes and listening time is kept for all`() = runTest {
        val result = importer.import(backup)

        assertEquals(DatabaseImportResult(feedsAdded = 1, progressApplied = 2, progressKept = 0, progressMissing = 2, statsImported = 4), result)
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
        assertEquals("Old subscription", stats.single { it.itemKey == "old-1" }.groupTitle)
        // The archived subscription comes in as an archived subscription.
        assertEquals(true, db.podcastDao().feedByUrl(OLD_URL)!!.archived)
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

    @Test
    fun `subscriptions from ARD Sounds are imported, also as feeds of the RSS bridge`() = runTest {
        fun bridge(show: String) = "https://rss-bridge.org/bridge01/?action=display&bridge=ARDAudiothekBridge" +
            "&path=https%3A%2F%2Fwww.ardsounds.de%2Fsendung%2Fx%2Furn%3Aard%3Ashow%3A$show%2F&format=Atom"
        val ard = ImportData(
            feeds = listOf(
                // This is how older exports (and the export without RSS-Bridge) wrote a subscription: archived so other apps don't subscribe to it.
                ImportFeed("ardsounds:urn:ard:show:8e6d4d6fa453e7f7", "Kalk & Welk", active = false),
                ImportFeed(bridge("7ae4f72cbe4a75e2"), "Hateland", active = true),
                ImportFeed(bridge("b4ca0b835f537610"), "Satire Deluxe", active = false),
            ),
            episodes = emptyList(),
        )

        val result = importer.import(ard)

        assertEquals(3, result.feedsAdded)
        assertEquals(false, db.podcastDao().feedByUrl("ardsounds:urn:ard:show:8e6d4d6fa453e7f7")!!.archived)
        assertEquals(false, db.podcastDao().feedByUrl("ardsounds:urn:ard:show:7ae4f72cbe4a75e2")!!.archived)
        assertEquals(true, db.podcastDao().feedByUrl("ardsounds:urn:ard:show:b4ca0b835f537610")!!.archived)
    }

    @Test
    fun `an archived entry does not archive a subscription of the app`() = runTest {
        importer.import(ImportData(feeds = listOf(ImportFeed(FEED_URL, "Test", active = false)), episodes = emptyList()))

        assertEquals(false, db.podcastDao().feedByUrl(FEED_URL)!!.archived)
    }

    @Test
    fun `an archived subscription of the app stays archived and keeps its settings`() = runTest {
        repository.archive(feedId)
        db.podcastDao().setEffects(feedId, 1.5f, true, 3)

        val result = importer.import(
            ImportData(
                feeds = listOf(ImportFeed(FEED_URL, "Test", active = true, speed = 2f, skipSilence = false, volumeBoostDb = 0)),
                episodes = listOf(episode("guid-A", played = true, at = 2_000)),
            ),
        )

        val feed = db.podcastDao().feed(feedId)!!
        assertEquals(true, feed.archived)
        assertEquals(1.5f, feed.speed)
        assertEquals(0, result.feedsAdded)
        // Its progress is still matched.
        assertEquals(1, result.progressApplied)
    }

    @Test
    fun `an http address in the file finds the subscription stored under https`() = runTest {
        val result = importer.import(
            ImportData(
                feeds = listOf(ImportFeed(HTTP_URL, "Test", active = true)),
                episodes = listOf(episode("guid-B", position = 120_000, at = 3_000, feed = HTTP_URL)),
            ),
        )

        assertEquals(0, result.feedsAdded)
        assertEquals(1, result.progressApplied)
        assertEquals(1, db.podcastDao().keptFeeds().size)
        assertEquals(120_000, db.bookDao().progress(bookId("guid-B"))!!.positionMs)
    }

    private companion object {
        const val HTTP_URL = "http://example.com/feed.xml"
        const val FEED_URL = "https://example.com/feed.xml"
        const val OLD_URL = "https://old.example.com/feed.xml"
        val FEED = """<rss version="2.0"><channel><title>Test</title>
            <item><title>A</title><guid>guid-A</guid><enclosure url="https://example.com/A.mp3" type="audio/mpeg"/></item>
            <item><title>B</title><guid>guid-B</guid><enclosure url="https://example.com/B.mp3" type="audio/mpeg"/></item>
            </channel></rss>"""
    }
}
