package de.kleinabersmart.earloft.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
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
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.core.data.share.ShareTarget
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.Artist
import de.kleinabersmart.earloft.core.model.OfflineMark
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.core.model.SongOfflineState
import de.kleinabersmart.earloft.core.model.SongOfflineStatus
import de.kleinabersmart.earloft.ui.library.AlbumMenu
import de.kleinabersmart.earloft.ui.playlists.AddToPlaylistDialog
import de.kleinabersmart.earloft.core.designsystem.R as DesignR
import coil3.compose.AsyncImage
import de.kleinabersmart.earloft.core.model.AppUris

/** Aktionen, die auf jeden Song in einer Liste angewendet werden können. */
interface SongActions {
    fun playNext(song: Song)
    fun addToQueue(song: Song)

    fun toggleFavorite(song: Song) = Unit
}

/**
 * Eine Song-Zeile. Mit [trackNumber] wird statt des Covers die Titelnummer gezeigt
 * (Album-Ansicht). Die Zeile zeigt den Download-Zustand (vor der zweiten Zeile) und das Menü „Herunterladen“ bzw.
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status != null) OfflineIndicator(status, Modifier.padding(end = MarkGap))
                    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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
                        ShareMenuItem(ShareTarget.Song(song.id), onDismiss = { menuOpen = false })
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

/**
 * Download-Zustand eines Songs vor der zweiten Zeile: wie [OfflineMarkIcon], beim Laden ein Fortschrittskreis.
 * Lokale und reine Stream-Songs zeigen nichts.
 */
@Composable
private fun OfflineIndicator(offline: SongOfflineStatus, modifier: Modifier = Modifier) {
    when (offline.state) {
        SongOfflineState.DOWNLOADED -> OfflineMarkIcon(OfflineMark.DONE, modifier)
        SongOfflineState.QUEUED, SongOfflineState.DOWNLOADING -> {
            val description = stringResource(R.string.download_state_pending)
            val progressModifier = modifier.padding(1.dp).size(14.dp).semantics { contentDescription = description }
            val progress = offline.progress
            if (progress != null) {
                CircularProgressIndicator(progress = { progress }, modifier = progressModifier, strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(modifier = progressModifier, strokeWidth = 2.dp)
            }
        }
        SongOfflineState.FAILED -> OfflineMarkIcon(OfflineMark.FAILED, modifier)
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

/** Album-Zeile mit der Download-Markierung vor dem Interpreten; Langklick öffnet das [AlbumMenu]. */
@Composable
fun AlbumListItem(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    playing: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    val mark = LocalOfflineController.current?.albumMarkOf(album.id)
    Box(modifier) {
        ListItem(
            headlineContent = { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { MarkedSupportingText(album.artist, mark) },
            leadingContent = {
                AlbumArt(albumId = album.id, modifier = Modifier.size(48.dp), shape = MaterialTheme.shapes.small)
            },
            trailingContent = onPlay?.let { play -> { PlayPauseButton(playing, play) } },
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClickLabel = stringResource(R.string.action_more),
                onLongClick = { menu = true },
            ),
        )
        if (menu) AlbumMenu(album, onDismiss = { menu = false })
    }
}

/**
 * Album-Kachel für Raster und horizontale Listen; mit [onPlay] sitzt ein Play- (bei [playing] Pause-)Knopf unten rechts auf
 * dem Cover, unten links die Download-Markierung ([OfflineBadge]). Langklick öffnet das [AlbumMenu].
 */
@Composable
fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    onPlay: (() -> Unit)? = null,
    playing: Boolean = false,
    contentPadding: Dp = 4.dp,
) {
    var menu by remember { mutableStateOf(false) }
    val mark = LocalOfflineController.current?.albumMarkOf(album.id)
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = stringResource(R.string.action_more),
                onLongClick = { menu = true },
            )
            .padding(contentPadding),
    ) {
        if (menu) AlbumMenu(album, onDismiss = { menu = false })
        Box {
            AlbumArt(albumId = album.id, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            OfflineBadge(mark, Modifier.align(Alignment.BottomStart).padding(6.dp))
            if (onPlay != null) {
                PlayPauseButton(playing, onPlay, Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
        }
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

/** Künstler-Kachel für horizontale Listen: Bild (wie die Autoren der Bücher eckig), Name, Albenzahl. */
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
    ) {
        ArtistAvatar(artistId = artist.id, modifier = Modifier.fillMaxWidth(), iconSize = 48.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.titleSmall,
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

/** Abschnittsüberschrift in Listen, bündig mit dem Text der Gruppe darunter ([GroupedItem]). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(start = GroupMargin + 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** Abstand des Regal-Inhalts zum Rand eines [Shelf]; Reihen darin richten ihr erstes Cover daran aus. */
val ShelfInset = 12.dp

private val ShelfShape = RoundedCornerShape(24.dp)

/**
 * Abschnitt als „Regal“: abgesetzte Fläche mit Überschrift (rechts davon optional [action]) und darunter [content],
 * meist eine horizontale Reihe. Mit [onTitleClick] ist die Überschrift antippbar.
 */
@Composable
fun Shelf(
    title: String,
    modifier: Modifier = Modifier,
    onTitleClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(ShelfShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(bottom = ShelfInset),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 52.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier)
                    .padding(horizontal = ShelfInset + 4.dp, vertical = 12.dp),
            )
            if (action != null) Box(Modifier.padding(end = 4.dp)) { action() }
        }
        content()
    }
}

private val GridTileShape = RoundedCornerShape(16.dp)

/** Innenabstand einer Rasterkachel ([gridTile]). */
val GridTilePadding = 8.dp

/**
 * Rasterkachel: eigene Fläche ([color], meist `surfaceContainer`), damit Cover und Titel als Einheit von den Nachbarn
 * getrennt sind. Vor dem Klick-Modifier setzen, damit die Berührungswelle die ganze Kachel füllt.
 */
fun Modifier.gridTile(color: Color): Modifier = clip(GridTileShape).background(color)

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

/** Ladekreis mittig im verfügbaren Platz. */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Hinweis, wenn ein Inhalt nicht gelesen werden konnte ([LoadState.Failed]). */
@Composable
fun LoadFailedHint(modifier: Modifier = Modifier) {
    EmptyHint(stringResource(R.string.load_failed), modifier)
}

/** Liste mit Ladekreis, Fehler- und Leer-Hinweis; [content] bekommt nur nicht leere Listen. */
@Composable
fun <T> LoadableList(state: LoadState<List<T>>, emptyText: String, content: @Composable (List<T>) -> Unit) {
    LoadableList(state, empty = { EmptyHint(emptyText) }, content)
}

/** Wie oben, aber mit eigenem Inhalt für die leere Liste ([empty], etwa [EmptyAreaHint]). */
@Composable
fun <T> LoadableList(state: LoadState<List<T>>, empty: @Composable () -> Unit, content: @Composable (List<T>) -> Unit) {
    when (state) {
        LoadState.Loading -> LoadingIndicator()
        LoadState.Missing, LoadState.Failed -> LoadFailedHint()
        is LoadState.Ready -> if (state.value.isEmpty()) empty() else content(state.value)
    }
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
    EarLoftTheme {
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
    // Eckig mit runden Ecken wie Cover und Autorenbilder; Gesichter sitzen meist oben, daher oben ausgerichtet.
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(CoverOutlineWidth, coverOutlineColor(), RoundedCornerShape(8.dp)),
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
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
