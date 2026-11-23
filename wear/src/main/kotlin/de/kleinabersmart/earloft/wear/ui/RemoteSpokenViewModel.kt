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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * An episode or book of the phone (Wear concept 17): play on the phone or bring it to the watch with "Download". Whether it is on
 * the watch is recognized by server or subscription and identifier ([WearRemoteBookDto.serverId], [WearRemoteBookDto.remoteId]):
 * the episode manifest creates exactly the chosen ones.
 */
@HiltViewModel(assistedFactory = RemoteSpokenViewModel.Factory::class)
class RemoteSpokenViewModel @AssistedInject constructor(
    @Assisted private val item: WearRemoteBookDto,
    private val remote: PhoneRemote,
    private val phoneSelections: PhoneSelections,
    books: BookRepository,
    storage: WatchStorageRepository,
) : ViewModel() {

    private val toggle = PendingToggle(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val download: Flow<SpokenDownload?> = books.observeIdByRemote(item.serverId, item.remoteId).flatMapLatest { localId ->
        if (localId == null) flowOf(null) else storage.spokenDownload(localId)
    }

    val onWatch: StateFlow<OnWatchState> = combine(
        books.observeIdByRemote(item.serverId, item.remoteId),
        download,
        toggle.pending,
        toggle.failed,
    ) { localId, loaded, wanted, failedNow ->
        val confirmed = localId != null
        toggle.confirmed(confirmed)
        onWatchState(confirmed, wanted, loaded?.progress, failedNow, downloadFailed = loaded?.failed == true)
    }
        .orOnError(OnWatchState(OnWatchStatus.NOT_ON_WATCH))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnWatchState(OnWatchStatus.NOT_ON_WATCH))

    /** The phone plays the book or episode at the saved position (as in the app). @return `false` without a phone. */
    suspend fun play(): Boolean = remote.play(WearRemotePlayRequest(WearRemoteListKind.CONTINUE, refId = item.id))

    fun setOnWatch(selected: Boolean) = toggle.request(selected) { phoneSelections.setSpokenSelected(item.id, it) }

    @AssistedFactory
    interface Factory {
        fun create(item: WearRemoteBookDto): RemoteSpokenViewModel
    }
}
