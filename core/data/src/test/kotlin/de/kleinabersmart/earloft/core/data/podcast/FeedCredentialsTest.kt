package de.kleinabersmart.earloft.core.data.podcast

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.backup.MusicBackup
import de.kleinabersmart.earloft.core.data.backup.RestoreNotice
import de.kleinabersmart.earloft.core.data.backup.UserDataBackup
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.UserDatabase
import de.kleinabersmart.earloft.core.datasource.local.AudioFileChapters
import de.kleinabersmart.earloft.core.model.FeedError
import de.kleinabersmart.earloft.core.network.podcast.FeedAuthInterceptor
import de.kleinabersmart.earloft.core.network.podcast.FeedAuthRegistry
import de.kleinabersmart.earloft.core.network.podcast.OpmlFeed
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Protected feeds (concept 12.10): prompt on 401, storing per subscription, sync with the stored credentials. */
@RunWith(RobolectricTestRunner::class)
class FeedCredentialsTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var server: MockWebServer
    private lateinit var repository: PodcastRepository
    private val secrets = MemorySecretStore()
    private val registry = FeedAuthRegistry()

    /** What the feed accepts as credentials. */
    private var accepted = Credentials.basic("anna", "geheim")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.headers["Authorization"] != accepted) {
                        MockResponse.Builder().code(401).addHeader("WWW-Authenticate", "Basic realm=\"feed\"").build()
                    } else {
                        MockResponse.Builder().addHeader("Content-Type", "application/rss+xml").body(FEED).build()
                    }
            }
            start()
        }
        val client = OkHttpClient.Builder().addNetworkInterceptor(FeedAuthInterceptor(registry)).build()
        repository = PodcastRepository(
            db, db.podcastDao(), db.bookDao(), PodcastFeedApi(client), ArdSoundsApi(client),
            PodcastSettings(ApplicationProvider.getApplicationContext()),
            AudioFileChapters(ApplicationProvider.getApplicationContext()),
            { error("kein Quellenwechsel im Test") },
            testFeedCredentials(db, secrets, registry),
            EpisodeQueue(ApplicationProvider.getApplicationContext(), db.bookDao()),
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    // MockWebServer doesn't speak HTTPS: the address without a scheme would become https://.
    private val feedUrl get() = server.url("/feed.xml").toString()

    @Test
    fun `a protected feed asks for credentials and keeps them`() = runTest {
        assertEquals(SubscribeResult.CredentialsNeeded(rejected = false), repository.subscribe(feedUrl))
        assertEquals(SubscribeResult.CredentialsNeeded(rejected = true), repository.subscribe(feedUrl, PodcastRepository.Credentials("anna", "falsch")))

        val added = repository.subscribe(feedUrl, PodcastRepository.Credentials("anna", "geheim")) as SubscribeResult.Added

        assertEquals(1, db.bookDao().booksOf(added.feedId).size)
        assertEquals("anna", repository.credentials(added.feedId).first()?.username)
        assertNotNull(secrets.values["feed:${added.feedId}:auth"])
        // The sync uses the stored ones.
        assertNotNull(repository.refresh(added.feedId))
    }

    @Test
    fun `credentials in the address are not stored with it`() = runTest {
        val withUser = server.url("/feed.xml").newBuilder().username("anna").password("geheim").build().toString()

        val added = repository.subscribe(withUser) as SubscribeResult.Added

        assertEquals(feedUrl, db.podcastDao().feed(added.feedId)?.url)
        assertEquals("geheim", repository.credentials(added.feedId).first()?.password)
    }

    @Test
    fun `credentials in an imported address are stored apart and the import refreshes with them`() = runTest {
        val withUser = server.url("/feed.xml").newBuilder().username("anna").password("geheim").build().toString()

        val result = repository.importOpml(listOf(OpmlFeed("Geschützt", withUser), OpmlFeed("Doppelt", feedUrl)), replace = false)

        assertEquals(1, result.added)
        val feed = db.podcastDao().feeds().single()
        assertEquals(feedUrl, feed.url)
        assertEquals("geheim", repository.credentials(feed.feedId).first()?.password)
        assertNotNull(repository.refresh(feed.feedId))
        assertFalse(repository.exportOpml("EarLoft", "2026-11-15", rssBridge = null).contains("geheim"))
    }

    @Test
    fun `changed credentials count as a denied refresh until they are fixed`() = runTest {
        val added = repository.subscribe(feedUrl, PodcastRepository.Credentials("anna", "geheim")) as SubscribeResult.Added
        accepted = Credentials.basic("anna", "neu")

        assertNull(repository.refresh(added.feedId))
        assertEquals(FeedError.DENIED, db.podcastDao().feed(added.feedId)?.lastError)

        assertNotNull(repository.setCredentials(added.feedId, PodcastRepository.Credentials("anna", "neu")))
        assertNull(db.podcastDao().feed(added.feedId)?.lastError)
        assertEquals("neu", repository.credentials(added.feedId).first()?.password)
    }

    @Test
    fun `a protected feed that moved to another host takes new credentials for that host`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val user = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build()
        val old = "http://127.0.0.1:${server.port}/feed.xml"
        val new = "http://localhost:${server.port}/new.xml"
        var moved = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                moved && request.url.host == "127.0.0.1" -> MockResponse.Builder().code(301).addHeader("Location", new).build()
                request.headers["Authorization"] != accepted ->
                    MockResponse.Builder().code(401).addHeader("WWW-Authenticate", "Basic realm=\"feed\"").build()
                else -> MockResponse.Builder().addHeader("Content-Type", "application/rss+xml").body(FEED).build()
            }
        }
        lateinit var switch: PodcastSourceSwitch
        val client = OkHttpClient.Builder().addNetworkInterceptor(FeedAuthInterceptor(registry)).build()
        val repository = PodcastRepository(
            db, db.podcastDao(), db.bookDao(), PodcastFeedApi(client), ArdSoundsApi(client), PodcastSettings(context),
            AudioFileChapters(context), { switch }, testFeedCredentials(db, secrets, registry), EpisodeQueue(context, db.bookDao()),
        )
        val backup = UserDataBackup(
            context = context, database = db, userDao = user.userDataDao(), sourceDao = db.sourceDao(), bookDao = db.bookDao(),
            podcastDao = db.podcastDao(), statsDao = db.statsDao(), music = MusicBackup(db.backupDao(), db.playlistDao(), db.syncDao()),
            restoreNotice = RestoreNotice(context, { error("nicht gebraucht") }, { error("nicht gebraucht") }),
            registry = { error("nicht gebraucht") }, ardBooks = { error("nicht gebraucht") }, scope = TestScope(),
        )
        switch = PodcastSourceSwitch(db, repository, db.podcastDao(), db.bookDao(), db.statsDao(), backup, emptySet())
        val feedId = (repository.subscribe(old, PodcastRepository.Credentials("anna", "geheim")) as SubscribeResult.Added).feedId

        // The feed moves permanently to a different host and wants different credentials there.
        moved = true
        accepted = Credentials.basic("anna", "neu")
        assertNull(repository.refresh(feedId))
        assertEquals(FeedError.DENIED, db.podcastDao().feed(feedId)?.lastError)
        assertEquals(new, repository.credentialsAddress(feedId).first())

        assertNotNull(repository.setCredentials(feedId, PodcastRepository.Credentials("anna", "neu")))

        assertEquals("localhost", repository.credentials(feedId).first()?.scope?.host)
        assertEquals(new, db.podcastDao().feed(feedId)?.url)
        assertNull(repository.credentialsAddress(feedId).first())
        user.close()
    }

    @Test
    fun `removed credentials are gone from the store`() = runTest {
        val added = repository.subscribe(feedUrl, PodcastRepository.Credentials("anna", "geheim")) as SubscribeResult.Added

        repository.setCredentials(added.feedId, null)

        assertNull(repository.credentials(added.feedId).first())
        assertFalse(secrets.values.containsKey("feed:${added.feedId}:auth"))
    }

    @Test
    fun `an ended subscription takes its credentials along`() = runTest {
        // A real thread: Room emits on its own threads.
        val observer = CoroutineScope(Dispatchers.Default)
        val observed = FeedAuthRegistry()
        val credentials = testFeedCredentials(db, secrets, observed, observer)
        credentials.start()
        val added = repository.subscribe(feedUrl, PodcastRepository.Credentials("anna", "geheim")) as SubscribeResult.Added
        // Only after the observer has seen the subscription is it one that can disappear.
        eventually { observed.forUrl(server.url("/1.mp3")) != null }

        repository.unsubscribe(added.feedId)
        eventually { observed.forUrl(server.url("/1.mp3")) == null }

        assertTrue(secrets.values.keys.none { it.startsWith("feed:${added.feedId}") })
        assertNull(credentials.forFeed(added.feedId))
        observer.cancel()
    }

    @Test
    fun `stored credentials only go along to a new source on the same host`() = runTest {
        val added = repository.subscribe(feedUrl, PodcastRepository.Credentials("anna", "geheim")) as SubscribeResult.Added
        val stored = repository.storedCredentials(added.feedId)

        val sameHost = repository.fetchSource(server.url("/neu.xml").toString(), stored)
        assertEquals("anna", (sameHost as PodcastRepository.SourceFetch.Loaded).auth?.username)

        // The same server under a different name counts as a foreign host (e.g. a move via `itunes:new-feed-url`).
        val otherHost = if (server.hostName == "localhost") "127.0.0.1" else "localhost"
        val foreign = server.url("/neu.xml").newBuilder().host(otherHost).build()
        assertEquals(
            PodcastRepository.SourceFetch.Failed(SubscribeResult.CredentialsNeeded(rejected = false)),
            repository.fetchSource(foreign.toString(), stored),
        )
    }

    private fun eventually(condition: () -> Boolean) {
        repeat(300) {
            if (condition()) return
            Thread.sleep(10)
        }
        error("Bedingung nicht eingetreten")
    }

    private companion object {
        const val FEED = """<rss version="2.0"><channel><title>Geschützt</title>
            <item><title>Folge 1</title><guid>1</guid><pubDate>Thu, 30 Oct 2025 06:00:00 GMT</pubDate>
            <enclosure url="https://example.org/1.mp3" type="audio/mpeg" length="1"/></item>
            </channel></rss>"""
    }
}
