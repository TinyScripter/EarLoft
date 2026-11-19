package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsBooks
import de.kleinabersmart.earloft.core.data.podcast.testFeedCredentials
import android.content.Context
import de.kleinabersmart.earloft.core.datasource.local.AudioFileChapters
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.wear.CredentialsStamps
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.datasource.local.LocalBookScanner
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.LOCAL_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Loading for playback versus paused restore when the service starts (review K1). */
@RunWith(RobolectricTestRunner::class)
class BookPlaybackSourceTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var source: BookPlaybackSource
    private val policy = PlaybackPolicy()
    private var bookId = 0L
    private val pausedSince = System.currentTimeMillis() - HOUR_MS

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (e: IllegalStateException) {
            // already initialized
        }
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        // A book from the device: no server, so no network in the test.
        val client = OkHttpClient()
        val api = AbsApi(client)
        val secrets = object : SecretStore {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val dao = db.bookDao()
        val servers = testAudiobookshelfServers(dao, secrets, api)
        val progressSync = BookProgressSync(context, dao, servers, api, policy)
        val settings = SpokenSettings(context)
        val scanner = LocalBookScanner(context)
        val covers = BookCovers(context, dao, db.podcastDao(), servers, api, PodcastFeedApi(client), scanner)
        val syncer = BookSyncer(dao, servers, api, progressSync, Bookmarks(context, dao, servers, api, PlaybackPolicy()), LocalBooks(dao, scanner, settings, covers),
            ArdBooks(dao, ArdSoundsBooks(ArdSoundsApi(OkHttpClient())), settings, covers, { _, _ -> }),
            ArdUploadAdoption(context, dao, progressSync, Bookmarks(context, dao, servers, api, PlaybackPolicy())))
        source = BookPlaybackSource(
            BookRepository(dao, syncer),
            servers,
            api,
            progressSync,
            PodcastRepository(db, db.podcastDao(), dao, PodcastFeedApi(client), ArdSoundsApi(client), PodcastSettings(ApplicationProvider.getApplicationContext()), AudioFileChapters(ApplicationProvider.getApplicationContext()), { error("no source switch in tests") }, testFeedCredentials(db), EpisodeQueue(ApplicationProvider.getApplicationContext(), db.bookDao())),
            EpisodeQueue(context, dao),
            settings,
            policy,
            SpokenEffectsRepository(dao, db.podcastDao(), settings),
        )
        bookId = dao.upsertBook(
            BookEntity(
                serverId = LOCAL_BOOK_SERVER_ID,
                kind = BookKind.BOOK,
                remoteId = "book",
                title = "Book",
                normTitle = "book",
                author = "Author",
                durationMs = 3_600_000,
                detailsLoaded = true,
            ),
        )
        dao.insertTracks(listOf(BookTrackEntity(bookId, trackIndex = 0, remoteFileId = "/sdcard/Audiobooks/book.m4b", startMs = 0, durationMs = 3_600_000)))
        dao.upsertProgress(BookProgressEntity(bookId, positionMs = 600_000, durationMs = 3_600_000, updatedAt = pausedSince))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `restoring does not rewind and writes nothing`() = runTest {
        val restored = source.restore(bookId)!!

        assertEquals(600_000L, restored.startPositionMs)
        assertEquals(pausedSince, restored.pausedSinceMs)
        val stored = db.bookDao().progress(bookId)!!
        assertEquals(600_000L, stored.positionMs)
        assertEquals(pausedSince, stored.updatedAt)
    }

    @Test
    fun `repeated restoring leaves the position alone`() = runTest {
        repeat(3) { source.restore(bookId) }

        assertEquals(600_000L, source.restore(bookId)!!.startPositionMs)
        assertEquals(600_000L, db.bookDao().progress(bookId)!!.positionMs)
    }

    @Test
    fun `playing applies the rule and records the start`() = runTest {
        val prepared = source.prepare(bookId)!!

        // Default: after more than 5 min pause, 10 s back.
        assertEquals(590_000L, prepared.startPositionMs)
        assertNull(prepared.pausedSinceMs)
        val stored = db.bookDao().progress(bookId)!!
        assertEquals(590_000L, stored.positionMs)
        assertEquals(true, stored.updatedAt > pausedSince)
    }

    @Test
    fun `a finished book starts from the beginning when restored`() = runTest {
        db.bookDao().upsertProgress(BookProgressEntity(bookId, positionMs = 3_600_000, durationMs = 3_600_000, finished = true, updatedAt = pausedSince))

        val restored = source.restore(bookId)!!

        assertEquals(0L, restored.startPositionMs)
        assertNull(restored.pausedSinceMs)
    }

    @Test
    fun `without streaming only with a file on the device`() = runTest {
        policy.allowStreaming = false
        assertNull(source.prepare(bookId))

        val file = File.createTempFile("book", ".m4b").apply { deleteOnExit() }
        db.bookDao().queueBookDownload(bookId)
        db.bookDao().markBookTrackRunning(bookId, 0)
        db.bookDao().markBookTrackDone(bookId, 0, file.absolutePath, file.length())

        assertEquals(listOf(Uri.fromFile(file).toString()), source.prepare(bookId)!!.trackUrls)
    }

    @Test
    fun `without streaming a file that is no longer available does not count`() = runTest {
        policy.allowStreaming = false
        val dao = db.bookDao()
        val file = File.createTempFile("book", ".m4b").apply { deleteOnExit() }
        dao.queueBookDownload(bookId)
        dao.markBookTrackRunning(bookId, 0)
        dao.markBookTrackDone(bookId, 0, file.absolutePath, file.length())
        dao.insertTracks(listOf(BookTrackEntity(bookId, trackIndex = 1, remoteFileId = "https://gone.example/2.mp3", startMs = 3_600_000, durationMs = 60_000)))
        assertNull(source.prepare(bookId))

        dao.setTrackUnavailable(bookId, 1, since = 5)

        assertEquals(2, source.prepare(bookId)!!.trackUrls.size)
    }

    @Test
    fun `an episode skips the intro of its subscription only when starting at 0`() = runTest {
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "feed", url = "https://example.org/feed", title = "Podcast", skipIntroS = 45))
        val episode = db.bookDao().upsertBook(
            BookEntity(serverId = "feed", kind = BookKind.EPISODE, remoteId = "guid", title = "Episode", normTitle = "episode", author = "", durationMs = HOUR_MS),
        )
        db.podcastDao().setTrack(BookTrackEntity(episode, trackIndex = 0, remoteFileId = "https://example.org/episode.mp3", startMs = 0, durationMs = HOUR_MS))

        assertEquals(45_000L, source.prepare(episode)!!.startPositionMs)

        db.bookDao().upsertProgress(BookProgressEntity(episode, positionMs = 5_000, durationMs = HOUR_MS, updatedAt = System.currentTimeMillis()))
        assertEquals(5_000L, source.prepare(episode)!!.startPositionMs)
    }

    @Test
    fun `an episode without a duration in the feed still skips the intro`() = runTest {
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "feed", url = "https://example.org/feed", title = "Podcast", skipIntroS = 45, skipOutroS = 20))
        val episode = db.bookDao().upsertBook(
            BookEntity(serverId = "feed", kind = BookKind.EPISODE, remoteId = "guid", title = "Episode", normTitle = "episode", author = "", durationMs = 0),
        )
        db.podcastDao().setTrack(BookTrackEntity(episode, trackIndex = 0, remoteFileId = "https://example.org/episode.mp3", startMs = 0, durationMs = 0))

        assertEquals(45_000L, source.prepare(episode)!!.startPositionMs)
    }

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
    }
}
