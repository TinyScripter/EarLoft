package de.kleinabersmart.earloft.wear.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps
import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.QueueEntry
import de.kleinabersmart.earloft.core.playback.SpokenEntry
import de.kleinabersmart.earloft.core.playback.nextRepeatMode
import de.kleinabersmart.earloft.core.wearsync.WearRemoteAction
import de.kleinabersmart.earloft.core.wearsync.WearRemoteChaptersDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteMediaKind
import de.kleinabersmart.earloft.core.wearsync.WearRemoteStateDto
import de.kleinabersmart.earloft.wear.R
import de.kleinabersmart.earloft.wear.sync.PhoneConnection
import de.kleinabersmart.earloft.wear.sync.PhoneRemote
import de.kleinabersmart.earloft.wear.sync.PhoneStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * The phone's now playing view (Wear concept 15): shows what the phone reports and sends the controls there.
 * Play/pause, shuffle and repeat toggle immediately and apply until the phone reports its state.
 */
@HiltViewModel
class RemotePlayerViewModel @Inject constructor(
    private val remote: PhoneRemote,
    phoneConnection: PhoneConnection,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel(), NowPlayingModel, ChaptersModel, SleepTimerModel {

    /**
     * Without the phone the last report stays: it would go on "playing" and counting, and controls would only seem to work
     * (review4 T59). While the state is still being checked, the phone counts as there, so the view doesn't flicker.
     */
    private val phoneReachable: StateFlow<Boolean> = phoneConnection.status
        .map { it is PhoneStatus.Connected || it is PhoneStatus.Checking }
        .onStart { emit(true) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** A command just didn't reach the phone. */
    private val sendFailed = MutableStateFlow(false)
    private var sendFailedJob: Job? = null

    override val notice: StateFlow<Int?> = combine(phoneReachable, sendFailed) { reachable, failed ->
        if (!reachable || failed) R.string.remote_unreachable else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val remoteState: StateFlow<WearRemoteStateDto?> =
        // The side effects hang on the flow, not on a separate collector: a permanent collector would keep the subscription
        // at the phone open (and with it its writing) even when the view is not visible.
        remote.state
            .onEach { dto ->
                // The phone has reported: the provisional values no longer apply.
                pendingPlaying.value = null
                pendingShuffle.value = null
                pendingRepeat.value = null
                pendingSleepCancelled.value = false
                if (dto != null && !_volumeVisible.value) _volume.value = dto.volume
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val pendingPlaying = MutableStateFlow<Boolean?>(null)
    private val pendingShuffle = MutableStateFlow<Boolean?>(null)
    private val pendingRepeat = MutableStateFlow<Int?>(null)

    /** The timer was ended here; until the phone reports, it is already shown as off. */
    private val pendingSleepCancelled = MutableStateFlow(false)
    private var pendingJob: Job? = null

    override val state: StateFlow<PlayerState> = combine(
        remoteState,
        pendingPlaying,
        pendingShuffle,
        pendingRepeat,
        phoneReachable,
    ) { remote, playing, shuffle, repeat, reachable ->
        val dto = remote ?: return@combine PlayerState()
        val spoken = dto.hasMedia && dto.isSpoken
        PlayerState(
            current = if (dto.hasMedia && !spoken) QueueEntry(0, dto.title, dto.artist, dto.albumId, dto.albumTitle) else null,
            // Position and length are already book times; there is no file (trackIndex) for the watch.
            spoken = if (spoken) {
                SpokenEntry(
                    dto.bookId ?: 0,
                    0,
                    dto.title,
                    dto.artist,
                    SeekSteps(dto.seekBackSeconds, dto.seekForwardSeconds),
                    isEpisode = dto.kind == WearRemoteMediaKind.EPISODE,
                )
            } else {
                null
            },
            speed = dto.speed,
            isPlaying = reachable && (playing ?: dto.isPlaying),
            durationMs = dto.durationMs,
            shuffle = shuffle ?: dto.shuffle,
            repeatMode = repeat ?: dto.repeatMode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerState())

    /**
     * Extrapolated from the reported state; regularly only while the phone is playing, otherwise on every new report. Only the
     * start value compares phone and watch clock time (the report may be older; Wear OS sets the watch clock from the phone); after that
     * the monotonic clock counts, so that a time sync along the way does not make the display jump.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val positionMs: StateFlow<Long> = combine(remoteState, phoneReachable, ::Pair)
        .flatMapLatest { (dto, reachable) ->
            flow {
                val start = startPosition(dto, reachable, System.currentTimeMillis(), shownPosition)
                val startedAt = SystemClock.elapsedRealtime()
                shownPosition = ShownPosition(dto, start)
                emit(start)
                while (reachable && dto?.isPlaying == true) {
                    delay(POSITION_INTERVAL_MS)
                    val position = start + ((SystemClock.elapsedRealtime() - startedAt) * dto.speed.toDouble()).toLong()
                    val shown = if (dto.durationMs > 0) position.coerceAtMost(dto.durationMs) else position
                    shownPosition = ShownPosition(dto, shown)
                    emit(shown)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** The position last shown and the report it was counted from; see [startPosition]. */
    @Volatile private var shownPosition: ShownPosition? = null

    override val cover: StateFlow<Bitmap?> = combine(remoteState, remote.cover) { dto, cover ->
        cover?.takeIf { it.first == dto?.coverKey }?.second
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override val seedColor: StateFlow<Int?> = remoteState.map { it?.seedColor }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override val chapters: StateFlow<List<BookChapter>> = combine(remoteState, remote.chapters, ::chaptersOf)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * From the extrapolated position: the name changes in the middle of the file without the phone reporting anything. It only
     * recomposes on a change, not in the 500 ms tick of the position.
     */
    override val currentChapter: StateFlow<BookChapter?> = combine(chapters, positionMs, ::chapterAt)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The phone chooses file and offset via its timeline ([WearRemoteAction.SEEK] in book time). */
    override fun seekToBook(bookPositionMs: Long) = seekTo(bookPositionMs)

    /**
     * Remaining time of the phone's sleep timer: extrapolated from the report like the position (the phone only reports start, end and
     * changes), for "until the end" only during playback.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val sleepTimerRemainingMs: StateFlow<Long?> = combine(remoteState, pendingSleepCancelled, ::Pair)
        .flatMapLatest { (dto, cancelled) ->
            flow {
                val start = if (cancelled) null else dto?.sleepTimerRemainingAt(System.currentTimeMillis())
                emit(start)
                if (start == null || dto?.sleepTimerCounting != true) return@flow
                val startedAt = SystemClock.elapsedRealtime()
                while (true) {
                    delay(SLEEP_TICK_MS)
                    val remaining = (start - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0)
                    emit(remaining)
                    if (remaining == 0L) break
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The phone accepts sleep timer commands (older ones do not: then no button). */
    val sleepTimerSupported: StateFlow<Boolean> = remoteState.map { it?.sleepTimerSupported == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    override val sleepToEndLabel: StateFlow<Int?> = combine(state.map { it.spoken }.distinctUntilChanged(), chapters, ::sleepToEndLabelFor)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override fun startSleepTimer(minutes: Int) = send(WearRemoteAction.SLEEP_TIMER_START, minutes.toLong())

    override fun startSleepTimerToEnd() = send(WearRemoteAction.SLEEP_TIMER_TO_END)

    private val _volume = MutableStateFlow(0f)
    override val volume: StateFlow<Float> = _volume

    private val _volumeVisible = MutableStateFlow(false)
    override val volumeVisible: StateFlow<Boolean> = _volumeVisible
    private var hideVolumeJob: Job? = null
    private val crown = CrownStepper(CROWN_PX_PER_STEP)

    override fun togglePlayPause() {
        pending { pendingPlaying.value = !state.value.isPlaying }
        send(WearRemoteAction.PLAY_PAUSE)
    }

    override fun next() = send(WearRemoteAction.NEXT)

    override fun previous() = send(WearRemoteAction.PREVIOUS)

    override fun toggleShuffle() {
        pending { pendingShuffle.value = !state.value.shuffle }
        send(WearRemoteAction.TOGGLE_SHUFFLE)
    }

    override fun cycleRepeat() {
        pending { pendingRepeat.value = nextRepeatMode(state.value.repeatMode) }
        send(WearRemoteAction.CYCLE_REPEAT)
    }

    override fun skipBack() = send(WearRemoteAction.SKIP_BACK)

    override fun skipForward() = send(WearRemoteAction.SKIP_FORWARD)

    override fun seekTo(positionMs: Long) = send(WearRemoteAction.SEEK, positionMs)

    override fun cancelSleepTimer() {
        pending { pendingSleepCancelled.value = true }
        send(WearRemoteAction.SLEEP_TIMER_CANCEL)
    }

    override fun onCrown(pixels: Float) {
        val steps = crown.add(pixels)
        val dto = remoteState.value
        if (steps != 0 && dto != null && dto.volumeMax > 0) {
            val current = (_volume.value * dto.volumeMax).roundToInt()
            val target = (current + steps).coerceIn(0, dto.volumeMax)
            if (target != current) {
                _volume.value = target / dto.volumeMax.toFloat()
                send(WearRemoteAction.VOLUME_STEPS, (target - current).toLong())
            }
        }
        _volumeVisible.value = true
        hideVolumeJob?.cancel()
        hideVolumeJob = viewModelScope.launch {
            delay(VOLUME_VISIBLE_MS)
            _volumeVisible.value = false
        }
    }

    /**
     * In the app scope: the chapter list closes right after the tap, its ViewModel would already be gone before sending
     * (Review V3).
     */
    private fun send(action: WearRemoteAction, value: Long = 0) {
        applicationScope.launch { if (!remote.send(action, value)) onSendFailed() }
    }

    /** The command didn't go out: the provisional values are dropped at once, and the view says why (review4 T59). */
    private fun onSendFailed() {
        pendingJob?.cancel()
        pendingPlaying.value = null
        pendingShuffle.value = null
        pendingRepeat.value = null
        pendingSleepCancelled.value = false
        sendFailed.value = true
        sendFailedJob?.cancel()
        sendFailedJob = applicationScope.launch {
            delay(PENDING_MS)
            sendFailed.value = false
        }
    }

    /** Sets provisional values; if the phone does not report back (unreachable), they expire after a short time. */
    private fun pending(set: () -> Unit) {
        set()
        pendingJob?.cancel()
        pendingJob = viewModelScope.launch {
            delay(PENDING_MS)
            pendingPlaying.value = null
            pendingShuffle.value = null
            pendingRepeat.value = null
            pendingSleepCancelled.value = false
        }
    }

    private companion object {
        const val POSITION_INTERVAL_MS = 500L
        const val CROWN_PX_PER_STEP = 30f
        const val VOLUME_VISIBLE_MS = 1_500L
        const val PENDING_MS = 3_000L
        const val SLEEP_TICK_MS = 1_000L
    }
}

/**
 * The chapters from [chapters] if they belong to the book or episode the phone is playing according to [state]; none for music, without
 * a report or with the chapters of a previous book.
 */
internal fun chaptersOf(state: WearRemoteStateDto?, chapters: WearRemoteChaptersDto?): List<BookChapter> {
    if (state == null || !state.hasMedia || !state.isSpoken || chapters == null || chapters.bookId != state.bookId) return emptyList()
    return chapters.chapters.mapIndexed { index, chapter ->
        BookChapter(index, chapter.title, chapter.startMs, chapter.endMs, unavailable = chapter.unavailable)
    }
}

/** A position shown on the watch, counted from the phone's report [from]. */
internal data class ShownPosition(val from: WearRemoteStateDto?, val positionMs: Long)

/**
 * Where the shown position starts for the report [dto]: with the phone counted on from the report. Without it, it stops where it
 * was last shown for the same report, since nobody knows what the phone did since; going back to the report itself, which can be
 * minutes old, made it jump back and then forward again once the phone was there (review4 8.6 Y30).
 */
internal fun startPosition(dto: WearRemoteStateDto?, reachable: Boolean, now: Long, shown: ShownPosition?): Long = when {
    dto == null -> 0L
    reachable -> dto.positionAt(now)
    shown != null && shown.from == dto -> shown.positionMs
    else -> dto.positionMs
}

/** The chapter at [positionMs] (book time), like [de.kleinabersmart.earloft.core.model.BookTimeline.chapterAt]. */
internal fun chapterAt(chapters: List<BookChapter>, positionMs: Long): BookChapter? =
    chapters.lastOrNull { it.startMs <= positionMs } ?: chapters.firstOrNull()
