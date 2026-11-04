package keinabersmart.kleinaberlaut.widget

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import keinabersmart.kleinaberlaut.core.data.repository.AlbumColorRepository
import keinabersmart.kleinaberlaut.core.data.repository.ArtworkRepository
import keinabersmart.kleinaberlaut.core.data.repository.seedColorOf
import keinabersmart.kleinaberlaut.core.data.spoken.BookCovers
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import keinabersmart.kleinaberlaut.core.model.BookTimeline
import keinabersmart.kleinaberlaut.core.playback.NowPlaying
import keinabersmart.kleinaberlaut.core.playback.NowPlayingObserver
import keinabersmart.kleinaberlaut.core.playback.ServicePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Spielzeit eines Buchs bzw. einer Folge: Position und Länge in Buchzeit (Länge 0 = unbekannt) und das Tempo, gemessen zu
 * [measuredAtMs] ([SystemClock.elapsedRealtime]).
 */
data class BookClock(
    val bookId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    val running: Boolean,
    val measuredAtMs: Long,
) {
    /** Zählt das Widget selbst (Chronometer im Launcher)? Nur bei 1×, denn der Chronometer läuft in Echtzeit. */
    val ticksItself: Boolean get() = running && abs(speed - 1f) < SPEED_TOLERANCE

    /** Stand zum Zeitpunkt [nowMs]: Läuft sie, ist seit der Messung Buchzeit vergangen (Echtzeit mal Tempo). */
    fun at(nowMs: Long): BookClock {
        if (!running) return this
        val position = positionMs + ((nowMs - measuredAtMs) * speed).toLong()
        return copy(positionMs = if (durationMs > 0) position.coerceAtMost(durationMs) else position, measuredAtMs = nowMs)
    }

    /** Angehalten zum Zeitpunkt [nowMs]. */
    fun stoppedAt(nowMs: Long): BookClock = if (running) at(nowMs).copy(running = false) else this

    /** Echtzeit, bis die Stunden eine Stelle dazubekommen und der Chronometer einen anderen Vorsatz braucht; `null` = nie. */
    fun msUntilPrefixChange(): Long? {
        val next = PREFIX_CHANGES_MS.firstOrNull { it > positionMs } ?: return null
        return untilBookTime(next - positionMs)
    }

    private fun untilBookTime(bookMs: Long): Long = (bookMs / speed.coerceAtLeast(MIN_SPEED)).toLong() + TICK_SLACK_MS
}

data class WidgetState(
    val nowPlaying: NowPlaying? = null,
    val cover: Bitmap? = null,
    /** Grundfarbe des Covers (ARGB) für die Widget-Farben; `null` = Systemfarben. */
    val seedColor: Int? = null,
    /** Spielzeit bei Büchern und Folgen; `null` bei Musik oder solange sie nicht feststeht. */
    val clock: BookClock? = null,
)

/**
 * Hält den Zustand für das Widget und aktualisiert es gedrosselt. Der letzte Stand bleibt stehen, auch wenn der Service
 * endet, und überdauert den Prozess ([WidgetStateStore]). Wird vom PlaybackService auch dann benachrichtigt, wenn die
 * App-UI nicht läuft.
 */
@Singleton
class NowPlayingWidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val artworkRepository: ArtworkRepository,
    private val albumColorRepository: AlbumColorRepository,
    private val bookCovers: BookCovers,
    private val books: BookRepository,
    private val servicePlayer: ServicePlayer,
    private val store: WidgetStateStore,
    @ApplicationScope scope: CoroutineScope,
) : NowPlayingObserver {

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    private val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val _state = MutableStateFlow(WidgetState())
    val state: StateFlow<WidgetState> = _state.asStateFlow()

    /** Ist mindestens ein Widget platziert? Ohne Widget wird keine Spielzeit gemessen. */
    private val widgetsPlaced = MutableStateFlow(false)

    init {
        scope.launch {
            if (GlanceAppWidgetManager(context).getGlanceIds(NowPlayingWidget::class.java).isNotEmpty()) widgetsPlaced.value = true
        }
        scope.launch {
            restore()
            // Erst nach dem Laden speichern, sonst überschriebe der leere Anfangszustand den gemerkten. Verzögert, damit der
            // Sekundentakt bei Tempo ≠ 1× nicht jede Sekunde schreibt; pausiert kommt der Stand so nach kurzer Zeit an.
            @OptIn(FlowPreview::class)
            _state
                .mapNotNull { state ->
                    state.nowPlaying?.let { SavedWidget(it.copy(isPlaying = false), state.clock?.positionMs, state.clock?.durationMs) }
                }
                .debounce(SAVE_DELAY_MS)
                .distinctUntilChanged()
                .collect { store.save(it) }
        }
        @OptIn(FlowPreview::class)
        nowPlaying
            .debounce(UPDATE_THROTTLE_MS)
            .onEach { update(it) }
            .launchIn(scope)
        @OptIn(ExperimentalCoroutinesApi::class)
        combine(nowPlaying, widgetsPlaced, ::Pair)
            .flatMapLatest { (nowPlaying, placed) -> if (placed) clockUpdates(nowPlaying) else emptyFlow() }
            .onEach { clock ->
                _state.value = _state.value.copy(clock = clock)
                NowPlayingWidget().updateAll(context)
            }
            .launchIn(scope)
    }

    /** Ein Widget wird angezeigt (Glance baut es auf). */
    fun onWidgetShown() {
        widgetsPlaced.value = true
    }

    /** Das letzte Widget wurde entfernt. */
    fun onAllWidgetsRemoved() {
        widgetsPlaced.value = false
    }

    /** Nach dem Prozessstart den gemerkten Stand zeigen, solange der Service nichts gemeldet hat. */
    private suspend fun restore() {
        val saved = store.load() ?: return
        val bookId = saved.nowPlaying.bookId
        if (bookId != null && saved.positionMs != null && _state.value.clock == null) {
            val clock = BookClock(bookId, saved.positionMs, saved.durationMs ?: 0, speed = 1f, running = false, SystemClock.elapsedRealtime())
            _state.value = _state.value.copy(clock = clock)
        }
        nowPlaying.compareAndSet(null, saved.nowPlaying)
    }

    /**
     * Die Spielzeit eines Buchs bzw. einer Folge: gemessen beim Start, bei Pause, beim Spulen und bei Tempowechseln
     * ([measured]); dazwischen läuft sie im Sekundentakt weiter.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun clockUpdates(nowPlaying: NowPlaying?): Flow<BookClock?> {
        val bookId = nowPlaying?.bookId ?: return flowOf(null)
        return flow {
            val timeline = books.timeline(bookId)
            val measurements = merge(flowOf(Unit), servicePlayer.timingChanges().debounce(UPDATE_THROTTLE_MS))
            emitAll(measurements.flatMapLatest { measured(bookId, timeline, nowPlaying.isPlaying) })
        }.distinctUntilChanged()
    }

    /**
     * Eine Messung und, solange es läuft, der Takt danach. Bei 1× zählt der Chronometer im Launcher selbst, und zwar nur,
     * solange das Widget zu sehen ist; neu gemessen wird nur, wenn die Stunden eine Stelle dazubekommen. Bei anderem Tempo
     * kann er nicht mitzählen (er läuft in Echtzeit): Dann setzt die App die Zeit jede Sekunde neu, solange das Widget zu
     * sehen sein kann ([widgetMaybeVisible]).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun measured(bookId: Long, timeline: BookTimeline?, playing: Boolean): Flow<BookClock?> = flow {
        var clock = currentClock(bookId, timeline, playing)
        emit(clock)
        if (clock == null || !clock.running) return@flow
        if (clock.ticksItself) {
            while (clock != null && clock.running) {
                delay(clock.msUntilPrefixChange() ?: return@flow)
                clock = currentClock(bookId, timeline, running = true)
                emit(clock)
            }
        } else {
            emitAll(widgetMaybeVisible().flatMapLatest { visible -> if (visible) secondTicks(bookId, timeline) else emptyFlow() })
        }
    }

    /**
     * Die Spielzeit einmal je Sekunde Echtzeit, bei 1,5× springt sie also um 1 bis 2 Sekunden. Hochgerechnet aus der
     * letzten Messung, denn der Player meldet seine Position in Sprüngen von einigen hundert Millisekunden. Nachgemessen
     * wird jede Minute.
     */
    private fun secondTicks(bookId: Long, timeline: BookTimeline?): Flow<BookClock?> = flow {
        var measured = currentClock(bookId, timeline, running = true)
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (measured != null && measured.running && now - measured.measuredAtMs >= REMEASURE_MS) {
                measured = currentClock(bookId, timeline, running = true)
            }
            val clock = measured?.at(now)
            emit(clock)
            if (clock == null || !clock.running) break
            delay(SECOND_MS - (SystemClock.elapsedRealtime() - start) % SECOND_MS)
        }
    }

    /**
     * Kann das Widget gerade zu sehen sein? Bildschirm an und entsperrt, und die App ist nicht selbst im Vordergrund. Ob
     * der Startbildschirm vorn ist, erfährt eine App nicht. Die Empfänger sind nur registriert, solange der Takt läuft.
     */
    private fun widgetMaybeVisible(): Flow<Boolean> =
        combine(screenUnlocked(), ProcessLifecycleOwner.get().lifecycle.currentStateFlow) { unlocked, appState ->
            unlocked && !appState.isAtLeast(Lifecycle.State.STARTED)
        }.distinctUntilChanged()

    private fun screenUnlocked(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(isScreenUnlocked())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(isScreenUnlocked())
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun isScreenUnlocked(): Boolean = powerManager.isInteractive && !keyguardManager.isKeyguardLocked

    /** Ohne geladenes Buch (Service beendet, Prozess neu) bleibt die letzte Spielzeit stehen. */
    private suspend fun currentClock(bookId: Long, timeline: BookTimeline?, running: Boolean): BookClock? = withContext(Dispatchers.Main.immediate) {
        val now = SystemClock.elapsedRealtime()
        val position = servicePlayer.bookPosition(bookId)
            ?: return@withContext _state.value.clock?.takeIf { it.bookId == bookId }?.stoppedAt(now)
        val inTrack = position.positionInTrackMs
        val inBook = timeline?.toBookPosition(position.trackIndex, inTrack) ?: inTrack
        BookClock(bookId, inBook, timeline?.durationMs ?: 0, position.speed, running, now)
    }


    override fun onNowPlayingChanged(nowPlaying: NowPlaying?) {
        // Endet der Service oder leert sich die Warteschlange, bleibt der letzte Stand stehen, nur eben pausiert.
        this.nowPlaying.value = nowPlaying ?: this.nowPlaying.value?.copy(isPlaying = false)
    }

    private suspend fun update(nowPlaying: NowPlaying?) {
        val previous = _state.value
        val albumId = nowPlaying?.albumId
        val bookId = nowPlaying?.bookId
        val cover = when {
            bookId != null -> if (bookId == previous.nowPlaying?.bookId && previous.cover != null) previous.cover else bookCovers.cover(bookId)?.scaledToWidget()
            albumId == null -> null
            albumId == previous.nowPlaying?.albumId && previous.cover != null -> previous.cover
            else -> artworkRepository.albumCover(albumId, COVER_SIZE_PX)
        }
        val seedColor = when {
            bookId != null -> when {
                bookId == previous.nowPlaying?.bookId && previous.seedColor != null -> previous.seedColor
                cover == null -> null
                else -> withContext(Dispatchers.Default) { seedColorOf(cover) }
            }
            albumId == null -> null
            albumId == previous.nowPlaying?.albumId && previous.seedColor != null -> previous.seedColor
            else -> albumColorRepository.seedColorNow(albumId)
        }
        // Die Restzeit kommt vom eigenen Takt; sie bleibt, bis der nächste Wert da ist. Der aktuelle Stand wird erst
        // jetzt gelesen, weil sie sich während des Ladens oben ändern kann.
        val clock = _state.value.clock?.takeIf { bookId != null && it.bookId == bookId }
        _state.value = WidgetState(nowPlaying, cover, seedColor, clock)
        NowPlayingWidget().updateAll(context)
    }

    /** Buchcover kommen mit 800 px; das Widget braucht wie bei Albumcovern nur [COVER_SIZE_PX] (Größe der RemoteViews, Layout). */
    private fun Bitmap.scaledToWidget(): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= COVER_SIZE_PX) return this
        val factor = COVER_SIZE_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(this, (width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1), true)
    }

    private companion object {
        const val UPDATE_THROTTLE_MS = 300L
        const val SAVE_DELAY_MS = 2_000L
        const val REMEASURE_MS = 60_000L
        const val COVER_SIZE_PX = 256
    }
}

private const val SECOND_MS = 1_000L
private const val HOUR_MS = 3_600_000L

/** Ab hier zeigt der Chronometer eine Stundenstelle mehr und braucht einen anderen [chronometerPrefix]. */
private val PREFIX_CHANGES_MS = listOf(HOUR_MS, 10 * HOUR_MS)

/** Etwas nach dem Wechsel aufwachen, damit er sicher erreicht ist. */
private const val TICK_SLACK_MS = 20L
private const val MIN_SPEED = 0.1f
private const val SPEED_TOLERANCE = 0.001f

/** „00:45:12“: Stunden mindestens zweistellig, angefangene Sekunden abgeschnitten. */
internal fun clockText(ms: Long): String {
    val seconds = (ms / SECOND_MS).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
}

/** „00:45:12 / 03:12:01“; ist die Länge unbekannt (0), nur die Position. */
internal fun bookClockText(positionMs: Long, durationMs: Long): String =
    if (durationMs > 0) "${clockText(positionMs)} / ${clockText(durationMs)}" else clockText(positionMs)

/**
 * Vorsatz vor den Text des Chronometers, damit er wie [clockText] aussieht: Der zeigt „45:12“ unter einer Stunde und
 * „3:12:01“ ab einer Stunde (`DateUtils.formatElapsedTime`). Wechselt der Vorsatz, misst der Updater neu.
 */
internal fun chronometerPrefix(positionMs: Long): String = when {
    positionMs < HOUR_MS -> "00:"
    positionMs < 10 * HOUR_MS -> "0"
    else -> ""
}

@Module
@InstallIn(SingletonComponent::class)
internal interface WidgetModule {
    @Binds
    @IntoSet
    fun bindNowPlayingObserver(updater: NowPlayingWidgetUpdater): NowPlayingObserver
}
