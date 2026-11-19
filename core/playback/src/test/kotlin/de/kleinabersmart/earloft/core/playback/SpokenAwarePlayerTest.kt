package de.kleinabersmart.earloft.core.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class SpokenAwarePlayerTest {

    @Test
    fun `books use the default steps of 10 s back and 30 s forward`() {
        val player = SpokenAwarePlayer(FakePlayer(item("book:1/0", MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER), positionMs = 60_000))

        assertEquals(10_000L, player.seekBackIncrement)
        assertEquals(30_000L, player.seekForwardIncrement)
        player.seekBack()
        assertEquals(50_000L, player.currentPosition)
        player.seekForward()
        assertEquals(80_000L, player.currentPosition)
    }

    @Test
    fun `episodes and books follow their own settings`() {
        val episode = SpokenAwarePlayer(FakePlayer(item("book:2/0", MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE), positionMs = 60_000))
        episode.bookSteps = SeekSteps(5, 60)
        episode.podcastSteps = SeekSteps(15, 45)

        assertEquals(SeekSteps(15, 45), episode.currentSteps)
        episode.seekForward()
        assertEquals(105_000L, episode.currentPosition)
    }

    @Test
    fun `changed steps are reported to listeners such as the session`() {
        val player = SpokenAwarePlayer(FakePlayer(item("book:2/0", MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE), positionMs = 0))
        val reported = mutableListOf<String>()
        player.addListener(object : Player.Listener {
            override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) {
                reported += "back $seekBackIncrementMs"
            }

            override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) {
                reported += "forward $seekForwardIncrementMs"
            }
        })

        player.bookSteps = SeekSteps(5, 60)
        player.podcastSteps = SeekSteps(10, 45)
        player.podcastSteps = SeekSteps(10, 45)

        assertEquals(listOf("forward 45000"), reported)
    }

    @Test
    fun `music keeps the player's own steps`() {
        val player = SpokenAwarePlayer(FakePlayer(item("song:1", MediaMetadata.MEDIA_TYPE_MUSIC), positionMs = 60_000))

        assertEquals(null, player.currentSteps)
        assertEquals(FakePlayer.MUSIC_INCREMENT_MS, player.seekBackIncrement)
    }

    @Test
    fun `the chapter stands before the author for notification and lock screen`() {
        val player = SpokenAwarePlayer(FakePlayer(item("book:1/0", MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER, artist = "Jo Nesbø"), positionMs = 0))
        val reported = mutableListOf<String?>()
        player.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                reported += mediaMetadata.artist?.toString()
            }
        })

        player.chapterTitle = "Chapter 3"
        player.chapterTitle = "Chapter 3"
        assertEquals("Chapter 3 · Jo Nesbø", player.mediaMetadata.artist.toString())
        player.chapterTitle = null

        assertEquals("Jo Nesbø", player.mediaMetadata.artist.toString())
        assertEquals(listOf("Chapter 3 · Jo Nesbø", "Jo Nesbø"), reported)
    }

    @Test
    fun `music keeps its metadata`() {
        val player = SpokenAwarePlayer(FakePlayer(item("song:1", MediaMetadata.MEDIA_TYPE_MUSIC, artist = "Queen"), positionMs = 0))

        player.chapterTitle = "Chapter 3"

        assertEquals("Queen", player.mediaMetadata.artist.toString())
    }

    private fun item(mediaId: String, mediaType: Int, artist: String? = null) = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(MediaMetadata.Builder().setMediaType(mediaType).setArtist(artist).build())
        .build()

    /** An item of ten minutes; seeking only sets the position. */
    private class FakePlayer(private val item: MediaItem, private var positionMs: Long) : SimpleBasePlayer(Looper.getMainLooper()) {

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(listOf(MediaItemData.Builder(item.mediaId).setMediaItem(item).setDurationUs(DURATION_MS * 1000).build()))
            .setPlaybackState(Player.STATE_READY)
            .setContentPositionMs(positionMs)
            .setSeekBackIncrementMs(MUSIC_INCREMENT_MS)
            .setSeekForwardIncrementMs(MUSIC_INCREMENT_MS)
            .build()

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            this.positionMs = positionMs
            return Futures.immediateVoidFuture()
        }

        companion object {
            const val DURATION_MS = 600_000L
            const val MUSIC_INCREMENT_MS = 5_000L
        }
    }
}
