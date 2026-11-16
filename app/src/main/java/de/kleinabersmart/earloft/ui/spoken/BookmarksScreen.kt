package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.spoken.BookmarkEntry
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.bookAuthorLine
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.components.LoadableList
import de.kleinabersmart.earloft.ui.components.TwoColumnMinWidth
import de.kleinabersmart.earloft.ui.components.group
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.ui.library.BackButton
import kotlinx.coroutines.flow.map
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Gesammelte Lesezeichen der Bücher bzw. Folgen ([kind]): je Buch eine Gruppe, das Buch mit dem neuesten Lesezeichen zuerst,
 * darin nach der Stelle. Die Kopfzeile der Gruppe öffnet das Buch ([onBookClick]), ein Lesezeichen spielt ab dort; Wischen
 * löscht (mit „Rückgängig“ in der Snackbar), der Stift ändert Titel und Notiz.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    kind: BookKind,
    onBack: () -> Unit,
    onBookClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookmarksViewModel = hiltViewModel(),
) {
    val state by remember(kind) { viewModel.all(kind).map<_, LoadState<List<BookmarkedBook>>> { LoadState.Ready(it.byBook()) } }
        .collectAsStateWithLifecycle(LoadState.Loading)
    val actions = rememberBookmarkActions(viewModel)
    Column(modifier.fillMaxSize().readableWidth(TwoColumnMinWidth)) {
        TopAppBar(title = { Text(stringResource(R.string.spoken_bookmarks)) }, navigationIcon = { BackButton(onBack) })
        LoadableList(state, stringResource(R.string.spoken_bookmarks_all_empty)) { books ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
                books.forEach { book ->
                    group(key = "book-${book.bookId}", title = null as (@Composable () -> String)?) {
                        row("book-${book.bookId}-head") {
                            ListItem(
                                headlineContent = {
                                    Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = book.author.takeIf { it.isNotBlank() }?.let { author ->
                                    { Text(author, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                },
                                leadingContent = { BookArt(book.bookId, Modifier.size(48.dp)) },
                                modifier = Modifier.clickable { onBookClick(book.bookId) },
                            )
                        }
                        book.entries.forEach { entry ->
                            row("bookmark-${entry.bookmark.id}") {
                                BookmarkRow(entry.bookmark, onJump = { viewModel.play(entry.bookmark) }, actions = actions, transparent = false)
                            }
                        }
                    }
                }
            }
        }
    }
    actions.Dialog()
}

/** Symbol im Kopf der Bibliothek zu den gesammelten Lesezeichen; nur, solange es im Bereich welche gibt. */
@Composable
internal fun BookmarksHeaderButton(kind: BookKind, onOpen: () -> Unit, viewModel: BookmarksViewModel = hiltViewModel()) {
    val any by remember(kind) { viewModel.all(kind).map { it.isNotEmpty() } }.collectAsStateWithLifecycle(false)
    if (!any) return
    IconButton(onClick = onOpen) {
        Icon(painterResource(DesignR.drawable.ic_bookmark), contentDescription = stringResource(R.string.spoken_bookmarks))
    }
}

/** Die Lesezeichen eines Buchs auf der gesammelten Liste. */
internal class BookmarkedBook(val bookId: Long, val title: String, val author: String, val entries: List<BookmarkEntry>)

/**
 * Je Buch in der Reihenfolge des neuesten Lesezeichens (die Liste kommt neueste zuerst), darin nach der Stelle. Bei Büchern nur
 * die Autoren (ohne Übersetzer und Sprecher), bei Folgen der Podcast.
 */
internal fun List<BookmarkEntry>.byBook(): List<BookmarkedBook> = groupBy { it.bookmark.bookId }.map { (bookId, entries) ->
    val first = entries.first()
    val author = if (first.kind == BookKind.BOOK) bookAuthorLine(first.bookAuthor) else first.bookAuthor
    BookmarkedBook(bookId, first.bookTitle, author, entries.sortedBy { it.bookmark.positionMs })
}
