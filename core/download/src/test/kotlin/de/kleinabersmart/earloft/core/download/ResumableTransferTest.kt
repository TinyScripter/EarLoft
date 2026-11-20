package de.kleinabersmart.earloft.core.download

import de.kleinabersmart.earloft.core.network.HttpStatusException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class ResumableTransferTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var storage: BookStorage
    private lateinit var transfer: ResumableTransfer

    private val audio = ByteArray(300_000) { (it * 31).toByte() }
    private val progress: suspend (Long, Long?) -> Unit = { _, _ -> }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        storage = BookStorage(tempFolder.newFolder("books"))
        transfer = ResumableTransfer(OkHttpClient(), storage)
    }

    @After
    fun tearDown() = server.close()

    private suspend fun run(onProgress: suspend (Long, Long?) -> Unit = progress) =
        transfer.run(server.url("/file"), bookId = 7, trackIndex = 0, onProgress = onProgress)

    private fun full(bytes: ByteArray = audio, etag: String? = "\"v1\"") = MockResponse.Builder()
        .addHeader("Content-Type", "audio/mp4")
        .apply { if (etag != null) addHeader("ETag", etag) }
        .body(Buffer().write(bytes))
        .build()

    /** The server aborts in the middle of the body although it announced the full length. */
    private fun cutOff() = MockResponse.Builder()
        .addHeader("Content-Type", "audio/mp4")
        .addHeader("ETag", "\"v1\"")
        .body(Buffer().write(audio))
        .onResponseBody(SocketEffect.CloseSocket())
        .build()

    private fun rest(from: Long, etag: String = "\"v1\"") = MockResponse.Builder()
        .code(206)
        .addHeader("Content-Type", "audio/mp4")
        .addHeader("ETag", etag)
        .addHeader("Content-Range", "bytes $from-${audio.size - 1}/${audio.size}")
        .body(Buffer().write(audio.copyOfRange(from.toInt(), audio.size)))
        .build()

    private suspend fun interrupt(): Long {
        server.enqueue(cutOff())
        try {
            run()
            fail("The abort should have thrown an exception")
        } catch (_: IOException) {
            // expected
        }
        return storage.partFileFor(7, 0).length()
    }

    @Test
    fun `a complete download needs no range and leaves no marker behind`() = runTest {
        server.enqueue(full())

        val result = run()

        assertEquals(TransferResult.Completed("audio/mp4"), result)
        assertNull(server.takeRequest().headers["Range"])
        assertArrayEquals(audio, storage.partFileFor(7, 0).readBytes())
        assertFalse(storage.metaFileFor(7, 0).exists())
    }

    @Test
    fun `a download removed on the way stops instead of loading to the end`() = runTest {
        server.enqueue(full())

        val result = transfer.run(server.url("/file"), bookId = 7, trackIndex = 0, onProgress = progress, stillWanted = { false })

        assertEquals(TransferResult.Cancelled, result)
    }

    @Test
    fun `the very first download creates the folder before writing the marker`() = runTest {
        // As on a freshly set up watch: the folder for books doesn't exist yet.
        val fresh = BookStorage(java.io.File(tempFolder.root, "new/books"))
        server.enqueue(full())

        val result = ResumableTransfer(OkHttpClient(), fresh).run(server.url("/file"), bookId = 7, trackIndex = 0, onProgress = progress)

        assertEquals(TransferResult.Completed("audio/mp4"), result)
        assertArrayEquals(audio, fresh.partFileFor(7, 0).readBytes())
    }

    @Test
    fun `an interrupted download continues where the part file ends`() = runTest {
        val have = interrupt()
        assertTrue("Part file must remain and be shorter", have in 1 until audio.size)
        server.takeRequest()
        server.enqueue(rest(have))

        val result = run()

        assertEquals(TransferResult.Completed("audio/mp4"), result)
        server.takeRequest().also {
            assertEquals("bytes=$have-", it.headers["Range"])
            assertEquals("\"v1\"", it.headers["If-Range"])
        }
        assertArrayEquals(audio, storage.partFileFor(7, 0).readBytes())
        assertFalse(storage.metaFileFor(7, 0).exists())
    }

    @Test
    fun `an episode continues with the headers of its request`() = runTest {
        val episodes = EpisodeStorage(tempFolder.newFolder("episodes"))
        val episodeTransfer = ResumableTransfer(OkHttpClient(), episodes)
        val request = okhttp3.Request.Builder().url(server.url("/episode.mp3")).header("User-Agent", "EarLoft").build()
        server.enqueue(cutOff())
        try {
            episodeTransfer.run(request, bookId = 9, trackIndex = 0, onProgress = progress)
            fail("The abort should have thrown an exception")
        } catch (_: IOException) {
            // expected
        }
        val have = episodes.partFileFor(9).length()
        assertTrue(episodes.metaFileFor(9).exists())
        // The partial file of a waiting episode survives the cleanup.
        episodes.deleteOrphans(emptyList(), listOf(9L))
        assertEquals(have, episodes.partFileFor(9).length())
        server.takeRequest()
        server.enqueue(rest(have))

        val result = episodeTransfer.run(request, bookId = 9, trackIndex = 0, onProgress = progress)

        assertEquals(TransferResult.Completed("audio/mp4"), result)
        server.takeRequest().also {
            assertEquals("bytes=$have-", it.headers["Range"])
            assertEquals("EarLoft", it.headers["User-Agent"])
        }
        assertArrayEquals(audio, episodes.partFileFor(9).readBytes())
    }

    @Test
    fun `progress of a resumed download includes what was already loaded`() = runTest {
        val have = interrupt()
        server.enqueue(rest(have))
        var first: Long? = null

        run { done, total ->
            if (first == null) first = done
            assertEquals(audio.size.toLong(), total)
        }

        assertTrue("Progress does not start at 0", first.let { it == null || it >= have })
    }

    @Test
    fun `a changed server file replaces the part file instead of being appended`() = runTest {
        interrupt()
        val changed = ByteArray(250_000) { (it * 7).toByte() }
        // The server doesn't honor If-Range and delivers the new file in full (200).
        server.enqueue(full(changed, etag = "\"v2\""))

        val result = run()

        assertEquals(TransferResult.Completed("audio/mp4"), result)
        assertArrayEquals(changed, storage.partFileFor(7, 0).readBytes())
    }

    @Test
    fun `without a validator an interrupted download starts from scratch`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "audio/mp4")
                .body(Buffer().write(audio))
                .onResponseBody(SocketEffect.CloseSocket())
                .build(),
        )
        try {
            run()
        } catch (_: IOException) {
        }
        server.takeRequest()
        server.enqueue(full(etag = null))

        run()

        assertNull(server.takeRequest().headers["Range"])
        assertArrayEquals(audio, storage.partFileFor(7, 0).readBytes())
    }

    @Test
    fun `a weak etag is not used to resume`() = runTest {
        server.enqueue(full(etag = "W/\"weak\""))
        run()
        assertFalse(storage.metaFileFor(7, 0).exists())
    }

    @Test
    fun `range not satisfiable discards the part file and the next attempt starts over`() = runTest {
        interrupt()
        server.enqueue(MockResponse.Builder().code(416).build())

        try {
            run()
            fail("416 must trigger a new attempt")
        } catch (_: IOException) {
        }
        assertFalse(storage.partFileFor(7, 0).exists())
        assertFalse(storage.metaFileFor(7, 0).exists())

        server.enqueue(full())
        assertEquals(TransferResult.Completed("audio/mp4"), run())
        assertArrayEquals(audio, storage.partFileFor(7, 0).readBytes())
    }

    @Test
    fun `a 206 for the wrong range is rejected rather than corrupting the file`() = runTest {
        val have = interrupt()
        server.enqueue(rest(have + 1000))

        try {
            run()
            fail("Wrong range must be rejected")
        } catch (_: IOException) {
        }
        assertFalse(storage.partFileFor(7, 0).exists())
    }

    @Test
    fun `a 206 of a replaced file is rejected even when the host ignores If-Range`() = runTest {
        val have = interrupt()
        server.takeRequest()
        // Same start, but a different fingerprint.
        server.enqueue(rest(have, etag = "\"v2\""))
        try {
            run()
            fail("A different file must not be glued to the part file")
        } catch (_: IOException) {
        }
        assertFalse(storage.partFileFor(7, 0).exists())

        // No fingerprint in the answer, but a different total size.
        val again = interrupt()
        server.takeRequest()
        server.enqueue(
            MockResponse.Builder().code(206).addHeader("Content-Type", "audio/mp4")
                .addHeader("Content-Range", "bytes $again-${audio.size}/${audio.size + 1}")
                .body(Buffer().write(ByteArray(audio.size + 1 - again.toInt()))).build(),
        )
        try {
            run()
            fail("A different size must not be glued to the part file")
        } catch (_: IOException) {
        }
        assertFalse(storage.partFileFor(7, 0).exists())
    }

    @Test
    fun `rate limits and timeouts are temporary`() = runTest {
        listOf(408, 425, 429).forEach { code ->
            server.enqueue(MockResponse.Builder().code(code).build())
            try {
                run()
                fail("HTTP $code must be retried later")
            } catch (e: IOException) {
                assertTrue(e !is HttpStatusException)
            }
        }
    }

    @Test
    fun `a rejected login is reported as an auth error and a forbidden one fails permanently`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())
        try {
            run()
            fail("401 must be reported as an error so the caller can fetch new credentials")
        } catch (e: HttpStatusException) {
            assertTrue(e.isAuthError)
        }

        server.enqueue(MockResponse.Builder().code(403).build())
        assertEquals(TransferResult.Failed("Not signed in", forbidden = true), run())
    }

    @Test
    fun `non audio answers fail permanently`() = runTest {

        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html>").build())
        assertTrue(run() is TransferResult.Failed)
        assertFalse(storage.partFileFor(7, 0).exists())
    }

    @Test
    fun `content range is parsed`() {
        val range = ResumableTransfer.parseContentRange("bytes 100-999/1000")!!
        assertEquals(100L, range.start)
        assertEquals(1000L, range.total)
        assertNull(ResumableTransfer.parseContentRange("bytes 100-999/*")!!.total)
        assertNull(ResumableTransfer.parseContentRange("nonsense"))
        assertNull(ResumableTransfer.parseContentRange(null))
    }

    @Test
    fun `orphan cleanup keeps part files and their markers`() {
        val keep = storage.fileFor(7, 1, "audio/mp4").apply { writeText("x") }
        storage.ensureDirectory()
        storage.partFileFor(7, 0).writeText("part")
        storage.metaFileFor(7, 0).writeText("\"v1\"\n10\n")
        val orphan = storage.fileFor(9, 0, "audio/mp4").apply { writeText("y") }

        storage.deleteOrphans(listOf(keep.absolutePath))

        assertTrue(keep.exists())
        assertFalse(orphan.exists())
        assertTrue(storage.partFileFor(7, 0).exists())
        assertTrue(storage.metaFileFor(7, 0).exists())
        assertEquals(2, storage.deleteParts())
    }
}
