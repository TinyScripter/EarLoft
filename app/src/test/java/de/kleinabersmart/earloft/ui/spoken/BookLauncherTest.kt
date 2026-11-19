package de.kleinabersmart.earloft.ui.spoken

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.SpokenEntry
import de.kleinabersmart.earloft.testing.FakePlaybackConnection
import de.kleinabersmart.earloft.testing.FakePlaybackConnection.Command
import de.kleinabersmart.earloft.testing.MainDispatcherRule
import de.kleinabersmart.earloft.testing.awaitReal
import de.kleinabersmart.earloft.ui.components.PlayControlViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * When the mobile data prompt appears, as the settings text describes it (review F40): when starting in the app
 * yes, when resuming what is loaded and when starting from the watch no; "Play" applies until the app ends.
 */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class BookLauncherTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val main = MainDispatcherRule()

    @Inject lateinit var playback: FakePlaybackConnection
    @Inject lateinit var dao: BookDao
    @Inject lateinit var settings: SpokenSettings
    @Inject lateinit var launcher: BookLauncher
    @Inject lateinit var library: LibraryRepository

    private var bookId = 0L
    private var otherId = 0L

    @Before
    fun setUp() = runTest {
        hilt.inject()
        settings.setAskBeforeMobileStreaming(true)
        // Cellular: metered, so without NOT_METERED.
        val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(ConnectivityManager::class.java)
        val capabilities = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
        bookId = serverBook("li_1")
        otherId = serverBook("li_2")
    }

    /** A book from an Audiobookshelf server whose file is not downloaded. */
    private suspend fun serverBook(remoteId: String): Long {
        val id = dao.upsertBook(
            BookEntity(
                serverId = "abs", kind = BookKind.BOOK, remoteId = remoteId, title = remoteId, normTitle = remoteId,
                author = "Author", durationMs = HOUR_MS, detailsLoaded = true,
            ),
        )
        dao.insertTracks(listOf(BookTrackEntity(id, trackIndex = 0, remoteFileId = "file", startMs = 0, durationMs = HOUR_MS)))
        return id
    }

    private suspend fun awaitQuestion() = awaitReal { launcher.question.first { it != null }!! }

    private suspend fun awaitStarted(id: Long) = awaitReal { playback.commands.first { Command.PlayBook(id, null) in it } }

    /** Gives the app scope time; nothing may have happened afterwards. */
    private suspend fun settle() = awaitReal { delay(300.milliseconds) }

    @Test
    fun `starting a book that is not downloaded in the app asks first`() = runTest {
        launcher.play(bookId)

        assertEquals(bookId, awaitQuestion().bookId)
        settle()
        assertEquals(emptyList<Command>(), playback.commands.value)
    }

    @Test
    fun `play in the question applies to later starts until the app is closed`() = runTest {
        launcher.play(bookId)
        BookLaunchViewModel(launcher, settings).playOverMobile(awaitQuestion(), always = false)
        awaitStarted(bookId)

        launcher.play(otherId)

        awaitStarted(otherId)
        assertNull(launcher.question.value)
    }

    @Test
    fun `a start from the watch never asks`() = runTest {
        launcher.playConfirmed(bookId)

        awaitStarted(bookId)
        assertNull(launcher.question.value)
    }

    @Test
    fun `resuming the loaded book from a tile does not ask`() = runTest {
        playback.state.value = PlayerState(spoken = SpokenEntry(bookId, trackIndex = 0, title = "li_1", author = "Author"), isPlaying = false)

        PlayControlViewModel(library, playback, launcher).playOrPauseBook(bookId)

        settle()
        assertEquals(listOf(Command.Simple("togglePlayPause")), playback.commands.value)
        assertNull(launcher.question.value)
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
