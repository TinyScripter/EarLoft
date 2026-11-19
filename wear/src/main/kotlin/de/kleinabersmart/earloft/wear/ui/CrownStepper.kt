package de.kleinabersmart.earloft.wear.ui

/**
 * Turns the crown's events into whole steps. The crown delivers many small pixel values per detent
 * (measured on the Pixel Watch 3: about 10 events totalling around 34 px, more when turning fast); taking each one
 * as a step would be far too coarse. The remainder below [pixelsPerStep] is kept for the next turn,
 * a change of direction discards it.
 */
class CrownStepper(private val pixelsPerStep: Float) {
    private var remainder = 0f

    /** @return whole steps, positive in the "louder" direction, negative the other way; 0 as long as the threshold is not reached. */
    fun add(pixels: Float): Int {
        if (pixels * remainder < 0f) remainder = 0f
        remainder += pixels
        val steps = (remainder / pixelsPerStep).toInt()
        remainder -= steps * pixelsPerStep
        return steps
    }
}
