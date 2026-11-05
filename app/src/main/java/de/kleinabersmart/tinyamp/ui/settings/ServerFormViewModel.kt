package de.kleinabersmart.tinyamp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.tinyamp.core.data.repository.ConnectionResult
import de.kleinabersmart.tinyamp.core.data.repository.ServerRepository
import de.kleinabersmart.tinyamp.core.download.DownloadSettings
import de.kleinabersmart.tinyamp.core.model.ServerInfo
import de.kleinabersmart.tinyamp.core.network.subsonic.SubsonicServer
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
    val name: String = "",
    val url: String = "",
    val username: String = "",
    /** Beim Bearbeiten: leer = gespeichertes Passwort behalten. */
    val password: String = "",
    /** Beim Bearbeiten: Es gibt ein lesbares gespeichertes Passwort. */
    val hasStoredPassword: Boolean = false,
    val allowHttp: Boolean = false,
    /** Alles von diesem Server automatisch herunterladen. */
    val autoDownload: Boolean = false,
    /** Adresse für Cast-Empfänger, leer = die normale Adresse. */
    val castUrl: String = "",
    val isBusy: Boolean = false,
    val result: ConnectionResult? = null,
    /** Gespeichert oder der Server existiert nicht mehr → Formular schließen. */
    val done: Boolean = false,
) {
    val isHttp: Boolean get() = url.trim().startsWith("http://", ignoreCase = true)

    val keepsStoredPassword: Boolean get() = isEditing && hasStoredPassword && password.isEmpty()

    val isCastUrlInvalid: Boolean get() = castUrl.isNotBlank() && SubsonicServer.normalizeCastUrl(castUrl) == null

    val canSubmit: Boolean
        get() = !isBusy && !isLoading && url.isNotBlank() && username.isNotBlank() &&
            (password.isNotEmpty() || keepsStoredPassword) && (!isHttp || allowHttp) && !isCastUrlInvalid
}

/** Formular zum Anlegen ([sourceId] `null`) oder Bearbeiten eines Servers. */
@HiltViewModel(assistedFactory = ServerFormViewModel.Factory::class)
class ServerFormViewModel @AssistedInject constructor(
    private val serverRepository: ServerRepository,
    private val downloadSettings: DownloadSettings,
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
                    name = server.info.name,
                    url = server.info.baseUrl,
                    username = server.info.username.orEmpty(),
                    hasStoredPassword = server.hasPassword,
                    // Die unverschlüsselte Verbindung wurde beim Einrichten schon bestätigt.
                    allowHttp = server.info.baseUrl.startsWith("http://", ignoreCase = true),
                    autoDownload = autoDownload,
                    castUrl = server.info.castBaseUrl.orEmpty(),
                )
            }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value) }
    fun onUrlChange(value: String) = _state.update { it.copy(url = value, result = null) }
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, result = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, result = null) }
    fun onAllowHttpChange(value: Boolean) = _state.update { it.copy(allowHttp = value) }
    fun onAutoDownloadChange(value: Boolean) = _state.update { it.copy(autoDownload = value) }
    fun onCastUrlChange(value: String) = _state.update { it.copy(castUrl = value) }

    fun test() = submit { s ->
        if (sourceId == null) {
            serverRepository.testConnection(s.url, s.username, s.password)
        } else {
            serverRepository.testConnection(sourceId, s.url, s.username, s.newPassword)
        }
    }

    /** Bei einem neuen Server wird die Download-Option erst gespeichert, wenn er angelegt ist. */
    fun save() {
        val current = _state.value
        val original = loaded
        // Nur Download-Option oder Cast-Adresse geändert: kein Verbindungstest, kein erneuter Vollabgleich.
        if (sourceId != null && original != null && current.canSubmit && current.onlyLocalOptionsDifferFrom(original)) {
            viewModelScope.launch {
                downloadSettings.setAutoDownloadServer(sourceId, current.autoDownload)
                serverRepository.setCastUrl(sourceId, current.castUrl)
                _state.update { it.copy(done = true) }
            }
            return
        }
        submit { s ->
            val result = if (sourceId == null) {
                serverRepository.addServer(s.name, s.url, s.username, s.password)
            } else {
                serverRepository.updateServer(sourceId, s.name, s.url, s.username, s.newPassword)
            }
            if (result is ConnectionResult.Success) {
                // Reihenfolge egal: Die Downloads starten bei Änderung der Einstellung und nach jedem Abgleich.
                (sourceId ?: result.sourceId)?.let {
                    downloadSettings.setAutoDownloadServer(it, s.autoDownload)
                    serverRepository.setCastUrl(it, s.castUrl)
                }
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
