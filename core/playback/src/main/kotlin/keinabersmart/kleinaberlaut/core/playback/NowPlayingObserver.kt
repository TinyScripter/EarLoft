package keinabersmart.kleinaberlaut.core.playback

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/** Kurzfassung des Wiedergabezustands für Oberflächen außerhalb der App (z. B. Widget). */
data class NowPlaying(
    val songId: Long,
    val title: String,
    val artist: String,
    val albumId: Long?,
    val isPlaying: Boolean,
)

/**
 * Wird vom [PlaybackService] bei Titel- oder Statuswechsel aufgerufen – auch wenn die App-UI
 * nicht läuft (z. B. Start per Kopfhörertaste). Implementierungen per `@IntoSet` binden.
 */
interface NowPlayingObserver {
    fun onNowPlayingChanged(nowPlaying: NowPlaying?)
}

@Module
@InstallIn(SingletonComponent::class)
internal interface PlaybackBindings {
    @Multibinds
    fun nowPlayingObservers(): Set<NowPlayingObserver>
}
