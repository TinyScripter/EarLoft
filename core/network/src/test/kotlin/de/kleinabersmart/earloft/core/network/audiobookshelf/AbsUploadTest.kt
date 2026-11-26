package de.kleinabersmart.earloft.core.network.audiobookshelf

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AbsUploadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: AbsApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = AbsApi(OkHttpClient())
    }

    @After
    fun tearDown() = server.close()

    private fun upload(): AbsUpload {
        val file = tempFolder.newFile("01.mp3").apply { writeBytes(ByteArray(200_000) { it.toByte() }) }
        return AbsUpload("lib", "folder", "Title", "Author", files = listOf(AbsUploadFile(file, "01.mp3", "audio/mpeg")))
    }

    private val absServer get() = AbsServer("abs", server.url("/"), "token")

    @Test
    fun `an answer lost after everything was sent counts as unconfirmed, not as failed`() = runTest {
        // The server receives the whole book, then the connection breaks before its answer (like a read timeout).
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        var sent = 0L
        try {
            api.uploadBook(absServer, upload()) { done, _ -> sent = done }
            fail("The missing answer should be reported")
        } catch (e: AbsUploadUnconfirmed) {
            assertEquals(200_000L, sent)
        }
    }

    @Test
    fun `a repeated request after a renewed token counts its bytes anew`() = runTest {
        // Like the token authenticator: the first answer is 401, the request goes out once more with a new token.
        val renewing = OkHttpClient.Builder()
            .authenticator { _, response ->
                if (response.priorResponse != null) null else response.request.newBuilder().header("Authorization", "Bearer new").build()
            }
            .build()
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(200).build())
        val reported = mutableListOf<Long>()

        AbsApi(renewing).uploadBook(absServer, upload()) { done, _ -> synchronized(reported) { reported += done } }

        assertEquals("Never beyond the total", 200_000L, reported.max())
        assertEquals(200_000L, reported.last())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a rejection stays a rejection`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).body("Forbidden").build())
        try {
            api.uploadBook(absServer, upload())
            fail("The rejection should be reported")
        } catch (e: AbsException) {
            assertTrue(e.isAuthError)
        }
    }
}
