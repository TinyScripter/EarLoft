package de.kleinabersmart.earloft.core.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import de.kleinabersmart.earloft.core.data.sync.LibraryChangeListener
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Tells the running [PlaybackService] that songs may have disappeared from the library. */
@Singleton
internal class LibraryRemovals @Inject constructor() {
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val removals: SharedFlow<Unit> = events.asSharedFlow()

    fun notifyRemoved() {
        events.tryEmit(Unit)
    }
}

/**
 * If song sources are gone (server removed, file deleted on the server), the service removes their
 * songs from the queue, and the stream cache frees their space.
 */
@OptIn(UnstableApi::class)
internal class PlaybackLibraryCleaner @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val streamCache: SimpleCache,
    private val removals: LibraryRemovals,
) : LibraryChangeListener {

    override suspend fun onSongSourcesRemoved() {
        // The queue first, so that the player has ideally already released the cache files.
        removals.notifyRemoved()
        val keys = withContext(Dispatchers.IO) { streamCache.keys.toList() }
        val existing = libraryRepository.existingSourceUids(keys)
        val stale = keys.filterNot { it in existing }
        withContext(Dispatchers.IO) {
            stale.forEach { key ->
                runCatching { streamCache.removeResource(key) }
                    .onFailure { Log.w(TAG, "Cache for $key not deleted", it) }
            }
        }
        if (stale.isNotEmpty()) Log.i(TAG, "Deleted stream cache of ${stale.size} removed sources")
    }

    private companion object {
        const val TAG = "PlaybackLibraryCleaner"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface PlaybackCleanupBindings {
    @Binds
    @IntoSet
    fun bindCleaner(cleaner: PlaybackLibraryCleaner): LibraryChangeListener
}
