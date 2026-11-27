package de.kleinabersmart.earloft.core.playback

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.repository.PlaybackSourceResolver
import de.kleinabersmart.earloft.core.data.repository.ServerRepository
import de.kleinabersmart.earloft.core.data.share.ShareLinks
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.datasource.local.AudioPermission
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.SpokenMode
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
    if (signedOut) return PhoneAction.SIGN_IN
    if (error.errorCode !in IO_ERROR_CODES) return null
    return when {
        audioAccessMissing -> PhoneAction.AUDIO_ACCESS
        homeNetworkBlocked -> PhoneAction.HOME_NETWORK
        else -> null
    }
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

/** Reads the state [phoneActionFor] and [setupNeeded] decide on. */
internal class PhoneActionHints @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val spokenSettings: SpokenSettings,
    private val libraryRepository: LibraryRepository,
    private val podcastRepository: PodcastRepository,
    private val bookRepository: BookRepository,
    private val musicServers: ServerRepository,
    private val bookServers: AudiobookshelfServers,
    private val sourceResolver: PlaybackSourceResolver,
) {
    suspend fun setupNeeded(): Boolean = setupNeeded(
        areas = spokenSettings.areas.first(),
        hasMusic = libraryRepository.hasSongs().first(),
        hasPodcasts = podcastRepository.feeds.first().isNotEmpty(),
        hasBooks = bookRepository.hasBooks().first(),
    )

    /** What the phone has to do so that [item] plays after [error]; `null` if it's nothing the phone can fix. */
    suspend fun forError(item: MediaItem?, error: PlaybackException): PhoneAction? {
        val book = item?.bookId?.let { bookRepository.bookNow(it) }
        val songId = item?.songId
        return phoneActionFor(
            error,
            signedOut = book != null && isSignedOut(book),
            homeNetworkBlocked = homeNetworkBlocked(),
            audioAccessMissing = songId != null && !AudioPermission.isGranted(context) && hasDeviceFile(songId),
        )
    }

    /** A book that couldn't be prepared: its server's sign-in is missing. `null` if that's not the reason. */
    suspend fun forUnplayableBook(bookId: Long): PhoneAction? {
        val book = bookRepository.bookNow(bookId) ?: return null
        return when {
            isSignedOut(book) -> PhoneAction.SIGN_IN
            homeNetworkBlocked() -> PhoneAction.HOME_NETWORK
            else -> null
        }
    }

    /** Books of an Audiobookshelf server whose key or sign-in is missing; episodes, ARD and device books have none. */
    private suspend fun isSignedOut(book: Book): Boolean =
        book.kind != BookKind.EPISODE && !book.isLocal && !book.isArdSounds && book.serverId in bookServers.serversWithoutKey.first()

    private suspend fun homeNetworkBlocked(): Boolean {
        if (Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK) return false
        if (context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED) return false
        val addresses = musicServers.servers().first().map { it.baseUrl } +
            bookServers.servers.first().map { it.baseUrl } +
            podcastRepository.feeds.first().map { it.url }
        return addresses.any(ShareLinks::isHomeNetworkAddress)
    }

    /** A file of the device's music library (not a download, which lives in the app's own storage) can play the song. */
    private suspend fun hasDeviceFile(songId: Long): Boolean =
        sourceResolver.candidates(songId).any { it.location is PlaybackLocation.LocalFile && it.sourceId != null }

    private companion object {
        const val LOCAL_NETWORK_SDK = 37
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
