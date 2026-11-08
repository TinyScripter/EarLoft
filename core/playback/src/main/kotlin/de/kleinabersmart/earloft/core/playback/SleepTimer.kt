package de.kleinabersmart.earloft.core.playback

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Das, was der [SleepTimer] vom Player braucht; so lässt er sich ohne echten Player testen. */
internal interface SleepTimerTarget {
    var volume: Float
    fun pause()
}

internal fun Player.asSleepTimerTarget() = object : SleepTimerTarget {
    override var volume: Float
        get() = this@asSleepTimerTarget.volume
        set(value) {
            this@asSleepTimerTarget.volume = value
        }

    override fun pause() = this@asSleepTimerTarget.pause()
}

/** Lautstärke [remainingMs] vor Ablauf: linear von 1 (Beginn des Ausblendens) auf 0. */
internal fun fadeVolume(remainingMs: Long): Float = (remainingMs.toFloat() / SleepTimer.FADE_MS).coerceIn(0f, 1f)

/**
 * Sleep-Timer: Nach der eingestellten Dauer wird die Musik ausgeblendet und pausiert. Die Zeit läuft im Prozess
 * des [PlaybackService], nicht in der UI, und überlebt deshalb das Schließen der App. Handy und Uhr haben je ihren
 * eigenen Timer, weil die Uhr eigenständig spielt.
 *
 * Alle Methoden gehören auf den Main-Thread.
 */
@Singleton
class SleepTimer internal constructor(
    private val now: () -> Long,
    private val scope: CoroutineScope,
) {
    @Inject constructor() : this(SystemClock::elapsedRealtime, MainScope())

    private val _endsAtMs = MutableStateFlow<Long?>(null)

    /** Ablaufzeit auf der Uhr von [SystemClock.elapsedRealtime]; `null`, solange kein Timer läuft. */
    val endsAtMs: StateFlow<Long?> = _endsAtMs.asStateFlow()

    /** Restzeit, jede Sekunde neu; `null`, solange kein Timer läuft. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val remainingMs: Flow<Long?> = _endsAtMs.flatMapLatest { endsAt ->
        if (endsAt == null) {
            flowOf(null)
        } else {
            flow {
                while (true) {
                    val remaining = (endsAt - now()).coerceAtLeast(0)
                    emit(remaining)
                    if (remaining == 0L) break
                    delay(TICK_MS)
                }
            }
        }
    }

    private var target: SleepTimerTarget? = null
    private var job: Job? = null

    internal fun attach(target: SleepTimerTarget) {
        this.target = target
    }

    /** Der Service wird beendet: Ein Timer ohne Player hätte nichts mehr zu tun. */
    internal fun detach() {
        cancel()
        target = null
    }

    /** Startet den Timer neu; ein laufender wird ersetzt. */
    fun start(durationMs: Long) {
        val target = target ?: return
        if (durationMs <= 0) return
        job?.cancel()
        target.volume = 1f
        val endsAt = now() + durationMs
        _endsAtMs.value = endsAt
        job = scope.launch { run(target, endsAt) }
    }

    fun cancel() {
        job?.cancel()
        job = null
        target?.volume = 1f
        _endsAtMs.value = null
    }

    private suspend fun run(target: SleepTimerTarget, endsAt: Long) {
        while (true) {
            val remaining = endsAt - now()
            if (remaining <= 0) break
            if (remaining > FADE_MS) {
                delay(remaining - FADE_MS)
            } else {
                target.volume = fadeVolume(remaining)
                delay(minOf(remaining, FADE_STEP_MS))
            }
        }
        target.pause()
        // Die Position bleibt, beim nächsten Abspielen soll es wieder in voller Lautstärke losgehen.
        target.volume = 1f
        _endsAtMs.value = null
    }

    companion object {
        /** Zeitspanne vor Ablauf, in der die Lautstärke sinkt. */
        const val FADE_MS = 20_000L
        private const val FADE_STEP_MS = 250L
        private const val TICK_MS = 1_000L

        /** Dauern, die beide Apps zur Auswahl anbieten. */
        val PRESET_MINUTES = listOf(15, 30, 45, 60)

        /** Schrittweite und Obergrenze für die eigene Dauer. */
        const val STEP_MINUTES = 5
        const val MAX_MINUTES = 240
    }
}
