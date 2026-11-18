package de.kleinabersmart.earloft.core.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import de.kleinabersmart.earloft.core.data.spoken.PreparedBook
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookTimeline
import de.kleinabersmart.earloft.core.model.BookTrack
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayBookOrderTest {

    private val book = PreparedBook(
        book = Book(id = 5, serverId = "abs", kind = BookKind.BOOK, title = "Buch", author = "Autor", durationMs = 60_000, remoteId = "b5"),
        timeline = BookTimeline(60_000, listOf(BookTrack(0, "f0", 0, 60_000, mimeType = "audio/mpeg")), emptyList()),
        trackUrls = listOf("https://abs.example.org/f0.mp3"),
        startPositionMs = 0,
        resumedFromServer = false,
        speed = 1f,
        castUrls = listOf("https://abs.example.org/f0.mp3"),
        castCoverUrl = null,
    )

    @Test
    fun `shuffle and repeat go off only once the book is loaded, so they are not saved for the music`() {
        val player = RecordingPlayer()
        // Wie der PersistenceListener: Was lag im Player, als Zufall bzw. Wiederholen sich änderten?
        val heldWhenModesChanged = mutableListOf<Boolean>()
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                heldWhenModesChanged += player.currentMediaItem?.isSpoken == true
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                heldWhenModesChanged += player.currentMediaItem?.isSpoken == true
            }
        })

        player.playBook(book)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("setMediaItems", "shuffle false", "repeat ${Player.REPEAT_MODE_OFF}"), player.calls.take(3))
        assertEquals(listOf(true, true), heldWhenModesChanged)
    }

    /** Spielt Musik mit Zufall und „Alle wiederholen“ und schreibt die Befehle mit. */
    private class RecordingPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        val calls = mutableListOf<String>()
        private var items = listOf(MediaItem.Builder().setMediaId(MediaIds.song(1)).build())
        private var shuffle = true
        private var repeatMode = Player.REPEAT_MODE_ALL

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(items.mapIndexed { index, item -> MediaItemData.Builder("$index").setMediaItem(item).build() })
            .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else 0)
            .setShuffleModeEnabled(shuffle)
            .setRepeatMode(repeatMode)
            .setPlaybackState(Player.STATE_READY)
            .build()

        override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
            calls += "setMediaItems"
            items = mediaItems
            return Futures.immediateVoidFuture()
        }

        override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
            calls += "shuffle $shuffleModeEnabled"
            shuffle = shuffleModeEnabled
            return Futures.immediateVoidFuture()
        }

        override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
            calls += "repeat $repeatMode"
            this.repeatMode = repeatMode
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> =
            Futures.immediateVoidFuture()

        override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = Futures.immediateVoidFuture()
    }
}
