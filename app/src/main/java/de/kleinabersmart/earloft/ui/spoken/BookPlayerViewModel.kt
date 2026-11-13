package de.kleinabersmart.earloft.ui.spoken

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenEffectsRepository
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
) : ViewModel() {

    val state: StateFlow<PlayerState> = playback.state

    /** Sprünge der Position, damit die Anzeige auch pausiert nachzieht. */
    val positionJumps: Flow<Unit> = playback.positionJumps

    private val bookId = state.map { it.spoken?.bookId }.distinctUntilChanged()

    val book: StateFlow<BookWithProgress?> = bookId
        .flatMapLatest { id -> if (id == null) flowOf(null) else books.book(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Podcast-Folgen haben nur den einen Regler über die ganze Länge. */
    val isEpisode: StateFlow<Boolean> = book
        .map { it?.book?.kind == BookKind.EPISODE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Zeitachse des laufenden Buchs; `null`, bis Dateien und Kapitel geladen sind. */
    val timeline: StateFlow<BookTimeline?> = bookId
        .mapLatest { id -> id?.let { books.timeline(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Tempo und Effekte des laufenden Buchs bzw. der Folge mit ihren Ebenen (eigene, Abo, Bereich). */
    val effectLayers: StateFlow<EffectLayers?> = bookId
        .flatMapLatest { id -> if (id == null) flowOf(null) else effects.layers(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Grundfarbe des Covers für das Farbschema; `null`, solange sie fehlt. */
    val seedColor: StateFlow<Int?> = bookId
        .mapLatest { id -> id?.let { bookCovers.seedColor(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sleepTimerRemainingMs: StateFlow<Long?> = playback.sleepTimerRemainingMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Der Sleep-Timer läuft bis zum Ende des Kapitels bzw. der Folge. */
    val sleepTimerToEnd: StateFlow<Boolean> = playback.sleepTimerToEnd
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Länge des ganzen Buchs. Eine Folge, deren Feed keine Dauer nannte, hat sie erst, wenn der Player sie kennt; bis
     * dahin gilt dessen Angabe.
     */
    fun durationMs(): Long = timeline.value?.durationMs?.takeIf { it > 0 } ?: state.value.durationMs

    /** Aktuelle Position im ganzen Buch. */
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

    /** Der letzte Kapitelsprung: von [fromMs] nach [toMs] (Buchposition), zur Zeit [atMs] (`elapsedRealtime`). */
    private class ChapterJump(val fromMs: Long, val toMs: Long, val atMs: Long)

    private var lastJump: ChapterJump? = null

    private fun jumpToChapter(startMs: Long) {
        lastJump = ChapterJump(fromMs = bookPositionMs(), toMs = startMs, atMs = clock())
        seekToBook(startMs)
    }

    /**
     * Position für die Kapitelknöpfe. Nach einem Sprung meldet der Player eine Weile noch die alte Stelle (beim Casten
     * mehrere Sekunden, bis der Empfänger nachgeladen hat); ein zweiter Tipp ginge sonst wieder vom alten Kapitel aus
     * („Kapitel zurück“ bliebe am Kapitelanfang hängen). Liegt die gemeldete Position näher an der alten als am Ziel,
     * gilt deshalb das Ziel – längstens [JUMP_HOLD_MS].
     */
    private fun chapterPositionMs(): Long {
        val reported = bookPositionMs()
        val jump = lastJump ?: return reported
        if (clock() - jump.atMs > JUMP_HOLD_MS) return reported
        val stale = abs(reported - jump.fromMs) < abs(reported - jump.toMs)
        return if (stale) jump.toMs else reported
    }

    /** Uhr für [lastJump]; im Test ersetzbar. */
    internal var clock: () -> Long = SystemClock::elapsedRealtime

    /** Es gibt eine weitere Folge in der Warteschlange der Podcasts. */
    val hasNextEpisode: StateFlow<Boolean> = combine(continueListening.episodes, bookId) { queue, current ->
        queue.any { it.book.id != current }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Die Folge von vorn; steht sie, geht es auch gleich los. */
    fun restartEpisode() {
        seekToBook(0)
        if (!state.value.isPlaying) playback.togglePlayPause()
    }

    /** Springt zur nächsten Folge der Warteschlange; sie rückt dabei an deren Anfang (`BookLauncher` → `started`). */
    fun skipToNextEpisode() {
        val current = state.value.spoken?.bookId ?: return
        viewModelScope.launch { continueListening.next(current)?.let(launcher::play) }
    }

    fun togglePlayPause() = playback.togglePlayPause()
    fun skipBack() = playback.skipBack()
    fun skipForward() = playback.skipForward()
    /** Tempo und Effekte gelten für das Buch bzw. die Folge; der Player folgt ihnen über die Datenbank. */
    fun setSpeed(speed: Float) = withBook { effects.setSpeed(it, speed) }
    fun setSkipSilence(enabled: Boolean) = withBook { effects.setSkipSilence(it, enabled) }
    fun setVolumeBoost(db: Int) = withBook { effects.setVolumeBoost(it, db) }

    /** „Als Standard“ bzw. „Für diesen Podcast“. */
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
