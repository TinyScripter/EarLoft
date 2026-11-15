package de.kleinabersmart.earloft.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Die beiden Download-Zeilen mit Mehrzahl: „1 von 1 Song“ statt „1 von 1 Songs“ (Englisch und Deutsch). */
@RunWith(RobolectricTestRunner::class)
class DownloadPluralsTest {

    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    @Config(qualifiers = "en")
    fun `english download lines use the singular for one`() {
        assertEquals("1 of 1 song · 3 MB", resources.getQuantityString(R.plurals.downloads_album_summary, 1, 1, 1, "3 MB"))
        assertEquals("2 of 12 songs · 3 MB", resources.getQuantityString(R.plurals.downloads_album_summary, 12, 2, 12, "3 MB"))
        assertEquals("Partly downloaded: 1 of 1 file", resources.getQuantityString(R.plurals.book_download_partial, 1, 1, 1))
        assertEquals("Partly downloaded: 3 of 7 files", resources.getQuantityString(R.plurals.book_download_partial, 7, 3, 7))
    }

    @Test
    @Config(qualifiers = "de")
    fun `german download lines use the singular for one`() {
        assertEquals("1 von 1 Song · 3 MB", resources.getQuantityString(R.plurals.downloads_album_summary, 1, 1, 1, "3 MB"))
        assertEquals("2 von 12 Songs · 3 MB", resources.getQuantityString(R.plurals.downloads_album_summary, 12, 2, 12, "3 MB"))
        assertEquals("Teilweise geladen: 1 von 1 Datei", resources.getQuantityString(R.plurals.book_download_partial, 1, 1, 1))
        assertEquals("Teilweise geladen: 3 von 7 Dateien", resources.getQuantityString(R.plurals.book_download_partial, 7, 3, 7))
    }
}
