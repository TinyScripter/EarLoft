package keinabersmart.kleinaberlaut.cast

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Der Cast-Empfänger lädt eine neue Warteschlange mit `autoplay = playWhenReady`. Die App ruft aber erst
 * `setMediaItems`, dann `play()` – war vorher pausiert, geht das LOAD ohne Autoplay hinaus und das `play()`
 * trifft den Empfänger mitten im Laden und verpufft (am Gerät gesehen: neue Warteschlange da, aber pausiert).
 * Deshalb wird „Abspielen“ zurückgehalten, bis der Empfänger nach dem Laden bereit ist. Lokal ändert sich nichts.
 */
@OptIn(UnstableApi::class)
internal class DeferredRemotePlayPlayer(player: Player) : ForwardingPlayer(player) {

    private val handler = Handler(player.applicationLooper)
    private val giveUp = Runnable { finishLoad() }
    private var loading = false
    private var playAfterLoad = false

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
            }
        })
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        startLoad()
        super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        startLoad()
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        startLoad()
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

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

    /** Nur nötig, wenn ohne Autoplay geladen wird: entfernt und pausiert. */
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
        const val LOAD_TIMEOUT_MS = 5_000L
    }
}
