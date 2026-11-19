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
import de.kleinabersmart.earloft.core.database.entity.SpokenServerEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsBookmarkDto
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookmarksTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var bookmarks: Bookmarks
    private val requests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private var offline = false
    private var bookId = 0L
    private var episodeId = 0L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (e: IllegalStateException) {
            // already initialized
        }
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                if (offline) throw java.io.IOException("offline")
                requests += "${request.method} ${request.url.encodedPath} $body".trim()
                Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1).code(200).message("x")
                    .body("{}".toResponseBody("application/json".toMediaType()))
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
        bookmarks = Bookmarks(context, db.bookDao(), testAudiobookshelfServers(db.bookDao(), secrets, api), api, PlaybackPolicy())
        bookId = db.bookDao().upsertBook(
            BookEntity(serverId = "srv", kind = BookKind.BOOK, remoteId = "item-1", title = "Book", normTitle = "book", author = "", durationMs = 36_000_000),
        )
        episodeId = db.bookDao().upsertBook(
            BookEntity(serverId = "feed", kind = BookKind.EPISODE, remoteId = "guid", title = "Episode", normTitle = "episode", author = "", durationMs = 3_600_000),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a bookmark in a server book is created there in whole seconds and its title follows`() = runTest {
        val id = bookmarks.add(bookId, 754_321, "Chapter 3 · 12:34")!!

        assertEquals(754_000, bookmarks.of(bookId).first().single().positionMs)
        assertEquals("""POST /api/me/item/item-1/bookmark {"time":754.0,"title":"Chapter 3 · 12:34"}""", requests.single())
        assertTrue(db.bookDao().dirtyBookmarks().isEmpty())

        // The note stays on the device; only the title goes out.
        bookmarks.edit(id, "Chapter 3 · 12:34", "Important")
        assertEquals(1, requests.size)
        bookmarks.edit(id, "The murder", "Important")
        assertEquals("""PATCH /api/me/item/item-1/bookmark {"time":754.0,"title":"The murder"}""", requests.last())

        bookmarks.delete(id)
        assertEquals("DELETE /api/me/item/item-1/bookmark/754", requests.last())
        assertTrue(db.bookDao().bookmarksOf(listOf(bookId)).isEmpty())
    }

    @Test
    fun `offline changes wait and are delivered later`() = runTest {
        offline = true
        val id = bookmarks.add(bookId, 60_000, "One")!!
        val other = bookmarks.add(bookId, 120_000, "Two")!!
        bookmarks.delete(other)
        // Never on the server: deleting needs no report.
        assertEquals(listOf(id), db.bookDao().dirtyBookmarks().map { it.bookmarkId })

        offline = false
        assertTrue(bookmarks.pushPending())
        assertEquals(listOf("""POST /api/me/item/item-1/bookmark {"time":60.0,"title":"One"}"""), requests)
        assertTrue(db.bookDao().dirtyBookmarks().isEmpty())
    }

    @Test
    fun `deleting can be undone before and after the server heard of it`() = runTest {
        val id = bookmarks.add(bookId, 60_000, "One")!!
        val saved = bookmarks.of(bookId).first().single()

        offline = true
        bookmarks.delete(id)
        assertTrue(bookmarks.of(bookId).first().isEmpty())
        bookmarks.restore(saved)
        assertEquals(listOf("One"), bookmarks.of(bookId).first().map { it.title })
        offline = false
        assertTrue(bookmarks.pushPending())
        assertEquals("""PATCH /api/me/item/item-1/bookmark {"time":60.0,"title":"One"}""", requests.last())

        bookmarks.delete(id)
        bookmarks.restore(saved)
        assertEquals("""POST /api/me/item/item-1/bookmark {"time":60.0,"title":"One"}""", requests.last())
        assertEquals(listOf("One"), bookmarks.of(bookId).first().map { it.title })
    }

    @Test
    fun `episode bookmarks stay on the device`() = runTest {
        val id = bookmarks.add(episodeId, 754_321, "Intro")!!
        bookmarks.edit(id, "Beginning", "")
        bookmarks.delete(id)

        assertTrue(requests.isEmpty())
        assertTrue(db.bookDao().bookmarksOf(listOf(episodeId)).isEmpty())
    }

    @Test
    fun `a bookmark set or deleted while the account is fetched is neither lost nor brought back`() = runTest {
        val old = bookmarks.add(bookId, 30_000, "Old")!!
        // The server's state from before the two reports: knows "Old", but not "New" yet.
        val before = listOf(AbsBookmarkDto("item-1", 30.0, "Old", 1_000))
        var added: Deferred<Long?>? = null
        var deleted: Deferred<Unit>? = null

        bookmarks.pull("srv") {
            added = async(Dispatchers.Default) { bookmarks.add(bookId, 60_000, "New", "My note") }
            deleted = async(Dispatchers.Default) { bookmarks.delete(old) }
            // The fetch takes a while; without coordination the reports would get through during that time.
            withContext(Dispatchers.Default) { withTimeoutOrNull(500) { while (requests.size < 3) delay(10) } }
            before
        }
        added!!.await()
        deleted!!.await()

        val left = bookmarks.of(bookId).first()
        assertEquals(listOf("New" to "My note"), left.map { it.title to it.note })
        assertTrue(db.bookDao().dirtyBookmarks().isEmpty())
    }

    @Test
    fun `server bookmarks are taken over, renamed and removed unless changed here`() = runTest {
        bookmarks.applyRemote("srv", listOf(AbsBookmarkDto("item-1", 30.5, "Beginning", 1_000), AbsBookmarkDto("other", 10.0, "Foreign", 1_000)))
        val first = bookmarks.of(bookId).first().single()
        assertEquals(30_500, first.positionMs)
        assertEquals(1_000, first.createdAt)

        bookmarks.applyRemote("srv", listOf(AbsBookmarkDto("item-1", 30.5, "New title", 1_000)))
        assertEquals("New title", bookmarks.of(bookId).first().single().title)

        // Changed here and not yet reported: stays, even if the server no longer knows it.
        offline = true
        bookmarks.edit(first.id, "Mine", "")
        bookmarks.applyRemote("srv", emptyList())
        assertEquals("Mine", bookmarks.of(bookId).first().single().title)

        offline = false
        assertTrue(bookmarks.pushPending())
        bookmarks.applyRemote("srv", emptyList())
        assertTrue(bookmarks.of(bookId).first().isEmpty())
        assertFalse(requests.isEmpty())
    }

    @Test
    fun `the collected list keeps books and episodes apart and leaves out switched off servers`() = runTest {
        bookmarks.add(bookId, 30_000, "In the book")
        bookmarks.add(episodeId, 60_000, "In the episode")

        assertEquals(listOf("Book" to "In the book"), bookmarks.all(BookKind.BOOK).first().map { it.bookTitle to it.bookmark.title })
        assertEquals(listOf("Episode" to "In the episode"), bookmarks.all(BookKind.EPISODE).first().map { it.bookTitle to it.bookmark.title })

        db.bookDao().upsertServer(SpokenServerEntity("srv", "Server", "https://abs.example/", enabled = false))
        assertTrue(bookmarks.all(BookKind.BOOK).first().isEmpty())
    }
}
