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
 * Die Audio-Session des Players für Equalizer (Konzept 7 „Equalizer“): Der Dienst meldet sie beim System an und ab
 * (`ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION`), damit der System-Equalizer bzw. eine Equalizer-App sie findet;
 * die App öffnet über [sessionId] deren Einstellungen. Einen eigenen Equalizer gibt es nicht. Nur im Speicher, App und Dienst
 * laufen im selben Prozess.
 */
@OptIn(UnstableApi::class)
@Singleton
class AudioEffectSession @Inject constructor() {
    private val current = MutableStateFlow<Int?>(null)

    /** `null`, solange der Dienst nicht läuft. */
    val sessionId: StateFlow<Int?> = current

    /** Meldet die Session von [player] an und folgt ihr, bis [close]. */
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
        /** Öffnet die Einstellungen des Equalizers für [sessionId]; mit `startActivityForResult` starten, sonst kennt er den Absender nicht. */
        fun controlPanelIntent(context: Context, sessionId: Int): Intent =
            intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL, context, sessionId)

        private fun intent(action: String, context: Context, sessionId: Int): Intent = Intent(action)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
}
