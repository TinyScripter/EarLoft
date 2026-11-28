package de.kleinabersmart.earloft.widget

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.playback.NowPlaying
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The widget's memory refers to IDs of the main database; one created anew hands them out again (review5 P21). */
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class WidgetStateStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val song = NowPlaying(songId = 1, title = "Song", artist = "A", albumId = 10, isPlaying = false)
    private val book = NowPlaying(songId = null, title = "Book", artist = "B", albumId = null, isPlaying = false, bookId = 7)

    @Test
    fun `states from before the main database was created anew are dropped`() = runBlocking {
        var createdAt: Long? = 1_000L
        val store = WidgetStateStore(context) { createdAt }
        store.save(listOf(SavedWidget(song, null, null, lastActiveAt = 2_000), SavedWidget(book, 60_000, 120_000, lastActiveAt = 3_000)))

        assertEquals(setOf(SpokenMode.MUSIC, SpokenMode.BOOKS), store.load().keys)

        // The database was created anew after the music played, but before the book did.
        createdAt = 2_500L
        assertEquals(setOf(SpokenMode.BOOKS), store.load().keys)

        // Unknown creation: everything stays.
        createdAt = null
        assertEquals(2, store.load().size)
    }
}
