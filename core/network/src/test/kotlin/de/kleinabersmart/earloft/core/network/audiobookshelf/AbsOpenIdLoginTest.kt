package de.kleinabersmart.earloft.core.network.audiobookshelf

import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsOpenIdLoginTest {

    private val login = AbsOpenIdLogin(
        baseUrl = "https://abs.example.org".toHttpUrl(),
        redirectUri = "earloft://oauth",
        authorizationUrl = "https://idp.example.org/auth".toHttpUrl(),
        state = "a+b/c=",
        verifier = "v",
        cookies = CookieJar.NO_COOKIES,
    )

    @Test
    fun `the redirect with this sign-in's state answers it`() {
        assertTrue(login.isAnsweredBy("earloft://oauth?code=123&state=a%2Bb%2Fc%3D"))
        assertTrue(login.isAnsweredBy("audiobookshelf://oauth?state=a%2Bb%2Fc%3D&code=123"))
    }

    @Test
    fun `another, a missing or a broken state does not`() {
        assertFalse(login.isAnsweredBy("earloft://oauth?code=123&state=other"))
        assertFalse(login.isAnsweredBy("earloft://oauth?code=123"))
        assertFalse(login.isAnsweredBy("earloft://oauth"))
        assertFalse(login.isAnsweredBy("earloft://oauth?state=%zz"))
        assertFalse(login.isAnsweredBy("not a uri with spaces"))
    }
}
