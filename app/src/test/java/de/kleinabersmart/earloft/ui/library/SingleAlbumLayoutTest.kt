package de.kleinabersmart.earloft.ui.library

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** When the record crate and the carousel show the list instead (review4 T56, 8.6 Y27). */
class SingleAlbumLayoutTest {

    @Test
    fun `a snackbar does not switch a small phone to the list for good`() {
        // 450 dp for the crate; a snackbar takes 70 dp of it, then goes again.
        val during = singleAlbumAsList(380.dp, snackbar = 70.dp, asList = false)
        val after = singleAlbumAsList(450.dp, snackbar = 0.dp, asList = during)

        assertEquals(false, during)
        assertEquals(false, after)
    }

    @Test
    fun `too low switches to the list and back only with room to spare`() {
        assertEquals(true, singleAlbumAsList(380.dp, snackbar = 0.dp, asList = false))
        assertEquals(true, singleAlbumAsList(500.dp, snackbar = 0.dp, asList = true))
        assertEquals(false, singleAlbumAsList(560.dp, snackbar = 0.dp, asList = true))
    }
}
