package keinabersmart.kleinaberlaut.core.data.stats

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.data.podcast.SubscribeResult
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.dao.DayListeningRow
import keinabersmart.kleinaberlaut.core.database.entity.BookProgressEntity
import keinabersmart.kleinaberlaut.core.database.entity.ListeningStatEntity
import keinabersmart.kleinaberlaut.core.model.BookKind
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class StatsRepositoryTest {

    private lateinit var db: KleinaberlautDatabase
    private lateinit var stats: StatsRepository
    private lateinit var feedId: String

    private val today = LocalDate.of(2026, 11, 2)
    private val noon = Instant.parse("2026-11-02T12:00:00Z").toEpochMilli()

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KleinaberlautDatabase::class.java)
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
        feedId = (PodcastRepository(db.podcastDao(), db.bookDao(), PodcastFeedApi(client)).subscribe(FEED_URL) as SubscribeResult.Added).feedId
        stats = StatsRepository(db.statsDao(), db.bookDao(), db.podcastDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun bookId(guid: String) = db.bookDao().booksOf(feedId).single { it.remoteId == guid }.bookId

    @Test
    fun `listening adds up per episode and per day`() = runTest {
        val a = bookId("guid-A")

        stats.recordEpisodeListening(a, 60_000, now = noon, zone = ZoneOffset.UTC)
        stats.recordEpisodeListening(a, 30_000, now = noon + 1_000, zone = ZoneOffset.UTC)
        stats.recordEpisodeListening(bookId("guid-B"), 10_000, now = noon, zone = ZoneOffset.UTC)

        val rows = db.statsDao().all()
        assertEquals(setOf("guid-A" to 90_000L, "guid-B" to 10_000L), rows.map { it.itemKey to it.listenedMs }.toSet())
        assertEquals(setOf(ListeningStatEntity.SOURCE_APP), rows.map { it.source }.toSet())
        val days = db.statsDao().allDays()
        assertEquals(1, days.size)
        assertEquals(today.toEpochDay(), days.single().day)
        assertEquals(100_000, days.single().listenedMs)
    }

    @Test
    fun `nothing is counted for zero time or for unknown episodes`() = runTest {
        stats.recordEpisodeListening(bookId("guid-A"), 0)
        stats.recordEpisodeListening(9_999, 5_000)

        assertTrue(db.statsDao().all().isEmpty())
        assertTrue(db.statsDao().allDays().isEmpty())
    }

    @Test
    fun `statistics combine counted and imported time per podcast`() = runTest {
        stats.recordEpisodeListening(bookId("guid-A"), 3_600_000, now = noon, zone = ZoneOffset.UTC)
        db.statsDao().upsertAll(
            listOf(
                ListeningStatEntity(source = ListeningStatEntity.SOURCE_IMPORTED, kind = BookKind.EPISODE, groupKey = FEED_URL, groupTitle = "Test", itemKey = "guid-A", itemTitle = "A", listenedMs = 3_600_000, lastListenedAt = 1),
                ListeningStatEntity(source = ListeningStatEntity.SOURCE_IMPORTED, kind = BookKind.EPISODE, groupKey = OLD_URL, groupTitle = "Altes Abo", itemKey = "x", itemTitle = "X", listenedMs = 10_800_000, lastListenedAt = 1),
            ),
        )
        db.bookDao().upsertProgress(BookProgressEntity(bookId("guid-B"), 600_000, 600_000, finished = true, updatedAt = 1))

        val result = stats.podcastStats { today }.first()

        assertEquals(5L * 3_600_000, result.totalMs)
        assertEquals(1, result.finishedEpisodes)
        assertEquals(1, result.subscriptions)
        assertEquals(listOf("Altes Abo", "Test-Podcast"), result.podcasts.map { it.title })
        assertNull(result.podcasts[0].feedId)
        assertEquals(feedId, result.podcasts[1].feedId)
        // „guid-A“ zählt einmal, auch wenn gezählte und importierte Zeile existieren.
        assertEquals(1, result.podcasts[1].episodes)
        assertTrue(result.hasHistory)
        assertEquals(3_600_000, result.months.last().listenedMs)
        assertEquals(12, result.months.size)
    }

    @Test
    fun `reset deletes counted and imported statistics but keeps progress`() = runTest {
        val a = bookId("guid-A")
        stats.recordEpisodeListening(a, 60_000, now = noon, zone = ZoneOffset.UTC)
        db.bookDao().upsertProgress(BookProgressEntity(a, 1_000, 600_000, finished = false, updatedAt = 1))

        stats.resetPodcastStats()

        assertTrue(db.statsDao().all().isEmpty())
        assertTrue(db.statsDao().allDays().isEmpty())
        assertFalse(stats.podcastStats { today }.first().hasHistory)
        assertEquals(1_000, db.bookDao().progress(a)!!.positionMs)
    }

    @Test
    fun `months are the last twelve in order and fill gaps with zero`() {
        val days = listOf(
            DayListeningRow(LocalDate.of(2026, 11, 1).toEpochDay(), 1_000),
            DayListeningRow(LocalDate.of(2026, 11, 2).toEpochDay(), 2_000),
            DayListeningRow(LocalDate.of(2026, 1, 15).toEpochDay(), 500),
        )

        val months = StatsRepository.monthsOf(days, today)

        assertEquals(12, months.size)
        assertEquals(2025 to 12, months.first().year to months.first().month)
        assertEquals(2026 to 11, months.last().year to months.last().month)
        assertEquals(3_000, months.last().listenedMs)
        assertEquals(500, months.single { it.month == 1 }.listenedMs)
        assertEquals(0, months.single { it.month == 6 }.listenedMs)
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
