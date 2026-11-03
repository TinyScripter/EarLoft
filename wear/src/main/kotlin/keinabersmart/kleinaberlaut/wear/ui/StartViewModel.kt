package keinabersmart.kleinaberlaut.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.playback.QueueEntry
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteListKind
import keinabersmart.kleinaberlaut.core.wearsync.WearRemotePlayRequest
import keinabersmart.kleinaberlaut.wear.download.SelectionItem
import keinabersmart.kleinaberlaut.wear.download.WatchStorageRepository
import keinabersmart.kleinaberlaut.wear.sync.PhoneConnection
import keinabersmart.kleinaberlaut.wear.sync.PhoneRemote
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

data class StartUiState(
    val phone: PhoneStatus = PhoneStatus.Checking,
    /** Auswahlen vom Handy in dessen Reihenfolge; dann zeigt die Uhr sie, auch ohne Handy. */
    val selections: List<SelectionItem> = emptyList(),
    val openOnPhone: OpenOnPhoneResult? = null,
    /** Der aktuelle Titel, solange die Warteschlange nicht leer ist. */
    val nowPlaying: QueueEntry? = null,
    /** Titel, der gerade auf dem Handy läuft (Fernsteuerung). */
    val phoneNowPlaying: String? = null,
) {
    /** Die Geräte-Playlist der Favoriten auf der Uhr; `null`, solange die Favoriten nicht übertragen sind. */
    val favoritesPlaylistId: Long? get() = selections.firstOrNull { it.kind == WearSelectionKind.FAVORITES }?.playlistId

    /** Die Fernsteuerung gibt es, solange ein Handy mit der App verbunden ist. */
    val phoneConnected: Boolean get() = phone is PhoneStatus.Connected

    val hasSelections: Boolean get() = selections.isNotEmpty()

    /** Ohne geladene Songs gibt es nichts zu mischen. */
    val canShuffleAll: Boolean get() = selections.any { it.downloadedCount > 0 }
}

@HiltViewModel
class StartViewModel @Inject constructor(
    private val phoneConnection: PhoneConnection,
    private val storage: WatchStorageRepository,
    private val remote: PhoneRemote,
    private val playback: PlaybackConnection,
) : ViewModel() {

    private val openOnPhone = MutableStateFlow<OpenOnPhoneResult?>(null)

    val state: StateFlow<StartUiState> = combine(
        phoneConnection.status,
        storage.selections(),
        openOnPhone,
        playback.state,
        remote.state,
    ) { phone, selections, opened, player, phonePlayer ->
        StartUiState(
            phone = phone,
            selections = selections,
            openOnPhone = opened,
            nowPlaying = player.current,
            phoneNowPlaying = phonePlayer?.takeIf { it.hasMedia }?.title,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartUiState())

    fun refresh() = phoneConnection.refresh()

    /** Mischt alle geladenen Songs; [onStarted] wechselt danach zur Wiedergabe-Ansicht. */
    fun shuffleAll(onStarted: () -> Unit) {
        viewModelScope.launch {
            val songs = storage.downloadedSongs()
            if (songs.isEmpty()) return@launch
            playback.play(songs, shuffle = true)
            onStarted()
        }
    }

    /** Das Handy mischt seine ganze Bibliothek; [onStarted] wechselt danach zur Wiedergabe-Ansicht des Handys. */
    fun shuffleAllOnPhone(onStarted: () -> Unit) {
        viewModelScope.launch {
            if (remote.play(WearRemotePlayRequest(WearRemoteListKind.ALL, shuffle = true))) onStarted()
        }
    }

    fun openStoreOnPhone(nodeId: String) {
        viewModelScope.launch {
            val opened = phoneConnection.openStoreOnPhone(nodeId)
            openOnPhone.update { if (opened) OpenOnPhoneResult.OPENED else OpenOnPhoneResult.FAILED }
        }
    }
}
