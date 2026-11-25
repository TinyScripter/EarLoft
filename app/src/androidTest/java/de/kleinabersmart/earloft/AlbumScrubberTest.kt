package de.kleinabersmart.earloft

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.kleinabersmart.earloft.ui.library.AlbumScrubber
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The scrubber of the album crate keeps working when its length changes while the crate is open (review T8). */
@RunWith(AndroidJUnit4::class)
class AlbumScrubberTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun touchingTheEndStillReachesTheLastAlbumAfterTheTrackGotShorter() {
        val jumps = mutableListOf<Int>()
        var heightDp by mutableStateOf(600)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.height(heightDp.dp).width(48.dp).testTag("track")) {
                    AlbumScrubber(
                        vertical = true,
                        count = 100,
                        position = { 0f },
                        frontIndex = 0,
                        frontTitle = "Album",
                        labelAt = { it.toString() },
                        onSlide = {},
                        onJump = { jumps += it },
                        modifier = Modifier.fillMaxHeight(),
                    )
                }
            }
        }
        // Compose starts the pointer handlers with the first touch; they keep what they captured then.
        rule.onNodeWithTag("track").performTouchInput { click(center) }
        // The mini player slides in, the track gets shorter.
        rule.runOnIdle { heightDp = 300 }
        rule.waitForIdle()

        rule.onNodeWithTag("track").performTouchInput { click(Offset(centerX, height - 1f)) }

        rule.runOnIdle { assertEquals("jumps $jumps", 99, jumps.last()) }
    }
}
