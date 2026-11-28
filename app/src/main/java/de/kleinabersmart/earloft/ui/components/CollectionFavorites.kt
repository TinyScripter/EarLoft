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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** Favorites of albums and artists (concept 8 "Favorites"); songs have their heart on each song itself. */
@Stable
class CollectionFavoritesController(
    private val albums: State<Set<Long>>,
    private val artists: State<Set<Long>>,
    val toggle: (kind: FavoriteKind, id: Long, label: String?) -> Unit,
    /** Restores a song's favorite flag in the application scope so Undo survives leaving the screen (review5 P58). */
    val restoreSongFavorite: (songId: Long) -> Unit = {},
) {
    fun isFavorite(kind: FavoriteKind, id: Long): Boolean = when (kind) {
        FavoriteKind.ALBUM -> id in albums.value
        FavoriteKind.ARTIST -> id in artists.value
        FavoriteKind.SONG -> false
    }
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
    val albums: StateFlow<Set<Long>> =
        favoritesRepository.favoriteAlbumIds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val artists: StateFlow<Set<Long>> =
        favoritesRepository.favoriteArtistIds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Toggles an album or artist favorite; removing offers Undo in the snackbar (review5 P58). */
    fun toggle(kind: FavoriteKind, id: Long, label: String? = null) {
        if (kind == FavoriteKind.SONG) return
        applicationScope.launch(Dispatchers.Main.immediate) {
            val wasFavorite = when (kind) {
                FavoriteKind.ALBUM -> favoritesRepository.isAlbumFavorite(id).first()
                FavoriteKind.ARTIST -> favoritesRepository.isArtistFavorite(id).first()
                FavoriteKind.SONG -> return@launch
            }
            favoritesRepository.setCollectionFavorite(kind, id, !wasFavorite)
            if (wasFavorite && !label.isNullOrBlank()) {
                snackbars.show(
                    context.getString(R.string.favorite_removed, label),
                    SnackbarDuration.Long,
                    context.getString(R.string.action_undo),
                ) {
                    applicationScope.launch(Dispatchers.Main.immediate) {
                        favoritesRepository.setCollectionFavorite(kind, id, true)
                    }
                }
            }
        }
    }

    fun restoreSongFavorite(songId: Long) {
        applicationScope.launch(Dispatchers.Main.immediate) {
            favoritesRepository.setFavorite(songId, true)
        }
    }
}

@Composable
fun rememberCollectionFavorites(viewModel: CollectionFavoritesViewModel = hiltViewModel()): CollectionFavoritesController {
    val albums = viewModel.albums.collectAsStateWithLifecycle()
    val artists = viewModel.artists.collectAsStateWithLifecycle()
    return remember(viewModel) { CollectionFavoritesController(albums, artists, viewModel::toggle, viewModel::restoreSongFavorite) }
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
