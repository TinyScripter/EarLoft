package de.kleinabersmart.earloft.core.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Optional replacement for the session's player. The phone app uses it to wrap Chromecast around the local
 * ExoPlayer; without a binding (watch) the session plays the ExoPlayer directly.
 */
interface SessionPlayerFactory {

    /** Main thread. The returned player takes over [localPlayer], also on `release()`. */
    fun create(context: Context, localPlayer: ExoPlayer): Player

    /**
     * Before songs from a controller enter the queue: those that [player] can currently play
     * (e.g. without purely local songs while casting). Main thread, may suspend.
     */
    suspend fun playableSongIds(player: Player, songIds: Collection<Long>): Set<Long> = songIds.toSet()

    /**
     * Maximum number of titles [player] holds in total (cast: size of one message, including the status); see [queueWindow] and
     * [roomIn]. Anything beyond that the factory holds back and hands over later ([onQueueReplaced], [appendPending]).
     */
    fun maxQueueSize(player: Player): Int = Int.MAX_VALUE

    /**
     * Main thread. A new queue was set; what didn't fit is in [before] (before the
     * window) and [after] (after it). Both empty if everything fit.
     */
    fun onQueueReplaced(before: List<MediaItem>, after: List<MediaItem>) = Unit

    /** Main thread. If titles are still held back, new titles belong at their end ([appendPending]) instead of directly in the player. */
    fun hasPendingItems(): Boolean = false

    /** Main thread. Appends [items] after the held-back titles. */
    fun appendPending(items: List<MediaItem>) = Unit

    /**
     * Main thread. The whole queue in its order while titles are held back (before and after the player's window), e.g. for
     * saving it as a playlist; `null` if the player holds the whole queue.
     */
    fun fullQueue(): List<MediaItem>? = null

    /**
     * Main thread. [removeFromPlayer] removes titles whose song no longer exists ([isMissing]) from the player. That is no clearing of
     * the queue by the user: the held-back titles stay, only the missing ones among them go too (review4 8.6 Y9).
     */
    fun removeMissing(isMissing: (MediaItem) -> Boolean, removeFromPlayer: () -> Unit) = removeFromPlayer()

    /**
     * Main thread. The service ends and has released the player from [create]: the factory lets go of it and of everything that
     * still listens for it (review4 T13); the next [create] starts afresh.
     */
    fun release() = Unit
}

/** How many more titles [player] can take ([SessionPlayerFactory.maxQueueSize] minus the existing ones). */
fun SessionPlayerFactory?.roomIn(player: Player): Int {
    val max = this?.maxQueueSize(player) ?: Int.MAX_VALUE
    return if (max == Int.MAX_VALUE) max else (max - player.mediaItemCount).coerceAtLeast(0)
}
