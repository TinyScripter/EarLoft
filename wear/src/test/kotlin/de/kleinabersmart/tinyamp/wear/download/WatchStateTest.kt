package de.kleinabersmart.tinyamp.wear.download

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WatchStateTest {

    private val state = WatchState(ApplicationProvider.getApplicationContext())

    @Test
    fun `a song switches to the phone after three server failures`() {
        assertFalse(state.hasServerFailedTooOften("nd:1"))
        assertEquals(1, state.recordServerFailure("nd:1"))
        assertEquals(2, state.recordServerFailure("nd:1"))
        assertFalse(state.hasServerFailedTooOften("nd:1"))
        assertEquals(3, state.recordServerFailure("nd:1"))
        assertTrue(state.hasServerFailedTooOften("nd:1"))
    }

    @Test
    fun `an unreachable server counts for all waiting songs of it`() {
        state.recordServerFailure("nd:1")
        state.recordServerFailures(listOf("nd:2", "nd:3"))
        state.recordServerFailures(listOf("nd:2", "nd:3"))

        assertFalse(state.hasServerFailedTooOften("nd:1"))
        assertFalse(state.hasServerFailedTooOften("nd:2"))
        assertEquals(3, state.recordServerFailure("nd:2"))
        assertTrue(state.hasServerFailedTooOften("nd:2"))
    }

    @Test
    fun `a finished download clears the failures`() {
        repeat(3) { state.recordServerFailure("nd:1") }

        state.clearServerFailures("nd:1")

        assertFalse(state.hasServerFailedTooOften("nd:1"))
        assertEquals(1, state.recordServerFailure("nd:1"))
    }
}
