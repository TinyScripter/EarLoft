package de.kleinabersmart.earloft.ui.snackbar

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
    fun `a withdrawn message disappears, waiting or visible`() = runTest {
        start()
        val hint = SnackbarMessage("New at the start", actionLabel = "Show", onAction = {}, keep = true)
        queue.offer(kept("Import failed"))
        runCurrent()
        queue.offer(hint)
        queue.withdraw(hint)
        assertEquals(emptyList<SnackbarMessage>(), queue.pending)

        expire()
        queue.offer(hint)
        runCurrent()
        assertEquals("New at the start", shown)
        // review3 Q25: its list is gone, "Show" would do nothing.
        queue.withdraw(hint)
        runCurrent()
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

    @Test
    fun `a message its list withdraws while the root goes away does not come back, in either order`() = runTest {
        val hint = SnackbarMessage("New at the start", actionLabel = "Show", onAction = {}, keep = true)

        // The list's withdrawal arrives first, the root ends before the dismissal is dispatched: like on the main thread, where
        // the cancellation then wins over the dismissal.
        var root = backgroundScope.launch(StandardTestDispatcher(testScheduler)) { queue.run() }
        queue.offer(hint)
        runCurrent()
        assertEquals("New at the start", shown)
        queue.withdraw(hint)
        root.cancel()
        runCurrent()
        assertEquals(emptyList<SnackbarMessage>(), queue.pending)

        // The root's end first, then the withdrawal.
        root = backgroundScope.launch(StandardTestDispatcher(testScheduler)) { queue.run() }
        queue.offer(hint)
        runCurrent()
        assertEquals("New at the start", shown)
        root.cancel()
        runCurrent()
        queue.withdraw(hint)
        assertEquals(emptyList<SnackbarMessage>(), queue.pending)

        // The next root shows the new list's live hint, not a dead one blocking it (review4 8.6 Y5).
        val live = SnackbarMessage("New at the start", actionLabel = "Show", onAction = {}, keep = true)
        start()
        queue.offer(live)
        runCurrent()
        assertEquals("New at the start", shown)
        assertEquals(emptyList<SnackbarMessage>(), queue.pending)
    }

    @Test
    fun `a message that was visible comes back in the next UI root, a dismissed one does not`() = runTest {
        val firstRoot = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { queue.run() }
        queue.offer(kept("Passwords are missing"))
        queue.offer(SnackbarMessage("Refreshed"))
        runCurrent()
        // Rotation: the root is gone, its snackbar with it (review T31).
        firstRoot.cancelAndJoin()
        assertNull(shown)

        start()
        runCurrent()
        assertEquals("Passwords are missing", shown)
        expire()
        assertEquals("Refreshed", shown)
        expire()
        assertNull(shown)
    }

    @Test
    fun `onDismissed runs on expiry or replacement, not on action, withdrawal, interruption or rotation`() = runTest {
        var expiredDismissed = 0
        var replacedDismissed = 0
        var actionedDismissed = 0
        var actioned = 0
        var withdrawnDismissed = 0
        var keptDismissed = 0
        var rotatedDismissed = 0

        val firstRoot = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { queue.run() }

        // Expiry invokes onDismissed.
        queue.offer(SnackbarMessage("First", actionLabel = "Undo", onAction = {}, onDismissed = { expiredDismissed++ }))
        runCurrent()
        expire()
        assertEquals(1, expiredDismissed)

        // Replacement invokes onDismissed on the replaced message.
        queue.offer(SnackbarMessage("Second", actionLabel = "Undo", onAction = {}, onDismissed = { replacedDismissed++ }))
        runCurrent()
        queue.offer(SnackbarMessage("Third", actionLabel = "Undo", onAction = { actioned++ }, onDismissed = { actionedDismissed++ }))
        runCurrent()
        assertEquals(1, replacedDismissed)

        // ActionPerformed invokes onAction, not onDismissed.
        host.currentSnackbarData?.performAction()
        runCurrent()
        assertEquals(1, actioned)
        assertEquals(0, actionedDismissed)

        // Withdrawal does not invoke onDismissed.
        val withdrawn = SnackbarMessage("Withdrawn", actionLabel = "Undo", onAction = {}, onDismissed = { withdrawnDismissed++ })
        queue.offer(withdrawn)
        runCurrent()
        queue.withdraw(withdrawn)
        runCurrent()
        assertEquals(0, withdrawnDismissed)

        // A kept message interrupted by an urgent undo does not invoke onDismissed until it actually finishes.
        queue.offer(SnackbarMessage("Kept", duration = SnackbarDuration.Indefinite, keep = true, onDismissed = { keptDismissed++ }))
        runCurrent()
        queue.offer(undo("Urgent"))
        runCurrent()
        assertEquals(0, keptDismissed)
        expire() // urgent expires, kept returns
        assertEquals("Kept", shown)
        assertEquals(0, keptDismissed)
        expire() // kept finishes
        assertEquals(1, keptDismissed)

        // Rotation (root cancellation) does not invoke onDismissed; the next root finishes it.
        queue.offer(SnackbarMessage("Rotate", actionLabel = "Undo", onAction = {}, onDismissed = { rotatedDismissed++ }))
        runCurrent()
        firstRoot.cancelAndJoin()
        assertEquals(0, rotatedDismissed)

        start()
        runCurrent()
        assertEquals("Rotate", shown)
        expire()
        assertEquals(1, rotatedDismissed)
    }
}
