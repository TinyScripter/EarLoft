package keinabersmart.kleinaberlaut.core.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import keinabersmart.kleinaberlaut.core.data.repository.PlaybackStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Zählt die tatsächlich gehörte Zeit pro Titel und schreibt einen Eintrag in den Hörverlauf,
 * sobald ein Titel mindestens halb bzw. 30 Sekunden lang gehört wurde.
 */
internal class PlayTracker(
    private val player: Player,
    private val repository: PlaybackStateRepository,
    private val scope: CoroutineScope,
) : Player.Listener {

    private var songId: Long? = null
    private var durationMs: Long = 0
    private var listenedMs: Long = 0
    private var playingSince: Long? = null
    /** Wanduhrzeit, zu der zuletzt gehört wurde – Ende des Hörens, auch wenn erst später abgeschlossen wird. */
    private var lastHeardAt: Long = 0

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
            scope.launch { repository.recordPlay(id, playedAt, listened) }
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
