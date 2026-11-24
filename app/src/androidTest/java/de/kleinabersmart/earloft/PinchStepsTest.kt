package de.kleinabersmart.earloft

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.kleinabersmart.earloft.ui.library.pinchSteps
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two-finger gesture of the album grid ([pinchSteps]): spreading gives one step up, pinching one step down, once per
 * gesture; one finger still scrolls the list underneath.
 */
@RunWith(AndroidJUnit4::class)
class PinchStepsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun spreadingAndPinchingStepOncePerGestureWhileOneFingerScrolls() {
        val steps = mutableListOf<Int>()
        rule.setContent {
            Box(Modifier.fillMaxSize().testTag("area").pinchSteps { steps += it }) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(100) { Text("Row $it", Modifier.height(48.dp)) }
                }
            }
        }
        val area = rule.onNodeWithTag("area")

        area.performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(320f, 0f), center + Offset(40f, 0f), center + Offset(320f, 0f))
        }
        rule.runOnIdle { assertEquals(listOf(1), steps) }

        area.performTouchInput {
            pinch(center - Offset(320f, 0f), center - Offset(40f, 0f), center + Offset(320f, 0f), center + Offset(40f, 0f))
        }
        rule.runOnIdle { assertEquals(listOf(1, -1), steps) }

        area.performTouchInput { swipeUp() }
        rule.onNodeWithText("Row 0").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf(1, -1), steps) }
    }
}
