package de.kleinabersmart.earloft.core.playback

import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player

/**
 * The cast receiver hasn't reported the handed-over queue yet (measured on the device: a good 6 s for a book from
 * Audiobookshelf). The switch to "empty" before that is not a title change: the service's listeners leave speed,
 * listening history and saved queue as they are until then (R24).
 */
internal val Player.awaitsRemoteQueue: Boolean
    get() = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE && currentTimeline.isEmpty

/**
 * When switching to the cast device, the player hands over the queue, but the receiver only reports it back after loading
 * (observed on the device: a good second). Until then the queue is empty, and the now playing view would show
 * "Nothing playing". For that long the phone's last state is kept here, already with [PlayerState.isRemote]; the position
 * keeps advancing from the last known value. If the receiver loads nothing, the cast player ends the session, and with the
 * switch back to the phone the holding ends too.
 */
internal class RemoteHandOver {

    private var holding = false
    private var positionMs = 0L
    private var positionAtMs = 0L

    /** 0 while nothing is playing. */
    private var positionSpeed = 0f

    /**
     * [next] is the player's state, [queueEmpty] whether its queue is empty, [positionMs] its position at
     * time [nowMs]. Returns the state to be displayed.
     */
    fun update(previous: PlayerState, next: PlayerState, queueEmpty: Boolean, positionMs: Long, nowMs: Long): PlayerState {
        holding = next.isRemote && queueEmpty && previous.hasMedia && (holding || !previous.isRemote)
        if (holding) return previous.copy(isRemote = true)
        this.positionMs = positionMs
        positionAtMs = nowMs
        positionSpeed = if (next.isPlaying) next.speed else 0f
        return next
    }

    /** The extrapolated position while holding; otherwise `null`. */
    fun positionMs(nowMs: Long): Long? =
        if (holding) positionMs + ((nowMs - positionAtMs) * positionSpeed).toLong() else null
}
