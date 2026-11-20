package de.kleinabersmart.earloft.core.datasource.subsonic

import de.kleinabersmart.earloft.core.datasource.local.FallbackNames
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicApi
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicCredentials
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class SubsonicDataSourceTest {

    private val names = FallbackNames(
        unknownTitle = "Unknown title",
        unknownArtist = "Unknown artist",
        unknownAlbum = "Unknown album",
        variousArtists = "Various artists",
    )
    private lateinit var mockServer: MockWebServer
    private lateinit var source: SubsonicDataSource

    /** `search3` songs for offset and count. */
    private var songPage: (offset: Int, count: Int) -> List<String> = { _, _ -> emptyList() }

    /** Song count the album list announces. */
    private var albumSongCount = 0

    @Before
    fun setUp() {
        mockServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.url
                    val body = when {
                        url.encodedPath.endsWith("/getAlbumList2") ->
                            if (url.queryParameter("offset") == "0") {
                                """"albumList2":{"album":[{"id":"al","name":"Takk","songCount":$albumSongCount}]}"""
                            } else {
                                """"albumList2":{"album":[]}"""
                            }
                        url.encodedPath.endsWith("/search3") -> {
                            val ids = songPage(url.queryParameter("songOffset")!!.toInt(), url.queryParameter("songCount")!!.toInt())
                            """"searchResult3":{"song":[${ids.joinToString { """{"id":"$it","title":"$it","albumId":"al"}""" }}]}"""
                        }
                        else -> return MockResponse.Builder().code(404).build()
                    }
                    return MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"subsonic-response":{"status":"ok","version":"1.16.1",$body}}""")
                        .build()
                }
            }
            start()
        }
        val server = SubsonicServer("nd", mockServer.url("/nd/"), SubsonicCredentials.Password("kal", "secret"))
        source = SubsonicDataSource(server, "Test", SubsonicApi(OkHttpClient()), names)
    }

    @After
    fun tearDown() {
        mockServer.close()
    }

    @Test
    fun `full scan reads on when the server returns fewer songs per page than asked for`() = runTest {
        val all = (0 until 5).map { "s$it" }
        albumSongCount = all.size
        // A server (or proxy) that caps every answer at two songs.
        songPage = { offset, count -> all.drop(offset).take(minOf(count, 2)) }

        assertEquals(all, scannedIds())
    }

    @Test
    fun `a song removed before the offset during the scan does not make the next one go missing`() = runTest {
        var removed = false
        songPage = { offset, count ->
            val all = if (removed) listOf("s1", "s2", "s3", "s4") else listOf("s0", "s1", "s2", "s3", "s4")
            all.drop(offset).take(minOf(count, 2)).also { removed = true }
        }

        // Without the check the second page would start at s3, and s2 would be missing (the sync would delete it).
        assertEquals(setOf("s0", "s1", "s2", "s3", "s4"), scannedIds().toSet())
    }

    @Test
    fun `full scan aborts when far fewer songs arrive than the albums announce`() = runTest {
        albumSongCount = 1_000
        songPage = { offset, _ -> listOf("s0", "s1").drop(offset) }

        try {
            source.fullScan().toList()
            fail("The scan must abort, otherwise the sync would delete the missing songs")
        } catch (_: IOException) {
        }
    }

    private suspend fun scannedIds() = source.fullScan().toList().flatMap { batch -> batch.tracks.map { it.remoteId } }
}
