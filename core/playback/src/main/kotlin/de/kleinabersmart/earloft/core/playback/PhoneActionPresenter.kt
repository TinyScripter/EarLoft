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
import kotlinx.coroutines.launch

/**
 * Shows a [PhoneAction] in Android Auto (car app quality VI-1): as a playback error of the platform session with the hint text and the
 * resolution "Open on phone" ([resolution]). Only for the media notification controller, which stands for the platform session
 * (Android Auto, the system's media controls); the app's own controller and the watch keep the player's real state.
 *
 * - A playback error the phone can fix ([PhoneActionHints.forError]) gets the hint as its message; once the error is gone (retry,
 *   next title, new queue) so is the hint.
 * - A book that couldn't be started from the car ([onSpokenNotPlayable]) likewise, until something plays.
 *
 * The error state takes the car all controls but the metadata (Media3 keeps only the `COMMAND_GET_*` commands). So it is only set
 * while there is nothing to control: an empty queue or a player in an error state. Otherwise (a book fails while music plays or is
 * paused) the hint goes out as a non-fatal error, which Android Auto shows as a message and the controls stay.
 * - Nothing set up at all ([onRootBrowsed], Android Auto opens the app): shown while the queue is empty, gone with the first title.
 */
@OptIn(UnstableApi::class)
internal class PhoneActionPresenter(
    private val context: Context,
    private val session: MediaSession,
    private val player: Player,
    private val hints: PhoneActionHints,
    private val scope: CoroutineScope,
    private val resolution: PendingIntent?,
) : Player.Listener {

    /** What is shown; `null` if nothing. */
    private var shown: PhoneAction? = null
    private var job: Job? = null

    override fun onPlayerErrorChanged(error: PlaybackException?) {
        job?.cancel()
        if (error == null) {
            if (shown != PhoneAction.SET_UP) clear()
            return
        }
        val item = player.currentMediaItem
        job = scope.launch { hints.forError(item, error)?.let { show(it, error) } }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (shown == PhoneAction.SET_UP && !timeline.isEmpty) clear()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) clear()
    }

    /** Android Auto asked for the tabs: if nothing is set up and nothing is loaded, say where to set it up. */
    fun onRootBrowsed() {
        scope.launch {
            val needed = player.mediaItemCount == 0 && hints.setupNeeded()
            if (needed) show(PhoneAction.SET_UP, cause = null) else if (shown == PhoneAction.SET_UP) clear()
        }
    }

    /** A book or episode from the car could not be prepared; if the phone can fix that, say so. */
    fun onSpokenNotPlayable(bookId: Long) {
        scope.launch { hints.forUnplayableBook(bookId)?.let { show(it, cause = null) } }
    }

    private fun show(action: PhoneAction, cause: PlaybackException?) {
        val controller = session.mediaNotificationControllerInfo ?: return
        val extras = Bundle().apply {
            if (resolution != null) {
                putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, context.getString(R.string.phone_action_open))
                putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, resolution)
            }
        }
        val message = context.getString(action.message)
        if (!hintAsErrorState(player.mediaItemCount, player.playerError)) {
            Log.d(TAG, "Hint for the car as a message: $action")
            session.sendError(controller, SessionError(SessionError.ERROR_UNKNOWN, message, extras))
            return
        }
        Log.d(TAG, "Hint for the car: $action")
        session.setPlaybackException(controller, PlaybackException(message, cause, action.errorCode, extras))
        shown = action
    }

    private fun clear() {
        if (shown == null) return
        shown = null
        session.mediaNotificationControllerInfo?.let { session.setPlaybackException(it, null) }
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
