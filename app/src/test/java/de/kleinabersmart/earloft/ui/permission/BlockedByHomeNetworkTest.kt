package de.kleinabersmart.earloft.ui.permission

import de.kleinabersmart.earloft.core.data.sync.SyncFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** review4 T5: without the permission a home network server only times out; the sync error has to say why. */
class BlockedByHomeNetworkTest {

    @Test
    fun `an unreachable home network server without the permission is blocked by it`() {
        assertTrue(blockedByHomeNetwork(unreachable = true, address = "http://192.168.0.140:8096", granted = false))
    }

    @Test
    fun `with the permission it is just unreachable`() {
        assertFalse(blockedByHomeNetwork(unreachable = true, address = "http://192.168.0.140:8096", granted = true))
    }

    @Test
    fun `a server on the internet does not need the permission`() {
        assertFalse(blockedByHomeNetwork(unreachable = true, address = "https://music.example.com", granted = false))
    }

    @Test
    fun `other errors such as wrong credentials keep their own text`() {
        assertFalse(blockedByHomeNetwork(unreachable = false, address = "http://192.168.0.140:8096", granted = false))
    }

    @Test
    fun `loopback does not need the permission`() {
        assertFalse(blockedByHomeNetwork(unreachable = true, address = "http://127.0.0.1:8765/feed.xml", granted = false))
    }

    @Test
    fun `once granted the home network targets that did not get through are synced again`() {
        val targets = listOf(
            "home-failed" to "http://192.168.0.140:8096",
            "home-wrong-password" to "http://10.0.2.2:8096",
            "internet-failed" to "https://music.example.com",
        )
        val failures = mapOf(
            "home-failed" to SyncFailure.Unreachable("timeout"),
            "home-wrong-password" to SyncFailure.WrongCredentials,
            "internet-failed" to SyncFailure.Unreachable("timeout"),
        )
        assertEquals(listOf("home-failed"), failedAtHome(targets, failures))
    }

    @Test
    fun `a home network sync still running at the grant is synced again`() {
        // Started next to the prompt, before the grant: it fails only once its timeout has run out, so nothing is recorded yet.
        val targets = listOf("home-syncing" to "http://192.168.0.141:4533", "internet-syncing" to "https://music.example.com")

        assertEquals(listOf("home-syncing"), failedAtHome(targets, emptyMap()))
    }
}
