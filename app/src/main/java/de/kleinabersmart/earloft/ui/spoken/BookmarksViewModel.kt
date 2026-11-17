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
 * Lesezeichen eines Buchs bzw. einer Folge (Konzept 12.6); Player, Buchseite und die gesammelte Liste teilen sich das.
 * Änderungen laufen im App-Scope zu Ende: „Rückgängig“ in der Snackbar kommt oft erst, wenn die Seite schon weg ist
 * (Review D13).
 */
@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val bookmarks: Bookmarks,
    private val launcher: BookLauncher,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {
    fun of(bookId: Long): Flow<List<Bookmark>> = bookmarks.of(bookId)

    /**
     * Die gesammelten Lesezeichen in Büchern bzw. Folgen je Buch (`byBook`); gruppiert abseits des Main-Threads, eine Ausnahme der
     * Datenbank wird [LoadState.Failed] statt eines Absturzes (Review V24).
     */
    internal fun books(kind: BookKind): Flow<LoadState<List<BookmarkedBook>>> =
        bookmarks.all(kind).map { it.byBook() }.flowOn(Dispatchers.Default).asLoadState()

    /** Ob es in Bücher bzw. Folgen Lesezeichen gibt (Symbol im Kopf der Bibliothek); bei einem Fehler keines. */
    fun any(kind: BookKind): Flow<Boolean> = bookmarks.all(kind).map { it.isNotEmpty() }.distinctUntilChanged().orOnError(false)

    /** Spielt das Buch bzw. die Folge ab dem Lesezeichen (gesammelte Liste). */
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
