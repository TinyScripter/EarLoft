package de.kleinabersmart.earloft.core.playback

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

/** File of a book or episode, the position in it and the speed. */
data class BookPlayerPosition(val trackIndex: Int, val positionInTrackMs: Long, val speed: Float)

/**
 * The player of the running [PlaybackService] for observers in the same process (widget). That way they don't need a
 * [PlaybackConnection] controller to their own service – it would keep it bound so that it never ends.
 * Only use on the main thread.
 */
@Singleton
class ServicePlayer @Inject constructor() {
    private val player = MutableStateFlow<Player?>(null)
    private var finisher: ((bookId: Long, playNext: Boolean) -> Boolean)? = null

    internal fun attach(player: Player, finisher: (bookId: Long, playNext: Boolean) -> Boolean) {
        this.player.value = player
        this.finisher = finisher
    }

    internal fun detach() {
        player.value = null
        finisher = null
    }

    /**
     * "Mark as played" for the loaded book or episode [bookId]: the player pauses (or, with
     * [playNext], plays the next episode of the queue) and doesn't overwrite the state again. `false` if [bookId]
     * is not loaded or no service is running; then writing the state is enough.
     */
    fun markFinished(bookId: Long, playNext: Boolean): Boolean = finisher?.invoke(bookId, playNext) ?: false

    /** File and position in it while the book [bookId] is loaded; otherwise `null` (also without a running service). */
    fun bookPosition(bookId: Long): BookPlayerPosition? {
        val player = player.value ?: return null
        val item = player.currentMediaItem?.takeIf { it.bookId == bookId } ?: return null
        return BookPlayerPosition(item.bookTrackIndex ?: 0, player.currentPosition, player.playbackParameters.speed)
    }

    /** Position jumps (seeking, file changes) and speed changes; nothing without a running service. */
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
