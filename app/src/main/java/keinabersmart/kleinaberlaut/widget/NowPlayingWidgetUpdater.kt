package keinabersmart.kleinaberlaut.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Restzeit eines Buchs bzw. einer Folge in Echtzeit beim jetzigen Tempo, gemessen zu [measuredAtMs]
 * ([SystemClock.elapsedRealtime]). Läuft sie ([running]), zählt das Widget selbst herunter.
 */
data class BookClock(val bookId: Long, val remainingMs: Long, val running: Boolean, val measuredAtMs: Long) {
    /** Angehalten zum Zeitpunkt [nowMs]; lief sie, ist seit der Messung Zeit vergangen. */
    fun stoppedAt(nowMs: Long): BookClock =
        if (running) copy(remainingMs = (remainingMs - (nowMs - measuredAtMs)).coerceAtLeast(0), running = false, measuredAtMs = nowMs) else this
}

data class WidgetState(
    val nowPlaying: NowPlaying? = null,
    val cover: Bitmap? = null,
    /** Grundfarbe des Covers (ARGB) für die Widget-Farben; `null` = Systemfarben. */
    val seedColor: Int? = null,
    /** Restzeit bei Büchern und Folgen; `null` bei Musik oder solange sie nicht feststeht. */
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

    private val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val _state = MutableStateFlow(WidgetState())
    val state: StateFlow<WidgetState> = _state.asStateFlow()

    /** Ist mindestens ein Widget platziert? Ohne Widget wird keine Restzeit gemessen. */
    private val widgetsPlaced = MutableStateFlow(false)

    init {
        scope.launch {
            if (GlanceAppWidgetManager(context).getGlanceIds(NowPlayingWidget::class.java).isNotEmpty()) widgetsPlaced.value = true
        }
        scope.launch {
            restore()
            // Erst nach dem Laden speichern, sonst überschriebe der leere Anfangszustand den gemerkten.
            _state
                .mapNotNull { state -> state.nowPlaying?.let { SavedWidget(it.copy(isPlaying = false), state.clock?.remainingMs) } }
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
        if (bookId != null && saved.remainingMs != null && _state.value.clock == null) {
            _state.value = _state.value.copy(clock = BookClock(bookId, saved.remainingMs, running = false, SystemClock.elapsedRealtime()))
        }
        nowPlaying.compareAndSet(null, saved.nowPlaying)
    }

    /**
     * Die Restzeit eines Buchs bzw. einer Folge: einmal beim Start, bei Pause, beim Spulen und bei Tempowechseln. Dazwischen
     * zählt das Widget sie selbst herunter (Chronometer im Launcher, tickt nur, solange es zu sehen ist), deshalb in
     * Echtzeit statt Buchzeit – die stimmt bei jedem Tempo mit dem Ticken überein.
     */
    private fun clockUpdates(nowPlaying: NowPlaying?): Flow<BookClock?> {
        val bookId = nowPlaying?.bookId ?: return flowOf(null)
        return flow {
            val timeline = books.timeline(bookId)
            emit(currentClock(bookId, timeline, nowPlaying.isPlaying))
            @OptIn(FlowPreview::class)
            servicePlayer.timingChanges()
                .debounce(UPDATE_THROTTLE_MS)
                .collect { emit(currentClock(bookId, timeline, nowPlaying.isPlaying)) }
        }.distinctUntilChanged()
    }

    /** Ohne geladenes Buch (Service beendet, Prozess neu) bleibt die letzte Restzeit stehen. */
    private suspend fun currentClock(bookId: Long, timeline: BookTimeline?, running: Boolean): BookClock? = withContext(Dispatchers.Main.immediate) {
        val now = SystemClock.elapsedRealtime()
        val position = servicePlayer.bookPosition(bookId)
        if (timeline == null || timeline.durationMs <= 0 || position == null) {
            return@withContext _state.value.clock?.takeIf { it.bookId == bookId }?.stoppedAt(now)
        }
        val inBook = timeline.toBookPosition(position.trackIndex, position.positionInTrackMs)
        BookClock(bookId, timeline.remainingBookMs(inBook, position.speed), running, now)
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
        const val COVER_SIZE_PX = 256
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface WidgetModule {
    @Binds
    @IntoSet
    fun bindNowPlayingObserver(updater: NowPlayingWidgetUpdater): NowPlayingObserver
}
