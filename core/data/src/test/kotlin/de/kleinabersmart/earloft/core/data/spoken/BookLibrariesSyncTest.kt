package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.datasource.local.LocalBookScanner
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthInterceptor
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentHashMap

/** Mehrere Buch-Bibliotheken je Server (`books_improvements.md` M3) und Genres (S9) im Abgleich, gegen einen nachgebauten Server. */
@RunWith(RobolectricTestRunner::class)
class BookLibrariesSyncTest {

    private val server = MockWebServer()
    private lateinit var db: EarLoftDatabase
    private lateinit var dao: BookDao
    private lateinit var servers: AudiobookshelfServers
    private lateinit var syncer: BookSyncer
    private lateinit var books: BookRepository

    /** Einträge je Bibliothek (Kennung, Titel, Genres); änderbar zwischen zwei Abgleichen. */
    private val items = ConcurrentHashMap(
        mapOf(
            "lib-a" to listOf(Triple("li_1", "Krimi", listOf("Polizei, Thriller")), Triple("li_2", "Roman", emptyList())),
            "lib-b" to listOf(Triple("li_3", "Hörspiel", listOf("Kinder"))),
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
            // schon initialisiert
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
        val progressSync = BookProgressSync(context, dao, servers, api, PlaybackPolicy())
        val scanner = LocalBookScanner(context)
        val covers = BookCovers(context, dao, db.podcastDao(), servers, api, PodcastFeedApi(client), scanner)
        syncer = BookSyncer(
            dao, servers, api, progressSync, Bookmarks(context, dao, servers, api, PlaybackPolicy()),
            LocalBooks(dao, scanner, SpokenSettings(context), covers),
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
        assertEquals(listOf("Audiobücher" to true, "Hörspiele" to true), connected.libraries.map { it.name to it.shown })

        assertTrue(sync(id).ok)

        val all = books.books().first().associateBy { it.book.title }
        assertEquals(listOf("Hörspiel", "Krimi", "Roman"), all.keys.sorted())
        assertEquals("lib-a", all.getValue("Krimi").book.libraryId)
        assertEquals("lib-b", all.getValue("Hörspiel").book.libraryId)
        assertEquals(listOf("Polizei", "Thriller"), all.getValue("Krimi").book.genres)
    }

    @Test
    fun `a deselected library disappears at once and is not synced, the last one stays`() = runTest {
        val id = connect()
        sync(id)

        assertTrue(servers.setLibraryShown(id, "lib-b", shown = false))
        assertEquals(listOf("Krimi", "Roman"), titles())
        sync(id)
        assertEquals(listOf("Krimi", "Roman"), titles())
        assertEquals(2, dao.booksOf(id).size)
        // Der nächste Abgleich fragt die abgewählte Bibliothek gar nicht ab.
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
        items["lib-a"] = items.getValue("lib-a") + Triple("li_4", "Neu", emptyList())
        val result = sync(id)

        assertEquals(listOf("Hörspiel", "Krimi", "Neu", "Roman"), titles())
        assertEquals(listOf("Neu"), result.newBooks.map { it.title })
    }

    @Test
    fun `a library the server no longer has drops its books`() = runTest {
        val id = connect()
        sync(id)
        items.remove("lib-b")

        sync(id)

        assertEquals(listOf("Krimi", "Roman"), titles())
        assertEquals(listOf("Audiobücher"), servers.servers.first().single().libraries.map { it.name })
    }

    /** `/ping`, `/api/me`, `/api/libraries`, die Einträge je Bibliothek und je Eintrag die Details (ohne Dateien). */
    private inner class FakeServer : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.url
            val path = url.encodedPath
            return when {
                path == "/ping" -> json("""{"success":true}""")
                path == "/api/me" -> json("""{"id":"user-1","mediaProgress":[],"bookmarks":[]}""")
                path == "/api/libraries" -> {
                    val names = mapOf("lib-a" to "Audiobücher", "lib-b" to "Hörspiele")
                    val libraries = items.keys.sorted().joinToString(",") { """{"id":"$it","name":"${names[it]}","mediaType":"book"}""" }
                    json("""{"libraries":[$libraries,{"id":"pod","name":"Podcasts","mediaType":"podcast"}]}""")
                }
                path.startsWith("/api/libraries/") && path.endsWith("/items") -> {
                    val library = path.removePrefix("/api/libraries/").removeSuffix("/items")
                    val entries = items[library].orEmpty()
                    json("""{"results":[${entries.joinToString(",") { item(it.first, it.second, it.third) }}],"total":${entries.size}}""")
                }
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
            return """{"id":"$id","addedAt":1,"updatedAt":1,"media":{"metadata":{"title":"$title","authorName":"A","genres":[$genreList]},"duration":60}}"""
        }

        private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
    }
}
