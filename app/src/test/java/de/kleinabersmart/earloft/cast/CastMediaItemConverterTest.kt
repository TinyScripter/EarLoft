package de.kleinabersmart.earloft.cast

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.kleinabersmart.earloft.core.playback.bookId
import de.kleinabersmart.earloft.core.playback.isEpisode
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

/** Einträge des Empfängers zurück in Items, wenn die App sich wieder an eine laufende Sitzung hängt. */
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
    fun `an item without a kind stays without one`() {
        val item = MediaItem.Builder().setMediaId("book:7/2").setMediaMetadata(MediaMetadata.Builder().setTitle("Teil").build()).build()

        assertNull(converter.toMediaItem(converter.toMediaQueueItem(item)).mediaMetadata.mediaType)
    }
}
