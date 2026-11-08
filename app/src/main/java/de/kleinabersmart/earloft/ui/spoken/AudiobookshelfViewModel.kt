package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.spoken.AddServerResult
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.SpokenRefresher
import de.kleinabersmart.earloft.core.model.SpokenServer
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.asLoadState
import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ConnectState {
    data object Idle : ConnectState
    data object Connecting : ConnectState
    data class Failed(val message: Int) : ConnectState
    data object Connected : ConnectState
}

@HiltViewModel
class AudiobookshelfViewModel @Inject constructor(
    private val servers: AudiobookshelfServers,
    private val refresher: SpokenRefresher,
) : ViewModel() {

    /** Eingegebener API-Schlüssel: nur hier im Speicher, nicht im gespeicherten Zustand der Activity (wie das Passwort im Musik-Formular). */
    var keyInput by mutableStateOf("")

    val list: StateFlow<LoadState<List<SpokenServer>>> = servers.servers
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** Server ohne lesbaren API-Key; die Liste zeigt bei ihnen „API-Key neu eingeben“. */
    val withoutKey: StateFlow<Set<String>> = servers.serversWithoutKey
        .orOnError(emptySet())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _connect = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val connect: StateFlow<ConnectState> = _connect.asStateFlow()

    /** [serverId] `null` = neuer Server, sonst werden die Angaben dieses Servers geändert. */
    fun connect(serverId: String?, name: String, url: String, apiKey: String) {
        if (_connect.value == ConnectState.Connecting) return
        _connect.value = ConnectState.Connecting
        viewModelScope.launch {
            val result = if (serverId == null) servers.add(name, url, apiKey) else servers.update(serverId, name, url, apiKey)
            _connect.value = when (result) {
                is AddServerResult.Added -> {
                    refresher.refreshAll()
                    ConnectState.Connected
                }
                AddServerResult.InvalidUrl -> ConnectState.Failed(R.string.spoken_abs_error_url)
                AddServerResult.Unreachable -> ConnectState.Failed(R.string.spoken_abs_error_unreachable)
                AddServerResult.AuthFailed -> ConnectState.Failed(R.string.spoken_abs_error_auth)
                AddServerResult.NoBookLibrary -> ConnectState.Failed(R.string.spoken_abs_error_library)
            }
        }
    }

    fun syncNow() = refresher.refreshAll()

    suspend fun editable(serverId: String) = servers.editable(serverId)

    fun setEnabled(serverId: String, enabled: Boolean) {
        viewModelScope.launch {
            servers.setEnabled(serverId, enabled)
            if (enabled) refresher.refreshAll()
        }
    }

    fun remove(serverId: String) {
        viewModelScope.launch { servers.remove(serverId) }
    }
}
