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

    /** Laufzeit je Eintrag in Sekunden; ohne Angabe 60. */
    private val durations = ConcurrentHashMap<String, Int>()

    /** Titel, unter dem der Server den Upload meldet (Audio-Tags vor Ordnername); `null`: wie hochgeladen. */
    @Volatile
    private var reportedTitle: String? = null

    /** Der Server meldet statt des Uploads ein Buch einer anderen Person (Titel „Fremdes Buch“, eine Stunde). */
    @Volatile
    private var foreignInstead = false

    @Volatile
    private var canUpload = true

    /** Der Server scannt den Upload erst später: Er landet hier statt in [items]. */
    @Volatile
    private var scanLater = false
    private val scannedLater = ConcurrentHashMap<String, String>()

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
        val adoption = ArdUploadAdoption(context, dao, progressSync, bookmarks)
        syncer = BookSyncer(
            dao, servers, api, progressSync, bookmarks,
            LocalBooks(dao, scanner, settings, covers),
            ArdBooks(dao, ArdSoundsBooks(ArdSoundsApi(client)), settings, covers, { _, _ -> }),
            adoption,
        )
        uploader = ArdBookUploader(context, dao, servers, api, syncer, covers, adoption)
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
    fun `a part that is gone and never downloaded is left out, positions move up`() = runTest {
        val id = dao.insertBook(
            BookEntity(
                serverId = ARD_BOOK_SERVER_ID, kind = BookKind.BOOK, remoteId = "group:krieg", title = "Der Krieg der Welten",
                normTitle = "Der Krieg der Welten".normalizeForMerge(), author = "H. G. Wells", durationMs = 60_000, detailsLoaded = true,
            ),
        )
        dao.insertTracks(
            listOf(
                BookTrackEntity(id, 0, "https://ard.example/1.mp3", 0, 30_000, "audio/mpeg", unavailableSince = 5),
                BookTrackEntity(id, 1, "https://ard.example/2.mp3", 30_000, 30_000, "audio/mpeg", TrackDownload.DONE, audio.absolutePath),
            ),
        )
        dao.insertChapters(listOf(BookChapterEntity(id, 0, "Ankunft", 0, 30_000), BookChapterEntity(id, 1, "Flucht", 30_000, 60_000)))
        dao.upsertProgress(BookProgressEntity(id, 45_000, 60_000, updatedAt = 5))
        dao.insertBookmark(BookmarkEntity(bookId = id, positionMs = 12_000, title = "Im ersten Teil", note = "", createdAt = 1, updatedAt = 1))

        assertEquals(1, uploader.missingParts(id))
        val newBookId = (uploader.upload(id, target()) as UploadResult.Done).newBookId!!

        assertTrue(uploads.single(), "file=Der Krieg der Welten.mp3" in uploads.single())
        assertEquals(15_000L, dao.progress(newBookId)!!.positionMs)
        assertEquals(listOf(0L), dao.bookmarksOf(listOf(newBookId)).map { it.positionMs })
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

    @Test
    fun `a book somebody else uploaded in the meantime is not taken for the upload`() = runTest {
        val bookId = ardBook()
        // Das Buch der anderen Person kennt die App noch nicht; ohne vorherigen Abgleich gelte es als neu.
        items["li_foreign"] = "Fremdes Buch"
        durations["li_foreign"] = 3_600
        foreignInstead = true

        val result = uploader.upload(bookId, target())

        assertEquals(UploadResult.Done(null), result)
        assertTrue(dao.booksOf(serverId).none { it.remoteId == "li_foreign" && dao.progress(it.bookId) != null })
        assertTrue(dao.bookmarksOf(dao.booksOf(serverId).map { it.bookId }).isEmpty())
    }

    @Test
    fun `a foreign book that appears during the upload is not taken either`() = runTest {
        val bookId = ardBook()
        foreignInstead = true

        val result = uploader.upload(bookId, target())

        assertEquals(UploadResult.Done(null), result)
        assertTrue(dao.bookmarksOf(dao.booksOf(serverId).map { it.bookId }).isEmpty())
    }

    @Test
    fun `a title changed by the audio tags is still found by the length`() = runTest {
        val bookId = ardBook()
        reportedTitle = "Krieg der Welten (Hörspiel)"

        val newBookId = (uploader.upload(bookId, target()) as UploadResult.Done).newBookId!!

        assertEquals(30_000L, dao.progress(newBookId)!!.positionMs)
    }

    @Test
    fun `a book the server reports only later gets progress and bookmarks at the next sync`() = runTest {
        val bookId = ardBook()
        scanLater = true

        // Der Server meldet das Buch während des Wartens nicht (oder der Nutzer bricht ab).
        assertEquals(UploadResult.Done(null), uploader.upload(bookId, target()))
        items.putAll(scannedLater)
        syncer.syncSafely(servers.server(serverId)!!)

        val newBook = dao.booksOf(serverId).single { it.remoteId == "li_new" }
        assertEquals(30_000L, dao.progress(newBook.bookId)!!.positionMs)
        assertEquals(listOf("Stelle"), dao.bookmarksOf(listOf(newBook.bookId)).map { it.title })
        // Nur einmal: Ein weiterer Abgleich legt das Lesezeichen nicht noch einmal an.
        syncer.syncSafely(servers.server(serverId)!!)
        assertEquals(1, dao.bookmarksOf(listOf(newBook.bookId)).size)
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
                        if (foreignInstead) {
                            items["li_foreign"] = "Fremdes Buch"
                            durations["li_foreign"] = 3_600
                        } else {
                            Regex("""name="title"\r\n\r\n([^\r]*)""").find(body)?.let {
                                (if (scanLater) scannedLater else items)["li_new"] = reportedTitle ?: it.groupValues[1]
                            }
                        }
                    }
                    MockResponse.Builder().code(uploadStatus).build()
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }

        private fun item(id: String, title: String) =
            """{"id":"$id","addedAt":1,"updatedAt":1,"media":{"metadata":{"title":"$title","authorName":"H. G. Wells"},"duration":${durations[id] ?: 60}}}"""

        private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
    }
}
