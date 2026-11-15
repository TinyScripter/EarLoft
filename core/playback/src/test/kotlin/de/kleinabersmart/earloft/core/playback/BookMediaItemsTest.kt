package de.kleinabersmart.earloft.core.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import de.kleinabersmart.earloft.core.data.spoken.PreparedBook
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookTimeline
import de.kleinabersmart.earloft.core.model.BookTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookMediaItemsTest {

    private val feedAddress = "https://feeds.example.org/private/s3cr3t/episode.mp3"

    private fun episode(id: Long) = PreparedBook(
        book = Book(id = id, serverId = "feed", kind = BookKind.EPISODE, title = "Folge", author = "Show", durationMs = 60_000, remoteId = "e1"),
        timeline = BookTimeline(60_000, listOf(BookTrack(0, "f0", 0, 60_000, mimeType = "audio/mpeg")), emptyList()),
        trackUrls = listOf(feedAddress),
        startPositionMs = 0,
        resumedFromServer = false,
        speed = 1f,
        castUrls = listOf(feedAddress),
        castCoverUrl = "https://feeds.example.org/cover.jpg",
    )

    @Test
    fun `cast address stays out of the metadata other controllers see`() {
        val item = episode(31).toMediaItems().single()
        val extras = item.mediaMetadata.extras!!

        assertFalse(extras.keySet().any { extras.get(it).toString().contains("s3cr3t") })
        assertEquals(feedAddress, item.spokenCastSource?.url)
        assertEquals("audio/mpeg", item.spokenCastSource?.mimeType)
    }

    @Test
    fun `item that came back through a controller still finds its cast address`() {
        val item = episode(32).toMediaItems().single()
        val returned = MediaItem.fromBundle(item.toBundle())

        assertEquals(feedAddress, returned.spokenCastSource?.url)
    }

    @Test
    fun `cast address set by a controller in the extras is ignored`() {
        val foreign = MediaItem.Builder()
            .setMediaId(BookIds.mediaId(9_999, 0))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setExtras(Bundle().apply { putString("de.kleinabersmart.earloft.CAST_URL", "https://evil.example.org/a.mp3") })
                    .build(),
            )
            .build()

        assertNull(foreign.spokenCastSource)
    }
}
