package de.kleinabersmart.earloft.core.playback

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import de.kleinabersmart.earloft.core.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "Start mix" from the playback screen of Android Auto and the system's media controls ([ACTION_START_MIX], concept 8 "Mix"):
 * like "Start mix" in the app's now playing menu, the current song keeps playing and the mix replaces whatever comes after it.
 * Shuffle ends (the mix has its own order, Media3 would shuffle it again).
 *
 * Also while casting: removing everything after the current title drops the factory's reserve as well
 * ([SessionPlayerFactory.hasPendingItems]); whatever doesn't fit into the receiver goes back into it.
 *
 * A new tap cancels a mix still being built. If the song changed in the meantime, the result no longer belongs to it and is
 * dropped. [onFailure] reports an empty or failed mix (Android Auto shows it as a message).
 */
internal class SessionMix(
    private val player: Player,
    private val mix: suspend (songId: Long) -> List<Song>,
    private val factory: SessionPlayerFactory?,
    private val scope: CoroutineScope,
    private val onFailure: (Failure) -> Unit,
) {
    enum class Failure { EMPTY, FAILED }

    private var job: Job? = null

    /** Main thread. `false` if no song is playing (books, episodes, empty queue). */
    fun start(): Boolean {
        val seed = player.currentMediaItem?.songId ?: return false
        job?.cancel()
        job = scope.launch {
            val found = try {
                mix(seed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Mix from song $seed failed", e)
                onFailure(Failure.FAILED)
                return@launch
            }
            // The starting song is playing already; it is the mix's first title.
            val upcoming = found.filter { it.id != seed }
            val playable = factory?.playableSongIds(player, upcoming.map { it.id }) ?: upcoming.mapTo(HashSet()) { it.id }
            val items = upcoming.filter { it.id in playable }.map { it.toMediaItem() }
            if (player.currentMediaItem?.songId != seed) return@launch
            if (items.isEmpty()) {
                onFailure(Failure.EMPTY)
                return@launch
            }
            replaceUpcoming(items)
            Log.d(TAG, "Mix after song $seed: ${items.size} tracks")
        }
        return true
    }

    private fun replaceUpcoming(items: List<MediaItem>) {
        player.shuffleModeEnabled = false
        val next = player.currentMediaItemIndex + 1
        // Also with nothing after the current title in the player: while casting the reserve can still hold titles, and clearing to
        // the end drops them (DeferredRemotePlayPlayer), otherwise they would play before the mix.
        if (next < player.mediaItemCount || factory?.hasPendingItems() == true) {
            player.removeMediaItems(next, player.mediaItemCount)
        }
        val room = factory.roomIn(player)
        player.addMediaItems(items.take(room))
        if (items.size > room) factory?.appendPending(items.drop(room))
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    private companion object {
        const val TAG = "SessionMix"
    }
}
