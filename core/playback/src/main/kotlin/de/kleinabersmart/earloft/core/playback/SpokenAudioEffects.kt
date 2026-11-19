package de.kleinabersmart.earloft.core.playback

import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Skip silence and volume boost (concept 12.4) on the local player; only for books and episodes, music plays without.
 * A cast receiver gets neither (it plays the file itself). The volume is boosted by a [LoudnessEnhancer] on the
 * audio session; it limits itself instead of clipping, and follows a new session.
 */
@OptIn(UnstableApi::class)
internal class SpokenAudioEffects(private val player: ExoPlayer) {
    private var enhancer: LoudnessEnhancer? = null
    private var enhancerSession = C.AUDIO_SESSION_ID_UNSET
    private var boostDb = 0

    init {
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (boostDb > 0) applyBoost()
            }
        })
    }

    fun apply(skipSilence: Boolean, volumeBoostDb: Int) {
        if (player.skipSilenceEnabled != skipSilence) player.skipSilenceEnabled = skipSilence
        if (volumeBoostDb == boostDb) return
        boostDb = volumeBoostDb
        applyBoost()
    }

    private fun applyBoost() {
        if (boostDb <= 0) {
            enhancer?.enabled = false
            return
        }
        val session = player.audioSessionId
        if (session == C.AUDIO_SESSION_ID_UNSET) return
        try {
            val current = enhancer?.takeIf { enhancerSession == session } ?: run {
                enhancer?.release()
                LoudnessEnhancer(session).also {
                    enhancer = it
                    enhancerSession = session
                }
            }
            current.setTargetGain(boostDb * MILLIBEL_PER_DB)
            current.enabled = true
        } catch (e: RuntimeException) {
            // Some devices don't offer the effect; then it stays quiet instead of failing.
            Log.w(TAG, "LoudnessEnhancer unavailable", e)
            // A half-configured effect (e.g. at setTargetGain) would otherwise hold on to its native resource.
            runCatching { enhancer?.release() }
            enhancer = null
            enhancerSession = C.AUDIO_SESSION_ID_UNSET
        }
    }

    fun release() {
        enhancer?.release()
        enhancer = null
    }

    private companion object {
        const val TAG = "SpokenAudioEffects"
        const val MILLIBEL_PER_DB = 100
    }
}
