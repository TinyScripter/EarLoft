package de.kleinabersmart.earloft.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.repository.ConnectionResult
import de.kleinabersmart.earloft.core.data.repository.ServerRepository
import de.kleinabersmart.earloft.core.data.repository.UnencryptedProbe
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
    /** `true` = edit an existing server, otherwise create a new one. */
    val isEditing: Boolean = false,
    val isLoading: Boolean = false,
    /** Selectable only when creating. */
    val type: SourceType = SourceType.SUBSONIC,
    val name: String = "",
    val url: String = "",
    val username: String = "",
    /** When editing: empty = keep the stored password. */
    val password: String = "",
    /** When editing: there is a readable stored password (for Jellyfin: a sign-in). */
    val hasStoredPassword: Boolean = false,
    val allowHttp: Boolean = false,
    /**
     * The address was typed without a scheme, HTTPS failed and the server answers over HTTP (review G63): the form now shows the
     * `http://` address with the checkbox; only after ticking it does the app connect unencrypted.
     */
    val onlyHttp: Boolean = false,
    /** Automatically download everything from this server. */
    val autoDownload: Boolean = false,
    val isBusy: Boolean = false,
    val result: ConnectionResult? = null,
    /** Saved, or the server no longer exists → close the form. */
    val done: Boolean = false,
    /**
     * The fields when the form opened (new: empty, edit: as loaded); `null` while loading. Kept here rather than in the
     * composition, which a rotation or a theme or language change rebuilds with the typed values as the start (review H11).
     */
    val initialFields: List<Any?>? = null,
) {
    val isHttp: Boolean get() = url.trim().startsWith("http://", ignoreCase = true)

    /** Something was typed since the form opened: leaving asks first. */
    val isDirty: Boolean get() = initialFields != null && !done && fields() != initialFields

    internal fun fields(): List<Any?> = listOf(url, username, password, name, allowHttp, autoDownload, type)

    val keepsStoredPassword: Boolean get() = isEditing && hasStoredPassword && password.isEmpty()

    val canSubmit: Boolean
        get() = !isBusy && !isLoading && url.isNotBlank() && username.isNotBlank() &&
            (password.isNotEmpty() || keepsStoredPassword || (!isEditing && type != SourceType.SUBSONIC)) &&
            (!isHttp || allowHttp)
}

/** Form for creating ([sourceId] `null`) or editing a server. */
@HiltViewModel(assistedFactory = ServerFormViewModel.Factory::class)
class ServerFormViewModel @AssistedInject constructor(
    private val serverRepository: ServerRepository,
    private val downloadSettings: DownloadSettings,
    private val downloads: DownloadRepository,
    private val unencryptedProbe: UnencryptedProbe,
    @Assisted private val sourceId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ServerFormUiState(isEditing = sourceId != null, isLoading = sourceId != null).let { if (sourceId == null) it.withInitialFields() else it },
    )
    val state: StateFlow<ServerFormUiState> = _state.asStateFlow()

    /** When editing: the loaded values, to save download-only changes without a connection test. */
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
                    // The unencrypted connection was already confirmed during setup.
                    allowHttp = server.info.baseUrl.startsWith("http://", ignoreCase = true),
                    autoDownload = autoDownload,
                ).withInitialFields()
            }
        }
    }

    fun onTypeChange(value: SourceType) = _state.update { if (it.isEditing) it else it.copy(type = value, result = null) }
    fun onNameChange(value: String) = _state.update { it.copy(name = value) }
    fun onUrlChange(value: String) = _state.update { it.copy(url = value, result = null, onlyHttp = false) }
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, result = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, result = null) }
    fun onAllowHttpChange(value: Boolean) = _state.update { it.copy(allowHttp = value) }
    fun onAutoDownloadChange(value: Boolean) = _state.update { it.copy(autoDownload = value) }

    /** For a new server the download option is only saved once it has been created. */
    fun save() {
        val current = _state.value
        val original = loaded
        // Only the download option changed: no connection test, no new full sync.
        if (sourceId != null && original != null && current.canSubmit && current.onlyLocalOptionsDifferFrom(original)) {
            viewModelScope.launch {
                downloadSettings.setAutoDownloadServer(sourceId, current.autoDownload)
                _state.update { it.copy(done = true) }
            }
            return
        }
        submit { s ->
            val result = if (sourceId == null) {
                // Trimmed: a keyboard often appends a space, the server then reports a wrong password (review G62).
                serverRepository.addServer(s.type, s.name, s.url, s.username.trim(), s.password)
            } else {
                serverRepository.updateServer(sourceId, s.name, s.url, s.username.trim(), s.newPassword)
            }
            if (result is ConnectionResult.Success) {
                // Order doesn't matter: downloads start when the setting changes and after every sync.
                (sourceId ?: result.sourceId)?.let { downloadSettings.setAutoDownloadServer(it, s.autoDownload) }
                // The corrected sign-in retries downloads that failed due to rejected credentials.
                sourceId?.let { downloads.retryFailedOf(it) }
                _state.update { it.copy(done = true) }
            }
            result
        }
    }

    /** "Remove server" in the edit form (moved there from the list row, away from the switch). */
    fun remove() {
        val id = sourceId ?: return
        viewModelScope.launch {
            serverRepository.removeServer(id)
            downloadSettings.setAutoDownloadServer(id, false)
            _state.update { it.copy(done = true) }
        }
    }

    private fun ServerFormUiState.withInitialFields() = copy(initialFields = fields())

    private fun ServerFormUiState.onlyLocalOptionsDifferFrom(original: ServerInfo): Boolean =
        name == original.name && url == original.baseUrl && username == original.username.orEmpty() && password.isEmpty()

    private val ServerFormUiState.newPassword: String? get() = password.ifEmpty { null }

    private fun submit(action: suspend (ServerFormUiState) -> ConnectionResult) {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isBusy = true, result = null) }
        viewModelScope.launch {
            val result = action(current)
            // Typed without "http(s)://": never switch to HTTP silently, but offer it if the server answers there.
            if (result is ConnectionResult.Unreachable && "://" !in current.url && unencryptedProbe.answers(current.url)) {
                _state.update {
                    it.copy(isBusy = false, result = null, url = "http://${current.url.trim()}", allowHttp = false, onlyHttp = true)
                }
                return@launch
            }
            _state.update { it.copy(isBusy = false, result = result) }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(sourceId: String?): ServerFormViewModel
    }
}
