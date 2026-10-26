package keinabersmart.kleinaberlaut.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.core.model.AlbumDetails
import keinabersmart.kleinaberlaut.ui.LoadState
import keinabersmart.kleinaberlaut.ui.components.AlbumArt
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.NoSongActions
import keinabersmart.kleinaberlaut.ui.components.PreviewAlbum
import keinabersmart.kleinaberlaut.ui.components.PreviewSong
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import keinabersmart.kleinaberlaut.ui.components.SongActions
import keinabersmart.kleinaberlaut.ui.components.SongListItem
import keinabersmart.kleinaberlaut.ui.components.formatDuration
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

@Composable
fun AlbumScreen(
    albumId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlbumViewModel = hiltViewModel<AlbumViewModel, AlbumViewModel.Factory> { it.create(albumId) },
) {
    val album by viewModel.album.collectAsStateWithLifecycle()
    AlbumContent(
        state = album,
        onBack = onBack,
        onPlay = viewModel::play,
        onShuffle = viewModel::shuffle,
        actions = viewModel,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumContent(
    state: LoadState<AlbumDetails>,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    actions: SongActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {},
            navigationIcon = { BackButton(onBack) },
        )
        when (state) {
            LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            LoadState.Missing -> EmptyHint(stringResource(R.string.item_missing))
            is LoadState.Ready -> AlbumSongs(state.value, onPlay, onShuffle, actions)
        }
    }
}

@Composable
private fun AlbumSongs(
    details: AlbumDetails,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    actions: SongActions,
) {
    val songs = details.songs
    val multiDisc = songs.map { it.discNumber }.distinct().size > 1

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            AlbumHeader(details, onPlay = { onPlay(0) }, onShuffle = onShuffle)
        }
        songs.forEachIndexed { index, song ->
            if (multiDisc && (index == 0 || songs[index - 1].discNumber != song.discNumber)) {
                item(key = "disc-${song.discNumber}") {
                    SectionHeader(stringResource(R.string.disc_number, song.discNumber))
                }
            }
            item(key = song.id) {
                SongListItem(
                    song = song,
                    onClick = { onPlay(index) },
                    actions = actions,
                    trackNumber = song.trackNumber,
                )
            }
        }
    }
}

@Composable
private fun AlbumHeader(
    details: AlbumDetails,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    val album = details.album
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AlbumArt(albumId = album.id, modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large)
        Spacer(Modifier.height(16.dp))
        Text(album.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            album.artist,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        val totalMs = details.songs.sumOf { it.durationMs }
        val meta = listOfNotNull(
            album.year?.toString(),
            pluralStringResource(R.plurals.song_count, details.songs.size, details.songs.size),
            formatDuration(totalMs),
        ).joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPlay) {
                Icon(painterResource(DesignR.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_play))
            }
            FilledTonalButton(onClick = onShuffle) {
                Icon(painterResource(DesignR.drawable.ic_shuffle), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_shuffle))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(painterResource(DesignR.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
    }
}

@Preview(showBackground = true)
@Composable
private fun AlbumContentPreview() {
    KleinaberlautTheme {
        AlbumContent(
            state = LoadState.Ready(
                AlbumDetails(
                    album = PreviewAlbum,
                    songs = List(4) { PreviewSong.copy(id = it.toLong(), trackNumber = it + 1) },
                ),
            ),
            onBack = {},
            onPlay = {},
            onShuffle = {},
            actions = NoSongActions,
        )
    }
}
