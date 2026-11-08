package de.kleinabersmart.earloft.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.wearsync.WearRemoteBookDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteListKind
import de.kleinabersmart.earloft.core.wearsync.WearRemotePlayRequest
import de.kleinabersmart.earloft.wear.download.SpokenDownload
import de.kleinabersmart.earloft.wear.download.WatchStorageRepository
import de.kleinabersmart.earloft.wear.sync.PhoneRemote
import de.kleinabersmart.earloft.wear.sync.PhoneSelections
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Eine Folge bzw. ein Buch des Handys (Wear-Konzept 17): am Handy abspielen oder mit „Download“ auf die Uhr holen. Ob es auf
 * der Uhr ist, erkennt sie an Server bzw. Abo und Kennung ([WearRemoteBookDto.serverId], [WearRemoteBookDto.remoteId]):
 * Das Folgen-Manifest legt genau die gewählten an.
 */
@HiltViewModel(assistedFactory = RemoteSpokenViewModel.Factory::class)
class RemoteSpokenViewModel @AssistedInject constructor(
    @Assisted private val item: WearRemoteBookDto,
    private val remote: PhoneRemote,
    private val phoneSelections: PhoneSelections,
    books: BookRepository,
    storage: WatchStorageRepository,
) : ViewModel() {

    /** Gewünschter Stand nach einem Tipp, bis das Folgen-Manifest ihn bestätigt (oder [PENDING_TIMEOUT_MS] vergeht). */
    private val pending = MutableStateFlow<Boolean?>(null)
    private val failed = MutableStateFlow(false)
    private var pendingTimeout: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val download: Flow<SpokenDownload?> = books.observeIdByRemote(item.serverId, item.remoteId).flatMapLatest { localId ->
        if (localId == null) flowOf(null) else storage.spokenDownload(localId)
    }

    val onWatch: StateFlow<OnWatchState> = combine(
        books.observeIdByRemote(item.serverId, item.remoteId),
        download,
        pending,
        failed,
    ) { localId, loaded, wanted, failedNow ->
        val confirmed = localId != null
        if (wanted == confirmed) pending.value = null
        onWatchState(confirmed, wanted, loaded?.progress, failedNow, downloadFailed = loaded?.failed == true)
    }
        .orOnError(OnWatchState(OnWatchStatus.NOT_ON_WATCH))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnWatchState(OnWatchStatus.NOT_ON_WATCH))

    /** Das Handy spielt das Buch bzw. die Folge an der gemerkten Stelle (wie in der App). @return `false` ohne Handy. */
    suspend fun play(): Boolean = remote.play(WearRemotePlayRequest(WearRemoteListKind.CONTINUE, refId = item.id))

    fun setOnWatch(selected: Boolean) {
        viewModelScope.launch {
            failed.value = false
            pending.value = selected
            if (!phoneSelections.setSpokenSelected(item.id, selected)) {
                pending.value = null
                failed.value = true
                return@launch
            }
            pendingTimeout?.cancel()
            pendingTimeout = launch {
                delay(PENDING_TIMEOUT_MS)
                pending.value = null
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(item: WearRemoteBookDto): RemoteSpokenViewModel
    }

    private companion object {
        /** Das Handy schickt das Folgen-Manifest erst nach 2 s Ruhe; die Uhr legt danach an. */
        const val PENDING_TIMEOUT_MS = 20_000L
    }
}
