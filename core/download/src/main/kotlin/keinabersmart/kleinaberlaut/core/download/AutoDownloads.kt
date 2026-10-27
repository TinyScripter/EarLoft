package keinabersmart.kleinaberlaut.core.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Hält die automatischen Downloads (Favoriten) aktuell: beim Start und bei jeder Änderung der Favoriten oder der Einstellung. */
@Singleton
class AutoDownloads @Inject constructor(
    private val settings: DownloadSettings,
    private val repository: DownloadRepository,
) {
    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(settings.autoDownloadFavorites, repository.favoriteSongIds()) { enabled, _ -> enabled }
                .collectLatest { repository.reconcileFavoriteDownloads(it) }
        }
    }
}
