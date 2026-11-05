package de.kleinabersmart.tinyamp.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteQueueWatchTest {

    private val album = listOf("album:3/song:1", "album:3/song:2", "album:3/song:3")

    @Test
    fun `tracks that show up and vanish again were rejected`() {
        // Am Gerät (Navidrome per HTTPS): angenommen, in ca. 3 s durchgelaufen, danach ohne Titel.
        val watch = RemoteQueueWatch().apply { start(album) }

        assertFalse(watch.onItems(emptyList()))
        assertFalse(watch.onItems(album))
        assertFalse(watch.onItems(album.drop(2)))
        assertTrue(watch.onItems(emptyList()))
        assertFalse(watch.isWatching)
    }

    @Test
    fun `a slow receiver is not a failure`() {
        // Ein Buch von Audiobookshelf: erst nach etwa 9 s gemeldet, dann bleibt es.
        val watch = RemoteQueueWatch().apply { start(listOf("book:7/0")) }

        assertFalse(watch.onItems(emptyList()))
        assertFalse(watch.onItems(listOf("book:7/0")))
        assertFalse(watch.onTimeout(itemCount = 1))
    }

    @Test
    fun `nothing at all until the end of the window is a failure`() {
        val watch = RemoteQueueWatch().apply { start(listOf("book:7/0")) }

        assertFalse(watch.onItems(emptyList()))
        assertTrue(watch.onTimeout(itemCount = 0))
    }

    @Test
    fun `the old queue still in view does not count`() {
        // Direkt nach dem Senden ist kurz noch die Folge von vorher zu sehen, dann wird es leer.
        val watch = RemoteQueueWatch().apply { start(album) }

        assertFalse(watch.onItems(listOf("book:5/0")))
        assertFalse(watch.onItems(emptyList()))
        assertTrue(watch.onTimeout(itemCount = 0))
    }

    @Test
    fun `reloading the same tracks without an empty phase triggers nothing`() {
        val watch = RemoteQueueWatch().apply { start(album) }

        assertFalse(watch.onItems(album))
        assertFalse(watch.onItems(emptyList()))
        assertFalse(watch.onTimeout(itemCount = 1))
    }

    @Test
    fun `after the window it no longer watches`() {
        val watch = RemoteQueueWatch().apply { start(album) }
        watch.onItems(emptyList())
        watch.onItems(album)
        watch.onTimeout(itemCount = 3)

        assertFalse(watch.onItems(emptyList()))
    }
}
