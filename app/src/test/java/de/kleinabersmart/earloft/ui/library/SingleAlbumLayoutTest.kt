package de.kleinabersmart.earloft.ui.library

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** When the record crate and the carousel show the list instead (review4 T56, 8.6 Y27). */
class SingleAlbumLayoutTest {

    @Test
    fun `a snackbar does not switch a small phone to the list for good`() {
        // 450 dp for the crate; a snackbar takes 70 dp of it, then goes again.
        val during = singleAlbumAsList(380.dp, snackbar = 70.dp, asList = false, scrolledAway = false)
        val after = singleAlbumAsList(450.dp, snackbar = 0.dp, asList = during, scrolledAway = false)

        assertEquals(false, during)
        assertEquals(false, after)
    }

    @Test
    fun `a scrolled list goes back to the crate only with room to spare`() {
        assertEquals(true, singleAlbumAsList(380.dp, snackbar = 0.dp, asList = false, scrolledAway = false))
        // Header collapsed and mini player hidden: the list has room the crate wouldn't have.
        assertEquals(true, singleAlbumAsList(500.dp, snackbar = 0.dp, asList = true, scrolledAway = true))
        assertEquals(false, singleAlbumAsList(560.dp, snackbar = 0.dp, asList = true, scrolledAway = true))
    }

    @Test
    fun `the crate comes back once the mini player that pushed it to the list goes again`() {
        // 430 dp for the crate; the mini player appearing after "Play" takes 70 dp, then is dismissed.
        val withMiniPlayer = singleAlbumAsList(360.dp, snackbar = 0.dp, asList = false, scrolledAway = false)
        val after = singleAlbumAsList(430.dp, snackbar = 0.dp, asList = withMiniPlayer, scrolledAway = false)

        assertEquals(true, withMiniPlayer)
        assertEquals(false, after)
    }
}
