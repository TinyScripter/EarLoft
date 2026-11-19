package de.kleinabersmart.earloft.cast

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlayerTransferState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What goes back to the phone after "Stop casting" when the app reattached to the session after a restart. */
@RunWith(RobolectricTestRunner::class)
class CastTransferTest {

    private fun fromReceiver(mediaId: String) = MediaItem.Builder().setMediaId(mediaId).build()

    private fun onPhone(mediaId: String) = MediaItem.Builder().setMediaId(mediaId).setUri("https://example.org/$mediaId").build()

    private fun state(items: List<MediaItem>, index: Int) =
        PlayerTransferState.Builder().setMediaItems(items).setCurrentMediaItemIndex(index).setCurrentPosition(90_000).build()

    @Test
    fun `episodes without an address on the phone are rebuilt and keep their position`() {
        val back = state(listOf(fromReceiver("book:42/0"), fromReceiver("book:42/1")), index = 1)
            .playableOnPhone { items -> items.map { onPhone(it.mediaId) } }

        assertEquals(listOf("book:42/0", "book:42/1"), back.mediaItems.map { it.mediaId })
        assertEquals("https://example.org/book:42/1", back.mediaItems[1].localConfiguration?.uri.toString())
        assertEquals(1, back.currentMediaItemIndex)
        assertEquals(90_000L, back.currentPosition)
    }

    @Test
    fun `what cannot be rebuilt is dropped and the next one plays from the start`() {
        val items = listOf(onPhone("song:1"), fromReceiver("book:99/0"), fromReceiver("book:42/0"))
        val back = state(items, index = 1)
            .playableOnPhone { list -> list.map { if (it.mediaId == "book:42/0") onPhone(it.mediaId) else it } }

        assertEquals(listOf("song:1", "book:42/0"), back.mediaItems.map { it.mediaId })
        assertEquals(1, back.currentMediaItemIndex)
        assertEquals(0L, back.currentPosition)
    }

    @Test
    fun `nothing playable leaves an empty queue`() {
        val back = state(listOf(fromReceiver("book:99/0")), index = 0).playableOnPhone { it }

        assertEquals(emptyList<MediaItem>(), back.mediaItems)
        assertEquals(C.INDEX_UNSET, back.currentMediaItemIndex)
    }
}
