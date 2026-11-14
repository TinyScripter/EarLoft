package de.kleinabersmart.earloft.core.data.podcast

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.datasource.local.AudioFileChapters
import de.kleinabersmart.earloft.core.model.FeedError
import de.kleinabersmart.earloft.core.network.podcast.FeedAuthInterceptor
import de.kleinabersmart.earloft.core.network.podcast.FeedAuthRegistry
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

/** Geschützte Feeds (Konzept 12.10): Abfrage bei 401, Speichern je Abo, Abgleich mit den gespeicherten Zugangsdaten. */
@RunWith(RobolectricTestRunner::class)
class FeedCredentialsTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var server: MockWebServer
    private lateinit var repository: PodcastRepository
    private val secrets = MemorySecretStore()
    private val registry = FeedAuthRegistry()

    /** Was der Feed als Anmeldung annimmt. */
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
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    // MockWebServer spricht kein HTTPS: Die Adresse ohne Schema würde zu https://.
    private val feedUrl get() = server.url("/feed.xml").toString()

    @Test
    fun `a protected feed asks for credentials and keeps them`() = runTest {
        assertEquals(SubscribeResult.CredentialsNeeded(rejected = false), repository.subscribe(feedUrl))
        assertEquals(SubscribeResult.CredentialsNeeded(rejected = true), repository.subscribe(feedUrl, PodcastRepository.Credentials("anna", "falsch")))

        val added = repository.subscribe(feedUrl, PodcastRepository.Credentials("anna", "geheim")) as SubscribeResult.Added

        assertEquals(1, db.bookDao().booksOf(added.feedId).size)
        assertEquals("anna", repository.credentials(added.feedId).first()?.username)
        assertNotNull(secrets.values["feed:${added.feedId}:auth"])
        // Der Abgleich nimmt die gespeicherten.
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
    fun `removed credentials are gone from the store`() = runTest {
        val added = repository.subscribe(feedUrl, PodcastRepository.Credentials("anna", "geheim")) as SubscribeResult.Added

        repository.setCredentials(added.feedId, null)

        assertNull(repository.credentials(added.feedId).first())
        assertFalse(secrets.values.containsKey("feed:${added.feedId}:auth"))
    }

    @Test
    fun `an ended subscription takes its credentials along`() = runTest {
        // Ein echter Thread: Room liefert auf eigenen Threads.
        val observer = CoroutineScope(Dispatchers.Default)
        val observed = FeedAuthRegistry()
        val credentials = testFeedCredentials(db, secrets, observed, observer)
        credentials.start()
        val added = repository.subscribe(feedUrl, PodcastRepository.Credentials("anna", "geheim")) as SubscribeResult.Added
        // Erst nachdem der Beobachter das Abo gesehen hat, ist es eins, das verschwinden kann.
        eventually { observed.forUrl(server.url("/1.mp3")) != null }

        repository.unsubscribe(added.feedId)
        eventually { observed.forUrl(server.url("/1.mp3")) == null }

        assertTrue(secrets.values.keys.none { it.startsWith("feed:${added.feedId}") })
        assertNull(credentials.forFeed(added.feedId))
        observer.cancel()
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
