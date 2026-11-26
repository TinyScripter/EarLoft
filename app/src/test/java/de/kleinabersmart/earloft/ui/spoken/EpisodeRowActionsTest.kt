package de.kleinabersmart.earloft.ui.spoken

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.download.DownloadStart
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServer
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.earloft.testing.MainDispatcherRule
import de.kleinabersmart.earloft.testing.awaitReal
import de.kleinabersmart.earloft.ui.snackbar.SnackbarMessage
import de.kleinabersmart.earloft.ui.snackbar.Snackbars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import javax.inject.Inject

/** The episode actions all lists share (review2 J46), with the rule for trial subscriptions (J4). */
@OptIn(ExperimentalCoroutinesApi::class)
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
    @Inject lateinit var snackbars: Snackbars
    @Inject lateinit var servers: AudiobookshelfServers
    @Inject lateinit var registry: AbsServerRegistry

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

    /** What the user is told while the test runs. */
    private fun kotlinx.coroutines.test.TestScope.collectMessages(): List<SnackbarMessage> {
        val received = mutableListOf<SnackbarMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { snackbars.messages.collect { received += it } }
        return received
    }

    @Test
    fun `taking an episode out of the queue says so and undo puts it back at its old place`() = runTest {
        val first = episode("sub", "a")
        val second = episode("sub", "b")
        val third = episode("sub", "c")
        listOf(first, second, third).forEach { continueListening.append(it) }
        val messages = collectMessages()

        rows.setQueued(second, queued = false)

        // review4 T38: the row's button used to remove silently.
        assertEquals(listOf(first, third), queue())
        val message = messages.single()
        assertTrue(message.text, "b" in message.text)
        assertNotNull(message.actionLabel)
        message.onAction!!.invoke()
        awaitReal { continueListening.episodes.first { list -> list.map { it.book.id } == listOf(first, second, third) } }
    }

    @Test
    fun `a trial episode leaves the queue with a notice but without undo`() = runTest {
        podcastDao.upsertFeed(PodcastFeedEntity(feedId = "trial", url = "https://example.com/trial.xml", title = "Trial", subscribed = false))
        val trial = episode("trial", "t1")
        continueListening.append(trial)
        val messages = collectMessages()

        rows.removeFromQueue(trial)

        assertEquals(listOf(null), messages.map { it.actionLabel })
    }

    @Test
    fun `removing an episode that is not in the queue says nothing`() = runTest {
        val loose = episode("sub", "x")
        val messages = collectMessages()

        rows.removeFromQueue(loose)

        assertEquals(emptyList<SnackbarMessage>(), messages)
    }

    @Test
    fun `taking a book out of continue listening says so and undo brings it back with its position`() = runTest {
        val book = bookDao.upsertBook(
            BookEntity(serverId = "abs", kind = BookKind.BOOK, remoteId = "b", title = "Book", normTitle = "book", author = "", durationMs = 600_000),
        )
        bookDao.upsertProgress(BookProgressEntity(bookId = book, positionMs = 123_000, durationMs = 600_000, updatedAt = 1))
        val messages = collectMessages()

        rows.hideBook(book)

        // review4 T39: no notice and no way back before.
        assertEquals(true, bookDao.progress(book)?.hidden)
        assertNotNull(messages.single().actionLabel)
        messages.single().onAction!!.invoke()
        awaitReal { bookDao.progress(book)?.takeIf { !it.hidden } }
        assertEquals(123_000L, bookDao.progress(book)?.positionMs)
    }

    @Test
    fun `taking a book out of continue listening says so without waiting for its server`() = runTest {
        // A server that takes the connection but never answers: before, the notice came only after its timeout.
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { silent ->
            try {
                WorkManager.initialize(ApplicationProvider.getApplicationContext(), Configuration.Builder().build())
            } catch (e: IllegalStateException) {
                // already initialized
            }
            servers.server("abs")
            registry.register(AbsServer("abs", "http://127.0.0.1:${silent.localPort}/".toHttpUrl(), "key"))
            val book = bookDao.upsertBook(
                BookEntity(serverId = "abs", kind = BookKind.BOOK, remoteId = "b", title = "Book", normTitle = "book", author = "", durationMs = 600_000),
            )
            bookDao.upsertProgress(BookProgressEntity(bookId = book, positionMs = 123_000, durationMs = 600_000, updatedAt = 1))
            val messages = collectMessages()

            withContext(Dispatchers.Default) { withTimeout(3_000) { rows.hideBook(book) } }

            assertEquals(true, bookDao.progress(book)?.hidden)
            assertNotNull(messages.single().actionLabel)
        }
    }
}
