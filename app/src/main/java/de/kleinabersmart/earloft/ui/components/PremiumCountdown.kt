package de.kleinabersmart.earloft.ui.components

import kotlin.random.Random

/**
 * Counts taps on the logo like the build number in the system's "About phone": the first taps stay quiet, then a
 * countdown runs to the "premium version", which turns out to be what everyone always had. A pause of more than
 * [RESET_AFTER_MS] starts over, so occasional taps never get there. Nothing is stored; the joke can be repeated.
 */
class PremiumCountdown(private val variants: Int, private val random: Random = Random.Default) {

    sealed interface Step {
        /** Only the animation. */
        data object Quiet : Step

        /** [taps] more taps until the unlock. */
        data class Remaining(val taps: Int) : Step

        /** Reached; [variant] is the message to show, never the same as the previous unlock. */
        data class Unlocked(val variant: Int) : Step
    }

    private var streak = 0
    private var lastTapAt = Long.MIN_VALUE
    private var lastVariant = -1

    init {
        require(variants > 0)
    }

    /** One tap at [nowMillis] (a monotonic clock). */
    fun tap(nowMillis: Long): Step {
        if (lastTapAt == Long.MIN_VALUE || nowMillis - lastTapAt > RESET_AFTER_MS) streak = 0
        lastTapAt = nowMillis
        streak++
        if (streak <= QUIET_TAPS) return Step.Quiet
        val remaining = QUIET_TAPS + TAPS_TO_UNLOCK + 1 - streak
        if (remaining > 0) return Step.Remaining(remaining)
        streak = 0
        val variant = if (variants == 1) 0 else {
            // Draw among the others, so the same message never comes twice in a row.
            val drawn = random.nextInt(if (lastVariant < 0) variants else variants - 1)
            if (lastVariant in 0..drawn) drawn + 1 else drawn
        }
        lastVariant = variant
        return Step.Unlocked(variant)
    }

    companion object {
        /** Taps that only animate before the countdown shows up. */
        const val QUIET_TAPS = 2

        /** The countdown announced on the first counted tap. */
        const val TAPS_TO_UNLOCK = 7

        const val RESET_AFTER_MS = 5_000L
    }
}
