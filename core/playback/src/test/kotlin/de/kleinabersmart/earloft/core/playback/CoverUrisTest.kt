package de.kleinabersmart.earloft.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CoverUrisTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `provider uris carry album, book and feed ids`() {
        assertEquals(7L, CoverProvider.albumId(CoverProvider.albumCoverUri(context, 7)))
        assertEquals(8L, CoverProvider.bookId(CoverProvider.bookCoverUri(context, 8)))
        assertEquals("local:Podcasts/Show", CoverProvider.feedId(CoverProvider.feedCoverUri(context, "local:Podcasts/Show")))
        assertNull(CoverProvider.albumId(CoverProvider.bookCoverUri(context, 8)))
    }

    @Test
    fun `queue covers fall back to the app uri without a running provider`() {
        assertEquals("earloft://cover/album/5", CoverProvider.queueCoverUri(5).toString())
        assertEquals("earloft://cover/book/6", CoverProvider.queueBookCoverUri(6).toString())
    }
}
