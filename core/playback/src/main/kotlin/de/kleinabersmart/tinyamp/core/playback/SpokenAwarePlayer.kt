package de.kleinabersmart.tinyamp.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import de.kleinabersmart.tinyamp.core.data.spoken.SeekSteps

/**
 * Bei Hörbüchern und Folgen gelten andere Sprungweiten als bei Musik, einstellbar je Bereich (Konzept 12.4). Media3
 * kennt pro Player nur ein Paar; deshalb beantwortet dieser Wrapper [getSeekBackIncrement], [getSeekForwardIncrement],
 * [seekBack] und [seekForward] je nach aktuellem Item. Benachrichtigung, Android Auto, Widget und App sprechen alle mit
 * der Session und damit mit diesem Player.
 */
@OptIn(UnstableApi::class)
internal class SpokenAwarePlayer(player: Player) : ForwardingPlayer(player) {

    /** Aus den Einstellungen; nur auf dem Main-Thread setzen. */
    var bookSteps: SeekSteps = SeekSteps.DEFAULT
    var podcastSteps: SeekSteps = SeekSteps.DEFAULT

    /** Sprungweiten des aktuellen Items; `null` bei Musik. */
    val currentSteps: SeekSteps?
        get() {
            val item = currentMediaItem?.takeIf { it.isSpoken } ?: return null
            return if (item.isEpisode) podcastSteps else bookSteps
        }

    override fun getSeekBackIncrement(): Long = currentSteps?.backMs ?: super.getSeekBackIncrement()

    override fun getSeekForwardIncrement(): Long = currentSteps?.forwardMs ?: super.getSeekForwardIncrement()

    override fun seekBack() {
        currentSteps?.let { seekByMs(-it.backMs) } ?: super.seekBack()
    }

    override fun seekForward() {
        currentSteps?.let { seekByMs(it.forwardMs) } ?: super.seekForward()
    }

    /** Springt auch über Dateigrenzen hinweg (Hörbuch aus mehreren MP3-Dateien). */
    private fun seekByMs(deltaMs: Long) {
        val target = currentPosition + deltaMs
        val duration = duration
        when {
            target < 0 && hasPreviousMediaItem() -> {
                val previousDuration = getMediaItemAt(currentMediaItemIndex - 1).mediaMetadata.durationMs
                if (previousDuration != null) {
                    seekTo(currentMediaItemIndex - 1, (previousDuration + target).coerceAtLeast(0))
                } else {
                    seekTo(0)
                }
            }
            duration != C.TIME_UNSET && target > duration && hasNextMediaItem() ->
                seekTo(currentMediaItemIndex + 1, target - duration)
            else -> seekTo(target.coerceIn(0, duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE))
        }
    }
}
