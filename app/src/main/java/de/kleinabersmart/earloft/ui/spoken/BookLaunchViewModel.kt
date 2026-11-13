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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BookLaunchEvent {
    data object Failed : BookLaunchEvent
    data class ResumedFromServer(val positionMs: Long) : BookLaunchEvent
}

/**
 * Mit „Vor dem Streamen über mobile Daten fragen“: Das Buch würde über mobile Daten gestreamt. [openPlayer]: Nach
 * „Abspielen“ soll die Wiedergabe-Ansicht aufgehen (Start von der Buchseite).
 */
data class MobileStreamQuestion(val bookId: Long, val title: String, val startPositionMs: Long?, val openPlayer: Boolean)

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

    // Zustand statt Ereignis: Die offene Frage übersteht so eine Drehung des Geräts.
    private val _question = MutableStateFlow<MobileStreamQuestion?>(null)

    /** Die offene Rückfrage zu mobilen Daten; beantwortet mit [playConfirmed] oder [dismissQuestion]. */
    val question: StateFlow<MobileStreamQuestion?> = _question.asStateFlow()

    private val _confirmedStarts = MutableSharedFlow<Long>(extraBufferCapacity = 4)

    /** Bücher, die nach der Rückfrage gestartet wurden und deren Wiedergabe-Ansicht aufgehen soll. */
    val confirmedStarts: SharedFlow<Long> = _confirmedStarts.asSharedFlow()

    /** „Abspielen“ in der Rückfrage gilt, bis die App beendet wird oder das Fragen wieder eingeschaltet wird. */
    @Volatile private var mobileConfirmed = false

    init {
        scope.launch {
            settings.askBeforeMobileStreaming.distinctUntilChanged().collect { ask -> if (ask) mobileConfirmed = false }
        }
    }

    fun play(bookId: Long, startPositionMs: Long? = null) {
        scope.launch { start(bookId, startPositionMs) }
    }

    /**
     * Ohne Rückfrage zu mobilen Daten: die Uhr startet das Buch, auf dem Handy sähe niemand die Frage. Nach der Antwort
     * „Abspielen“ gilt die Erlaubnis bis zum Ende der App.
     */
    fun playConfirmed(bookId: Long, startPositionMs: Long? = null, rememberForSession: Boolean = false, openPlayer: Boolean = false) {
        if (rememberForSession) mobileConfirmed = true
        _question.value = null
        scope.launch {
            if (start(bookId, startPositionMs, confirmed = true) && openPlayer) _confirmedStarts.emit(bookId)
        }
    }

    /** „Abbrechen“ in der Rückfrage. */
    fun dismissQuestion() {
        _question.value = null
    }

    /**
     * Wie [play], wartet aber, bis das Buch läuft; `false`, wenn es sich nicht starten ließ oder erst die Rückfrage zu
     * mobilen Daten kommt. Der Start läuft im App-Scope zu Ende, auch wenn der Aufrufer vorher verschwindet.
     */
    suspend fun playAndAwait(bookId: Long): Boolean = scope.async { start(bookId, null, openPlayer = true) }.await()

    private suspend fun start(bookId: Long, startPositionMs: Long?, confirmed: Boolean = false, openPlayer: Boolean = false): Boolean {
        if (!confirmed && !mobileConfirmed && wouldStreamOverMobile(bookId)) {
            val title = books.book(bookId).first()?.book?.title.orEmpty()
            _question.value = MobileStreamQuestion(bookId, title, startPositionMs, openPlayer)
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
    /** Antwort auf [MobileStreamQuestion]: „Abspielen“ bis zum Ende der App, [always] schaltet das Fragen ab. */
    fun playOverMobile(question: MobileStreamQuestion, always: Boolean) {
        if (always) viewModelScope.launch { settings.setAskBeforeMobileStreaming(false) }
        launcher.playConfirmed(question.bookId, question.startPositionMs, rememberForSession = !always, openPlayer = question.openPlayer)
    }
}
