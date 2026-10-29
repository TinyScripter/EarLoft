package keinabersmart.kleinaberlaut.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CrownStepperTest {

    /** Die gemessene langsame Rastung der Pixel Watch 3: zehn Ereignisse, zusammen rund 34 px. */
    private val slowDetent = listOf(1.77f, 1.77f, 1.77f, 3.53f, 5.30f, 5.30f, 7.07f, 3.53f, 1.77f, 1.77f)

    @Test
    fun `eine langsame Rastung ist genau ein Schritt`() {
        val stepper = CrownStepper(pixelsPerStep = 30f)
        assertEquals(1, slowDetent.sumOf { stepper.add(it) })
    }

    @Test
    fun `kleine Ereignisse allein lösen noch keinen Schritt aus`() {
        val stepper = CrownStepper(pixelsPerStep = 35f)
        assertEquals(0, stepper.add(1.77f) + stepper.add(1.77f) + stepper.add(3.53f))
    }

    @Test
    fun `der Rest bleibt für die nächste Drehung erhalten`() {
        val stepper = CrownStepper(pixelsPerStep = 35f)
        assertEquals(0, stepper.add(20f))
        assertEquals(1, stepper.add(20f))
        assertEquals(1, stepper.add(30f))
    }

    @Test
    fun `schnelles Drehen gibt mehrere Schritte`() {
        val stepper = CrownStepper(pixelsPerStep = 35f)
        assertEquals(3, stepper.add(110f))
    }

    @Test
    fun `negative Drehung gibt negative Schritte`() {
        val stepper = CrownStepper(pixelsPerStep = 35f)
        assertEquals(-2, stepper.add(-75f))
    }

    @Test
    fun `ein Richtungswechsel verwirft den Rest`() {
        val stepper = CrownStepper(pixelsPerStep = 35f)
        assertEquals(0, stepper.add(30f))
        assertEquals(0, stepper.add(-30f))
        assertEquals(-1, stepper.add(-10f))
    }
}
