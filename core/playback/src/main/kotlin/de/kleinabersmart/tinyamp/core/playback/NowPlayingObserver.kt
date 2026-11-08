package de.kleinabersmart.tinyamp.core.playback

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Kurzfassung des Wiedergabezustands für Oberflächen außerhalb der App (z. B. Widget).
 * Entweder ein Song ([songId]) oder ein Hörbuch ([bookId]); bei Büchern ist [artist] der Autor.
 */
data class NowPlaying(
    val songId: Long?,
    val title: String,
    val artist: String,
    val albumId: Long?,
    val isPlaying: Boolean,
    val bookId: Long? = null,
    /** Bei [bookId]: eine Podcast-Folge statt eines Buchs. */
    val isEpisode: Boolean = false,
) {
    val isBook: Boolean get() = bookId != null
}

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
