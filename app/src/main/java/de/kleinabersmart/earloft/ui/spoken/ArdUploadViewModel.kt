package de.kleinabersmart.earloft.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.spoken.ArdBooks
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.ArdBookUploader
import de.kleinabersmart.earloft.core.download.BookDownloads
import de.kleinabersmart.earloft.core.download.DownloadStart
import de.kleinabersmart.earloft.core.data.spoken.UploadResult
import de.kleinabersmart.earloft.core.data.spoken.UploadTarget
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ablauf „In Audiobookshelf hochladen“ auf der Buchseite eines Buchs aus ARD Sounds (`books_improvements.md` M9). */
sealed interface UploadState {
    data object Idle : UploadState

    /** Ziele werden gesucht. */
    data object Loading : UploadState

    /** Kein Server, bei dem das Konto hochladen darf. */
    data object NoTarget : UploadState

    /** Frage: wohin? Bei einem Ziel nur die Bestätigung. [bytes]: Größe des Pakets, 0 = noch nicht geladen. */
    /** [missingParts]: Teile, die ARD Sounds nicht mehr führt und die nie geladen wurden; sie fehlen auf dem Server. */
    data class Choosing(val targets: List<UploadTarget>, val bytes: Long, val missingParts: Int = 0) : UploadState

    /** Das Buch wird erst geladen, [doneBytes] von [totalBytes] (`null` = unbekannt). */
    data class Downloading(val doneBytes: Long, val totalBytes: Long?) : UploadState

    data class Uploading(val sent: Long, val total: Long) : UploadState

    /** Ende mit einem Hinweis, der bestätigt werden muss. */
    data class Finished(val reason: UploadFinish) : UploadState
}

enum class UploadFinish { OFFLINE, WAITING_FOR_WIFI, DOWNLOAD_FAILED, EXISTS, NO_PERMISSION, SERVER_ERROR, FAILED, NOT_YET_ON_SERVER, KEPT_PLAYING }

/** Ereignis für die Seite: Das Buch liegt jetzt auf dem Server, [bookId] ist das dortige. */
data class UploadedEvent(val bookId: Long)

@HiltViewModel(assistedFactory = ArdUploadViewModel.Factory::class)
class ArdUploadViewModel @AssistedInject constructor(
    @Assisted private val bookId: Long,
    private val uploader: ArdBookUploader,
    private val downloads: BookDownloads,
    private val books: BookRepository,
    private val ardBooks: ArdBooks,
    private val playback: PlaybackConnection,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow<UploadState>(UploadState.Idle)
    val state: StateFlow<UploadState> = _state.asStateFlow()

    private val _server = MutableStateFlow(false)

    /** Es gibt einen Audiobookshelf-Server: Der Knopf erscheint. */
    val hasServer: StateFlow<Boolean> = _server.asStateFlow()

    private val _uploaded = Channel<UploadedEvent>(Channel.CONFLATED)
    val uploaded: Flow<UploadedEvent> = _uploaded.receiveAsFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch { _server.value = uploader.hasServer() }
    }

    /** Sucht die Ziele und fragt nach. */
    fun start() {
        if (job?.isActive == true) return
        _state.value = UploadState.Loading
        job = viewModelScope.launch {
            val targets = uploader.targets()
            _state.value = if (targets.isEmpty()) UploadState.NoTarget else UploadState.Choosing(targets, uploader.uploadBytes(bookId), uploader.missingParts(bookId))
        }
    }

    /** Lädt das Buch (falls nötig) und dann hoch. */
    fun confirm(target: UploadTarget) {
        job?.cancel()
        job = viewModelScope.launch {
            if (!ensureDownloaded()) return@launch
            val result = uploader.upload(bookId, target) { sent, total -> _state.value = UploadState.Uploading(sent, total) }
            when (result) {
                is UploadResult.Done -> finish(result.newBookId)
                UploadResult.NotDownloaded -> _state.value = UploadState.Finished(UploadFinish.DOWNLOAD_FAILED)
                UploadResult.Exists -> _state.value = UploadState.Finished(UploadFinish.EXISTS)
                UploadResult.NoPermission -> _state.value = UploadState.Finished(UploadFinish.NO_PERMISSION)
                UploadResult.ServerError -> _state.value = UploadState.Finished(UploadFinish.SERVER_ERROR)
                UploadResult.Failed -> _state.value = UploadState.Finished(UploadFinish.FAILED)
            }
        }
    }

    /** Bricht ab, auch einen laufenden Upload; ein bereits angestoßener Download läuft weiter. */
    fun dismiss() {
        job?.cancel()
        _state.value = UploadState.Idle
    }

    private suspend fun ensureDownloaded(): Boolean {
        if (books.download(bookId).first().state == TrackDownload.DONE) return true
        _state.value = UploadState.Downloading(0, null)
        when (downloads.download(bookId)) {
            DownloadStart.OFFLINE -> return fail(UploadFinish.OFFLINE)
            DownloadStart.WAITING_FOR_WIFI -> _state.value = UploadState.Finished(UploadFinish.WAITING_FOR_WIFI)
            DownloadStart.STARTED -> Unit
        }
        if (_state.value is UploadState.Finished) return false
        val state = books.download(bookId).first { it.state == TrackDownload.DONE || it.state == TrackDownload.FAILED || it.state == TrackDownload.REMOVED }
        return if (state.state == TrackDownload.DONE) true else fail(UploadFinish.DOWNLOAD_FAILED)
    }

    private fun fail(reason: UploadFinish): Boolean {
        _state.value = UploadState.Finished(reason)
        return false
    }

    /**
     * Das Buch liegt auf dem Server und trägt den Stand. Das Buch aus ARD Sounds geht samt Download weg und die Seite wechselt zum
     * neuen Buch – außer es läuft gerade im Player.
     */
    private suspend fun finish(newBookId: Long?) {
        if (newBookId == null) {
            _state.value = UploadState.Finished(UploadFinish.NOT_YET_ON_SERVER)
            return
        }
        if (playback.state.value.spoken?.bookId == bookId) {
            _state.value = UploadState.Finished(UploadFinish.KEPT_PLAYING)
            return
        }
        _state.value = UploadState.Idle
        _uploaded.send(UploadedEvent(newBookId))
        // Nach dem Wechsel der Seite und unabhängig von ihr: Dieses ViewModel stirbt mit ihr.
        appScope.launch {
            withContext(NonCancellable) {
                downloads.remove(bookId)
                ardBooks.remove(bookId)
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(bookId: Long): ArdUploadViewModel
    }
}
