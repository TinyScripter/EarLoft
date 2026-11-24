package de.kleinabersmart.earloft.core.network

import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServer
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicCredentials
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicServer
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicServerRegistry
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialScopeTest {

    private val base = "https://music.example.org/jellyfin/".toHttpUrl()

    @Test
    fun `same server keeps the credentials`() {
        assertTrue("https://music.example.org/jellyfin/Items".toHttpUrl().acceptsCredentialsOf(base))
        assertTrue("https://music.example.org/other".toHttpUrl().acceptsCredentialsOf(base))
    }

    @Test
    fun `other host or port gets no credentials`() {
        assertFalse("https://auth.example.org/login".toHttpUrl().acceptsCredentialsOf(base))
        assertFalse("https://music.example.org:8443/jellyfin/Items".toHttpUrl().acceptsCredentialsOf(base))
    }

    @Test
    fun `upgrade to https is allowed, downgrade is not`() {
        val plain = "http://192.168.1.5:4533/".toHttpUrl()
        assertTrue("https://192.168.1.5/rest/ping".toHttpUrl().acceptsCredentialsOf(plain))
        assertFalse("http://music.example.org/jellyfin/Items".toHttpUrl().acceptsCredentialsOf(base))
    }

    @Test
    fun `two accounts on one host each get their own requests`() {
        val plain = SubsonicServer("plain", "http://nas.local:4533/".toHttpUrl(), SubsonicCredentials.ApiKey("a"))
        val secure = SubsonicServer("secure", "https://nas.local/".toHttpUrl(), SubsonicCredentials.ApiKey("b"))
        val subsonic = SubsonicServerRegistry().apply { replaceAll(listOf(plain, secure)) }
        assertEquals("secure", subsonic.forUrl("https://nas.local/rest/stream".toHttpUrl())?.sourceId)
        assertEquals("plain", subsonic.forUrl("http://nas.local:4533/rest/stream".toHttpUrl())?.sourceId)

        val abs = AbsServerRegistry().apply {
            replaceAll(listOf(AbsServer("plain", "http://nas.local:13378/".toHttpUrl(), "a"), AbsServer("secure", "https://nas.local/".toHttpUrl(), "b")))
        }
        assertEquals("secure", abs.forUrl("https://nas.local/api/me".toHttpUrl())?.serverId)
        assertEquals("plain", abs.forUrl("http://nas.local:13378/api/me".toHttpUrl())?.serverId)
    }

    @Test
    fun `after a redirect to https the only server on the host keeps its requests`() {
        val plain = SubsonicServer("plain", "http://nas.local:4533/".toHttpUrl(), SubsonicCredentials.ApiKey("a"))
        val subsonic = SubsonicServerRegistry().apply { replaceAll(listOf(plain)) }
        assertEquals("plain", subsonic.forUrl("https://nas.local/rest/stream".toHttpUrl())?.sourceId)
    }

    @Test
    fun `an edited address keeps the stored credentials only on the same host`() {
        assertTrue("https://music.example.org/emby/".toHttpUrl().keepsCredentialsOf(base.toString()))
        assertTrue("https://192.168.1.5/".toHttpUrl().keepsCredentialsOf("http://192.168.1.5:4533/"))
        assertFalse("https://music.exmaple.org/jellyfin/".toHttpUrl().keepsCredentialsOf(base.toString()))
        assertFalse("http://music.example.org/jellyfin/".toHttpUrl().keepsCredentialsOf(base.toString()))
        assertFalse(base.keepsCredentialsOf(null))
    }
}
