package keinabersmart.kleinaberlaut.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ListItem
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.ui.spoken.BookArt
import keinabersmart.kleinaberlaut.ui.spoken.FeedCard
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.ui.components.AlbumListItem
import keinabersmart.kleinaberlaut.ui.components.ArtistListItem
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.PlayControlViewModel
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import keinabersmart.kleinaberlaut.ui.components.SongListItem
import keinabersmart.kleinaberlaut.ui.spoken.BookCard
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

/** Abschnitt mit Überschrift und Kachelreihe für Bücher bzw. Podcast-Folgen; leere Listen entfallen. */
private fun LazyListScope.spokenSection(
    key: String,
    @StringRes title: Int,
    items: List<BookWithProgress>,
    onClick: (Long) -> Unit,
    playControl: PlayControlViewModel,
    playingBookId: Long?,
) {
    if (items.isEmpty()) return
    item(key = key) { SectionHeader(stringResource(title)) }
    item(key = "$key-row") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.book.id }) { item ->
                BookCard(
                    item,
                    onClick = { onClick(item.book.id) },
                    modifier = Modifier.width(140.dp),
                    onPlay = { playControl.playOrPauseBook(item.book.id) },
                    playing = item.book.id == playingBookId,
                )
            }
        }
    }
}

@Composable
fun SearchScreen(
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onBookClick: (Long) -> Unit,
    onFeedClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.results.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val playControl: PlayControlViewModel = hiltViewModel()
    val playingAlbumId by playControl.playingAlbumId.collectAsStateWithLifecycle()
    val playingBookId by playControl.playingBookId.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            placeholder = {
                Text(stringResource(state.scope.hint))
            },
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        val results = state.results
        when {
            state.query.isEmpty() -> Unit
            state.isEmpty -> EmptyHint(stringResource(R.string.search_no_results, state.query))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                // Reihenfolge wie die Bereiche der App: Musik, Podcasts, Bücher; darin jeweils vom Allgemeinen zum Einzelnen.
                if (results.artists.isNotEmpty()) {
                    item(key = "artists") { SectionHeader(stringResource(R.string.library_artists)) }
                    items(results.artists, key = { "artist-${it.id}" }) { artist ->
                        ArtistListItem(artist = artist, onClick = { onArtistClick(artist.id) })
                    }
                }
                if (results.albums.isNotEmpty()) {
                    item(key = "albums") { SectionHeader(stringResource(R.string.library_albums)) }
                    items(results.albums, key = { "album-${it.id}" }) { album ->
                        AlbumListItem(
                            album = album,
                            onClick = { onAlbumClick(album.id) },
                            onPlay = { playControl.playOrPauseAlbum(album.id) },
                            playing = album.id == playingAlbumId,
                        )
                    }
                }
                if (results.songs.isNotEmpty()) {
                    item(key = "songs") { SectionHeader(stringResource(R.string.library_songs)) }
                    itemsIndexed(results.songs, key = { _, song -> "song-${song.id}" }) { index, song ->
                        SongListItem(song = song, onClick = { viewModel.playSongs(index) }, actions = viewModel)
                    }
                }
                if (state.feeds.isNotEmpty()) {
                    item(key = "feeds") { SectionHeader(stringResource(R.string.spoken_search_feeds)) }
                    item(key = "feeds-row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.feeds, key = { it.id }) { feed ->
                                FeedCard(feed, onClick = { onFeedClick(feed.id) }, modifier = Modifier.width(140.dp))
                            }
                        }
                    }
                }
                spokenSection("episodes", R.string.spoken_search_episodes, state.episodes, onBookClick, playControl, playingBookId)
                if (state.series.isNotEmpty()) {
                    item(key = "series") { SectionHeader(stringResource(R.string.spoken_search_series)) }
                    items(state.series, key = { "series-${it.name}" }) { series ->
                        ListItem(
                            headlineContent = { Text(series.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(pluralStringResource(R.plurals.book_count, series.bookCount, series.bookCount)) },
                            leadingContent = { BookArt(series.firstBookId, Modifier.size(48.dp), MaterialTheme.shapes.small) },
                            modifier = Modifier.clickable { onSeriesClick(series.name) },
                        )
                    }
                }
                if (state.authors.isNotEmpty()) {
                    item(key = "authors") { SectionHeader(stringResource(R.string.spoken_search_authors)) }
                    items(state.authors, key = { "author-${it.name}" }) { author ->
                        ListItem(
                            headlineContent = { Text(author.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(pluralStringResource(R.plurals.book_count, author.bookCount, author.bookCount)) },
                            leadingContent = { BookArt(author.firstBookId, Modifier.size(48.dp), MaterialTheme.shapes.small) },
                            modifier = Modifier.clickable { onAuthorClick(author.name) },
                        )
                    }
                }
                spokenSection("books", R.string.spoken_search_books, state.books, onBookClick, playControl, playingBookId)
            }
        }
    }
}
