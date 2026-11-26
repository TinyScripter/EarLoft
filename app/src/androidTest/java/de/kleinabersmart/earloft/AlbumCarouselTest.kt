package de.kleinabersmart.earloft

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.ui.library.AlbumCarousel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The album carousel with little height (review4 T49, 8.6 Y25). */
@RunWith(AndroidJUnit4::class)
class AlbumCarouselTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun withLittleHeightTheCoverGivesWayNotTheSlider() {
        val albums = List(10) { Album(id = it + 1L, title = "Album ${it + 1}", artist = "Artist", year = null, songCount = 1) }
        rule.setContent {
            MaterialTheme {
                // Split screen or a small phone with filter row and mini player: less than cover, slider and details need.
                Box(Modifier.height(280.dp).width(360.dp)) {
                    AlbumCarousel(albums, onAlbumClick = {}, label = null, header = null, resetKey = "sort", playingAlbumId = null, onPlay = {})
                }
            }
        }
        val slider = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.library_albums)

        rule.onNodeWithContentDescription(slider).assertHeightIsAtLeast(40.dp)
    }
}
