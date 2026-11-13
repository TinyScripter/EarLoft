package de.kleinabersmart.earloft.core.playback

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class SpokenMediaButtonsTest {

    private val calls = mutableListOf<String>()
    private var spoken = true
    private val buttons = SpokenMediaButtons(
        handler = Handler(Looper.getMainLooper()),
        doubleTapTimeoutMs = 300,
        isSpoken = { spoken },
        playPause = { calls += "playPause" },
        next = { calls += "next" },
        previous = { calls += "previous" },
    )

    private fun press(keyCode: Int, external: Boolean = true): Pair<Boolean, Boolean> =
        buttons.onMediaButton(KeyEvent(KeyEvent.ACTION_DOWN, keyCode), external) to
            buttons.onMediaButton(KeyEvent(KeyEvent.ACTION_UP, keyCode), external)

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `a single click toggles play and pause after the double tap timeout`() {
        assertEquals(true to true, press(KeyEvent.KEYCODE_HEADSETHOOK))
        assertEquals(emptyList<String>(), calls)

        idle(300)
        assertEquals(listOf("playPause"), calls)
    }

    @Test
    fun `a double click skips to the next chapter or episode without toggling`() {
        press(KeyEvent.KEYCODE_HEADSETHOOK)
        idle(100)
        press(KeyEvent.KEYCODE_HEADSETHOOK)
        idle(1_000)

        assertEquals(listOf("next"), calls)
    }

    @Test
    fun `two clicks far apart are two toggles`() {
        press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        idle(400)
        press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        idle(400)

        assertEquals(listOf("playPause", "playPause"), calls)
    }

    @Test
    fun `next and previous keys skip chapters at once`() {
        assertEquals(true to true, press(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(true to true, press(KeyEvent.KEYCODE_MEDIA_PREVIOUS))

        assertEquals(listOf("next", "previous"), calls)
    }

    @Test
    fun `a pending single click runs before another key`() {
        press(KeyEvent.KEYCODE_HEADSETHOOK)
        press(KeyEvent.KEYCODE_MEDIA_NEXT)

        assertEquals(listOf("playPause", "next"), calls)
    }

    @Test
    fun `music and clicks from inside the app stay with Media3`() {
        assertEquals(false to false, press(KeyEvent.KEYCODE_HEADSETHOOK, external = false))
        spoken = false
        assertEquals(false to false, press(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(false to false, press(KeyEvent.KEYCODE_HEADSETHOOK))

        idle(1_000)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a long press is left to Media3`() {
        val longPress = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 1)
        assertFalse(buttons.onMediaButton(longPress, external = true))
    }
}
