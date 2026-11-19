package de.kleinabersmart.earloft.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CrownStepperTest {

    /** The measured slow detent of the Pixel Watch 3: ten events, about 34 px in total. */
    private val slowDetent = listOf(1.77f, 1.77f, 1.77f, 3.53f, 5.30f, 5.30f, 7.07f, 3.53f, 1.77f, 1.77f)

    @Test
    fun `a slow detent is exactly one step`() {
        val stepper = CrownStepper(pixelsPerStep = 30f)
        assertEquals(1, slowDetent.sumOf { stepper.add(it) })
    }

    @Test
    fun `small events alone do not trigger a step yet`() {
        val stepper = CrownStepper(pixelsPerStep = 35f)
        assertEquals(0, stepper.add(1.77f) + stepper.add(1.77f) + stepper.add(3.53f))
    }

    @Test
    fun `the remainder is kept for the next rotation`() {
        val stepper = CrownStepper(pixelsPerStep = 35f)
        assertEquals(0, stepper.add(20f))
        assertEquals(1, stepper.add(20f))
        assertEquals(1, stepper.add(30f))
    }

    @Test
    fun `fast rotation gives several steps`() {
        val stepper = CrownStepper(pixelsPerStep = 35f)
        assertEquals(3, stepper.add(110f))
    }

    @Test
    fun `negative rotation gives negative steps`() {
        val stepper = CrownStepper(pixelsPerStep = 35f)
        assertEquals(-2, stepper.add(-75f))
    }

    @Test
    fun `a change of direction discards the remainder`() {
        val stepper = CrownStepper(pixelsPerStep = 35f)
        assertEquals(0, stepper.add(30f))
        assertEquals(0, stepper.add(-30f))
        assertEquals(-1, stepper.add(-10f))
    }
}
