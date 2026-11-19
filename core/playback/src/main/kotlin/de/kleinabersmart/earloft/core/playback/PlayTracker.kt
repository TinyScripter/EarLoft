package de.kleinabersmart.earloft.core.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Counts the actually listened time per title and writes an entry to the listening history ([recordPlay]: song,
 * timestamp, listened milliseconds) as soon as a title has been listened to for at least half its length or 30 seconds.
 */
internal class PlayTracker(
    private val player: Player,
    private val recordPlay: (songId: Long, playedAt: Long, listenedMs: Long) -> Unit,
) : Player.Listener {

    private var songId: Long? = null
    private var durationMs: Long = 0
    private var listenedMs: Long = 0
    private var playingSince: Long? = null
    /** Wall-clock time of the last listening – the end of listening, even if it's only completed later. */
    private var lastHeardAt: Long = 0

    /** Where the title plays (phone or cast); if that changes, the same title continues instead of starting anew. */
    private var playbackType = player.deviceInfo.playbackType

    /** The queue just went to the cast receiver ([awaitsRemoteQueue]). */
    private var handingOver = false

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            playingSince = SystemClock.elapsedRealtime()
        } else {
            accumulate()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // End of the queue: no title change follows that would complete the title.
        // The title stays set – "Play" restarts it without a title change.
        if (playbackState == Player.STATE_ENDED) recordCurrent()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem == null && player.awaitsRemoteQueue) {
            accumulate()
            handingOver = true
            return
        }
        // When switching between phone and cast device, the same title comes back as a new queue: keep counting, otherwise
        // it would appear twice in the listening history.
        val type = player.deviceInfo.playbackType
        val continues = mediaItem?.songId != null && mediaItem.songId == songId &&
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && (handingOver || type != playbackType)
        handingOver = false
        playbackType = type
        if (continues) {
            accumulate()
            if (player.isPlaying) playingSince = SystemClock.elapsedRealtime()
            return
        }
        finishCurrent()
        songId = mediaItem?.songId
        durationMs = mediaItem?.mediaMetadata?.durationMs ?: 0
        if (player.isPlaying) playingSince = SystemClock.elapsedRealtime()
    }

    /** Call when the service ends, so the last title isn't lost. */
    fun finishCurrent() {
        recordCurrent()
        songId = null
    }

    private fun recordCurrent() {
        accumulate()
        val id = songId
        val listened = listenedMs
        if (id != null && listened >= threshold()) {
            val playedAt = lastHeardAt
            recordPlay(id, playedAt, listened)
        }
        listenedMs = 0
    }

    private fun accumulate() {
        playingSince?.let {
            listenedMs += SystemClock.elapsedRealtime() - it
            lastHeardAt = System.currentTimeMillis()
        }
        playingSince = null
    }

    private fun threshold(): Long =
        if (durationMs > 0) minOf(durationMs / 2, MIN_LISTEN_MS) else MIN_LISTEN_MS

    private companion object {
        const val MIN_LISTEN_MS = 30_000L
    }
}
