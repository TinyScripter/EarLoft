package keinabersmart.kleinaberlaut.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.PowerManager
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/** Position im ganzen Buch bzw. in der ganzen Folge und deren Länge (0 = unbekannt). */
data class BookClock(val positionMs: Long, val durationMs: Long)

data class WidgetState(
    val nowPlaying: NowPlaying? = null,
    val cover: Bitmap? = null,
    /** Grundfarbe des Covers (ARGB) für die Widget-Farben; `null` = Systemfarben. */
    val seedColor: Int? = null,
    /** Spielzeit bei Büchern und Folgen; `null` bei Musik oder solange sie nicht feststeht. */
    val clock: BookClock? = null,
)

/**
 * Hält den Zustand für das Widget (nur im Speicher) und aktualisiert es gedrosselt.
 * Wird vom PlaybackService auch dann benachrichtigt, wenn die App-UI nicht läuft.
 */
@Singleton
class NowPlayingWidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val artworkRepository: ArtworkRepository,
    private val albumColorRepository: AlbumColorRepository,
    private val bookCovers: BookCovers,
    private val books: BookRepository,
    private val servicePlayer: ServicePlayer,
    @ApplicationScope scope: CoroutineScope,
) : NowPlayingObserver {

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val _state = MutableStateFlow(WidgetState())
    val state: StateFlow<WidgetState> = _state.asStateFlow()

    /** Ist mindestens ein Widget platziert? Ohne Widget läuft keine Uhr. */
    private val widgetsPlaced = MutableStateFlow(false)

    init {
        scope.launch {
            if (GlanceAppWidgetManager(context).getGlanceIds(NowPlayingWidget::class.java).isNotEmpty()) widgetsPlaced.value = true
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

    /**
     * Die Spielzeit eines Buchs bzw. einer Folge in Minuten: aktualisiert beim Wechsel der Minute (in Buchzeit, also mit dem
     * Tempo), solange es läuft und der Bildschirm an ist; pausiert, gibt es sie einmal. Jede Aktualisierung baut das ganze
     * Widget samt Cover neu, ein Sekundentakt wäre dafür zu teuer.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun clockUpdates(nowPlaying: NowPlaying?): Flow<BookClock?> {
        val bookId = nowPlaying?.bookId ?: return flowOf(null)
        return flow {
            val timeline = books.timeline(bookId)
            if (!nowPlaying.isPlaying) {
                emit(currentClock(bookId, timeline)?.first)
                return@flow
            }
            emitAll(
                screenOn().flatMapLatest { on ->
                    if (!on) {
                        emptyFlow()
                    } else {
                        flow {
                            while (true) {
                                val (clock, waitMs) = currentClock(bookId, timeline) ?: break
                                emit(clock)
                                delay(waitMs)
                            }
                        }
                    }
                },
            )
        }.distinctUntilChanged()
    }

    /** An und aus des Bildschirms, mit dem jetzigen Stand zuerst. Der Empfänger ist nur registriert, solange ein Buch läuft. */
    private fun screenOn(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(intent.action == Intent.ACTION_SCREEN_ON)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(powerManager.isInteractive)
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    /** Spielzeit auf die Minute abgerundet und die Wartezeit bis zum nächsten Minutenwechsel; `null` ohne laufendes Buch. */
    private suspend fun currentClock(bookId: Long, timeline: BookTimeline?): Pair<BookClock, Long>? = withContext(Dispatchers.Main.immediate) {
        val position = servicePlayer.bookPosition(bookId) ?: return@withContext null
        val inTrack = position.positionInTrackMs
        val inBook = timeline?.toBookPosition(position.trackIndex, inTrack) ?: inTrack
        BookClock(positionMs = inBook - inBook % MINUTE_MS, durationMs = timeline?.durationMs ?: 0) to
            msUntilNextMinute(inBook, position.speed)
    }


    override fun onNowPlayingChanged(nowPlaying: NowPlaying?) {
        this.nowPlaying.value = nowPlaying
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
        // Die Spielzeit kommt vom eigenen Takt; sie bleibt, bis der nächste Wert da ist.
        _state.value = WidgetState(nowPlaying, cover, seedColor, previous.clock.takeIf { nowPlaying?.bookId != null })
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

private const val MINUTE_MS = 60_000L

/** Etwas nach dem Wechsel aufwachen, damit die neue Minute sicher erreicht ist. */
private const val MINUTE_SLACK_MS = 200L

/** Echtzeit bis zur nächsten vollen Minute Buchzeit bei [speed] (die Buchzeit läuft [speed]-mal so schnell). */
internal fun msUntilNextMinute(positionMs: Long, speed: Float): Long {
    val inBook = MINUTE_MS - positionMs.coerceAtLeast(0) % MINUTE_MS
    return (inBook / speed.coerceAtLeast(MIN_SPEED)).toLong() + MINUTE_SLACK_MS
}

private const val MIN_SPEED = 0.1f

@Module
@InstallIn(SingletonComponent::class)
internal interface WidgetModule {
    @Binds
    @IntoSet
    fun bindNowPlayingObserver(updater: NowPlayingWidgetUpdater): NowPlayingObserver
}
