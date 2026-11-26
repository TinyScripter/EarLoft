package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.data.sync.SweepGuard
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsBooks
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.datasource.local.LocalBookScanner
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.LOCAL_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Where a book belongs after "unplayed", "reset" and "Remove from Continue listening" (books_improvements F2, S5). */
@RunWith(RobolectricTestRunner::class)
class ContinueListeningShelvesTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var dao: BookDao
    private lateinit var books: BookRepository
    private lateinit var progressSync: BookProgressSync

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (e: IllegalStateException) {
            // already initialized
        }
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient()
        val api = AbsApi(client)
        val secrets = object : SecretStore {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        dao = db.bookDao()
        val servers = testAudiobookshelfServers(dao, secrets, api)
        // Books from the device: no server, so no network in the test.
        progressSync = BookProgressSync(context, dao, servers, api, PlaybackPolicy())
        val scanner = LocalBookScanner(context)
        val covers = BookCovers(context, dao, db.podcastDao(), servers, api, PodcastFeedApi(client), scanner)
        val syncer = BookSyncer(dao, servers, api, progressSync, AbsSessionReporter(context, db.absSessionDao(), dao, servers, api, PlaybackPolicy()), covers, Bookmarks(context, dao, servers, api, PlaybackPolicy()), LocalBooks(dao, scanner, SpokenSettings(context), covers, SweepGuard(context)),
            ArdBooks(dao, ArdSoundsBooks(ArdSoundsApi(OkHttpClient())), SpokenSettings(context), covers, { _, _ -> }),
            ArdUploadAdoption(context, dao, progressSync, Bookmarks(context, dao, servers, api, PlaybackPolicy())))
        books = BookRepository(dao, syncer)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun book(title: String) = dao.upsertBook(
        BookEntity(
            serverId = LOCAL_BOOK_SERVER_ID,
            kind = BookKind.BOOK,
            remoteId = title,
            title = title,
            normTitle = title.lowercase(),
            author = "Author",
            durationMs = HOUR_MS,
            detailsLoaded = true,
        ),
    )

    @Test
    fun `search finds authors, narrators and series written with spaces, dots and umlauts`() = runTest {
        dao.upsertBook(
            BookEntity(
                serverId = LOCAL_BOOK_SERVER_ID, kind = BookKind.BOOK, remoteId = "it", title = "Es", normTitle = "es",
                author = "Stephen King", narrator = "Jörg Pohl", series = "Der dunkle Turm #1", durationMs = HOUR_MS, detailsLoaded = true,
            ),
        )

        assertEquals(listOf("Es"), books.search("stephen king").map { it.book.title })
        assertEquals(listOf("Es"), books.search("jorg").map { it.book.title })
        assertEquals(listOf("Stephen King"), books.searchAuthors("Stephen K").map { it.name })
        assertEquals(listOf("Jörg Pohl"), books.searchNarrators("jörg p").map { it.name })
        assertEquals(listOf("Der dunkle Turm"), books.searchSeries("dunkle turm").map { it.name })
        assertEquals(emptyList<String>(), books.search("koontz").map { it.book.title })
    }

    private suspend fun continueTitles() = books.continueListening().first().map { it.book.title }
    private suspend fun recentTitles() = books.recentlyAdded().first().map { it.book.title }

    @Test
    fun `a started book is only in continue listening`() = runTest {
        val id = book("Started")
        progressSync.record(id, 600_000, HOUR_MS, finished = false, force = true)

        assertEquals(listOf("Started"), continueTitles())
        assertEquals(emptyList<String>(), recentTitles())
    }

    @Test
    fun `a reset book goes back to recently added`() = runTest {
        val id = book("Reset")
        progressSync.record(id, 600_000, HOUR_MS, finished = false, force = true)
        progressSync.record(id, 0, HOUR_MS, finished = false, force = true, hidden = true)

        assertEquals(emptyList<String>(), continueTitles())
        assertEquals(listOf("Reset"), recentTitles())
    }

    @Test
    fun `a book finished and marked unheard is again recently added, a finished one nowhere`() = runTest {
        val unheard = book("Unheard")
        val finished = book("Finished")
        progressSync.record(unheard, HOUR_MS, HOUR_MS, finished = true, force = true)
        progressSync.record(unheard, 0, HOUR_MS, finished = false, force = true, hidden = true)
        progressSync.record(finished, HOUR_MS, HOUR_MS, finished = true, force = true)

        assertEquals(emptyList<String>(), continueTitles())
        assertEquals(listOf("Unheard"), recentTitles())
    }

    @Test
    fun `a hidden book keeps its position and comes back when it plays again`() = runTest {
        val id = book("Paused")
        progressSync.record(id, 600_000, HOUR_MS, finished = false, force = true)

        progressSync.hideFromContinueListening(id)
        assertEquals(emptyList<String>(), continueTitles())
        // With a state it isn't "new".
        assertEquals(emptyList<String>(), recentTitles())
        assertEquals(600_000L, dao.progress(id)?.positionMs)

        books.touchProgress(id, 600_000, HOUR_MS)
        assertEquals(listOf("Paused"), continueTitles())
    }

    @Test
    fun `playing writes the progress without the hidden mark`() = runTest {
        val id = book("Resumed")
        progressSync.record(id, 600_000, HOUR_MS, finished = false, force = true)
        progressSync.hideFromContinueListening(id)

        progressSync.record(id, 615_000, HOUR_MS, finished = false)
        assertEquals(listOf("Resumed"), continueTitles())
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
