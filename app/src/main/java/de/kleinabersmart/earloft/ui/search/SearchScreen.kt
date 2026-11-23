package de.kleinabersmart.earloft.ui.search

import de.kleinabersmart.earloft.ui.components.TwoColumnMinWidth
import de.kleinabersmart.earloft.ui.components.readableWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.ui.spoken.BookArt
import de.kleinabersmart.earloft.ui.spoken.SpokenModeViewModel
import de.kleinabersmart.earloft.ui.spoken.searchLabel
import de.kleinabersmart.earloft.ui.spoken.FeedCard
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.ui.components.FastScrollLazyColumn
import de.kleinabersmart.earloft.ui.components.AlbumListItem
import de.kleinabersmart.earloft.ui.components.ArtistListItem
import de.kleinabersmart.earloft.ui.components.GenreListItem
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.PlayControlViewModel
import de.kleinabersmart.earloft.ui.components.SectionHeader
import de.kleinabersmart.earloft.ui.components.Shelf
import de.kleinabersmart.earloft.ui.components.ShelfInset
import de.kleinabersmart.earloft.ui.components.groupedItems
import de.kleinabersmart.earloft.ui.components.groupedItemsIndexed
import de.kleinabersmart.earloft.ui.components.SongListItem
import de.kleinabersmart.earloft.ui.spoken.BookCard
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** Section with a header and a tile row for books or podcast episodes; empty lists are omitted. */
private fun LazyListScope.spokenSection(
    key: String,
    @StringRes title: Int,
    items: List<BookWithProgress>,
    onClick: (Long) -> Unit,
    playControl: PlayControlViewModel,
    playingBookId: Long?,
) {
    if (items.isEmpty()) return
    item(key = key) {
        Shelf(stringResource(title), Modifier.padding(top = 8.dp)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = ShelfInset),
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
}

/**
 * Search in the current area like the search within a single subscription: Back and the input field at the top (focused on open),
 * below it the area and the "Search everywhere" switch, which remembers its state.
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onGenreClick: (String) -> Unit,
    onBookClick: (Long) -> Unit,
    onFeedClick: (String) -> Unit,
    onFeedSettings: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onNarratorClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
    modeViewModel: SpokenModeViewModel = hiltViewModel(),
) {
    // The text lives here and only goes to the ViewModel: via its StateFlow it would come back delayed, and when typing
    // fast the cursor would jump or characters would get lost. This way the initial value also survives process death.
    var query by rememberSaveable { mutableStateOf(viewModel.query.value) }
    val state by viewModel.results.collectAsStateWithLifecycle()
    val mode by modeViewModel.mode.collectAsStateWithLifecycle()
    val searchAll by modeViewModel.searchAll.collectAsStateWithLifecycle()
    val areas by modeViewModel.areas.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val playControl: PlayControlViewModel = hiltViewModel()
    val playingAlbumId by playControl.playingAlbumId.collectAsStateWithLifecycle()
    val playingBookId by playControl.playingBookId.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    // Only on first open; coming back from a hit, the keyboard stays closed.
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }

    // Not across the full width of a tablet, like the other lists.
    Column(modifier.fillMaxSize().readableWidth(TwoColumnMinWidth)) {
        Row(Modifier.statusBarsPadding().padding(end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.onQueryChange(it)
                },
                placeholder = { Text(stringResource(state.scope.hint)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                query = ""
                                viewModel.onQueryChange("")
                            },
                        ) {
                            Icon(painterResource(DesignR.drawable.ic_close), contentDescription = stringResource(R.string.action_clear_search))
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        Row(Modifier.padding(start = 68.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            // With "Everywhere" the area doesn't apply; then only the switch is shown. With only one enabled area
            // there is no "Everywhere".
            val everywhere = searchAll && areas.size > 1
            Text(
                if (everywhere) "" else stringResource(searchLabel(mode)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (areas.size > 1) {
                FilterChip(
                    selected = searchAll,
                    onClick = { modeViewModel.setSearchAll(!searchAll) },
                    label = { Text(stringResource(R.string.search_hint_all)) },
                )
            }
        }

        val results = state.results
        // New results (another query or "Search everywhere") start at the top: the list would otherwise hold on to its first
        // visible hit, and hits added above it (the music after "Search everywhere", review J14) would stay out of sight.
        // The rows get new keys for the same reason. Saved, so coming back from a hit keeps the position.
        val resultsKey = "${state.scope.name}:${state.query}"
        val listState = rememberLazyListState()
        var shownKey by rememberSaveable { mutableStateOf(resultsKey) }
        LaunchedEffect(resultsKey) {
            if (state.query.isNotEmpty() && resultsKey != shownKey) {
                shownKey = resultsKey
                listState.scrollToItem(0)
            }
        }
        when {
            state.query.isEmpty() -> Unit
            state.isEmpty -> EmptyHint(stringResource(R.string.search_no_results, state.query))
            else -> FastScrollLazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                // Order like the app's areas: music, podcasts, books; within each from the general to the specific.
                if (results.artists.isNotEmpty()) {
                    item(key = "artists") { SectionHeader(stringResource(R.string.library_artists)) }
                    groupedItems(results.artists, key = { "artist-${it.id}" }) { artist ->
                        ArtistListItem(artist = artist, onClick = { onArtistClick(artist.id) })
                    }
                }
                if (results.genres.isNotEmpty()) {
                    item(key = "genres") { SectionHeader(stringResource(R.string.library_genres)) }
                    // The name unchanged: the query only merges ASCII spellings, "Électro" and "électro" are two rows.
                    groupedItems(results.genres, key = { "genre-${it.name}" }) { genre ->
                        GenreListItem(genre = genre, onClick = { onGenreClick(genre.name) })
                    }
                }
                if (results.albums.isNotEmpty()) {
                    item(key = "albums") { SectionHeader(stringResource(R.string.library_albums)) }
                    groupedItems(results.albums, key = { "album-${it.id}" }) { album ->
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
                    groupedItemsIndexed(results.songs, key = { _, song -> "song-${song.id}" }) { index, song ->
                        SongListItem(song = song, onClick = { viewModel.playSongs(index) }, actions = viewModel)
                    }
                }
                if (state.feeds.isNotEmpty()) {
                    item(key = "feeds-$resultsKey") {
                        Shelf(stringResource(R.string.spoken_search_feeds), Modifier.padding(top = 8.dp)) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = ShelfInset),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(state.feeds, key = { it.id }) { feed ->
                                    FeedCard(
                                        feed,
                                        onClick = { onFeedClick(feed.id) },
                                        onSettings = { onFeedSettings(feed.id) },
                                        modifier = Modifier.width(140.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                spokenSection("episodes-$resultsKey", R.string.spoken_search_episodes, state.episodes, onBookClick, playControl, playingBookId)
                if (state.series.isNotEmpty()) {
                    item(key = "series") { SectionHeader(stringResource(R.string.spoken_search_series)) }
                    groupedItems(state.series, key = { "series-${it.name}" }) { series ->
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
                    groupedItems(state.authors, key = { "author-${it.name}" }) { author ->
                        ListItem(
                            headlineContent = { Text(author.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(pluralStringResource(R.plurals.book_count, author.bookCount, author.bookCount)) },
                            leadingContent = { BookArt(author.firstBookId, Modifier.size(48.dp), MaterialTheme.shapes.small) },
                            modifier = Modifier.clickable { onAuthorClick(author.name) },
                        )
                    }
                }
                if (state.narrators.isNotEmpty()) {
                    item(key = "narrators") { SectionHeader(stringResource(R.string.spoken_search_narrators)) }
                    groupedItems(state.narrators, key = { "narrator-${it.name}" }) { narrator ->
                        ListItem(
                            headlineContent = { Text(narrator.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(pluralStringResource(R.plurals.book_count, narrator.bookCount, narrator.bookCount)) },
                            leadingContent = { BookArt(narrator.firstBookId, Modifier.size(48.dp), MaterialTheme.shapes.small) },
                            modifier = Modifier.clickable { onNarratorClick(narrator.name) },
                        )
                    }
                }
                spokenSection("books-$resultsKey", R.string.spoken_search_books, state.books, onBookClick, playControl, playingBookId)
            }
        }
    }
}
