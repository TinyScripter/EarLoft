package de.kleinabersmart.earloft.ui.components

import android.content.Context
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.ui.snackbar.Snackbars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import de.kleinabersmart.earloft.ui.snackbar.SnackbarMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** Favorites of albums and artists (concept 8 "Favorites"); songs have their heart on each song itself. */
@Stable
class CollectionFavoritesController(
    private val albums: State<Set<Long>>,
    private val artists: State<Set<Long>>,
    val toggle: (kind: FavoriteKind, id: Long, label: String?) -> Unit,
    private val pendingSongRemovals: State<Set<Long>> = mutableStateOf(emptySet()),
    /** Removes a song's heart once the "Undo" snackbar expires (review5 8.12). */
    val removeSongFavorite: (songId: Long, title: String) -> Unit = { _, _ -> },
    /** Keeps a song whose removal is still waiting for its snackbar. */
    val keepSongFavorite: (songId: Long) -> Unit = {},
) {
    fun isFavorite(kind: FavoriteKind, id: Long): Boolean = when (kind) {
        FavoriteKind.ALBUM -> id in albums.value
        FavoriteKind.ARTIST -> id in artists.value
        FavoriteKind.SONG -> false
    }

    /** The song's heart is about to go: it already shows as removed. */
    fun isSongRemovalPending(songId: Long): Boolean = songId in pendingSongRemovals.value
}

/** `null` (preview, tests): no hearts on albums and artists. */
val LocalCollectionFavorites = staticCompositionLocalOf<CollectionFavoritesController?> { null }

@HiltViewModel
class CollectionFavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val snackbars: Snackbars,
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {
    private data class PendingKey(val kind: FavoriteKind, val id: Long)

    private class PendingRemoval(val token: Long, val message: SnackbarMessage)

    private var nextToken = 0L

    /**
     * Removed hearts whose "Undo" snackbar is still showing (review5 8.12): the favorite and with it the automatic downloads of an
     * album or artist only go once it expires, so "Undo" doesn't download anything again.
     */
    private val pending = MutableStateFlow<Map<PendingKey, PendingRemoval>>(emptyMap())

    private fun Map<PendingKey, PendingRemoval>.idsOf(kind: FavoriteKind) = keys.filter { it.kind == kind }.mapTo(HashSet()) { it.id }

    val albums: StateFlow<Set<Long>> = combine(favoritesRepository.favoriteAlbumIds(), pending) { ids, removing -> ids - removing.idsOf(FavoriteKind.ALBUM) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val artists: StateFlow<Set<Long>> = combine(favoritesRepository.favoriteArtistIds(), pending) { ids, removing -> ids - removing.idsOf(FavoriteKind.ARTIST) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val pendingSongRemovals: StateFlow<Set<Long>> = pending.map { it.idsOf(FavoriteKind.SONG) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Toggles an album or artist favorite; removing offers Undo in the snackbar (review5 P58). */
    fun toggle(kind: FavoriteKind, id: Long, label: String? = null) {
        if (kind == FavoriteKind.SONG) return
        applicationScope.launch(Dispatchers.Main.immediate) {
            // Tapped again while the removal waits: it simply stays.
            if (keep(PendingKey(kind, id))) return@launch
            val wasFavorite = when (kind) {
                FavoriteKind.ALBUM -> favoritesRepository.isAlbumFavorite(id).first()
                FavoriteKind.ARTIST -> favoritesRepository.isArtistFavorite(id).first()
                FavoriteKind.SONG -> return@launch
            }
            if (wasFavorite && !label.isNullOrBlank()) {
                removeLater(PendingKey(kind, id), label)
            } else {
                favoritesRepository.setCollectionFavorite(kind, id, !wasFavorite)
            }
        }
    }

    fun removeSongFavorite(songId: Long, title: String) = removeLater(PendingKey(FavoriteKind.SONG, songId), title)

    fun keepSongFavorite(songId: Long) {
        keep(PendingKey(FavoriteKind.SONG, songId))
    }

    private fun removeLater(key: PendingKey, label: String) {
        val token = ++nextToken
        val message = SnackbarMessage(
            text = context.getString(R.string.favorite_removed, label),
            actionLabel = context.getString(R.string.action_undo),
            onAction = { cancel(key, token) },
            duration = SnackbarDuration.Long,
            onDismissed = { commit(key, token) },
        )
        pending.update { it + (key to PendingRemoval(token, message)) }
        snackbars.show(message)
    }

    /** Ends a waiting removal of [key] and takes back its snackbar; `false` if none was waiting. */
    private fun keep(key: PendingKey): Boolean {
        var kept: PendingRemoval? = null
        pending.update { map ->
            kept = map[key]
            map - key
        }
        kept?.let { snackbars.withdraw(it.message) }
        return kept != null
    }

    private fun cancel(key: PendingKey, token: Long) {
        pending.update { map -> if (map[key]?.token == token) map - key else map }
    }

    private fun commit(key: PendingKey, token: Long) {
        var commit = false
        pending.update { map ->
            if (map[key]?.token == token) {
                commit = true
                map - key
            } else {
                map
            }
        }
        if (commit) applicationScope.launch { remove(key) }
    }

    private suspend fun remove(key: PendingKey) = when (key.kind) {
        FavoriteKind.SONG -> favoritesRepository.setFavorite(key.id, false)
        else -> favoritesRepository.setCollectionFavorite(key.kind, key.id, false)
    }

    /** Snackbars that never ended (rotation, the screen went away): their removal still applies. */
    override fun onCleared() {
        val remaining = pending.getAndUpdate { emptyMap() }.keys.toList()
        if (remaining.isNotEmpty()) applicationScope.launch { remaining.forEach { remove(it) } }
    }
}

@Composable
fun rememberCollectionFavorites(viewModel: CollectionFavoritesViewModel = hiltViewModel()): CollectionFavoritesController {
    val albums = viewModel.albums.collectAsStateWithLifecycle()
    val artists = viewModel.artists.collectAsStateWithLifecycle()
    val pendingSongs = viewModel.pendingSongRemovals.collectAsStateWithLifecycle()
    return remember(viewModel) {
        CollectionFavoritesController(albums, artists, viewModel::toggle, pendingSongs, viewModel::removeSongFavorite, viewModel::keepSongFavorite)
    }
}

/** Heart for the TopAppBar of the album or artist page; invisible without [LocalCollectionFavorites]. */
@Composable
fun CollectionFavoriteButton(kind: FavoriteKind, id: Long, label: String? = null) {
    val controller = LocalCollectionFavorites.current ?: return
    val favorite = controller.isFavorite(kind, id)
    IconToggleButton(checked = favorite, onCheckedChange = { controller.toggle(kind, id, label) }) {
        Icon(
            painterResource(if (favorite) DesignR.drawable.ic_favorite else DesignR.drawable.ic_favorite_border),
            contentDescription = stringResource(if (favorite) R.string.action_remove_favorite else R.string.action_add_favorite),
        )
    }
}

/** "Add to favorites" or "Remove from favorites" entry for the context menus; invisible without [LocalCollectionFavorites]. */
@Composable
fun CollectionFavoriteMenuItem(kind: FavoriteKind, id: Long, label: String? = null, onDismiss: () -> Unit) {
    val controller = LocalCollectionFavorites.current ?: return
    val favorite = controller.isFavorite(kind, id)
    DropdownMenuItem(
        text = { Text(stringResource(if (favorite) R.string.action_remove_favorite else R.string.action_add_favorite)) },
        leadingIcon = {
            Icon(
                painterResource(if (favorite) DesignR.drawable.ic_favorite else DesignR.drawable.ic_favorite_border),
                contentDescription = null,
            )
        },
        onClick = {
            controller.toggle(kind, id, label)
            onDismiss()
        },
    )
}
