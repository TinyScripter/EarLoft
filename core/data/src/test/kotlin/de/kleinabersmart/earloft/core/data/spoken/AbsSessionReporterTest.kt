package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.wear.CredentialsStamps
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.SpokenServerEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AbsSessionReporterTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var reporter: AbsSessionReporter
    private val requests = mutableListOf<Pair<String, String>>()
    private var failSessions = false
    private var bookId = 0L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric starts WorkManager itself with the first test; only if not, do it here.
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (e: IllegalStateException) {
            // already initialized
        }
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                requests += request.method + " " + request.url.encodedPath to body
                val (code, json) = when {
                    request.url.encodedPath.endsWith("/api/me") -> 200 to """{"id":"user-1","mediaProgress":[]}"""
                    failSessions -> 500 to "{}"
                    else -> 200 to "OK"
                }
                Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val api = AbsApi(client)
        val secrets = object : SecretStore {
            override suspend fun get(key: String) = "token"
            override suspend fun put(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        db.bookDao().upsertServer(SpokenServerEntity("srv", "Server", "https://abs.example/"))
        val servers = testAudiobookshelfServers(db.bookDao(), secrets, api)
        reporter = AbsSessionReporter(context, db.absSessionDao(), db.bookDao(), servers, api, PlaybackPolicy())
        bookId = db.bookDao().upsertBook(
            BookEntity(serverId = "srv", kind = BookKind.BOOK, remoteId = "item-1", title = "Book", normTitle = "book", author = "Author", durationMs = 36_000_000),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun sessions() = requests.filter { it.first == "POST /api/session/local" }.map { JSONObject(it.second) }

    @Test
    fun `listening is reported as one local session with running totals`() = runTest {
        reporter.record(bookId, 60_000, positionMs = 600_000, durationMs = 36_000_000, force = true, now = 1_000_000)
        reporter.record(bookId, 30_000, positionMs = 640_000, durationMs = 36_000_000, force = true, now = 1_100_000)

        val sent = sessions()
        assertEquals(2, sent.size)
        assertEquals(sent[0].getString("id"), sent[1].getString("id"))
        with(sent[0]) {
            assertEquals("user-1", getString("userId"))
            assertEquals("item-1", getString("libraryItemId"))
            assertEquals("book", getString("mediaType"))
            assertEquals(60.0, getDouble("timeListening"), 0.001)
            assertEquals(540.0, getDouble("startTime"), 0.001)
            assertEquals(600.0, getDouble("currentTime"), 0.001)
            assertEquals(36_000.0, getDouble("duration"), 0.001)
            assertEquals(3, getInt("playMethod"))
        }
        assertEquals(90.0, sent[1].getDouble("timeListening"), 0.001)
        assertEquals(640.0, sent[1].getDouble("currentTime"), 0.001)
        assertTrue(db.absSessionDao().dirty().isEmpty())
    }

    @Test
    fun `after a long pause a new session begins`() = runTest {
        reporter.record(bookId, 60_000, 100_000, 36_000_000, force = true, now = 1_000_000)
        reporter.record(bookId, 60_000, 200_000, 36_000_000, force = true, now = 1_000_000 + 31 * 60_000)

        val ids = sessions().map { it.getString("id") }
        assertEquals(2, ids.distinct().size)
    }

    @Test
    fun `unthrottled records within thirty seconds are sent only once`() = runTest {
        reporter.record(bookId, 15_000, 100_000, 36_000_000, force = false, now = System.currentTimeMillis())
        reporter.record(bookId, 15_000, 115_000, 36_000_000, force = false, now = System.currentTimeMillis())

        assertEquals(1, sessions().size)
        // The second state is stored and waits for the next report.
        assertEquals(30_000, db.absSessionDao().dirty().single().timeListeningMs)
    }

    @Test
    fun `a failed report stays pending and is delivered later`() = runTest {
        failSessions = true
        reporter.record(bookId, 60_000, 100_000, 36_000_000, force = true, now = 1_000_000)
        assertEquals(1, db.absSessionDao().dirty().size)

        failSessions = false
        assertTrue(reporter.pushPending())

        assertTrue(db.absSessionDao().dirty().isEmpty())
        assertEquals(2, sessions().size)
        assertEquals(sessions()[0].getString("id"), sessions()[1].getString("id"))
    }

    @Test
    fun `episodes and books from the device are not reported`() = runTest {
        val episode = db.bookDao().upsertBook(
            BookEntity(serverId = "feed", kind = BookKind.EPISODE, remoteId = "g", title = "Episode", normTitle = "episode", author = "P", durationMs = 1),
        )
        val local = db.bookDao().upsertBook(
            BookEntity(serverId = "local", kind = BookKind.BOOK, remoteId = "/x.m4b", title = "Local", normTitle = "local", author = "A", durationMs = 1),
        )

        reporter.record(episode, 60_000, 0, 1, force = true)
        reporter.record(local, 60_000, 0, 1, force = true)

        assertFalse(requests.any { it.first.contains("session") })
        assertTrue(db.absSessionDao().dirty().isEmpty())
    }
}
