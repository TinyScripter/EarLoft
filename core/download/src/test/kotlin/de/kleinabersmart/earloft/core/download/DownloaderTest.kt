package de.kleinabersmart.earloft.core.download

import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.model.DownloadState
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var library: TestLibrary
    private lateinit var mockServer: MockWebServer
    private lateinit var storage: DownloadStorage
    private lateinit var downloader: Downloader
    private val uid = "nd:t1"

    @Before
    fun setUp() = runTest {
        library = TestLibrary()
        mockServer = MockWebServer().apply { start() }
        storage = DownloadStorage(tempFolder.newFolder("downloads"))
        downloader = Downloader(
            downloadDao = library.db.downloadDao(),
            sources = FakeSources(TranscodingServer("nd", baseUrl())),
            client = OkHttpClient(),
            storage = storage,
        )
        library.source("nd")
        val songId = library.song(library.album("Mondlicht"), "Leise", "nd" to "t1")
        library.db.downloadDao().insertIfAbsent(listOf(DownloadEntity(uid, songId, DownloadState.QUEUED)))
    }

    private fun baseUrl() = mockServer.url("/").toString().trimEnd('/')

    @After
    fun tearDown() {
        mockServer.close()
        library.close()
    }

    @Test
    fun `complete download is stored and marked done`() = runTest {
        val audio = ByteArray(200_000) { it.toByte() }
        mockServer.enqueue(audio(audio))

        val outcome = downloader.download(queued())

        assertEquals(DownloadOutcome.Done, outcome)
        assertEquals("/stream/t1", mockServer.takeRequest().url.encodedPath)
        val entry = library.db.downloadDao().get(uid)!!
        assertEquals(DownloadState.DONE, entry.state)
        assertEquals(audio.size.toLong(), entry.bytesTotal)
        val file = File(entry.filePath!!)
        assertEquals("nd_t1.flac", file.name)
        assertArrayEquals(audio, file.readBytes())
        assertTrue(storage.directory.listFiles()!!.none { it.name.endsWith(DownloadStorage.PART_SUFFIX) })
    }

    @Test
    fun `opus profile asks the server to transcode and names the file after the response`() = runTest {
        // Without Content-Length (Navidrome doesn't know the size of the transcoded file in advance).
        val audio = ByteArray(50_000) { (it * 7).toByte() }
        mockServer.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "audio/ogg")
                .chunkedBody(Buffer().write(audio), 4_096)
                .build(),
        )

        val outcome = downloader.download(queued(), DownloadProfile.OPUS_128)

        assertEquals(DownloadOutcome.Done, outcome)
        val url = mockServer.takeRequest().url
        assertEquals("opus", url.queryParameter("codec"))
        assertEquals("128", url.queryParameter("maxKbps"))
        val entry = library.db.downloadDao().get(uid)!!
        assertEquals("nd_t1.ogg", File(entry.filePath!!).name)
        assertArrayEquals(audio, File(entry.filePath!!).readBytes())
        assertEquals(audio.size.toLong(), entry.bytesTotal)
    }

    @Test
    fun `original profile leaves the stream url unchanged`() = runTest {
        mockServer.enqueue(audio(ByteArray(1_000)))

        downloader.download(queued(), DownloadProfile.ORIGINAL)

        val url = mockServer.takeRequest().url
        assertEquals("/stream/t1", url.encodedPath)
        assertNull(url.query)
    }

    @Test
    fun `opus profile loads the original from a source that cannot transcode`() = runTest {
        val plain = Downloader(library.db.downloadDao(), FakeSources(FakeServer("nd", baseUrl())), OkHttpClient(), storage)
        mockServer.enqueue(audio(ByteArray(1_000)))

        assertEquals(DownloadOutcome.Done, plain.download(queued(), DownloadProfile.OPUS_128))

        val url = mockServer.takeRequest().url
        assertEquals("/stream/t1", url.encodedPath)
        assertNull(url.query)
    }

    @Test
    fun `missing file on the server fails permanently`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(404).build())

        val outcome = downloader.download(queued())

        assertTrue(outcome is DownloadOutcome.Failed)
        assertEquals(DownloadState.FAILED, library.db.downloadDao().get(uid)!!.state)
        assertTrue(storage.directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `subsonic error with status 200 is not saved as audio`() = runTest {
        mockServer.enqueue(subsonicError(70))

        val outcome = downloader.download(queued())

        assertEquals("Subsonic error 70", (outcome as DownloadOutcome.Failed).reason)
        assertNull(library.db.downloadDao().get(uid)!!.filePath)
    }

    @Test
    fun `a web page with status 200 is not saved as audio`() = runTest {
        // The login page of a proxy or a captive portal: complete, with a matching length, but no song.
        mockServer.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8").body("<html><body>Sign in to continue</body></html>").build(),
        )

        val outcome = downloader.download(queued())

        assertEquals("Not an audio file (text/html)", (outcome as DownloadOutcome.Failed).reason)
        assertEquals(DownloadState.FAILED, library.db.downloadDao().get(uid)!!.state)
        assertNull(library.db.downloadDao().get(uid)!!.filePath)
        assertTrue(storage.directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `missing permission fails without asking for new credentials`() = runTest {
        mockServer.enqueue(subsonicError(50))

        val outcome = downloader.download(queued())

        assertTrue(outcome is DownloadOutcome.Failed)
        assertEquals(DownloadState.FAILED, library.db.downloadDao().get(uid)!!.state)
    }

    @Test
    fun `rejected api key is retried with new credentials`() = runTest {
        mockServer.enqueue(subsonicError(44))

        assertTrue((downloader.download(queued()) as DownloadOutcome.Retry).isAuthRejection)
    }

    @Test
    fun `rejected subsonic password is retried with new credentials`() = runTest {
        mockServer.enqueue(subsonicError(40))

        val outcome = downloader.download(queued())

        assertTrue((outcome as DownloadOutcome.Retry).isAuthRejection)
        assertEquals(DownloadState.RUNNING, library.db.downloadDao().get(uid)!!.state)
    }

    @Test
    fun `rejected token is retried with new credentials`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(401).build())

        val outcome = downloader.download(queued())

        assertTrue((outcome as DownloadOutcome.Retry).isAuthRejection)
        assertEquals(DownloadState.RUNNING, library.db.downloadDao().get(uid)!!.state)
    }

    @Test
    fun `server error is retried later`() = runTest {
        mockServer.enqueue(MockResponse.Builder().code(503).build())

        val outcome = downloader.download(queued())

        assertFalse((outcome as DownloadOutcome.Retry).isAuthRejection)
        assertEquals(DownloadState.RUNNING, library.db.downloadDao().get(uid)!!.state)
        assertTrue(storage.directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `download removed while loading leaves no file`() = runTest {
        mockServer.enqueue(audio(ByteArray(500_000)))

        val outcome = downloader.download(queued()) { _, _ ->
            library.db.downloadDao().delete(listOf(uid))
        }

        assertEquals(DownloadOutcome.Cancelled, outcome)
        assertTrue(storage.directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `download removed before start is skipped`() = runTest {
        val entry = queued()
        library.db.downloadDao().delete(listOf(uid))

        assertEquals(DownloadOutcome.Cancelled, downloader.download(entry))
        assertEquals(0, mockServer.requestCount)
    }

    private suspend fun queued() = library.db.downloadDao().get(uid)!!

    private fun subsonicError(code: Int) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json")
        .body("""{"subsonic-response":{"status":"failed","error":{"code":$code}}}""")
        .build()

    private fun audio(bytes: ByteArray) = MockResponse.Builder()
        .addHeader("Content-Type", "audio/flac")
        .body(Buffer().write(bytes))
        .build()
}
