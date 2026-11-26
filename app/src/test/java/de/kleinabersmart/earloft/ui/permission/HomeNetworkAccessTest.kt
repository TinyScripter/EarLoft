package de.kleinabersmart.earloft.ui.permission

import org.junit.Assert.assertEquals
import org.junit.Test

/** When the app has to ask for the home network permission on its own (review4 T5). */
class HomeNetworkAccessTest {

    @Test
    fun `a server or feed on the home network needs the permission`() {
        assertEquals(true, needsHomeNetworkAccess(listOf("https://music.example.com", "http://192.168.0.140:8096")))
        assertEquals(true, needsHomeNetworkAccess(listOf("http://10.0.0.5/feed.xml")))
        assertEquals(true, needsHomeNetworkAccess(listOf("http://nas:4533")))
        assertEquals(true, needsHomeNetworkAccess(listOf("https://media.fritz.box/rss")))
    }

    @Test
    fun `public addresses and nothing at all need no permission`() {
        assertEquals(false, needsHomeNetworkAccess(emptyList()))
        assertEquals(false, needsHomeNetworkAccess(listOf("https://jellyfin.example.com", "https://feeds.example.org/show.xml")))
        assertEquals(false, needsHomeNetworkAccess(listOf("not an address")))
    }

    @Test
    fun `the device itself needs no permission, carrier-grade NAT does`() {
        // review4 Y22: Android counts 100.64/10 as local network, the loopback address not.
        assertEquals(false, needsHomeNetworkAccess(listOf("http://127.0.0.1:4533", "http://localhost:8096", "http://[::1]:13378")))
        assertEquals(true, needsHomeNetworkAccess(listOf("http://100.101.102.103:4533")))
    }
}
