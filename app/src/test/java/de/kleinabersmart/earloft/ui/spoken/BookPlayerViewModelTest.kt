package de.kleinabersmart.earloft.ui.spoken

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenEffectsRepository
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.LOCAL_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.SpokenEntry
import de.kleinabersmart.earloft.testing.FakePlaybackConnection
import de.kleinabersmart.earloft.testing.FakePlaybackConnection.Command
import de.kleinabersmart.earloft.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import de.kleinabersmart.earloft.testing.awaitReal
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject
import de.kleinabersmart.earloft.core.data.podcast.PodcastTranscripts

/** Position within the whole book across several files (review A3). */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class BookPlayerViewModelTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val main = MainDispatcherRule()

    @Inject lateinit var playback: FakePlaybackConnection
    @Inject lateinit var dao: BookDao
    @Inject lateinit var books: BookRepository
    @Inject lateinit var covers: BookCovers
    @Inject lateinit var continueListening: ContinueListening
    @Inject lateinit var launcher: BookLauncher
    @Inject lateinit var effects: SpokenEffectsRepository
    @Inject lateinit var transcripts: PodcastTranscripts

    // Hilt doesn't allow injecting a @HiltViewModel; but all dependencies come from the real graph.
    private val viewModel by lazy { BookPlayerViewModel(playback, books, covers, continueListening, launcher, effects, transcripts) }

    private var bookId = 0L

    @Before
    fun setUp() = runTest {
        hilt.inject()
        // A book on the device made of two one-hour files with three chapters; no server, so no network.
        bookId = dao.upsertBook(
            BookEntity(
                serverId = LOCAL_BOOK_SERVER_ID,
                kind = BookKind.BOOK,
                remoteId = "book",
                title = "Book",
                normTitle = "book",
                author = "Author",
                durationMs = 2 * HOUR_MS,
                detailsLoaded = true,
            ),
        )
        dao.insertTracks(
            listOf(
                BookTrackEntity(bookId, trackIndex = 0, remoteFileId = "/sdcard/Audiobooks/part1.m4b", startMs = 0, durationMs = HOUR_MS),
                BookTrackEntity(bookId, trackIndex = 1, remoteFileId = "/sdcard/Audiobooks/part2.m4b", startMs = HOUR_MS, durationMs = HOUR_MS),
            ),
        )
        dao.insertChapters(
            listOf(
                BookChapterEntity(bookId, chapterIndex = 0, title = "One", startMs = 0, endMs = 50 * MINUTE_MS),
                BookChapterEntity(bookId, chapterIndex = 1, title = "Two", startMs = 50 * MINUTE_MS, endMs = 80 * MINUTE_MS),
                BookChapterEntity(bookId, chapterIndex = 2, title = "Three", startMs = 80 * MINUTE_MS, endMs = 2 * HOUR_MS),
            ),
        )
    }

    /** Plays the second file at [offsetMs] and waits until the ViewModel has loaded the timeline. */
    private suspend fun TestScope.playSecondFileAt(offsetMs: Long) {
        playback.state.value = PlayerState(spoken = SpokenEntry(bookId, trackIndex = 1, title = "Book", author = "Author"), isPlaying = true)
        playback.currentPositionMs = offsetMs
        backgroundScope.launch { viewModel.timeline.collect {} }
        awaitReal { viewModel.timeline.first { it != null } }
    }

    @Test
    fun `book position adds the start of the playing file`() = runTest {
        playSecondFileAt(5 * MINUTE_MS)

        assertEquals(HOUR_MS + 5 * MINUTE_MS, viewModel.bookPositionMs())
        assertEquals(2 * HOUR_MS, viewModel.durationMs())
    }

    @Test
    fun `seeking in the book lands in the right file`() = runTest {
        playSecondFileAt(0)

        viewModel.seekToBook(90 * MINUTE_MS)
        viewModel.seekToBook(10 * MINUTE_MS)

        assertEquals(
            listOf(Command.SeekToTrack(1, 30 * MINUTE_MS), Command.SeekToTrack(0, 10 * MINUTE_MS)),
            playback.commands.value,
        )
    }

    @Test
    fun `next chapter jumps across the file boundary`() = runTest {
        // 65 min into the book = chapter "Two"; the next one starts at 80 min, i.e. 20 min into the second file.
        playSecondFileAt(5 * MINUTE_MS)

        viewModel.nextChapter()

        assertEquals(listOf(Command.SeekToTrack(1, 20 * MINUTE_MS)), playback.commands.value)
    }

    @Test
    fun `previous chapter twice goes back although the player still reports the old position`() = runTest {
        // 65 min = 15 min into "Two": first to its start (50 min), then – the player still reports 65 min while casting – to "One".
        playSecondFileAt(5 * MINUTE_MS)

        viewModel.previousChapter()
        viewModel.previousChapter()

        assertEquals(listOf(Command.SeekToTrack(0, 50 * MINUTE_MS), Command.SeekToTrack(0, 0)), playback.commands.value)
    }

    @Test
    fun `once the player has caught up its position counts again`() = runTest {
        playSecondFileAt(5 * MINUTE_MS)
        viewModel.previousChapter()
        // The seek has arrived and it played on for 10 s: back to the start of "Two" again.
        playback.state.value = playback.state.value.copy(spoken = playback.state.value.spoken?.copy(trackIndex = 0))
        playback.currentPositionMs = 50 * MINUTE_MS + 10_000

        viewModel.previousChapter()

        assertEquals(listOf(Command.SeekToTrack(0, 50 * MINUTE_MS), Command.SeekToTrack(0, 50 * MINUTE_MS)), playback.commands.value)
    }

    @Test
    fun `an old jump no longer counts`() = runTest {
        var now = 1_000_000L
        viewModel.clock = { now }
        playSecondFileAt(5 * MINUTE_MS)
        viewModel.previousChapter()
        now += 16_000

        viewModel.previousChapter()

        assertEquals(listOf(Command.SeekToTrack(0, 50 * MINUTE_MS), Command.SeekToTrack(0, 50 * MINUTE_MS)), playback.commands.value)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
    }
}
