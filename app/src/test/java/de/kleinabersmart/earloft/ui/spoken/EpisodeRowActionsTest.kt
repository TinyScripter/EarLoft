package de.kleinabersmart.earloft.ui.spoken

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.download.DownloadStart
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.testing.MainDispatcherRule
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

/** The episode actions all lists share (review2 J46), with the rule for trial subscriptions (J4). */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class EpisodeRowActionsTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val main = MainDispatcherRule()

    @Inject lateinit var rows: EpisodeRowActions
    @Inject lateinit var continueListening: ContinueListening
    @Inject lateinit var podcastDao: PodcastDao
    @Inject lateinit var bookDao: BookDao

    @Before
    fun setUp() {
        hilt.inject()
    }

    private suspend fun episode(feedId: String, guid: String): Long = bookDao.upsertBook(
        BookEntity(serverId = feedId, kind = BookKind.EPISODE, remoteId = guid, title = guid, normTitle = guid, author = "", durationMs = 60_000),
    )

    private suspend fun queue(): List<Long> = continueListening.episodes.first().map { it.book.id }

    @Test
    fun `downloading an episode of a trial subscription puts it into the queue first`() = runTest {
        podcastDao.upsertFeed(PodcastFeedEntity(feedId = "trial", url = "https://example.com/trial.xml", title = "Trial", subscribed = false))
        podcastDao.upsertFeed(PodcastFeedEntity(feedId = "sub", url = "https://example.com/sub.xml", title = "Subscribed"))
        val trial = episode("trial", "t1")
        val subscribed = episode("sub", "s1")

        rows.download(listOf(subscribed, trial))

        // Otherwise the trial cleanup would delete the download a second later; a subscription's episode stays where it is.
        assertEquals(listOf(trial), queue())
    }

    @Test
    fun `a trial episode already in the queue keeps its place`() = runTest {
        podcastDao.upsertFeed(PodcastFeedEntity(feedId = "trial", url = "https://example.com/trial.xml", title = "Trial", subscribed = false))
        val first = episode("trial", "t1")
        val second = episode("trial", "t2")
        continueListening.append(first)
        continueListening.append(second)

        rows.download(first)

        assertEquals(listOf(first, second), queue())
    }

    @Test
    fun `offline outweighs waiting for Wi-Fi and books name their own settings`() {
        assertEquals(
            R.string.podcast_download_offline,
            downloadHint(listOf(DownloadStart.WAITING_FOR_WIFI, DownloadStart.OFFLINE, DownloadStart.STARTED)),
        )
        assertEquals(R.string.podcast_download_waiting_wifi, downloadHint(listOf(DownloadStart.STARTED, DownloadStart.WAITING_FOR_WIFI)))
        assertEquals(R.string.book_download_waiting_wifi, downloadHint(listOf(DownloadStart.WAITING_FOR_WIFI), book = true))
        assertNull(downloadHint(listOf(DownloadStart.STARTED)))
    }
}
