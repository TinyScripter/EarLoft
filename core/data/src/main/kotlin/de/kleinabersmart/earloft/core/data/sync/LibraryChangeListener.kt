package de.kleinabersmart.earloft.core.data.sync

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Reacts to library changes that affect other modules (download files,
 * stream cache, current playback). Bind implementations via `@IntoSet`.
 */
interface LibraryChangeListener {
    /**
     * Song sources were deleted (mark-and-sweep or server removed). Downloads disappear
     * from the database via CASCADE, but their files don't.
     */
    suspend fun onSongSourcesRemoved() {}

    /** A sync has finished; it may e.g. have re-enqueued downloads of changed files. */
    suspend fun onSyncFinished() {}
}

@Module
@InstallIn(SingletonComponent::class)
internal interface ChangeListenerBindings {
    @Multibinds
    fun changeListeners(): Set<LibraryChangeListener>
}
