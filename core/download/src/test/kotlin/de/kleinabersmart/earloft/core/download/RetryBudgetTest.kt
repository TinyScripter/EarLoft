package de.kleinabersmart.earloft.core.download

import de.kleinabersmart.earloft.core.network.HttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class RetryBudgetTest {

    private val interrupted = DownloadOutcome.Retry(IOException("Verbindung abgebrochen"))

    @Test
    fun `before the budget is used up every interrupted file waits for the next run`() {
        val budget = RetryBudget(runAttemptCount = 3, maxAttempts = 5)

        assertEquals(listOf(false, false), List(2) { budget.giveUp(interrupted) })
    }

    @Test
    fun `with the budget used up only the first file of a run gives up, the others wait`() {
        val budget = RetryBudget(runAttemptCount = 4, maxAttempts = 5)

        assertEquals(listOf(true, false, false), List(3) { budget.giveUp(interrupted) })
        // The next run may give up on one again.
        assertEquals(true, RetryBudget(runAttemptCount = 5, maxAttempts = 5).giveUp(interrupted))
    }

    @Test
    fun `a rejected key gives up at once, every time`() {
        val budget = RetryBudget(runAttemptCount = 0, maxAttempts = 5)
        val rejected = DownloadOutcome.Retry(HttpStatusException(401))

        assertEquals(listOf(true, true), List(2) { budget.giveUp(rejected) })
    }
}
