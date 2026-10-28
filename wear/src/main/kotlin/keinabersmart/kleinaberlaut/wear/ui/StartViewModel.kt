package keinabersmart.kleinaberlaut.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.data.repository.PlaylistRepository
import keinabersmart.kleinaberlaut.core.database.dao.DownloadDao
import keinabersmart.kleinaberlaut.core.database.dao.WearDao
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.playback.QueueEntry
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
    /** Geladene Songs der Auswahl; `songCount` sind alle. */
    val downloadedCount: Int = songCount,
)

data class StartUiState(
    val phone: PhoneStatus = PhoneStatus.Checking,
    /** Auswahlen vom Handy in dessen Reihenfolge; dann zeigt die Uhr sie, auch ohne Handy. */
    val selections: List<SelectionItem> = emptyList(),
    val openOnPhone: OpenOnPhoneResult? = null,
    /** Der aktuelle Titel, solange die Warteschlange nicht leer ist. */
    val nowPlaying: QueueEntry? = null,
) {
    val hasSelections: Boolean get() = selections.isNotEmpty()

    /** Ohne geladene Songs gibt es nichts zu mischen. */
    val canShuffleAll: Boolean get() = selections.any { it.downloadedCount > 0 }
}

@HiltViewModel
class StartViewModel @Inject constructor(
    private val phoneConnection: PhoneConnection,
    wearDao: WearDao,
    playlistRepository: PlaylistRepository,
    private val downloadDao: DownloadDao,
    private val libraryRepository: LibraryRepository,
    private val playback: PlaybackConnection,
) : ViewModel() {

    private val openOnPhone = MutableStateFlow<OpenOnPhoneResult?>(null)

    private val selections = combine(
        wearDao.observeSelections(),
        playlistRepository.playlists(),
        wearDao.observeSelectionProgress(),
    ) { selections, playlists, progress ->
        val byId = playlists.associateBy { it.id }
        val progressByKey = progress.associateBy { it.key }
        selections.mapNotNull { selection ->
            val playlist = selection.refId?.let { byId[it] } ?: return@mapNotNull null
            val done = progressByKey[selection.key]?.done ?: 0
            SelectionItem(playlist.id, selection.kind, playlist.name, playlist.songCount, downloadedCount = done)
        }
    }

    val state: StateFlow<StartUiState> = combine(
        phoneConnection.status,
        selections,
        openOnPhone,
        playback.state,
    ) { phone, selections, opened, player ->
        StartUiState(phone = phone, selections = selections, openOnPhone = opened, nowPlaying = player.current)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartUiState())

    fun refresh() = phoneConnection.refresh()

    /** Mischt alle geladenen Songs; [onStarted] wechselt danach zur Wiedergabe-Ansicht. */
    fun shuffleAll(onStarted: () -> Unit) {
        viewModelScope.launch {
            val songs = libraryRepository.songs(downloadDao.getDoneSongIds())
            if (songs.isEmpty()) return@launch
            playback.play(songs, shuffle = true)
            onStarted()
        }
    }

    fun openStoreOnPhone(nodeId: String) {
        viewModelScope.launch {
            val opened = phoneConnection.openStoreOnPhone(nodeId)
            openOnPhone.update { if (opened) OpenOnPhoneResult.OPENED else OpenOnPhoneResult.FAILED }
        }
    }
}
