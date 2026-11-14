package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.BookmarkEntity
import de.kleinabersmart.earloft.core.datasource.local.LocalBookScanner
import de.kleinabersmart.earloft.core.model.ARD_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthInterceptor
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsBooks
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Hochladen eines Buchs aus ARD Sounds (`books_improvements.md` M9) gegen einen nachgebauten Audiobookshelf-Server. */
@RunWith(RobolectricTestRunner::class)
class ArdBookUploaderTest {

    private val server = MockWebServer()
    private lateinit var context: Context
    private lateinit var db: EarLoftDatabase
    private lateinit var dao: BookDao
    private lateinit var servers: AudiobookshelfServers
    private lateinit var bookmarks: Bookmarks
    private lateinit var uploader: ArdBookUploader
    private lateinit var syncer: BookSyncer
    private lateinit var serverId: String
    private lateinit var audio: File

    /** Was der Server als Upload erhalten hat: Formularfelder und Dateinamen. */
    private val uploads = CopyOnWriteArrayList<String>()

    /** Antwort auf `POST /api/upload`. */
    @Volatile
    private var uploadStatus = 200

    /** Einträge der Bibliothek auf dem Server (Kennung → Titel); ein erfolgreicher Upload legt einen an. */
    private val items = ConcurrentHashMap<String, String>()

    @Volatile
    private var canUpload = true

    @Before
    fun setUp() = runTest {
        server.dispatcher = FakeServer()
        server.start()
        context = ApplicationProvider.getApplicationContext()
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
        serverId = (servers.add("ABS", server.url("/").toString(), "key") as AddServerResult.Added).serverId
        val progressSync = BookProgressSync(context, dao, servers, api, PlaybackPolicy())
        bookmarks = Bookmarks(context, dao, servers, api, PlaybackPolicy())
        val scanner = LocalBookScanner(context)
        val covers = BookCovers(context, dao, db.podcastDao(), servers, api, PodcastFeedApi(client), scanner)
        val settings = SpokenSettings(context)
        syncer = BookSyncer(
            dao, servers, api, progressSync, bookmarks,
            LocalBooks(dao, scanner, settings, covers),
            ArdBooks(dao, ArdSoundsBooks(ArdSoundsApi(client)), settings, covers),
        )
        uploader = ArdBookUploader(context, dao, servers, api, syncer, covers, progressSync, bookmarks)
        audio = File(context.cacheDir, "teil.mp3").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    /** Ein vollständig geladenes Buch aus ARD Sounds mit Fortschritt, Tempo und einem Lesezeichen. */
    private suspend fun ardBook(title: String = "Der Krieg der Welten", author: String = "H. G. Wells", downloaded: Boolean = true): Long {
        val id = dao.insertBook(
            BookEntity(
                serverId = ARD_BOOK_SERVER_ID, kind = BookKind.BOOK, remoteId = "item:$title", title = title,
                normTitle = title.normalizeForMerge(), author = author, narrator = "Andreas Fröhlich", description = "Marsianer greifen an.",
                durationMs = 60_000, detailsLoaded = true,
            ),
        )
        dao.insertTracks(
            listOf(
                BookTrackEntity(
                    id, 0, "https://ard.example/1.mp3", 0, 60_000, "audio/mpeg",
                    downloadState = if (downloaded) TrackDownload.DONE else TrackDownload.NONE,
                    filePath = audio.absolutePath.takeIf { downloaded },
                ),
            ),
        )
        dao.insertChapters(listOf(BookChapterEntity(id, 0, title, 0, 60_000)))
        dao.upsertProgress(BookProgressEntity(id, 30_000, 60_000, speed = 1.5f, updatedAt = 5, volumeBoostDb = 3))
        dao.insertBookmark(BookmarkEntity(bookId = id, positionMs = 12_000, title = "Stelle", note = "wichtig", createdAt = 1, updatedAt = 1))
        return id
    }

    private suspend fun target() = uploader.targets().single()

    @Test
    fun `targets list the folders of the book libraries only when the account may upload`() = runTest {
        val target = target()
        assertEquals(listOf("lib" to "folder"), listOf(target.libraryId to target.folderId))
        assertEquals("Audiobücher", target.libraryName)

        canUpload = false
        assertTrue(uploader.targets().isEmpty())
    }

    @Test
    fun `upload sends the numbered files with cover texts and takes over progress, speed and bookmarks`() = runTest {
        val bookId = ardBook()

        val result = uploader.upload(bookId, target())

        val done = result as UploadResult.Done
        val newBookId = done.newBookId!!
        val sent = uploads.single()
        assertTrue(sent, "title=Der Krieg der Welten" in sent && "author=H. G. Wells" in sent && "library=lib" in sent && "folder=folder" in sent)
        assertTrue(sent, "file=Der Krieg der Welten.mp3" in sent)
        assertTrue(sent, "file=desc.txt" in sent && "file=reader.txt" in sent)
        val progress = dao.progress(newBookId)!!
        assertEquals(30_000L, progress.positionMs)
        assertEquals(1.5f, progress.speed)
        assertEquals(3, progress.volumeBoostDb)
        assertEquals(listOf("Stelle"), dao.bookmarksOf(listOf(newBookId)).map { it.title })
        // Die Fassung aus ARD Sounds bleibt, bis der Aufrufer sie entfernt.
        assertTrue(dao.book(bookId) != null)
    }

    @Test
    fun `a book that is not completely downloaded is not uploaded`() = runTest {
        val bookId = ardBook(downloaded = false)

        assertEquals(UploadResult.NotDownloaded, uploader.upload(bookId, target()))
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun `a book already on the server is not uploaded again`() = runTest {
        items["li_old"] = "Der Krieg der Welten"
        val bookId = ardBook()
        // Der Abgleich kennt das Buch auf dem Server.
        syncer.syncSafely(servers.server(serverId)!!)

        assertEquals(UploadResult.Exists, uploader.upload(bookId, target()))
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun `a server error is reported as such, a refusal as missing permission`() = runTest {
        val bookId = ardBook()

        uploadStatus = 500
        assertEquals(UploadResult.ServerError, uploader.upload(bookId, target()))
        uploadStatus = 403
        assertEquals(UploadResult.NoPermission, uploader.upload(bookId, target()))
        assertFalse(items.containsKey("li_new"))
    }

    /** `/ping`, `/api/me`, `/api/libraries` mit Ordner, die Einträge je Bibliothek, Details je Eintrag und `POST /api/upload`. */
    private inner class FakeServer : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            return when {
                path == "/ping" -> json("""{"success":true}""")
                path == "/api/me" -> json("""{"id":"u","type":"user","permissions":{"upload":$canUpload},"mediaProgress":[],"bookmarks":[]}""")
                path == "/api/libraries" -> json(
                    """{"libraries":[{"id":"lib","name":"Audiobücher","mediaType":"book","folders":[{"id":"folder","fullPath":"/books"}]}]}""",
                )
                path == "/api/libraries/lib/items" ->
                    json("""{"results":[${items.entries.joinToString(",") { item(it.key, it.value) }}],"total":${items.size}}""")
                path == "/api/libraries/lib/authors" -> json("""{"authors":[]}""")
                path.startsWith("/api/items/") -> {
                    val id = path.removePrefix("/api/items/")
                    items[id]?.let { json(item(id, it)) } ?: MockResponse.Builder().code(404).build()
                }
                path == "/api/upload" && request.method == "POST" -> {
                    if (uploadStatus == 200) {
                        val body = request.body?.utf8().orEmpty()
                        val fields = Regex("""name="([a-z]+)"\r\n\r\n([^\r]*)""").findAll(body).joinToString(" ") { "${it.groupValues[1]}=${it.groupValues[2]}" }
                        val files = Regex("""filename="([^"]+)"""").findAll(body).joinToString(" ") { "file=${it.groupValues[1]}" }
                        uploads += "$fields $files"
                        // Der Server scannt und meldet das Buch danach.
                        Regex("""name="title"\r\n\r\n([^\r]*)""").find(body)?.let { items["li_new"] = it.groupValues[1] }
                    }
                    MockResponse.Builder().code(uploadStatus).build()
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }

        private fun item(id: String, title: String) =
            """{"id":"$id","addedAt":1,"updatedAt":1,"media":{"metadata":{"title":"$title","authorName":"H. G. Wells"},"duration":60}}"""

        private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
    }
}
