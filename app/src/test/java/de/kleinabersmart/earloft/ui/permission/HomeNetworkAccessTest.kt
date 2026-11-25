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
}
