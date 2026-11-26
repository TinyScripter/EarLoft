package de.kleinabersmart.earloft.ui.spoken

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.podcast.RecentlyAdded
import de.kleinabersmart.earloft.core.data.spoken.ArdBooks
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.download.BookDownloads
import de.kleinabersmart.earloft.core.download.EpisodeDownloads
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.playback.ServicePlayer
import de.kleinabersmart.earloft.testing.FakePlaybackConnection
import de.kleinabersmart.earloft.testing.MainDispatcherRule
import de.kleinabersmart.earloft.testing.awaitReal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** What the episode menu knows before it opens (review4 Y20). */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class EpisodeMenuViewModelTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val main = MainDispatcherRule()

    @Inject lateinit var podcasts: PodcastRepository
    @Inject lateinit var continueListening: ContinueListening
    @Inject lateinit var recentlyAdded: RecentlyAdded
    @Inject lateinit var downloads: EpisodeDownloads
    @Inject lateinit var playback: FakePlaybackConnection
    @Inject lateinit var books: BookRepository
    @Inject lateinit var bookDownloads: BookDownloads
    @Inject lateinit var progressSync: BookProgressSync
    @Inject lateinit var servicePlayer: ServicePlayer
    @Inject lateinit var launcher: BookLauncher
    @Inject lateinit var ardBooks: ArdBooks
    @Inject lateinit var rows: EpisodeRowActions
    @Inject lateinit var bookDao: BookDao

    private val viewModel by lazy {
        EpisodeMenuViewModel(
            podcasts, continueListening, recentlyAdded, downloads, playback, books, bookDownloads, progressSync, servicePlayer,
            launcher, ardBooks, rows, CoroutineScope(Dispatchers.Unconfined),
        )
    }

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun `the menu learns about a queued episode before it shows anything`() = runTest {
        val first = episode("a")
        val second = episode("b")
        continueListening.append(first)
        continueListening.append(second)
        // Before, the menu showed "Add to queue" until its own flows had caught up.
        assertNull("Nothing to show before it is known", viewModel.facts.value)

        val facts = awaitReal { viewModel.facts.filterNotNull().first() }

        assertEquals(setOf(first, second), facts.queued)
        assertEquals(first, facts.queueHead)
        assertEquals(second, facts.queueSecond)
    }

    private suspend fun episode(guid: String): Long = bookDao.upsertBook(
        BookEntity(serverId = "sub", kind = BookKind.EPISODE, remoteId = guid, title = guid, normTitle = guid, author = "", durationMs = 60_000),
    )
}
