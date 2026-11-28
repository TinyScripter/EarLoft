package de.kleinabersmart.earloft.ui.spoken

import de.kleinabersmart.earloft.core.data.spoken.BookDownload
import de.kleinabersmart.earloft.core.download.BookTransfer
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.TrackDownload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cancelling or deleting a book download asks first and names the downloaded size (review5 P8). */
class BookDownloadConfirmationTest {

    @Test
    fun `books ask before cancelling or deleting a download, episodes do not`() {
        assertTrue(bookRemovalNeedsConfirmation(BookKind.BOOK))
        assertFalse(bookRemovalNeedsConfirmation(BookKind.EPISODE))
    }

    @Test
    fun `reported size includes bytes from an active partial transfer`() {
        val running = BookDownload(TrackDownload.RUNNING, bytes = 200_000_000L)
        assertEquals(200_000_000L, bookDownloadBytes(running, transfer = null))
        assertEquals(350_000_000L, bookDownloadBytes(running, BookTransfer(doneBytes = 350_000_000L, totalBytes = 500_000_000L)))
        assertEquals(200_000_000L, bookDownloadBytes(running, BookTransfer(doneBytes = 100_000_000L, totalBytes = 500_000_000L)))
    }
}
