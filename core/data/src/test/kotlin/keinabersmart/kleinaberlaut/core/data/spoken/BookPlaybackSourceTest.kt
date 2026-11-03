package keinabersmart.kleinaberlaut.core.data.spoken

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import keinabersmart.kleinaberlaut.core.data.podcast.EpisodeQueue
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.data.security.SecretStore
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.entity.BookEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookProgressEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookTrackEntity
import keinabersmart.kleinaberlaut.core.datasource.local.LocalBookScanner
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.LOCAL_BOOK_SERVER_ID
import keinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsApi
import keinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsServerRegistry
import keinabersmart.kleinaberlaut.core.network.podcast.PodcastFeedApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Laden zum Abspielen gegenüber pausiertem Wiederherstellen beim Start des Service (Review K1). */
@RunWith(RobolectricTestRunner::class)
class BookPlaybackSourceTest {

    private lateinit var db: KleinaberlautDatabase
    private lateinit var source: BookPlaybackSource
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
        db = Room.inMemoryDatabaseBuilder(context, KleinaberlautDatabase::class.java).allowMainThreadQueries().build()
        // Ein Buch vom Gerät: kein Server, also kein Netz im Test.
        val client = OkHttpClient()
        val api = AbsApi(client)
        val secrets = object : SecretStore {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val dao = db.bookDao()
        val servers = AudiobookshelfServers(dao, secrets, api, AbsServerRegistry())
        val progressSync = BookProgressSync(context, dao, servers, api)
        val settings = SpokenSettings(context)
        val scanner = LocalBookScanner(context)
        val covers = BookCovers(context, dao, servers, api, PodcastFeedApi(client), scanner)
        val syncer = BookSyncer(dao, servers, api, progressSync, LocalBooks(dao, scanner, settings, covers))
        source = BookPlaybackSource(
            BookRepository(dao, syncer),
            servers,
            api,
            progressSync,
            PodcastRepository(db.podcastDao(), dao, PodcastFeedApi(client)),
            EpisodeQueue(context, dao),
            settings,
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

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
    }
}
