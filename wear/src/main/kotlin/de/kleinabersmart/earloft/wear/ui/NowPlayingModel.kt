package de.kleinabersmart.earloft.wear.ui

import android.graphics.Bitmap
import de.kleinabersmart.earloft.core.playback.PlayerState
import kotlinx.coroutines.flow.StateFlow

/**
 * Was die Wiedergabe-Ansicht braucht, damit sie die Uhr selbst ([PlayerViewModel]) oder das Handy
 * ([RemotePlayerViewModel]) bedienen kann.
 */
interface NowPlayingModel {
    val state: StateFlow<PlayerState>

    /** Position in Millisekunden. */
    val positionMs: StateFlow<Long>
    val cover: StateFlow<Bitmap?>

    /** Akzentfarbe des laufenden Albums (ARGB); `null`, solange sie fehlt. */
    val seedColor: StateFlow<Int?>

    /** 0..1 für die Anzeige beim Drehen der Krone. */
    val volume: StateFlow<Float>
    val volumeVisible: StateFlow<Boolean>

    /** Restzeit des Sleep-Timers; `null`, solange keiner läuft (oder er sich hier nicht stellen lässt). */
    val sleepTimerRemainingMs: StateFlow<Long?>

    fun togglePlayPause()

    fun next()

    fun previous()

    fun toggleShuffle()

    /** Buch oder Folge: um die eingestellte Sprungweite zurück bzw. vor ([de.kleinabersmart.earloft.core.playback.SpokenEntry.seek]). */
    fun skipBack()

    fun skipForward()

    fun seekTo(positionMs: Long)

    fun cancelSleepTimer()

    /** Die Krone wurde um [pixels] gedreht: ändert die Lautstärke. */
    fun onCrown(pixels: Float)
}
