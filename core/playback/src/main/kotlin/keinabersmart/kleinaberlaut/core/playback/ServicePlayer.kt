package keinabersmart.kleinaberlaut.core.playback

import androidx.media3.common.Player
import javax.inject.Inject
import javax.inject.Singleton

/** Datei eines Buchs bzw. einer Folge, die Position darin und das Tempo. */
data class BookPlayerPosition(val trackIndex: Int, val positionInTrackMs: Long, val speed: Float)

/**
 * Der Player des laufenden [PlaybackService] für Beobachter im selben Prozess (Widget). Sie brauchen dadurch keinen
 * [PlaybackConnection]-Controller auf den eigenen Service – der hielte ihn gebunden, sodass er nie beendet würde.
 * Nur auf dem Main-Thread benutzen.
 */
@Singleton
class ServicePlayer @Inject constructor() {
    private var player: Player? = null

    internal fun attach(player: Player) {
        this.player = player
    }

    internal fun detach() {
        player = null
    }

    /** Datei und Position darin, solange das Buch [bookId] geladen ist; sonst `null` (auch ohne laufenden Service). */
    fun bookPosition(bookId: Long): BookPlayerPosition? {
        val player = player ?: return null
        val item = player.currentMediaItem?.takeIf { it.bookId == bookId } ?: return null
        return BookPlayerPosition(item.bookTrackIndex ?: 0, player.currentPosition, player.playbackParameters.speed)
    }
}
