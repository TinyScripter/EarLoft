package de.kleinabersmart.kleinaberlaut.core.data.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.kleinaberlaut.core.data.security.SecretStore
import de.kleinabersmart.kleinaberlaut.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import de.kleinabersmart.kleinaberlaut.core.database.entity.BookEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.SpokenServerEntity
import de.kleinabersmart.kleinaberlaut.core.model.BookKind
import de.kleinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsServerRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ServerStatsRepositoryTest {

    private lateinit var db: KleinaberlautDatabase
    private lateinit var repository: ServerStatsRepository
    private var calls = 0
    private var response: String? = RESPONSE
    private val today = LocalDate.of(2026, 11, 2)

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KleinaberlautDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls++
                val body = response ?: throw IOException("offline")
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
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
        db.bookDao().upsertBook(
            BookEntity(serverId = "srv", kind = BookKind.BOOK, remoteId = "item-1", title = "Buch Eins", normTitle = "buch eins", author = "A", durationMs = 1),
        )
        repository = ServerStatsRepository(db.statsDao(), db.bookDao(), AudiobookshelfServers(db.bookDao(), secrets, api, AbsServerRegistry()), api)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `the servers statistics are cached and prepared`() = runTest {
        assertNull(repository.stats("srv") { today }.first())

        assertTrue(repository.refresh("srv", now = 1_000))
        val stats = repository.stats("srv") { today }.first()!!

        assertEquals(36_000_000L, stats.totalMs)
        assertEquals(1_800_000L, stats.todayMs)
        assertEquals(3, stats.streakDays)
        assertEquals(listOf("Buch Eins", "Fremdes Buch"), stats.books.map { it.title })
        assertEquals(listOf(true, false), stats.books.map { it.bookId != null })
        assertEquals(12, stats.months.size)
        assertEquals(1_800_000L + 3_600_000L, stats.months.last().listenedMs)
        assertEquals(7_200_000L + 100_000L, stats.months[10].listenedMs)
        assertEquals(7, stats.weekdays.size)
        assertEquals(10_800_000L, stats.weekdays.single { it.dayOfWeek == 1 }.listenedMs)
        assertEquals(1_000L, stats.fetchedAt)
    }

    @Test
    fun `a fresh cache is not fetched again but a stale one is`() = runTest {
        repository.refresh("srv", now = 0)
        val first = calls

        repository.refresh("srv", now = 2 * 60_000)
        assertEquals(first, calls)

        repository.refresh("srv", now = 10 * 60_000)
        assertTrue(calls > first)
    }

    @Test
    fun `offline keeps the last statistics`() = runTest {
        repository.refresh("srv", now = 0)
        response = null

        assertFalse(repository.refresh("srv", now = 5 * 60 * 60_000))

        assertEquals(36_000_000L, repository.stats("srv") { today }.first()!!.totalMs)
    }

    @Test
    fun `streak counts back from yesterday when today has nothing yet`() {
        val d = LocalDate.of(2026, 11, 2).toEpochDay()

        assertEquals(2, ServerStatsRepository.streak(setOf(d - 1, d - 2, d - 4), LocalDate.of(2026, 11, 2)))
        assertEquals(3, ServerStatsRepository.streak(setOf(d, d - 1, d - 2), LocalDate.of(2026, 11, 2)))
        assertEquals(0, ServerStatsRepository.streak(setOf(d - 3), LocalDate.of(2026, 11, 2)))
    }

    private companion object {
        val RESPONSE = """
            {"totalTime":36000,"today":1800,
             "items":{
               "item-1":{"id":"item-1","timeListening":20000,"mediaMetadata":{"title":"Buch Eins","authorName":"A"},"lastUpdate":1},
               "item-9":{"id":"item-9","timeListening":5000,"mediaMetadata":{"title":"Fremdes Buch"},"lastUpdate":2},
               "item-0":{"id":"item-0","timeListening":0,"mediaMetadata":{"title":"Nie"},"lastUpdate":3}},
             "days":{"2026-11-02":1800,"2026-11-01":3600,"2026-10-31":7200,"2026-10-01":100},
             "dayOfWeek":{"Monday":10800,"Tuesday":0,"Sunday":3600},
             "recentSessions":[]}
        """.trimIndent()
    }
}
