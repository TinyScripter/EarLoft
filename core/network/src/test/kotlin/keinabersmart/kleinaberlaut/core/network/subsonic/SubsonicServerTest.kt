package keinabersmart.kleinaberlaut.core.network.subsonic

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsonicServerTest {

    @Test
    fun `base url gets https and a trailing slash`() {
        assertEquals("https://music.example.com/", SubsonicServer.normalizeBaseUrl("music.example.com")?.toString())
        assertEquals("http://10.0.2.2:4533/", SubsonicServer.normalizeBaseUrl(" http://10.0.2.2:4533 ")?.toString())
    }

    @Test
    fun `sub paths are kept, rest and app suffixes are removed`() {
        assertEquals("https://example.com/navidrome/", SubsonicServer.normalizeBaseUrl("https://example.com/navidrome/")?.toString())
        assertEquals("https://example.com/navidrome/", SubsonicServer.normalizeBaseUrl("https://example.com/navidrome/rest")?.toString())
        assertEquals("https://example.com/", SubsonicServer.normalizeBaseUrl("https://example.com/app/#/album")?.toString())
    }

    @Test
    fun `invalid input is rejected`() {
        assertNull(SubsonicServer.normalizeBaseUrl(""))
        assertNull(SubsonicServer.normalizeBaseUrl("ftp://example.com"))
        assertNull(SubsonicServer.normalizeBaseUrl("http://"))
    }

    @Test
    fun `cast address defaults to http`() {
        assertEquals("http://192.168.0.10:4533/", SubsonicServer.normalizeCastUrl("192.168.0.10:4533")?.toString())
        assertEquals("https://lan.example/nd/", SubsonicServer.normalizeCastUrl("https://lan.example/nd/rest")?.toString())
        assertNull(SubsonicServer.normalizeCastUrl("ftp://lan.example"))
    }

    @Test
    fun `for cast the server uses its cast address if set`() {
        val plain = SubsonicServer("s", "https://music.example.com/".toHttpUrl(), SubsonicCredentials.ApiKey("k"))
        assertEquals(plain, plain.forCast())

        val withCast = plain.copy(castBaseUrl = "http://192.168.0.10:4533/".toHttpUrl())
        val cast = withCast.forCast()
        assertEquals("http://192.168.0.10:4533/rest/stream", cast.endpoint("stream").build().toString())
        assertEquals("s", cast.sourceId)
    }

    @Test
    fun `a server owns only its own rest endpoints`() {
        val server = SubsonicServer(
            sourceId = "s",
            baseUrl = "https://example.com/navidrome/".toHttpUrl(),
            credentials = SubsonicCredentials.ApiKey("k"),
        )
        assertTrue(server.owns("https://example.com/navidrome/rest/stream?id=1".toHttpUrl()))
        assertFalse(server.owns("https://example.com/other/rest/stream?id=1".toHttpUrl()))
        assertFalse(server.owns("http://example.com/navidrome/rest/stream?id=1".toHttpUrl()))
        assertFalse(server.owns("https://example.com:8443/navidrome/rest/stream?id=1".toHttpUrl()))
    }

    @Test
    fun `authenticated url carries a valid token for external receivers`() {
        val server = SubsonicServer("nd", "https://example.com/".toHttpUrl(), SubsonicCredentials.Password("anna", "geheim"))

        val url = server.authenticatedUrl("https://example.com/rest/stream?id=7&format=raw".toHttpUrl())

        assertEquals("7", url.queryParameter("id"))
        assertEquals("anna", url.queryParameter("u"))
        val salt = url.queryParameter("s")!!
        assertEquals(SubsonicAuthInterceptor.md5Hex("geheim$salt"), url.queryParameter("t"))
        assertEquals(SubsonicAuthInterceptor.CLIENT_NAME, url.queryParameter("c"))
        assertNull(url.queryParameter("p"))
    }
}
