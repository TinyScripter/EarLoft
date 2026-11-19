package de.kleinabersmart.earloft.core.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** Listening history when switching between phone and cast device (R24). */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayTrackerTest {

    private val player = FakeCastPlayer()
    private val plays = mutableListOf<Pair<Long, Long>>()

    @Before
    fun setUp() {
        player.addListener(PlayTracker(player) { songId, _, listenedMs -> plays += songId to listenedMs })
    }

    @Test
    fun `a song handed over to cast is recorded once`() {
        player.update(items = listOf("local" to "song:1"), playing = true)
        listen(40)
        // The receiver only reports the queue after loading.
        player.update(items = emptyList(), remote = true, playing = false)
        listen(6)
        player.update(items = listOf("remote" to "song:1"), remote = true, playing = true)
        listen(40)
        player.update(items = listOf("next" to "song:2"), remote = true, playing = true)

        assertEquals(listOf(1L), plays.map { it.first })
        assertEquals(80_000L, plays.single().second)
    }

    @Test
    fun `a song coming back from cast is recorded once`() {
        player.update(items = listOf("remote" to "song:1"), remote = true, playing = true)
        listen(40)
        player.update(items = listOf("local" to "song:1"), remote = false, playing = true)
        listen(40)
        player.update(items = listOf("next" to "song:2"), playing = true)

        assertEquals(listOf(1L to 80_000L), plays)
    }

    @Test
    fun `the same song started again on the same device counts again`() {
        player.update(items = listOf("first" to "song:1"), playing = true)
        listen(40)
        player.update(items = listOf("again" to "song:1"), playing = true)
        listen(40)
        player.update(items = listOf("next" to "song:2"), playing = true)

        assertEquals(listOf(1L to 40_000L, 1L to 40_000L), plays)
    }

    @Test
    fun `an emptied queue on the phone finishes the song`() {
        player.update(items = listOf("local" to "song:1"), playing = true)
        listen(40)
        player.update(items = emptyList(), playing = false)

        assertEquals(listOf(1L to 40_000L), plays)
    }

    private fun listen(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    /** A player whose queue (`uid` to media ID), device and playback state the test sets. */
    private class FakeCastPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var items: List<Pair<String, String>> = emptyList()
        private var remote = false
        private var playing = false

        fun update(items: List<Pair<String, String>>, remote: Boolean = false, playing: Boolean) {
            this.items = items
            this.remote = remote
            this.playing = playing
            invalidateState()
            shadowOf(Looper.getMainLooper()).idle()
        }

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(
                items.map { (uid, mediaId) ->
                    MediaItemData.Builder(uid).setMediaItem(MediaItem.Builder().setMediaId(mediaId).build()).build()
                },
            )
            .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else 0)
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setDeviceInfo(DeviceInfo.Builder(if (remote) DeviceInfo.PLAYBACK_TYPE_REMOTE else DeviceInfo.PLAYBACK_TYPE_LOCAL).build())
            .build()
    }
}
