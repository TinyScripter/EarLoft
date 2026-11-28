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
    fun `states saved with an earlier main database are dropped`() = runBlocking {
        var databaseId: String? = "1000"
        val store = WidgetStateStore(context) { databaseId }
        // Stamped later than the new database's creation (re-saved after a restart, or the clock): the database still tells.
        store.save(listOf(SavedWidget(song, null, null, lastActiveAt = 4_000)))
        assertEquals(setOf(SpokenMode.MUSIC), store.load().keys)

        // The database was created anew after the music played, and the book played since.
        databaseId = "2500"
        store.save(listOf(SavedWidget(book, 60_000, 120_000, lastActiveAt = 3_000)))
        assertEquals(setOf(SpokenMode.BOOKS), store.load().keys)
    }

    @Test
    fun `states without a known database go by time`() = runBlocking {
        var databaseId: String? = null
        val store = WidgetStateStore(context) { databaseId }
        store.save(listOf(SavedWidget(song, null, null, lastActiveAt = 2_000)))

        // Only the music section: the store's file outlives a single test.
        databaseId = "2500"
        assertEquals("from before the database", null, store.load()[SpokenMode.MUSIC])
        databaseId = "1500"
        assertEquals("Song", store.load()[SpokenMode.MUSIC]?.nowPlaying?.title)
    }
}
