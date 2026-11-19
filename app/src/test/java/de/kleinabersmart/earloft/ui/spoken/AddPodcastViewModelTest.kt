package de.kleinabersmart.earloft.ui.spoken

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import de.kleinabersmart.earloft.core.data.podcast.PodcastDirectorySearch
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.network.podcast.directory.PodcastDirectory
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

/** "Add podcast" page: feed address and enabled directories (concept 12.10). */
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
    @Inject lateinit var podcastDao: PodcastDao
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    private val viewModel by lazy { AddPodcastViewModel(podcasts, directorySearch, settings, opmlImporter, applicationScope) }

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
    fun `an archived subscription asks to reactivate instead of failing`() = runTest {
        podcastDao.upsertFeed(PodcastFeedEntity(feedId = "f", url = "https://example.com/feed.xml", title = "Alt", archived = true))

        viewModel.subscribe("https://example.com/feed.xml")

        assertEquals(
            AddPodcastState.Archived("f", "Alt"),
            awaitReal { viewModel.add.first { it is AddPodcastState.Archived } },
        )
    }

    @Test
    fun `no directory is switched on until the user does it`() = runTest {
        assertEquals(emptyList<PodcastDirectory>(), awaitReal { viewModel.directories.first { it != null } })

        settings.setDirectory(PodcastDirectory.APPLE, true)

        assertEquals(listOf(PodcastDirectory.APPLE), awaitReal { viewModel.directories.first { !it.isNullOrEmpty() } })
    }

    @Test
    fun `directories can be switched on and off right on the page`() = runTest {
        viewModel.setDirectory(PodcastDirectory.APPLE, true)
        assertEquals(listOf(PodcastDirectory.APPLE), awaitReal { viewModel.directories.first { !it.isNullOrEmpty() } })

        viewModel.setDirectory(PodcastDirectory.APPLE, false)
        assertEquals(emptyList<PodcastDirectory>(), awaitReal { viewModel.directories.first { it?.isEmpty() == true } })
    }

    @Test
    fun `a blank search term does not search`() {
        viewModel.search("   ")

        assertEquals(DirectorySearchState.Idle, viewModel.search.value)
    }
}
