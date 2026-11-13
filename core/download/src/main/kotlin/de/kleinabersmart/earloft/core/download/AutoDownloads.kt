package de.kleinabersmart.earloft.core.download

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import de.kleinabersmart.earloft.core.data.sync.LibraryChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hält die automatischen Downloads aktuell:
 * - **Favoriten** (Songs, Alben, auf Wunsch Künstler): beim Start und bei jeder Änderung der Favoriten oder der Einstellungen.
 * - **Ganze Server:** beim Start, bei Änderung der Einstellung und nach jedem Abgleich (neue Songs).
 * - **Neueste und meistgehörte Alben:** beim Start, bei Änderung der Einstellungen, nach jedem Abgleich (auch die Songs eines
 *   neuen Albums, die erst nach seinem ersten Auftauchen ankamen) und sobald sich die Auswahl der Alben ändert.
 */
@Singleton
class AutoDownloads @Inject constructor(
    private val settings: DownloadSettings,
    private val repository: DownloadRepository,
) : LibraryChangeListener {
    private val started = AtomicBoolean(false)
    private val syncsFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Room meldet jede Änderung an `songs` (jeder Abgleichsschritt); abgleichen nur, wenn sich die gewollten Songs ändern.
            combine(settings.autoDownloadFavorites, settings.autoDownloadFavoriteArtists) { enabled, artists -> enabled to artists }
                .flatMapLatest { (enabled, artists) ->
                    repository.favoriteSongIds(artists).distinctUntilChanged().map { enabled to artists }
                }
                .collectLatest { (enabled, artists) -> repository.reconcileFavoriteDownloads(enabled, artists) }
        }
        scope.launch {
            combine(settings.autoDownloadServers, syncsFinished.onStart { emit(Unit) }) { sourceIds, _ -> sourceIds }
                .collectLatest { repository.reconcileServerDownloads(it) }
        }
        scope.launch {
            combine(
                settings.autoDownloadNewestAlbums,
                settings.autoDownloadMostPlayedAlbums,
                settings.autoDownloadFavorites,
                settings.autoDownloadFavoriteArtists,
                syncsFinished.onStart { emit(Unit) },
            ) { newest, mostPlayed, keepFavorites, favoriteArtists, _ -> AlbumRule(newest, mostPlayed, keepFavorites, favoriteArtists) }
                .flatMapLatest { rule ->
                    repository.autoAlbumIds(rule.newest, rule.mostPlayed).distinctUntilChanged().map { it to rule }
                }
                .collectLatest { (albumIds, rule) ->
                    repository.reconcileAlbumDownloads(albumIds, rule.keepFavorites, rule.favoriteArtists)
                }
        }
    }

    override suspend fun onSyncFinished() {
        syncsFinished.tryEmit(Unit)
    }

    private data class AlbumRule(val newest: Int, val mostPlayed: Int, val keepFavorites: Boolean, val favoriteArtists: Boolean)
}

@Module
@InstallIn(SingletonComponent::class)
internal interface AutoDownloadBindings {
    @Binds
    @IntoSet
    fun bindAutoDownloads(autoDownloads: AutoDownloads): LibraryChangeListener
}
