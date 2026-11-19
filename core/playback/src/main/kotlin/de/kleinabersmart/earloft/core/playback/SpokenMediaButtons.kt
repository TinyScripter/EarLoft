package de.kleinabersmart.earloft.core.playback

import android.os.Handler
import android.view.KeyEvent

/**
 * Headphone and media buttons for books and episodes (concept 12.4): double click and "next" jump to the next
 * chapter or the next episode, "previous" to the chapter start or the previous one ([next], [previous]).
 *
 * Media3 detects the double click itself, but turns it into "next item" – which a single episode or m4b doesn't have,
 * and Media3 discards the key. That's why this class takes over the keys while a book or episode is playing
 * ([isSpoken]) and detects the double click like Media3: only for external keys ([onMediaButton] `external`, e.g.
 * Bluetooth), a single click waits [doubleTapTimeoutMs] for the second one. Music stays with Media3.
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

    /** `true`: handled here; `false`: Media3 carries on. Main thread only. */
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
                // From the app, the notification or long-pressed: as before.
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

    /** Another key arrives: a pending single click takes effect immediately so the order is right. */
    private fun flush() {
        val pending = pendingPlayPause ?: return
        handler.removeCallbacks(pending)
        pendingPlayPause = null
        pending.run()
    }
}
