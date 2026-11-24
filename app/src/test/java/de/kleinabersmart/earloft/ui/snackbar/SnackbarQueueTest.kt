package de.kleinabersmart.earloft.ui.snackbar

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SnackbarQueueTest {

    private val host = SnackbarHostState()
    private val queue = SnackbarQueue(host)

    private val shown get() = host.currentSnackbarData?.visuals?.message

    private fun TestScope.start() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { queue.run() }
    }

    /** Like the timer of the snackbar running out, or the user closing it. */
    private fun TestScope.expire() {
        host.currentSnackbarData?.dismiss()
        runCurrent()
    }

    private fun kept(text: String) = SnackbarMessage(text, duration = SnackbarDuration.Indefinite, keep = true)

    private fun undo(text: String) = SnackbarMessage(text, actionLabel = "Undo", onAction = {})

    @Test
    fun `a new message replaces one that isn't kept`() = runTest {
        start()
        queue.offer(SnackbarMessage("Refreshing"))
        runCurrent()
        queue.offer(SnackbarMessage("3 new episodes"))
        runCurrent()

        assertEquals("3 new episodes", shown)
    }

    @Test
    fun `other messages wait behind a kept one`() = runTest {
        start()
        queue.offer(kept("The VPN keeps the receiver away"))
        runCurrent()
        queue.offer(SnackbarMessage("Refreshed"))
        runCurrent()

        assertEquals("The VPN keeps the receiver away", shown)
        expire()
        assertEquals("Refreshed", shown)
    }

    @Test
    fun `undo goes before a kept message, which comes back after it`() = runTest {
        start()
        queue.offer(kept("The VPN keeps the receiver away"))
        runCurrent()
        queue.offer(SnackbarMessage("Refreshed"))
        queue.offer(undo("Removed from the queue"))
        runCurrent()

        // review3 Q3: the undo waited invisibly until the kept hint was closed.
        assertEquals("Removed from the queue", shown)
        expire()
        assertEquals("The VPN keeps the receiver away", shown)
        expire()
        assertEquals("Refreshed", shown)
        expire()
        assertNull(shown)
    }

    @Test
    fun `a kept message already shown or waiting is not queued again`() = runTest {
        start()
        val hint = SnackbarMessage("New at the start", actionLabel = "Show", onAction = {}, keep = true)
        queue.offer(kept("Import failed"))
        runCurrent()
        repeat(3) { queue.offer(hint) }
        queue.offer(kept("Import failed"))

        assertEquals(listOf(hint), queue.pending)
    }
}
