package de.kleinabersmart.earloft.ui.spoken

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenEffectsRepository
import de.kleinabersmart.earloft.core.data.spoken.nextInSeries
import de.kleinabersmart.earloft.core.model.EffectLayers
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookTimeline
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.core.playback.PlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.MutableStateFlow
import de.kleinabersmart.earloft.core.data.podcast.PodcastTranscripts
import de.kleinabersmart.earloft.core.data.podcast.TranscriptResult
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookPlayerViewModel @javax.inject.Inject constructor(
    private val playback: PlaybackConnection,
    books: BookRepository,
    bookCovers: BookCovers,
    private val continueListening: ContinueListening,
    private val launcher: BookLauncher,
    private val effects: SpokenEffectsRepository,
    private val transcripts: PodcastTranscripts,
) : ViewModel() {

    val state: StateFlow<PlayerState> = playback.state

    /** Position jumps, so the display catches up even when paused. */
    val positionJumps: Flow<Unit> = playback.positionJumps

    private val bookId = state.map { it.spoken?.bookId }.distinctUntilChanged()

    val book: StateFlow<BookWithProgress?> = bookId
        .flatMapLatest { id -> if (id == null) flowOf(null) else books.book(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * If the current book has been finished, the next numbered volume of its series (card in the player, books_improvements M2);
     * it never starts by itself.
     */
    val nextInSeries: StateFlow<Pair<BookWithProgress, String?>?> = book
        .map { current -> current?.takeIf { it.book.kind == BookKind.BOOK && it.progress?.finished == true }?.book?.id }
        .distinctUntilChanged()
        .flatMapLatest { finishedId ->
            if (finishedId == null) {
                flowOf(null)
            } else {
                books.books().map { all -> all.firstOrNull { it.book.id == finishedId }?.let { nextInSeries(it, all) } }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun playBook(bookId: Long) = launcher.play(bookId)

    /** Podcast episodes only have the one slider over the whole length. */
    val isEpisode: StateFlow<Boolean> = book
        .map { it?.book?.kind == BookKind.EPISODE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Timeline of the current book; `null` until files and chapters are loaded. */
    val timeline: StateFlow<BookTimeline?> = bookId
        .mapLatest { id -> id?.let { books.timeline(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Speed and effects of the current book or episode with their levels (own, subscription, area). */
    val effectLayers: StateFlow<EffectLayers?> = bookId
        .flatMapLatest { id -> if (id == null) flowOf(null) else effects.layers(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The current episode has a transcript (`podcast:transcript`, concept 12.5 "Transcripts"). */
    val hasTranscript: StateFlow<Boolean> = book
        .map { it?.book?.takeIf { book -> book.kind == BookKind.EPISODE }?.transcriptUrl != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val transcriptWanted = MutableStateFlow(false)
    private val transcriptAttempt = MutableStateFlow(0)

    /**
     * Transcript of the current episode; `null` while it is loading or hasn't been shown yet. It is loaded only once it is
     * displayed ([loadTranscript]), afterwards right away for every new episode.
     */
    val transcript: StateFlow<TranscriptResult?> = combine(
        book.map { it?.book?.takeIf { book -> book.kind == BookKind.EPISODE }?.let { book -> book.id to book.transcriptUrl } }.distinctUntilChanged(),
        transcriptWanted,
        transcriptAttempt,
    ) { key, wanted, _ -> key to wanted }
        .transformLatest { (key, wanted) ->
            when {
                key?.second == null -> emit(TranscriptResult.None)
                !wanted -> emit(null)
                else -> {
                    emit(null)
                    emit(transcripts.load(key.first))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun loadTranscript() {
        transcriptWanted.value = true
    }

    fun retryTranscript() {
        transcriptAttempt.value++
    }

    /** Base color of the cover for the color scheme; `null` as long as it is missing. */
    val seedColor: StateFlow<Int?> = bookId
        .mapLatest { id -> id?.let { bookCovers.seedColor(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sleepTimerRemainingMs: StateFlow<Long?> = playback.sleepTimerRemainingMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The sleep timer runs until the end of the chapter or episode. */
    val sleepTimerToEnd: StateFlow<Boolean> = playback.sleepTimerToEnd
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Length of the whole book. An episode whose feed gave no duration only has one once the player knows it; until
     * then the player's value applies.
     */
    fun durationMs(): Long = timeline.value?.durationMs?.takeIf { it > 0 } ?: state.value.durationMs

    /** Current position in the whole book. */
    fun bookPositionMs(): Long {
        val timeline = timeline.value ?: return 0
        val entry = state.value.spoken ?: return 0
        return timeline.toBookPosition(entry.trackIndex, playback.currentPositionMs)
    }

    fun seekToBook(positionMs: Long) {
        val target = timeline.value?.locate(positionMs) ?: return
        playback.seekToTrack(target.trackIndex, target.offsetMs)
    }

    fun previousChapter() {
        val timeline = timeline.value ?: return
        timeline.previousChapterStart(chapterPositionMs())?.let(::jumpToChapter)
    }

    fun nextChapter() {
        val timeline = timeline.value ?: return
        timeline.nextChapter(chapterPositionMs())?.let { jumpToChapter(it.startMs) }
    }

    /** The last chapter jump: from [fromMs] to [toMs] (book position), at time [atMs] (`elapsedRealtime`). */
    private class ChapterJump(val fromMs: Long, val toMs: Long, val atMs: Long)

    private var lastJump: ChapterJump? = null

    private fun jumpToChapter(startMs: Long) {
        lastJump = ChapterJump(fromMs = bookPositionMs(), toMs = startMs, atMs = clock())
        seekToBook(startMs)
    }

    /**
     * Position for the chapter buttons. After a jump the player keeps reporting the old position for a while (when casting
     * several seconds, until the receiver has reloaded); a second tap would otherwise start from the old chapter again
     * ("Previous chapter" would get stuck at the chapter start). If the reported position is closer to the old one than to the target,
     * the target therefore applies – for at most [JUMP_HOLD_MS].
     */
    private fun chapterPositionMs(): Long {
        val reported = bookPositionMs()
        val jump = lastJump ?: return reported
        if (clock() - jump.atMs > JUMP_HOLD_MS) return reported
        val stale = abs(reported - jump.fromMs) < abs(reported - jump.toMs)
        return if (stale) jump.toMs else reported
    }

    /** Clock for [lastJump]; replaceable in tests. */
    internal var clock: () -> Long = SystemClock::elapsedRealtime

    /** There is another episode in the podcast queue. */
    val hasNextEpisode: StateFlow<Boolean> = combine(continueListening.episodes, bookId) { queue, current ->
        queue.any { it.book.id != current }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The episode from the start; if it is stopped, it starts right away too. */
    fun restartEpisode() {
        seekToBook(0)
        if (!state.value.isPlaying) playback.togglePlayPause()
    }

    /** Jumps to the next episode of the queue; it moves to the front of it (`BookLauncher` → `started`). */
    fun skipToNextEpisode() {
        val current = state.value.spoken?.bookId ?: return
        viewModelScope.launch { continueListening.next(current)?.let(launcher::play) }
    }

    fun togglePlayPause() = playback.togglePlayPause()
    fun skipBack() = playback.skipBack()
    fun skipForward() = playback.skipForward()
    /** Speed and effects apply to the book or episode; the player follows them via the database. */
    fun setSpeed(speed: Float) = withBook { effects.setSpeed(it, speed) }
    fun setSkipSilence(enabled: Boolean) = withBook { effects.setSkipSilence(it, enabled) }
    fun setVolumeBoost(db: Int) = withBook { effects.setVolumeBoost(it, db) }

    /** "As default" or "For this podcast". */
    fun rememberEffectsAsDefault() = withBook { effects.rememberAsDefault(it) }

    private fun withBook(action: suspend (Long) -> Unit) {
        val id = state.value.spoken?.bookId ?: return
        viewModelScope.launch { action(id) }
    }
    fun startSleepTimer(minutes: Int) = playback.startSleepTimer(minutes)
    fun startSleepTimerToEnd() = playback.startSleepTimerToEnd()
    fun cancelSleepTimer() = playback.cancelSleepTimer()

    private companion object {
        const val JUMP_HOLD_MS = 15_000L
    }
}
