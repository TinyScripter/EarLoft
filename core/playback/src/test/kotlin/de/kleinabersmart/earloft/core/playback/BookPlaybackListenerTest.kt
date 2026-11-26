package de.kleinabersmart.earloft.core.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.spoken.AbsSessionReporter
import de.kleinabersmart.earloft.core.data.spoken.AbsTokenRenewal
import de.kleinabersmart.earloft.core.data.spoken.ArdBooks
import de.kleinabersmart.earloft.core.data.spoken.ArdUploadAdoption
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.BookSyncer
import de.kleinabersmart.earloft.core.data.spoken.Bookmarks
import de.kleinabersmart.earloft.core.data.spoken.LocalBooks
import de.kleinabersmart.earloft.core.data.spoken.PreparedBook
import de.kleinabersmart.earloft.core.data.spoken.ResumeRule
import de.kleinabersmart.earloft.core.data.spoken.SpokenEffectsRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.data.stats.StatsRepository
import de.kleinabersmart.earloft.core.data.sync.SweepGuard
import de.kleinabersmart.earloft.core.data.wear.CredentialsStamps
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.datasource.local.LocalBookScanner
import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookTimeline
import de.kleinabersmart.earloft.core.model.LOCAL_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsBooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What the player does with books and episodes beyond playing them: which state it records, when it follows another
 * device, how it rewinds after a pause, chapters, speed and the end of an episode. Real repositories on an in-memory
 * database (books from the device, so no network); the player is a [SimpleBasePlayer] whose position the test moves.
 */
@RunWith(RobolectricTestRunner::class)
class BookPlaybackListenerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: EarLoftDatabase
    private lateinit var books: BookRepository
    private lateinit var effects: SpokenEffectsRepository
    private lateinit var listener: BookPlaybackListener
    private lateinit var progressSync: BookProgressSync
    private val settings = SpokenSettings(context)
    private val player = BookPlayer()

    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private val persistScope = CoroutineScope(Dispatchers.IO + Job())

    private val endedEpisodes = mutableListOf<Long>()
    private val chapters = mutableListOf<BookChapter?>()

    /** Jumps to a file outside the player's window (book, track index, offset). */
    private val outsideWindow = mutableListOf<Triple<Long, Int, Long>>()

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.bookDao()
        val client = OkHttpClient()
        val api = AbsApi(client)
        val secrets = object : SecretStore {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val registry = AbsServerRegistry()
        val authApi = AbsAuthApi(client)
        val servers = AudiobookshelfServers(dao, secrets, api, authApi, AbsTokenRenewal(secrets, registry, authApi), registry, CredentialsStamps(secrets))
        val policy = PlaybackPolicy()
        progressSync = BookProgressSync(context, dao, servers, api, policy)
        val scanner = LocalBookScanner(context)
        val covers = BookCovers(context, dao, db.podcastDao(), servers, api, PodcastFeedApi(client), scanner)
        val bookmarks = Bookmarks(context, dao, servers, api, policy)
        val sessions = AbsSessionReporter(context, db.absSessionDao(), dao, servers, api, policy)
        val syncer = BookSyncer(
            dao, servers, api, progressSync, sessions, covers, bookmarks, LocalBooks(dao, scanner, settings, covers, SweepGuard(context)),
            ArdBooks(dao, ArdSoundsBooks(ArdSoundsApi(client)), settings, covers) { _, _ -> },
            ArdUploadAdoption(context, dao, progressSync, bookmarks),
        )
        books = BookRepository(dao, syncer)
        effects = SpokenEffectsRepository(dao, db.podcastDao(), settings)
        settings.setBookResume(ResumeRule(ResumeRule.DEFAULT_AFTER_S, ResumeRule.DEFAULT_REWIND_S))
        listener = BookPlaybackListener(
            player, books, progressSync, settings, StatsRepository(db.statsDao(), dao, db.podcastDao()),
            sessions, mainScope, persistScope,
            onEpisodeEnded = { endedEpisodes += it },
            onChapterChanged = { chapters += it },
            effects = effects,
            seekOutsideWindow = { bookId, trackIndex, offsetMs ->
                outsideWindow += Triple(bookId, trackIndex, offsetMs)
                true
            },
        )
        // SimpleBasePlayer reads its first state on first access without events: that has to be the empty one.
        player.mediaItemCount
        player.addListener(listener)
    }

    @After
    fun tearDown() {
        mainScope.cancel()
        persistScope.cancel()
        db.close()
    }

    // --- Library ---

    /** A book from the device with two files of 10 minutes and a chapter every 5 minutes. */
    private suspend fun book(title: String = "Book", kind: BookKind = BookKind.BOOK, files: Int = 2): Long {
        val dao = db.bookDao()
        val bookId = dao.upsertBook(
            BookEntity(
                serverId = if (kind == BookKind.EPISODE) "local:podcast" else LOCAL_BOOK_SERVER_ID, kind = kind, remoteId = title,
                title = title, normTitle = title.lowercase(), author = "Author", durationMs = files * FILE_MS, detailsLoaded = true,
            ),
        )
        val tracks = (0 until files).map { BookTrackEntity(bookId, it, "content://media/$title/$it", it * FILE_MS, FILE_MS, "audio/mpeg") }
        val chapters = if (kind == BookKind.EPISODE) emptyList() else (0 until files * 2).map {
            BookChapterEntity(bookId, it, "Chapter ${it + 1}", it * CHAPTER_MS, (it + 1) * CHAPTER_MS)
        }
        dao.replaceDetails(bookId, tracks, chapters)
        return bookId
    }

    /**
     * Puts [bookId] into the player, paused at [positionMs] in the book, as the service does; from file [windowStart] on only
     * (while casting the player holds a window of a long book).
     */
    private suspend fun load(bookId: Long, positionMs: Long = 0, unavailable: Set<Int> = emptySet(), windowStart: Int = 0) {
        val timeline = books.timeline(bookId)!!
        val prepared = PreparedBook(
            book = books.bookNow(bookId)!!,
            timeline = BookTimeline(timeline.durationMs, timeline.tracks.map { it.copy(unavailable = it.index in unavailable) }, timeline.chapters),
            trackUrls = timeline.tracks.map { it.remoteFileId },
            startPositionMs = positionMs,
            resumedFromServer = false,
            speed = 1f,
        )
        val located = timeline.locate(positionMs)
        player.load(prepared.toMediaItems().drop(windowStart), located.trackIndex - windowStart, located.offsetMs)
        // The listener has its timeline once it knows where in the book the player is.
        eventually { assertTrue(listener.sleepSpot() != null) }
    }

    private suspend fun progress(bookId: Long) = db.bookDao().progress(bookId)

    // --- Waiting ---

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Lets the player thread and the database work until [check] holds (at most 5 s). */
    private fun eventually(check: () -> Unit) {
        val until = System.currentTimeMillis() + 5_000
        while (true) {
            idle()
            try {
                check()
                return
            } catch (e: AssertionError) {
                if (System.currentTimeMillis() > until) throw e
                Thread.sleep(10)
            }
        }
    }

    /** Lets everything settle that is not supposed to change anything. */
    private fun quiet() {
        repeat(20) {
            idle()
            Thread.sleep(10)
        }
    }

    // --- Tests ---

    @Test
    fun `a book that was only loaded never writes its state`() = runBlocking {
        val first = book("First")
        val second = book("Second")
        load(first, positionMs = 90_000)
        quiet()

        load(second)
        listener.finish()
        quiet()

        assertNull(progress(first))
        assertNull(progress(second))
    }

    @Test
    fun `playing and pausing stores the position in the whole book`() = runBlocking {
        val bookId = book()
        load(bookId, positionMs = FILE_MS + 30_000)
        player.play()
        idle()
        player.moveTo(90_000)

        player.pause()

        eventually { assertEquals(FILE_MS + 90_000, runBlocking { progress(bookId) }?.positionMs) }
    }

    @Test
    fun `a newer state from another device moves the paused player and is not overwritten`() = runBlocking {
        val bookId = book()
        load(bookId, positionMs = 60_000)
        player.play()
        idle()
        player.pause()
        eventually { assertEquals(60_000L, runBlocking { progress(bookId) }?.positionMs) }

        // The watch saved a later position.
        val foreign = BookProgressEntity(bookId, positionMs = FILE_MS + 120_000, durationMs = 2 * FILE_MS, updatedAt = System.currentTimeMillis() + 1_000)
        db.bookDao().upsertProgress(foreign)

        eventually {
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals(120_000L, player.currentPosition)
        }
        listener.finish()
        quiet()
        assertEquals(foreign.positionMs, progress(bookId)!!.positionMs)
        assertEquals(foreign.updatedAt, progress(bookId)!!.updatedAt)
    }

    @Test
    fun `scrubbing a paused book is stored and a change of its effects does not move it back`() = runBlocking {
        val bookId = book()
        load(bookId, positionMs = 300_000)
        player.play()
        idle()
        player.pause()
        eventually { assertEquals(300_000L, runBlocking { progress(bookId) }?.positionMs) }

        // Two minutes back while paused: this is the new state at once, not only when the playback stops.
        player.seekTo(0, 180_000)
        eventually { assertEquals(180_000L, runBlocking { progress(bookId) }?.positionMs) }

        // The speed changes the row but moves no position; neither does the sending that marks it as sent.
        val before = progress(bookId)!!
        db.bookDao().setEffects(bookId, speed = 1.5f, skipSilence = null, volumeBoostDb = null)
        db.bookDao().markClean(bookId, before.updatedAt)
        quiet()

        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(180_000L, player.currentPosition)
        assertEquals(180_000L, progress(bookId)!!.positionMs)
    }

    @Test
    fun `an earlier own save that lands last does not pull the paused player back`() = runBlocking {
        val bookId = book()
        load(bookId, positionMs = 300_000)
        player.play()
        idle()
        player.pause()
        eventually { assertEquals(300_000L, runBlocking { progress(bookId) }?.positionMs) }
        player.seekTo(0, 240_000)
        eventually { assertEquals(240_000L, runBlocking { progress(bookId) }?.positionMs) }
        player.seekTo(0, 180_000)
        eventually { assertEquals(180_000L, runBlocking { progress(bookId) }?.positionMs) }

        // The save of the first seek is written last: the row holds its position with the newest timestamp.
        db.bookDao().upsertProgress(progress(bookId)!!.copy(positionMs = 240_000, updatedAt = System.currentTimeMillis() + 1_000))
        quiet()

        assertEquals(180_000L, player.currentPosition)
    }

    @Test
    fun `scrubbing a paused book close to the end does not finish it`() = runBlocking {
        val bookId = book()
        load(bookId, positionMs = 300_000)
        player.play()
        idle()
        player.pause()
        eventually { assertEquals(300_000L, runBlocking { progress(bookId) }?.positionMs) }

        // Ten seconds before the end: within the tolerance for a stopped book, but a seek is no stop.
        player.seekTo(1, FILE_MS - 10_000)
        eventually { assertEquals(2 * FILE_MS - 10_000, runBlocking { progress(bookId) }?.positionMs) }
        assertFalse(progress(bookId)!!.finished)
    }

    @Test
    fun `unplayed on the loaded paused book keeps it out of continue listening`() = runBlocking {
        val bookId = book()
        load(bookId, positionMs = 300_000)
        player.play()
        idle()
        player.pause()
        eventually { assertEquals(300_000L, runBlocking { progress(bookId) }?.positionMs) }

        // What the menu does: first the state at the start and hidden, then the loaded player to the start.
        progressSync.record(bookId, 0, 2 * FILE_MS, finished = false, force = true, hidden = true)
        player.seekTo(0, 0)
        quiet()

        assertEquals(0L, progress(bookId)!!.positionMs)
        assertTrue(progress(bookId)!!.hidden)

        // Playing brings it back.
        player.play()
        idle()
        player.pause()
        eventually { assertFalse(runBlocking { progress(bookId) }!!.hidden) }
    }

    @Test
    fun `after a long pause playback rewinds, after a short one it does not`() = runBlocking {
        val bookId = book()
        val longAgo = System.currentTimeMillis() - 3_600_000
        listener.restoredPaused(bookId, longAgo)
        load(bookId, positionMs = 200_000)

        player.play()
        eventually { assertEquals(200_000L - ResumeRule.DEFAULT_REWIND_S * 1000, player.currentPosition) }

        // Paused and played again right away: no further rewind.
        player.pause()
        idle()
        player.play()
        quiet()
        assertEquals(200_000L - ResumeRule.DEFAULT_REWIND_S * 1000, player.currentPosition)
    }

    @Test
    fun `a chapter jump into a file before the cast window loads a window there`() = runBlocking {
        val bookId = book(files = 3)
        // The player holds files 1 and 2; it stands 10 s into the third chapter (file 1).
        load(bookId, positionMs = FILE_MS + 10_000, windowStart = 1)

        // Back to the chapter start: in the window, no reload.
        assertTrue(listener.skipChapter(forward = false))
        idle()
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(0L, player.currentPosition)
        assertTrue(outsideWindow.isEmpty())

        // Once more: chapter 2 lies in file 0, outside the window.
        assertTrue(listener.skipChapter(forward = false))
        assertEquals(listOf(Triple(bookId, 0, CHAPTER_MS)), outsideWindow)

        // Directly by track index (chapter list, slider): in the window by its place there, not by its index.
        assertTrue(listener.seekToTrack(2, 30_000))
        idle()
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(30_000L, player.currentPosition)
    }

    @Test
    fun `a paused jump outside the cast window is saved`() = runBlocking {
        val bookId = book(files = 3)
        load(bookId, positionMs = FILE_MS + 10_000, windowStart = 1)

        // The new window comes without a seek; the jump is the new state all the same.
        assertTrue(listener.seekToTrack(0, 30_000))
        assertEquals(listOf(Triple(bookId, 0, 30_000L)), outsideWindow)
        eventually { assertEquals(30_000L, runBlocking { progress(bookId) }?.positionMs) }

        // As the receiver does: first an empty queue, then the new window. The old position must not come back.
        player.load(emptyList(), 0, 0)
        load(bookId, positionMs = 30_000)
        quiet()
        assertEquals(30_000L, progress(bookId)!!.positionMs)
    }

    @Test
    fun `the current chapter is reported and follows a seek`() = runBlocking {
        val bookId = book()
        load(bookId, positionMs = 10_000)
        eventually { assertEquals("Chapter 1", chapters.lastOrNull()?.title) }

        player.seekTo(1, CHAPTER_MS + 10_000)
        eventually { assertEquals("Chapter 4", chapters.lastOrNull()?.title) }

        // Back: to the start of the chapter, which lies in the second file.
        assertTrue(listener.skipChapter(forward = false))
        idle()
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(CHAPTER_MS, player.currentPosition)
        // Within the first seconds of a chapter, back goes to the previous one, across the file boundary.
        assertTrue(listener.skipChapter(forward = false))
        idle()
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(0L, player.currentPosition)
        assertTrue(listener.skipChapter(forward = false))
        idle()
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(CHAPTER_MS, player.currentPosition)
    }

    @Test
    fun `mark as played stays even when the player pauses afterwards`() = runBlocking {
        val bookId = book()
        load(bookId, positionMs = 60_000)
        player.play()
        idle()

        assertTrue(listener.markFinished(bookId))
        player.pause()

        eventually { assertTrue(runBlocking { progress(bookId) }?.finished == true) }
        quiet()
        assertEquals(2 * FILE_MS, progress(bookId)!!.positionMs)
        assertTrue(progress(bookId)!!.finished)
    }

    @Test
    fun `an episode that plays to its end moves on`() = runBlocking {
        val episodeId = book("Episode", kind = BookKind.EPISODE, files = 1)
        load(episodeId, positionMs = FILE_MS - 5_000)
        player.play()
        idle()

        player.end()

        eventually { assertEquals(listOf(episodeId), endedEpisodes) }
        eventually { assertTrue(runBlocking { progress(episodeId) }?.finished == true) }
    }

    @Test
    fun `the book plays at its own speed, music at normal speed`() = runBlocking {
        val bookId = book()
        effects.setSpeed(bookId, 1.5f)
        load(bookId)
        eventually { assertEquals(1.5f, player.playbackParameters.speed) }

        player.load(listOf(MediaItem.Builder().setMediaId(MediaIds.song(7)).build()), 0, 0)
        eventually { assertEquals(1f, player.playbackParameters.speed) }
        assertNull(chapters.last())
    }

    @Test
    fun `a file that is no longer available is skipped`() = runBlocking {
        val bookId = book(files = 3)
        load(bookId, positionMs = 0, unavailable = setOf(0, 1))

        eventually { assertEquals(2, player.currentMediaItemIndex) }
        assertEquals(0L, player.currentPosition)
    }

    /** Holds a queue and a position the test sets; plays nothing by itself. */
    private class BookPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var items: List<MediaItem> = emptyList()
        private var index = 0
        private var positionMs = 0L
        private var playWhenReady = false
        private var state = Player.STATE_IDLE
        private var speed = 1f

        fun load(items: List<MediaItem>, index: Int, positionMs: Long) {
            this.items = items
            this.index = index
            this.positionMs = positionMs
            state = Player.STATE_READY
            invalidateState()
            shadowOf(Looper.getMainLooper()).idle()
        }

        /** Playback went on to [positionMs] in the current file. */
        fun moveTo(positionMs: Long) {
            this.positionMs = positionMs
            invalidateState()
            shadowOf(Looper.getMainLooper()).idle()
        }

        /** Played to the end of the last file. */
        fun end() {
            positionMs = FILE_MS
            state = Player.STATE_ENDED
            invalidateState()
            shadowOf(Looper.getMainLooper()).idle()
        }

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(items.map { MediaItemData.Builder(it.mediaId).setMediaItem(it).setDurationUs(FILE_MS * 1_000).build() })
            .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else index)
            .setContentPositionMs(positionMs)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else state)
            .setPlaybackParameters(PlaybackParameters(speed))
            .build()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            this.playWhenReady = playWhenReady
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            if (mediaItemIndex != C.INDEX_UNSET) index = mediaItemIndex
            this.positionMs = positionMs.coerceAtLeast(0)
            if (state == Player.STATE_ENDED) state = Player.STATE_READY
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
            speed = playbackParameters.speed
            return Futures.immediateVoidFuture()
        }
    }

    private companion object {
        const val FILE_MS = 600_000L
        const val CHAPTER_MS = 300_000L
    }
}
