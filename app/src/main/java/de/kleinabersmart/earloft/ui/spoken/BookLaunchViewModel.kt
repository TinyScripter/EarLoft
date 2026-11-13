package de.kleinabersmart.earloft.ui.spoken

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.model.isLocalSource
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BookLaunchEvent {
    data object Failed : BookLaunchEvent
    data class ResumedFromServer(val positionMs: Long) : BookLaunchEvent

    /** Mit „Vor dem Streamen über mobile Daten fragen“: Das Buch würde über mobile Daten gestreamt. */
    data class ConfirmMobile(val bookId: Long, val title: String, val startPositionMs: Long?) : BookLaunchEvent
}

/** Startet Bücher aus der UI; meldet, wenn es nicht klappt oder der Stand eines anderen Geräts übernommen wurde. */
@Singleton
class BookLauncher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playbackConnection: PlaybackConnection,
    private val books: BookRepository,
    private val settings: SpokenSettings,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<BookLaunchEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<BookLaunchEvent> = _events.asSharedFlow()

    /** „Abspielen“ in der Rückfrage gilt, bis die App beendet wird. */
    @Volatile private var mobileConfirmed = false

    fun play(bookId: Long, startPositionMs: Long? = null) {
        scope.launch { start(bookId, startPositionMs) }
    }

    /**
     * Ohne Rückfrage zu mobilen Daten: die Uhr startet das Buch, auf dem Handy sähe niemand die Frage. Nach der Antwort
     * „Abspielen“ gilt die Erlaubnis bis zum Ende der App.
     */
    fun playConfirmed(bookId: Long, startPositionMs: Long? = null, rememberForSession: Boolean = false) {
        if (rememberForSession) mobileConfirmed = true
        scope.launch { start(bookId, startPositionMs, confirmed = true) }
    }

    /**
     * Wie [play], wartet aber, bis das Buch läuft; `false`, wenn es sich nicht starten ließ oder erst die Rückfrage zu
     * mobilen Daten kommt. Der Start läuft im App-Scope zu Ende, auch wenn der Aufrufer vorher verschwindet.
     */
    suspend fun playAndAwait(bookId: Long): Boolean = scope.async { start(bookId, null) }.await()

    private suspend fun start(bookId: Long, startPositionMs: Long?, confirmed: Boolean = false): Boolean {
        if (!confirmed && !mobileConfirmed && wouldStreamOverMobile(bookId)) {
            val title = books.book(bookId).first()?.book?.title.orEmpty()
            _events.emit(BookLaunchEvent.ConfirmMobile(bookId, title, startPositionMs))
            return false
        }
        val prepared = playbackConnection.playBook(bookId, startPositionMs)
        when {
            prepared == null -> _events.emit(BookLaunchEvent.Failed)
            prepared.resumedFromServer && startPositionMs == null ->
                _events.emit(BookLaunchEvent.ResumedFromServer(prepared.startPositionMs))
        }
        return prepared != null
    }

    /** Fragen ist eingeschaltet, das Netz ist volumenbegrenzt, und nicht alle Dateien liegen auf dem Gerät. */
    private suspend fun wouldStreamOverMobile(bookId: Long): Boolean {
        if (!settings.askBeforeMobileStreaming.first()) return false
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
        val book = books.book(bookId).first()?.book ?: return false
        if (isLocalSource(book.serverId)) return false
        val files = books.trackFiles(bookId)
        return files.isEmpty() || files.any { it.localPath == null }
    }
}

@HiltViewModel
class BookLaunchViewModel @Inject constructor(val launcher: BookLauncher, private val settings: SpokenSettings) : ViewModel() {
    /** Antwort auf [BookLaunchEvent.ConfirmMobile]: „Abspielen“ bis zum Ende der App, [always] schaltet das Fragen ab. */
    fun playOverMobile(request: BookLaunchEvent.ConfirmMobile, always: Boolean) {
        if (always) viewModelScope.launch { settings.setAskBeforeMobileStreaming(false) }
        launcher.playConfirmed(request.bookId, request.startPositionMs, rememberForSession = !always)
    }
}
