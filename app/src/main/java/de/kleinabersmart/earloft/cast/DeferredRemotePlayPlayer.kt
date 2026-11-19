package de.kleinabersmart.earloft.cast

import android.os.Handler
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PlayerTransferState
import androidx.media3.common.util.UnstableApi

/**
 * The cast receiver loads a new queue with `autoplay = playWhenReady`. The app, however, first calls
 * `setMediaItems` and then `play()` – if it was paused before, the LOAD goes out without autoplay and the `play()`
 * hits the receiver mid-load and fizzles out (seen on the device: new queue there, but paused).
 * That's why "play" is held back until the receiver is ready after loading. Locally nothing changes.
 *
 * Same when connecting: the handover loads the phone's queue on the receiver without going through this player.
 * A "play" during that load was rejected by the receiver ("Invalid Request", no media session yet), it then reported
 * no queue anymore, and after 20 s (the factory's load watch) everything came back to the phone (seen on the
 * device, 2026-11-13). Books from Audiobookshelf take 10–12 s there, hence the long deadline [LOAD_TIMEOUT_MS].
 *
 * Every new queue that goes out while casting is reported via [onRemoteQueue] (as the state for the phone in case the
 * receiver can't load it). If someone removes everything up to the end while casting (mix "play next"), that is reported via
 * [onClearedToEnd]: whatever is still in the factory's reserve then counts as removed too.
 *
 * Seeks can be held back briefly ([holdSeeks]) while the factory removes played titles before the current one from the
 * receiver: `RemoteCastPlayer` works with a seek's index until it is confirmed, and if the receiver reports
 * the shorter queue during that time, Media3 (1.11.1) gets it wrong and the app crashes (seen on the device,
 * 2026-11-18). When the last seek happened is stored in [lastSeekAtMs].
 */
@OptIn(UnstableApi::class)
internal class DeferredRemotePlayPlayer(
    player: Player,
    private val onRemoteQueue: (PlayerTransferState) -> Unit = {},
    private val onClearedToEnd: () -> Unit = {},
) : ForwardingPlayer(player) {

    private val handler = Handler(player.applicationLooper)
    private val giveUp = Runnable { finishLoad() }
    private var loading = false
    private var playAfterLoad = false

    /** `SystemClock.elapsedRealtime()` of the last seek. */
    var lastSeekAtMs = 0L
        private set
    private var seeksHeld = false
    private val heldSeeks = ArrayList<() -> Unit>()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) finishLoad()
            }

            override fun onPlayerError(error: PlaybackException) {
                playAfterLoad = false
                finishLoad()
            }

            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                playAfterLoad = false
                finishLoad()
                // When connecting: if the receiver is already running (rejoin), it's ready; otherwise the handover is loading.
                if (playbackState != Player.STATE_READY) startLoad()
            }
        })
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        startLoad()
        reportRemoteQueue(mediaItems, 0, 0)
        super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        startLoad()
        reportRemoteQueue(mediaItems, 0, 0)
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        startLoad()
        reportRemoteQueue(mediaItems, startIndex, startPositionMs)
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    private fun reportRemoteQueue(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE || mediaItems.isEmpty()) return
        val state = PlayerTransferState.builderFromPlayer(this)
            .setMediaItems(mediaItems)
            .setCurrentMediaItemIndex(startIndex.coerceIn(0, mediaItems.lastIndex))
            .setCurrentPosition(if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs)
            .build()
        onRemoteQueue(state)
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE && toIndex >= mediaItemCount) onClearedToEnd()
        super.removeMediaItems(fromIndex, toIndex)
    }

    /** Empty lists (everything went into the reserve) would be an empty cast message. */
    override fun addMediaItems(mediaItems: List<MediaItem>) {
        if (mediaItems.isNotEmpty()) super.addMediaItems(mediaItems)
    }

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        if (mediaItems.isNotEmpty()) super.addMediaItems(index, mediaItems)
    }

    /** Main thread. Collect seeks from now on until [releaseSeeks]. */
    fun holdSeeks() {
        seeksHeld = true
    }

    /** Main thread. Executes the collected seeks in order. */
    fun releaseSeeks() {
        if (!seeksHeld) return
        seeksHeld = false
        val held = heldSeeks.toList()
        heldSeeks.clear()
        held.forEach { it() }
    }

    private fun seek(action: () -> Unit) {
        lastSeekAtMs = SystemClock.elapsedRealtime()
        if (seeksHeld) heldSeeks += action else action()
    }

    /**
     * A seek to an index: if it waits until titles before it are removed, it then targets the title that was there at the time of the call.
     * `null` if it no longer exists.
     */
    private fun indexAfterHold(mediaItemIndex: Int, mediaId: String?): Int? {
        if (mediaId == null || mediaItemIndex in 0 until mediaItemCount && getMediaItemAt(mediaItemIndex).mediaId == mediaId) {
            return mediaItemIndex
        }
        return (0 until mediaItemCount).firstOrNull { getMediaItemAt(it).mediaId == mediaId }
    }

    private fun mediaIdAt(mediaItemIndex: Int): String? =
        mediaItemIndex.takeIf { it in 0 until mediaItemCount }?.let { getMediaItemAt(it).mediaId }

    override fun seekToDefaultPosition() = seek { super.seekToDefaultPosition() }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        val mediaId = mediaIdAt(mediaItemIndex)
        seek { indexAfterHold(mediaItemIndex, mediaId)?.let { super.seekToDefaultPosition(it) } }
    }

    override fun seekTo(positionMs: Long) = seek { super.seekTo(positionMs) }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val mediaId = mediaIdAt(mediaItemIndex)
        seek { indexAfterHold(mediaItemIndex, mediaId)?.let { super.seekTo(it, positionMs) } }
    }

    override fun seekBack() = seek { super.seekBack() }
    override fun seekForward() = seek { super.seekForward() }
    override fun seekToPrevious() = seek { super.seekToPrevious() }
    override fun seekToPreviousMediaItem() = seek { super.seekToPreviousMediaItem() }
    override fun seekToNext() = seek { super.seekToNext() }
    override fun seekToNextMediaItem() = seek { super.seekToNextMediaItem() }

    override fun play() {
        if (loading) playAfterLoad = true else super.play()
    }

    override fun pause() {
        playAfterLoad = false
        super.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady && loading) {
            playAfterLoad = true
            return
        }
        if (!playWhenReady) playAfterLoad = false
        super.setPlayWhenReady(playWhenReady)
    }

    /** Only needed when loading without autoplay: removed and paused. */
    private fun startLoad() {
        if (deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE || playWhenReady) return
        loading = true
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, LOAD_TIMEOUT_MS)
    }

    private fun finishLoad() {
        if (!loading) return
        loading = false
        handler.removeCallbacks(giveUp)
        if (playAfterLoad) {
            playAfterLoad = false
            super.play()
        }
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 20_000L
    }
}
