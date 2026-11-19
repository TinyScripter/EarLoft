package de.kleinabersmart.earloft.core.playback

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The player's audio session for equalizers (concept 7 "Equalizer"): the service registers and unregisters it with the system
 * (`ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION`) so the system equalizer or an equalizer app can find it;
 * the app opens its settings via [sessionId]. There is no built-in equalizer. In memory only; app and service
 * run in the same process.
 */
@OptIn(UnstableApi::class)
@Singleton
class AudioEffectSession @Inject constructor() {
    private val current = MutableStateFlow<Int?>(null)

    /** `null` while the service is not running. */
    val sessionId: StateFlow<Int?> = current

    /** Registers the session of [player] and follows it until [close]. */
    internal fun attach(context: Context, player: ExoPlayer) {
        open(context, player.audioSessionId)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId == current.value) return
                close(context)
                open(context, audioSessionId)
            }
        })
    }

    internal fun close(context: Context) {
        val id = current.value ?: return
        context.sendBroadcast(intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, context, id))
        current.value = null
    }

    private fun open(context: Context, id: Int) {
        if (id == C.AUDIO_SESSION_ID_UNSET) return
        context.sendBroadcast(intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, context, id))
        current.value = id
    }

    companion object {
        /** Opens the equalizer settings for [sessionId]; start with `startActivityForResult`, otherwise it doesn't know the caller. */
        fun controlPanelIntent(context: Context, sessionId: Int): Intent =
            intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL, context, sessionId)

        private fun intent(action: String, context: Context, sessionId: Int): Intent = Intent(action)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
}
