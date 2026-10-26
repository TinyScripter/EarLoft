package keinabersmart.kleinaberlaut.core.data.sync

import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import keinabersmart.kleinaberlaut.core.datasource.local.MediaStoreChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hält die Bibliothek aktuell: Abgleich beim Start, auf Anforderung (z. B. nach Erteilen der
 * Berechtigung) und gebündelt nach Änderungen im MediaStore.
 */
@Singleton
class LibraryRefresher @Inject constructor(
    private val syncer: LibrarySyncer,
    private val mediaStoreChanges: MediaStoreChanges,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        merge(requests, mediaStoreChanges.observe())
            .onStart { emit(Unit) }
            .debounce(DEBOUNCE_MS)
            .onEach { syncer.syncAll() }
            .launchIn(scope)
    }

    fun refreshNow() {
        requests.tryEmit(Unit)
    }

    private companion object {
        const val DEBOUNCE_MS = 1_500L
    }
}
