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
 * Keeps the automatic downloads up to date:
 * - **Favorites** (songs, albums, optionally artists): at startup and on every change of the favorites or the settings.
 * - **Whole servers:** at startup, when the setting changes and after every sync (new songs).
 * - **Newest and most played albums:** at startup, when the settings change, after every sync (including the songs of a
 *   new album that only arrived after it first appeared) and as soon as the selection of albums changes.
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
            // Room reports every change to `songs` (every sync step); only reconcile when the wanted songs change.
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
