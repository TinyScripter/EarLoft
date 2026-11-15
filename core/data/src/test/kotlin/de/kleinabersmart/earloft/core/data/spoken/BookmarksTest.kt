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
            // schon initialisiert
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
            BookEntity(serverId = "srv", kind = BookKind.BOOK, remoteId = "item-1", title = "Buch", normTitle = "buch", author = "", durationMs = 36_000_000),
        )
        episodeId = db.bookDao().upsertBook(
            BookEntity(serverId = "feed", kind = BookKind.EPISODE, remoteId = "guid", title = "Folge", normTitle = "folge", author = "", durationMs = 3_600_000),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a bookmark in a server book is created there in whole seconds and its title follows`() = runTest {
        val id = bookmarks.add(bookId, 754_321, "Kapitel 3 · 12:34")!!

        assertEquals(754_000, bookmarks.of(bookId).first().single().positionMs)
        assertEquals("""POST /api/me/item/item-1/bookmark {"time":754.0,"title":"Kapitel 3 · 12:34"}""", requests.single())
        assertTrue(db.bookDao().dirtyBookmarks().isEmpty())

        // Die Notiz bleibt auf dem Gerät; nur der Titel geht hinaus.
        bookmarks.edit(id, "Kapitel 3 · 12:34", "Wichtig")
        assertEquals(1, requests.size)
        bookmarks.edit(id, "Der Mord", "Wichtig")
        assertEquals("""PATCH /api/me/item/item-1/bookmark {"time":754.0,"title":"Der Mord"}""", requests.last())

        bookmarks.delete(id)
        assertEquals("DELETE /api/me/item/item-1/bookmark/754", requests.last())
        assertTrue(db.bookDao().bookmarksOf(listOf(bookId)).isEmpty())
    }

    @Test
    fun `offline changes wait and are delivered later`() = runTest {
        offline = true
        val id = bookmarks.add(bookId, 60_000, "Eins")!!
        val other = bookmarks.add(bookId, 120_000, "Zwei")!!
        bookmarks.delete(other)
        // Noch nie beim Server: Löschen braucht keine Meldung.
        assertEquals(listOf(id), db.bookDao().dirtyBookmarks().map { it.bookmarkId })

        offline = false
        assertTrue(bookmarks.pushPending())
        assertEquals(listOf("""POST /api/me/item/item-1/bookmark {"time":60.0,"title":"Eins"}"""), requests)
        assertTrue(db.bookDao().dirtyBookmarks().isEmpty())
    }

    @Test
    fun `deleting can be undone before and after the server heard of it`() = runTest {
        val id = bookmarks.add(bookId, 60_000, "Eins")!!
        val saved = bookmarks.of(bookId).first().single()

        offline = true
        bookmarks.delete(id)
        assertTrue(bookmarks.of(bookId).first().isEmpty())
        bookmarks.restore(saved)
        assertEquals(listOf("Eins"), bookmarks.of(bookId).first().map { it.title })
        offline = false
        assertTrue(bookmarks.pushPending())
        assertEquals("""PATCH /api/me/item/item-1/bookmark {"time":60.0,"title":"Eins"}""", requests.last())

        bookmarks.delete(id)
        bookmarks.restore(saved)
        assertEquals("""POST /api/me/item/item-1/bookmark {"time":60.0,"title":"Eins"}""", requests.last())
        assertEquals(listOf("Eins"), bookmarks.of(bookId).first().map { it.title })
    }

    @Test
    fun `episode bookmarks stay on the device`() = runTest {
        val id = bookmarks.add(episodeId, 754_321, "Intro")!!
        bookmarks.edit(id, "Anfang", "")
        bookmarks.delete(id)

        assertTrue(requests.isEmpty())
        assertTrue(db.bookDao().bookmarksOf(listOf(episodeId)).isEmpty())
    }

    @Test
    fun `a bookmark set or deleted while the account is fetched is neither lost nor brought back`() = runTest {
        val old = bookmarks.add(bookId, 30_000, "Alt")!!
        // Der Stand des Servers von vor den beiden Meldungen: kennt "Alt", aber noch nicht "Neu".
        val before = listOf(AbsBookmarkDto("item-1", 30.0, "Alt", 1_000))
        var added: Deferred<Long?>? = null
        var deleted: Deferred<Unit>? = null

        bookmarks.pull("srv") {
            added = async(Dispatchers.Default) { bookmarks.add(bookId, 60_000, "Neu", "Meine Notiz") }
            deleted = async(Dispatchers.Default) { bookmarks.delete(old) }
            // Der Abruf dauert; ohne Absprache kämen die Meldungen in dieser Zeit durch.
            withContext(Dispatchers.Default) { withTimeoutOrNull(500) { while (requests.size < 3) delay(10) } }
            before
        }
        added!!.await()
        deleted!!.await()

        val left = bookmarks.of(bookId).first()
        assertEquals(listOf("Neu" to "Meine Notiz"), left.map { it.title to it.note })
        assertTrue(db.bookDao().dirtyBookmarks().isEmpty())
    }

    @Test
    fun `server bookmarks are taken over, renamed and removed unless changed here`() = runTest {
        bookmarks.applyRemote("srv", listOf(AbsBookmarkDto("item-1", 30.5, "Anfang", 1_000), AbsBookmarkDto("other", 10.0, "Fremd", 1_000)))
        val first = bookmarks.of(bookId).first().single()
        assertEquals(30_500, first.positionMs)
        assertEquals(1_000, first.createdAt)

        bookmarks.applyRemote("srv", listOf(AbsBookmarkDto("item-1", 30.5, "Neuer Titel", 1_000)))
        assertEquals("Neuer Titel", bookmarks.of(bookId).first().single().title)

        // Hier geändert und noch nicht gemeldet: bleibt, auch wenn der Server es nicht mehr kennt.
        offline = true
        bookmarks.edit(first.id, "Meins", "")
        bookmarks.applyRemote("srv", emptyList())
        assertEquals("Meins", bookmarks.of(bookId).first().single().title)

        offline = false
        assertTrue(bookmarks.pushPending())
        bookmarks.applyRemote("srv", emptyList())
        assertTrue(bookmarks.of(bookId).first().isEmpty())
        assertFalse(requests.isEmpty())
    }
}
