package de.kleinabersmart.earloft.core.data.podcast

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseExporterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: EarLoftDatabase
    private lateinit var importer: DatabaseImporter
    private lateinit var exporter: DatabaseExporter

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
        val repository = PodcastRepository(db, db.podcastDao(), db.bookDao(), PodcastFeedApi(client), ArdSoundsApi(client))
        importer = DatabaseImporter(repository, db.podcastDao(), db.bookDao(), db.statsDao())
        exporter = DatabaseExporter(db.podcastDao(), db.bookDao(), db.statsDao())
        repository.subscribe(FEED_URL)
    }

    @After
    fun tearDown() = db.close()

    private fun episode(guid: String, played: Boolean = false, position: Long = 0, listened: Long = 0, at: Long = 1_000, feed: String = FEED_URL) =
        ImportEpisode(feed, guid, "Titel $guid", 600_000, position, played, listened, at)

    @Test
    fun `an export reads back as the same progress and listening time`() = runTest {
        importer.import(
            ImportData(
                feeds = listOf(ImportFeed(FEED_URL, "Test", active = true), ImportFeed(OLD_URL, "Altes Abo", active = false)),
                episodes = listOf(
                    episode("guid-A", played = true, listened = 500_000, at = 2_000),
                    episode("guid-B", position = 120_000, listened = 130_000, at = 3_000),
                    episode("guid-weg", listened = 60_000),
                    episode("old-1", listened = 1_000_000, feed = OLD_URL),
                ),
            ),
        )
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "local:x", url = "local:x", title = "Gerät"))
        val file = folder.newFile("export.db")

        val result = exporter.export(file)

        assertEquals(DatabaseExportResult(feeds = 2, episodes = 4), result)
        val read = DatabaseReader.read(file)
        assertEquals(listOf(ImportFeed(FEED_URL, "Test-Podcast", true), ImportFeed(OLD_URL, "Altes Abo", false)), read.feeds.sortedBy { !it.active })
        val byGuid = read.episodes.associateBy { it.guid }
        assertEquals(setOf("guid-A", "guid-B", "guid-weg", "old-1"), byGuid.keys)
        with(byGuid.getValue("guid-A")) {
            assertEquals(true, played)
            assertEquals(500_000, listenedMs)
            assertEquals(2_000, lastPlayedAt)
        }
        with(byGuid.getValue("guid-B")) {
            assertEquals(false, played)
            assertEquals(120_000, positionMs)
            assertEquals(130_000, listenedMs)
        }
        assertEquals(60_000, byGuid.getValue("guid-weg").listenedMs)
        assertEquals(OLD_URL, byGuid.getValue("old-1").feedUrl)
    }

    @Test
    fun `exporting twice replaces the file`() = runTest {
        val file = folder.newFile("export.db")

        exporter.export(file)
        val again = exporter.export(file)

        assertEquals(DatabaseExportResult(feeds = 1, episodes = 2), again)
        assertEquals(1, DatabaseReader.read(file).feeds.size)
    }

    private companion object {
        const val FEED_URL = "https://example.com/feed.xml"
        const val OLD_URL = "https://old.example.com/feed.xml"
        val FEED = """<rss version="2.0"><channel><title>Test-Podcast</title>
            <item><title>A</title><guid>guid-A</guid><enclosure url="https://example.com/A.mp3" type="audio/mpeg"/></item>
            <item><title>B</title><guid>guid-B</guid><enclosure url="https://example.com/B.mp3" type="audio/mpeg"/></item>
            </channel></rss>"""
    }
}
