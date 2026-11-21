package de.kleinabersmart.earloft

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServer
import de.kleinabersmart.earloft.debug.AbsTokenTestEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.CacheControl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An expired OpenID access token is renewed by the refresh token, without a new sign-in (review H3, concept 12.11). Uses the
 * debug broadcast `EXPIRE_ABS_TOKEN` instead of waiting for the real expiry. Needs an Audiobookshelf server signed in via
 * OpenID on the device; without one the tests are skipped. Each run rotates that server's refresh token.
 */
@RunWith(AndroidJUnit4::class)
class AbsTokenExpiryTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = EntryPointAccessors.fromApplication(context, AbsTokenTestEntryPoint::class.java)
    private val servers = app.tokenTestServers()
    private val registry = app.tokenTestRegistry()
    private lateinit var serverId: String

    @Before
    fun setUp() = runBlocking {
        val openId = servers.servers.first().filter { it.openId && it.enabled }.firstNotNullOfOrNull { servers.server(it.serverId) }
        assumeTrue("No Audiobookshelf server signed in via OpenID", openId != null && AbsAuthApi.expiresAt(openId.token) != null)
        serverId = openId!!.serverId
    }

    /** An API call that carries the expired token itself gets 401, renews and is repeated. */
    @Test
    fun apiCallRenewsAnExpiredToken() = runBlocking {
        val expired = expire()
        app.tokenTestApi().me(expired)
        assertRenewed(expired)
    }

    /** Streams, downloads and covers carry no header of their own; the interceptor adds the expired one (review H3). */
    @Test
    fun fileRequestRenewsAnExpiredToken() = runBlocking {
        val expired = expire()
        val request = Request.Builder()
            .url(expired.endpoint("api/me").build())
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        val code = withContext(Dispatchers.IO) { app.tokenTestClient().newCall(request).execute().use { it.code } }
        assertEquals(200, code)
        assertRenewed(expired)
    }

    /** Sends the broadcast as the shell does and waits until the registry holds the invalid token. */
    private suspend fun expire(): AbsServer {
        val before = registry[serverId]!!.token
        shell("am broadcast -a ${context.packageName}.EXPIRE_ABS_TOKEN -p ${context.packageName}")
        return withTimeout(10_000) {
            while (registry[serverId]?.token != before + "expired") delay(100)
            registry[serverId]!!
        }
    }

    private suspend fun assertRenewed(expired: AbsServer) {
        val current = registry[serverId]!!.token
        assertNotEquals("Token not renewed", expired.token, current)
        assertFalse("Still the invalidated token", current.endsWith("expired"))
        val expiresAt = AbsAuthApi.expiresAt(current)
        assertTrue("Renewed token already expired: $expiresAt", expiresAt != null && expiresAt > System.currentTimeMillis() / 1000)
        assertFalse("Sign-in lost", serverId in servers.serversWithoutKey.first())
    }

    private fun shell(command: String) {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }
}
