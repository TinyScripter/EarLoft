package de.kleinabersmart.earloft.core.playback

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Short summary of the playback state for surfaces outside the app (e.g. widget).
 * Either a song ([songId]) or an audiobook ([bookId]); for books, [artist] is the author.
 */
data class NowPlaying(
    val songId: Long?,
    val title: String,
    val artist: String,
    val albumId: Long?,
    val isPlaying: Boolean,
    val bookId: Long? = null,
    /** With [bookId]: a podcast episode instead of a book. */
    val isEpisode: Boolean = false,
    /** With [bookId]: the current chapter; `null` without chapters. */
    val chapter: String? = null,
) {
    val isBook: Boolean get() = bookId != null
}

/**
 * Called by the [PlaybackService] on title or status changes – even if the app UI
 * is not running (e.g. started via headphone button). Bind implementations via `@IntoSet`.
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
