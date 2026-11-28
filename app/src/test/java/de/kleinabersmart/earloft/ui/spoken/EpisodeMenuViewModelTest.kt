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
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.download.BookDownloads
import de.kleinabersmart.earloft.core.download.EpisodeDownloads
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.ServicePlayer
import de.kleinabersmart.earloft.core.playback.SpokenEntry
import de.kleinabersmart.earloft.testing.FakePlaybackConnection
import de.kleinabersmart.earloft.testing.MainDispatcherRule
import de.kleinabersmart.earloft.testing.awaitReal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    @Inject lateinit var podcastDao: PodcastDao

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

    @Test
    fun `removing selected episodes from the queue keeps the loaded one and undo restores the old order`() = runTest {
        val first = episode("q1", "menu-sub")
        val second = episode("q2", "menu-sub")
        val third = episode("q3", "menu-sub")
        val fourth = episode("q4", "menu-sub")
        listOf(first, second, third, fourth).forEach { continueListening.append(it) }
        playback.state.value = PlayerState(spoken = SpokenEntry(bookId = first, trackIndex = 0, title = "q1", author = "", isEpisode = true))

        val done = CompletableDeferred<Pair<Int, (suspend () -> Unit)?>>()
        viewModel.removeFromQueue(listOf(first, second, third, fourth).map { item(it, "menu-sub") }) { count, undo ->
            done.complete(count to undo)
        }
        val (removedCount, restore) = awaitReal { done.await() }

        // Loaded episode stays, the other three leave; Undo brings all three back in their original places (review5 P45).
        assertEquals(3, removedCount)
        assertEquals(listOf(first), continueListening.episodes.first().map { it.book.id })
        assertNotNull(restore)

        viewModel.undo(restore!!)
        awaitReal { continueListening.episodes.first { list -> list.map { it.book.id } == listOf(first, second, third, fourth) } }
    }

    @Test
    fun `removing selected episodes from the queue offers no undo for trial episodes`() = runTest {
        podcastDao.upsertFeed(PodcastFeedEntity(feedId = "menu-trial", url = "https://example.com/trial.xml", title = "Trial", subscribed = false))
        podcastDao.upsertFeed(PodcastFeedEntity(feedId = "menu-sub2", url = "https://example.com/sub.xml", title = "Sub"))
        val sub1 = episode("ms1", "menu-sub2")
        val trial1 = episode("mt1", "menu-trial")
        val trial2 = episode("mt2", "menu-trial")
        val sub2 = episode("ms2", "menu-sub2")
        listOf(sub1, trial1, trial2, sub2).forEach { continueListening.append(it) }

        val trialDone = CompletableDeferred<(suspend () -> Unit)?>()
        viewModel.removeFromQueue(listOf(item(trial1, "menu-trial"))) { _, undo -> trialDone.complete(undo) }
        assertNull(awaitReal { trialDone.await() })
        assertEquals(listOf(sub1, trial2, sub2), continueListening.episodes.first().map { it.book.id })

        val mixedDone = CompletableDeferred<(suspend () -> Unit)?>()
        viewModel.removeFromQueue(listOf(item(sub1, "menu-sub2"), item(trial2, "menu-trial"))) { _, undo -> mixedDone.complete(undo) }
        val mixedUndo = awaitReal { mixedDone.await() }
        assertNotNull(mixedUndo)
        assertEquals(listOf(sub2), continueListening.episodes.first().map { it.book.id })

        // Mixed selection: Undo restores the subscribed episode in its place, not the trial one.
        viewModel.undo(mixedUndo!!)
        awaitReal { continueListening.episodes.first { list -> list.map { it.book.id } == listOf(sub1, sub2) } }
    }

    @Test
    fun `undo after taking one out keeps what changed in the queue meanwhile`() = runTest {
        val a = episode("u1")
        val b = episode("u2")
        val c = episode("u3")
        listOf(a, b, c).forEach { continueListening.append(it) }
        val undo = CompletableDeferred<suspend () -> Unit>()

        viewModel.setQueued(b, queued = false) { undo.complete(it) }
        val restore = awaitReal { undo.await() }
        awaitReal { continueListening.episodes.first { list -> list.map { it.book.id } == listOf(a, c) } }
        // Meanwhile the last one moved to the front: that stays, only the removed one comes back after its neighbor (review5 8.12).
        awaitReal { continueListening.reorder(listOf(c, a), c) }
        awaitReal { continueListening.episodes.first { list -> list.map { it.book.id } == listOf(c, a) } }
        viewModel.undo(restore)

        // The order itself is checked in ReinsertAtOldPlacesTest.
        awaitReal { continueListening.episodes.first { list -> list.map { it.book.id } == listOf(c, a, b) } }
    }

    private suspend fun episode(guid: String, feedId: String = "menu-sub"): Long = bookDao.upsertBook(
        BookEntity(serverId = feedId, kind = BookKind.EPISODE, remoteId = guid, title = guid, normTitle = guid, author = "", durationMs = 60_000),
    )

    private fun item(id: Long, feedId: String) = BookWithProgress(
        Book(id = id, serverId = feedId, kind = BookKind.EPISODE, title = "ep$id", author = "", durationMs = 60_000, remoteId = "ep$id"),
        progress = null,
    )
}
