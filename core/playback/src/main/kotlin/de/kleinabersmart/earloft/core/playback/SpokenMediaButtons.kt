package de.kleinabersmart.earloft.core.playback

import android.os.Handler
import android.view.KeyEvent

/**
 * Kopfhörer- und Medientasten bei Büchern und Folgen (Konzept 12.4): Doppelklick und „Weiter“ springen zum nächsten
 * Kapitel bzw. zur nächsten Folge, „Zurück“ zum Kapitelanfang bzw. ins vorige ([next], [previous]).
 *
 * Media3 erkennt den Doppelklick selbst, macht daraus aber „nächstes Item“ – das hat eine einzelne Folge oder m4b nicht,
 * und Media3 verwirft die Taste. Deshalb übernimmt diese Klasse die Tasten, solange ein Buch oder eine Folge läuft
 * ([isSpoken]), und erkennt den Doppelklick wie Media3: nur für Tasten von außen ([onMediaButton] `external`, etwa
 * Bluetooth), ein einzelner Klick wartet [doubleTapTimeoutMs] auf den zweiten. Musik bleibt bei Media3.
 */
internal class SpokenMediaButtons(
    private val handler: Handler,
    private val doubleTapTimeoutMs: Long,
    private val isSpoken: () -> Boolean,
    private val playPause: () -> Unit,
    private val next: () -> Unit,
    private val previous: () -> Unit,
) {
    private var pendingPlayPause: Runnable? = null

    /** `true`: hier erledigt; `false`: Media3 macht weiter. Nur auf dem Main-Thread. */
    fun onMediaButton(event: KeyEvent, external: Boolean): Boolean {
        if (!isSpoken()) {
            flush()
            return false
        }
        val down = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                if (down) {
                    flush()
                    next()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                if (down) {
                    flush()
                    previous()
                }
                return true
            }
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                // Aus der App, der Benachrichtigung oder lange gedrückt: wie bisher.
                if (!external || event.repeatCount > 0) {
                    flush()
                    return false
                }
                if (event.action != KeyEvent.ACTION_DOWN) return true
                val pending = pendingPlayPause
                if (pending != null) {
                    handler.removeCallbacks(pending)
                    pendingPlayPause = null
                    next()
                } else {
                    val task = Runnable {
                        pendingPlayPause = null
                        playPause()
                    }
                    pendingPlayPause = task
                    handler.postDelayed(task, doubleTapTimeoutMs)
                }
                return true
            }
            else -> {
                flush()
                return false
            }
        }
    }

    /** Eine andere Taste kommt: ein wartender Einzelklick gilt sofort, damit die Reihenfolge stimmt. */
    private fun flush() {
        val pending = pendingPlayPause ?: return
        handler.removeCallbacks(pending)
        pendingPlayPause = null
        pending.run()
    }
}
