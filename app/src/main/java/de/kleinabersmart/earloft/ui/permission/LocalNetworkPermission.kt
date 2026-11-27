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
import de.kleinabersmart.earloft.core.data.spoken.BookSyncer
import de.kleinabersmart.earloft.core.data.spoken.SpokenRefresher
import de.kleinabersmart.earloft.core.data.sync.LibrarySyncer
import de.kleinabersmart.earloft.core.data.sync.SyncFailure
import de.kleinabersmart.earloft.core.model.FeedError
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

/**
 * Ids of the [targets] (id to address) on the home network to sync again once the permission is granted, so an old timeout does not
 * stay until the next sync: all but those whose last sync failed for another reason ([failures] by id, e.g. wrong credentials).
 * Without a failure counts too: a sync started before the grant (the one on opening the app, next to the prompt) only fails once its
 * timeout has run out, after the grant; the syncs of one server run one after the other, so the new one follows it.
 */
internal fun failedAtHome(targets: List<Pair<String, String>>, failures: Map<String, SyncFailure>): List<String> =
    targets.filter { (id, address) ->
        val failure = failures[id]
        blockedByHomeNetwork(failure == null || failure is SyncFailure.Unreachable, address, granted = false)
    }.map { it.first }

/** Whether any of the [addresses] of servers and feeds is on the home network. */
internal fun needsHomeNetworkAccess(addresses: Collection<String>): Boolean = addresses.any(ShareLinks::isHomeNetworkAddress)

@HiltViewModel
class HomeNetworkViewModel @Inject constructor(
    private val servers: ServerRepository,
    private val spokenServers: AudiobookshelfServers,
    private val podcasts: PodcastRepository,
    private val musicSyncer: LibrarySyncer,
    private val bookSyncer: BookSyncer,
    private val bookRefresher: SpokenRefresher,
    private val playbackConnection: PlaybackConnection,
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

    /**
     * The permission was just granted: syncs the servers and feeds on the home network again whose last sync failed without it or is
     * still running ([failedAtHome]). Otherwise their rows kept showing the old timeout until the next sync, although the cause is gone.
     */
    fun resyncFailedAtHome() {
        // A title from a server at home that failed for want of the permission plays now.
        playbackConnection.retryFailedStart()
        viewModelScope.launch {
            val music = servers.servers().first().filter { it.enabled }.map { it.sourceId to it.baseUrl }
            failedAtHome(music, musicSyncer.failures.value).forEach(servers::syncNow)
            val spoken = spokenServers.servers.first().filter { it.enabled }.map { it.serverId to it.baseUrl }
            failedAtHome(spoken, bookSyncer.failures.value).forEach(bookRefresher::refreshServer)
            // Paused subscriptions are only synced on their own page.
            val feeds = podcasts.feeds.first().filter { !it.isPaused }
            val feedFailures = feeds
                .filter { it.failedRefreshes > 0 && it.refreshError != FeedError.UNREACHABLE && it.refreshError != null }
                .associate { it.id to SyncFailure.Other(it.refreshError.toString()) }
            failedAtHome(feeds.map { it.id to it.url }, feedFailures).forEach { feedId -> launch { podcasts.refresh(feedId) } }
        }
    }
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
    // Granted from this request, a form or the app settings ("Open settings" at a sync error): each shows up at the next resume.
    val granted = rememberLocalNetworkGranted()
    var wasDenied by remember { mutableStateOf(!granted) }
    LaunchedEffect(granted) {
        if (granted && wasDenied) viewModel.resyncFailedAtHome()
        wasDenied = !granted
    }
    LaunchedEffect(needed) {
        if (needed && !LocalNetworkPermission.askedThisProcess && !LocalNetworkPermission.isGranted(context)) {
            LocalNetworkPermission.askedThisProcess = true
            launcher.launch(LocalNetworkPermission.name)
        }
    }
}
