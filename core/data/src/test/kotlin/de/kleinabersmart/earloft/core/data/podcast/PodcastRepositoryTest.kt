package de.kleinabersmart.earloft.core.data.podcast

import androidx.room.Room
import de.kleinabersmart.earloft.core.datasource.local.AudioFileChapters
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookmarkEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningStatEntity
import de.kleinabersmart.earloft.core.model.EpisodeFilter
import de.kleinabersmart.earloft.core.model.filterEpisodes
import de.kleinabersmart.earloft.core.database.model.BookPeopleRow
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.ChapterSource
import de.kleinabersmart.earloft.core.model.DownloadFailure
import de.kleinabersmart.earloft.core.model.FEED_PROBLEM_AFTER
import de.kleinabersmart.earloft.core.model.FeedError
import de.kleinabersmart.earloft.core.model.FeedQueueMode
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.network.podcast.Opml
import de.kleinabersmart.earloft.core.network.podcast.OpmlFeed
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
class PodcastRepositoryTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var repository: PodcastRepository
    private val settings = PodcastSettings(ApplicationProvider.getApplicationContext())

    /** What the "server" is currently serving; `null` = unreachable. */
    private var served: String? = feed("A" to "Thu, 30 Oct 2025 06:00:00 GMT", "B" to "Wed, 29 Oct 2025 06:00:00 GMT", "C" to "Tue, 28 Oct 2025 06:00:00 GMT")

    private val now = Instant.parse("2025-10-31T00:00:00Z").toEpochMilli()

    /** What the ARD Sounds GraphQL interface is currently serving; `null` = unreachable. */
    private var ardServed: String? = show("Fall 2", "Fall 1")

    /** HTTP status of the feed. */
    private var servedCode = 200

    /** `false`: the host doesn't speak HTTPS. */
    private var httpsAvailable = true

    /** All fetched addresses in order (feeds are fetched in parallel, so synchronized). */
    private val requested: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** Runs while the feed is being fetched (changes made during a sync). */
    private var duringFetch: () -> Unit = {}

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requested += chain.request().url.toString()
                duringFetch()
                if (chain.request().url.isHttps && !httpsAvailable) throw IOException("no TLS")
                val ard = chain.request().url.host == "api.ardaudiothek.de"
                val body = (if (ard) ardServed else served) ?: throw IOException("offline")
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (ard) 200 else servedCode).message("OK")
                    .body(body.toResponseBody("application/rss+xml".toMediaType()))
                    .build()
            }
            .build()
        repository = PodcastRepository(db, db.podcastDao(), db.bookDao(), PodcastFeedApi(client), ArdSoundsApi(client), settings, AudioFileChapters(ApplicationProvider.getApplicationContext()), { error("no source switch in tests") }, testFeedCredentials(db), EpisodeQueue(ApplicationProvider.getApplicationContext(), db.bookDao()))
    }

    @After
    fun tearDown() {
        // The DataStore outlives the test.
        kotlinx.coroutines.runBlocking { settings.setAutoDownloadNew(false) }
        db.close()
    }

    private suspend fun subscribe(): String = (repository.subscribe("https://example.com/feed.xml") as SubscribeResult.Added).feedId

    private suspend fun idOf(feedId: String, title: String) = db.bookDao().booksOf(feedId).single { it.title == title }.bookId

    private suspend fun listen(bookId: Long, positionMs: Long, updatedAt: Long, finished: Boolean = false) =
        db.bookDao().upsertProgress(BookProgressEntity(bookId, positionMs, 600_000, finished, 1f, updatedAt))

    @Test
    fun `subscribing stores the feed and its episodes with one track each`() = runTest {
        val feedId = subscribe()

        val feed = db.podcastDao().feed(feedId)!!
        assertEquals("Test-Podcast", feed.title)
        val episodes = repository.episodes(feedId).first().map { it.book }
        assertEquals(listOf("A", "B", "C"), episodes.map { it.title })
        assertTrue(episodes.all { it.kind == BookKind.EPISODE && it.author == "Test-Podcast" })
        val track = db.bookDao().tracks(episodes.first().id).single()
        assertEquals("https://example.com/A.mp3", track.remoteFileId)
        assertEquals(600_000, track.durationMs)
        assertTrue(db.bookDao().book(episodes.first().id)!!.detailsLoaded)
    }

    @Test
    fun `podcasting 2 0 details are stored, persons follow the feed and trailers hide behind real episodes`() = runTest {
        fun podcasting20(guest: String) = """
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:podcast="https://podcastindex.org/namespace/1.0">
              <channel>
                <title>P20</title>
                <podcast:guid>abc</podcast:guid>
                <podcast:funding url="https://example.com/support">Donate</podcast:funding>
                <item>
                  <title>One</title><guid>1</guid><pubDate>Thu, 30 Oct 2025 06:00:00 GMT</pubDate>
                  <enclosure url="https://example.com/1.mp3"/>
                  <itunes:season>2</itunes:season><itunes:episode>7</itunes:episode>
                  <podcast:person role="guest">$guest</podcast:person>
                  <podcast:transcript url="https://example.com/1.vtt" type="text/vtt"/>
                </item>
                <item>
                  <title>Trailer</title><guid>t</guid><pubDate>Wed, 29 Oct 2025 06:00:00 GMT</pubDate>
                  <enclosure url="https://example.com/t.mp3"/>
                  <itunes:episodeType>trailer</itunes:episodeType>
                </item>
              </channel>
            </rss>
        """.trimIndent()
        served = podcasting20("Bob")
        val feedId = subscribe()

        val feed = db.podcastDao().feed(feedId)!!
        assertEquals("abc", feed.podcastGuid)
        assertEquals("https://example.com/support", repository.feed(feedId).first()!!.fundingUrl)
        assertEquals("Donate", repository.feed(feedId).first()!!.fundingLabel)
        val episodes = repository.episodes(feedId).first().map { it.book }
        assertEquals(listOf("One"), episodes.map { it.title })
        val first = episodes.single()
        assertEquals(2, first.season)
        assertEquals(7, first.episodeNumber)
        assertEquals("https://example.com/1.vtt", first.transcriptUrl)
        assertEquals(listOf("Bob"), db.bookDao().personsOf(feedId).map { it.name })

        served = podcasting20("Carla")
        repository.refresh(feedId)
        assertEquals(listOf("Carla"), db.bookDao().personsOf(feedId).map { it.name })
    }

    @Test
    fun `settings changed during a sync are not overwritten by it`() = runTest {
        val feedId = subscribe()
        duringFetch = {
            kotlinx.coroutines.runBlocking {
                db.podcastDao().setAutoDownload(feedId, true)
                db.podcastDao().setArchived(feedId, true)
            }
        }

        repository.refresh(feedId)
        val changed = db.podcastDao().feed(feedId)!!
        assertTrue(changed.autoDownload && changed.archived)

        // A 304 doesn't write the old row back either.
        duringFetch = { kotlinx.coroutines.runBlocking { db.podcastDao().setAutoDownload(feedId, false) } }
        servedCode = 304
        repository.refresh(feedId)
        assertEquals(false, db.podcastDao().feed(feedId)!!.autoDownload)
    }

    @Test
    fun `a subscription ended during a sync does not come back`() = runTest {
        val feedId = subscribe()
        duringFetch = { kotlinx.coroutines.runBlocking { repository.unsubscribe(feedId) } }

        repository.refresh(feedId)

        assertEquals(null, db.podcastDao().feed(feedId))
        assertTrue(db.bookDao().booksOf(feedId).isEmpty())
    }

    @Test
    fun `failed refreshes are counted with their reason until one succeeds`() = runTest {
        val feedId = subscribe()
        servedCode = 404

        repeat(3) { assertEquals(null, repository.refresh(feedId)) }

        val failing = repository.feed(feedId).first()!!
        assertEquals(3 to FeedError.GONE, failing.failedRefreshes to failing.refreshError)
        assertTrue(failing.hasProblem && failing.failingSince > 0)
        servedCode = 200
        assertNotNull(repository.refresh(feedId))
        val fine = db.podcastDao().feed(feedId)!!
        assertEquals(Triple(0, null, 0L), Triple(fine.failedRefreshes, fine.lastError, fine.failingSince))
    }

    @Test
    fun `when no feed is reachable it is the network, a dead feed still counts`() = runTest {
        val feedId = subscribe()
        served = null
        repository.refreshAll()
        assertEquals(0, db.podcastDao().feed(feedId)!!.failedRefreshes)

        served = feed("A" to "Thu, 30 Oct 2025 06:00:00 GMT")
        servedCode = 410
        val summary = repository.refreshAll()
        assertTrue(summary.allFailed)
        assertEquals(1 to FeedError.GONE, db.podcastDao().feed(feedId)!!.let { it.failedRefreshes to it.lastError })
        servedCode = 200
        served = "<html><body>Parkplatz</body></html>"
        repository.refresh(feedId)
        assertEquals(2 to FeedError.NOT_A_FEED, db.podcastDao().feed(feedId)!!.let { it.failedRefreshes to it.lastError })
    }

    @Test
    fun `an http address is subscribed over https when the host offers it`() = runTest {
        val feedId = (repository.subscribe("http://example.com/feed.xml") as SubscribeResult.Added).feedId

        assertEquals("https://example.com/feed.xml", db.podcastDao().feed(feedId)!!.url)
        assertEquals(listOf("https://example.com/feed.xml"), requested)
    }

    @Test
    fun `without https the feed is subscribed unencrypted`() = runTest {
        httpsAvailable = false

        val feedId = (repository.subscribe("http://example.com/feed.xml") as SubscribeResult.Added).feedId

        assertEquals("http://example.com/feed.xml", db.podcastDao().feed(feedId)!!.url)
        assertEquals(listOf("https://example.com/feed.xml", "http://example.com/feed.xml"), requested)
    }

    @Test
    fun `an http address finds the same feed already subscribed over https`() = runTest {
        val feedId = subscribe()
        requested.clear()

        assertEquals(SubscribeResult.AlreadySubscribed(feedId), repository.subscribe("http://example.com/feed.xml"))
        assertEquals(emptyList<String>(), requested)
    }

    @Test
    fun `an unreachable host stays unreachable after both attempts`() = runTest {
        served = null

        assertEquals(SubscribeResult.Unreachable, repository.subscribe("http://example.com/feed.xml"))
        assertEquals(2, requested.size)
    }

    @Test
    fun `a subscription created elsewhere while the feed loads is kept instead of replaced`() = runTest {
        var parallel = true
        duringFetch = {
            if (parallel) {
                parallel = false
                kotlinx.coroutines.runBlocking { repository.importOpml(listOf(OpmlFeed("Imported", "https://example.com/feed.xml")), replace = false) }
            }
        }

        val result = repository.subscribe("https://example.com/feed.xml")

        val feed = db.podcastDao().feeds().single()
        assertEquals("Imported", feed.title)
        assertEquals(SubscribeResult.AlreadySubscribed(feed.feedId), result)
    }

    @Test
    fun `opml import skips known and repeated feeds and replacing keeps matches and device podcasts`() = runTest {
        val kept = subscribe()
        val dropped = (repository.subscribe("https://example.com/other.xml") as SubscribeResult.Added).feedId
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "local:x", url = "local:x", title = "Device"))
        val file = listOf(
            OpmlFeed("Old", "https://example.com/feed.xml"),
            OpmlFeed("New", "https://example.com/new.xml"),
            OpmlFeed("New again", "https://example.com/new.xml"),
        )

        val added = repository.importOpml(file, replace = false)
        assertEquals(OpmlImportResult(added = 1, skipped = 2, removed = 0), added)
        assertEquals(4, db.podcastDao().feeds().size)

        val replaced = repository.importOpml(file, replace = true)
        assertEquals(OpmlImportResult(added = 0, skipped = 3, removed = 1), replaced)
        val ids = db.podcastDao().feeds().map { it.feedId }
        assertTrue(kept in ids && "local:x" in ids && dropped !in ids)
        assertEquals(3, ids.size)
        // The existing subscription kept its episodes.
        assertEquals(3, db.bookDao().booksOf(kept).size)
    }

    @Test
    fun `opml import finds a subscription stored under https by its http address`() = runTest {
        val stored = subscribe()
        val other = (repository.subscribe("https://example.com/other.xml") as SubscribeResult.Added).feedId
        val file = listOf(OpmlFeed("Old", "http://example.com/feed.xml"), OpmlFeed("Same", "https://example.com/feed.xml"))

        assertEquals(1, repository.opmlReplaceRemovals(file))
        assertEquals(OpmlImportResult(added = 0, skipped = 2, removed = 0), repository.importOpml(file, replace = false))
        assertEquals(2, db.podcastDao().feeds().size)

        val replaced = repository.importOpml(file, replace = true)
        assertEquals(OpmlImportResult(added = 0, skipped = 2, removed = 1), replaced)
        val ids = db.podcastDao().feeds().map { it.feedId }
        assertEquals(listOf(stored), ids)
        assertTrue(other !in ids)
        // Kept with its episodes rather than deleted and created again under the old address.
        assertEquals(3, db.bookDao().booksOf(stored).size)
        assertEquals(0, repository.opmlReplaceRemovals(file))
        assertEquals(0, repository.opmlReplaceRemovals(emptyList()))
    }

    @Test
    fun `with both the http and the https subscription stored, the file's exact address is the one kept`() = runTest {
        // Left over from an import before review2 J2: the same feed twice, the http one holding the imported progress.
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "plain", url = "http://example.com/feed.xml", title = "Plain"))
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "secure", url = "https://example.com/feed.xml", title = "Secure"))
        val file = listOf(OpmlFeed("Plain", "http://example.com/feed.xml"))

        // review3 Q8: the https one was matched first, and "Replace" deleted the one the file names.
        assertEquals(1, repository.opmlReplaceRemovals(file))
        assertEquals(OpmlImportResult(added = 0, skipped = 1, removed = 1), repository.importOpml(file, replace = true))
        assertEquals(listOf("plain"), db.podcastDao().feeds().map { it.feedId })
        assertEquals("plain", repository.storedFeed("http://example.com/feed.xml")!!.feedId)
    }

    @Test
    fun `a new address in the file under http and https becomes one subscription under https`() = runTest {
        val file = listOf(OpmlFeed("Plain", "http://example.com/new.xml"), OpmlFeed("Secure", "https://example.com/new.xml"))

        assertEquals(OpmlImportResult(added = 1, skipped = 1, removed = 0), repository.importOpml(file, replace = false))
        assertEquals(listOf("https://example.com/new.xml"), db.podcastDao().feeds().map { it.url })
    }

    @Test
    fun `replacing with a file without feeds or without ard sounds keeps those subscriptions`() = runTest {
        val feed = subscribe()
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "ard", url = "ardsounds:urn:ard:show:5fa94a3212aa9829", title = "ARD"))

        // Empty or differently structured file (e.g. `url` instead of `xmlUrl`): delete nothing.
        assertEquals(OpmlImportResult(added = 0, skipped = 0, removed = 0), repository.importOpml(emptyList(), replace = true))
        assertEquals(2, db.podcastDao().feeds().size)

        // Without RSS-Bridge EarLoft exports no ARD subscriptions; the file therefore doesn't replace them.
        val result = repository.importOpml(listOf(OpmlFeed("New", "https://example.com/new.xml")), replace = true)
        assertEquals(1, result.removed)
        val ids = db.podcastDao().feeds().map { it.feedId }
        assertTrue("ard" in ids && feed !in ids)
    }

    @Test
    fun `opml export lists subscriptions but not device podcasts`() = runTest {
        subscribe()
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "local:x", url = "local:x", title = "Device"))

        val feeds = Opml.parse(repository.exportOpml("Subscriptions", "Mon, 2 Nov 2026 14:10:40 +0100", rssBridge = null).byteInputStream())

        assertEquals(listOf(OpmlFeed("Test-Podcast", "https://example.com/feed.xml")), feeds)
    }

    @Test
    fun `feed search matches the title regardless of case and finds nothing for blank or foreign terms`() = runTest {
        val feedId = subscribe()

        assertEquals(listOf(feedId), repository.searchFeeds("test-POD").map { it.id })
        assertEquals(emptyList<String>(), repository.searchFeeds("   ").map { it.id })
        assertEquals(emptyList<String>(), repository.searchFeeds("Audiobook").map { it.id })
    }

    @Test
    fun `book search keeps episodes and books apart and lists book authors once`() = runTest {
        subscribe()
        val dao = db.bookDao()
        fun book(title: String, author: String, narrator: String = "") = BookEntity(
            serverId = "abs", remoteId = title, title = title, normTitle = title.lowercase(), author = author, narrator = narrator, durationMs = 1,
        )
        val first = dao.insertBook(book("Dune", "Frank Herbert"))
        dao.insertBook(book("Dune Messiah", "Frank Herbert"))
        val potter = dao.insertBook(book("Harry Potter", "J. K. Rowling", narrator = "Rufus Beck"))
        dao.insertBook(book("Sophies Welt", "Jostein Gaarder", narrator = "Rufus Beck"))

        assertEquals(listOf("A", "B", "C"), dao.searchRows(BookKind.EPISODE).map { it.normTitle.uppercase() }.sorted())
        assertEquals(listOf("dune", "dune messiah", "harry potter", "sophies welt"), dao.searchRows(BookKind.BOOK).map { it.normTitle }.sorted())
        assertEquals(listOf(first, first + 1, potter, potter + 1), dao.authorRows().map { it.bookId }.sorted())
        // Narrators as a separate hit group, without authors and without episodes.
        assertEquals(listOf(BookPeopleRow(potter, "Rufus Beck"), BookPeopleRow(potter + 1, "Rufus Beck")), dao.narratorRows().sortedBy { it.bookId })
    }

    @Test
    fun `the same feed cannot be subscribed twice and bad input is told apart`() = runTest {
        val feedId = subscribe()

        assertEquals(SubscribeResult.AlreadySubscribed(feedId), repository.subscribe("https://example.com/feed.xml"))
        assertEquals(SubscribeResult.InvalidUrl, repository.subscribe("not a feed"))
        served = "<html></html>"
        assertEquals(SubscribeResult.NotAFeed, repository.subscribe("https://example.com/other"))
        served = null
        assertEquals(SubscribeResult.Unreachable, repository.subscribe("https://example.com/third"))
    }

    @Test
    fun `continue listening puts the most recently heard first and new episodes last`() = runTest {
        val feedId = subscribe()
        listen(idOf(feedId, "B"), positionMs = 60_000, updatedAt = 100)
        listen(idOf(feedId, "C"), positionMs = 30_000, updatedAt = 200)

        val titles = repository.continueListening { now }.first().map { it.book.title }

        assertEquals(listOf("C", "B", "A"), titles)
    }

    @Test
    fun `finished and old episodes are not offered`() = runTest {
        val feedId = subscribe()
        listen(idOf(feedId, "A"), positionMs = 590_000, updatedAt = 100, finished = true)

        val titles = repository.continueListening { now + 30L * 24 * 60 * 60 * 1000 }.first().map { it.book.title }

        assertEquals(emptyList<String>(), titles)
    }

    @Test
    fun `only the newest new episode or none comes by itself depending on the subscription`() = runTest {
        val feedId = subscribe()
        listen(idOf(feedId, "B"), positionMs = 60_000, updatedAt = 100)

        repository.setQueueMode(feedId, FeedQueueMode.NEWEST_ONLY)
        assertEquals(listOf("B", "A"), repository.continueListening { now }.first().map { it.book.title })

        repository.setQueueMode(feedId, FeedQueueMode.MANUAL)
        assertEquals(listOf("B"), repository.continueListening { now }.first().map { it.book.title })

        // If the newest one is played, no older one moves up.
        repository.setQueueMode(feedId, FeedQueueMode.NEWEST_ONLY)
        listen(idOf(feedId, "A"), positionMs = 600_000, updatedAt = 150, finished = true)
        assertEquals(listOf("B"), repository.continueListening { now }.first().map { it.book.title })
    }

    @Test
    fun `keeping the newest episodes hides older untouched ones everywhere but keeps started ones`() = runTest {
        val feedId = subscribe()
        listen(idOf(feedId, "C"), positionMs = 30_000, updatedAt = 300)

        repository.setKeepEpisodes(feedId, 1)

        assertEquals(listOf("A", "C"), repository.episodes(feedId).first().map { it.book.title })
        assertEquals(listOf("A", "C"), repository.latestEpisodes().first().map { it.book.title })
        assertEquals(listOf("C", "A"), repository.continueListening { now }.first().map { it.book.title })
        val stats = repository.feedStats().first().getValue(feedId)
        assertEquals(1, stats.newEpisodes)
        assertEquals(300L, stats.lastPlayedAt)
        assertEquals(Instant.parse("2025-10-30T06:00:00Z").toEpochMilli(), stats.newestEpisodeAt)
    }

    @Test
    fun `starred episodes keep their star across refreshes and stay when the feed drops them`() = runTest {
        val feedId = subscribe()
        val (b, c) = listOf("B", "C").map { idOf(feedId, it) }
        db.podcastDao().setStarred(listOf(b), 100)
        db.podcastDao().setStarred(listOf(c), 200)
        assertEquals(listOf("C", "B"), repository.starredEpisodes().first().map { it.book.title })
        // Already remembered ones keep their timestamp.
        assertEquals(0, db.podcastDao().setStarred(listOf(b), 300))

        repository.setStarred(listOf(c), starred = false)
        served = feed("A" to "Thu, 30 Oct 2025 06:00:00 GMT")
        // Two of three gone: the second sync confirms (review G3).
        repository.refresh(feedId)
        repository.refresh(feedId)

        // B is no longer in the feed but stays along with its star; C went with the feed.
        assertEquals(listOf("A", "B"), repository.episodes(feedId).first().map { it.book.title })
        assertEquals(100L, db.bookDao().book(b)!!.starredAt)
        assertEquals(null, db.bookDao().book(c))
        assertEquals(listOf("B"), repository.starredEpisodes().first().map { it.book.title })
        assertEquals(1, repository.setStarred(listOf(b, b), starred = false))
        assertEquals(emptyList<String>(), repository.starredEpisodes().first().map { it.book.title })
    }

    @Test
    fun `episodes with a bookmark stay when the feed drops them, deleted bookmarks do not count`() = runTest {
        val feedId = subscribe()
        val (b, c) = listOf("B", "C").map { idOf(feedId, it) }
        val bookmark = BookmarkEntity(bookId = b, positionMs = 5_000, title = "Stelle", createdAt = 1, updatedAt = 1)
        db.bookDao().insertBookmark(bookmark)
        db.bookDao().insertBookmark(bookmark.copy(bookId = c, deleted = true))

        served = feed("A" to "Thu, 30 Oct 2025 06:00:00 GMT")
        repository.refresh(feedId)
        repository.refresh(feedId)

        assertEquals(listOf("A", "B"), repository.episodes(feedId).first().map { it.book.title })
        assertEquals(null, db.bookDao().book(c))
    }

    @Test
    fun `heard downloads of starred episodes are not cleaned up`() = runTest {
        val feedId = subscribe()
        val dao = db.podcastDao()
        val (a, b) = listOf("A", "B").map { idOf(feedId, it) }
        listOf(a, b).forEach { id ->
            dao.queueDownload(id)
            dao.markDownloadRunning(id)
            dao.markDownloadDone(id, "/tmp/$id.mp3", 1)
            listen(id, positionMs = 600_000, updatedAt = 100, finished = true)
        }
        repository.setStarred(listOf(b), starred = true)

        assertEquals(listOf(a), dao.observeFinishedDownloads().first().map { it.bookId })
        assertEquals(listOf(a), dao.finishedDownloads(feedId).map { it.bookId })
    }

    @Test
    fun `keeping the newest episodes still shows starred ones`() = runTest {
        val feedId = subscribe()
        repository.setStarred(listOf(idOf(feedId, "C")), starred = true)

        repository.setKeepEpisodes(feedId, 1)

        assertEquals(listOf("A", "C"), repository.episodes(feedId).first().map { it.book.title })
        assertEquals(listOf("A", "C"), repository.latestEpisodes().first().map { it.book.title })
        val episodes = repository.episodes(feedId).first()
        assertEquals(listOf("C"), episodes.filterEpisodes(EpisodeFilter.STARRED, oldestFirst = false, downloaded = emptySet()).map { it.book.title })
    }

    @Test
    fun `history lists heard episodes newest first, also ones that are gone`() = runTest {
        val feedId = subscribe()
        val a = idOf(feedId, "A")
        listen(a, positionMs = 600_000, updatedAt = 300, finished = true)
        fun stat(source: String, item: String, title: String, ms: Long, at: Long, group: String = "https://example.com/feed.xml") =
            ListeningStatEntity(source = source, kind = BookKind.EPISODE, groupKey = group, groupTitle = "Test-Podcast", itemKey = item, itemTitle = title, listenedMs = ms, lastListenedAt = at)
        db.statsDao().upsertAll(
            listOf(
                stat(ListeningStatEntity.SOURCE_APP, "guid-A", "A", 60_000, 300),
                stat(ListeningStatEntity.SOURCE_IMPORTED, "guid-A", "A", 30_000, 100),
                stat(ListeningStatEntity.SOURCE_APP, "guid-old", "Old", 10_000, 200),
                stat(ListeningStatEntity.SOURCE_IMPORTED, "x", "Foreign", 5_000, 400, group = "https://example.com/gone.xml"),
                // Without a timestamp it isn't in the history.
                stat(ListeningStatEntity.SOURCE_IMPORTED, "guid-B", "B", 5_000, 0),
            ),
        )

        val history = repository.history().first()

        assertEquals(listOf("Foreign", "A", "Old"), history.map { it.title })
        val (gone, heard, old) = history
        assertEquals(Triple(null, null, false), Triple(gone.feedId, gone.bookId, gone.finished))
        assertEquals(Triple(feedId, a, true), Triple(heard.feedId, heard.bookId, heard.finished))
        assertEquals(90_000L, heard.listenedMs)
        assertEquals(feedId to null, old.feedId to old.bookId)
    }

    @Test
    fun `play next goes right behind the first episode and clearing keeps only the loaded one`() = runTest {
        val feedId = subscribe()
        val (a, b, c) = listOf("A", "B", "C").map { idOf(feedId, it) }
        listen(b, positionMs = 60_000, updatedAt = 200)
        listen(c, positionMs = 30_000, updatedAt = 100)
        val continueListening = ContinueListening(
            EpisodeQueue(ApplicationProvider.getApplicationContext(), db.bookDao()),
            repository,
            PodcastSettings(ApplicationProvider.getApplicationContext()),
        )
        // The episodes are older than 14 days: only the started ones appear on their own.
        assertEquals(listOf(b, c), continueListening.episodes.first().map { it.book.id })

        continueListening.playNext(a)
        assertEquals(listOf(b, a, c), continueListening.episodes.first().map { it.book.id })

        continueListening.playNext(c)
        assertEquals(listOf(b, c, a), continueListening.episodes.first().map { it.book.id })

        continueListening.clear(keepId = b)
        assertEquals(listOf(b), continueListening.episodes.first().map { it.book.id })
    }

    @Test
    fun `play next for several episodes keeps their order, with an empty queue and behind the first one`() = runTest {
        val feedId = subscribe()
        val (a, b, c) = listOf("A", "B", "C").map { idOf(feedId, it) }
        val continueListening = ContinueListening(
            EpisodeQueue(ApplicationProvider.getApplicationContext(), db.bookDao()),
            repository,
            PodcastSettings(ApplicationProvider.getApplicationContext()),
        )
        assertEquals(emptyList<Long>(), continueListening.episodes.first().map { it.book.id })

        continueListening.playNext(listOf(a, b, c))
        assertEquals(listOf(a, b, c), continueListening.episodes.first().map { it.book.id })

        // With the frontmost (a) in the selection it stays in front, the others follow in the order of the selection.
        continueListening.playNext(listOf(c, a, b))
        assertEquals(listOf(a, c, b), continueListening.episodes.first().map { it.book.id })
    }

    @Test
    fun `moving an episode fixes only it and what stands before it`() = runTest {
        val feedId = subscribe()
        val (a, b, c) = listOf("A", "B", "C").map { idOf(feedId, it) }
        listen(b, positionMs = 60_000, updatedAt = 200)
        listen(c, positionMs = 30_000, updatedAt = 100)
        val queue = EpisodeQueue(ApplicationProvider.getApplicationContext(), db.bookDao())
        val continueListening = ContinueListening(queue, repository, PodcastSettings(ApplicationProvider.getApplicationContext()))
        continueListening.playNext(a)
        assertEquals(listOf(b, a, c), continueListening.episodes.first().map { it.book.id })
        assertEquals(listOf(b, a), queue.order.first())

        // A to the front: B was already pinned and stays so, C still appears on its own.
        continueListening.reorder(listOf(a, b, c), movedId = a)
        assertEquals(listOf(a, b, c), continueListening.episodes.first().map { it.book.id })
        assertEquals(listOf(a, b), queue.order.first())

        // C between A and B: only A is before it, B stays pinned.
        continueListening.reorder(listOf(a, c, b), movedId = c)
        assertEquals(listOf(a, c, b), continueListening.episodes.first().map { it.book.id })
        assertEquals(listOf(a, c, b), queue.order.first())
    }

    @Test
    fun `moving an episode down leaves the automatic ones behind it alone`() = runTest {
        val feedId = subscribe()
        val (a, b, c) = listOf("A", "B", "C").map { idOf(feedId, it) }
        listen(a, positionMs = 10_000, updatedAt = 300)
        listen(b, positionMs = 60_000, updatedAt = 200)
        listen(c, positionMs = 30_000, updatedAt = 100)
        val queue = EpisodeQueue(ApplicationProvider.getApplicationContext(), db.bookDao())
        val continueListening = ContinueListening(queue, repository, PodcastSettings(ApplicationProvider.getApplicationContext()))
        assertEquals(listOf(a, b, c), continueListening.episodes.first().map { it.book.id })

        continueListening.reorder(listOf(b, a, c), movedId = a)

        assertEquals(listOf(b, a, c), continueListening.episodes.first().map { it.book.id })
        assertEquals(listOf(b, a), queue.order.first())
    }

    @Test
    fun `chapters from the feed are stored and give way to the chapter list but not the other way round`() = runTest {
        val rss = """<rss version="2.0" xmlns:psc="http://podlove.org/simple-chapters" xmlns:podcast="https://podcastindex.org/namespace/1.0">
            <channel><title>Lage</title><item><title>LdN</title><guid>ldn</guid>
            <enclosure url="https://example.com/ldn.m4a" type="audio/mp4"/><itunes:duration xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">10:00</itunes:duration>
            <podcast:chapters url="https://example.com/ldn.json" type="application/json+chapters"/>
            <psc:chapters><psc:chapter start="0" title="Intro"/><psc:chapter start="00:01:00" title="Thema"/></psc:chapters>
            </item></channel></rss>"""
        served = rss
        val feedId = subscribe()
        val id = idOf(feedId, "LdN")
        suspend fun chapters() = db.bookDao().chapters(id).map { Triple(it.title, it.startMs, it.endMs) }

        assertEquals(listOf(Triple("Intro", 0L, 60_000L), Triple("Thema", 60_000L, 600_000L)), chapters())
        assertEquals(ChapterSource.FEED, db.bookDao().chapterSource(id))

        // The chapter list (JSON) takes precedence.
        served = """{"version":"1.2.0","chapters":[{"startTime":0,"title":"Welcome"},
            {"startTime":30.5,"title":"Ukraine","img":"https://example.com/karte.jpg","url":"https://example.com/quelle"}]}"""
        repository.ensureChapters(id)
        assertEquals(listOf(Triple("Welcome", 0L, 30_500L), Triple("Ukraine", 30_500L, 600_000L)), chapters())
        assertEquals(ChapterSource.JSON, db.bookDao().chapterSource(id))
        // Image and website come along.
        val ukraine = db.bookDao().chapters(id).last()
        assertEquals("https://example.com/karte.jpg" to "https://example.com/quelle", ukraine.imageUrl to ukraine.url)

        // The next sync brings the feed chapters again; they don't replace the list.
        served = rss
        repository.refresh(feedId)
        assertEquals("Ukraine", chapters().last().first)
        assertEquals(ChapterSource.JSON, db.bookDao().chapterSource(id))

        // Without a connection everything stays, and without a downloaded file there's nothing from the file.
        served = null
        repository.ensureChapters(id)
        repository.chaptersFromFile(id)
        assertEquals(2, chapters().size)
    }

    @Test
    fun `an episode without any chapter source is left alone when it is not downloaded`() = runTest {
        val feedId = subscribe()
        val id = idOf(feedId, "A")

        repository.ensureChapters(id)

        assertEquals(emptyList<Any>(), db.bookDao().chapters(id))
        assertEquals(null, db.bookDao().chapterSource(id))
    }

    @Test
    fun `a missing or empty chapter list is not fetched again before every start`() = runTest {
        fun rss(chaptersUrl: String) = """<rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0">
            <channel><title>Lage</title><item><title>LdN</title><guid>ldn</guid>
            <enclosure url="https://example.com/ldn.m4a" type="audio/mp4"/>
            <podcast:chapters url="$chaptersUrl" type="application/json+chapters"/>
            </item></channel></rss>"""
        served = rss("https://example.com/ldn.json")
        val feedId = subscribe()
        val id = idOf(feedId, "LdN")
        fun fetches() = requested.count { it.endsWith(".json") }
        var clock = 1_000_000L

        // 404: not asked again until the next attempt hours later.
        servedCode = 404
        repository.ensureChapters(id) { clock }
        repository.ensureChapters(id) { clock }
        assertEquals(1, fetches())
        clock += 60 * 60 * 1000L
        repository.ensureChapters(id) { clock }
        assertEquals(1, fetches())

        // Empty list: likewise; again after six hours, then with chapters.
        servedCode = 200
        clock += 6 * 60 * 60 * 1000L
        served = """{"version":"1.2.0","chapters":[]}"""
        repository.ensureChapters(id) { clock }
        repository.ensureChapters(id) { clock }
        assertEquals(2, fetches())
        clock += 6 * 60 * 60 * 1000L
        served = """{"version":"1.2.0","chapters":[{"startTime":0,"title":"Welcome"}]}"""
        repository.ensureChapters(id) { clock }
        assertEquals(3, fetches())
        assertEquals(ChapterSource.JSON, db.bookDao().chapterSource(id))
    }

    @Test
    fun `an unreachable chapter list is tried again after minutes and a new address at once`() = runTest {
        fun rss(chaptersUrl: String) = """<rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0">
            <channel><title>Lage</title><item><title>LdN</title><guid>ldn</guid>
            <enclosure url="https://example.com/ldn.m4a" type="audio/mp4"/>
            <podcast:chapters url="$chaptersUrl" type="application/json+chapters"/>
            </item></channel></rss>"""
        served = rss("https://example.com/ldn.json")
        val feedId = subscribe()
        val id = idOf(feedId, "LdN")
        fun fetches() = requested.count { it.endsWith(".json") }
        var clock = 1_000_000L

        served = null
        repository.ensureChapters(id) { clock }
        repository.ensureChapters(id) { clock }
        assertEquals(1, fetches())
        clock += 10 * 60 * 1000L
        repository.ensureChapters(id) { clock }
        assertEquals(2, fetches())

        // The feed names a new address: immediately.
        served = rss("https://example.com/ldn-v2.json")
        repository.refresh(feedId)
        served = null
        repository.ensureChapters(id) { clock }
        assertEquals(3, fetches())
    }

    @Test
    fun `queueing a finished episode marks it unheard and it shows in the queue`() = runTest {
        val feedId = subscribe()
        val c = idOf(feedId, "C")
        listen(c, positionMs = 600_000, updatedAt = 100, finished = true)
        val continueListening = ContinueListening(
            EpisodeQueue(ApplicationProvider.getApplicationContext(), db.bookDao()),
            repository,
            PodcastSettings(ApplicationProvider.getApplicationContext()),
        )

        continueListening.append(c)

        val progress = db.bookDao().progress(c)!!
        assertEquals(false, progress.finished)
        assertEquals(0L, progress.positionMs)
        assertTrue(continueListening.episodes.first().any { it.book.id == c })
    }

    @Test
    fun `a removed episode is not queued again by itself when the one before it starts`() = runTest {
        val feedId = subscribe()
        val (b, c) = listOf("B", "C").map { idOf(feedId, it) }
        val queue = EpisodeQueue(ApplicationProvider.getApplicationContext(), db.bookDao())
        val continueListening = ContinueListening(queue, repository, PodcastSettings(ApplicationProvider.getApplicationContext()))
        continueListening.started(c)
        assertEquals(listOf(c, b), continueListening.episodes.first().map { it.book.id })

        continueListening.remove(b)
        continueListening.started(c)

        assertEquals(listOf(c), continueListening.episodes.first().map { it.book.id })
        assertEquals(setOf(b), queue.hidden.first())

        // Played manually, it's back in the row.
        continueListening.started(b)
        assertTrue(continueListening.episodes.first().any { it.book.id == b })
        assertEquals(emptySet<Long>(), queue.hidden.first())
    }

    @Test
    fun `a preview stores a trial feed that is missing from all subscription lists`() = runTest {
        val feedId = (repository.openPreview("https://example.com/feed.xml") as SubscribeResult.Added).feedId
        listen(idOf(feedId, "B"), positionMs = 60_000, updatedAt = 100)

        assertEquals(false, repository.feed(feedId).first()!!.subscribed)
        assertEquals(listOf("A", "B", "C"), repository.episodes(feedId).first().map { it.book.title })
        assertTrue(repository.feeds.first().isEmpty())
        assertTrue(repository.latestEpisodes().first().isEmpty())
        assertTrue(repository.recentEpisodes().first().isEmpty())
        // Neither as new nor as started does it appear in "Continue listening" on its own.
        assertTrue(repository.continueListening { now }.first().isEmpty())
        assertTrue(repository.subscribedByKey.first().isEmpty())
        assertTrue(repository.searchFeeds("Test").isEmpty())
    }

    @Test
    fun `trial episodes appear in the queue only where they were put`() = runTest {
        val feedId = (repository.openPreview("https://example.com/feed.xml") as SubscribeResult.Added).feedId
        val b = idOf(feedId, "B")

        val titles = repository.continueListening(flowOf(listOf(b))) { now }.first().map { it.book.title }

        assertEquals(listOf("B"), titles)
        assertEquals(null, repository.autoNextAfter(b))
    }

    @Test
    fun `trial episode ids name only the episodes of trial feeds`() = runTest {
        val trialId = (repository.openPreview("https://example.com/feed.xml") as SubscribeResult.Added).feedId
        val trialB = idOf(trialId, "B")
        val trialC = idOf(trialId, "C")
        served = feed("X" to "Thu, 30 Oct 2025 06:00:00 GMT")
        val subscribedX = idOf((repository.subscribe("https://example.org/other.xml") as SubscribeResult.Added).feedId, "X")

        assertEquals(setOf(trialB, trialC), repository.trialEpisodeIds(listOf(trialB, subscribedX, trialC, trialB)))

        repository.subscribeTrial(trialId)
        assertEquals(emptySet<Long>(), repository.trialEpisodeIds(listOf(trialB, subscribedX)))
    }

    @Test
    fun `subscribing a trial feed keeps its episodes and progress`() = runTest {
        val trialId = (repository.openPreview("https://example.com/feed.xml") as SubscribeResult.Added).feedId
        val b = idOf(trialId, "B")
        listen(b, positionMs = 60_000, updatedAt = 100)
        requested.clear()

        assertEquals(SubscribeResult.Added(trialId), repository.subscribe("http://example.com/feed.xml"))

        assertEquals(emptyList<String>(), requested)
        assertEquals(listOf(trialId), repository.feeds.first().map { it.id })
        assertEquals(60_000L, db.bookDao().progress(b)!!.positionMs)
        assertEquals(SubscribeResult.AlreadySubscribed(trialId), repository.openPreview("https://example.com/feed.xml"))
    }

    @Test
    fun `new subscriptions take the default for automatic downloads, existing ones keep theirs`() = runTest {
        val before = subscribe()
        settings.setAutoDownloadNew(true)
        val after = (repository.subscribe("https://example.com/other.xml") as SubscribeResult.Added).feedId
        repository.setAutoDownload(after, false)
        val trial = (repository.openPreview("https://example.com/trial.xml") as SubscribeResult.Added).feedId

        assertEquals(false, db.podcastDao().feed(trial)!!.autoDownload)
        repository.subscribeTrial(trial)
        settings.setAutoDownloadNew(false)

        assertEquals(false, db.podcastDao().feed(before)!!.autoDownload)
        assertEquals(false, db.podcastDao().feed(after)!!.autoDownload)
        assertEquals(true, db.podcastDao().feed(trial)!!.autoDownload)
    }

    @Test
    fun `opml import gives new and trial feeds the default, an archived one keeps its setting`() = runTest {
        val archived = subscribe()
        repository.archive(archived)
        val trial = (repository.openPreview("https://example.com/trial.xml") as SubscribeResult.Added).feedId
        settings.setAutoDownloadNew(true)

        repository.importOpml(
            listOf(
                OpmlFeed("Archiviert", "https://example.com/feed.xml"),
                OpmlFeed("Probe", "https://example.com/trial.xml"),
                OpmlFeed("New", "https://example.com/new.xml"),
            ),
            replace = false,
        )

        assertEquals(false, db.podcastDao().feed(archived)!!.autoDownload)
        assertEquals(true, db.podcastDao().feed(trial)!!.autoDownload)
        assertEquals(true, db.podcastDao().feedByUrl("https://example.com/new.xml")!!.autoDownload)
    }

    @Test
    fun `opening the preview of a known trial feed refreshes it instead of adding another`() = runTest {
        val trialId = (repository.openPreview("https://example.com/feed.xml") as SubscribeResult.Added).feedId

        assertEquals(SubscribeResult.Added(trialId), repository.openPreview("https://example.com/feed.xml"))
        assertEquals(1, db.podcastDao().observeAllFeeds().first().size)
    }

    @Test
    fun `a feed with an empty image keeps the directory image across refreshes`() = runTest {
        served = feedWithoutImage(episodeImage = null)
        val feedId = (repository.openPreview("https://example.com/feed.xml", "https://dir.example/cover.jpg") as SubscribeResult.Added).feedId
        repository.refresh(feedId)
        repository.subscribeTrial(feedId)

        assertEquals("https://dir.example/cover.jpg", db.podcastDao().feed(feedId)!!.imageUrl)
        assertEquals("https://dir.example/cover.jpg", db.bookDao().booksOf(feedId).single().coverUrl)
    }

    @Test
    fun `a feed without an image of its own shows the image of the newest episode`() = runTest {
        served = feedWithoutImage(episodeImage = "https://example.com/episode.jpg")

        val feedId = subscribe()

        assertEquals("https://example.com/episode.jpg", db.podcastDao().feed(feedId)!!.imageUrl)
    }

    /** Like the feeds of the rbb stations: channel image elements present, but empty. */
    private fun feedWithoutImage(episodeImage: String?) = """
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>No image</title>
        <itunes:image href=""/><image><url></url><title>No image</title></image>
        <item><title>A</title><guid>guid-A</guid><enclosure url="https://example.com/A.mp3" type="audio/mpeg"/>
        ${episodeImage?.let { "<itunes:image href=\"$it\"/>" }.orEmpty()}</item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun `an opml entry turns a trial feed into a subscription`() = runTest {
        val trialId = (repository.openPreview("https://example.com/feed.xml") as SubscribeResult.Added).feedId

        repository.importOpml(listOf(OpmlFeed("Test", "https://example.com/feed.xml")), replace = false)

        assertEquals(listOf(trialId), repository.feeds.first().map { it.id })
        assertEquals(3, db.bookDao().booksOf(trialId).size)
    }

    @Test
    fun `an archived feed is missing from all lists but keeps its episodes and progress`() = runTest {
        val feedId = subscribe()
        val b = idOf(feedId, "B")
        listen(b, positionMs = 60_000, updatedAt = 100)

        repository.archive(feedId)

        assertTrue(repository.feeds.first().isEmpty())
        assertTrue(repository.latestEpisodes().first().isEmpty())
        assertTrue(repository.recentEpisodes().first().isEmpty())
        assertTrue(repository.continueListening(flowOf(listOf(b))) { now }.first().isEmpty())
        assertTrue(repository.subscribedByKey.first().isEmpty())
        assertTrue(repository.searchFeeds("Test").isEmpty())
        assertTrue(db.bookDao().searchRows(BookKind.EPISODE).isEmpty())
        assertEquals(listOf(feedId), repository.archivedFeeds.first().map { it.id })
        assertEquals(3, db.bookDao().booksOf(feedId).size)
        assertEquals(60_000L, db.bookDao().progress(b)!!.positionMs)
        // No more syncing.
        requested.clear()
        repository.refreshAll()
        assertEquals(emptyList<String>(), requested)
    }

    @Test
    fun `subscribing or previewing an archived feed asks instead of adding and reactivating brings it back`() = runTest {
        val feedId = subscribe()
        val b = idOf(feedId, "B")
        listen(b, positionMs = 60_000, updatedAt = 100)
        repository.archive(feedId)
        requested.clear()

        assertEquals(SubscribeResult.Archived(feedId, "Test-Podcast"), repository.subscribe("http://example.com/feed.xml"))
        assertEquals(SubscribeResult.Archived(feedId, "Test-Podcast"), repository.openPreview("https://example.com/feed.xml"))
        assertEquals(emptyList<String>(), requested)
        assertEquals(1, db.podcastDao().observeAllFeeds().first().size)

        served = feed("D" to "Fri, 31 Oct 2025 06:00:00 GMT", "A" to "Thu, 30 Oct 2025 06:00:00 GMT", "B" to "Wed, 29 Oct 2025 06:00:00 GMT")
        assertTrue(repository.reactivate(feedId))

        assertEquals(listOf(feedId), repository.feeds.first().map { it.id })
        assertTrue(repository.archivedFeeds.first().isEmpty())
        assertEquals(listOf("D", "A", "B"), repository.episodes(feedId).first().map { it.book.title })
        assertEquals(60_000L, db.bookDao().progress(b)!!.positionMs)
    }

    @Test
    fun `unsubscribing an archived feed removes it with its episodes`() = runTest {
        val feedId = subscribe()
        repository.archive(feedId)

        repository.unsubscribe(feedId)

        assertTrue(repository.archivedFeeds.first().isEmpty())
        assertTrue(db.bookDao().booksOf(feedId).isEmpty())
        assertTrue(repository.subscribe("https://example.com/feed.xml") is SubscribeResult.Added)
    }

    @Test
    fun `an opml entry reactivates an archived feed`() = runTest {
        val feedId = subscribe()
        repository.archive(feedId)

        val result = repository.importOpml(listOf(OpmlFeed("Test", "https://example.com/feed.xml")), replace = false)

        assertEquals(1, result.added)
        assertEquals(listOf(feedId), repository.feeds.first().map { it.id })
    }

    @Test
    fun `trial episodes report progress and downloads for the cleanup`() = runTest {
        val trialId = (repository.openPreview("https://example.com/feed.xml") as SubscribeResult.Added).feedId
        val a = idOf(trialId, "A")
        listen(a, positionMs = 600_000, updatedAt = 100, finished = true)
        db.podcastDao().queueDownload(idOf(trialId, "B"))

        val byTitle = repository.trialEpisodes.first().associateBy { it.bookId }

        assertEquals(listOf(trialId), repository.trialFeedIds.first())
        assertEquals(TrialEpisode(a, trialId, finished = true, hasProgress = true, downloaded = false), byTitle[a])
        assertEquals(true, byTitle[idOf(trialId, "B")]!!.downloaded)
        repository.forgetProgress(a)
        assertEquals(null, db.bookDao().progress(a))
    }

    @Test
    fun `recently added lists the newest episodes first without finished ones`() = runTest {
        val feedId = subscribe()
        listen(idOf(feedId, "B"), positionMs = 600_000, updatedAt = 100, finished = true)
        listen(idOf(feedId, "C"), positionMs = 30_000, updatedAt = 200)

        assertEquals(listOf("A", "C"), repository.recentEpisodes { now }.first().map { it.book.title })
        assertEquals(listOf("A"), repository.recentEpisodes(limit = 1) { now }.first().map { it.book.title })
    }

    @Test
    fun `recently added drops episodes published more than 30 days ago`() = runTest {
        subscribe()
        // 30 days back from here is 29 Oct, 00:00: B (06:00 that day) is still in, C (28 Oct) is not.
        val later = Instant.parse("2025-11-28T00:00:00Z").toEpochMilli()

        assertEquals(listOf("A", "B"), repository.recentEpisodes { later }.first().map { it.book.title })
    }

    @Test
    fun `recently added drops never started episodes behind a newer started or played one of the subscription`() = runTest {
        val feedId = subscribe()
        listen(idOf(feedId, "B"), positionMs = 30_000, updatedAt = 100)

        assertEquals(listOf("A", "B"), repository.recentEpisodes { now }.first().map { it.book.title })

        listen(idOf(feedId, "B"), positionMs = 600_000, updatedAt = 200, finished = true)

        assertEquals(listOf("A"), repository.recentEpisodes { now }.first().map { it.book.title })
    }

    @Test
    fun `recently added leaves out hidden episodes`() = runTest {
        val feedId = subscribe()

        val hidden = repository.recentEpisodes(hidden = flowOf(setOf(idOf(feedId, "A")))) { now }.first()

        assertEquals(listOf("B", "C"), hidden.map { it.book.title })
    }

    @Test
    fun `a cleared recently added row comes back with a newer episode, a hidden episode with undo`() = runTest {
        val queue = EpisodeQueue(ApplicationProvider.getApplicationContext(), db.bookDao())
        val feedId = subscribe()
        val c = idOf(feedId, "C")
        val hidden = combine(queue.hidden, queue.recentHidden, queue.recentCleared) { x, y, z -> x + y + z }
        val recent = { repository.recentEpisodes(hidden) { now } }
        val continueTitles = suspend { repository.continueListening(queue.order, queue.hidden) { now }.first().map { it.book.title } }
        try {
            queue.hideFromRecent(c)
            assertEquals(listOf("A", "B"), recent().first().map { it.book.title })
            assertEquals("Continue listening keeps it", listOf("A", "B", "C"), continueTitles())
            queue.unhideFromRecent(c)
            assertEquals(listOf("A", "B", "C"), recent().first().map { it.book.title })

            queue.setRecentCleared(recent().first().map { it.book.id })
            assertTrue(recent().first().isEmpty())

            served = feed("D" to "Thu, 30 Oct 2025 18:00:00 GMT", "A" to "Thu, 30 Oct 2025 06:00:00 GMT")
            repository.refresh(feedId)
            assertEquals(listOf("D"), recent().first().map { it.book.title })
        } finally {
            // The DataStore outlives the test.
            queue.unhideFromRecent(c)
            queue.setRecentCleared(emptyList())
        }
    }

    @Test
    fun `an episode arriving after clearing appears even with an older date than the cleared ones`() = runTest {
        val queue = EpisodeQueue(ApplicationProvider.getApplicationContext(), db.bookDao())
        val recentlyAdded = RecentlyAdded(queue, repository)
        // The row counts its 30 days from the real time.
        val hour = 60 * 60 * 1000L
        val start = System.currentTimeMillis()
        fun date(ago: Long) = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(start - ago).atZone(ZoneOffset.UTC))
        served = feed("A" to date(hour), "B" to date(5 * hour))
        val feedId = subscribe()
        try {
            assertEquals(listOf("A", "B"), recentlyAdded.episodes.first().map { it.book.title })
            recentlyAdded.clear()
            assertTrue(recentlyAdded.episodes.first().isEmpty())

            // review3 Q6: the feed brings an episode dated before the newest cleared one; it was hidden by the date.
            served = feed("A" to date(hour), "Late" to date(3 * hour), "B" to date(5 * hour))
            repository.refresh(feedId)
            assertEquals(listOf("Late"), recentlyAdded.episodes.first().map { it.book.title })
        } finally {
            queue.setRecentCleared(emptyList())
        }
    }

    @Test
    fun `an episode dated in the future counts from when it appeared`() = runTest {
        val feedId = subscribe()
        served = feed("Soon" to "Fri, 01 Jan 2100 06:00:00 GMT", "A" to "Thu, 30 Oct 2025 06:00:00 GMT")
        val before = System.currentTimeMillis()
        repository.refresh(feedId)

        val soon = db.bookDao().booksOf(feedId).single { it.title == "Soon" }
        assertTrue(soon.addedAt in before..System.currentTimeMillis())
        // A second sync keeps the time it appeared instead of moving it up again.
        repository.refresh(feedId)
        assertEquals(soon.addedAt, db.bookDao().booksOf(feedId).single { it.title == "Soon" }.addedAt)
    }

    @Test
    fun `the new tab lists the newest episodes first including finished ones`() = runTest {
        val feedId = subscribe()
        listen(idOf(feedId, "B"), positionMs = 600_000, updatedAt = 100, finished = true)

        assertEquals(listOf("A", "B", "C"), repository.latestEpisodes().first().map { it.book.title })
        assertEquals(listOf("A", "B"), repository.latestEpisodes(limit = 2).first().map { it.book.title })
    }

    @Test
    fun `the new and starred tabs carry the start of the shownotes, other episode lists none`() = runTest {
        val notes = "<p>" + "Inhalt ".repeat(500) + "</p>"
        served = """<rss version="2.0"><channel><title>Test-Podcast</title><item><title>A</title><guid>guid-A</guid>""" +
            """<description><![CDATA[$notes]]></description><enclosure url="https://example.com/A.mp3" type="audio/mpeg"/></item></channel></rss>"""
        val feedId = subscribe()
        repository.setStarred(listOf(idOf(feedId, "A")), starred = true)

        assertEquals(notes.take(2_000), repository.latestEpisodes().first().single().book.description)
        assertEquals(notes.take(2_000), repository.starredEpisodes().first().single().book.description)
        assertEquals("", repository.recentEpisodes { now }.first().single().book.description)
    }

    @Test
    fun `refresh keeps started episodes the feed no longer lists and drops the rest`() = runTest {
        val feedId = subscribe()
        listen(idOf(feedId, "B"), positionMs = 60_000, updatedAt = 100)
        val ids = db.bookDao().booksOf(feedId).associate { it.title to it.bookId }
        served = feed("D" to "Fri, 31 Oct 2025 06:00:00 GMT")

        val result = repository.refresh(feedId)
        // All three listed episodes are gone at once: the sweep waits for the next sync to confirm (review G3).
        repository.refresh(feedId)

        assertEquals(1, result!!.newEpisodeIds.size)
        val titles = repository.episodes(feedId).first().map { it.book.title }
        assertEquals(listOf("D", "B"), titles)
        assertEquals("a started episode keeps its row and id", ids.getValue("B"), db.bookDao().booksOf(feedId).single { it.title == "B" }.bookId)
        assertNotNull(db.bookDao().progress(ids.getValue("B")))
    }

    @Test
    fun `refresh keeps downloaded episodes the feed no longer lists`() = runTest {
        val feedId = subscribe()
        db.podcastDao().queueDownload(idOf(feedId, "C"))
        served = feed("D" to "Fri, 31 Oct 2025 06:00:00 GMT")

        repository.refresh(feedId)
        repository.refresh(feedId)

        assertEquals(setOf("C", "D"), repository.episodes(feedId).first().map { it.book.title }.toSet())
    }

    @Test
    fun `refresh keeps queued episodes the feed no longer lists`() = runTest {
        val feedId = subscribe()
        val queued = idOf(feedId, "C")
        EpisodeQueue(ApplicationProvider.getApplicationContext(), db.bookDao()).append(queued)
        served = feed("D" to "Fri, 31 Oct 2025 06:00:00 GMT")

        repository.refresh(feedId)
        repository.refresh(feedId)

        assertEquals(setOf("C", "D"), repository.episodes(feedId).first().map { it.book.title }.toSet())
        assertEquals("the queue keeps pointing at it", queued, db.bookDao().booksOf(feedId).single { it.title == "C" }.bookId)
    }

    @Test
    fun `a feed without episodes never sweeps`() = runTest {
        val feedId = subscribe()
        served = feed()

        repository.refresh(feedId)
        repository.refresh(feedId)

        assertEquals(setOf("A", "B", "C"), repository.episodes(feedId).first().map { it.book.title }.toSet())
    }

    @Test
    fun `a feed losing more than half waits one sync, then a recovered feed keeps everything`() = runTest {
        val feedId = subscribe()
        val full = served
        served = feed("A" to "Wed, 29 Oct 2025 06:00:00 GMT")

        repository.refresh(feedId)
        assertEquals("postponed", setOf("A", "B", "C"), repository.episodes(feedId).first().map { it.book.title }.toSet())

        served = full
        repository.refresh(feedId)
        repository.refresh(feedId)
        assertEquals(setOf("A", "B", "C"), repository.episodes(feedId).first().map { it.book.title }.toSet())
    }

    @Test
    fun `a feed coming back shrunk after an empty sync still waits one sync`() = runTest {
        val feedId = subscribe()
        served = feed()
        repository.refresh(feedId)
        served = feed("A" to "Wed, 29 Oct 2025 06:00:00 GMT")

        repository.refresh(feedId)
        assertEquals("postponed", setOf("A", "B", "C"), repository.episodes(feedId).first().map { it.book.title }.toSet())

        repository.refresh(feedId)
        assertEquals("confirmed", setOf("A"), repository.episodes(feedId).first().map { it.book.title }.toSet())
    }

    @Test
    fun `a feed losing at most half sweeps right away`() = runTest {
        val feedId = subscribe()
        served = feed("A" to "Wed, 29 Oct 2025 06:00:00 GMT", "B" to "Tue, 28 Oct 2025 06:00:00 GMT")

        repository.refresh(feedId)

        assertEquals(setOf("A", "B"), repository.episodes(feedId).first().map { it.book.title }.toSet())
    }

    @Test
    fun `a failed refresh changes nothing`() = runTest {
        val feedId = subscribe()
        served = null
        assertEquals(null, repository.refresh(feedId))
        served = "<rss><channel>"
        assertEquals(null, repository.refresh(feedId))

        assertEquals(3, repository.episodes(feedId).first().size)
    }

    @Test
    fun `refreshing all reports new episodes per feed and respects the notification switch`() = runTest {
        val feedId = subscribe()
        served = feed("D" to "Fri, 31 Oct 2025 06:00:00 GMT", "A" to "Thu, 30 Oct 2025 06:00:00 GMT")

        val summary = repository.refreshAll()

        assertTrue(summary.ok)
        assertEquals(1, summary.newCount)
        val news = summary.newEpisodes.single()
        assertEquals(feedId, news.feedId)
        assertTrue(news.notify)

        repository.setNotifyNew(feedId, false)
        served = feed("E" to "Sat, 01 Nov 2025 06:00:00 GMT", "D" to "Fri, 31 Oct 2025 06:00:00 GMT")
        assertEquals(false, repository.refreshAll().newEpisodes.single().notify)
    }

    @Test
    fun `refreshing all reports a failure and counts nothing for an unreachable feed`() = runTest {
        subscribe()
        served = null

        val summary = repository.refreshAll()

        assertEquals(false, summary.ok)
        assertEquals(0, summary.newCount)
    }

    @Test
    fun `an episode keeps its id and first-seen date across refreshes`() = runTest {
        served = feed("A" to "")
        val feedId = subscribe()
        val before = db.bookDao().booksOf(feedId).single()

        repository.refresh(feedId)
        val after = db.bookDao().booksOf(feedId).single()

        assertEquals(before.bookId, after.bookId)
        assertEquals(before.addedAt, after.addedAt)
    }

    @Test
    fun `unsubscribing removes episodes and progress`() = runTest {
        val feedId = subscribe()
        val id = idOf(feedId, "A")
        listen(id, 1_000, 1)

        repository.unsubscribe(feedId)

        assertTrue(db.bookDao().booksOf(feedId).isEmpty())
        assertEquals(null, db.bookDao().progress(id))
        assertTrue(repository.feeds.first().isEmpty())
    }

    @Test
    fun `the next episode follows in the order of the podcast`() = runTest {
        val feedId = subscribe()
        val (a, b, c) = listOf("A", "B", "C").map { idOf(feedId, it) }

        assertEquals(b, repository.autoNextAfter(c))
        assertEquals(a, repository.autoNextAfter(b))
        assertEquals(null, repository.autoNextAfter(a))
    }

    @Test
    fun `newest first reverses the order and finished episodes are skipped`() = runTest {
        val feedId = subscribe()
        val (a, b, c) = listOf("A", "B", "C").map { idOf(feedId, it) }
        repository.setNewestFirst(feedId, true)
        listen(b, positionMs = 600_000, updatedAt = 1, finished = true)

        assertEquals("B is finished", c, repository.autoNextAfter(a))
        assertEquals(null, repository.autoNextAfter(c))
    }

    @Test
    fun `marking all as played finishes the open episodes and keeps the speed`() = runTest {
        val feedId = subscribe()
        val (a, b, c) = listOf("A", "B", "C").map { idOf(feedId, it) }
        listen(a, positionMs = 600_000, updatedAt = 1, finished = true)
        db.bookDao().upsertProgress(BookProgressEntity(b, 60_000, 600_000, finished = false, speed = 1.5f, updatedAt = 2))

        assertEquals("A was already finished", 2, repository.setAllPlayed(feedId))

        listOf(a, b, c).forEach { assertEquals(true, db.bookDao().progress(it)!!.finished) }
        assertEquals(600_000, db.bookDao().progress(c)!!.positionMs)
        assertEquals(1.5f, db.bookDao().progress(b)!!.speed)
        assertEquals(0, repository.setAllPlayed(feedId))
        assertEquals(emptyList<String>(), repository.continueListening { now }.first().map { it.book.title })
    }

    @Test
    fun `marking several as played can be undone to the exact state before`() = runTest {
        val feedId = subscribe()
        val (a, b, c) = listOf("A", "B", "C").map { idOf(feedId, it) }
        db.bookDao().upsertProgress(BookProgressEntity(b, 60_000, 600_000, finished = false, speed = 1.5f, updatedAt = 2))

        val undo = repository.setPlayed(listOf(b, c), played = true)

        assertEquals(true, db.bookDao().progress(b)!!.finished)
        assertEquals(true, db.bookDao().progress(c)!!.finished)
        assertEquals(null, db.bookDao().progress(a))

        undo()

        val restored = db.bookDao().progress(b)!!
        assertEquals(60_000, restored.positionMs)
        assertEquals(false, restored.finished)
        assertEquals(1.5f, restored.speed)
        assertTrue("newer than the marking", restored.updatedAt > 2)
        assertEquals("never started again", null, db.bookDao().progress(c))
    }

    @Test
    fun `nothing is queued when queueing the next episode is off`() = runTest {
        val feedId = subscribe()
        repository.setContinuous(feedId, false)

        assertEquals(null, repository.autoNextAfter(idOf(feedId, "C")))
    }

    private suspend fun stateOf(bookId: Long) = db.podcastDao().trackOf(bookId)!!.downloadState

    @Test
    fun `download marks list episodes with a download and count the finished ones per feed`() = runTest {
        val feedId = subscribe()
        val (a, b, c) = listOf("A", "B", "C").map { idOf(feedId, it) }
        val dao = db.podcastDao()
        dao.queueDownload(a)
        dao.markDownloadRunning(a)
        dao.markDownloadDone(a, "/files/episode-$a.mp3", 1234)
        dao.queueDownload(b)

        assertEquals(mapOf(a to TrackDownload.DONE, b to TrackDownload.QUEUED), repository.episodeDownloadStates().first())
        assertEquals(mapOf(feedId to 1), repository.downloadedEpisodeCounts().first())
        assertEquals(TrackDownload.NONE, stateOf(c))
    }

    @Test
    fun `a refresh keeps the download of an episode whose file address is unchanged`() = runTest {
        val feedId = subscribe()
        val a = idOf(feedId, "A")
        val dao = db.podcastDao()
        dao.queueDownload(a)
        dao.markDownloadRunning(a)
        dao.markDownloadDone(a, "/files/episode-$a.mp3", 1234)

        repository.refresh(feedId)

        val track = dao.trackOf(a)!!
        assertEquals(TrackDownload.DONE, track.downloadState)
        assertEquals("/files/episode-$a.mp3", track.filePath)
        assertEquals(1234, track.fileSize)
    }

    @Test
    fun `a new file address of the same episode keeps its download and only the address changes`() = runTest {
        val feedId = subscribe()
        val a = idOf(feedId, "A")
        val dao = db.podcastDao()
        dao.queueDownload(a)
        dao.markDownloadRunning(a)
        dao.markDownloadDone(a, "/files/old.mp3", 1)
        // E.g. a token that changes on every fetch.
        served = served!!.replace("https://example.com/A.mp3", "https://example.com/A.mp3?token=2")

        repository.refresh(feedId)

        val track = dao.trackOf(a)!!
        assertEquals("https://example.com/A.mp3?token=2", track.remoteFileId)
        assertEquals(TrackDownload.DONE, track.downloadState)
        assertEquals("/files/old.mp3", track.filePath)
        assertEquals(1, track.fileSize)
    }

    @Test
    fun `a failed download starts over with a new file address`() = runTest {
        val feedId = subscribe()
        val a = idOf(feedId, "A")
        val dao = db.podcastDao()
        dao.queueDownload(a)
        dao.markDownloadRunning(a)
        dao.markDownloadFailed(a, DownloadFailure.NOT_FOUND)
        served = served!!.replace("https://example.com/A.mp3", "https://example.com/A-new.mp3")

        repository.refresh(feedId)

        val track = dao.trackOf(a)!!
        assertEquals("https://example.com/A-new.mp3", track.remoteFileId)
        assertEquals(TrackDownload.NONE, track.downloadState)
        assertEquals(null, track.downloadFailure)
    }

    @Test
    fun `the reason of a failed download is kept until it is retried or removed and syncs do not touch it`() = runTest {
        val feedId = subscribe()
        val a = idOf(feedId, "A")
        val dao = db.podcastDao()
        assertEquals(null, repository.downloadFailure(a).first())
        dao.queueDownload(a)
        dao.markDownloadRunning(a)
        dao.markDownloadFailed(a, DownloadFailure.NOT_FOUND)
        assertEquals(DownloadFailure.NOT_FOUND, repository.downloadFailure(a).first())

        // Same address: the sync leaves the failed state and its reason alone.
        repository.refresh(feedId)
        assertEquals(DownloadFailure.NOT_FOUND, repository.downloadFailure(a).first())

        // Retrying clears it; a finished download has none.
        assertEquals(1, dao.queueDownload(a))
        assertEquals(null, dao.trackOf(a)!!.downloadFailure)
        dao.markDownloadRunning(a)
        dao.markDownloadFailed(a, DownloadFailure.SERVER)
        dao.markDownloadRemoved(a)
        assertEquals(null, dao.trackOf(a)!!.downloadFailure)
        assertEquals(null, repository.downloadFailure(a).first())
    }

    @Test
    fun `the automatic download takes the newest unheard episodes after the last heard one and leaves removed ones alone`() = runTest {
        served = feed(
            "E" to "Fri, 31 Oct 2025 06:00:00 GMT", "D" to "Thu, 30 Oct 2025 06:00:00 GMT", "C" to "Wed, 29 Oct 2025 06:00:00 GMT",
            "B" to "Tue, 28 Oct 2025 06:00:00 GMT", "A" to "Mon, 27 Oct 2025 06:00:00 GMT",
        )
        val feedId = subscribe()
        val (e, d, c, b) = listOf("E", "D", "C", "B").map { idOf(feedId, it) }
        val dao = db.podcastDao()
        listen(b, positionMs = 600_000, updatedAt = 1, finished = true)
        dao.queueDownload(c)
        dao.markDownloadRemoved(c)

        dao.queueLatestUnheard(feedId, 3)

        assertEquals(TrackDownload.QUEUED, stateOf(e))
        assertEquals(TrackDownload.QUEUED, stateOf(d))
        assertEquals("removed by the user", TrackDownload.REMOVED, stateOf(c))
        assertEquals("finished", TrackDownload.NONE, stateOf(b))
        assertEquals("older than the last heard one", TrackDownload.NONE, stateOf(idOf(feedId, "A")))
        assertEquals("newest queued first", e, dao.nextQueuedDownload()!!.bookId)
    }

    @Test
    fun `the automatic download leaves the backlog alone once the newest episode is heard`() = runTest {
        served = feed(
            "C" to "Wed, 29 Oct 2025 06:00:00 GMT", "B" to "Tue, 28 Oct 2025 06:00:00 GMT", "A" to "Mon, 27 Oct 2025 06:00:00 GMT",
        )
        val feedId = subscribe()
        val (c, b, a) = listOf("C", "B", "A").map { idOf(feedId, it) }
        listen(c, positionMs = 600_000, updatedAt = 1, finished = true)

        assertEquals(0, db.podcastDao().queueLatestUnheard(feedId, 3))
        assertEquals(TrackDownload.NONE, stateOf(b))
        assertEquals(TrackDownload.NONE, stateOf(a))
    }

    @Test
    fun `newest only removes older automatic downloads once a newer one is done but keeps manual and started ones`() = runTest {
        served = feed(
            "D" to "Thu, 30 Oct 2025 06:00:00 GMT", "C" to "Wed, 29 Oct 2025 06:00:00 GMT",
            "B" to "Tue, 28 Oct 2025 06:00:00 GMT", "A" to "Mon, 27 Oct 2025 06:00:00 GMT",
        )
        val feedId = subscribe()
        val (d, c, b, a) = listOf("D", "C", "B", "A").map { idOf(feedId, it) }
        val dao = db.podcastDao()
        dao.queueLatestUnheard(feedId, 3)
        dao.queueDownload(a)
        for (id in listOf(c, b, a)) {
            dao.markDownloadRunning(id)
            dao.markDownloadDone(id, "/x/episode-$id.mp3", 100)
        }
        listen(b, positionMs = 60_000, updatedAt = 1, finished = false)

        // The newest one is still downloading: nothing goes.
        assertEquals(emptyList<Long>(), dao.supersededAutomaticDownloads(feedId))

        dao.markDownloadRunning(d)
        dao.markDownloadDone(d, "/x/episode-$d.mp3", 100)

        // C automatic and never started; B started, A manual.
        assertEquals(listOf(c), dao.supersededAutomaticDownloads(feedId))
    }

    @Test
    fun `removing a queued or finished download is remembered and manual download works again`() = runTest {
        val feedId = subscribe()
        val a = idOf(feedId, "A")
        val dao = db.podcastDao()
        assertEquals(1, dao.queueDownload(a))
        assertEquals(0, dao.queueDownload(a))

        dao.markDownloadRemoved(a)
        assertEquals(TrackDownload.REMOVED, stateOf(a))
        dao.queueLatestUnheard(feedId, 3)
        assertEquals("the automation does not bring it back", TrackDownload.REMOVED, stateOf(a))
        assertEquals(1, dao.queueDownload(a))
    }

    @Test
    fun `a show from ARD Sounds is subscribed by its link and refreshed over graphql`() = runTest {
        val feedId = (repository.subscribe("https://www.ardsounds.de/sendung/radio-tatort/urn:ard:show:abc/") as SubscribeResult.Added).feedId

        val feed = db.podcastDao().feed(feedId)!!
        assertEquals("ardsounds:urn:ard:show:abc", feed.url)
        assertEquals("Radio Tatort", feed.title)
        assertEquals("ARD", feed.author)
        assertEquals(listOf("Fall 2", "Fall 1"), repository.episodes(feedId).first().map { it.book.title })
        assertEquals("https://dl.example/Fall 2.mp3", db.bookDao().tracks(idOf(feedId, "Fall 2")).single().remoteFileId)
        assertEquals(listOf("https://api.ardaudiothek.de/graphql"), requested)
        assertEquals(SubscribeResult.AlreadySubscribed(feedId), repository.subscribe("ardsounds:urn:ard:show:abc"))
        assertEquals(mapOf("ardsounds:urn:ard:show:abc" to feedId), repository.subscribedByKey.first())

        ardServed = show("Fall 3", "Fall 2", "Fall 1")
        val result = repository.refresh(feedId)!!

        assertEquals(listOf(idOf(feedId, "Fall 3")), result.newEpisodeIds)
    }

    @Test
    fun `syncing all fetches several feeds at once but never more than the limit`() = runTest {
        val feedIds = (1..6).map { (repository.subscribe("https://example.com/feed$it.xml") as SubscribeResult.Added).feedId }
        served = feed("New" to "Fri, 31 Oct 2025 06:00:00 GMT", "A" to "Thu, 30 Oct 2025 06:00:00 GMT")
        val inFlight = java.util.concurrent.atomic.AtomicInteger()
        val most = java.util.concurrent.atomic.AtomicInteger()
        duringFetch = {
            most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            Thread.sleep(200)
            inFlight.decrementAndGet()
        }

        val summary = repository.refreshAll()

        assertTrue("at most ${most.get()} at once", most.get() in 2..4)
        assertTrue(summary.ok)
        // Every subscription adopted its own result.
        assertEquals(feedIds.toSet(), summary.newEpisodes.map { it.feedId }.toSet())
        feedIds.forEach { id -> assertTrue(db.bookDao().booksOf(id).any { it.title == "New" }) }
    }

    @Test
    fun `a fetch of the old address is dropped when the subscription moves meanwhile`() = runTest {
        val feedId = subscribe()
        served = feed("Old source" to "Fri, 31 Oct 2025 06:00:00 GMT", "A" to "Thu, 30 Oct 2025 06:00:00 GMT")
        duringFetch = {
            duringFetch = {}
            kotlinx.coroutines.runBlocking {
                val feed = db.podcastDao().feed(feedId)!!
                db.podcastDao().upsertFeed(feed.copy(url = "https://example.com/moved.xml", etag = null, lastModified = null))
            }
        }

        assertEquals(emptyList<Long>(), repository.refresh(feedId)!!.newEpisodeIds)
        assertTrue(db.bookDao().booksOf(feedId).none { it.title == "Old source" })

        // The next sync fetches the new address and adopts it.
        repository.refresh(feedId)
        assertEquals("https://example.com/moved.xml", requested.last())
        assertTrue(db.bookDao().booksOf(feedId).any { it.title == "Old source" })
    }

    @Test
    fun `a paused subscription is left out of syncing all and only its own refresh fetches it`() = runTest {
        val feedId = subscribe()
        repository.setKeepUpdated(feedId, false)
        served = feed("New" to "Fri, 31 Oct 2025 06:00:00 GMT", "A" to "Thu, 30 Oct 2025 06:00:00 GMT")
        requested.clear()

        val summary = repository.refreshAll()

        assertTrue(requested.isEmpty())
        assertTrue(summary.ok && !summary.allFailed && summary.newEpisodes.isEmpty())
        assertTrue(db.bookDao().booksOf(feedId).none { it.title == "New" })
        assertTrue(repository.feed(feedId).first()!!.isPaused)

        val own = repository.refresh(feedId)!!
        assertEquals(listOf(idOf(feedId, "New")), own.newEpisodeIds)

        repository.setKeepUpdated(feedId, true)
        served = feed("Newer" to "Sat, 01 Nov 2025 06:00:00 GMT", "New" to "Fri, 31 Oct 2025 06:00:00 GMT")
        val resumed = repository.refreshAll()
        assertEquals(listOf(idOf(feedId, "Newer")), resumed.newEpisodes.single().episodeIds)
    }

    @Test
    fun `a paused subscription that was never synced is fetched once with the others`() = runTest {
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "restored", url = "https://example.com/feed.xml", title = "Restored", keepUpdated = false))

        repository.refreshAll()

        assertEquals(3, db.bookDao().booksOf("restored").size)
        requested.clear()
        repository.refreshAll()
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `a paused subscription shows no problem from an old failure streak`() = runTest {
        val feedId = subscribe()
        servedCode = 410
        repeat(FEED_PROBLEM_AFTER) { repository.refresh(feedId) }
        assertTrue(repository.feed(feedId).first()!!.hasProblem)

        repository.setKeepUpdated(feedId, false)

        assertEquals(false, repository.feed(feedId).first()!!.hasProblem)
    }

    @Test
    fun `refreshing only the stale feeds does not count a lost connection against them`() = runTest {
        val stale = subscribe()
        val current = (repository.subscribe("ardsounds:urn:ard:show:abc") as SubscribeResult.Added).feedId
        served = null
        requested.clear()

        val summary = repository.refreshAll { it.feedId == stale }

        assertTrue(summary.allFailed)
        assertEquals(0, db.podcastDao().feed(stale)!!.failedRefreshes)
        assertEquals(null, db.podcastDao().feed(stale)!!.lastError)
        // Only the part was queried.
        assertTrue(requested.none { it.contains("api.ardaudiothek.de") })
        assertNotNull(db.podcastDao().feed(current))

        // If something gets through, it's the feed's fault: that counts.
        val both = repository.refreshAll()
        assertEquals(false, both.allFailed)
        assertEquals(1, db.podcastDao().feed(stale)!!.failedRefreshes)
    }

    @Test
    fun `a broken ARD Sounds answer fails only its own shows`() = runTest {
        val rss = subscribe()
        val ard = (repository.openPreview("ardsounds:urn:ard:show:abc") as SubscribeResult.Added).feedId
        repository.subscribeTrial(ard)

        ardServed = """{"data":{"show":{"items":{"nodes":"changed"}}}}"""
        val summary = repository.refreshAll()

        assertEquals(false, summary.ok)
        assertEquals(null, repository.refresh(ard))
        assertNotNull(repository.refresh(rss))
        assertEquals(2, db.bookDao().booksOf(ard).size)
        ardServed = """{"data":{"show":null}}"""
        assertEquals(SubscribeResult.NotAFeed, repository.subscribe("ardsounds:urn:ard:show:gone"))
    }

    @Test
    fun `ARD Sounds shows go into opml as rss bridge feeds and come back as ARD Sounds subscriptions`() = runTest {
        subscribe()
        val ard = (repository.subscribe("ardsounds:urn:ard:show:abc") as SubscribeResult.Added).feedId
        val bridgeFeed = OpmlFeed(
            "Radio Tatort",
            "https://bridge.example/rss/?action=display&bridge=ARDAudiothekBridge&path=urn%3Aard%3Ashow%3Aabc&format=Atom",
        )

        val exported = Opml.parse(repository.exportOpml("Subscriptions", "Mon, 2 Nov 2026 14:10:40 +0100", "https://bridge.example/rss/").byteInputStream())
        val withoutBridge = Opml.parse(repository.exportOpml("Subscriptions", "Mon, 2 Nov 2026 14:10:40 +0100", rssBridge = null).byteInputStream())

        assertEquals(listOf(bridgeFeed, OpmlFeed("Test-Podcast", "https://example.com/feed.xml")), exported)
        assertEquals(listOf(OpmlFeed("Test-Podcast", "https://example.com/feed.xml")), withoutBridge)
        // The own file back: nothing new, the show stays the same subscription.
        assertEquals(OpmlImportResult(added = 0, skipped = 2, removed = 0), repository.importOpml(exported, replace = true))
        assertTrue(ard in db.podcastDao().feeds().map { it.feedId })

        // A file without ARD shows (exported without bridge) doesn't replace them; one with other shows does.
        repository.importOpml(withoutBridge, replace = true)
        assertTrue(ard in db.podcastDao().feeds().map { it.feedId })
        repository.importOpml(withoutBridge + OpmlFeed("Andere", "ardsounds:urn:ard:show:xyz"), replace = true)
        assertTrue(ard !in db.podcastDao().feeds().map { it.feedId })
        // A bridge address recreates it.
        repository.importOpml(listOf(bridgeFeed), replace = false)
        assertEquals("ardsounds:urn:ard:show:abc", db.podcastDao().feeds().single { it.title == "Radio Tatort" }.url)
    }

    @Test
    fun `descriptions keep their html and doubly escaped markup is resolved`() {
        assertEquals("<p>Hello <b>world</b></p>", " <p>Hello <b>world</b></p>\n".toDescriptionHtml())
        assertEquals("<p>Hello <b>world</b></p>", "&lt;p&gt;Hello &lt;b&gt;world&lt;/b&gt;&lt;/p&gt;".toDescriptionHtml())
        assertEquals("Tom &amp; Jerry", "Tom &amp; Jerry".toDescriptionHtml())
    }

    private companion object {
        /** ARD Sounds response for a show with the episodes [titles], newest first. */
        fun show(vararg titles: String): String = buildString {
            append("""{"data":{"show":{"id":"9","coreId":"urn:ard:show:abc","title":"Radio Tatort","description":"Krimis",""")
            append(""""publicationService":{"title":"ARD"},"items":{"nodes":[""")
            titles.forEachIndexed { index, title ->
                if (index > 0) append(',')
                append("""{"coreId":"urn:ard:publication:$title","title":"$title","publishDate":"2026-10-${20 - index}T10:00:00+02:00","duration":600,""")
                append(""""audios":[{"url":"https://dl.example/$title.mp3","downloadUrl":"https://dl.example/$title.mp3","allowDownload":true,"mimeType":"audio/mp3"}]}""")
            }
            append("]}}}}")
        }

        fun feed(vararg episodes: Pair<String, String>): String = buildString {
            append("""<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>Test-Podcast</title>""")
            episodes.forEach { (title, date) ->
                append("<item><title>$title</title><guid>guid-$title</guid>")
                if (date.isNotEmpty()) append("<pubDate>$date</pubDate>")
                append("""<enclosure url="https://example.com/$title.mp3" type="audio/mpeg"/><itunes:duration>10:00</itunes:duration></item>""")
            }
            append("</channel></rss>")
        }
    }
}
