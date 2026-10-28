package keinabersmart.kleinaberlaut.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.wear.sync.PhoneCatalog
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

/** Ein wählbarer Eintrag: die Favoriten ([playlistId] `null`) oder eine Playlist des Handys. */
data class CatalogItem(
    val kind: WearSelectionKind,
    val playlistId: Long?,
    val name: String?,
    val songCount: Int,
    /** Server der Playlist; `null` = Geräte-Playlist des Handys. */
    val serverName: String?,
    val selected: Boolean,
) {
    val key: String get() = kind.key(playlistId)
}

data class AddMusicUiState(
    val phone: PhoneStatus = PhoneStatus.Checking,
    /** `null`, solange das Handy keinen Katalog geschrieben hat. */
    val items: List<CatalogItem>? = null,
) {
    /** Ohne verbundenes Handy lässt sich nichts ändern (Wear-Konzept 5). */
    val canChange: Boolean get() = phone is PhoneStatus.Connected
}

@HiltViewModel
class AddMusicViewModel @Inject constructor(
    private val phoneConnection: PhoneConnection,
    private val phoneCatalog: PhoneCatalog,
) : ViewModel() {

    /** Gewünschter Stand je Eintrag, bis das Handy die Änderung im Katalog bestätigt. */
    private val pending = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    val state: StateFlow<AddMusicUiState> = combine(phoneConnection.status, phoneCatalog.catalog, pending) { phone, catalog, pending ->
        val items = catalog?.let {
            buildList {
                add(CatalogItem(WearSelectionKind.FAVORITES, null, null, it.favoritesCount, null, it.favoritesSelected))
                it.playlists.forEach { playlist ->
                    add(CatalogItem(WearSelectionKind.PLAYLIST, playlist.playlistId, playlist.name, playlist.songCount, playlist.serverName, playlist.selected))
                }
            }.map { item -> pending[item.key]?.let { item.copy(selected = it) } ?: item }
        }
        AddMusicUiState(phone, items)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddMusicUiState())

    init {
        // Ein neuer Katalog ist die Antwort des Handys: Die vorläufigen Werte gelten nicht mehr.
        viewModelScope.launch { phoneCatalog.catalog.collect { pending.value = emptyMap() } }
    }

    fun refresh() = phoneConnection.refresh()

    fun setSelected(item: CatalogItem, selected: Boolean) {
        viewModelScope.launch {
            pending.update { it + (item.key to selected) }
            if (!phoneCatalog.setSelected(item.kind, item.playlistId, selected)) {
                pending.update { it - item.key }
                phoneConnection.refresh()
            }
        }
    }
}
