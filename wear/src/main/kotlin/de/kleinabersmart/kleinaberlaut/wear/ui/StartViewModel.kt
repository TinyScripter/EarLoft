package de.kleinabersmart.kleinaberlaut.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.kleinaberlaut.core.data.podcast.ContinueListening
import de.kleinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import de.kleinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import de.kleinabersmart.kleinaberlaut.wear.download.SelectionItem
import de.kleinabersmart.kleinaberlaut.wear.download.WatchStorageRepository
import de.kleinabersmart.kleinaberlaut.wear.sync.PhoneConnection
import de.kleinabersmart.kleinaberlaut.wear.sync.PhoneRemote
import de.kleinabersmart.kleinaberlaut.wear.sync.PhoneStatus
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
    /** Titel bzw. Folge, die gerade auf der Uhr läuft; `null`, solange nichts geladen ist. */
    val nowPlaying: String? = null,
    /** Titel, der gerade auf dem Handy läuft (Fernsteuerung). */
    val phoneNowPlaying: String? = null,
    /** Es liegen Folgen vom Handy auf der Uhr (Wear-Konzept 16.1). */
    val hasEpisodes: Boolean = false,
    /** Es liegen offene Hörbücher vom Handy auf der Uhr (Wear-Konzept 16.2). */
    val hasBooks: Boolean = false,
) {
    /** Die Fernsteuerung gibt es, solange ein Handy mit der App verbunden ist. */
    val phoneConnected: Boolean get() = phone is PhoneStatus.Connected

    /** Musik ist ausgewählt, auch wenn sie noch lädt. */
    val hasMusic: Boolean get() = selections.isNotEmpty()

    val hasContent: Boolean get() = hasMusic || hasEpisodes || hasBooks
}

@HiltViewModel
class StartViewModel @Inject constructor(
    private val phoneConnection: PhoneConnection,
    storage: WatchStorageRepository,
    remote: PhoneRemote,
    playback: PlaybackConnection,
    continueListening: ContinueListening,
    books: BookRepository,
) : ViewModel() {

    private val openOnPhone = MutableStateFlow<OpenOnPhoneResult?>(null)

    /** Was gerade auf dem Handy läuft und ob Folgen bzw. Bücher auf der Uhr liegen; zusammengefasst, damit `combine` typsicher bleibt. */
    private val phoneAndSpoken = combine(remote.state, continueListening.episodes, books.books()) { phonePlayer, episodes, allBooks ->
        Triple(phonePlayer?.takeIf { it.hasMedia }?.title, episodes.isNotEmpty(), allBooks.any { it.progress?.finished != true })
    }

    val state: StateFlow<StartUiState> = combine(
        phoneConnection.status,
        storage.selections(),
        openOnPhone,
        playback.state,
        phoneAndSpoken,
    ) { phone, selections, opened, player, (phoneTitle, hasEpisodes, hasBooks) ->
        StartUiState(
            phone = phone,
            selections = selections,
            openOnPhone = opened,
            nowPlaying = player.current?.title ?: player.spoken?.title,
            phoneNowPlaying = phoneTitle,
            hasEpisodes = hasEpisodes,
            hasBooks = hasBooks,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartUiState())

    fun refresh() = phoneConnection.refresh()

    fun openStoreOnPhone(nodeId: String) {
        viewModelScope.launch {
            val opened = phoneConnection.openStoreOnPhone(nodeId)
            openOnPhone.update { if (opened) OpenOnPhoneResult.OPENED else OpenOnPhoneResult.FAILED }
        }
    }
}
