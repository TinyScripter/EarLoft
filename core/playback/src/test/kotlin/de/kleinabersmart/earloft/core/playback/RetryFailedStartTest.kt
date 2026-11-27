package de.kleinabersmart.earloft.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** After a permission is granted, only a start that failed is loaded again, nothing the user paused. */
class RetryFailedStartTest {

    @Test
    fun `a start that failed is loaded again`() {
        assertTrue(shouldRetryFailedStart(hasError = true, playWhenReady = true, itemCount = 12))
    }

    @Test
    fun `a paused queue stays paused, even with an error`() {
        assertFalse(shouldRetryFailedStart(hasError = true, playWhenReady = false, itemCount = 12))
    }

    @Test
    fun `without an error or without titles nothing happens`() {
        assertFalse(shouldRetryFailedStart(hasError = false, playWhenReady = true, itemCount = 12))
        assertFalse(shouldRetryFailedStart(hasError = true, playWhenReady = true, itemCount = 0))
    }
}
