package de.kleinabersmart.tinyamp.core.data.podcast

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.tinyamp.core.database.TinyAmpDatabase
import de.kleinabersmart.tinyamp.core.database.entity.BookEntity
import de.kleinabersmart.tinyamp.core.database.entity.BookProgressEntity
import de.kleinabersmart.tinyamp.core.model.AuthorHit
import de.kleinabersmart.tinyamp.core.model.BookKind
import de.kleinabersmart.tinyamp.core.model.TrackDownload
import de.kleinabersmart.tinyamp.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.tinyamp.core.network.podcast.Opml
import de.kleinabersmart.tinyamp.core.network.podcast.OpmlFeed
import de.kleinabersmart.tinyamp.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.tinyamp.core.network.podcast.ardsounds.ArdSoundsApi
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

@RunWith(RobolectricTestRunner::class)
class PodcastRepositoryTest {

    private lateinit var db: TinyAmpDatabase
    private lateinit var repository: PodcastRepository

    /** Was der „Server“ gerade ausliefert; `null` = nicht erreichbar. */
    private var served: String? = feed("A" to "Thu, 30 Oct 2025 06:00:00 GMT", "B" to "Wed, 29 Oct 2025 06:00:00 GMT", "C" to "Tue, 28 Oct 2025 06:00:00 GMT")

    private val now = Instant.parse("2025-10-31T00:00:00Z").toEpochMilli()

    /** Was die GraphQL-Schnittstelle von ARD Sounds gerade ausliefert; `null` = nicht erreichbar. */
    private var ardServed: String? = show("Fall 2", "Fall 1")

    /** `false`: Der Host spricht kein HTTPS. */
    private var httpsAvailable = true

    /** Alle abgerufenen Adressen in Reihenfolge. */
    private val requested = mutableListOf<String>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TinyAmpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requested += chain.request().url.toString()
                if (chain.request().url.isHttps && !httpsAvailable) throw IOException("no TLS")
                val ard = chain.request().url.host == "api.ardaudiothek.de"
                val body = (if (ard) ardServed else served) ?: throw IOException("offline")
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/rss+xml".toMediaType()))
                    .build()
            }
            .build()
        repository = PodcastRepository(db.podcastDao(), db.bookDao(), PodcastFeedApi(client), ArdSoundsApi(client))
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
    fun `replacing with a file without feeds or without ard sounds keeps those subscriptions`() = runTest {
        val feed = subscribe()
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "ard", url = "ardsounds:urn:ard:show:5fa94a3212aa9829", title = "ARD"))

        // Leere oder fremd aufgebaute Datei (z. B. `url` statt `xmlUrl`): nichts löschen.
        assertEquals(OpmlImportResult(added = 0, skipped = 0, removed = 0), repository.importOpml(emptyList(), replace = true))
        assertEquals(2, db.podcastDao().feeds().size)

        // Ohne RSS-Bridge exportiert TinyAmp keine ARD-Abos; die Datei ersetzt sie deshalb nicht.
        val result = repository.importOpml(listOf(OpmlFeed("Neu", "https://example.com/new.xml")), replace = true)
        assertEquals(1, result.removed)
        val ids = db.podcastDao().feeds().map { it.feedId }
        assertTrue("ard" in ids && feed !in ids)
    }

    @Test
    fun `opml export lists subscriptions but not device podcasts`() = runTest {
        subscribe()
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "local:x", url = "local:x", title = "Gerät"))

        val feeds = Opml.parse(repository.exportOpml("Abos", "Mon, 2 Nov 2026 14:10:40 +0100", rssBridge = null).byteInputStream())

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
    fun `a preview stores a trial feed that is missing from all subscription lists`() = runTest {
        val feedId = (repository.openPreview("https://example.com/feed.xml") as SubscribeResult.Added).feedId
        listen(idOf(feedId, "B"), positionMs = 60_000, updatedAt = 100)

        assertEquals(false, repository.feed(feedId).first()!!.subscribed)
        assertEquals(listOf("A", "B", "C"), repository.episodes(feedId).first().map { it.book.title })
        assertTrue(repository.feeds.first().isEmpty())
        assertTrue(repository.unheardByFeed().first().isEmpty())
        assertTrue(repository.recentEpisodes().first().isEmpty())
        // Weder als neu noch als angefangen von selbst in „Weiterhören“.
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

    /** Wie die Feeds der rbb-Sender: Bild-Elemente des Kanals vorhanden, aber leer. */
    private fun feedWithoutImage(episodeImage: String?) = """
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>Ohne Bild</title>
        <itunes:image href=""/><image><url></url><title>Ohne Bild</title></image>
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
        assertTrue(repository.unheardByFeed().first().isEmpty())
        assertTrue(repository.recentEpisodes().first().isEmpty())
        assertTrue(repository.continueListening(flowOf(listOf(b))) { now }.first().isEmpty())
        assertTrue(repository.subscribedByKey.first().isEmpty())
        assertTrue(repository.searchFeeds("Test").isEmpty())
        assertTrue(db.bookDao().search(BookKind.EPISODE, "%podcast%", 10).isEmpty())
        assertEquals(listOf(feedId), repository.archivedFeeds.first().map { it.id })
        assertEquals(3, db.bookDao().booksOf(feedId).size)
        assertEquals(60_000L, db.bookDao().progress(b)!!.positionMs)
        // Kein Abgleich mehr.
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

        assertEquals(listOf("A", "C"), repository.recentEpisodes().first().map { it.book.title })
        assertEquals(listOf("A"), repository.recentEpisodes(limit = 1).first().map { it.book.title })
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
    fun `a broken ARD Sounds answer fails only its own shows`() = runTest {
        val rss = subscribe()
        val ard = (repository.openPreview("ardsounds:urn:ard:show:abc") as SubscribeResult.Added).feedId
        repository.subscribeTrial(ard)

        ardServed = """{"data":{"show":{"items":{"nodes":"geändert"}}}}"""
        val summary = repository.refreshAll()

        assertEquals(false, summary.ok)
        assertEquals(null, repository.refresh(ard))
        assertNotNull(repository.refresh(rss))
        assertEquals(2, db.bookDao().booksOf(ard).size)
        ardServed = """{"data":{"show":null}}"""
        assertEquals(SubscribeResult.NotAFeed, repository.subscribe("ardsounds:urn:ard:show:weg"))
    }

    @Test
    fun `ARD Sounds shows go into opml as rss bridge feeds and come back as ARD Sounds subscriptions`() = runTest {
        subscribe()
        val ard = (repository.subscribe("ardsounds:urn:ard:show:abc") as SubscribeResult.Added).feedId
        val bridgeFeed = OpmlFeed(
            "Radio Tatort",
            "https://bridge.example/rss/?action=display&bridge=ARDAudiothekBridge&path=urn%3Aard%3Ashow%3Aabc&format=Atom",
        )

        val exported = Opml.parse(repository.exportOpml("Abos", "Mon, 2 Nov 2026 14:10:40 +0100", "https://bridge.example/rss/").byteInputStream())
        val withoutBridge = Opml.parse(repository.exportOpml("Abos", "Mon, 2 Nov 2026 14:10:40 +0100", rssBridge = null).byteInputStream())

        assertEquals(listOf(bridgeFeed, OpmlFeed("Test-Podcast", "https://example.com/feed.xml")), exported)
        assertEquals(listOf(OpmlFeed("Test-Podcast", "https://example.com/feed.xml")), withoutBridge)
        // Die eigene Datei zurück: nichts Neues, die Sendung bleibt dasselbe Abo.
        assertEquals(OpmlImportResult(added = 0, skipped = 2, removed = 0), repository.importOpml(exported, replace = true))
        assertTrue(ard in db.podcastDao().feeds().map { it.feedId })

        // Eine Datei ohne ARD-Sendungen (ohne Bridge exportiert) ersetzt sie nicht; eine mit anderen Sendungen schon.
        repository.importOpml(withoutBridge, replace = true)
        assertTrue(ard in db.podcastDao().feeds().map { it.feedId })
        repository.importOpml(withoutBridge + OpmlFeed("Andere", "ardsounds:urn:ard:show:xyz"), replace = true)
        assertTrue(ard !in db.podcastDao().feeds().map { it.feedId })
        // Eine Bridge-Adresse legt sie wieder an.
        repository.importOpml(listOf(bridgeFeed), replace = false)
        assertEquals("ardsounds:urn:ard:show:abc", db.podcastDao().feeds().single { it.title == "Radio Tatort" }.url)
    }

    @Test
    fun `descriptions keep their html and doubly escaped markup is resolved`() {
        assertEquals("<p>Hallo <b>Welt</b></p>", " <p>Hallo <b>Welt</b></p>\n".toDescriptionHtml())
        assertEquals("<p>Hallo <b>Welt</b></p>", "&lt;p&gt;Hallo &lt;b&gt;Welt&lt;/b&gt;&lt;/p&gt;".toDescriptionHtml())
        assertEquals("Tom &amp; Jerry", "Tom &amp; Jerry".toDescriptionHtml())
    }

    private companion object {
        /** Antwort von ARD Sounds für eine Sendung mit den Folgen [titles], neueste zuerst. */
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
