package keinabersmart.kleinaberlaut.core.data.sync

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Wird aufgerufen, nachdem Song-Quellen gelöscht wurden (Mark-and-Sweep oder Server entfernt).
 * Downloads verschwinden dabei per CASCADE aus der Datenbank, ihre Dateien aber nicht – darum
 * kümmert sich `:core:download`. Implementierungen per `@IntoSet` binden.
 */
interface LibraryCleanupListener {
    suspend fun onSongSourcesRemoved()
}

@Module
@InstallIn(SingletonComponent::class)
internal interface CleanupBindings {
    @Multibinds
    fun cleanupListeners(): Set<LibraryCleanupListener>
}
