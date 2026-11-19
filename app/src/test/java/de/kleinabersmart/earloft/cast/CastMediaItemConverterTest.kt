package de.kleinabersmart.earloft.cast

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.core.playback.albumIdExtra
import de.kleinabersmart.earloft.core.playback.bookId
import de.kleinabersmart.earloft.core.playback.isEpisode
import de.kleinabersmart.earloft.core.playback.songId
import de.kleinabersmart.earloft.core.playback.toMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** Receiver entries back into items when the app reattaches to a running session. */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class CastMediaItemConverterTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var cache: CastMediaCache

    private lateinit var converter: CastMediaItemConverter

    @Before
    fun setUp() {
        hilt.inject()
        converter = CastMediaItemConverter(cache)
    }

    @Test
    fun `an episode keeps its kind and podcast through the receiver`() {
        val episode = MediaItem.Builder()
            .setMediaId("book:42/0")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Folge")
                    .setArtist("Podcast")
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .build(),
            )
            .build()

        val back = converter.toMediaItem(converter.toMediaQueueItem(episode))

        assertEquals(42L, back.bookId)
        assertTrue(back.isEpisode)
        assertEquals("Folge", back.mediaMetadata.title)
        assertEquals("Podcast", back.mediaMetadata.artist)
    }

    @Test
    fun `a song keeps its album and cover through the receiver`() {
        val song = Song(
            id = 1310, title = "Achterbahn", artist = "Madsen", albumId = 77, albumTitle = "SMILE",
            durationMs = 202_000, discNumber = 1, trackNumber = 2, isFavorite = false,
        ).toMediaItem()

        val back = converter.toMediaItem(converter.toMediaQueueItem(song))

        assertEquals(song.mediaId, back.mediaId)
        assertEquals(1310L, back.songId)
        assertEquals(77L, back.mediaMetadata.albumIdExtra)
        assertEquals(song.mediaMetadata.artworkUri, back.mediaMetadata.artworkUri)
        assertEquals(song.localConfiguration?.uri, back.localConfiguration?.uri)
        assertEquals("Achterbahn", back.mediaMetadata.title)
        assertEquals("Madsen", back.mediaMetadata.artist)
        assertEquals("SMILE", back.mediaMetadata.albumTitle)
        assertEquals(202_000L, back.mediaMetadata.durationMs)
        assertEquals(2, back.mediaMetadata.trackNumber)
    }

    @Test
    fun `an item without a kind stays without one`() {
        val item = MediaItem.Builder().setMediaId("book:7/2").setMediaMetadata(MediaMetadata.Builder().setTitle("Teil").build()).build()

        assertNull(converter.toMediaItem(converter.toMediaQueueItem(item)).mediaMetadata.mediaType)
    }
}
