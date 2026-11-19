package de.kleinabersmart.earloft.ui.playlists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.PlaylistEntry
import de.kleinabersmart.earloft.ui.components.AlbumArt
import de.kleinabersmart.earloft.ui.components.LazyListFastScroller
import de.kleinabersmart.earloft.ui.components.rememberReorderState
import de.kleinabersmart.earloft.ui.components.reorderActions
import de.kleinabersmart.earloft.ui.components.reorderHandle
import de.kleinabersmart.earloft.ui.components.reorderableItem
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Die Einträge einer Playlist zum Umsortieren, mit Ziehgriffen wie die Warteschlange. Sortiert wird nur [entries] (die
 * Arbeitskopie); gespeichert wird erst mit „Fertig“ als Ganzes, damit ein Server nicht jede Zwischenstellung bekommt.
 */
@Composable
fun PlaylistOrderList(entries: SnapshotStateList<PlaylistEntry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(listState)
    val move = { from: Int, to: Int -> entries.add(to, entries.removeAt(from)) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            items(entries, key = { it.entryId }) { entry ->
                val index = entries.indexOf(entry)
                ListItem(
                    headlineContent = { Text(entry.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(entry.song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painterResource(DesignR.drawable.ic_drag_handle),
                                contentDescription = stringResource(R.string.action_reorder_queue_item),
                                modifier = Modifier
                                    .size(40.dp)
                                    .padding(8.dp)
                                    .reorderHandle(
                                        reorder,
                                        entry.entryId,
                                        index = { entries.indexOfFirst { it.entryId == entry.entryId } },
                                        onMove = move,
                                    ),
                            )
                            AlbumArt(
                                albumId = entry.song.albumId,
                                modifier = Modifier.padding(start = 4.dp).size(48.dp),
                                shape = MaterialTheme.shapes.small,
                            )
                        }
                    },
                    modifier = Modifier
                        .reorderableItem(this, reorder, entry.entryId)
                        .reorderActions(index, entries.size, move),
                )
            }
        }
        // Ohne Marke (eigene Reihenfolge); die Ziehgriffe zum Umsortieren liegen links.
        LazyListFastScroller(listState)
    }
}
