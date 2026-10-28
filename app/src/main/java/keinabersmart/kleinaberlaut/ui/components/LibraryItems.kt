package keinabersmart.kleinaberlaut.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.core.model.Album
import keinabersmart.kleinaberlaut.core.model.Artist
import keinabersmart.kleinaberlaut.core.model.Song
import keinabersmart.kleinaberlaut.core.model.SongOfflineState
import keinabersmart.kleinaberlaut.core.model.SongOfflineStatus
import keinabersmart.kleinaberlaut.ui.playlists.AddToPlaylistDialog
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR
import coil3.compose.AsyncImage
import keinabersmart.kleinaberlaut.core.model.AppUris

/** Aktionen, die auf jeden Song in einer Liste angewendet werden können. */
interface SongActions {
    fun playNext(song: Song)
    fun addToQueue(song: Song)

    fun toggleFavorite(song: Song) = Unit
}

/**
 * Eine Song-Zeile. Mit [trackNumber] wird statt des Covers die Titelnummer gezeigt
 * (Album-Ansicht). Die Zeile zeigt den Download-Zustand und das Menü „Herunterladen“ bzw.
 * „Download entfernen“, sofern ein [LocalOfflineController] bereitsteht; [offline] überschreibt den Zustand (Vorschau). [onRemoveFromPlaylist] ergänzt das Menü in
 * Playlists, [warning] erscheint als zweite Zeile (z. B. „Nicht auf …“).
 */
@Composable
fun SongListItem(
    song: Song,
    onClick: () -> Unit,
    actions: SongActions,
    modifier: Modifier = Modifier,
    trackNumber: Int? = null,
    offline: SongOfflineStatus? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    warning: String? = null,
) {
    val offlineController = LocalOfflineController.current
    val status = offline ?: offlineController?.statusOf(song.id)
    var addToPlaylist by rememberSaveable { mutableStateOf(false) }
    if (addToPlaylist) AddToPlaylistDialog(songIds = listOf(song.id), onDismiss = { addToPlaylist = false })

    ListItem(
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val text = if (trackNumber != null) song.artist else "${song.artist} · ${song.albumTitle}"
            Column {
                Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (warning != null) Text(warning, color = MaterialTheme.colorScheme.error, maxLines = 1)
            }
        },
        leadingContent = {
            if (trackNumber != null) {
                Text(
                    text = if (trackNumber > 0) trackNumber.toString() else "–",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(32.dp),
                )
            } else {
                AlbumArt(albumId = song.albumId, modifier = Modifier.size(48.dp), shape = MaterialTheme.shapes.small)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status != null) OfflineIndicator(status)
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(DesignR.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_play_next)) },
                            leadingIcon = { Icon(painterResource(DesignR.drawable.ic_playlist_play), contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                actions.playNext(song)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_add_to_queue)) },
                            leadingIcon = { Icon(painterResource(DesignR.drawable.ic_playlist_add), contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                actions.addToQueue(song)
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(if (song.isFavorite) R.string.action_remove_favorite else R.string.action_add_favorite))
                            },
                            leadingIcon = {
                                Icon(
                                    painterResource(if (song.isFavorite) DesignR.drawable.ic_favorite else DesignR.drawable.ic_favorite_border),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                actions.toggleFavorite(song)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_add_to_playlist)) },
                            leadingIcon = { Icon(painterResource(DesignR.drawable.ic_queue_music), contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                addToPlaylist = true
                            },
                        )
                        if (onRemoveFromPlaylist != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_remove_from_playlist)) },
                                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_close), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onRemoveFromPlaylist()
                                },
                            )
                        }
                        when (status?.state) {
                            null, SongOfflineState.LOCAL -> Unit
                            SongOfflineState.NONE, SongOfflineState.FAILED -> DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_download)) },
                                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    offlineController?.download?.invoke(song.id)
                                },
                            )
                            SongOfflineState.DOWNLOADED, SongOfflineState.QUEUED, SongOfflineState.DOWNLOADING -> DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_remove_download)) },
                                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    offlineController?.removeDownload?.invoke(song.id)
                                },
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier.clickable(onClick = onClick),
    )
}

/** Kleines Symbol für den Download-Zustand eines Songs; lokale und reine Stream-Songs zeigen nichts. */
@Composable
private fun OfflineIndicator(offline: SongOfflineStatus) {
    val iconModifier = Modifier.size(18.dp)
    when (offline.state) {
        SongOfflineState.DOWNLOADED -> Icon(
            painterResource(DesignR.drawable.ic_download_done),
            contentDescription = stringResource(R.string.download_state_downloaded),
            tint = MaterialTheme.colorScheme.primary,
            modifier = iconModifier,
        )
        SongOfflineState.QUEUED, SongOfflineState.DOWNLOADING -> {
            val description = stringResource(R.string.download_state_pending)
            val progressModifier = iconModifier.semantics { contentDescription = description }
            val progress = offline.progress
            if (progress != null) {
                CircularProgressIndicator(progress = { progress }, modifier = progressModifier, strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(modifier = progressModifier, strokeWidth = 2.dp)
            }
        }
        SongOfflineState.FAILED -> Icon(
            painterResource(DesignR.drawable.ic_error),
            contentDescription = stringResource(R.string.download_state_failed),
            tint = MaterialTheme.colorScheme.error,
            modifier = iconModifier,
        )
        SongOfflineState.LOCAL, SongOfflineState.NONE -> Unit
    }
}

@Composable
fun ArtistListItem(
    artist: Artist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(pluralStringResource(R.plurals.album_count, artist.albumCount, artist.albumCount)) },
        leadingContent = {
            ArtistAvatar(artistId = artist.id, modifier = Modifier.size(48.dp), iconSize = 24.dp)
        },
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
fun AlbumListItem(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            AlbumArt(albumId = album.id, modifier = Modifier.size(48.dp), shape = MaterialTheme.shapes.small)
        },
        modifier = modifier.clickable(onClick = onClick),
    )
}

/** Album-Kachel für Raster und horizontale Listen. */
@Composable
fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
) {
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        AlbumArt(albumId = album.id, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = album.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Künstler-Kachel für horizontale Listen: runder Platzhalter, Name, Albenzahl. */
@Composable
fun ArtistCard(
    artist: Artist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 120.dp,
) {
    Column(
        modifier = modifier
            .width(width)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtistAvatar(artistId = artist.id, modifier = Modifier.fillMaxWidth(), iconSize = 48.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = pluralStringResource(R.plurals.album_count, artist.albumCount, artist.albumCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Abschnittsüberschrift in Listen. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** Hinweis für leere Listen. */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
    )
}

/** `m:ss` bzw. `h:mm:ss`. */
fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

internal val PreviewSong = Song(
    id = 1,
    title = "Ein Lied",
    artist = "Eine Band",
    albumId = 1,
    albumTitle = "Ein Album",
    durationMs = 215_000,
    discNumber = 1,
    trackNumber = 3,
    isFavorite = false,
)

internal val PreviewAlbum = Album(id = 1, title = "Ein Album", artist = "Eine Band", year = 2024, songCount = 12)

internal object NoSongActions : SongActions {
    override fun playNext(song: Song) = Unit
    override fun addToQueue(song: Song) = Unit
}

@Preview(showBackground = true)
@Composable
private fun SongListItemPreview() {
    KleinaberlautTheme {
        Column {
            SongListItem(song = PreviewSong, onClick = {}, actions = NoSongActions)
            SongListItem(song = PreviewSong, onClick = {}, actions = NoSongActions, trackNumber = 3)
            SongListItem(
                song = PreviewSong,
                onClick = {},
                actions = NoSongActions,
                trackNumber = 4,
                offline = SongOfflineStatus(SongOfflineState.DOWNLOADED),
            )
            SongListItem(
                song = PreviewSong,
                onClick = {},
                actions = NoSongActions,
                trackNumber = 5,
                offline = SongOfflineStatus(SongOfflineState.DOWNLOADING, progress = 0.4f),
            )
            ArtistListItem(artist = Artist(id = 1, name = "Eine Band", albumCount = 2), onClick = {})
            AlbumCard(album = PreviewAlbum, onClick = {}, width = 160.dp)
        }
    }
}

/** Runder Interpreten-Platzhalter; mit eingeschalteter Option liegt das Bild aus dem Internet darüber. */
@Composable
fun ArtistAvatar(
    artistId: Long,
    modifier: Modifier = Modifier,
    iconSize: Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.ic_person),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize),
        )
        AsyncImage(
            model = AppUris.artistImage(artistId),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
