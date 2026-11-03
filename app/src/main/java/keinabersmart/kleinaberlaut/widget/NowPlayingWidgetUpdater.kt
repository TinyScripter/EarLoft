package keinabersmart.kleinaberlaut.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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

    init {
        @OptIn(FlowPreview::class)
        nowPlaying
            .debounce(UPDATE_THROTTLE_MS)
            .onEach { update(it) }
            .launchIn(scope)
        @OptIn(ExperimentalCoroutinesApi::class)
        nowPlaying
            .flatMapLatest { clockUpdates(it) }
            .onEach { clock ->
                _state.value = _state.value.copy(clock = clock)
                NowPlayingWidget().updateAll(context)
            }
            .launchIn(scope)
    }

    /**
     * Die Spielzeit eines Buchs bzw. einer Folge. Sie ruckelt nicht von selbst, deshalb ein Takt von einer Sekunde, solange
     * es läuft und der Bildschirm an ist; pausiert, gibt es sie einmal.
     */
    private fun clockUpdates(nowPlaying: NowPlaying?): Flow<BookClock?> {
        val bookId = nowPlaying?.bookId ?: return flowOf(null)
        return flow {
            val timeline = books.timeline(bookId)
            while (true) {
                if (nowPlaying.isPlaying && !powerManager.isInteractive) {
                    delay(CLOCK_INTERVAL_MS)
                    continue
                }
                emit(currentClock(bookId, timeline))
                if (!nowPlaying.isPlaying) break
                delay(CLOCK_INTERVAL_MS)
            }
        }.distinctUntilChanged()
    }

    private suspend fun currentClock(bookId: Long, timeline: BookTimeline?): BookClock? = withContext(Dispatchers.Main.immediate) {
        val position = servicePlayer.bookPosition(bookId) ?: return@withContext null
        val inTrack = position.positionInTrackMs
        BookClock(
            positionMs = timeline?.toBookPosition(position.trackIndex, inTrack) ?: inTrack,
            durationMs = timeline?.durationMs ?: 0,
        )
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
        const val CLOCK_INTERVAL_MS = 1_000L
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
