package keinabersmart.kleinaberlaut.core.data.podcast

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.entity.BookEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookProgressEntity
import keinabersmart.kleinaberlaut.core.model.AuthorHit
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.TrackDownload
import keinabersmart.kleinaberlaut.core.database.entity.PodcastFeedEntity
import keinabersmart.kleinaberlaut.core.network.podcast.Opml
import keinabersmart.kleinaberlaut.core.network.podcast.OpmlFeed
import keinabersmart.kleinaberlaut.core.network.podcast.PodcastFeedApi
import kotlinx.coroutines.flow.first
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

@RunWith(RobolectricTestRunner::class)
class PodcastRepositoryTest {

    private lateinit var db: KleinaberlautDatabase
    private lateinit var repository: PodcastRepository

    /** Was der „Server“ gerade ausliefert; `null` = nicht erreichbar. */
    private var served: String? = feed("A" to "Thu, 30 Oct 2025 06:00:00 GMT", "B" to "Wed, 29 Oct 2025 06:00:00 GMT", "C" to "Tue, 28 Oct 2025 06:00:00 GMT")

    private val now = Instant.parse("2025-10-31T00:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KleinaberlautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = served ?: throw IOException("offline")
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/rss+xml".toMediaType()))
                    .build()
            }
            .build()
        repository = PodcastRepository(db.podcastDao(), db.bookDao(), PodcastFeedApi(client))
    }

    @After
    fun tearDown() = db.close()

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
    fun `opml import skips known and repeated feeds and replacing keeps matches and device podcasts`() = runTest {
        val kept = subscribe()
        val dropped = (repository.subscribe("https://example.com/other.xml") as SubscribeResult.Added).feedId
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "local:x", url = "local:x", title = "Gerät"))
        val file = listOf(
            OpmlFeed("Alt", "https://example.com/feed.xml"),
            OpmlFeed("Neu", "https://example.com/new.xml"),
            OpmlFeed("Neu nochmal", "https://example.com/new.xml"),
        )

        val added = repository.importOpml(file, replace = false)
        assertEquals(OpmlImportResult(added = 1, skipped = 2, removed = 0), added)
        assertEquals(4, db.podcastDao().feeds().size)

        val replaced = repository.importOpml(file, replace = true)
        assertEquals(OpmlImportResult(added = 0, skipped = 3, removed = 1), replaced)
        val ids = db.podcastDao().feeds().map { it.feedId }
        assertTrue(kept in ids && "local:x" in ids && dropped !in ids)
        assertEquals(3, ids.size)
        // Das bestehende Abo behielt seine Folgen.
        assertEquals(3, db.bookDao().booksOf(kept).size)
    }

    @Test
    fun `opml export lists subscriptions but not device podcasts`() = runTest {
        subscribe()
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "local:x", url = "local:x", title = "Gerät"))

        val feeds = Opml.parse(repository.exportOpml("Abos", "Mon, 2 Nov 2026 14:10:40 +0100").byteInputStream())

        assertEquals(listOf(OpmlFeed("Test-Podcast", "https://example.com/feed.xml")), feeds)
    }

    @Test
    fun `feed search matches the title regardless of case and finds nothing for blank or foreign terms`() = runTest {
        val feedId = subscribe()

        assertEquals(listOf(feedId), repository.searchFeeds("test-POD").map { it.id })
        assertEquals(emptyList<String>(), repository.searchFeeds("   ").map { it.id })
        assertEquals(emptyList<String>(), repository.searchFeeds("Hörbuch").map { it.id })
    }

    @Test
    fun `book search keeps episodes and books apart and lists book authors once`() = runTest {
        subscribe()
        val dao = db.bookDao()
        fun book(title: String, author: String) = BookEntity(
            serverId = "abs", remoteId = title, title = title, normTitle = title.lowercase(), author = author, durationMs = 1,
        )
        val first = dao.insertBook(book("Dune", "Frank Herbert"))
        dao.insertBook(book("Dune Messiah", "Frank Herbert"))

        assertEquals(listOf("A", "B", "C"), dao.search(BookKind.EPISODE, "%podcast%", 10).map { it.title }.sorted())
        assertEquals(emptyList<String>(), dao.search(BookKind.BOOK, "%podcast%", 10).map { it.title })
        assertEquals(listOf("Dune", "Dune Messiah"), dao.search(BookKind.BOOK, "%dune%", 10).map { it.title })
        assertEquals(emptyList<String>(), dao.search(BookKind.EPISODE, "%dune%", 10).map { it.title })
        assertEquals(listOf(AuthorHit("Frank Herbert", first, 2)), dao.searchAuthors("%herbert%", 10))
        assertEquals(emptyList<AuthorHit>(), dao.searchAuthors("%podcast%", 10))
    }

    @Test
    fun `the same feed cannot be subscribed twice and bad input is told apart`() = runTest {
        val feedId = subscribe()

        assertEquals(SubscribeResult.AlreadySubscribed(feedId), repository.subscribe("https://example.com/feed.xml"))
        assertEquals(SubscribeResult.InvalidUrl, repository.subscribe("kein feed"))
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
    fun `refresh keeps started episodes the feed no longer lists and drops the rest`() = runTest {
        val feedId = subscribe()
        listen(idOf(feedId, "B"), positionMs = 60_000, updatedAt = 100)
        val ids = db.bookDao().booksOf(feedId).associate { it.title to it.bookId }
        served = feed("D" to "Fri, 31 Oct 2025 06:00:00 GMT")

        val result = repository.refresh(feedId)

        assertEquals(1, result!!.newEpisodeIds.size)
        val titles = repository.episodes(feedId).first().map { it.book.title }
        assertEquals(listOf("D", "B"), titles)
        assertEquals("a started episode keeps its row and id", ids.getValue("B"), db.bookDao().booksOf(feedId).single { it.title == "B" }.bookId)
        assertNotNull(db.bookDao().progress(ids.getValue("B")))
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
    fun `nothing is queued when queueing the next episode is off`() = runTest {
        val feedId = subscribe()
        repository.setContinuous(feedId, false)

        assertEquals(null, repository.autoNextAfter(idOf(feedId, "C")))
    }

    private suspend fun stateOf(bookId: Long) = db.podcastDao().trackOf(bookId)!!.downloadState

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
    fun `a new file address starts the download over`() = runTest {
        val feedId = subscribe()
        val a = idOf(feedId, "A")
        val dao = db.podcastDao()
        dao.queueDownload(a)
        dao.markDownloadRunning(a)
        dao.markDownloadDone(a, "/files/old.mp3", 1)
        served = served!!.replace("https://example.com/A.mp3", "https://example.com/A-new.mp3")

        repository.refresh(feedId)

        val track = dao.trackOf(a)!!
        assertEquals("https://example.com/A-new.mp3", track.remoteFileId)
        assertEquals(TrackDownload.NONE, track.downloadState)
        assertEquals(null, track.filePath)
    }

    @Test
    fun `the automatic download takes the newest unheard episodes and leaves removed ones alone`() = runTest {
        served = feed(
            "E" to "Fri, 31 Oct 2025 06:00:00 GMT", "D" to "Thu, 30 Oct 2025 06:00:00 GMT", "C" to "Wed, 29 Oct 2025 06:00:00 GMT",
            "B" to "Tue, 28 Oct 2025 06:00:00 GMT", "A" to "Mon, 27 Oct 2025 06:00:00 GMT",
        )
        val feedId = subscribe()
        val (e, d, c, b) = listOf("E", "D", "C", "B").map { idOf(feedId, it) }
        val dao = db.podcastDao()
        listen(e, positionMs = 600_000, updatedAt = 1, finished = true)
        dao.queueDownload(c)
        dao.markDownloadRemoved(c)

        dao.queueLatestUnheard(feedId, 3)

        assertEquals("finished", TrackDownload.NONE, stateOf(e))
        assertEquals(TrackDownload.QUEUED, stateOf(d))
        assertEquals("removed by the user", TrackDownload.REMOVED, stateOf(c))
        assertEquals(TrackDownload.QUEUED, stateOf(b))
        assertEquals("A is the fourth unheard one", TrackDownload.NONE, stateOf(idOf(feedId, "A")))
        assertEquals("newest queued first", d, dao.nextQueuedDownload()!!.bookId)
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
    fun `descriptions keep their html and doubly escaped markup is resolved`() {
        assertEquals("<p>Hallo <b>Welt</b></p>", " <p>Hallo <b>Welt</b></p>\n".toDescriptionHtml())
        assertEquals("<p>Hallo <b>Welt</b></p>", "&lt;p&gt;Hallo &lt;b&gt;Welt&lt;/b&gt;&lt;/p&gt;".toDescriptionHtml())
        assertEquals("Tom &amp; Jerry", "Tom &amp; Jerry".toDescriptionHtml())
    }

    private companion object {
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
