package de.kleinabersmart.kleinaberlaut.core.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
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
    private val player = MutableStateFlow<Player?>(null)

    internal fun attach(player: Player) {
        this.player.value = player
    }

    internal fun detach() {
        player.value = null
    }

    /** Datei und Position darin, solange das Buch [bookId] geladen ist; sonst `null` (auch ohne laufenden Service). */
    fun bookPosition(bookId: Long): BookPlayerPosition? {
        val player = player.value ?: return null
        val item = player.currentMediaItem?.takeIf { it.bookId == bookId } ?: return null
        return BookPlayerPosition(item.bookTrackIndex ?: 0, player.currentPosition, player.playbackParameters.speed)
    }

    /** Sprünge der Position (Spulen, Dateiwechsel) und Tempowechsel; ohne laufenden Service nichts. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun timingChanges(): Flow<Unit> = player.flatMapLatest { player ->
        if (player == null) {
            emptyFlow()
        } else {
            callbackFlow {
                val listener = object : Player.Listener {
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        trySend(Unit)
                    }

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        trySend(Unit)
                    }
                }
                player.addListener(listener)
                awaitClose { player.removeListener(listener) }
            }
        }
    }.flowOn(Dispatchers.Main.immediate)
}
