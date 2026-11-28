package de.kleinabersmart.earloft.wear.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.wear.download.WatchState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import de.kleinabersmart.earloft.wear.download.WatchStorageRepository
import de.kleinabersmart.earloft.wear.sync.PhoneConnection
import de.kleinabersmart.earloft.wear.sync.PhoneRemote
import de.kleinabersmart.earloft.wear.sync.PhoneStatus
import de.kleinabersmart.earloft.wear.sync.PhoneSyncWorker
import de.kleinabersmart.earloft.wear.sync.RemovalGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Result of "Open on phone". */
enum class OpenOnPhoneResult { OPENED, FAILED }

data class StartUiState(
    val phone: PhoneStatus = PhoneStatus.Checking,
    /**
     * Music is selected on the phone, even if it is still downloading; then the watch shows it, even without the phone. Only
     * this, not the selections with their progress: those change twice a second while downloading (review H47).
     */
    val hasMusic: Boolean = false,
    val openOnPhone: OpenOnPhoneResult? = null,
    /** Track or episode currently playing on the watch; `null` as long as nothing is loaded. */
    val nowPlaying: String? = null,
    /** Track currently playing on the phone (remote control). */
    val phoneNowPlaying: String? = null,
    /** There are episodes from the phone on the watch (Wear concept 16.1). */
    val hasEpisodes: Boolean = false,
    /** There are open audiobooks from the phone on the watch (Wear concept 16.2). */
    val hasBooks: Boolean = false,
    /** Sections enabled on the phone (General → Sections); the others are missing on the watch and on the phone. */
    val areas: List<SpokenMode> = SpokenMode.entries,
    /** The phone removed most of what is on the watch; it stays until confirmed ([RemovalGuard]). */
    val removalHeld: Boolean = false,
) {
    /** The remote control exists as long as a phone with the app is connected. */
    val phoneConnected: Boolean get() = phone is PhoneStatus.Connected

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
    watchState: WatchState,
    private val removalGuard: RemovalGuard,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val openOnPhone = MutableStateFlow<OpenOnPhoneResult?>(null)

    /** What is currently playing on the phone and whether episodes or books are on the watch; combined so that `combine` stays type-safe. */
    // The Room sources are guarded individually (orOnError): if the watch cannot read its content, phone and remote control
    // stay usable instead of the app being terminated.
    private val phoneAndSpoken = combine(
        remote.state,
        continueListening.episodes.orOnError(emptyList()),
        books.books().orOnError(emptyList()),
    ) { phonePlayer, episodes, allBooks ->
        Triple(phonePlayer?.takeIf { it.hasMedia }?.title, episodes.isNotEmpty(), allBooks.any { it.progress?.finished != true })
    }

    val state: StateFlow<StartUiState> = combine(
        phoneConnection.status,
        storage.selections().orOnError(emptyList()).map { it.isNotEmpty() }.distinctUntilChanged(),
        openOnPhone,
        playback.state.map { it.current?.title ?: it.spoken?.title }.distinctUntilChanged(),
        phoneAndSpoken,
    ) { phone, hasMusic, opened, nowPlaying, (phoneTitle, hasEpisodes, hasBooks) ->
        StartUiState(
            phone = phone,
            hasMusic = hasMusic,
            openOnPhone = opened,
            nowPlaying = nowPlaying,
            phoneNowPlaying = phoneTitle,
            hasEpisodes = hasEpisodes,
            hasBooks = hasBooks,
        )
    }.combine(watchState.settings.map { it.areas }.distinctUntilChanged()) { state, areas ->
        // The start screen does not show hidden sections; whatever of them is on the watch stays there (Storage & downloads).
        state.copy(
            hasMusic = state.hasMusic && SpokenMode.MUSIC in areas,
            hasEpisodes = state.hasEpisodes && SpokenMode.PODCASTS in areas,
            hasBooks = state.hasBooks && SpokenMode.BOOKS in areas,
            areas = areas,
        )
    }.combine(removalGuard.held) { state, held -> state.copy(removalHeld = held) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartUiState())

    fun refresh() = phoneConnection.refresh()

    /** Applies the phone's state here too, with what it removes. */
    fun confirmRemoval() {
        removalGuard.confirm()
        PhoneSyncWorker.enqueue(context)
    }

    fun openStoreOnPhone(nodeId: String) {
        viewModelScope.launch {
            val opened = phoneConnection.openStoreOnPhone(nodeId)
            openOnPhone.update { if (opened) OpenOnPhoneResult.OPENED else OpenOnPhoneResult.FAILED }
        }
    }
}
