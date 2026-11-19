package de.kleinabersmart.earloft.core.datasource.jellyfin

import de.kleinabersmart.earloft.core.datasource.RemotePlaylist
import de.kleinabersmart.earloft.core.datasource.local.FallbackNames
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinApi
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinClientInfo
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinServer
import kotlinx.coroutines.flow.toList
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class JellyfinDataSourceTest {

    private val names = FallbackNames(
        unknownTitle = "Unknown title",
        unknownArtist = "Unknown artist",
        unknownAlbum = "Unknown album",
        variousArtists = "Various artists",
    )
    private lateinit var mockServer: MockWebServer
    private lateinit var source: JellyfinDataSource

    /** Responses per path; song pages via [songPage]. */
    private var songPage: (startIndex: Int) -> String = { "" }

    /** Etag of the playlists p1 (own) and p2 (foreign public) in the list. */
    private var playlistEtag = "e1"
    private val requests = mutableListOf<String>()

    @Before
    fun setUp() {
        mockServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.url
                    synchronized(requests) { requests += url.encodedPath.substringAfter("/jf/") }
                    val body = when {
                        url.encodedPath.endsWith("/Items") && url.queryParameter("includeItemTypes") == "Playlist" ->
                            """{"Items":[{"Id":"p1","Name":"Summer","MediaType":"Audio","Etag":"$playlistEtag"},""" +
                                """{"Id":"p2","Name":"Fremd","MediaType":"Audio","Etag":"x"}],"TotalRecordCount":2}"""
                        url.encodedPath.endsWith("/Playlists/p1/Users/u1") -> """{"CanEdit":true}"""
                        url.encodedPath.endsWith("/Playlists/p2/Users/u1") -> return MockResponse.Builder().code(403).build()
                        url.encodedPath.endsWith("/UserViews") ->
                            """{"Items":[{"Id":"lib","Name":"Musik","CollectionType":"music"}],"TotalRecordCount":1}"""
                        url.encodedPath.endsWith("/Items") && url.queryParameter("includeItemTypes") == "Audio" ->
                            songPage(url.queryParameter("startIndex")!!.toInt())
                        request.method == "POST" && url.encodedPath.endsWith("/Playlists") -> """{"Id":"p1"}"""
                        request.method == "POST" -> return MockResponse.Builder().code(204).build()
                        url.encodedPath.endsWith("/Playlists/p1") -> """{"OpenAccess":false,"Shares":[],"ItemIds":["a"]}"""
                        url.encodedPath.endsWith("/Items/p1") -> """{"Id":"p1","Name":"Summer","Type":"Playlist"}"""
                        else -> return MockResponse.Builder().code(404).build()
                    }
                    return MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
                }
            }
            start()
        }
        val server = JellyfinServer("jf", mockServer.url("/jf/"), userId = "u1", token = "tok")
        source = JellyfinDataSource(server, "Test", JellyfinApi(OkHttpClient(), JellyfinClientInfo("Test", "1.0")), names)
    }

    @After
    fun tearDown() {
        mockServer.close()
    }

    @Test
    fun `full scan follows pages a proxy cut short until the announced total`() = runTest {
        // The server (or a proxy) returns only two songs per page instead of the requested 500.
        songPage = { start -> page(ids = (start until minOf(start + 2, 5)).map { "s$it" }, total = 5) }

        val ids = source.fullScan().toList().flatMap { batch -> batch.tracks.map { it.remoteId } }

        assertEquals(listOf("s0", "s1", "s2", "s3", "s4"), ids)
    }

    @Test
    fun `a song removed before the offset during the scan does not make the next one go missing`() = runTest {
        // After the first page, s0 disappears on the server; everything moves forward by one.
        var removed = false
        songPage = { start ->
            val all = if (removed) listOf("s1", "s2", "s3", "s4") else listOf("s0", "s1", "s2", "s3", "s4")
            page(ids = all.drop(start).take(2), total = all.size).also { removed = true }
        }

        val ids = source.fullScan().toList().flatMap { batch -> batch.tracks.map { it.remoteId } }.toSet()

        // Without the check the second page would start at s3, and s2 would be missing (the sync would delete it).
        assertEquals(setOf("s0", "s1", "s2", "s3", "s4"), ids)
    }

    @Test
    fun `full scan aborts instead of losing songs when pages end before the total`() = runTest {
        songPage = { start -> if (start == 0) page(listOf("s0", "s1"), total = 5) else page(emptyList(), total = 5) }

        try {
            source.fullScan().toList()
            fail("The scan must abort, otherwise the sync would delete the missing songs")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `changing a playlist right after creating it waits until the server has settled`() = runTest {
        val created = source.createPlaylist("Summer", listOf("a"))
        val current = RemotePlaylist(created, listOf("a"))

        source.replacePlaylist(current, "Summer 2", listOf("a", "b"))
        assertTrue(testScheduler.currentTime >= JellyfinDataSource.CREATION_SETTLE_MS - 1_000)

        // Only the first change after creation waits.
        val before = testScheduler.currentTime
        source.replacePlaylist(current, "Summer 3", listOf("a"))
        assertEquals(before, testScheduler.currentTime)
    }

    @Test
    fun `playlists ask for rights once and for entries only when the etag changed`() = runTest {
        val first = source.playlists()
        val second = source.playlists()
        playlistEtag = "e2"
        source.playlists()

        assertEquals(listOf("p1"), first.map { it.remoteId })
        assertEquals(first, second)
        val calls = synchronized(requests) { requests.toList() }
        assertEquals(1, calls.count { it == "Playlists/p1/Users/u1" })
        assertEquals(1, calls.count { it == "Playlists/p2/Users/u1" })
        assertEquals(2, calls.count { it == "Playlists/p1" })
    }

    @Test
    fun `own changes fetch the entries again even when the etag stays`() = runTest {
        // Emby keeps the Etag unchanged on reordering; otherwise the remembered entries would count as a change on the server.
        source.replacePlaylist(RemotePlaylist(source.playlists().single(), listOf("a")), "Summer", listOf("a"))
        val afterReplace = synchronized(requests) { requests.size }
        source.playlists()

        val calls = synchronized(requests) { requests.drop(afterReplace) }
        assertEquals(1, calls.count { it == "Playlists/p1" })
    }

    @Test
    fun `cast cover url carries no token, the stream url does`() {
        assertFalse("ApiKey" in source.externalCoverUrl("al1:t1", 300))
        assertTrue("ApiKey=tok" in source.externalStreamUrl("s1", null))
    }

    private fun page(ids: List<String>, total: Int): String =
        """{"Items":[${ids.joinToString { """{"Id":"$it","Name":"$it","Type":"Audio"}""" }}],"TotalRecordCount":$total}"""
}
