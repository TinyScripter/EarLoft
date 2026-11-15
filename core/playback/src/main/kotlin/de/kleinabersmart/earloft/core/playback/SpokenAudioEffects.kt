package de.kleinabersmart.earloft.core.playback

import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Stille kürzen und Lautstärke anheben (Konzept 12.4) am lokalen Player; nur für Bücher und Folgen, Musik spielt ohne.
 * Ein Cast-Empfänger bekommt beides nicht (er spielt die Datei selbst). Die Lautstärke hebt ein [LoudnessEnhancer] an der
 * Audio-Session an; er begrenzt selbst, statt zu übersteuern, und folgt einer neuen Session.
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
            // Manche Geräte bieten den Effekt nicht an; dann bleibt es leise statt abzubrechen.
            Log.w(TAG, "LoudnessEnhancer nicht verfügbar", e)
            // Ein halb eingerichteter Effekt (etwa bei setTargetGain) hält sonst seine native Ressource.
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
