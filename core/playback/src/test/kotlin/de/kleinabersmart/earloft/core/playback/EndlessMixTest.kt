package de.kleinabersmart.earloft.core.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import de.kleinabersmart.earloft.core.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class EndlessMixTest {

    private val player = QueuePlayer()
    private var enabled = true
    private val seeds = mutableListOf<Long>()

    private fun endlessMix(mix: List<Long> = listOf(1, 2, 7, 8)) = EndlessMix(
        player,
        isEnabled = { enabled },
        mix = { seed -> seeds += seed; mix.map(::song) },
        factory = null,
        scope = CoroutineScope(Dispatchers.Unconfined),
    ).also(player::addListener)

    @Test
    fun `the last song brings a mix without the songs already queued`() {
        endlessMix()
        player.update(listOf(1, 2, 3), current = 1)
        assertEquals(emptyList<Long>(), seeds)

        player.update(current = 2)

        assertEquals(listOf(3L), seeds)
        assertEquals(listOf(1L, 2, 3, 7, 8), player.songIds())
        // Das Angehängte löst keinen weiteren Mix aus.
        assertEquals(listOf(3L), seeds)
    }

    @Test
    fun `nothing is added while switched off, with repeat or with shuffle`() {
        endlessMix()
        enabled = false
        player.update(listOf(1, 2), current = 1)
        player.update(listOf(1, 2), current = 1, repeatMode = Player.REPEAT_MODE_ALL)
        player.update(listOf(1, 2), current = 1, shuffle = true)

        assertEquals(emptyList<Long>(), seeds)
        assertEquals(listOf(1L, 2), player.songIds())
    }

    @Test
    fun `switching it on at the last song adds the mix right away`() {
        val endlessMix = endlessMix()
        enabled = false
        player.update(listOf(1, 2), current = 1)

        enabled = true
        endlessMix.check()

        assertEquals(listOf(1L, 2, 7, 8), player.songIds())
    }

    @Test
    fun `an empty mix is not looked for again until the song changes`() {
        endlessMix(mix = emptyList())
        player.update(listOf(1, 2), current = 0)
        player.update(current = 1)
        // Wiederholen an und wieder aus: kein zweiter Versuch beim selben Song.
        player.update(current = 1, repeatMode = Player.REPEAT_MODE_ALL)
        player.update(current = 1)

        assertEquals(listOf(2L), seeds)
    }

    @Test
    fun `a slow mix for an earlier song does not keep the new last song from its mix`() {
        val slow = CompletableDeferred<Unit>()
        EndlessMix(
            player,
            isEnabled = { enabled },
            mix = { seed ->
                seeds += seed
                if (seed == 3L) slow.await()
                listOf(7L, 8).map(::song)
            },
            factory = null,
            scope = CoroutineScope(Dispatchers.Unconfined),
        ).also(player::addListener)
        player.update(listOf(1, 2, 3), current = 0)
        player.update(current = 2)

        // Während der Mix zu 3 noch sucht, kommt eine neue Warteschlange mit nur einem Song.
        player.update(listOf(5), current = 0)
        slow.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(3L, 5), seeds)
        assertEquals(listOf(5L, 7, 8), player.songIds())
    }

    private fun song(id: Long) = Song(id, "Song $id", "Künstler", albumId = 1, albumTitle = "Album", durationMs = 1000, discNumber = 1, trackNumber = id.toInt(), isFavorite = false)

    /**
     * Spielt eine Liste von Songs (`song:<id>`) und nimmt angehängte Titel auf. Den ersten Stand liest [SimpleBasePlayer] erst beim
     * ersten [update], ohne Ereignisse: Tests beginnen deshalb vor dem Stand, auf den es ankommt.
     */
    private class QueuePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var items: List<MediaItem> = emptyList()
        private var current = 0
        private var repeatMode = Player.REPEAT_MODE_OFF
        private var shuffle = false

        fun update(songIds: List<Long>? = null, current: Int, repeatMode: Int = Player.REPEAT_MODE_OFF, shuffle: Boolean = false) {
            songIds?.let { ids -> items = ids.map { MediaItem.Builder().setMediaId(MediaIds.song(it)).build() } }
            this.current = current
            this.repeatMode = repeatMode
            this.shuffle = shuffle
            invalidateState()
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun songIds(): List<Long> = (0 until mediaItemCount).mapNotNull { getMediaItemAt(it).songId }

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(items.map { MediaItemData.Builder(it.mediaId).setMediaItem(it).build() })
            .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else current)
            .setRepeatMode(repeatMode)
            .setShuffleModeEnabled(shuffle)
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
            .build()

        override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
            items = items.take(index) + mediaItems + items.drop(index)
            return Futures.immediateVoidFuture()
        }
    }
}
