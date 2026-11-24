package de.kleinabersmart.earloft.ui.components

import de.kleinabersmart.earloft.ui.components.PremiumCountdown.Step
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumCountdownTest {

    private fun PremiumCountdown.taps(count: Int, start: Long = 0L, gap: Long = 300L): List<Step> =
        (0 until count).map { tap(start + it * gap) }

    @Test
    fun `two quiet taps, then seven counted down, then the unlock`() {
        val steps = PremiumCountdown(variants = 5, random = Random(1)).taps(10)

        assertEquals(listOf(Step.Quiet, Step.Quiet), steps.take(2))
        assertEquals((7 downTo 1).map { Step.Remaining(it) }, steps.subList(2, 9))
        assertTrue(steps[9] is Step.Unlocked)
    }

    @Test
    fun `a long pause starts over`() {
        val countdown = PremiumCountdown(variants = 5)
        countdown.taps(5)

        assertEquals(Step.Quiet, countdown.tap(1_200L + PremiumCountdown.RESET_AFTER_MS + 1))
    }

    @Test
    fun `a pause right at the limit still counts`() {
        val countdown = PremiumCountdown(variants = 5)
        countdown.taps(3)

        assertEquals(Step.Remaining(6), countdown.tap(600L + PremiumCountdown.RESET_AFTER_MS))
    }

    @Test
    fun `after the unlock the next round starts quiet again`() {
        val countdown = PremiumCountdown(variants = 5)
        countdown.taps(10)

        assertEquals(Step.Quiet, countdown.tap(3_100L))
    }

    @Test
    fun `the same message never comes twice in a row and all of them come up`() {
        val countdown = PremiumCountdown(variants = 5, random = Random(42))
        val variants = (0 until 200).map { round ->
            (countdown.taps(10, start = round * 10_000L).last() as Step.Unlocked).variant
        }

        variants.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        assertEquals((0 until 5).toSet(), variants.toSet())
    }

    @Test
    fun `a single message is always that one`() {
        val countdown = PremiumCountdown(variants = 1)

        assertEquals(Step.Unlocked(0), countdown.taps(10).last())
        assertEquals(Step.Unlocked(0), countdown.taps(10, start = 10_000L).last())
    }
}
