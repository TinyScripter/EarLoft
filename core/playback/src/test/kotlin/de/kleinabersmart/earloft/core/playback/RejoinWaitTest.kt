package de.kleinabersmart.earloft.core.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The service start waits for a cast session the app reattached to before it restores the saved queue. Once the receiver reports
 * its titles there is nothing left to wait for; it used to wait the full 45 s, and resuming playback with it (review5 P15).
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class RejoinWaitTest {

    @Test
    fun `a reattached receiver with titles ends the wait at once`() = runBlocking {
        val started = System.nanoTime()

        assertFalse(awaitLocalOrQueued(FakePlayer(remote = true, items = 3), timeoutMs = 10_000))

        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun `playing on the device ends the wait at once`() = runBlocking {
        assertTrue(awaitLocalOrQueued(FakePlayer(remote = false, items = 0), timeoutMs = 10_000))
    }

    @Test
    fun `an empty receiver is waited for until the timeout`() = runBlocking {
        assertFalse(awaitLocalOrQueued(FakePlayer(remote = true, items = 0), timeoutMs = 200))
    }

    private class FakePlayer(private val remote: Boolean, private val items: Int) : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): State = State.Builder()
            .setDeviceInfo(
                DeviceInfo.Builder(if (remote) DeviceInfo.PLAYBACK_TYPE_REMOTE else DeviceInfo.PLAYBACK_TYPE_LOCAL).build(),
            )
            .setPlaylist((0 until items).map { MediaItemData.Builder("song:$it").setMediaItem(MediaItem.Builder().setMediaId("song:$it").build()).build() })
            .build()
    }
}
