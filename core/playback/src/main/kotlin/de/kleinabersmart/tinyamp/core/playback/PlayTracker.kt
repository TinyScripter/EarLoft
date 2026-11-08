package de.kleinabersmart.tinyamp.core.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Zählt die tatsächlich gehörte Zeit pro Titel und schreibt einen Eintrag in den Hörverlauf ([recordPlay]: Song,
 * Zeitpunkt, gehörte Millisekunden), sobald ein Titel mindestens halb bzw. 30 Sekunden lang gehört wurde.
 */
internal class PlayTracker(
    private val player: Player,
    private val recordPlay: (songId: Long, playedAt: Long, listenedMs: Long) -> Unit,
) : Player.Listener {

    private var songId: Long? = null
    private var durationMs: Long = 0
    private var listenedMs: Long = 0
    private var playingSince: Long? = null
    /** Wanduhrzeit, zu der zuletzt gehört wurde – Ende des Hörens, auch wenn erst später abgeschlossen wird. */
    private var lastHeardAt: Long = 0

    /** Wo der Titel spielt (Handy oder Cast); wechselt es, läuft derselbe Titel weiter statt neu zu beginnen. */
    private var playbackType = player.deviceInfo.playbackType

    /** Die Warteschlange ging gerade an den Cast-Empfänger ([awaitsRemoteQueue]). */
    private var handingOver = false

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            playingSince = SystemClock.elapsedRealtime()
        } else {
            accumulate()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // Ende der Warteschlange: Es folgt kein Titelwechsel, der den Titel abschließen würde.
        // Der Titel bleibt gesetzt – „Abspielen“ startet ihn ohne Titelwechsel neu.
        if (playbackState == Player.STATE_ENDED) recordCurrent()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem == null && player.awaitsRemoteQueue) {
            accumulate()
            handingOver = true
            return
        }
        // Beim Wechsel zwischen Handy und Cast-Gerät kommt derselbe Titel als neue Warteschlange wieder: weiterzählen, sonst
        // stünde er doppelt im Hörverlauf.
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

    /** Beim Beenden des Service aufrufen, damit der letzte Titel nicht verloren geht. */
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
