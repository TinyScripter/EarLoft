package keinabersmart.kleinaberlaut.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.ui.components.AlbumListItem
import keinabersmart.kleinaberlaut.ui.components.ArtistListItem
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import keinabersmart.kleinaberlaut.ui.components.SongListItem
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

@Composable
fun SearchScreen(
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.results.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_nav_search), contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(painterResource(DesignR.drawable.ic_close), contentDescription = stringResource(R.string.action_clear_search))
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        val results = state.results
        when {
            state.query.isEmpty() -> Unit
            results.isEmpty -> EmptyHint(stringResource(R.string.search_no_results, state.query))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (results.artists.isNotEmpty()) {
                    item(key = "artists") { SectionHeader(stringResource(R.string.library_artists)) }
                    items(results.artists, key = { "artist-${it.id}" }) { artist ->
                        ArtistListItem(artist = artist, onClick = { onArtistClick(artist.id) })
                    }
                }
                if (results.albums.isNotEmpty()) {
                    item(key = "albums") { SectionHeader(stringResource(R.string.library_albums)) }
                    items(results.albums, key = { "album-${it.id}" }) { album ->
                        AlbumListItem(album = album, onClick = { onAlbumClick(album.id) })
                    }
                }
                if (results.songs.isNotEmpty()) {
                    item(key = "songs") { SectionHeader(stringResource(R.string.library_songs)) }
                    itemsIndexed(results.songs, key = { _, song -> "song-${song.id}" }) { index, song ->
                        SongListItem(song = song, onClick = { viewModel.playSongs(index) }, actions = viewModel)
                    }
                }
            }
        }
    }
}
