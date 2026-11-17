package de.kleinabersmart.earloft.ui.spoken

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.BookTimeline
import de.kleinabersmart.earloft.core.model.Bookmark
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import javax.inject.Inject
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** Titel eines neuen Lesezeichens: „Kapitelname · 12:34“, ohne Kapitel nur die Zeit. */
internal fun bookmarkTitle(resources: Resources, timeline: BookTimeline?, positionMs: Long): String {
    val time = formatClock(positionMs)
    val chapter = timeline?.chapterAt(positionMs) ?: return time
    val name = chapter.title.ifBlank { resources.getString(R.string.spoken_chapter_n, chapter.index + 1) }
    return "$name · $time"
}

/**
 * Lesezeichen im Player (Sheet bzw. rechter Bereich): oben „Lesezeichen hier setzen“ ([onAdd]), darunter die Liste. Ein
 * Tipp springt hin ([onJump]), Wischen nach links löscht, der Stift ändert Titel und Notiz. „Rückgängig“ steht hier in
 * der Liste: Die Snackbar läge unter dem Sheet.
 */
@Composable
internal fun BookmarkList(
    bookId: Long,
    onAdd: () -> Unit,
    onJump: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookmarksViewModel = hiltViewModel(),
) {
    val bookmarks by remember(bookId) { viewModel.of(bookId) }.collectAsStateWithLifecycle(emptyList())
    var lastDeleted by remember(bookId) { mutableStateOf<Bookmark?>(null) }
    val actions = rememberBookmarkActions(viewModel) { lastDeleted = it }
    LazyColumn(modifier) {
        item(key = "add") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                FilledTonalButton(onClick = {
                    lastDeleted = null
                    onAdd()
                }) {
                    Icon(painterResource(DesignR.drawable.ic_bookmark_add), contentDescription = null)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(stringResource(R.string.spoken_bookmark_add))
                }
            }
        }
        lastDeleted?.let { deleted ->
            item(key = "deleted") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.spoken_bookmark_deleted)) },
                    trailingContent = {
                        TextButton(onClick = {
                            viewModel.restore(deleted)
                            lastDeleted = null
                        }) { Text(stringResource(R.string.spoken_bookmark_undo)) }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        if (bookmarks.isEmpty() && lastDeleted == null) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.spoken_bookmarks_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
        items(bookmarks, key = { it.id }) { bookmark ->
            BookmarkRow(bookmark, onJump = { onJump(bookmark.positionMs) }, actions = actions, transparent = true)
        }
    }
    actions.Dialog()
}

/** Lesezeichen auf der Buch- bzw. Folgenseite unter den Kapiteln; ein Tipp spielt ab dort. */
internal fun LazyListScope.bookmarkItems(bookmarks: List<Bookmark>, actions: BookmarkActions, onJump: (Long) -> Unit) {
    if (bookmarks.isEmpty()) return
    item(key = "bookmarks-header") {
        Text(
            stringResource(R.string.spoken_bookmarks),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        )
    }
    items(bookmarks, key = { "bookmark-${it.id}" }) { bookmark ->
        BookmarkRow(bookmark, onJump = { onJump(bookmark.positionMs) }, actions = actions, transparent = false)
    }
}

/**
 * Bearbeiten (Dialog) und Löschen mit Rückgängig; der Dialog muss einmal je Bildschirm gezeigt werden ([Dialog]). Welches
 * Lesezeichen bearbeitet wird, liegt in [editingState] (gesichert): Drehen schließt den Dialog nicht (Review V25).
 */
@androidx.compose.runtime.Stable
internal class BookmarkActions(
    private val viewModel: BookmarksViewModel,
    private val editingState: MutableState<Bookmark?>,
    private val onDeleted: (Bookmark) -> Unit,
) {
    var editing by editingState

    fun delete(bookmark: Bookmark) {
        viewModel.delete(bookmark)
        onDeleted(bookmark)
    }

    @Composable
    fun Dialog() {
        val bookmark = editing ?: return
        BookmarkEditDialog(
            bookmark = bookmark,
            onSave = { title, note ->
                viewModel.edit(bookmark, title, note)
                editing = null
            },
            onDelete = {
                delete(bookmark)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** [onDeleted]: eigenes „Rückgängig“; ohne bietet es eine Snackbar an. */
@Composable
internal fun rememberBookmarkActions(
    viewModel: BookmarksViewModel = hiltViewModel(),
    onDeleted: ((Bookmark) -> Unit)? = null,
): BookmarkActions {
    val snackbars = LocalSnackbars.current
    val deleted = stringResource(R.string.spoken_bookmark_deleted)
    val undo = stringResource(R.string.spoken_bookmark_undo)
    // Immer das aktuelle: Nach einem Wechsel des Buchs schrieb der gemerkte Aufruf sonst in den Zustand des vorigen, und
    // „Rückgängig“ fehlte (Review D36).
    val currentOnDeleted by rememberUpdatedState(onDeleted)
    val editing = rememberSaveable(stateSaver = BookmarkSaver) { mutableStateOf(null) }
    return remember(viewModel, snackbars, editing) {
        BookmarkActions(viewModel, editing) { bookmark ->
            val ownUndo = currentOnDeleted
            if (ownUndo != null) {
                ownUndo(bookmark)
            } else {
                snackbars.show(deleted, SnackbarDuration.Long, actionLabel = undo, onAction = { viewModel.restore(bookmark) })
            }
        }
    }
}

/** Ein Lesezeichen im gesicherten Zustand (Bearbeiten-Dialog über Drehen und Prozessende); `null` = keines. */
private val BookmarkSaver: Saver<Bookmark?, Any> = listSaver(
    save = { bookmark ->
        if (bookmark == null) emptyList() else listOf(bookmark.id, bookmark.bookId, bookmark.positionMs, bookmark.title, bookmark.note, bookmark.createdAt)
    },
    restore = { values ->
        if (values.size < 6) {
            null
        } else {
            Bookmark(values[0] as Long, values[1] as Long, values[2] as Long, values[3] as String, values[4] as String, values[5] as Long)
        }
    },
)

@Composable
internal fun BookmarkRow(bookmark: Bookmark, onJump: () -> Unit, actions: BookmarkActions, transparent: Boolean) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) actions.delete(bookmark) },
        backgroundContent = {
            // Nur beim Wischen: Im Player ist die Zeile durchsichtig und läge sonst immer auf Rot.
            if (state.dismissDirection != SwipeToDismissBoxValue.EndToStart) return@SwipeToDismissBox
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    painterResource(DesignR.drawable.ic_delete),
                    contentDescription = stringResource(R.string.spoken_bookmark_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        ListItem(
            headlineContent = { Text(bookmark.title.ifBlank { formatClock(bookmark.positionMs) }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = bookmark.note.takeIf { it.isNotBlank() }?.let { note -> { Text(note, maxLines = 3, overflow = TextOverflow.Ellipsis) } },
            leadingContent = { Text(formatClock(bookmark.positionMs), style = MaterialTheme.typography.bodySmall) },
            trailingContent = {
                IconButton(onClick = { actions.editing = bookmark }) {
                    Icon(painterResource(DesignR.drawable.ic_edit), contentDescription = stringResource(R.string.spoken_bookmark_edit))
                }
            },
            colors = if (transparent) ListItemDefaults.colors(containerColor = Color.Transparent) else ListItemDefaults.colors(),
            modifier = Modifier.clickable(onClick = onJump),
        )
    }
}

@Composable
private fun BookmarkEditDialog(
    bookmark: Bookmark,
    onSave: (title: String, note: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(bookmark.id) { mutableStateOf(bookmark.title) }
    var note by rememberSaveable(bookmark.id) { mutableStateOf(bookmark.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.spoken_bookmark_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.spoken_bookmark_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.spoken_bookmark_note)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title.trim(), note.trim()) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.spoken_bookmark_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.spoken_bookmark_delete)) }
        },
    )
}
