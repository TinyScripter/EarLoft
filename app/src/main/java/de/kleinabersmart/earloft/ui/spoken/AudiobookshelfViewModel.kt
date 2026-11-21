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
import de.kleinabersmart.earloft.core.data.spoken.BeginOpenIdResult
import de.kleinabersmart.earloft.core.data.spoken.SpokenRefresher
import de.kleinabersmart.earloft.core.model.SpokenServer
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsLoginOptions
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsOpenIdLogin
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.asLoadState
import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ConnectState {
    data object Idle : ConnectState
    data object Connecting : ConnectState
    /** [arg] fills `%1$s` in [message]. */
    data class Failed(val message: Int, val arg: String? = null) : ConnectState
    data object Connected : ConnectState
}

/**
 * Redirects from the browser without Auth Tab (`earloft://oauth`, concept 12.11); [OpenIdRedirectActivity] passes them on
 * to here, the form waits for them.
 */
object OpenIdRedirects {
    private val _received = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val received: SharedFlow<String> = _received.asSharedFlow()

    fun deliver(uri: String) {
        _received.tryEmit(uri)
    }
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class AudiobookshelfViewModel @Inject constructor(
    private val servers: AudiobookshelfServers,
    private val refresher: SpokenRefresher,
) : ViewModel() {

    /** Entered API key: only in memory here, not in the activity's saved state (like the password in the music form). */
    var keyInput by mutableStateOf("")

    val list: StateFlow<LoadState<List<SpokenServer>>> = servers.servers
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** Servers without a readable API key; the list shows "Re-enter API key" for them. */
    val withoutKey: StateFlow<Set<String>> = servers.serversWithoutKey
        .orOnError(emptySet())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _connect = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val connect: StateFlow<ConnectState> = _connect.asStateFlow()

    private val urlInput = MutableStateFlow("")

    /** What the server offers at the entered address; `null` as long as unknown or not Audiobookshelf. */
    val loginOptions: StateFlow<AbsLoginOptions?> = urlInput
        .debounce(URL_CHECK_DELAY_MS)
        .mapLatest { if (it.isBlank()) null else servers.loginOptions(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Address of the identity provider that the form should open once in the browser ([browserOpened]). */
    private val _browser = MutableStateFlow<String?>(null)
    val browser: StateFlow<String?> = _browser.asStateFlow()

    /** The running OpenID login and what it is for (server, name); only in memory. */
    private var pendingLogin: Triple<AbsOpenIdLogin, String?, String>? = null

    init {
        viewModelScope.launch { OpenIdRedirects.received.collect(::onBrowserResult) }
    }

    fun onUrlChanged(url: String) {
        urlInput.value = url
    }

    /** [serverId] `null` = new server, otherwise this server's details are changed. */
    fun connect(serverId: String?, name: String, url: String, apiKey: String) {
        if (_connect.value == ConnectState.Connecting) return
        _connect.value = ConnectState.Connecting
        viewModelScope.launch {
            val result = if (serverId == null) servers.add(name, url, apiKey) else servers.update(serverId, name, url, apiKey)
            _connect.value = connected(result)
        }
    }

    private fun connected(result: AddServerResult): ConnectState = when (result) {
        is AddServerResult.Added -> {
            refresher.refreshAll()
            ConnectState.Connected
        }
        AddServerResult.InvalidUrl -> ConnectState.Failed(R.string.spoken_abs_error_url)
        AddServerResult.Unreachable -> ConnectState.Failed(R.string.spoken_abs_error_unreachable)
        AddServerResult.AuthFailed -> ConnectState.Failed(R.string.spoken_abs_error_auth)
        AddServerResult.OpenIdFailed -> ConnectState.Failed(R.string.spoken_abs_error_openid)
        AddServerResult.NoBookLibrary -> ConnectState.Failed(R.string.spoken_abs_error_library)
    }

    /**
     * Start the OpenID login: the server names the identity provider's page, the form opens it ([browser]).
     * [redirectUri] is `audiobookshelf://oauth` with Auth Tab, otherwise the app's own address.
     */
    fun startOpenId(serverId: String?, name: String, url: String, redirectUri: String) {
        if (_connect.value == ConnectState.Connecting) return
        _connect.value = ConnectState.Connecting
        viewModelScope.launch {
            _connect.value = when (val result = servers.beginOpenId(url, redirectUri)) {
                is BeginOpenIdResult.Started -> {
                    pendingLogin = Triple(result.login, serverId, name)
                    _browser.value = result.login.authorizationUrl.toString()
                    ConnectState.Idle
                }
                BeginOpenIdResult.InvalidUrl -> ConnectState.Failed(R.string.spoken_abs_error_url)
                BeginOpenIdResult.Unreachable -> ConnectState.Failed(R.string.spoken_abs_error_unreachable)
                is BeginOpenIdResult.RedirectNotAllowed -> ConnectState.Failed(R.string.spoken_abs_error_redirect, result.redirectUri)
            }
        }
    }

    fun browserOpened() {
        _browser.value = null
    }

    /** The browser's redirect; `null` if the user cancelled. */
    fun onBrowserResult(redirect: String?) {
        val (login, serverId, name) = pendingLogin ?: run {
            // After the process ended during the login, the PKCE verifier and cookies are missing.
            if (redirect != null) _connect.value = ConnectState.Failed(R.string.spoken_abs_error_openid_lost)
            return
        }
        if (redirect == null) return
        pendingLogin = null
        _connect.value = ConnectState.Connecting
        viewModelScope.launch {
            _connect.value = connected(servers.connectOpenId(serverId, name, login, redirect))
        }
    }

    fun syncNow() = refresher.refreshAll()

    /** "Sync now" on a server's row: only this server, as with music servers. */
    fun syncNow(serverId: String) = refresher.refreshServer(serverId)

    suspend fun editable(serverId: String) = servers.editable(serverId)

    /** Deselects or selects a book library; when selected again it syncs afterwards so its books are brought up to date. */
    fun setLibraryShown(serverId: String, libraryId: String, shown: Boolean) {
        viewModelScope.launch { if (servers.setLibraryShown(serverId, libraryId, shown) && shown) refresher.refreshAll() }
    }

    fun setEnabled(serverId: String, enabled: Boolean) {
        viewModelScope.launch {
            servers.setEnabled(serverId, enabled)
            if (enabled) refresher.refreshAll()
        }
    }

    /** [onRemoved] only afterwards: leaving the form ends the `viewModelScope`. */
    fun remove(serverId: String, onRemoved: () -> Unit = {}) {
        viewModelScope.launch {
            servers.remove(serverId)
            onRemoved()
        }
    }

    /** Typing again clears the last connection error, as in the music server form. */
    fun clearFailure() {
        if (_connect.value is ConnectState.Failed) _connect.value = ConnectState.Idle
    }

    private companion object {
        const val URL_CHECK_DELAY_MS = 500L
    }
}
