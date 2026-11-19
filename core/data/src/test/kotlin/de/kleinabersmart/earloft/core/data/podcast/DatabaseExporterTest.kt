package de.kleinabersmart.earloft.core.data.podcast

import androidx.room.Room
import de.kleinabersmart.earloft.core.datasource.local.AudioFileChapters
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.model.SpokenEffects
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
    private lateinit var settings: SpokenSettings

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
        val repository = PodcastRepository(db, db.podcastDao(), db.bookDao(), PodcastFeedApi(client), ArdSoundsApi(client), PodcastSettings(ApplicationProvider.getApplicationContext()), AudioFileChapters(ApplicationProvider.getApplicationContext()), { error("no source switch in tests") }, testFeedCredentials(db), EpisodeQueue(ApplicationProvider.getApplicationContext(), db.bookDao()))
        importer = DatabaseImporter(repository, db.podcastDao(), db.bookDao(), db.statsDao())
        settings = SpokenSettings(ApplicationProvider.getApplicationContext())
        // The DataStore persists between tests in the same JVM.
        settings.setPodcastEffects(SpokenEffects())
        exporter = DatabaseExporter(db.podcastDao(), db.bookDao(), db.statsDao(), settings)
        repository.subscribe(FEED_URL)
    }

    @After
    fun tearDown() = db.close()

    private fun episode(guid: String, played: Boolean = false, position: Long = 0, listened: Long = 0, at: Long = 1_000, feed: String = FEED_URL) =
        ImportEpisode(feed, guid, "Title $guid", 600_000, position, played, listened, at)

    @Test
    fun `an export reads back as the same progress and listening time`() = runTest {
        importer.import(
            ImportData(
                feeds = listOf(ImportFeed(FEED_URL, "Test", active = true), ImportFeed(OLD_URL, "Old subscription", active = false)),
                episodes = listOf(
                    episode("guid-A", played = true, listened = 500_000, at = 2_000),
                    episode("guid-B", position = 120_000, listened = 130_000, at = 3_000),
                    episode("guid-gone", listened = 60_000),
                    episode("old-1", listened = 1_000_000, feed = OLD_URL),
                ),
            ),
        )
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "local:x", url = "local:x", title = "Device"))
        val file = folder.newFile("export.db")

        val result = exporter.export(file, rssBridge = null)

        // The archived subscription (the test feed delivers the same two episodes) with its listening time without an episode in the app.
        assertEquals(DatabaseExportResult(feeds = 2, episodes = 6), result)
        val read = DatabaseReader.read(file)
        assertEquals(listOf(ImportFeed(FEED_URL, "Test-Podcast", true, volumeBoostDb = 0), ImportFeed(OLD_URL, "Test-Podcast", false, volumeBoostDb = 0)), read.feeds.sortedBy { !it.active })
        // Only what has progress or listening time is read.
        assertEquals(listOf("old-1"), read.episodes.filter { it.feedUrl == OLD_URL }.map { it.guid })
        val byGuid = read.episodes.filter { it.feedUrl != OLD_URL }.associateBy { it.guid }
        assertEquals(setOf("guid-A", "guid-B", "guid-gone"), byGuid.keys)
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
        assertEquals(60_000, byGuid.getValue("guid-gone").listenedMs)
    }

    @Test
    fun `starred episodes go out as favorites and come back starred`() = runTest {
        val feedId = db.podcastDao().feedByUrl(FEED_URL)!!.feedId
        val starred = db.bookDao().booksOf(feedId).first { it.remoteId == "guid-B" }
        db.podcastDao().setStarred(listOf(starred.bookId), 500)
        val file = folder.newFile("stars.db")
        exporter.export(file, rssBridge = null)

        // The remembered episode is read even without progress.
        val read = DatabaseReader.read(file)
        assertEquals(listOf("guid-B" to true), read.episodes.map { it.guid to it.starred })

        db.podcastDao().setStarred(listOf(starred.bookId), null)
        val result = importer.import(read)
        assertEquals(1, result.starred)
        assertEquals(true, db.bookDao().book(starred.bookId)!!.starredAt != null)
    }

    @Test
    fun `shows from ARD Sounds go out as feeds of the RSS bridge and come back as subscriptions`() = runTest {
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "ard", url = ARD_URL, title = "Kalk & Welk"))
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "ard-old", url = ARD_ARCHIVED_URL, title = "Satire Deluxe", archived = true))
        val file = folder.newFile("export.db")

        exporter.export(file, rssBridge = "https://bridge.example.com/")

        val read = DatabaseReader.read(file).feeds.filter { "bridge.example.com" in it.url }
        assertEquals(listOf(true, false), read.sortedBy { !it.active }.map { it.active })
        // In a fresh app: the bridge feeds become shows from ARD Sounds again, the archived one stays archived.
        db.clearAllTables()
        importer.import(DatabaseReader.read(file))
        assertEquals(false, db.podcastDao().feedByUrl(ARD_URL)!!.archived)
        assertEquals(true, db.podcastDao().feedByUrl(ARD_ARCHIVED_URL)!!.archived)
    }

    @Test
    fun `without a bridge shows from ARD Sounds go out archived and still come back as subscriptions`() = runTest {
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "ard", url = ARD_URL, title = "Kalk & Welk"))
        val file = folder.newFile("export.db")

        exporter.export(file, rssBridge = null)

        assertEquals(ImportFeed(ARD_URL, "Kalk & Welk", active = false, volumeBoostDb = 0), DatabaseReader.read(file).feeds.single { it.url == ARD_URL })
        db.clearAllTables()
        importer.import(DatabaseReader.read(file))
        assertEquals(false, db.podcastDao().feedByUrl(ARD_URL)!!.archived)
    }

    @Test
    fun `speed, effects, intro and outro of a subscription go out and come back`() = runTest {
        val feedId = db.podcastDao().feedByUrl(FEED_URL)!!.feedId
        db.podcastDao().setEffects(feedId, speed = 1.5f, skipSilence = true, volumeBoostDb = 6)
        db.podcastDao().setSkipIntroOutro(feedId, introS = 30, outroS = 45)
        val file = folder.newFile("export.db")

        exporter.export(file, rssBridge = null)

        val expected = ImportFeed(FEED_URL, "Test-Podcast", true, speed = 1.5f, skipSilence = true, volumeBoostDb = 6, skipIntroS = 30, skipOutroS = 45)
        assertEquals(expected, DatabaseReader.read(file).feeds.single())
        // In a fresh app the newly created subscription gets the values.
        db.clearAllTables()
        importer.import(DatabaseReader.read(file))
        with(db.podcastDao().feedByUrl(FEED_URL)!!) {
            assertEquals(listOf<Any?>(1.5f, true, 6, 30, 45), listOf(speed, skipSilence, volumeBoostDb, skipIntroS, skipOutroS))
        }
    }

    @Test
    fun `an explicit boost of 0 dB is read as 0 and not as the default of the area`() = runTest {
        val feedId = db.podcastDao().feedByUrl(FEED_URL)!!.feedId
        db.podcastDao().setEffects(feedId, speed = null, skipSilence = null, volumeBoostDb = 0)
        val file = folder.newFile("export.db")

        exporter.export(file, rssBridge = null)

        assertEquals(0, DatabaseReader.read(file).feeds.single().volumeBoostDb)
        db.clearAllTables()
        importer.import(DatabaseReader.read(file))
        assertEquals(0, db.podcastDao().feedByUrl(FEED_URL)!!.volumeBoostDb)
    }

    @Test
    fun `a subscription without its own boost goes out with the boost of the area`() = runTest {
        settings.setPodcastEffects(SpokenEffects(volumeBoostDb = 6))
        val file = folder.newFile("export.db")

        exporter.export(file, rssBridge = null)

        assertEquals(6, DatabaseReader.read(file).feeds.single().volumeBoostDb)
    }

    @Test
    fun `an existing subscription keeps its own playback settings on import`() = runTest {
        val feedId = db.podcastDao().feedByUrl(FEED_URL)!!.feedId
        db.podcastDao().setEffects(feedId, speed = 1.2f, skipSilence = null, volumeBoostDb = null)

        importer.import(ImportData(listOf(ImportFeed(FEED_URL, "Test", true, speed = 2f, skipIntroS = 10)), emptyList()))

        with(db.podcastDao().feedByUrl(FEED_URL)!!) {
            assertEquals(1.2f, speed)
            assertEquals(0, skipIntroS)
        }
    }

    @Test
    fun `exporting twice replaces the file`() = runTest {
        val file = folder.newFile("export.db")

        exporter.export(file, rssBridge = null)
        val again = exporter.export(file, rssBridge = null)

        assertEquals(DatabaseExportResult(feeds = 1, episodes = 2), again)
        assertEquals(1, DatabaseReader.read(file).feeds.size)
    }

    private companion object {
        const val FEED_URL = "https://example.com/feed.xml"
        const val OLD_URL = "https://old.example.com/feed.xml"
        const val ARD_URL = "ardsounds:urn:ard:show:8e6d4d6fa453e7f7"
        const val ARD_ARCHIVED_URL = "ardsounds:urn:ard:show:b4ca0b835f537610"
        val FEED = """<rss version="2.0"><channel><title>Test-Podcast</title>
            <item><title>A</title><guid>guid-A</guid><enclosure url="https://example.com/A.mp3" type="audio/mpeg"/></item>
            <item><title>B</title><guid>guid-B</guid><enclosure url="https://example.com/B.mp3" type="audio/mpeg"/></item>
            </channel></rss>"""
    }
}
