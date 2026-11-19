package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.refresh.NewShow
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.datasource.local.LocalBookScanner
import de.kleinabersmart.earloft.core.model.ARD_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsBooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.OffsetDateTime

@RunWith(RobolectricTestRunner::class)
class ArdBooksTest {

    private lateinit var context: Context
    private lateinit var db: EarLoftDatabase
    private lateinit var dao: BookDao
    private lateinit var server: MockWebServer
    private lateinit var settings: SpokenSettings
    private lateinit var ardBooks: ArdBooks
    private val moves = mutableListOf<Pair<String, Map<String, String>>>()

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
        ardBooks = ArdBooks(dao, ArdSoundsBooks(ArdSoundsApi(client, server.url("/graphql"))), settings, covers) { serverId, renames ->
            moves += serverId to renames
        }
    }

    @After
    fun tearDown() = runTest {
        settings.setArdSoundsBooks(false)
        settings.unsubscribeArd(SHOW)
        settings.setArdMissing(KEY, null)
        settings.setArdGoneCheck(KEY, null)
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
        // Already in the library: no second fetch.
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

        // ARD Sounds no longer lists part 1.
        server.enqueue(answer(part(2)))
        ardBooks.refresh()

        assertEquals(listOf("https://a.example/1.mp3", "https://a.example/2.mp3"), dao.tracks(bookId).map { it.remoteFileId })
        assertEquals(2, dao.chapters(bookId).size)
    }

    @Test
    fun `a part that expired unloaded keeps its place in a started book`() = runTest {
        server.enqueue(answer(part(1), part(2), part(3, availableTo = "2099-01-01T00:00:00+01:00")))
        val bookId = ardBooks.add(KEY)!!
        dao.upsertProgress(BookProgressEntity(bookId, positionMs = 1_500_000, durationMs = 3_000_000, updatedAt = 1))

        // ARD Sounds no longer lists part 1; it was never downloaded.
        server.enqueue(answer(part(2), part(3, availableTo = "2099-01-01T00:00:00+01:00")))
        assertTrue(ardBooks.refresh().ok)

        val tracks = dao.tracks(bookId)
        assertEquals(listOf("https://a.example/1.mp3", "https://a.example/2.mp3", "https://a.example/3.mp3"), tracks.map { it.remoteFileId })
        assertEquals(listOf(0L, 1_000_000L, 2_000_000L), tracks.map { it.startMs })
        assertNotNull(tracks[0].unavailableSince)
        assertNull(tracks[1].unavailableSince)
        assertEquals(listOf("Teil 1", "Teil 2", "Teil 3"), dao.chapters(bookId).map { it.title })
        assertEquals(3_000_000L, dao.book(bookId)!!.durationMs)
        // Expiry of the book: the next part that isn't downloaded yet and expires.
        assertEquals(OffsetDateTime.parse("2099-01-01T00:00:00+01:00").toInstant().toEpochMilli(), dao.book(bookId)!!.availableUntil)
        // Downloading skips the disappeared part; without it the book counts as fully downloaded.
        assertEquals(2, dao.queueBookDownload(bookId))
        listOf(1, 2).forEach { index ->
            dao.markBookTrackRunning(bookId, index)
            dao.markBookTrackDone(bookId, index, "/files/$index.mp3", 42)
        }
        val download = dao.observeBookDownloads().first().single { it.bookId == bookId }
        assertEquals(download.total, download.done)
    }

    @Test
    fun `new parts still come after a downloaded part disappeared`() = runTest {
        server.enqueue(answer(part(1), part(2)))
        val bookId = ardBooks.add(KEY)!!
        dao.queueBookDownload(bookId)
        dao.markBookTrackRunning(bookId, 0)
        dao.markBookTrackDone(bookId, 0, "/files/1.mp3", 42)

        server.enqueue(answer(part(2), part(3)))
        ardBooks.refresh()

        val tracks = dao.tracks(bookId)
        assertEquals(listOf("https://a.example/1.mp3", "https://a.example/2.mp3", "https://a.example/3.mp3"), tracks.map { it.remoteFileId })
        assertEquals(TrackDownload.DONE, tracks[0].downloadState)
        assertEquals(3, dao.chapters(bookId).size)
    }

    @Test
    fun `a part that ARD Sounds gives a new address, name and length stays in its place in a started book`() = runTest {
        server.enqueue(answer(part(1), part(2)))
        val bookId = ardBooks.add(KEY)!!
        dao.upsertProgress(BookProgressEntity(bookId, positionMs = 1_500_000, durationMs = 2_000_000, updatedAt = 1))

        val recut = part(1)
            .replace("https://a.example/1.mp3", "https://b.example/1-new.mp3")
            .replace("Goldstrand (1/2)", "Goldstrand (1/2): On the beach")
            .replace("\"duration\":1000", "\"duration\":1300")
        server.enqueue(answer(recut, part(2)))
        assertTrue(ardBooks.refresh().ok)

        val tracks = dao.tracks(bookId)
        assertEquals(listOf("https://b.example/1-new.mp3", "https://a.example/2.mp3"), tracks.map { it.remoteFileId })
        assertEquals(listOf(null, null), tracks.map { it.unavailableSince })
        assertEquals(listOf(0L, 1_000_000L), tracks.map { it.startMs })
        assertEquals(listOf("Teil 1", "Teil 2"), dao.chapters(bookId).map { it.title })
    }

    @Test
    fun `a book nobody started takes the parts as they are`() = runTest {
        server.enqueue(answer(part(1), part(2)))
        val bookId = ardBooks.add(KEY)!!

        server.enqueue(answer(part(2)))
        ardBooks.refresh()

        assertEquals(listOf("https://a.example/2.mp3"), dao.tracks(bookId).map { it.remoteFileId })
        assertTrue(dao.chapters(bookId).isEmpty())
    }

    @Test
    fun `an answer with errors next to the data changes nothing`() = runTest {
        server.enqueue(answer(part(1)))
        val bookId = ardBooks.add(KEY)!!

        server.enqueue(MockResponse.Builder().body("""{"data":{"items":null},"errors":[{"message":"Timeout"}]}""").build())
        assertFalse(ardBooks.refresh().ok)

        assertNull(dao.book(bookId)!!.availableUntil)
        assertNull(dao.tracks(bookId).single().unavailableSince)
    }

    /** The first empty response lies further back than a confirmation needs at least. */
    private suspend fun firstEmptyAnswerLongAgo() = settings.setArdMissing(KEY, System.currentTimeMillis() - 7 * HOUR_MS)

    /** ARD Sounds no longer lists the book: nothing under its key and none of its titles. */
    private fun enqueueGone() {
        server.enqueue(answer())
        server.enqueue(answer())
    }

    @Test
    fun `a book without published parts stays and counts as expired once a later answer confirms it`() = runTest {
        server.enqueue(answer(part(1)))
        val bookId = ardBooks.add(KEY)!!

        // An empty response alone can be a hiccup: only noted.
        enqueueGone()
        assertTrue(ardBooks.refresh().ok)
        assertNull(dao.book(bookId)!!.availableUntil)
        assertNull(dao.tracks(bookId).single().unavailableSince)
        assertTrue(KEY in settings.ardMissing())
        assertTrue(settings.ardGoneChecks().isEmpty())

        firstEmptyAnswerLongAgo()
        enqueueGone()
        ardBooks.refresh()

        val book = dao.book(bookId)!!
        assertTrue(book.availableUntil!! <= System.currentTimeMillis())
        assertEquals(1, dao.tracks(bookId).size)
        assertNotNull(dao.tracks(bookId).single().unavailableSince)
        assertTrue(settings.ardMissing().isEmpty())
    }

    @Test
    fun `two empty answers right after each other do not confirm anything`() = runTest {
        server.enqueue(answer(part(1)))
        val bookId = ardBooks.add(KEY)!!

        enqueueGone()
        ardBooks.refresh()
        enqueueGone()
        ardBooks.refresh()

        assertNull(dao.book(bookId)!!.availableUntil)
        assertTrue(KEY in settings.ardMissing())
    }

    @Test
    fun `a book that is back after an empty answer loses the note`() = runTest {
        server.enqueue(answer(part(1)))
        val bookId = ardBooks.add(KEY)!!
        enqueueGone()
        ardBooks.refresh()

        server.enqueue(answer(part(1), part(2)))
        ardBooks.refresh()

        assertTrue(settings.ardMissing().isEmpty())
        assertEquals(2, dao.tracks(bookId).size)
        // A later empty response starts over.
        enqueueGone()
        ardBooks.refresh()
        assertNull(dao.book(bookId)!!.availableUntil)
    }

    @Test
    fun `a book past its date needs no second answer`() = runTest {
        server.enqueue(answer(part(1, availableTo = "2020-01-01T00:00:00+01:00")))
        val bookId = ardBooks.add(KEY)!!

        enqueueGone()
        ardBooks.refresh()

        assertNotNull(dao.tracks(bookId).single().unavailableSince)
        assertTrue(KEY in settings.ardGoneChecks())
        assertTrue(settings.ardMissing().isEmpty())
    }

    @Test
    fun `an expired book is not asked for again at every refresh`() = runTest {
        server.enqueue(answer(part(1)))
        val bookId = ardBooks.add(KEY)!!
        enqueueGone()
        ardBooks.refresh()
        firstEmptyAnswerLongAgo()
        enqueueGone()
        ardBooks.refresh()
        assertTrue(dao.book(bookId)!!.availableUntil!! <= System.currentTimeMillis())
        val asked = server.requestCount

        assertTrue(ardBooks.refresh().ok)
        assertTrue(ardBooks.refresh().ok)

        assertEquals(asked, server.requestCount)
        // Again after a week: if it shows up, it no longer counts as expired.
        settings.setArdGoneCheck(KEY, System.currentTimeMillis() - 8 * 24 * 60 * 60 * 1000L)
        server.enqueue(answer(part(1), part(2)))
        ardBooks.refresh()
        assertEquals(asked + 1, server.requestCount)
        assertNull(dao.book(bookId)!!.availableUntil)
        assertTrue(settings.ardGoneChecks().isEmpty())
    }

    @Test
    fun `two refreshes at once count a new book once`() = runTest {
        server.enqueue(showAnswer(single("old", "Old")))
        ardBooks.subscribe(SHOW, "Series")
        server.enqueue(showAnswer(single("new", "New")))
        server.enqueue(itemsAnswer(single("new", "New")))
        server.enqueue(showAnswer(single("new", "New")))

        val results = listOf(async(Dispatchers.Default) { ardBooks.refresh() }, async(Dispatchers.Default) { ardBooks.refresh() }).awaitAll()

        assertEquals(1, results.sumOf { it.newBooks.size })
        assertEquals(1, dao.booksOf(ARD_BOOK_SERVER_ID).size)
    }

    @Test
    fun `a single title that ARD Sounds later puts into a group keeps everything under the new key`() = runTest {
        server.enqueue(itemsAnswer(single("solo", "Solo")))
        val bookId = ardBooks.add("item:solo")!!
        dao.upsertProgress(BookProgressEntity(bookId, positionMs = 500_000, durationMs = 1_000_000, updatedAt = 1))

        // The title now belongs to a series as its second part: there's no book under its old key anymore.
        server.enqueue(itemsAnswer(grouped("solo", 2)))
        server.enqueue(itemsAnswer(grouped("solo", 2)))
        server.enqueue(itemsAnswer(grouped("one", 1), grouped("solo", 2)))
        assertTrue(ardBooks.refresh().ok)

        val book = dao.book(bookId)!!
        assertEquals("group:$GROUP", book.remoteId)
        assertNull(book.availableUntil)
        assertEquals(500_000L, dao.progress(bookId)!!.positionMs)
        // Started: the known part stays in front, the new one is added.
        assertEquals(listOf("https://a.example/solo.mp3", "https://a.example/one.mp3"), dao.tracks(bookId).map { it.remoteFileId })
        assertEquals(1, dao.booksOf(ARD_BOOK_SERVER_ID).size)
    }

    @Test
    fun `a group that ARD Sounds gives a new id keeps everything under the new key`() = runTest {
        server.enqueue(answer(part(1), part(2)))
        val bookId = ardBooks.add(KEY)!!
        assertEquals(listOf("urn:ard:publication:1", "urn:ard:publication:2"), dao.tracks(bookId).map { it.remoteItemId })
        dao.upsertProgress(BookProgressEntity(bookId, positionMs = 1_500_000, durationMs = 2_000_000, updatedAt = 1))

        // There's nothing under the old group anymore; the same titles are now in a different one.
        server.enqueue(answer())
        server.enqueue(answer(part(1, group = MOVED), part(2, group = MOVED)))
        server.enqueue(answer(part(1, group = MOVED), part(2, group = MOVED), part(3, group = MOVED)))
        assertTrue(ardBooks.refresh().ok)

        val book = dao.book(bookId)!!
        assertEquals("group:$MOVED", book.remoteId)
        assertNull(book.availableUntil)
        assertEquals(1_500_000L, dao.progress(bookId)!!.positionMs)
        assertEquals(listOf("https://a.example/1.mp3", "https://a.example/2.mp3", "https://a.example/3.mp3"), dao.tracks(bookId).map { it.remoteFileId })
        assertTrue(settings.ardMissing().isEmpty())
        assertEquals(1, dao.booksOf(ARD_BOOK_SERVER_ID).size)
        // Queue, statistics and backup move along.
        assertEquals(listOf(ARD_BOOK_SERVER_ID to mapOf(KEY to "group:$MOVED")), moves)
    }

    @Test
    fun `a book from before the ids of its titles gets them at the next refresh`() = runTest {
        server.enqueue(answer(part(1)))
        val bookId = ardBooks.add(KEY)!!
        dao.replaceDetails(bookId, dao.tracks(bookId).map { it.copy(remoteItemId = null) }, dao.chapters(bookId))

        server.enqueue(answer(part(1)))
        ardBooks.refresh()

        assertEquals(listOf("urn:ard:publication:1"), dao.tracks(bookId).map { it.remoteItemId })
    }

    @Test
    fun `a group without the ids of its titles is not looked for elsewhere`() = runTest {
        server.enqueue(answer(part(1)))
        val bookId = ardBooks.add(KEY)!!
        dao.replaceDetails(bookId, dao.tracks(bookId).map { it.copy(remoteItemId = null) }, dao.chapters(bookId))

        server.enqueue(answer())
        assertTrue(ardBooks.refresh().ok)

        assertEquals(2, server.requestCount)
        assertTrue(KEY in settings.ardMissing())
    }

    @Test
    fun `a restored group under a new id comes back under the saved key and moves at the next refresh`() = runTest {
        val saved = decodeArdParts(
            listOf(1, 2).joinToString("\n") { "https://a.example/$it.mp3\t1000000\taudio/mp3\t\tTeil $it\turn:ard:publication:$it" },
        )
        server.enqueue(answer())
        server.enqueue(answer(part(1, group = MOVED), part(2, group = MOVED)))
        server.enqueue(answer(part(1, group = MOVED), part(2, group = MOVED)))

        val bookId = ardBooks.restore(KEY, addedAt = 7, saved = saved)!!

        // Under the backed-up key, the backup's progress and bookmarks find their book.
        assertEquals(KEY, dao.book(bookId)!!.remoteId)
        assertEquals(7L, dao.book(bookId)!!.addedAt)
        assertEquals(listOf("urn:ard:publication:1", "urn:ard:publication:2"), dao.tracks(bookId).map { it.remoteItemId })
        assertTrue(settings.ardMissing().isEmpty())
        assertTrue(settings.ardGoneChecks().isEmpty())

        server.enqueue(answer())
        server.enqueue(answer(part(1, group = MOVED), part(2, group = MOVED)))
        server.enqueue(answer(part(1, group = MOVED), part(2, group = MOVED)))
        ardBooks.refresh()

        assertEquals("group:$MOVED", dao.book(bookId)!!.remoteId)
        assertEquals(1, dao.booksOf(ARD_BOOK_SERVER_ID).size)
    }

    @Test
    fun `a restored book that is already here under its new key is not added twice and asked for once a week`() = runTest {
        server.enqueue(answer(part(1, group = MOVED)))
        val here = ardBooks.add("group:$MOVED")!!
        val saved = decodeArdParts("https://a.example/1.mp3\t1000000\taudio/mp3\t\tTeil 1\turn:ard:publication:1")
        server.enqueue(answer())
        server.enqueue(answer(part(1, group = MOVED)))
        server.enqueue(answer(part(1, group = MOVED)))

        assertEquals(here, ardBooks.restore(KEY, addedAt = 7, saved = saved))

        assertEquals(1, dao.booksOf(ARD_BOOK_SERVER_ID).size)
        assertTrue(KEY in settings.ardGoneChecks())
        val asked = server.requestCount
        assertNull(ardBooks.restore(KEY, addedAt = 7, saved = saved))
        assertEquals(asked, server.requestCount)
    }

    @Test
    fun `a restored book keeps the parts it had on the old device`() = runTest {
        val saved = decodeArdParts(
            listOf(1, 2, 3).joinToString("\n") { "https://a.example/$it.mp3\t1000000\taudio/mp3\t\tTeil $it" },
        )

        // Part 1 has expired in the meantime.
        server.enqueue(answer(part(2), part(3)))
        val bookId = ardBooks.add(KEY, addedAt = 7, saved = saved)!!

        val tracks = dao.tracks(bookId)
        assertEquals(listOf("https://a.example/1.mp3", "https://a.example/2.mp3", "https://a.example/3.mp3"), tracks.map { it.remoteFileId })
        assertEquals(listOf(0L, 1_000_000L, 2_000_000L), tracks.map { it.startMs })
        assertNotNull(tracks[0].unavailableSince)
        assertEquals(listOf("Teil 1", "Teil 2", "Teil 3"), dao.chapters(bookId).map { it.title })
        assertEquals(7L, dao.book(bookId)!!.addedAt)
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
        server.enqueue(showAnswer(single("old", "Old")))
        ardBooks.subscribe(SHOW, "Series")
        assertEquals(listOf(ArdSubscription(SHOW, "Series")), ardBooks.subscriptions.first())

        // Nothing new: the book that already existed when subscribing doesn't go into the library.
        server.enqueue(showAnswer(single("old", "Old")))
        assertEquals(emptyList<String>(), ardBooks.refresh().newBooks.map { it.title })
        assertTrue(dao.booksOf(ARD_BOOK_SERVER_ID).isEmpty())

        // A new book appears.
        server.enqueue(showAnswer(single("new", "New"), single("old", "Old")))
        server.enqueue(itemsAnswer(single("new", "New")))
        val result = ardBooks.refresh()
        assertTrue(result.ok)
        assertEquals(listOf("New"), result.newBooks.map { it.title })
        val bookId = dao.bookByRemote(ARD_BOOK_SERVER_ID, "item:new")!!.bookId
        assertEquals(bookId, result.newBooks.single().bookId)
        // The show is included so the notification can lead to it.
        assertEquals(NewShow(SHOW, "Series"), result.newBooks.single().ardShow)

        // Whoever removes it from the library doesn't get it back.
        ardBooks.remove(bookId)
        server.enqueue(showAnswer(single("new", "New"), single("old", "Old")))
        assertTrue(ardBooks.refresh().newBooks.isEmpty())
        assertNull(dao.bookByRemote(ARD_BOOK_SERVER_ID, "item:new"))
    }

    @Test
    fun `a new book that cannot be fetched yet is tried again`() = runTest {
        server.enqueue(showAnswer(single("old", "Old")))
        ardBooks.subscribe(SHOW, "Series")

        // The book is announced but has no parts yet: it stays unknown and comes next time.
        server.enqueue(showAnswer(single("new", "New")))
        server.enqueue(itemsAnswer())
        assertTrue(ardBooks.refresh().newBooks.isEmpty())

        server.enqueue(showAnswer(single("new", "New")))
        server.enqueue(itemsAnswer(single("new", "New")))
        assertEquals(listOf("New"), ardBooks.refresh().newBooks.map { it.title })
    }

    @Test
    fun `after unsubscribing nothing new comes`() = runTest {
        server.enqueue(showAnswer(single("old", "Old")))
        ardBooks.subscribe(SHOW, "Series")
        ardBooks.unsubscribe(SHOW)

        assertTrue(ardBooks.subscriptions.first().isEmpty())
        assertTrue(ardBooks.refresh().newBooks.isEmpty())
        // No fetch of the show.
        assertEquals(1, server.requestCount)
    }

    private fun showAnswer(vararg items: String) =
        MockResponse.Builder().body("""{"data":{"programSetByCoreId":{"items":{"nodes":[${items.joinToString(",")}]}}}}""").build()

    private fun itemsAnswer(vararg items: String) =
        MockResponse.Builder().body("""{"data":{"items":{"nodes":[${items.joinToString(",")}]}}}""").build()

    /** A single title of show [SHOW] (a book on its own, without group and number). */
    private fun single(coreId: String, title: String) = """
        {"id":"$coreId","coreId":"$coreId","title":"$title","groupId":null,"duration":1000,"publishDate":"2026-11-01T10:00:00+01:00",
         "audios":[{"url":"https://a.example/$coreId.mp3","downloadUrl":null,"allowDownload":false,"mimeType":"audio/mp3"}],
         "programSet":{"id":"1","coreId":"$SHOW","title":"Series","publicationService":{"title":"MDR KULTUR"},
                       "editorialCategoryId":"42914713","editorialCategoriesList":[{"id":"42914713"}]}}
    """.trimIndent()

    /** Part [number] of series [GROUP] of show [SHOW]. */
    private fun grouped(coreId: String, number: Int) = """
        {"id":"$coreId","coreId":"$coreId","title":"Series ($number/2)","groupId":"$GROUP","episodeNumber":$number,"maxGroupNumber":2,
         "duration":1000,"publishDate":"2026-11-0${number}T10:00:00+01:00","group":{"type":"MULTIPART","title":"Series","count":2},
         "audios":[{"url":"https://a.example/$coreId.mp3","downloadUrl":null,"allowDownload":false,"mimeType":"audio/mp3"}],
         "programSet":{"id":"1","coreId":"$SHOW","title":"Series","publicationService":{"title":"MDR KULTUR"},
                       "editorialCategoryId":"42914713","editorialCategoriesList":[{"id":"42914713"}]}}
    """.trimIndent()

    private fun answer(vararg parts: String) = MockResponse.Builder().body("""{"data":{"items":{"nodes":[${parts.joinToString(",")}]}}}""").build()

    private fun part(number: Int, availableTo: String? = null, group: String = "urn:ard:show:gs") = """
        {"id":"$number","coreId":"urn:ard:publication:$number","title":"Goldstrand ($number/2)","groupId":"$group",
         "coreDocument":${availableTo?.let { """{"availableTo":"$it"}""" } ?: "null"},
         "episodeNumber":$number,"maxGroupNumber":2,"duration":1000,"publishDate":"2026-11-0${number}T10:00:00+01:00",
         "group":{"type":"MULTIPART","title":"Katerina Poladjan: Goldstrand","count":2},
         "audios":[{"url":"https://a.example/$number.mp3","downloadUrl":null,"allowDownload":false,"mimeType":"audio/mp3"}],
         "programSet":{"id":"1","coreId":"urn:ard:show:gs","title":"Katerina Poladjan: Goldstrand","publicationService":{"title":"MDR KULTUR"},
                       "editorialCategoryId":"42914713","editorialCategoriesList":[{"id":"42914713"}]}}
    """.trimIndent()

    private companion object {
        const val KEY = "group:urn:ard:show:gs"
        const val SHOW = "urn:ard:show:series"
        const val GROUP = "urn:ard:show:new"
        const val MOVED = "urn:ard:show:moved"
        const val HOUR_MS = 60 * 60 * 1000L
    }
}
