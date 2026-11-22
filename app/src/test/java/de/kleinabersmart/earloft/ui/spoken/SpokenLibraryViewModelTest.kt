package de.kleinabersmart.earloft.ui.spoken

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.data.spoken.ArdBooks
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenRefresher
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.testing.FakePlaybackConnection
import de.kleinabersmart.earloft.testing.MainDispatcherRule
import de.kleinabersmart.earloft.testing.awaitReal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** Whether the books area has a source at all, or only offers to set one up. */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class SpokenLibraryViewModelTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val main = MainDispatcherRule()

    @Inject lateinit var playback: FakePlaybackConnection
    @Inject lateinit var books: BookRepository
    @Inject lateinit var servers: AudiobookshelfServers
    @Inject lateinit var launcher: BookLauncher
    @Inject lateinit var queue: EpisodeQueue
    @Inject lateinit var refresher: SpokenRefresher
    @Inject lateinit var settings: SpokenSettings
    @Inject lateinit var ardBooks: ArdBooks

    private val viewModel by lazy {
        SpokenLibraryViewModel(books, servers, launcher, playback, queue, refresher, settings, ardBooks)
    }

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun `without any book source the area offers the setup`() = runTest {
        settings.setUseLocalBooks(false)

        assertEquals(false, awaitReal { viewModel.hasServer.first { it != null } })
    }

    @Test
    fun `books on this device are a source of their own`() = runTest {
        settings.setUseLocalBooks(true)

        assertEquals(true, awaitReal { viewModel.hasServer.first { it != null } })
    }
}
