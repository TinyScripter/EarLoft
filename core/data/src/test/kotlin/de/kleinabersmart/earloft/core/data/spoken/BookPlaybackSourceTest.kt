package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
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

/** Laden zum Abspielen gegenüber pausiertem Wiederherstellen beim Start des Service (Review K1). */
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
            // schon initialisiert
        }
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        // Ein Buch vom Gerät: kein Server, also kein Netz im Test.
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
        val syncer = BookSyncer(dao, servers, api, progressSync, LocalBooks(dao, scanner, settings, covers))
        source = BookPlaybackSource(
            BookRepository(dao, syncer),
            servers,
            api,
            progressSync,
            PodcastRepository(db, db.podcastDao(), dao, PodcastFeedApi(client), ArdSoundsApi(client), PodcastSettings(ApplicationProvider.getApplicationContext())),
            EpisodeQueue(context, dao),
            settings,
            policy,
            SpokenEffectsRepository(dao, db.podcastDao(), settings),
        )
        bookId = dao.upsertBook(
            BookEntity(
                serverId = LOCAL_BOOK_SERVER_ID,
                kind = BookKind.BOOK,
                remoteId = "buch",
                title = "Buch",
                normTitle = "buch",
                author = "Autor",
                durationMs = 3_600_000,
                detailsLoaded = true,
            ),
        )
        dao.insertTracks(listOf(BookTrackEntity(bookId, trackIndex = 0, remoteFileId = "/sdcard/Audiobooks/buch.m4b", startMs = 0, durationMs = 3_600_000)))
        dao.upsertProgress(BookProgressEntity(bookId, positionMs = 600_000, durationMs = 3_600_000, updatedAt = pausedSince))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `wiederherstellen spult nicht zurueck und schreibt nichts`() = runTest {
        val restored = source.restore(bookId)!!

        assertEquals(600_000L, restored.startPositionMs)
        assertEquals(pausedSince, restored.pausedSinceMs)
        val stored = db.bookDao().progress(bookId)!!
        assertEquals(600_000L, stored.positionMs)
        assertEquals(pausedSince, stored.updatedAt)
    }

    @Test
    fun `wiederholtes Wiederherstellen laesst die Position stehen`() = runTest {
        repeat(3) { source.restore(bookId) }

        assertEquals(600_000L, source.restore(bookId)!!.startPositionMs)
        assertEquals(600_000L, db.bookDao().progress(bookId)!!.positionMs)
    }

    @Test
    fun `abspielen wendet die Regel an und haelt den Start fest`() = runTest {
        val prepared = source.prepare(bookId)!!

        // Standard: nach mehr als 5 min Pause 10 s zurück.
        assertEquals(590_000L, prepared.startPositionMs)
        assertNull(prepared.pausedSinceMs)
        val stored = db.bookDao().progress(bookId)!!
        assertEquals(590_000L, stored.positionMs)
        assertEquals(true, stored.updatedAt > pausedSince)
    }

    @Test
    fun `ein beendetes Buch beginnt beim Wiederherstellen von vorn`() = runTest {
        db.bookDao().upsertProgress(BookProgressEntity(bookId, positionMs = 3_600_000, durationMs = 3_600_000, finished = true, updatedAt = pausedSince))

        val restored = source.restore(bookId)!!

        assertEquals(0L, restored.startPositionMs)
        assertNull(restored.pausedSinceMs)
    }

    @Test
    fun `ohne Streams nur mit Datei auf dem Geraet`() = runTest {
        policy.allowStreaming = false
        assertNull(source.prepare(bookId))

        val file = File.createTempFile("buch", ".m4b").apply { deleteOnExit() }
        db.bookDao().queueBookDownload(bookId)
        db.bookDao().markBookTrackRunning(bookId, 0)
        db.bookDao().markBookTrackDone(bookId, 0, file.absolutePath, file.length())

        assertEquals(listOf(Uri.fromFile(file).toString()), source.prepare(bookId)!!.trackUrls)
    }

    @Test
    fun `eine Folge ueberspringt das Intro ihres Abos nur beim Start bei 0`() = runTest {
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "feed", url = "https://example.org/feed", title = "Podcast", skipIntroS = 45))
        val episode = db.bookDao().upsertBook(
            BookEntity(serverId = "feed", kind = BookKind.EPISODE, remoteId = "guid", title = "Folge", normTitle = "folge", author = "", durationMs = HOUR_MS),
        )
        db.podcastDao().setTrack(BookTrackEntity(episode, trackIndex = 0, remoteFileId = "https://example.org/folge.mp3", startMs = 0, durationMs = HOUR_MS))

        assertEquals(45_000L, source.prepare(episode)!!.startPositionMs)

        db.bookDao().upsertProgress(BookProgressEntity(episode, positionMs = 5_000, durationMs = HOUR_MS, updatedAt = System.currentTimeMillis()))
        assertEquals(5_000L, source.prepare(episode)!!.startPositionMs)
    }

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
    }
}
