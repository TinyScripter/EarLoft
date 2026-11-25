package de.kleinabersmart.earloft.ui.player

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.kleinabersmart.earloft.core.data.repository.AlbumColorRepository
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.repository.LyricsRepository
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.testing.FakePlaybackConnection
import de.kleinabersmart.earloft.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The mini player's visibility follows playback only while the UI is started, and holds no observer otherwise (review T9). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class PlayerViewModelTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val main = MainDispatcherRule()

    @Inject lateinit var playback: FakePlaybackConnection
    @Inject lateinit var favorites: FavoritesRepository
    @Inject lateinit var lyrics: LyricsRepository
    @Inject lateinit var albumColors: AlbumColorRepository
    @Inject lateinit var visibility: MiniPlayerVisibility
    @Inject lateinit var library: LibraryRepository

    private val viewModel by lazy { PlayerViewModel(playback, favorites, lyrics, albumColors, visibility, library) }

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun `a created view model holds no observer of the playback state`() {
        viewModel
        // An observer would keep the playback service bound for as long as the activity lives.
        assertEquals(0, playback.state.subscriptionCount.value)
    }

    @Test
    fun `playing again brings back a hidden mini player while the UI keeps it in sync`() = runTest {
        visibility.hide("old", 0)
        assertTrue(visibility.hidden.value)
        val job = launch { viewModel.keepMiniPlayerInSync() }
        advanceUntilIdle()

        playback.state.value = PlayerState(isPlaying = true)
        advanceUntilIdle()

        assertFalse(visibility.hidden.value)
        job.cancel()
        advanceUntilIdle()
        assertEquals(0, playback.state.subscriptionCount.value)
    }
}
