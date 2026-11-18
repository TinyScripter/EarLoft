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
 * Der Cast-Empfänger lädt eine neue Warteschlange mit `autoplay = playWhenReady`. Die App ruft aber erst
 * `setMediaItems`, dann `play()` – war vorher pausiert, geht das LOAD ohne Autoplay hinaus und das `play()`
 * trifft den Empfänger mitten im Laden und verpufft (am Gerät gesehen: neue Warteschlange da, aber pausiert).
 * Deshalb wird „Abspielen“ zurückgehalten, bis der Empfänger nach dem Laden bereit ist. Lokal ändert sich nichts.
 *
 * Ebenso beim Verbinden: Die Übergabe lädt die Warteschlange des Handys am Empfänger, ohne über diesen Player zu gehen.
 * Ein „Abspielen“ während dieses Ladens lehnte der Empfänger ab („Invalid Request“, noch keine Mediensitzung), meldete
 * danach keine Warteschlange mehr, und nach 20 s (Ladeüberwachung der Factory) kam alles aufs Handy zurück (am
 * Gerät gesehen, 2026-11-13). Bücher von Audiobookshelf brauchen dort 10–12 s, daher die lange Frist [LOAD_TIMEOUT_MS].
 *
 * Jede neue Warteschlange, die beim Casten hinausgeht, meldet [onRemoteQueue] (als Stand fürs Handy, falls der
 * Empfänger sie nicht laden kann). Entfernt jemand beim Casten alles bis zum Ende (Mix „als Nächstes“), meldet das
 * [onClearedToEnd]: Was noch in der Reserve der Factory liegt, gilt dann ebenfalls als entfernt.
 *
 * Sprünge lassen sich kurz zurückhalten ([holdSeeks]), solange die Factory gespielte Titel vor dem laufenden aus dem
 * Empfänger nimmt: `RemoteCastPlayer` rechnet bis zur Bestätigung eines Sprungs mit dessen Index, und meldet der Empfänger
 * in dieser Zeit die kürzere Warteschlange, greift Media3 (1.11.1) daneben und die App stürzt ab (am Gerät gesehen,
 * 2026-11-18). Wann zuletzt gesprungen wurde, steht in [lastSeekAtMs].
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

    /** `SystemClock.elapsedRealtime()` des letzten Sprungs. */
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
                // Beim Verbinden: Läuft der Empfänger schon (Wiederanhängen), ist er bereit, sonst lädt die Übergabe.
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

    /** Leere Listen (alles ging in die Reserve) wären eine leere Cast-Nachricht. */
    override fun addMediaItems(mediaItems: List<MediaItem>) {
        if (mediaItems.isNotEmpty()) super.addMediaItems(mediaItems)
    }

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        if (mediaItems.isNotEmpty()) super.addMediaItems(index, mediaItems)
    }

    /** Main-Thread. Sprünge ab jetzt sammeln, bis [releaseSeeks]. */
    fun holdSeeks() {
        seeksHeld = true
    }

    /** Main-Thread. Führt die gesammelten Sprünge in ihrer Reihenfolge aus. */
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
     * Ein Sprung zu einem Index: Wartet er, bis Titel davor entfernt sind, gilt danach der Titel, der beim Aufruf dort stand.
     * `null`, wenn es ihn nicht mehr gibt.
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
        const val LOAD_TIMEOUT_MS = 20_000L
    }
}
