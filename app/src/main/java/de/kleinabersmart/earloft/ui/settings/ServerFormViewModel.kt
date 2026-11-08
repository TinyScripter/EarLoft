package de.kleinabersmart.earloft.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.repository.ConnectionResult
import de.kleinabersmart.earloft.core.data.repository.ServerRepository
import de.kleinabersmart.earloft.core.download.DownloadRepository
import de.kleinabersmart.earloft.core.download.DownloadSettings
import de.kleinabersmart.earloft.core.model.ServerInfo
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerFormUiState(
    /** `true` = vorhandenen Server bearbeiten, sonst neuen anlegen. */
    val isEditing: Boolean = false,
    val isLoading: Boolean = false,
    /** Wählbar nur beim Anlegen. */
    val type: SourceType = SourceType.SUBSONIC,
    val name: String = "",
    val url: String = "",
    val username: String = "",
    /** Beim Bearbeiten: leer = gespeichertes Passwort behalten. */
    val password: String = "",
    /** Beim Bearbeiten: Es gibt ein lesbares gespeichertes Passwort (bei Jellyfin: eine Anmeldung). */
    val hasStoredPassword: Boolean = false,
    val allowHttp: Boolean = false,
    /** Alles von diesem Server automatisch herunterladen. */
    val autoDownload: Boolean = false,
    val isBusy: Boolean = false,
    val result: ConnectionResult? = null,
    /** Gespeichert oder der Server existiert nicht mehr → Formular schließen. */
    val done: Boolean = false,
) {
    val isHttp: Boolean get() = url.trim().startsWith("http://", ignoreCase = true)

    val keepsStoredPassword: Boolean get() = isEditing && hasStoredPassword && password.isEmpty()

    val canSubmit: Boolean
        get() = !isBusy && !isLoading && url.isNotBlank() && username.isNotBlank() &&
            (password.isNotEmpty() || keepsStoredPassword || (!isEditing && type != SourceType.SUBSONIC)) &&
            (!isHttp || allowHttp)
}

/** Formular zum Anlegen ([sourceId] `null`) oder Bearbeiten eines Servers. */
@HiltViewModel(assistedFactory = ServerFormViewModel.Factory::class)
class ServerFormViewModel @AssistedInject constructor(
    private val serverRepository: ServerRepository,
    private val downloadSettings: DownloadSettings,
    private val downloads: DownloadRepository,
    @Assisted private val sourceId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(ServerFormUiState(isEditing = sourceId != null, isLoading = sourceId != null))
    val state: StateFlow<ServerFormUiState> = _state.asStateFlow()

    /** Beim Bearbeiten: die geladenen Angaben, um reine Download-Änderungen ohne Verbindungstest zu speichern. */
    private var loaded: ServerInfo? = null

    init {
        if (sourceId != null) load(sourceId)
    }

    private fun load(sourceId: String) {
        viewModelScope.launch {
            val server = serverRepository.editableServer(sourceId)
            if (server == null) {
                _state.update { it.copy(isLoading = false, done = true) }
                return@launch
            }
            loaded = server.info
            val autoDownload = sourceId in downloadSettings.autoDownloadServers.first()
            _state.update {
                it.copy(
                    isLoading = false,
                    type = server.info.type,
                    name = server.info.name,
                    url = server.info.baseUrl,
                    username = server.info.username.orEmpty(),
                    hasStoredPassword = server.hasPassword,
                    // Die unverschlüsselte Verbindung wurde beim Einrichten schon bestätigt.
                    allowHttp = server.info.baseUrl.startsWith("http://", ignoreCase = true),
                    autoDownload = autoDownload,
                )
            }
        }
    }

    fun onTypeChange(value: SourceType) = _state.update { if (it.isEditing) it else it.copy(type = value, result = null) }
    fun onNameChange(value: String) = _state.update { it.copy(name = value) }
    fun onUrlChange(value: String) = _state.update { it.copy(url = value, result = null) }
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, result = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, result = null) }
    fun onAllowHttpChange(value: Boolean) = _state.update { it.copy(allowHttp = value) }
    fun onAutoDownloadChange(value: Boolean) = _state.update { it.copy(autoDownload = value) }

    /** Bei einem neuen Server wird die Download-Option erst gespeichert, wenn er angelegt ist. */
    fun save() {
        val current = _state.value
        val original = loaded
        // Nur die Download-Option geändert: kein Verbindungstest, kein erneuter Vollabgleich.
        if (sourceId != null && original != null && current.canSubmit && current.onlyLocalOptionsDifferFrom(original)) {
            viewModelScope.launch {
                downloadSettings.setAutoDownloadServer(sourceId, current.autoDownload)
                _state.update { it.copy(done = true) }
            }
            return
        }
        submit { s ->
            val result = if (sourceId == null) {
                serverRepository.addServer(s.type, s.name, s.url, s.username, s.password)
            } else {
                serverRepository.updateServer(sourceId, s.name, s.url, s.username, s.newPassword)
            }
            if (result is ConnectionResult.Success) {
                // Reihenfolge egal: Die Downloads starten bei Änderung der Einstellung und nach jedem Abgleich.
                (sourceId ?: result.sourceId)?.let { downloadSettings.setAutoDownloadServer(it, s.autoDownload) }
                // Mit abgelehnten Zugangsdaten gescheiterte Downloads holt die korrigierte Anmeldung nach.
                sourceId?.let { downloads.retryFailedOf(it) }
                _state.update { it.copy(done = true) }
            }
            result
        }
    }

    private fun ServerFormUiState.onlyLocalOptionsDifferFrom(original: ServerInfo): Boolean =
        name == original.name && url == original.baseUrl && username == original.username.orEmpty() && password.isEmpty()

    private val ServerFormUiState.newPassword: String? get() = password.ifEmpty { null }

    private fun submit(action: suspend (ServerFormUiState) -> ConnectionResult) {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isBusy = true, result = null) }
        viewModelScope.launch {
            val result = action(current)
            _state.update { it.copy(isBusy = false, result = result) }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(sourceId: String?): ServerFormViewModel
    }
}
