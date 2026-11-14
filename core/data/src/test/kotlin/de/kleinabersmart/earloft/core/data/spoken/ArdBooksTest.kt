package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.datasource.local.LocalBookScanner
import de.kleinabersmart.earloft.core.model.ARD_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsBooks
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArdBooksTest {

    private lateinit var context: Context
    private lateinit var db: EarLoftDatabase
    private lateinit var dao: BookDao
    private lateinit var server: MockWebServer
    private lateinit var settings: SpokenSettings
    private lateinit var ardBooks: ArdBooks

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        dao = db.bookDao()
        server = MockWebServer().apply { start() }
        val client = OkHttpClient()
        val secrets = object : SecretStore {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val api = AbsApi(client)
        val covers = BookCovers(context, dao, db.podcastDao(), testAudiobookshelfServers(dao, secrets, api), api, PodcastFeedApi(client), LocalBookScanner(context))
        settings = SpokenSettings(context)
        settings.setArdSoundsBooks(true)
        ardBooks = ArdBooks(dao, ArdSoundsBooks(ArdSoundsApi(client, server.url("/graphql"))), settings, covers)
    }

    @After
    fun tearDown() = runTest {
        settings.setArdSoundsBooks(false)
        settings.unsubscribeArd(SHOW)
        db.close()
        server.close()
    }

    @Test
    fun `add stores parts as tracks and chapters`() = runTest {
        server.enqueue(answer(part(1), part(2)))

        val bookId = ardBooks.add(KEY)!!

        val book = dao.book(bookId)!!
        assertEquals(ARD_BOOK_SERVER_ID, book.serverId)
        assertEquals("Goldstrand", book.title)
        assertEquals("Katerina Poladjan", book.author)
        assertEquals("Hörbuch", book.genres)
        assertEquals(2_000_000L, book.durationMs)
        assertEquals(listOf(0L, 1_000_000L), dao.tracks(bookId).map { it.startMs })
        assertEquals(listOf("https://a.example/1.mp3", "https://a.example/2.mp3"), dao.tracks(bookId).map { it.remoteFileId })
        assertEquals(listOf("Teil 1", "Teil 2"), dao.chapters(bookId).map { it.title })
        // Schon in der Bibliothek: kein zweiter Abruf.
        assertEquals(bookId, ardBooks.add(KEY))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `refresh appends new parts and keeps the download of unchanged ones`() = runTest {
        server.enqueue(answer(part(1)))
        val bookId = ardBooks.add(KEY)!!
        dao.queueBookDownload(bookId)
        dao.markBookTrackRunning(bookId, 0)
        dao.markBookTrackDone(bookId, 0, "/files/1.mp3", 42)

        server.enqueue(answer(part(1), part(2)))
        assertTrue(ardBooks.refresh().ok)

        val tracks = dao.tracks(bookId)
        assertEquals(2, tracks.size)
        assertEquals(TrackDownload.DONE, tracks[0].downloadState)
        assertEquals(2_000_000L, dao.book(bookId)!!.durationMs)
    }

    @Test
    fun `a downloaded part that disappeared stays with all files`() = runTest {
        server.enqueue(answer(part(1), part(2)))
        val bookId = ardBooks.add(KEY)!!
        dao.queueBookDownload(bookId)
        dao.markBookTrackRunning(bookId, 0)
        dao.markBookTrackDone(bookId, 0, "/files/1.mp3", 42)

        // ARD Sounds führt Teil 1 nicht mehr.
        server.enqueue(answer(part(2)))
        ardBooks.refresh()

        assertEquals(listOf("https://a.example/1.mp3", "https://a.example/2.mp3"), dao.tracks(bookId).map { it.remoteFileId })
        assertEquals(2, dao.chapters(bookId).size)
    }

    @Test
    fun `a book without published parts stays and counts as expired`() = runTest {
        server.enqueue(answer(part(1)))
        val bookId = ardBooks.add(KEY)!!

        server.enqueue(answer())
        ardBooks.refresh()

        val book = dao.book(bookId)!!
        assertTrue(book.availableUntil!! <= System.currentTimeMillis())
        assertEquals(1, dao.tracks(bookId).size)
    }

    @Test
    fun `remove deletes the book`() = runTest {
        server.enqueue(answer(part(1)))
        val bookId = ardBooks.add(KEY)!!

        ardBooks.remove(bookId)

        assertNull(dao.book(bookId))
    }

    @Test
    fun `a subscription takes only the books that come after it, each once`() = runTest {
        server.enqueue(showAnswer(single("old", "Alt")))
        ardBooks.subscribe(SHOW, "Reihe")
        assertEquals(listOf(ArdSubscription(SHOW, "Reihe")), ardBooks.subscriptions.first())

        // Nichts Neues: Das Buch, das es beim Abonnieren schon gab, kommt nicht in die Bibliothek.
        server.enqueue(showAnswer(single("old", "Alt")))
        assertEquals(emptyList<String>(), ardBooks.refresh().newBooks.map { it.title })
        assertTrue(dao.booksOf(ARD_BOOK_SERVER_ID).isEmpty())

        // Ein neues Buch erscheint.
        server.enqueue(showAnswer(single("new", "Neu"), single("old", "Alt")))
        server.enqueue(itemsAnswer(single("new", "Neu")))
        val result = ardBooks.refresh()
        assertTrue(result.ok)
        assertEquals(listOf("Neu"), result.newBooks.map { it.title })
        val bookId = dao.bookByRemote(ARD_BOOK_SERVER_ID, "item:new")!!.bookId
        assertEquals(bookId, result.newBooks.single().bookId)

        // Wer es aus der Bibliothek nimmt, bekommt es nicht wieder.
        ardBooks.remove(bookId)
        server.enqueue(showAnswer(single("new", "Neu"), single("old", "Alt")))
        assertTrue(ardBooks.refresh().newBooks.isEmpty())
        assertNull(dao.bookByRemote(ARD_BOOK_SERVER_ID, "item:new"))
    }

    @Test
    fun `a new book that cannot be fetched yet is tried again`() = runTest {
        server.enqueue(showAnswer(single("old", "Alt")))
        ardBooks.subscribe(SHOW, "Reihe")

        // Das Buch ist angekündigt, hat aber noch keine Teile: Es bleibt unbekannt und kommt beim nächsten Mal.
        server.enqueue(showAnswer(single("new", "Neu")))
        server.enqueue(itemsAnswer())
        assertTrue(ardBooks.refresh().newBooks.isEmpty())

        server.enqueue(showAnswer(single("new", "Neu")))
        server.enqueue(itemsAnswer(single("new", "Neu")))
        assertEquals(listOf("Neu"), ardBooks.refresh().newBooks.map { it.title })
    }

    @Test
    fun `after unsubscribing nothing new comes`() = runTest {
        server.enqueue(showAnswer(single("old", "Alt")))
        ardBooks.subscribe(SHOW, "Reihe")
        ardBooks.unsubscribe(SHOW)

        assertTrue(ardBooks.subscriptions.first().isEmpty())
        assertTrue(ardBooks.refresh().newBooks.isEmpty())
        // Kein Abruf der Sendung.
        assertEquals(1, server.requestCount)
    }

    private fun showAnswer(vararg items: String) =
        MockResponse.Builder().body("""{"data":{"programSetByCoreId":{"items":{"nodes":[${items.joinToString(",")}]}}}}""").build()

    private fun itemsAnswer(vararg items: String) =
        MockResponse.Builder().body("""{"data":{"items":{"nodes":[${items.joinToString(",")}]}}}""").build()

    /** Ein einzelner Titel der Sendung [SHOW] (ein Buch für sich, ohne Gruppe und Nummer). */
    private fun single(coreId: String, title: String) = """
        {"id":"$coreId","coreId":"$coreId","title":"$title","groupId":null,"duration":1000,"publishDate":"2026-11-01T10:00:00+01:00",
         "audios":[{"url":"https://a.example/$coreId.mp3","downloadUrl":null,"allowDownload":false,"mimeType":"audio/mp3"}],
         "programSet":{"id":"1","coreId":"$SHOW","title":"Reihe","publicationService":{"title":"MDR KULTUR"},
                       "editorialCategoryId":"42914713","editorialCategoriesList":[{"id":"42914713"}]}}
    """.trimIndent()

    private fun answer(vararg parts: String) = MockResponse.Builder().body("""{"data":{"items":{"nodes":[${parts.joinToString(",")}]}}}""").build()

    private fun part(number: Int) = """
        {"id":"$number","coreId":"urn:ard:publication:$number","title":"Goldstrand ($number/2)","groupId":"urn:ard:show:gs",
         "episodeNumber":$number,"maxGroupNumber":2,"duration":1000,"publishDate":"2026-11-0${number}T10:00:00+01:00",
         "group":{"type":"MULTIPART","title":"Katerina Poladjan: Goldstrand","count":2},
         "audios":[{"url":"https://a.example/$number.mp3","downloadUrl":null,"allowDownload":false,"mimeType":"audio/mp3"}],
         "programSet":{"id":"1","coreId":"urn:ard:show:gs","title":"Katerina Poladjan: Goldstrand","publicationService":{"title":"MDR KULTUR"},
                       "editorialCategoryId":"42914713","editorialCategoriesList":[{"id":"42914713"}]}}
    """.trimIndent()

    private companion object {
        const val KEY = "group:urn:ard:show:gs"
        const val SHOW = "urn:ard:show:reihe"
    }
}
