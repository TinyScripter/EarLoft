package de.kleinabersmart.earloft.ui.spoken

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.spoken.Bookmarks
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.Bookmark
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.asLoadState
import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bookmarks of a book or episode (concept 12.6); player, book page and the collected list share this.
 * Changes complete in the app scope: "Undo" in the snackbar often comes only when the page is already gone
 * (review D13).
 */
@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val bookmarks: Bookmarks,
    private val launcher: BookLauncher,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {
    fun of(bookId: Long): Flow<List<Bookmark>> = bookmarks.of(bookId)

    /**
     * The collected bookmarks in books or episodes per book (`byBook`); grouped off the main thread, a database
     * exception becomes [LoadState.Failed] instead of a crash (review V24).
     */
    internal fun books(kind: BookKind): Flow<LoadState<List<BookmarkedBook>>> =
        bookmarks.all(kind).map { it.byBook() }.flowOn(Dispatchers.Default).asLoadState()

    /** Whether there are bookmarks in books or episodes (icon in the library header); none on error. */
    fun any(kind: BookKind): Flow<Boolean> = bookmarks.all(kind).map { it.isNotEmpty() }.distinctUntilChanged().orOnError(false)

    /** Plays the book or episode from the bookmark (collected list). */
    fun play(bookmark: Bookmark) {
        launcher.play(bookmark.bookId, bookmark.positionMs)
    }

    fun add(bookId: Long, positionMs: Long, title: String) {
        applicationScope.launch { bookmarks.add(bookId, positionMs, title) }
    }

    fun edit(bookmark: Bookmark, title: String, note: String) {
        applicationScope.launch { bookmarks.edit(bookmark.id, title, note) }
    }

    fun delete(bookmark: Bookmark) {
        applicationScope.launch { bookmarks.delete(bookmark.id) }
    }

    fun restore(bookmark: Bookmark) {
        applicationScope.launch { bookmarks.restore(bookmark) }
    }
}
