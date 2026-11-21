package de.kleinabersmart.earloft.core.playback

import androidx.media3.common.C
import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a book or episode currently is (book positions): [ends] are the positions where the timer "until the end"
 * can stop, ascending – for books with chapters every chapter end, otherwise only the end of the whole. For [music] the
 * current song, whose only end is its length ("until the end of the song"); [bookId] is then a key of the song in the queue.
 */
internal class SpokenSpot(val bookId: Long, val positionMs: Long, val speed: Float, val ends: List<Long>, val music: Boolean = false) {
    /** The end of the section in which [positionMs] lies. */
    fun endAfter(positionMs: Long): Long = ends.firstOrNull { it > positionMs } ?: ends.last()

    /** Start of the section that ends at [endMs]. */
    fun startOf(endMs: Long): Long = ends.lastOrNull { it < endMs } ?: 0L
}

/** What the [SleepTimer] needs from the player; this way it can be tested without a real player. */
internal interface SleepTimerTarget {
    var volume: Float
    val isPlaying: Boolean

    /** A book or episode is loaded (even if [spokenSpot] is still missing because the timeline is loading). */
    val isSpoken: Boolean

    /** A song is loaded (even if its length isn't known yet); "until the end" then means the end of the song. */
    val hasMusic: Boolean get() = false
    fun pause()
    fun play()

    /** State of the current book, episode or song; `null` while the timeline or the song's length is still loading. */
    fun spokenSpot(): SpokenSpot?

    /** For books and episodes rewind [heardMs] of listened time (converted with speed); nothing for music. */
    fun rewind(heardMs: Long)
}

internal fun Player.asSleepTimerTarget(spot: () -> SpokenSpot?, rewind: (Long) -> Unit) = object : SleepTimerTarget {
    override var volume: Float
        get() = this@asSleepTimerTarget.volume
        set(value) {
            this@asSleepTimerTarget.volume = value
        }
    override val isPlaying: Boolean get() = this@asSleepTimerTarget.isPlaying
    // During the handover to the cast device the queue is empty for a few seconds: not an end, "until the end" waits (review D9).
    override val isSpoken: Boolean get() = currentMediaItem?.isSpoken == true || awaitsRemoteQueue
    override val hasMusic: Boolean get() = currentMediaItem?.let { !it.isSpoken } == true
    override fun pause() = this@asSleepTimerTarget.pause()
    override fun play() = this@asSleepTimerTarget.play()
    override fun spokenSpot(): SpokenSpot? = spot() ?: musicSpot()

    private fun musicSpot(): SpokenSpot? {
        if (!hasMusic) return null
        val item = currentMediaItem ?: return null
        return musicSpot(
            index = currentMediaItemIndex,
            mediaId = item.mediaId,
            positionMs = currentPosition,
            speed = playbackParameters.speed,
            playerDurationMs = duration,
            metadataDurationMs = item.mediaMetadata.durationMs,
        )
    }
    override fun rewind(heardMs: Long) = rewind.invoke(heardMs)
}

/**
 * The current song as one section. Its key (negative, never a book) is made of the position in the queue and the song: the same
 * song twice in a row is told apart, and so is another album started at the same position (review H8). The length comes from the
 * player, or from the server's metadata while the player doesn't know it (transcoded streams without a length, review H18).
 */
internal fun musicSpot(
    index: Int,
    mediaId: String,
    positionMs: Long,
    speed: Float,
    playerDurationMs: Long,
    metadataDurationMs: Long?,
): SpokenSpot? {
    val length = playerDurationMs.takeIf { it != C.TIME_UNSET && it > 0 }
        ?: metadataDurationMs?.takeIf { it > 0 }
        ?: return null
    val key = (index.toLong() shl 31) or (mediaId.hashCode().toLong() and 0x7FFF_FFFFL)
    return SpokenSpot(-(key + 1), positionMs, speed, listOf(length), music = true)
}

/** Volume [remainingMs] before expiry: linear from 1 (start of fade-out) to 0. */
internal fun fadeVolume(remainingMs: Long): Float = (remainingMs.toFloat() / SleepTimer.FADE_MS).coerceIn(0f, 1f)

/** A running sleep timer: remaining time (real time) and whether it runs until the end of the chapter or episode. */
data class SleepTimerState(val remainingMs: Long, val toEnd: Boolean)

/**
 * Sleep timer: after the set duration or at the end of the chapter or episode it fades out and pauses. The
 * time runs in the process of the [PlaybackService], not in the UI, and therefore survives closing the app. Phone and watch
 * each have their own timer because the watch plays independently.
 *
 * "Until the end" recomputes the remaining time from the position on every step: speed changes count immediately, and whoever
 * seeks into another chapter falls asleep at its end. While paused, nothing fades out.
 *
 * Shaking ([extend]) extends during the last [SHAKE_WINDOW_MS] while playing, and up to [SHAKE_AFTER_MS]
 * after stopping ([shakeWindow]): by the last chosen duration or until the end of the next chapter.
 *
 * All methods belong on the main thread.
 */
@Singleton
class SleepTimer internal constructor(
    private val now: () -> Long,
    private val scope: CoroutineScope,
) {
    @Inject constructor() : this(SystemClock::elapsedRealtime, MainScope())

    private val _state = MutableStateFlow<SleepTimerState?>(null)

    /** `null` while no timer is running; otherwise updated about every second. */
    val state: StateFlow<SleepTimerState?> = _state.asStateFlow()

    /** Remaining time, updated about every second; `null` while no timer is running. */
    val remainingMs: Flow<Long?> = _state.map { it?.remainingMs }.distinctUntilChanged()

    private val _shakeWindow = MutableStateFlow(false)

    /** Shaking now extends the timer; only then is the sensor needed. */
    val shakeWindow: StateFlow<Boolean> = _shakeWindow.asStateFlow()

    private var target: SleepTimerTarget? = null
    private var job: Job? = null
    private var afterJob: Job? = null

    /** What is currently running or last expired (for shaking afterwards). */
    private var plan: Plan? = null
    private var firedAt: Long? = null

    /** Last chosen duration; shaking extends by it. */
    private var lastDurationMs = PRESET_MINUTES.first() * MS_PER_MINUTE

    internal fun attach(target: SleepTimerTarget) {
        this.target = target
    }

    /** The service is ending: a timer without a player would have nothing left to do. */
    internal fun detach() {
        cancel()
        target = null
    }

    /** Restarts the timer with a fixed duration; a running one is replaced. */
    fun start(durationMs: Long) {
        if (durationMs <= 0) return
        lastDurationMs = durationMs
        launch(Plan.Duration(now() + durationMs))
    }

    /** Until the end of the chapter (books with chapters), the book, the episode or the song; nothing without anything loaded. */
    fun startToEnd() {
        val target = target ?: return
        if (!target.isSpoken && !target.hasMusic) return
        launch(Plan.ToEnd())
    }

    fun cancel() {
        job?.cancel()
        job = null
        afterJob?.cancel()
        afterJob = null
        plan = null
        firedAt = null
        target?.volume = 1f
        _state.value = null
        _shakeWindow.value = false
    }

    /**
     * Shaking: shortly before expiry it starts over (fixed duration) or runs until the end of the next chapter; whatever was already
     * faded out is rewound for books and episodes. Shortly after expiry it also resumes playback.
     * `true` if the timer was extended.
     */
    fun extend(): Boolean {
        val target = target ?: return false
        if (!_shakeWindow.value) return false
        val plan = plan ?: return false
        val firedAt = firedAt
        // The window after stopping is closed by a `delay`, which counts uptime: in deep sleep it stayed open for hours, and
        // movement in the pocket in the morning would have started playback. Real time is what counts (review D11).
        if (firedAt != null && now() - firedAt > SHAKE_AFTER_MS) {
            closeAfterWindow()
            return false
        }
        val fired = firedAt != null
        // Only what actually played quieter is rewound (while paused "until the end" doesn't fade out).
        val faded = target.volume < 1f
        val heard = when {
            fired -> FADE_MS
            faded -> (FADE_MS - (_state.value?.remainingMs ?: FADE_MS)).coerceAtLeast(0)
            else -> 0L
        }
        if (heard > 0) target.rewind(heard)
        val next = when (plan) {
            is Plan.Duration -> null
            // Without a further chapter (episode, last chapter) by the last chosen duration.
            is Plan.ToEnd -> plan.endMs?.let { end ->
                target.spokenSpot()?.takeIf { it.bookId == plan.bookId }?.endAfter(end)?.takeIf { it > end }
            }?.let { Plan.ToEnd(bookId = plan.bookId, endMs = it, fromMs = plan.fromMs, music = plan.music) }
        }
        launch(next ?: Plan.Duration(now() + lastDurationMs))
        if (fired) target.play()
        return true
    }

    private fun launch(plan: Plan) {
        val target = target ?: return
        job?.cancel()
        afterJob?.cancel()
        afterJob = null
        firedAt = null
        this.plan = plan
        target.volume = 1f
        // Until the first step the remaining time of the replaced timer would stay on display, for good if "until the end" can't
        // find an end (review H18).
        _state.value = (plan as? Plan.Duration)?.let { SleepTimerState(it.endsAt - now(), toEnd = false) }
        _shakeWindow.value = false
        job = scope.launch { run(target, plan) }
    }

    private suspend fun run(target: SleepTimerTarget, plan: Plan) {
        while (true) {
            val remaining = when (plan) {
                is Plan.Duration -> plan.endsAt - now()
                is Plan.ToEnd -> when (val step = plan.remaining(target)) {
                    Step.Stop -> {
                        cancel()
                        return
                    }
                    Step.Wait -> {
                        delay(TICK_MS)
                        continue
                    }
                    is Step.Remaining -> step.ms
                }
            }
            if (remaining <= plan.slackMs) break
            _state.value = SleepTimerState(remaining, toEnd = plan is Plan.ToEnd)
            // Only during playback: when paused the sensor would only drain battery, and every movement would rewind.
            _shakeWindow.value = remaining <= SHAKE_WINDOW_MS && target.isPlaying
            // When paused "until the end" doesn't continue; fading out only happens once it plays again.
            val fading = remaining <= FADE_MS && (plan is Plan.Duration || target.isPlaying)
            if (fading) {
                target.volume = fadeVolume(remaining)
                delay(minOf(remaining - plan.slackMs, FADE_STEP_MS).coerceAtLeast(1))
            } else {
                target.volume = 1f
                val untilFade = (remaining - FADE_MS).takeIf { it > 0 } ?: TICK_MS
                delay(minOf(untilFade, TICK_MS))
            }
        }
        target.pause()
        // The position stays; the next playback should start at full volume again.
        target.volume = 1f
        _state.value = null
        firedAt = now()
        _shakeWindow.value = true
        afterJob = scope.launch {
            delay(SHAKE_AFTER_MS)
            closeAfterWindow()
        }
    }

    private fun closeAfterWindow() {
        afterJob?.cancel()
        afterJob = null
        _shakeWindow.value = false
        plan = null
        firedAt = null
    }

    private sealed interface Step {
        data object Stop : Step
        data object Wait : Step
        data class Remaining(val ms: Long) : Step
    }

    private sealed interface Plan {
        /** From this remaining time on, the timer counts as expired. */
        val slackMs: Long

        class Duration(val endsAt: Long) : Plan {
            override val slackMs = 0L
        }

        /**
         * Until [endMs] in the book [bookId]; all of them are only fixed after the first step. Whoever seeks before [fromMs] (after an
         * extension, the start of the original chapter) falls asleep at the end of the new chapter. Stop just before the
         * boundary: otherwise an episode would already move on to the next one.
         */
        class ToEnd(var bookId: Long? = null, var endMs: Long? = null, var fromMs: Long? = null, var music: Boolean? = null) : Plan {
            override val slackMs = END_SLACK_MS
            private var lastRemaining = Long.MAX_VALUE

            fun remaining(target: SleepTimerTarget): Step {
                val spot = target.spokenSpot()
                if (spot == null) {
                    // Just crossed the boundary into the next item (episode finished, next one loading): that was the end.
                    if (lastRemaining < CROSS_TOLERANCE_MS) return Step.Remaining(0)
                    return if (target.isSpoken || target.hasMusic) Step.Wait else Step.Stop
                }
                // Started for a book or episode and now music plays (or the other way round): that timer is over, unless it was
                // just its end.
                val kind = music
                if (kind == null) {
                    music = spot.music
                } else if (kind != spot.music) {
                    return if (lastRemaining < CROSS_TOLERANCE_MS) Step.Remaining(0) else Step.Stop
                }
                if (spot.bookId != bookId) {
                    if (bookId != null && lastRemaining < CROSS_TOLERANCE_MS) return Step.Remaining(0)
                    bookId = spot.bookId
                    endMs = null
                    fromMs = null
                }
                val position = spot.positionMs
                var end = endMs ?: spot.endAfter(position)
                var from = fromMs ?: spot.startOf(end)
                val overshoot = position - end
                when {
                    // Naturally ran past the boundary (between two steps): stop.
                    overshoot >= 0 && overshoot <= CROSS_TOLERANCE_MS * spot.speed -> return Step.Remaining(0)
                    // Seeked: the end of the section you are in now applies.
                    overshoot >= 0 || position < from -> {
                        end = spot.endAfter(position)
                        from = spot.startOf(end)
                    }
                }
                endMs = end
                fromMs = from
                val remaining = ((end - position).coerceAtLeast(0) / spot.speed.coerceAtLeast(MIN_SPEED)).toLong()
                lastRemaining = remaining
                return Step.Remaining(remaining)
            }
        }
    }

    companion object {
        /** Time span before expiry during which the volume decreases. */
        const val FADE_MS = 20_000L
        private const val FADE_STEP_MS = 250L
        private const val TICK_MS = 1_000L
        private const val MS_PER_MINUTE = 60_000L

        /** "Until the end" stops this close before the boundary. */
        internal const val END_SLACK_MS = 300L

        /** This far past the boundary it still counts as "just reached" rather than skipped. */
        private const val CROSS_TOLERANCE_MS = 3_000L
        private const val MIN_SPEED = 0.1f

        /** Shaking extends during the last seconds and for a while after stopping. */
        const val SHAKE_WINDOW_MS = 60_000L
        const val SHAKE_AFTER_MS = 120_000L

        /** Durations both apps offer for selection. */
        val PRESET_MINUTES = listOf(15, 30, 45, 60)

        /** Step size and upper limit for the custom duration. */
        const val STEP_MINUTES = 5
        const val MAX_MINUTES = 240
    }
}
