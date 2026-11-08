package de.kleinabersmart.earloft.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
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
}
