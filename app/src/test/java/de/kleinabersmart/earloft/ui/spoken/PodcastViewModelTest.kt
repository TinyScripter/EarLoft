package de.kleinabersmart.earloft.ui.spoken

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.PodcastRefresher
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.SpokenEntry
import de.kleinabersmart.earloft.testing.FakePlaybackConnection
import de.kleinabersmart.earloft.testing.FakePlaybackConnection.Command
import de.kleinabersmart.earloft.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** Playing on the podcast page (review A3); subscribing is in [AddPodcastViewModelTest]. */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class PodcastViewModelTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val main = MainDispatcherRule()

    @Inject lateinit var playback: FakePlaybackConnection
    @Inject lateinit var podcasts: PodcastRepository
    @Inject lateinit var refresher: PodcastRefresher
    @Inject lateinit var launcher: BookLauncher
    @Inject lateinit var continueListening: ContinueListening
    @Inject lateinit var settings: PodcastSettings

    private val viewModel by lazy { PodcastViewModel(podcasts, refresher, launcher, playback, continueListening, settings) }

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun `the loaded episode pauses or resumes instead of starting again`() {
        playback.state.value = PlayerState(spoken = SpokenEntry(bookId = 7, trackIndex = 0, title = "Folge", author = "Feed"), isPlaying = true)

        viewModel.playOrPause(7)

        assertEquals(listOf(Command.Simple("togglePlayPause")), playback.commands.value)
    }

    @Test
    fun `another episode is started through the launcher`() = runTest {
        playback.state.value = PlayerState(spoken = SpokenEntry(bookId = 7, trackIndex = 0, title = "Folge", author = "Feed"), isPlaying = true)

        viewModel.playOrPause(8)

        // No saved position: the launcher passes no start, playback uses the saved state.
        assertEquals(listOf(Command.PlayBook(8, null)), playback.awaitCommands())
    }

}
