package de.kleinabersmart.earloft.core.playback

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the running [PlaybackService] that a permission was granted on the phone so a stuck car hint
 * ([PhoneActionPresenter]) can clear even when the player has no error to retry (review5 P12).
 */
@Singleton
class PhoneActionResolutions @Inject constructor() {
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val resolved: SharedFlow<Unit> = events.asSharedFlow()

    fun notifyResolved() {
        events.tryEmit(Unit)
    }
}

/** How [PhoneActionPresenter] reports hints to Android Auto (real [MediaSession] in production, fake in tests). */
@OptIn(UnstableApi::class)
internal interface PhoneActionSink {
    fun sendNonFatalError(message: String, extras: Bundle)

    /** Sets or clears the platform session's error state; returns `false` if no notification controller is connected. */
    fun setErrorState(exception: PlaybackException?): Boolean

    /** Whether Android Auto or Android Automotive is connected to the session. */
    val carConnected: Boolean
}

@OptIn(UnstableApi::class)
internal class SessionPhoneActionSink(private val session: MediaSession) : PhoneActionSink {
    override fun sendNonFatalError(message: String, extras: Bundle) {
        val controller = session.mediaNotificationControllerInfo ?: return
        session.sendError(controller, SessionError(SessionError.ERROR_UNKNOWN, message, extras))
    }

    override fun setErrorState(exception: PlaybackException?): Boolean {
        val controller = session.mediaNotificationControllerInfo ?: return false
        session.setPlaybackException(controller, exception)
        return true
    }

    override val carConnected: Boolean
        get() = session.connectedControllers.any { session.isAutoCompanionController(it) || session.isAutomotiveController(it) }
}

/**
 * Shows a [PhoneAction] in Android Auto (car app quality VI-1): as a playback error of the platform session with the hint text and the
 * resolution "Open on phone" ([resolutionFor]). Only for the media notification controller, which stands for the platform session
 * (Android Auto, the system's media controls); the app's own controller and the watch keep the player's real state.
 *
 * - A playback error the phone can fix ([PhoneActionState.forError]) gets the hint as its message; once the error is gone (retry,
 *   next title, new queue) so is the hint.
 * - A book that couldn't be started from the car ([onSpokenNotPlayable]) likewise, until something plays or the cause is resolved
 *   ([onCauseMayBeResolved], [onRootBrowsed]; review5 P12).
 *
 * The error state takes the car all controls but the metadata (Media3 keeps only the `COMMAND_GET_*` commands). So it is only set
 * while there is nothing to control: an empty queue or a player in an error state. Otherwise (a book fails while music plays or is
 * paused) the hint goes out as a non-fatal error, which Android Auto shows as a message and the controls stay.
 * The platform session also feeds the media card of the quick settings, the lock screen and a paired watch: without a car connected
 * the hint is only ever a non-fatal error, and one shown goes when the car disconnects ([onCarDisconnected]; review5 P10).
 * - Nothing set up at all ([onRootBrowsed], Android Auto opens the app): shown while the queue is empty, gone with the first title.
 */
@OptIn(UnstableApi::class)
internal class PhoneActionPresenter(
    private val context: Context,
    private val sink: PhoneActionSink,
    private val player: Player,
    private val hints: PhoneActionState,
    private val scope: CoroutineScope,
    private val resolutionFor: (PhoneAction) -> PendingIntent?,
) : Player.Listener {

    /** What is shown; `null` if nothing. */
    private var shown: PhoneAction? = null

    /** The book that triggered [shown], if any; used to re-check whether the cause is resolved (review5 P12). */
    private var shownBookId: Long? = null
    private var job: Job? = null

    override fun onPlayerErrorChanged(error: PlaybackException?) {
        job?.cancel()
        if (error == null) {
            if (shown != PhoneAction.SET_UP) clear()
            return
        }
        val item = player.currentMediaItem
        job = scope.launch { hints.forError(item, error)?.let { show(it, error, bookId = item?.bookId) } }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (shown == PhoneAction.SET_UP && !timeline.isEmpty) clear()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) clear()
    }

    /** Android Auto asked for the tabs: if nothing is set up and nothing is loaded, say where to set it up; otherwise clear stale hints. */
    fun onRootBrowsed() {
        scope.launch {
            val needed = player.mediaItemCount == 0 && hints.setupNeeded()
            if (needed) {
                show(PhoneAction.SET_UP, cause = null, bookId = null)
            } else {
                val action = shown ?: return@launch
                if (action == PhoneAction.SET_UP || (player.playerError == null && !hints.isStillBlocked(action, shownBookId))) {
                    clear()
                }
            }
        }
    }

    /** A book or episode from the car could not be prepared; if the phone can fix that, say so. */
    fun onSpokenNotPlayable(bookId: Long) {
        scope.launch { hints.forUnplayableBook(bookId)?.let { show(it, cause = null, bookId = bookId) } }
    }

    /** The car went away: the media card and the lock screen get their controls back. */
    fun onCarDisconnected() {
        // Posted: the session may still list the controller while it reports the disconnect. A second car stays served.
        scope.launch { if (!sink.carConnected) clear() }
    }

    /** A permission was granted or an Audiobookshelf sign-in changed: clear a stuck hint if its cause is gone (review5 P12). */
    fun onCauseMayBeResolved() {
        val action = shown ?: return
        if (action == PhoneAction.SET_UP || player.playerError != null) return
        val bookId = shownBookId
        scope.launch {
            if (shown == action && shownBookId == bookId && player.playerError == null && !hints.isStillBlocked(action, bookId)) {
                clear()
            }
        }
    }

    private fun show(action: PhoneAction, cause: PlaybackException?, bookId: Long?) {
        val resolution = resolutionFor(action)
        val extras = Bundle().apply {
            if (resolution != null) {
                putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, context.getString(R.string.phone_action_open))
                putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, resolution)
            }
        }
        val message = context.getString(action.message)
        if (!sink.carConnected || !hintAsErrorState(player.mediaItemCount, player.playerError)) {
            Log.d(TAG, "Hint for the car as a message: $action")
            sink.sendNonFatalError(message, extras)
            return
        }
        Log.d(TAG, "Hint for the car: $action")
        if (sink.setErrorState(PlaybackException(message, cause, action.errorCode, extras))) {
            shown = action
            shownBookId = bookId
        }
    }

    private fun clear() {
        if (shown == null) return
        shown = null
        shownBookId = null
        sink.setErrorState(null)
    }

    private companion object {
        const val TAG = "PhoneActionPresenter"
    }
}

/**
 * Whether a hint may put the platform session into the error state ([PhoneActionPresenter]): only if there is nothing the car could
 * control, an empty queue or a player that already failed. Otherwise it is sent as a non-fatal error and the controls stay.
 */
internal fun hintAsErrorState(mediaItemCount: Int, playerError: PlaybackException?): Boolean =
    mediaItemCount == 0 || playerError != null
