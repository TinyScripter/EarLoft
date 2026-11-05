package de.kleinabersmart.tinyamp.core.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import de.kleinabersmart.tinyamp.core.data.sync.LibraryChangeListener
import de.kleinabersmart.tinyamp.core.data.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Meldet dem laufenden [PlaybackService], dass Songs aus der Bibliothek verschwunden sein können. */
@Singleton
internal class LibraryRemovals @Inject constructor() {
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val removals: SharedFlow<Unit> = events.asSharedFlow()

    fun notifyRemoved() {
        events.tryEmit(Unit)
    }
}

/**
 * Sind Song-Quellen weg (Server entfernt, Datei auf dem Server gelöscht), nimmt der Service deren
 * Songs aus der Warteschlange, und der Stream-Cache gibt ihren Platz frei.
 */
@OptIn(UnstableApi::class)
internal class PlaybackLibraryCleaner @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val streamCache: SimpleCache,
    private val removals: LibraryRemovals,
) : LibraryChangeListener {

    override suspend fun onSongSourcesRemoved() {
        // Erst die Warteschlange, damit der Player die Cache-Dateien möglichst schon losgelassen hat.
        removals.notifyRemoved()
        val keys = withContext(Dispatchers.IO) { streamCache.keys.toList() }
        val existing = libraryRepository.existingSourceUids(keys)
        val stale = keys.filterNot { it in existing }
        withContext(Dispatchers.IO) {
            stale.forEach { key ->
                runCatching { streamCache.removeResource(key) }
                    .onFailure { Log.w(TAG, "Cache für $key nicht gelöscht", it) }
            }
        }
        if (stale.isNotEmpty()) Log.i(TAG, "Stream-Cache von ${stale.size} entfernten Quellen gelöscht")
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
