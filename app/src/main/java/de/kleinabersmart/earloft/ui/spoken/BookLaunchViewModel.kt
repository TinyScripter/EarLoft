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
 * With "Ask before streaming over mobile data": the book would be streamed over mobile data. [openPlayer]: after
 * "Play" the now-playing view should open (started from the book page).
 */
data class MobileStreamQuestion(val bookId: Long, val title: String, val startPositionMs: Long?, val openPlayer: Boolean)

/** Starts books from the UI; reports when it fails or another device's progress was adopted. */
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

    // State instead of event: this way the open question survives a device rotation.
    private val _question = MutableStateFlow<MobileStreamQuestion?>(null)

    /** The open question about mobile data; answered with [playConfirmed] or [dismissQuestion]. */
    val question: StateFlow<MobileStreamQuestion?> = _question.asStateFlow()

    private val _confirmedStarts = MutableSharedFlow<Long>(extraBufferCapacity = 4)

    /** Books that were started after the question and whose now-playing view should open. */
    val confirmedStarts: SharedFlow<Long> = _confirmedStarts.asSharedFlow()

    /** "Play" in the question applies until the app is closed or asking is turned on again. */
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
     * Without the mobile-data question: the watch starts the book, nobody would see the question on the phone. After the answer
     * "Play" the permission applies until the app ends.
     */
    fun playConfirmed(bookId: Long, startPositionMs: Long? = null, rememberForSession: Boolean = false, openPlayer: Boolean = false) {
        if (rememberForSession) mobileConfirmed = true
        _question.value = null
        scope.launch {
            if (start(bookId, startPositionMs, confirmed = true) && openPlayer) _confirmedStarts.emit(bookId)
        }
    }

    /** "Cancel" in the question. */
    fun dismissQuestion() {
        _question.value = null
    }

    /**
     * Like [play], but waits until the book is playing; `false` if it couldn't be started or the mobile-data question
     * comes first. The start completes in the app scope even if the caller disappears beforehand.
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

    /** Asking is enabled, the network is metered, and not all files are on the device. */
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
    /** Answer to [MobileStreamQuestion]: "Play" until the app ends, [always] turns asking off. */
    fun playOverMobile(question: MobileStreamQuestion, always: Boolean) {
        if (always) viewModelScope.launch { settings.setAskBeforeMobileStreaming(false) }
        launcher.playConfirmed(question.bookId, question.startPositionMs, rememberForSession = !always, openPlayer = question.openPlayer)
    }
}
