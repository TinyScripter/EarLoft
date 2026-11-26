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

    /** A player whose device and state the test sets; remembers whether it should play. */
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

        /** Changes the state and delivers the events (they go through the looper). */
        fun update(block: FakePlayer.() -> Unit) {
            // The state is only read on first access; without this there would be no difference to report.
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
        assertFalse("No play goes out while loading", fake.wantsPlay)

        fake.update { receiverState = Player.STATE_READY }
        assertTrue(fake.wantsPlay)
    }

    @Test
    fun `removing everything up to the end while casting drops the reserve as well`() {
        val fake = FakePlayer()
        var cleared = 0
        val player = DeferredRemotePlayPlayer(fake, onClearedToEnd = { cleared++ })

        player.removeMediaItems(0, 1)
        assertEquals("Locally there is no reserve", 0, cleared)

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

    /** Five tracks a–e, the fourth is playing; remembers skips and can be trimmed at the front. */
    private class PlaylistPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var ids = listOf("a", "b", "c", "d", "e")
        var current = 3
        val seeks = mutableListOf<Int>()

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).build())
            .setPlaylist(ImmutableList.copyOf(ids.map { MediaItemData.Builder(it).setMediaItem(MediaItem.Builder().setMediaId(it).build()).build() }))
            .setCurrentMediaItemIndex(current)
            .build()

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            seeks += mediaItemIndex
            current = mediaItemIndex
            return Futures.immediateVoidFuture()
        }

        override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> = Futures.immediateVoidFuture()

        fun dropFirst(count: Int) {
            ids = ids.drop(count)
            current -= count
            invalidateState()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `seeks wait while items before the current one are removed and then hit the item they meant`() {
        val fake = PlaylistPlayer()
        val player = DeferredRemotePlayPlayer(fake)
        player.currentMediaItemIndex

        player.holdSeeks()
        player.seekTo(4, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Held back", emptyList<Int>(), fake.seeks)

        fake.dropFirst(2)
        player.releaseSeeks()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("\"e\" is now at index 2", listOf(2), fake.seeks)
    }

    @Test
    fun `removing everything before the current item while casting drops what lay before the window`() {
        val fake = PlaylistPlayer()
        var clearedBefore = 0
        var clearedToEnd = 0
        val player = DeferredRemotePlayPlayer(fake, onClearedToEnd = { clearedToEnd++ }, onClearedBefore = { clearedBefore++ })
        player.currentMediaItemIndex

        player.removeMediaItems(0, 1)
        assertEquals("A single item at the front is no clearing", 0, clearedBefore)

        player.removeMediaItems(0, 3)
        assertEquals(1, clearedBefore)
        assertEquals(0, clearedToEnd)
    }

    @Test
    fun `removing missing songs while casting is no clearing`() {
        val fake = PlaylistPlayer()
        var clearedBefore = 0
        var clearedToEnd = 0
        val player = DeferredRemotePlayPlayer(fake, onClearedToEnd = { clearedToEnd++ }, onClearedBefore = { clearedBefore++ })
        player.currentMediaItemIndex

        // Exactly the songs after the current one ("d") and those before it are gone.
        player.withoutClearing {
            player.removeMediaItems(4, 5)
            player.removeMediaItems(0, 3)
        }
        assertEquals(0, clearedBefore)
        assertEquals(0, clearedToEnd)

        // Afterwards it is the user's clearing again.
        shadowOf(Looper.getMainLooper()).idle()
        player.removeMediaItems(0, 3)
        assertEquals(1, clearedBefore)
    }

    @Test
    fun `without holding seeks go out at once`() {
        val fake = PlaylistPlayer()
        val player = DeferredRemotePlayPlayer(fake)

        player.seekToNextMediaItem()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(4), fake.seeks)
    }
}
