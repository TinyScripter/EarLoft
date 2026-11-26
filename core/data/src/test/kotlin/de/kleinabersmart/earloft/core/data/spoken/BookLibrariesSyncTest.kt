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
import de.kleinabersmart.earloft.core.database.entity.AbsSessionEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.datasource.local.LocalBookScanner
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthInterceptor
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Several book libraries per server (`books_improvements.md` M3) and genres (S9) in the sync, against a mocked server. */
@RunWith(RobolectricTestRunner::class)
class BookLibrariesSyncTest {

    private val server = MockWebServer()
    private lateinit var db: EarLoftDatabase
    private lateinit var dao: BookDao
    private lateinit var servers: AudiobookshelfServers
    private lateinit var syncer: BookSyncer
    private lateinit var books: BookRepository
    private lateinit var covers: BookCovers
    private lateinit var progressSync: BookProgressSync

    /** `lastUpdate` of the progress the server reports for `li_1` (at 10 s) and the positions (s) sent to it. */
    private val serverProgressAt = AtomicLong(0)
    private val sentPositions = CopyOnWriteArrayList<Double>()

    /** `updatedAt` per item on the server; 1 if not set. */
    private val updatedAt = ConcurrentHashMap<String, Long>()

    /** The cover the server delivers for every item; `null`: not reachable (404). */
    @Volatile private var serverCover: ByteArray? = null

    /** A library whose entries the server can't deliver (500). */
    @Volatile private var failingLibrary: String? = null

    /** Entries per library (id, title, genres); changeable between two syncs. */
    private val items = ConcurrentHashMap(
        mapOf(
            "lib-a" to listOf(Triple("li_1", "Crime", listOf("Police, Thriller")), Triple("li_2", "Novel", emptyList())),
            "lib-b" to listOf(Triple("li_3", "Drama", listOf("Kids"))),
        ),
    )

    @Before
    fun setUp() {
        server.dispatcher = FakeServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (e: IllegalStateException) {
            // already initialized
        }
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        dao = db.bookDao()
        val registry = AbsServerRegistry()
        val client = OkHttpClient.Builder().addNetworkInterceptor(AbsAuthInterceptor(registry)).build()
        val api = AbsApi(client)
        val secrets = object : SecretStore {
            val values = ConcurrentHashMap<String, String>()
            override suspend fun get(key: String): String? = values[key]
            override suspend fun put(key: String, value: String) {
                values[key] = value
            }
            override suspend fun remove(key: String) {
                values.remove(key)
            }
        }
        servers = testAudiobookshelfServers(dao, secrets, api, registry, client)
        progressSync = BookProgressSync(context, dao, servers, api, PlaybackPolicy())
        val scanner = LocalBookScanner(context)
        covers = BookCovers(context, dao, db.podcastDao(), servers, api, PodcastFeedApi(client), scanner)
        syncer = BookSyncer(
            dao, servers, api, progressSync, AbsSessionReporter(context, db.absSessionDao(), dao, servers, api, PlaybackPolicy()), covers, Bookmarks(context, dao, servers, api, PlaybackPolicy()),
            LocalBooks(dao, scanner, SpokenSettings(context), covers, SweepGuard(context)),
            ArdBooks(dao, ArdSoundsBooks(ArdSoundsApi(OkHttpClient())), SpokenSettings(context), covers, { _, _ -> }),
            ArdUploadAdoption(context, dao, progressSync, Bookmarks(context, dao, servers, api, PlaybackPolicy())),
        )
        books = BookRepository(dao, syncer)
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private suspend fun connect(): String =
        (servers.add("ABS", server.url("/").toString(), "key") as AddServerResult.Added).serverId

    private suspend fun sync(serverId: String) = syncer.syncSafely(servers.server(serverId)!!)

    private suspend fun titles() = books.books().first().map { it.book.title }.sorted()

    @Test
    fun `all book libraries of a server are synced, each book knows its library and genres`() = runTest {
        val id = connect()
        val connected = servers.servers.first().single()
        assertEquals(listOf("Audiobooks" to true, "Radio plays" to true), connected.libraries.map { it.name to it.shown })

        assertTrue(sync(id).ok)

        val all = books.books().first().associateBy { it.book.title }
        assertEquals(listOf("Crime", "Drama", "Novel"), all.keys.sorted())
        assertEquals("lib-a", all.getValue("Crime").book.libraryId)
        assertEquals("lib-b", all.getValue("Drama").book.libraryId)
        assertEquals(listOf("Police", "Thriller"), all.getValue("Crime").book.genres)
    }

    @Test
    fun `a sync sends listening sessions the catch-up worker gave up on`() = runTest {
        val id = connect()
        sync(id)
        val book = dao.booksOf(id).single { it.title == "Crime" }
        val now = System.currentTimeMillis()
        db.absSessionDao().upsert(
            AbsSessionEntity("s1", book.bookId, startedAt = now - 60_000, updatedAt = now, timeListeningMs = 60_000, startPositionMs = 0, currentPositionMs = 60_000, durationMs = 600_000),
        )

        assertTrue(sync(id).ok)

        assertEquals(false, db.absSessionDao().session("s1")?.dirty)
    }

    @Test
    fun `a book changed on the server loads its cover again, an unchanged one keeps it`() = runTest {
        val id = connect()
        sync(id)
        val changed = dao.booksOf(id).single { it.title == "Crime" }.bookId
        val unchanged = dao.booksOf(id).single { it.title == "Novel" }.bookId
        covers.store(changed, byteArrayOf(1))
        covers.store(unchanged, byteArrayOf(2))

        updatedAt["li_1"] = 2
        assertTrue(sync(id).ok)
        serverCover = byteArrayOf(3)

        assertArrayEquals("Loaded again on the next display", byteArrayOf(3), covers.coverBytes(changed))
        assertArrayEquals(byteArrayOf(2), covers.coverBytes(unchanged))
    }

    @Test
    fun `a replaced cover that can not be loaded yet keeps showing the previous one`() = runTest {
        val id = connect()
        sync(id)
        val changed = dao.booksOf(id).single { it.title == "Crime" }.bookId
        covers.store(changed, byteArrayOf(1))

        updatedAt["li_1"] = 2
        assertTrue(sync(id).ok)

        // Offline: the previous cover rather than none.
        assertArrayEquals(byteArrayOf(1), covers.coverBytes(changed))
        serverCover = byteArrayOf(3)
        assertArrayEquals(byteArrayOf(3), covers.coverBytes(changed))
        assertEquals(0, covers.retainKnown())
    }

    @Test
    fun `a changed cover still arrives when the sync aborts after its library`() = runTest {
        val id = connect()
        sync(id)
        val changed = dao.booksOf(id).single { it.title == "Crime" }.bookId
        covers.store(changed, byteArrayOf(1))

        // "Crime" is in the first library; the second one fails, after the first was written.
        updatedAt["li_1"] = 2
        failingLibrary = "lib-b"
        assertFalse(sync(id).ok)
        failingLibrary = null
        assertTrue(sync(id).ok)
        serverCover = byteArrayOf(3)

        assertArrayEquals(byteArrayOf(3), covers.coverBytes(changed))
    }

    @Test
    fun `before playback a newer server state wins over one listened offline here`() = runBlocking {
        // Real time: the pull waits for the server with a timeout, which virtual time would end at once.
        val book = offlineState()
        serverProgressAt.set(System.currentTimeMillis() + 5 * 60_000)

        val result = progressSync.pull(book)

        assertEquals(10_000L, result.progress?.positionMs)
        assertEquals(false, dao.progress(book)?.dirty)
        assertEquals(emptyList<Double>(), sentPositions)
    }

    @Test
    fun `before playback a state listened offline that is newer than the server's goes up`() = runBlocking {
        val book = offlineState()
        serverProgressAt.set(System.currentTimeMillis() - 60_000)

        val result = progressSync.pull(book)

        assertEquals(300_000L, result.progress?.positionMs)
        assertEquals(false, dao.progress(book)?.dirty)
        assertEquals(listOf(300.0), sentPositions)
    }

    @Test
    fun `a state delivered late adopts a newer one on the server instead of overwriting it`() = runBlocking {
        val book = offlineState()
        serverProgressAt.set(System.currentTimeMillis() + 60_000)

        assertTrue(progressSync.pushPending())

        assertEquals(10_000L, dao.progress(book)?.positionMs)
        assertEquals(false, dao.progress(book)?.dirty)
        assertEquals(emptyList<Double>(), sentPositions)
    }

    @Test
    fun `a state delivered late that is still newer than the server's goes up`() = runBlocking {
        val book = offlineState()
        serverProgressAt.set(System.currentTimeMillis() - 60_000)

        assertTrue(progressSync.pushPending())

        assertEquals(300_000L, dao.progress(book)?.positionMs)
        assertEquals(listOf(300.0), sentPositions)
    }

    /** Five minutes of "Crime" listened here offline, not reported yet. */
    private suspend fun offlineState(): Long {
        val id = connect()
        sync(id)
        val book = dao.booksOf(id).single { it.title == "Crime" }.bookId
        dao.upsertProgress(BookProgressEntity(book, positionMs = 300_000, durationMs = 600_000, updatedAt = System.currentTimeMillis(), dirty = true))
        return book
    }

    @Test
    fun `quick clicks on several libraries do not overwrite each other`() = runTest {
        val id = connect()
        sync(id)

        val results = listOf("lib-a", "lib-b").map { library ->
            async(Dispatchers.Default) { servers.setLibraryShown(id, library, shown = false) }
        }.awaitAll()

        // Deselecting both at the same time must not succeed: one stays, namely the one that was left last.
        assertEquals(listOf(false, true), results.sorted())
        assertEquals(1, servers.servers.first().single().libraries.count { it.shown })
    }

    @Test
    fun `a deselected library disappears at once and is not synced, the last one stays`() = runTest {
        val id = connect()
        sync(id)

        assertTrue(servers.setLibraryShown(id, "lib-b", shown = false))
        assertEquals(listOf("Crime", "Novel"), titles())
        sync(id)
        assertEquals(listOf("Crime", "Novel"), titles())
        // Hidden, not deleted: downloads, progress and bookmarks stay.
        assertEquals(3, dao.booksOf(id).size)
        // The next sync doesn't query the deselected library at all.
        assertFalse(servers.setLibraryShown(id, "lib-a", shown = false))
        assertEquals(listOf(false), servers.servers.first().single().libraries.filter { it.libraryId == "lib-b" }.map { it.shown })
    }

    @Test
    fun `books of a library chosen again are not reported as new, a new book is`() = runTest {
        val id = connect()
        sync(id)
        servers.setLibraryShown(id, "lib-b", shown = false)
        sync(id)

        servers.setLibraryShown(id, "lib-b", shown = true)
        items["lib-a"] = items.getValue("lib-a") + Triple("li_4", "New", emptyList())
        val result = sync(id)

        assertEquals(listOf("Crime", "Drama", "New", "Novel"), titles())
        assertEquals(listOf("New"), result.newBooks.map { it.title })
    }

    @Test
    fun `a library chosen again brings back the same books with their progress`() = runTest {
        val id = connect()
        sync(id)
        val before = dao.booksOf(id).single { it.title == "Drama" }
        dao.upsertProgress(BookProgressEntity(before.bookId, positionMs = 30_000, durationMs = 60_000, updatedAt = 1))
        servers.setLibraryShown(id, "lib-b", shown = false)
        sync(id)
        sync(id)

        servers.setLibraryShown(id, "lib-b", shown = true)
        sync(id)

        val after = dao.booksOf(id).single { it.title == "Drama" }
        assertEquals(before.bookId, after.bookId)
        assertEquals(30_000L, dao.progress(after.bookId)?.positionMs)
    }

    @Test
    fun `a deselected library the server no longer has drops its books`() = runTest {
        val id = connect()
        sync(id)
        servers.setLibraryShown(id, "lib-b", shown = false)
        sync(id)
        items.remove("lib-b")

        sync(id)

        assertEquals(listOf("Crime", "Novel"), dao.booksOf(id).map { it.title }.sorted())
    }

    @Test
    fun `a library the server no longer has drops its books`() = runTest {
        val id = connect()
        sync(id)
        items.remove("lib-b")

        sync(id)

        assertEquals(listOf("Crime", "Novel"), titles())
        assertEquals(listOf("Audiobooks"), servers.servers.first().single().libraries.map { it.name })
    }

    @Test
    fun `authors of all libraries come with their description, a failed request keeps them`() = runTest {
        val id = connect()
        assertTrue(sync(id).ok)

        assertEquals("Writes crime novels.", books.authorDescription("anna author").first())
        assertEquals("Writes radio plays.", books.authorDescription("Bert Author").first())
        assertEquals(null, books.authorDescription("No Text").first())

        authorsFail = true
        assertTrue(sync(id).ok)
        assertEquals("Writes crime novels.", books.authorDescription("Anna Author").first())

        servers.setEnabled(id, false)
        assertEquals(null, books.authorDescription("Anna Author").first())
    }

    @Test
    fun `an entry with only an e-book is not taken over, a known one that lost its audio disappears`() = runTest {
        items["lib-a"] = items.getValue("lib-a") + Triple("ebook", "Only text", emptyList())
        audioFiles["ebook"] = 0
        audioFiles["li_2"] = 3
        val id = connect()

        assertTrue(sync(id).ok)
        assertEquals(listOf("Crime", "Drama", "Novel"), titles())

        // Its audio files were removed on the server, only the e-book is left.
        audioFiles["li_2"] = 0
        assertTrue(sync(id).ok)
        assertEquals(listOf("Crime", "Drama"), titles())
    }

    @Test
    fun `a known book whose files are missing for the moment stays, even reported without audio files`() = runTest {
        audioFiles["li_2"] = 3
        val id = connect()
        assertTrue(sync(id).ok)

        // Share not mounted during the server's scan: the entry is missing, possibly also counted without audio (review3 Q9).
        missing += "li_2"
        audioFiles["li_2"] = 0
        assertTrue(sync(id).ok)
        assertEquals(listOf("Crime", "Drama", "Novel"), titles())
    }

    /** `numAudioFiles` per entry in the list; without one the field is left out, as it may be on other server versions. */
    private val audioFiles = ConcurrentHashMap<String, Int>()

    /** Entries the server reports as `isMissing`. */
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var authorsFail = false

    /** After each response with entries (library, `limit`, `page`): e.g. delete a book on the server. */
    @Volatile
    private var afterItems: (library: String, limit: Int, page: Int) -> Unit = { _, _, _ -> }

    private fun manyBooks(count: Int) = (1..count).map { Triple("many_$it", "Book $it", emptyList<String>()) }

    @Test
    fun `a book removed on the server between two pages does not make the sync drop the next one`() = runTest {
        items["lib-a"] = manyBooks(150)
        items.remove("lib-b")
        val id = connect()
        assertTrue(sync(id).ok)
        val before = dao.booksOf(id).single { it.remoteId == "many_101" }

        // During the sync a book of the first page disappears: without the check "many_101" would be missing on page 2 and get deleted.
        var removed = false
        afterItems = { library, limit, page ->
            if (library == "lib-a" && limit > 1 && page == 0 && !removed) {
                removed = true
                items["lib-a"] = items.getValue("lib-a").filterNot { it.first == "many_5" }
            }
        }
        assertTrue(sync(id).ok)

        assertEquals(before.bookId, dao.booksOf(id).single { it.remoteId == "many_101" }.bookId)
        // The aborted pass still saw "many_5": it's only dropped with the next sync.
        assertEquals(150, dao.booksOf(id).size)
        assertTrue(sync(id).ok)
        val left = dao.booksOf(id).map { it.remoteId }.toSet()
        assertEquals(149, left.size)
        assertFalse("many_5" in left)
    }

    @Test
    fun `a server removed during its sync does not come back, neither do its books`() = runTest {
        val id = connect()
        var removed = false
        afterItems = { library, _, _ ->
            if (library == "lib-b" && !removed) {
                removed = true
                kotlinx.coroutines.runBlocking { servers.remove(id) }
            }
        }

        sync(id)

        assertEquals(null, dao.server(id))
        assertTrue(dao.booksOf(id).isEmpty())
    }

    @Test
    fun `a library that keeps shifting stops the sync without deleting anything`() = runTest {
        items["lib-a"] = manyBooks(150)
        items.remove("lib-b")
        val id = connect()
        assertTrue(sync(id).ok)

        // Every first page loses a book: the second pass shifts too.
        afterItems = { library, limit, page ->
            if (library == "lib-a" && limit > 1 && page == 0) items["lib-a"] = items.getValue("lib-a").drop(1)
        }
        assertFalse(sync(id).ok)

        assertEquals(150, dao.booksOf(id).size)
    }

    /** `/ping`, `/api/me`, `/api/libraries`, the entries and authors per library and the details per entry (without files). */
    private inner class FakeServer : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.url
            val path = url.encodedPath
            return when {
                path == "/ping" -> json("""{"success":true}""")
                path == "/api/me" -> json("""{"id":"user-1","mediaProgress":[],"bookmarks":[]}""")
                path == "/api/session/local" -> json("{}")
                path == "/api/me/progress/li_1" && request.method == "PATCH" -> {
                    val body = request.body?.utf8().orEmpty()
                    Regex(""""currentTime":([0-9.]+)""").find(body)?.groupValues?.get(1)?.toDouble()?.let(sentPositions::add)
                    json("{}")
                }
                path == "/api/me/progress/li_1" -> json(
                    """{"libraryItemId":"li_1","duration":600,"currentTime":10,"lastUpdate":${serverProgressAt.get()}}""",
                )
                path == "/api/libraries" -> {
                    val names = mapOf("lib-a" to "Audiobooks", "lib-b" to "Radio plays")
                    val libraries = items.keys.sorted().joinToString(",") { """{"id":"$it","name":"${names[it]}","mediaType":"book"}""" }
                    json("""{"libraries":[$libraries,{"id":"pod","name":"Podcasts","mediaType":"podcast"}]}""")
                }
                path.startsWith("/api/libraries/") && path.endsWith("/items") -> {
                    val library = path.removePrefix("/api/libraries/").removeSuffix("/items")
                    if (library == failingLibrary) return MockResponse.Builder().code(500).build()
                    val entries = items[library].orEmpty()
                    // Like the server: start = page * limit, limit 0 = everything.
                    val limit = url.queryParameter("limit")?.toInt() ?: 0
                    val page = url.queryParameter("page")?.toInt() ?: 0
                    val shown = if (limit > 0) entries.drop(page * limit).take(limit) else entries
                    val body = """{"results":[${shown.joinToString(",") { item(it.first, it.second, it.third) }}],"total":${entries.size}}"""
                    afterItems(library, limit, page)
                    json(body)
                }
                path.startsWith("/api/libraries/") && path.endsWith("/authors") -> {
                    if (authorsFail) return MockResponse.Builder().code(500).build()
                    when (path.removePrefix("/api/libraries/").removeSuffix("/authors")) {
                        "lib-a" -> json(
                            """{"authors":[{"id":"au1","name":"Anna Author","description":"Writes crime novels."},""" +
                                """{"id":"au3","name":"No Text","description":null}]}""",
                        )
                        else -> json("""{"authors":[{"id":"au2","name":"Bert Author","description":"Writes radio plays."}]}""")
                    }
                }
                path.endsWith("/cover") -> serverCover?.let { MockResponse.Builder().body(okio.Buffer().write(it)).build() }
                    ?: MockResponse.Builder().code(404).build()
                path.startsWith("/api/items/") -> {
                    val itemId = path.removePrefix("/api/items/")
                    val entry = items.values.flatten().firstOrNull { it.first == itemId }
                    if (entry == null) MockResponse.Builder().code(404).build() else json(item(entry.first, entry.second, entry.third))
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }

        private fun item(id: String, title: String, genres: List<String>): String {
            val genreList = genres.joinToString(",") { "\"$it\"" }
            val count = audioFiles[id]?.let { ",\"numAudioFiles\":$it" }.orEmpty()
            val isMissing = if (id in missing) ",\"isMissing\":true" else ""
            return """{"id":"$id","addedAt":1,"updatedAt":${updatedAt[id] ?: 1}$isMissing,"media":{"metadata":{"title":"$title","authorName":"A","genres":[$genreList]},"duration":60$count}}"""
        }

        private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
    }
}
