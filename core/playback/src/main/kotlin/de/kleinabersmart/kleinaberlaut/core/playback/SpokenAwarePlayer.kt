package de.kleinabersmart.kleinaberlaut.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Springen bei Hörbüchern: −30 s zurück, +10 s vor (Konzept 12.4). */
const val SPOKEN_SEEK_BACK_MS = 30_000L
const val SPOKEN_SEEK_FORWARD_MS = 10_000L

/**
 * Bei Hörbüchern gelten andere Sprungweiten als bei Musik. Media3 kennt pro Player nur ein Paar; deshalb beantwortet
 * dieser Wrapper [getSeekBackIncrement], [getSeekForwardIncrement], [seekBack] und [seekForward] je nach aktuellem
 * Item. Benachrichtigung, Android Auto, Widget und App sprechen alle mit der Session und damit mit diesem Player.
 */
@OptIn(UnstableApi::class)
internal class SpokenAwarePlayer(player: Player) : ForwardingPlayer(player) {

    private val isSpokenNow: Boolean get() = currentMediaItem?.isSpoken == true

    override fun getSeekBackIncrement(): Long = if (isSpokenNow) SPOKEN_SEEK_BACK_MS else super.getSeekBackIncrement()

    override fun getSeekForwardIncrement(): Long = if (isSpokenNow) SPOKEN_SEEK_FORWARD_MS else super.getSeekForwardIncrement()

    override fun seekBack() {
        if (isSpokenNow) seekByMs(-SPOKEN_SEEK_BACK_MS) else super.seekBack()
    }

    override fun seekForward() {
        if (isSpokenNow) seekByMs(SPOKEN_SEEK_FORWARD_MS) else super.seekForward()
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
