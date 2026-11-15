package de.kleinabersmart.earloft.cast

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class DeferredRemotePlayPlayerTest {

    /** Ein Player, dessen Gerät und Zustand der Test setzt; merkt sich, ob abgespielt werden soll. */
    private class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var remote = false
        var receiverState = Player.STATE_IDLE
        var wantsPlay = false

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setDeviceInfo(DeviceInfo.Builder(if (remote) DeviceInfo.PLAYBACK_TYPE_REMOTE else DeviceInfo.PLAYBACK_TYPE_LOCAL).build())
            .setPlaybackState(receiverState)
            .setPlayWhenReady(wantsPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(MediaItemData.Builder("book").setMediaItem(MediaItem.Builder().setMediaId("book").build()).build()),
            )
            .build()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            wantsPlay = playWhenReady
            return Futures.immediateVoidFuture()
        }

        override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> = Futures.immediateVoidFuture()

        /** Ändert den Zustand und stellt die Ereignisse zu (sie laufen über den Looper). */
        fun update(block: FakePlayer.() -> Unit) {
            // Der Zustand wird erst beim ersten Zugriff gelesen; ohne den hier gäbe es keinen Unterschied zu melden.
            deviceInfo
            block()
            invalidateState()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `play while the receiver still loads the handed over queue waits until it is ready`() {
        val fake = FakePlayer()
        val player = DeferredRemotePlayPlayer(fake)

        fake.update { remote = true }
        player.play()
        assertFalse("Während des Ladens geht kein Abspielen hinaus", fake.wantsPlay)

        fake.update { receiverState = Player.STATE_READY }
        assertTrue(fake.wantsPlay)
    }

    @Test
    fun `removing everything up to the end while casting drops the reserve as well`() {
        val fake = FakePlayer()
        var cleared = 0
        val player = DeferredRemotePlayPlayer(fake, onClearedToEnd = { cleared++ })

        player.removeMediaItems(0, 1)
        assertEquals("Lokal gibt es keine Reserve", 0, cleared)

        fake.update { remote = true }
        player.removeMediaItems(0, 1)
        assertEquals(1, cleared)
    }

    @Test
    fun `a receiver that is already ready plays at once`() {
        val fake = FakePlayer()
        val player = DeferredRemotePlayPlayer(fake)

        fake.update {
            remote = true
            receiverState = Player.STATE_READY
        }
        player.play()
        assertTrue(fake.wantsPlay)
    }

    @Test
    fun `play goes out after the time limit even without a ready receiver`() {
        val fake = FakePlayer()
        val player = DeferredRemotePlayPlayer(fake)

        fake.update { remote = true }
        player.play()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        assertTrue(fake.wantsPlay)
    }

    @Test
    fun `on the phone nothing is held back`() {
        val fake = FakePlayer()
        val player = DeferredRemotePlayPlayer(fake)

        player.play()
        assertTrue(fake.wantsPlay)
    }
}
