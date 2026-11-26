package de.kleinabersmart.earloft.ui.permission

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
}
