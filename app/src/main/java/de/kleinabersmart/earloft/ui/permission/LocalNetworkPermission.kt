package de.kleinabersmart.earloft.ui.permission

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.repository.ServerRepository
import de.kleinabersmart.earloft.core.data.share.ShareLinks
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * From Android 17 (API 37) on, an app targeting `targetSdk` 37 may reach devices on the home network (e.g. a Navidrome at
 * `http://192.168.x.x`) only with this runtime permission. Before that it doesn't exist.
 */
object LocalNetworkPermission {
    const val name = "android.permission.ACCESS_LOCAL_NETWORK"

    val isRequired: Boolean get() = Build.VERSION.SDK_INT >= 37

    /** The system was asked in this process (by a form or by [HomeNetworkPermissionPrompt]): don't ask a second time right away. */
    @Volatile
    var askedThisProcess = false

    fun isGranted(context: Context): Boolean =
        !isRequired || ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
}

/**
 * Returns a function that requests the home network permission before [block] if needed. [block] runs afterwards in any case
 * (even after a denial) so the connection error is shown as usual.
 */
@Composable
fun rememberLocalNetworkRequester(block: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentBlock by rememberUpdatedState(block)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { currentBlock() }
    return {
        if (LocalNetworkPermission.isGranted(context)) {
            currentBlock()
        } else {
            LocalNetworkPermission.askedThisProcess = true
            launcher.launch(LocalNetworkPermission.name)
        }
    }
}

/**
 * Whether the home network permission is granted, checked again each time the screen resumes: the way back from the app settings
 * ([openAppSettings]) has to show the result. Always `true` before Android 17.
 */
@Composable
fun rememberLocalNetworkGranted(): Boolean {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(LocalNetworkPermission.isGranted(context)) }
    LifecycleResumeEffect(Unit) {
        granted = LocalNetworkPermission.isGranted(context)
        onPauseOrDispose { }
    }
    return granted
}

/**
 * Whether a connection error at [address] comes from the missing home network permission: without it every connection to the
 * home network ends in a timeout, and "Server unreachable (timeout)" hides the cause (review4 T5). [unreachable]: the error is
 * one of not reaching the server, not e.g. wrong credentials.
 */
internal fun blockedByHomeNetwork(unreachable: Boolean, address: String, granted: Boolean): Boolean =
    unreachable && !granted && ShareLinks.isHomeNetworkAddress(address)

/** Whether any of the [addresses] of servers and feeds is on the home network. */
internal fun needsHomeNetworkAccess(addresses: Collection<String>): Boolean = addresses.any(ShareLinks::isHomeNetworkAddress)

@HiltViewModel
class HomeNetworkViewModel @Inject constructor(
    servers: ServerRepository,
    spokenServers: AudiobookshelfServers,
    podcasts: PodcastRepository,
) : ViewModel() {

    /** A set up music or Audiobookshelf server, or a podcast feed, is on the home network. */
    val needed: StateFlow<Boolean> = combine(
        servers.servers(),
        spokenServers.servers,
        podcasts.feeds,
    ) { music, spoken, feeds ->
        needsHomeNetworkAccess(music.map { it.baseUrl } + spoken.map { it.baseUrl } + feeds.map { it.url })
    }
        .orOnError(false)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

/**
 * Asks for the home network permission once per process as soon as a server or feed on the home network exists and the
 * permission is missing: after restoring a backup or moving to a new device, no form asks for it, and every connection would
 * end in a timeout without a hint (review4 T5). Does nothing before Android 17.
 */
@Composable
fun HomeNetworkPermissionPrompt(viewModel: HomeNetworkViewModel = hiltViewModel()) {
    if (!LocalNetworkPermission.isRequired) return
    val context = LocalContext.current
    val needed by viewModel.needed.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(needed) {
        if (needed && !LocalNetworkPermission.askedThisProcess && !LocalNetworkPermission.isGranted(context)) {
            LocalNetworkPermission.askedThisProcess = true
            launcher.launch(LocalNetworkPermission.name)
        }
    }
}
