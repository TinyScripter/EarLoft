package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.LOCAL_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Position speichern gegenüber Tempo und Effekten, die gleichzeitig geändert werden (Review F33). */
@RunWith(RobolectricTestRunner::class)
class BookProgressSyncTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var sync: BookProgressSync
    private var bookId = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (e: IllegalStateException) {
            // schon initialisiert
        }
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        val api = AbsApi(OkHttpClient())
        val secrets = object : SecretStore {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val dao = db.bookDao()
        sync = BookProgressSync(context, dao, testAudiobookshelfServers(dao, secrets, api), api, PlaybackPolicy())
        // Ein Buch vom Gerät: kein Server, also kein Netz im Test.
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
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a new progress starts without own speed and effects`() = runBlocking {
        sync.record(bookId, 5_000, 3_600_000, finished = false)

        val stored = db.bookDao().progress(bookId)!!
        assertEquals(5_000L, stored.positionMs)
        assertNull(stored.speed)
        assertNull(stored.skipSilence)
        assertNull(stored.volumeBoostDb)
    }

    @Test
    fun `saving the position keeps the own speed and effects`() = runBlocking {
        sync.record(bookId, 5_000, 3_600_000, finished = false)
        db.bookDao().setEffects(bookId, 1.5f, true, 3)

        sync.record(bookId, 9_000, 3_600_000, finished = false, hidden = false)

        val stored = db.bookDao().progress(bookId)!!
        assertEquals(9_000L, stored.positionMs)
        assertEquals(1.5f, stored.speed)
        assertEquals(true, stored.skipSilence)
        assertEquals(3, stored.volumeBoostDb)
    }

    @Test
    fun `the time a book was finished stays when it is only hidden from continue listening`() = runBlocking {
        sync.record(bookId, 3_600_000, 3_600_000, finished = true)
        val finishedAt = db.bookDao().progress(bookId)!!.finishedAt!!
        db.bookDao().insertTracks(listOf(BookTrackEntity(bookId, trackIndex = 0, remoteFileId = "datei", startMs = 0, durationMs = 3_600_000)))
        db.bookDao().queueBookDownload(bookId)
        db.bookDao().markBookTrackRunning(bookId, 0)
        db.bookDao().markBookTrackDone(bookId, 0, "/tmp/datei", 1)
        Thread.sleep(5)

        sync.hideFromContinueListening(bookId)
        sync.record(bookId, 3_600_000, 3_600_000, finished = true, hidden = true)

        val stored = db.bookDao().progress(bookId)!!
        assertEquals(finishedAt, stored.finishedAt)
        assertEquals(true, stored.updatedAt > finishedAt)
        assertEquals(listOf(finishedAt), db.bookDao().observeFinishedBookDownloads().first().map { it.finishedAt })
    }

    @Test
    fun `hiding a book finished without a recorded time keeps its last change as the time it was finished`() = runBlocking {
        // So kommt ein beendetes Buch aus einer alten Zeile oder einer fremden Quelle: ohne `finishedAt`.
        db.bookDao().upsertProgress(BookProgressEntity(bookId, 3_600_000, 3_600_000, finished = true, updatedAt = 1_000))
        val other = db.bookDao().upsertBook(
            BookEntity(
                serverId = LOCAL_BOOK_SERVER_ID, kind = BookKind.BOOK, remoteId = "zwei", title = "Zwei", normTitle = "zwei",
                author = "Autor", durationMs = 3_600_000, detailsLoaded = true,
            ),
        )
        db.bookDao().upsertProgress(BookProgressEntity(other, 3_600_000, 3_600_000, finished = true, updatedAt = 2_000))

        sync.hideFromContinueListening(bookId)
        sync.hideFromContinueListening(listOf(other))

        assertEquals(1_000L, db.bookDao().progress(bookId)!!.finishedAt)
        assertEquals(2_000L, db.bookDao().progress(other)!!.finishedAt)
        assertEquals(true, db.bookDao().progress(bookId)!!.updatedAt > 1_000)
    }

    @Test
    fun `hiding a book that is not finished sets no time it was finished`() = runBlocking {
        sync.record(bookId, 60_000, 3_600_000, finished = false)

        sync.hideFromContinueListening(bookId)

        assertNull(db.bookDao().progress(bookId)!!.finishedAt)
    }

    @Test
    fun `resuming a finished book clears the time it was finished`() = runBlocking {
        sync.record(bookId, 3_600_000, 3_600_000, finished = true)

        sync.record(bookId, 60_000, 3_600_000, finished = false)

        assertNull(db.bookDao().progress(bookId)!!.finishedAt)
    }

    @Test
    fun `a held position is announced, a periodic one is not`() = runBlocking {
        val held = mutableListOf<Long>()
        val listener = launch(start = CoroutineStart.UNDISPATCHED) { sync.held.collect { held += it } }

        sync.record(bookId, 5_000, 3_600_000, finished = false)
        sync.record(bookId, 7_000, 3_600_000, finished = false, force = true)
        withTimeout(5_000) { while (held.isEmpty()) yield() }
        listener.cancel()

        assertEquals(listOf(bookId), held)
        assertEquals(7_000L, db.bookDao().progress(bookId)!!.positionMs)
    }

    @Test
    fun `a speed change made while the position is saved is not lost`() = runBlocking {
        sync.record(bookId, 1_000, 3_600_000, finished = false)

        repeat(100) { round ->
            val speed = 1f + (round + 1) / 100f
            // Mehrere Speicherungen sind gleichzeitig unterwegs, wenn das Tempo geändert wird.
            val saves = List(8) { n -> async(Dispatchers.IO) { sync.record(bookId, 2_000L + round * 10 + n, 3_600_000, finished = false) } }
            val change = async(Dispatchers.IO) { db.bookDao().setEffects(bookId, speed, null, null) }
            (saves + change).awaitAll()

            assertEquals("Runde $round", speed, db.bookDao().progress(bookId)!!.speed)
        }
    }
}
