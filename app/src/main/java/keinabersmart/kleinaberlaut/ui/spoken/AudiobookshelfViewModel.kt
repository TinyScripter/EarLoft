package keinabersmart.kleinaberlaut.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.spoken.AddServerResult
import keinabersmart.kleinaberlaut.core.data.spoken.AudiobookshelfServers
import keinabersmart.kleinaberlaut.core.data.spoken.SpokenRefresher
import keinabersmart.kleinaberlaut.core.model.SpokenServer
import keinabersmart.kleinaberlaut.ui.LoadState
import keinabersmart.kleinaberlaut.ui.asLoadState
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

    val list: StateFlow<LoadState<List<SpokenServer>>> = servers.servers
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** Server ohne lesbaren API-Key; die Liste zeigt bei ihnen „API-Key neu eingeben“. */
    val withoutKey: StateFlow<Set<String>> = servers.serversWithoutKey
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
