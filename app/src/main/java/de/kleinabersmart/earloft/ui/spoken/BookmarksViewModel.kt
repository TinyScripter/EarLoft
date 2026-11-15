package de.kleinabersmart.earloft.ui.spoken

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.spoken.Bookmarks
import de.kleinabersmart.earloft.core.model.Bookmark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lesezeichen eines Buchs bzw. einer Folge (Konzept 12.6); Player und Buchseite teilen sich das. Änderungen laufen im
 * App-Scope zu Ende: „Rückgängig“ in der Snackbar kommt oft erst, wenn die Seite schon weg ist (Review D13).
 */
@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val bookmarks: Bookmarks,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {
    fun of(bookId: Long): Flow<List<Bookmark>> = bookmarks.of(bookId)

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
