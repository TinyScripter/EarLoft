package keinabersmart.kleinaberlaut.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.ConnectionResult
import keinabersmart.kleinaberlaut.core.data.repository.ServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddServerUiState(
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val allowHttp: Boolean = false,
    val isBusy: Boolean = false,
    val result: ConnectionResult? = null,
    val saved: Boolean = false,
) {
    val isHttp: Boolean get() = url.trim().startsWith("http://", ignoreCase = true)

    val canSubmit: Boolean
        get() = !isBusy && url.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && (!isHttp || allowHttp)
}

@HiltViewModel
class AddServerViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AddServerUiState())
    val state: StateFlow<AddServerUiState> = _state.asStateFlow()

    fun onNameChange(value: String) = _state.update { it.copy(name = value) }
    fun onUrlChange(value: String) = _state.update { it.copy(url = value, result = null) }
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, result = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, result = null) }
    fun onAllowHttpChange(value: Boolean) = _state.update { it.copy(allowHttp = value) }

    fun test() = submit { s -> serverRepository.testConnection(s.url, s.username, s.password) }

    fun save() = submit { s ->
        serverRepository.addServer(s.name, s.url, s.username, s.password).also { result ->
            if (result is ConnectionResult.Success) _state.update { it.copy(saved = true) }
        }
    }

    private fun submit(action: suspend (AddServerUiState) -> ConnectionResult) {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isBusy = true, result = null) }
        viewModelScope.launch {
            val result = action(current)
            _state.update { it.copy(isBusy = false, result = result) }
        }
    }
}
