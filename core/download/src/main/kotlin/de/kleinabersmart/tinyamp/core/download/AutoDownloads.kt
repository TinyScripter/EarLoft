package de.kleinabersmart.tinyamp.core.download

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import de.kleinabersmart.tinyamp.core.data.sync.LibraryChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hält die automatischen Downloads aktuell:
 * - **Favoriten:** beim Start und bei jeder Änderung der Favoriten oder der Einstellung.
 * - **Ganze Server:** beim Start, bei Änderung der Einstellung und nach jedem Abgleich (neue Songs).
 */
@Singleton
class AutoDownloads @Inject constructor(
    private val settings: DownloadSettings,
    private val repository: DownloadRepository,
) : LibraryChangeListener {
    private val started = AtomicBoolean(false)
    private val syncsFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Room meldet jede Änderung an `songs` (jeder Abgleichsschritt); abgleichen nur, wenn sich die Favoriten ändern.
            combine(settings.autoDownloadFavorites, repository.favoriteSongIds().distinctUntilChanged()) { enabled, _ -> enabled }
                .collectLatest { repository.reconcileFavoriteDownloads(it) }
        }
        scope.launch {
            combine(settings.autoDownloadServers, syncsFinished.onStart { emit(Unit) }) { sourceIds, _ -> sourceIds }
                .collectLatest { repository.reconcileServerDownloads(it) }
        }
    }

    override suspend fun onSyncFinished() {
        syncsFinished.tryEmit(Unit)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface AutoDownloadBindings {
    @Binds
    @IntoSet
    fun bindAutoDownloads(autoDownloads: AutoDownloads): LibraryChangeListener
}
