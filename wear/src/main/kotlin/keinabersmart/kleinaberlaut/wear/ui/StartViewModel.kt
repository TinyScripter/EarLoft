package keinabersmart.kleinaberlaut.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.PlaylistRepository
import keinabersmart.kleinaberlaut.core.database.dao.WearDao
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.wear.sync.PhoneConnection
import keinabersmart.kleinaberlaut.wear.sync.PhoneStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Ergebnis von „Auf Handy öffnen“. */
enum class OpenOnPhoneResult { OPENED, FAILED }

/** Eine Auswahl vom Handy; ihre Songs liegen in der Geräte-Playlist [playlistId] (Wear-Konzept 7.2). */
data class SelectionItem(
    val playlistId: Long,
    val kind: WearSelectionKind,
    val title: String,
    val songCount: Int,
)

data class StartUiState(
    val phone: PhoneStatus = PhoneStatus.Checking,
    /** Auswahlen vom Handy in dessen Reihenfolge; dann zeigt die Uhr sie, auch ohne Handy. */
    val selections: List<SelectionItem> = emptyList(),
    val openOnPhone: OpenOnPhoneResult? = null,
) {
    val hasSelections: Boolean get() = selections.isNotEmpty()
}

@HiltViewModel
class StartViewModel @Inject constructor(
    private val phoneConnection: PhoneConnection,
    wearDao: WearDao,
    playlistRepository: PlaylistRepository,
) : ViewModel() {

    private val openOnPhone = MutableStateFlow<OpenOnPhoneResult?>(null)

    private val selections = combine(wearDao.observeSelections(), playlistRepository.playlists()) { selections, playlists ->
        val byId = playlists.associateBy { it.id }
        selections.mapNotNull { selection ->
            val playlist = selection.refId?.let { byId[it] } ?: return@mapNotNull null
            SelectionItem(playlist.id, selection.kind, playlist.name, playlist.songCount)
        }
    }

    val state: StateFlow<StartUiState> = combine(phoneConnection.status, selections, openOnPhone) { phone, selections, opened ->
        StartUiState(phone = phone, selections = selections, openOnPhone = opened)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartUiState())

    fun refresh() = phoneConnection.refresh()

    fun openStoreOnPhone(nodeId: String) {
        viewModelScope.launch {
            val opened = phoneConnection.openStoreOnPhone(nodeId)
            openOnPhone.update { if (opened) OpenOnPhoneResult.OPENED else OpenOnPhoneResult.FAILED }
        }
    }
}
