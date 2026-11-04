package keinabersmart.kleinaberlaut.ui.spoken

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import keinabersmart.kleinaberlaut.core.data.podcast.ContinueListening
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRefresher
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.playback.PlayerState
import keinabersmart.kleinaberlaut.core.playback.SpokenEntry
import keinabersmart.kleinaberlaut.testing.FakePlaybackConnection
import keinabersmart.kleinaberlaut.testing.FakePlaybackConnection.Command
import keinabersmart.kleinaberlaut.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** Abspielen auf der Podcast-Seite (Review A3); Abonnieren steht in [AddPodcastViewModelTest]. */
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

    private val viewModel by lazy { PodcastViewModel(podcasts, refresher, launcher, playback, continueListening) }

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

        // Ohne gespeicherte Stelle: Der Launcher übergibt keinen Start, die Wiedergabe nimmt den gespeicherten Stand.
        assertEquals(listOf(Command.PlayBook(8, null)), playback.awaitCommands())
    }

}
