package de.kleinabersmart.earloft.core.network.podcast

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Feed and episode images: whole or not at all (review H14). */
class PodcastImageTest {

    private lateinit var server: MockWebServer
    private val api = PodcastFeedApi(OkHttpClient())

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `an image is delivered whole`() = runTest {
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(12_000_000))).build())

        assertEquals(12_000_000, api.image(server.url("/big.png").toString())!!.size)
    }

    @Test
    fun `an image beyond the limit is refused instead of cut off`() = runTest {
        // Chunked: the size is only known while reading.
        server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(ByteArray(41 * 1024 * 1024)), 1024 * 1024).build())

        try {
            api.image(server.url("/huge.png").toString())
            fail("A cut-off image must not be returned")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun `a missing image is null`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())

        assertNull(api.image(server.url("/gone.png").toString()))
    }
}
