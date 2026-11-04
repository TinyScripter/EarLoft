package keinabersmart.kleinaberlaut.ui.spoken

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastDirectorySearch
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastSettings
import keinabersmart.kleinaberlaut.core.network.podcast.directory.PodcastDirectory
import keinabersmart.kleinaberlaut.testing.MainDispatcherRule
import keinabersmart.kleinaberlaut.testing.awaitReal
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

/** Seite „Podcast hinzufügen“: Feed-Adresse und eingeschaltete Verzeichnisse (Konzept 12.10). */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class AddPodcastViewModelTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val main = MainDispatcherRule()

    @Inject lateinit var podcasts: PodcastRepository
    @Inject lateinit var directorySearch: PodcastDirectorySearch
    @Inject lateinit var settings: PodcastSettings
    @Inject lateinit var opmlImporter: OpmlImporter
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    private val viewModel by lazy { AddPodcastViewModel(podcasts, directorySearch, opmlImporter, applicationScope) }

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun `an invalid address fails without touching the network`() = runTest {
        viewModel.subscribe("kein feed")

        assertEquals(
            AddPodcastState.Failed(R.string.podcast_error_invalid),
            awaitReal { viewModel.add.first { it is AddPodcastState.Failed } },
        )
    }

    @Test
    fun `no directory is switched on until the user does it`() = runTest {
        assertEquals(emptyList<PodcastDirectory>(), awaitReal { viewModel.directories.first { it != null } })

        settings.setDirectory(PodcastDirectory.APPLE, true)

        assertEquals(listOf(PodcastDirectory.APPLE), awaitReal { viewModel.directories.first { !it.isNullOrEmpty() } })
    }

    @Test
    fun `a blank search term does not search`() {
        viewModel.search("   ")

        assertEquals(DirectorySearchState.Idle, viewModel.search.value)
    }
}
