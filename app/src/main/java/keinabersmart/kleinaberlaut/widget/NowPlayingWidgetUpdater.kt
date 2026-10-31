package keinabersmart.kleinaberlaut.widget

import android.content.Context
import android.graphics.Bitmap
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
import keinabersmart.kleinaberlaut.core.data.spoken.BookCovers
import keinabersmart.kleinaberlaut.core.playback.NowPlaying
import keinabersmart.kleinaberlaut.core.playback.NowPlayingObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

data class WidgetState(
    val nowPlaying: NowPlaying? = null,
    val cover: Bitmap? = null,
    /** Grundfarbe des Albums (ARGB) für die Widget-Farben; `null` = Systemfarben. */
    val seedColor: Int? = null,
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
    @ApplicationScope scope: CoroutineScope,
) : NowPlayingObserver {

    private val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val _state = MutableStateFlow(WidgetState())
    val state: StateFlow<WidgetState> = _state.asStateFlow()

    init {
        @OptIn(FlowPreview::class)
        nowPlaying
            .debounce(UPDATE_THROTTLE_MS)
            .onEach { update(it) }
            .launchIn(scope)
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
            albumId == null -> null
            albumId == previous.nowPlaying?.albumId && previous.seedColor != null -> previous.seedColor
            else -> albumColorRepository.seedColorNow(albumId)
        }
        _state.value = WidgetState(nowPlaying, cover, seedColor)
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
