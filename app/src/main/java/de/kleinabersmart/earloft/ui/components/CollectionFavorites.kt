package de.kleinabersmart.earloft.ui.components

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
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
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.model.FavoriteKind
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** Favoriten von Alben und Künstlern (Konzept 8 „Favoriten“); Songs haben ihr Herz an jedem Song selbst. */
@Stable
class CollectionFavoritesController(
    private val albums: State<Set<Long>>,
    private val artists: State<Set<Long>>,
    val toggle: (kind: FavoriteKind, id: Long) -> Unit,
) {
    fun isFavorite(kind: FavoriteKind, id: Long): Boolean = when (kind) {
        FavoriteKind.ALBUM -> id in albums.value
        FavoriteKind.ARTIST -> id in artists.value
        FavoriteKind.SONG -> false
    }
}

/** `null` (Vorschau, Tests): keine Herzen an Alben und Künstlern. */
val LocalCollectionFavorites = staticCompositionLocalOf<CollectionFavoritesController?> { null }

@HiltViewModel
class CollectionFavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {
    val albums: StateFlow<Set<Long>> =
        favoritesRepository.favoriteAlbumIds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val artists: StateFlow<Set<Long>> =
        favoritesRepository.favoriteArtistIds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggle(kind: FavoriteKind, id: Long) {
        viewModelScope.launch { favoritesRepository.toggleCollectionFavorite(kind, id) }
    }
}

@Composable
fun rememberCollectionFavorites(viewModel: CollectionFavoritesViewModel = hiltViewModel()): CollectionFavoritesController {
    val albums = viewModel.albums.collectAsStateWithLifecycle()
    val artists = viewModel.artists.collectAsStateWithLifecycle()
    return remember(viewModel) { CollectionFavoritesController(albums, artists, viewModel::toggle) }
}

/** Herz für die TopAppBar der Album- bzw. Künstlerseite; ohne [LocalCollectionFavorites] unsichtbar. */
@Composable
fun CollectionFavoriteButton(kind: FavoriteKind, id: Long) {
    val controller = LocalCollectionFavorites.current ?: return
    val favorite = controller.isFavorite(kind, id)
    IconToggleButton(checked = favorite, onCheckedChange = { controller.toggle(kind, id) }) {
        Icon(
            painterResource(if (favorite) DesignR.drawable.ic_favorite else DesignR.drawable.ic_favorite_border),
            contentDescription = stringResource(if (favorite) R.string.action_remove_favorite else R.string.action_add_favorite),
        )
    }
}

/** Eintrag „Zu Favoriten“ bzw. „Aus Favoriten entfernen“ für die Kontextmenüs; ohne [LocalCollectionFavorites] unsichtbar. */
@Composable
fun CollectionFavoriteMenuItem(kind: FavoriteKind, id: Long, onDismiss: () -> Unit) {
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
            controller.toggle(kind, id)
            onDismiss()
        },
    )
}
