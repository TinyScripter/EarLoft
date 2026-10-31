package keinabersmart.kleinaberlaut.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import keinabersmart.kleinaberlaut.core.data.spoken.BookProgressSync
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import keinabersmart.kleinaberlaut.core.model.BookTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Alles, was nur für Hörbücher gilt (Konzept 12.3/12.4): Fortschritt festhalten und melden, Geschwindigkeit je Buch
 * setzen und merken. Läuft im Service auf dem Player-Thread; [scope] ist der Main-Scope.
 */
@OptIn(UnstableApi::class)
internal class BookPlaybackListener(
    private val player: Player,
    private val books: BookRepository,
    private val progressSync: BookProgressSync,
    private val scope: CoroutineScope,
) : Player.Listener {

    private var timelineBookId: Long? = null
    private var timeline: BookTimeline? = null
    private var loop: Job? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val bookId = mediaItem?.bookId
        if (bookId == null) {
            // Musik läuft immer mit normaler Geschwindigkeit.
            if (player.playbackParameters.speed != 1f) player.setPlaybackSpeed(1f)
            return
        }
        // Innerhalb eines Buchs (nächste Datei) bleibt alles; bei einem anderen Buch gilt dessen Geschwindigkeit.
        if (timelineBookId == bookId) return
        timelineBookId = bookId
        timeline = null
        scope.launch {
            timeline = books.timeline(bookId)
            books.progressNow(bookId)?.speed?.let { speed ->
                if (speed != player.playbackParameters.speed && player.currentMediaItem?.bookId == bookId) player.setPlaybackSpeed(speed)
            }
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        val bookId = player.currentMediaItem?.bookId ?: return
        scope.launch { books.setSpeed(bookId, playbackParameters.speed) }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        // Ein anderes Buch (oder Musik) beginnt: den Stand des bisherigen festhalten.
        val oldBookId = oldPosition.mediaItem?.bookId ?: return
        if (oldBookId == newPosition.mediaItem?.bookId) return
        record(oldBookId, oldPosition.mediaItem?.bookTrackIndex ?: 0, oldPosition.positionMs, force = true)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val item = player.currentMediaItem
        val bookId = item?.bookId
        loop?.cancel()
        if (bookId == null) return
        if (isPlaying) {
            loop = scope.launch {
                while (true) {
                    delay(SAVE_INTERVAL_MS)
                    recordCurrent(force = false)
                }
            }
        } else {
            recordCurrent(force = true)
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) recordCurrent(force = true)
        if (playbackState == Player.STATE_READY) learnDuration()
    }

    /** Folgen ohne (oder mit falscher) Dauer im Feed: die Datei sagt, wie lang sie wirklich ist. */
    private fun learnDuration() {
        val bookId = player.currentMediaItem?.bookId ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        scope.launch {
            if (books.learnEpisodeDuration(bookId, duration) && timelineBookId == bookId) timeline = books.timeline(bookId)
        }
    }

    /** Beim Beenden des Service aufrufen. */
    fun finish() {
        loop?.cancel()
        recordCurrent(force = true)
    }

    private fun recordCurrent(force: Boolean) {
        val item = player.currentMediaItem ?: return
        val bookId = item.bookId ?: return
        record(bookId, item.bookTrackIndex ?: 0, player.currentPosition, force)
    }

    private fun record(bookId: Long, trackIndex: Int, positionInTrackMs: Long, force: Boolean) {
        val timeline = timeline.takeIf { timelineBookId == bookId } ?: return
        val position = timeline.toBookPosition(trackIndex, positionInTrackMs)
        // Ohne bekannte Dauer (Folge, deren Länge noch nicht gelernt ist) kann es nicht zu Ende sein.
        val finished = (timeline.durationMs > 0 && position >= timeline.durationMs - FINISHED_TOLERANCE_MS) ||
            (player.playbackState == Player.STATE_ENDED && player.currentMediaItem?.bookId == bookId)
        scope.launch { progressSync.record(bookId, position, timeline.durationMs, finished, force) }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 15_000L

        /** So kurz vor dem Ende gilt ein Buch als zu Ende gehört (Abspann). */
        const val FINISHED_TOLERANCE_MS = 5_000L
    }
}
