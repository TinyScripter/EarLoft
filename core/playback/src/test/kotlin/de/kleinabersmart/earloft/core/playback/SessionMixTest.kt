package de.kleinabersmart.earloft.core.playback

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import de.kleinabersmart.earloft.core.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** "Start mix" from the session's button (Android Auto): the current song keeps playing, the mix replaces what follows. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionMixTest {

    private val player = QueuePlayer()

    /** Like the cast player ([SessionPlayerFactory] wrapper): notes a clearing up to the end, even of an empty range. */
    private var clearedToEnd = false
    private val sessionPlayer = object : ForwardingPlayer(player) {
        override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
            if (toIndex >= mediaItemCount) clearedToEnd = true
            super.removeMediaItems(fromIndex, toIndex)
        }
    }
    private val seeds = mutableListOf<Long>()
    private val failures = mutableListOf<SessionMix.Failure>()

    private fun sessionMix(
        factory: SessionPlayerFactory? = null,
        mix: suspend (Long) -> List<Song> = { seed -> listOf(seed, 7, 8).map(::song) },
    ) = SessionMix(
        sessionPlayer,
        mix = { seed -> seeds += seed; mix(seed) },
        factory = factory,
        scope = CoroutineScope(Dispatchers.Unconfined),
        onFailure = { failures += it },
    )

    @Test
    fun `the mix replaces everything after the current song and shuffle ends`() {
        player.update(listOf(1, 2, 3, 4), current = 1, shuffle = true)

        assertTrue(sessionMix().start())
        idle()

        assertEquals(listOf(2L), seeds)
        assertEquals(listOf(1L, 2, 7, 8), player.songIds())
        assertEquals(1, player.currentMediaItemIndex)
        assertFalse(player.shuffleModeEnabled)
        assertEquals(emptyList<SessionMix.Failure>(), failures)
    }

    @Test
    fun `without a song nothing starts`() {
        player.update(listOf(), current = 0)

        assertFalse(sessionMix().start())
        assertEquals(emptyList<Long>(), seeds)
    }

    @Test
    fun `an empty mix keeps the queue and is reported`() {
        player.update(listOf(1, 2), current = 0)

        sessionMix(mix = { seed -> listOf(song(seed)) }).start()
        idle()

        assertEquals(listOf(1L, 2), player.songIds())
        assertEquals(listOf(SessionMix.Failure.EMPTY), failures)
    }

    @Test
    fun `a failing mix keeps the queue and is reported`() {
        player.update(listOf(1, 2), current = 0)

        sessionMix(mix = { error("database gone") }).start()
        idle()

        assertEquals(listOf(1L, 2), player.songIds())
        assertEquals(listOf(SessionMix.Failure.FAILED), failures)
    }

    @Test
    fun `a mix that arrives after the song changed is dropped`() {
        val slow = CompletableDeferred<Unit>()
        player.update(listOf(1, 2, 3), current = 0)

        sessionMix(mix = { seed -> slow.await(); listOf(seed, 7).map(::song) }).start()
        player.update(current = 1)
        slow.complete(Unit)
        idle()

        assertEquals(listOf(1L, 2, 3), player.songIds())
        assertEquals(emptyList<SessionMix.Failure>(), failures)
    }

    @Test
    fun `while casting the reserve is cleared and what does not fit goes back into it`() {
        val factory = FakeFactory(maxSize = 3, pending = true)
        // The current song is the last one on the receiver, the reserve still holds more.
        player.update(listOf(1, 2), current = 1)

        sessionMix(factory, mix = { seed -> listOf(seed, 7, 8, 9).map(::song) }).start()
        idle()

        assertTrue("clearing to the end ran although nothing followed in the player", clearedToEnd)
        assertEquals(listOf(1L, 2, 7), player.songIds())
        assertEquals(listOf(8L, 9), factory.appended.map { it.songId })
    }

    @Test
    fun `clearing after the last title still reports a clearing to the end, as the app's mix from now playing needs it`() {
        player.update(listOf(1, 2), current = 1)

        sessionPlayer.clearAfterCurrent()
        idle()

        assertTrue(clearedToEnd)
        assertEquals(listOf(1L, 2), player.songIds())
    }

    @Test
    fun `songs the receiver cannot play are left out`() {
        val factory = FakeFactory(playable = setOf(8))
        player.update(listOf(1, 2), current = 0)

        sessionMix(factory).start()
        idle()

        assertEquals(listOf(1L, 8), player.songIds())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun song(id: Long) = Song(id, "Song $id", "Artist", albumId = 1, albumTitle = "Album", durationMs = 1000, discNumber = 1, trackNumber = id.toInt(), isFavorite = false)

    private class FakeFactory(
        private val maxSize: Int = Int.MAX_VALUE,
        private var pending: Boolean = false,
        private val playable: Set<Long>? = null,
    ) : SessionPlayerFactory {
        val appended = mutableListOf<MediaItem>()

        override fun create(context: Context, localPlayer: ExoPlayer): Player = error("not used")
        override suspend fun playableSongIds(player: Player, songIds: Collection<Long>): Set<Long> =
            playable?.let { songIds.filterTo(HashSet()) { it in playable } } ?: songIds.toSet()
        override fun maxQueueSize(player: Player): Int = maxSize
        override fun hasPendingItems(): Boolean = pending
        override fun appendPending(items: List<MediaItem>) {
            appended += items
        }
    }

    /** A list of songs (`song:<id>`) that can be changed like a player. */
    private class QueuePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var items: List<MediaItem> = emptyList()
        private var current = 0
        private var shuffle = false

        fun update(songIds: List<Long>? = null, current: Int, shuffle: Boolean = false) {
            songIds?.let { ids -> items = ids.map { MediaItem.Builder().setMediaId(MediaIds.song(it)).build() } }
            this.current = current
            this.shuffle = shuffle
            invalidateState()
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun songIds(): List<Long> = (0 until mediaItemCount).mapNotNull { getMediaItemAt(it).songId }

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(items.map { MediaItemData.Builder(it.mediaId).setMediaItem(it).build() })
            .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else current)
            .setShuffleModeEnabled(shuffle)
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
            .build()

        override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
            items = items.take(index) + mediaItems + items.drop(index)
            return Futures.immediateVoidFuture()
        }

        override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
            items = items.take(fromIndex) + items.drop(toIndex)
            return Futures.immediateVoidFuture()
        }

        override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
            shuffle = shuffleModeEnabled
            return Futures.immediateVoidFuture()
        }
    }
}
