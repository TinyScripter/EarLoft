package de.kleinabersmart.earloft.core.playback

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.repository.PlaybackSourceResolver
import de.kleinabersmart.earloft.core.data.share.ShareLinks
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.datasource.local.AudioPermission
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.SpokenMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Something only the phone can fix before anything (more) plays in the car. Android Auto shows [message] instead of an empty list or
 * a bare playback error; the text tells the driver to do it only once stopped safely (car app quality VI-1, "Handle errors").
 */
internal enum class PhoneAction(@param:StringRes val message: Int, val errorCode: Int) {
    /** No enabled area has anything to play: no server, subscription or music on the device yet. */
    SET_UP(R.string.phone_action_set_up, PlaybackException.ERROR_CODE_SETUP_REQUIRED),

    /** The Audiobookshelf sign-in of the book's server has expired (or its key is missing after a restore). */
    SIGN_IN(R.string.phone_action_sign_in, PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED),

    /** Android 17: without the home network permission every connection to a server at home ends in a timeout. */
    HOME_NETWORK(R.string.phone_action_home_network, PlaybackException.ERROR_CODE_PERMISSION_DENIED),

    /** Music from the device without the permission to read audio files. */
    AUDIO_ACCESS(R.string.phone_action_audio_access, PlaybackException.ERROR_CODE_PERMISSION_DENIED),
}

/**
 * Why [error] happened, if the phone can fix it. Only errors of reading or loading the file count ([PlaybackException] IO codes);
 * the flags say what is missing for the failed item. A missing sign-in wins: the request was rejected, not left without an answer.
 */
internal fun phoneActionFor(
    error: PlaybackException,
    signedOut: Boolean,
    homeNetworkBlocked: Boolean,
    audioAccessMissing: Boolean,
): PhoneAction? {
    if (error.errorCode !in IO_ERROR_CODES) return null
    if (signedOut) return PhoneAction.SIGN_IN
    return when {
        audioAccessMissing -> PhoneAction.AUDIO_ACCESS
        homeNetworkBlocked -> PhoneAction.HOME_NETWORK
        else -> null
    }
}

/**
 * Whether [addresses] of the failed item lead to the home network while the Android 17 permission is missing and no VPN
 * (`TRANSPORT_VPN`, e.g. Tailscale/WireGuard) carries the traffic (review5 P9, decision 5.5 A).
 */
internal fun isHomeNetworkBlocked(
    addresses: Collection<String>,
    permissionMissing: Boolean,
    vpnActive: Boolean,
): Boolean = permissionMissing && !vpnActive && addresses.any(ShareLinks::isHomeNetworkAddress)

/** A downloaded (`file://`) or local (`content://`) media file on the device: neither sign-in nor home network applies to it. */
internal fun isLocalMediaUri(uri: Uri?): Boolean = uri?.scheme == "file" || uri?.scheme == "content"

/** Whether the active network is a VPN (Tailscale, WireGuard, …), which does not require Android 17's home network permission. */
internal fun hasActiveVpn(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
}

/** Whether none of the enabled [areas] has anything to show: the user still has to set the app up on the phone. */
internal fun setupNeeded(areas: List<SpokenMode>, hasMusic: Boolean, hasPodcasts: Boolean, hasBooks: Boolean): Boolean =
    areas.none { area ->
        when (area) {
            SpokenMode.MUSIC -> hasMusic
            SpokenMode.PODCASTS -> hasPodcasts
            SpokenMode.BOOKS -> hasBooks
        }
    }

private val IO_ERROR_CODES = PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE

/** Read-only query interface of [PhoneActionHints], so [PhoneActionPresenter] can be tested without repositories. */
internal interface PhoneActionState {
    suspend fun setupNeeded(): Boolean
    suspend fun forError(item: MediaItem?, error: PlaybackException): PhoneAction?
    suspend fun forUnplayableBook(bookId: Long): PhoneAction?
    suspend fun isStillBlocked(action: PhoneAction, bookId: Long?): Boolean
}

/** Reads the state [phoneActionFor] and [setupNeeded] decide on. */
internal class PhoneActionHints @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val spokenSettings: SpokenSettings,
    private val libraryRepository: LibraryRepository,
    private val podcastRepository: PodcastRepository,
    private val bookRepository: BookRepository,
    private val bookServers: AudiobookshelfServers,
    private val sourceResolver: PlaybackSourceResolver,
) : PhoneActionState {
    /** Emits whenever the set of Audiobookshelf servers missing a key changes (sign-in restored or expired). */
    val serversWithoutKey: Flow<Set<String>> get() = bookServers.serversWithoutKey

    override suspend fun setupNeeded(): Boolean = setupNeeded(
        areas = spokenSettings.areas.first(),
        hasMusic = libraryRepository.hasSongs().first(),
        hasPodcasts = podcastRepository.feeds.first().isNotEmpty(),
        hasBooks = bookRepository.hasBooks().first(),
    )

    /** What the phone has to do so that [item] plays after [error]; `null` if it's nothing the phone can fix. */
    override suspend fun forError(item: MediaItem?, error: PlaybackException): PhoneAction? {
        val songId = item?.songId
        if (songId != null) {
            val candidates = sourceResolver.candidates(songId)
            val addresses = candidates.mapNotNull { (it.location as? PlaybackLocation.Remote)?.uri?.toString() }
            val hasDeviceFile = candidates.any { it.location is PlaybackLocation.LocalFile && it.sourceId != null }
            return phoneActionFor(
                error,
                signedOut = false,
                homeNetworkBlocked = homeNetworkBlocked(addresses),
                audioAccessMissing = !AudioPermission.isGranted(context) && hasDeviceFile,
            )
        }
        val book = item?.bookId?.let { bookRepository.bookNow(it) } ?: return null
        val itemUri = item.localConfiguration?.uri
        // Downloaded or local files need neither server sign-in nor home network access (review5 P9).
        if (isLocalMediaUri(itemUri)) return null
        val addresses = itemUri?.takeIf { it.scheme == "http" || it.scheme == "https" }
            ?.let { listOf(it.toString()) }
            ?: bookAddresses(book)
        return phoneActionFor(
            error,
            signedOut = isSignedOut(book),
            homeNetworkBlocked = homeNetworkBlocked(addresses),
            audioAccessMissing = false,
        )
    }

    /** A book that couldn't be prepared: its server's sign-in or home network access is missing. `null` if that's not the reason. */
    override suspend fun forUnplayableBook(bookId: Long): PhoneAction? {
        val book = bookRepository.bookNow(bookId) ?: return null
        return when {
            isSignedOut(book) -> PhoneAction.SIGN_IN
            homeNetworkBlocked(bookAddresses(book)) -> PhoneAction.HOME_NETWORK
            else -> null
        }
    }

    /** Whether [action] (shown earlier without a player error, e.g. via [forUnplayableBook]) still holds (review5 P12). */
    override suspend fun isStillBlocked(action: PhoneAction, bookId: Long?): Boolean = when (action) {
        PhoneAction.SET_UP -> setupNeeded()
        PhoneAction.SIGN_IN -> bookId?.let { bookRepository.bookNow(it) }?.let { isSignedOut(it) } ?: false
        // A VPN switched on since reaches the home network without the permission, also without a book (review5 8.12).
        PhoneAction.HOME_NETWORK -> isLocalNetworkPermissionMissing() && !hasActiveVpn(context) &&
            (bookId == null || bookRepository.bookNow(bookId)?.let { homeNetworkBlocked(bookAddresses(it)) } == true)
        PhoneAction.AUDIO_ACCESS -> !AudioPermission.isGranted(context)
    }

    /** Books of an Audiobookshelf server whose key or sign-in is missing; episodes, ARD and device books have none. */
    private suspend fun isSignedOut(book: Book): Boolean =
        book.kind != BookKind.EPISODE && !book.isLocal && !book.isArdSounds && book.serverId in bookServers.serversWithoutKey.first()

    private suspend fun bookAddresses(book: Book): List<String> = when {
        book.isLocal || book.isArdSounds -> emptyList()
        book.kind == BookKind.EPISODE -> listOfNotNull(podcastRepository.feeds.first().find { it.id == book.serverId }?.url)
        else -> listOfNotNull(bookServers.servers.first().find { it.enabled && it.serverId == book.serverId }?.baseUrl)
    }

    private fun isLocalNetworkPermissionMissing(): Boolean =
        Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK &&
            context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED

    private fun homeNetworkBlocked(addresses: Collection<String>): Boolean = isHomeNetworkBlocked(
        addresses = addresses,
        permissionMissing = isLocalNetworkPermissionMissing(),
        vpnActive = hasActiveVpn(context),
    )

    private companion object {
        const val LOCAL_NETWORK_SDK = 37
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
