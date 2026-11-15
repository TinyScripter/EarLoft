package de.kleinabersmart.earloft.core.playback

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
 * Wo ein Buch bzw. eine Folge gerade steht (Buchpositionen): [ends] sind die Stellen, an denen der Timer „bis zum Ende“
 * anhalten kann, aufsteigend – bei Büchern mit Kapiteln jedes Kapitelende, sonst nur das Ende des Ganzen.
 */
internal class SpokenSpot(val bookId: Long, val positionMs: Long, val speed: Float, val ends: List<Long>) {
    /** Das Ende des Abschnitts, in dem [positionMs] liegt. */
    fun endAfter(positionMs: Long): Long = ends.firstOrNull { it > positionMs } ?: ends.last()

    /** Anfang des Abschnitts, der bei [endMs] endet. */
    fun startOf(endMs: Long): Long = ends.lastOrNull { it < endMs } ?: 0L
}

/** Das, was der [SleepTimer] vom Player braucht; so lässt er sich ohne echten Player testen. */
internal interface SleepTimerTarget {
    var volume: Float
    val isPlaying: Boolean

    /** Ein Buch oder eine Folge ist geladen (auch wenn [spokenSpot] noch fehlt, weil die Zeitachse lädt). */
    val isSpoken: Boolean
    fun pause()
    fun play()

    /** Stand des laufenden Buchs bzw. der Folge; `null` bei Musik oder solange die Zeitachse lädt. */
    fun spokenSpot(): SpokenSpot?

    /** Bei Büchern und Folgen [heardMs] gehörte Zeit zurück (mit Tempo umgerechnet); bei Musik nichts. */
    fun rewind(heardMs: Long)
}

internal fun Player.asSleepTimerTarget(spot: () -> SpokenSpot?, rewind: (Long) -> Unit) = object : SleepTimerTarget {
    override var volume: Float
        get() = this@asSleepTimerTarget.volume
        set(value) {
            this@asSleepTimerTarget.volume = value
        }
    override val isPlaying: Boolean get() = this@asSleepTimerTarget.isPlaying
    override val isSpoken: Boolean get() = currentMediaItem?.isSpoken == true
    override fun pause() = this@asSleepTimerTarget.pause()
    override fun play() = this@asSleepTimerTarget.play()
    override fun spokenSpot(): SpokenSpot? = spot()
    override fun rewind(heardMs: Long) = rewind.invoke(heardMs)
}

/** Lautstärke [remainingMs] vor Ablauf: linear von 1 (Beginn des Ausblendens) auf 0. */
internal fun fadeVolume(remainingMs: Long): Float = (remainingMs.toFloat() / SleepTimer.FADE_MS).coerceIn(0f, 1f)

/** Ein laufender Sleep-Timer: Restzeit (Echtzeit) und ob er bis zum Ende des Kapitels bzw. der Folge läuft. */
data class SleepTimerState(val remainingMs: Long, val toEnd: Boolean)

/**
 * Sleep-Timer: Nach der eingestellten Dauer oder am Ende des Kapitels bzw. der Folge wird ausgeblendet und pausiert. Die
 * Zeit läuft im Prozess des [PlaybackService], nicht in der UI, und überlebt deshalb das Schließen der App. Handy und Uhr
 * haben je ihren eigenen Timer, weil die Uhr eigenständig spielt.
 *
 * „Bis zum Ende“ rechnet die Restzeit bei jedem Schritt aus der Position neu: Tempowechsel zählen sofort, und wer in ein
 * anderes Kapitel spult, schläft an dessen Ende ein. Solange pausiert ist, wird nicht ausgeblendet.
 *
 * Schütteln ([extend]) verlängert in den letzten [SHAKE_WINDOW_MS], solange abgespielt wird, und bis [SHAKE_AFTER_MS]
 * nach dem Anhalten ([shakeWindow]): um die zuletzt gewählte Dauer bzw. bis zum Ende des nächsten Kapitels.
 *
 * Alle Methoden gehören auf den Main-Thread.
 */
@Singleton
class SleepTimer internal constructor(
    private val now: () -> Long,
    private val scope: CoroutineScope,
) {
    @Inject constructor() : this(SystemClock::elapsedRealtime, MainScope())

    private val _state = MutableStateFlow<SleepTimerState?>(null)

    /** `null`, solange kein Timer läuft; sonst etwa jede Sekunde neu. */
    val state: StateFlow<SleepTimerState?> = _state.asStateFlow()

    /** Restzeit, etwa jede Sekunde neu; `null`, solange kein Timer läuft. */
    val remainingMs: Flow<Long?> = _state.map { it?.remainingMs }.distinctUntilChanged()

    private val _shakeWindow = MutableStateFlow(false)

    /** Jetzt verlängert Schütteln den Timer; nur dann braucht es den Sensor. */
    val shakeWindow: StateFlow<Boolean> = _shakeWindow.asStateFlow()

    private var target: SleepTimerTarget? = null
    private var job: Job? = null
    private var afterJob: Job? = null

    /** Was gerade läuft bzw. zuletzt abgelaufen ist (für das Schütteln danach). */
    private var plan: Plan? = null
    private var firedAt: Long? = null

    /** Zuletzt gewählte Dauer; Schütteln verlängert darum. */
    private var lastDurationMs = PRESET_MINUTES.first() * MS_PER_MINUTE

    internal fun attach(target: SleepTimerTarget) {
        this.target = target
    }

    /** Der Service wird beendet: Ein Timer ohne Player hätte nichts mehr zu tun. */
    internal fun detach() {
        cancel()
        target = null
    }

    /** Startet den Timer neu mit fester Dauer; ein laufender wird ersetzt. */
    fun start(durationMs: Long) {
        if (durationMs <= 0) return
        lastDurationMs = durationMs
        launch(Plan.Duration(now() + durationMs))
    }

    /** Bis zum Ende des Kapitels (Bücher mit Kapiteln) bzw. des Buchs oder der Folge; ohne Buch oder Folge nichts. */
    fun startToEnd() {
        if (target?.isSpoken != true) return
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
     * Schütteln: Kurz vor Ablauf geht es von vorn los (feste Dauer) bzw. bis zum Ende des nächsten Kapitels, was schon
     * ausgeblendet war, wird bei Büchern und Folgen zurückgespult. Kurz nach Ablauf zusätzlich wieder abspielen.
     * `true`, wenn der Timer verlängert wurde.
     */
    fun extend(): Boolean {
        val target = target ?: return false
        if (!_shakeWindow.value) return false
        val plan = plan ?: return false
        val fired = firedAt != null
        // Zurückgespult wird nur, was wirklich leiser lief (pausiert blendet „bis zum Ende“ nicht aus).
        val faded = target.volume < 1f
        val heard = when {
            fired -> FADE_MS
            faded -> (FADE_MS - (_state.value?.remainingMs ?: FADE_MS)).coerceAtLeast(0)
            else -> 0L
        }
        if (heard > 0) target.rewind(heard)
        val next = when (plan) {
            is Plan.Duration -> null
            // Ohne weiteres Kapitel (Folge, letztes Kapitel) um die zuletzt gewählte Dauer.
            is Plan.ToEnd -> plan.endMs?.let { end ->
                target.spokenSpot()?.takeIf { it.bookId == plan.bookId }?.endAfter(end)?.takeIf { it > end }
            }?.let { Plan.ToEnd(bookId = plan.bookId, endMs = it, fromMs = plan.fromMs) }
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
            // Nur während der Wiedergabe: Pausiert bräuchte der Sensor nur Akku, und jede Bewegung spulte zurück.
            _shakeWindow.value = remaining <= SHAKE_WINDOW_MS && target.isPlaying
            // Pausiert läuft „bis zum Ende“ nicht weiter; ausgeblendet wird erst, wenn es wieder läuft.
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
        // Die Position bleibt, beim nächsten Abspielen soll es wieder in voller Lautstärke losgehen.
        target.volume = 1f
        _state.value = null
        firedAt = now()
        _shakeWindow.value = true
        afterJob = scope.launch {
            delay(SHAKE_AFTER_MS)
            _shakeWindow.value = false
            this@SleepTimer.plan = null
            firedAt = null
        }
    }

    private sealed interface Step {
        data object Stop : Step
        data object Wait : Step
        data class Remaining(val ms: Long) : Step
    }

    private sealed interface Plan {
        /** Ab dieser Restzeit gilt der Timer als abgelaufen. */
        val slackMs: Long

        class Duration(val endsAt: Long) : Plan {
            override val slackMs = 0L
        }

        /**
         * Bis [endMs] im Buch [bookId]; alle stehen erst nach dem ersten Schritt fest. Wer vor [fromMs] spult (nach einer
         * Verlängerung der Anfang des ursprünglichen Kapitels), schläft am Ende des neuen Kapitels ein. Knapp vor der
         * Grenze anhalten: Eine Folge ginge sonst schon in die nächste über.
         */
        class ToEnd(var bookId: Long? = null, var endMs: Long? = null, var fromMs: Long? = null) : Plan {
            override val slackMs = END_SLACK_MS
            private var lastRemaining = Long.MAX_VALUE

            fun remaining(target: SleepTimerTarget): Step {
                val spot = target.spokenSpot()
                if (spot == null) {
                    // Gerade über die Grenze ins nächste Item (Folge zu Ende, nächste lädt): Das war das Ende.
                    if (lastRemaining < CROSS_TOLERANCE_MS) return Step.Remaining(0)
                    return if (target.isSpoken) Step.Wait else Step.Stop
                }
                if (spot.bookId != bookId) {
                    if (bookId != null && lastRemaining < CROSS_TOLERANCE_MS) return Step.Remaining(0)
                    bookId = spot.bookId
                    endMs = null
                }
                val position = spot.positionMs
                var end = endMs ?: spot.endAfter(position)
                var from = fromMs ?: spot.startOf(end)
                val overshoot = position - end
                when {
                    // Natürlich über die Grenze gelaufen (zwischen zwei Schritten): anhalten.
                    overshoot >= 0 && overshoot <= CROSS_TOLERANCE_MS * spot.speed -> return Step.Remaining(0)
                    // Gespult: Es gilt das Ende des Abschnitts, in dem man jetzt ist.
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
        /** Zeitspanne vor Ablauf, in der die Lautstärke sinkt. */
        const val FADE_MS = 20_000L
        private const val FADE_STEP_MS = 250L
        private const val TICK_MS = 1_000L
        private const val MS_PER_MINUTE = 60_000L

        /** „Bis zum Ende“ hält so knapp vor der Grenze an. */
        internal const val END_SLACK_MS = 300L

        /** So weit hinter der Grenze gilt sie noch als „gerade erreicht“ statt als übersprungen. */
        private const val CROSS_TOLERANCE_MS = 3_000L
        private const val MIN_SPEED = 0.1f

        /** Schütteln verlängert in den letzten Sekunden und noch eine Weile nach dem Anhalten. */
        const val SHAKE_WINDOW_MS = 60_000L
        const val SHAKE_AFTER_MS = 120_000L

        /** Dauern, die beide Apps zur Auswahl anbieten. */
        val PRESET_MINUTES = listOf(15, 30, 45, 60)

        /** Schrittweite und Obergrenze für die eigene Dauer. */
        const val STEP_MINUTES = 5
        const val MAX_MINUTES = 240
    }
}
