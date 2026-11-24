package de.kleinabersmart.earloft.wear.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.spoken.AbsTokenRenewal
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
import de.kleinabersmart.earloft.core.data.wear.CredentialsStamps
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningStatEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeListeningDto
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeReportDto
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the watch reports to the phone about episodes and books: only newer progress and only the time listened since the
 * last report, again after a report that didn't go out. Real database and report bookkeeping; the DataItems are a fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WatchEpisodeReporterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: EarLoftDatabase
    private lateinit var reporter: WatchEpisodeReporter
    private lateinit var reported: ReportedEpisodes
    private val phoneData = FakePhoneData()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient()
        val api = AbsApi(client)
        val secrets = object : SecretStore {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val registry = AbsServerRegistry()
        val authApi = AbsAuthApi(client)
        val servers = AudiobookshelfServers(db.bookDao(), secrets, api, authApi, AbsTokenRenewal(secrets, registry, authApi), registry, CredentialsStamps(secrets))
        reported = ReportedEpisodes(context)
        reporter = WatchEpisodeReporter(
            phoneData, db.bookDao(), db.podcastDao(), db.statsDao(), reported,
            BookProgressSync(context, db.bookDao(), servers, api, PlaybackPolicy()),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun episode(guid: String): Long {
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = FEED_ID, url = FEED_URL, title = "Podcast"))
        return db.bookDao().upsertBook(BookEntity(serverId = FEED_ID, kind = BookKind.EPISODE, remoteId = guid, title = guid, normTitle = guid, author = "", durationMs = 600_000))
    }

    private suspend fun listen(bookId: Long, positionMs: Long, at: Long) =
        db.bookDao().upsertProgress(BookProgressEntity(bookId, positionMs = positionMs, durationMs = 600_000, updatedAt = at))

    /** The statistics as the player books them (episodes under the feed address, books under their server). */
    private suspend fun listened(kind: BookKind, groupKey: String, itemKey: String, ms: Long) {
        val existing = db.statsDao().listenedSince(ListeningStatEntity.SOURCE_APP, 0).firstOrNull { it.groupKey == groupKey && it.itemKey == itemKey }
        db.statsDao().upsertAll(
            listOf(
                (existing ?: ListeningStatEntity(source = ListeningStatEntity.SOURCE_APP, kind = kind, groupKey = groupKey, groupTitle = "", itemKey = itemKey, itemTitle = "", listenedMs = 0, lastListenedAt = 0))
                    .copy(listenedMs = ms, lastListenedAt = System.currentTimeMillis()),
            ),
        )
    }

    @Test
    fun `newer progress goes out once`() = runTest {
        val bookId = episode("e1")
        listen(bookId, 60_000, at = 1_000)

        reporter.report()
        reporter.report()

        val packet = phoneData.packets.single()
        assertEquals(FEED_ID, packet.progress.single().feedId)
        assertEquals("e1", packet.progress.single().guid)
        assertEquals(60_000L, packet.progress.single().progress.positionMs)

        listen(bookId, 90_000, at = 2_000)
        reporter.report()
        assertEquals(90_000L, phoneData.packets.last().progress.single().progress.positionMs)
    }

    @Test
    fun `a report that did not go out is sent again`() = runTest {
        listen(episode("e1"), 60_000, at = 1_000)
        phoneData.accepts = false
        reporter.report()

        phoneData.accepts = true
        reporter.report()

        assertEquals(2, phoneData.attempts)
        assertEquals(60_000L, phoneData.packets.single().progress.single().progress.positionMs)
    }

    @Test
    fun `only the time listened since the last report goes out, episodes under their feed, books under their server`() = runTest {
        episode("e1")
        listened(BookKind.EPISODE, FEED_URL, "e1", ms = 120_000)
        listened(BookKind.BOOK, "abs-1", "li_1", ms = 30_000)
        reporter.report()
        assertEquals(
            setOf(FEED_ID to 120_000L, "abs-1" to 30_000L),
            phoneData.packets.single().listening.mapTo(HashSet()) { it.feedId to it.listenedMs },
        )

        listened(BookKind.EPISODE, FEED_URL, "e1", ms = 150_000)
        reporter.report()
        assertEquals(listOf(FEED_ID to 30_000L), phoneData.packets.last().listening.map(WearEpisodeListeningDto::pair))
    }

    @Test
    fun `cleared statistics on the watch count anew instead of going negative`() = runTest {
        episode("e1")
        listened(BookKind.EPISODE, FEED_URL, "e1", ms = 120_000)
        reporter.report()

        db.statsDao().upsertAll(db.statsDao().listenedSince(ListeningStatEntity.SOURCE_APP, 0).map { it.copy(listenedMs = 10_000) })
        reporter.report()
        assertEquals("nothing to report after the reset", 1, phoneData.packets.size)

        listened(BookKind.EPISODE, FEED_URL, "e1", ms = 25_000)
        reporter.report()
        assertEquals(listOf(FEED_ID to 15_000L), phoneData.packets.last().listening.map(WearEpisodeListeningDto::pair))
    }

    @Test
    fun `a clock that ran ahead at the last report and was corrected does not hide new listening times`() = runTest {
        episode("e1")
        // The last report ran while the watch's clock was three hours ahead; now it shows the right time again.
        reported.markReported(emptyList(), emptyList(), checkedAt = System.currentTimeMillis() + 3 * 60 * 60_000L)
        listened(BookKind.EPISODE, FEED_URL, "e1", ms = 120_000)

        reporter.report()

        assertEquals(listOf(FEED_ID to 120_000L), phoneData.packets.single().listening.map(WearEpisodeListeningDto::pair))
        assertTrue("the time is right again", reported.listeningCheckedAt() <= System.currentTimeMillis())
    }

    @Test
    fun `nothing new sends nothing`() = runTest {
        episode("e1")
        reporter.report()

        assertEquals(0, phoneData.attempts)
        assertTrue(phoneData.packets.isEmpty())
    }

    private class FakePhoneData : PhoneData {
        var accepts = true
        var attempts = 0
        val packets = mutableListOf<WearEpisodeReportDto>()

        override suspend fun payloads(path: String): Map<String, ByteArray> = emptyMap()
        override suspend fun hashes(path: String): Map<String, String> = emptyMap()
        override suspend fun assetPayloads(path: String): Map<String, ByteArray> = emptyMap()

        override suspend fun put(path: String, payload: ByteArray): Boolean {
            attempts++
            if (!accepts) return false
            val packet = WearCodec.decode<WearEpisodeReportDto>(payload)
            assertEquals(WearPaths.episodes(packet.packetId), path)
            packets += packet
            return true
        }
    }

    private companion object {
        const val FEED_ID = "feed-1"
        const val FEED_URL = "https://example.org/feed.xml"
    }
}

private fun WearEpisodeListeningDto.pair() = feedId to listenedMs
