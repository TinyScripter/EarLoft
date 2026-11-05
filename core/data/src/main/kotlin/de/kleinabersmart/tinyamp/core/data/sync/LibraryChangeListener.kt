package de.kleinabersmart.tinyamp.core.data.sync

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Reagiert auf Änderungen der Bibliothek, die andere Module betreffen (Download-Dateien,
 * Stream-Cache, laufende Wiedergabe). Implementierungen per `@IntoSet` binden.
 */
interface LibraryChangeListener {
    /**
     * Song-Quellen wurden gelöscht (Mark-and-Sweep oder Server entfernt). Downloads verschwinden
     * dabei per CASCADE aus der Datenbank, ihre Dateien aber nicht.
     */
    suspend fun onSongSourcesRemoved() {}

    /** Ein Abgleich ist durch; er kann z. B. Downloads geänderter Dateien neu eingereiht haben. */
    suspend fun onSyncFinished() {}
}

@Module
@InstallIn(SingletonComponent::class)
internal interface ChangeListenerBindings {
    @Multibinds
    fun changeListeners(): Set<LibraryChangeListener>
}
