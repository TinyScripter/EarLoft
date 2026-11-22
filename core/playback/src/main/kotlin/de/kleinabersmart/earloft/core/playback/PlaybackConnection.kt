package de.kleinabersmart.earloft.core.playback

import androidx.media3.common.Player
import de.kleinabersmart.earloft.core.data.spoken.PreparedBook
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps
import de.kleinabersmart.earloft.core.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Cycle repeat: off → all → one → off (`Player.REPEAT_MODE_*`). */
fun nextRepeatMode(mode: Int): Int = when (mode) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
}

data class QueueEntry(
    val songId: Long,
    val title: String,
    val artist: String,
    val albumId: Long?,
    val albumTitle: String = "",
)

/**
 * The saved music queue while a book or episode is loaded: [entries] as loaded on resume
 * (songs that no longer exist are missing), [index] the title at which the music would continue.
 */
data class SavedMusicQueue(val entries: List<QueueEntry>, val index: Int)

/** The current audiobook (concept 12); the UI computes the position in the book via the timeline. */
data class SpokenEntry(
    val bookId: Long,
    val trackIndex: Int,
    val title: String,
    val author: String,
    /** Skip distances of the seek buttons, from the area's settings (books or podcasts). */
    val seek: SeekSteps = SeekSteps.DEFAULT,
    /** A podcast episode instead of a book. */
    val isEpisode: Boolean = false,
)

data class PlayerState(
    val current: QueueEntry? = null,
    /** Instead of [current] while an audiobook is playing. */
    val spoken: SpokenEntry? = null,
    val speed: Float = 1f,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    /**
     * Indices into [queue] of the titles still to come after the current one, in the order the player will play them (the
     * shuffled order with [shuffle]; without the wrap of repeat). While casting only the window the cast player holds.
     */
    val laterIndices: List<Int> = emptyList(),
    /** Playback paused because the output isn't suitable (watch without headphones, Wear concept 9). */
    val outputSuppressed: Boolean = false,
    /** Playback on another device (Chromecast). */
    val isRemote: Boolean = false,
    /** Volume of the cast device in steps from 0 to [maxDeviceVolume]; only meaningful if [isRemote]. */
    val deviceVolume: Int = 0,
    val maxDeviceVolume: Int = 0,
) {
    val hasMedia: Boolean get() = current != null || spoken != null
}

/**
 * Connection of the app UI to playback. All methods must be called on the main thread (requirement of
 * `MediaController`). Implemented by [MediaPlaybackConnection]; an interface so that ViewModels are testable
 * with a fake (review A3).
 */
interface PlaybackConnection {

    val state: StateFlow<PlayerState>

    /** Reports position jumps (seeking, title changes) that [state] doesn't reflect. */
    val positionJumps: Flow<Unit>

    /**
     * Playback starts: from paused to playing (not the end of buffering), or another title or queue chosen while playing (a
     * skip, a new album), but not the next title following on by itself. The mini player comes back then (review H28).
     */
    val playbackStarts: Flow<Unit>

    /** Current position (0 without a connection); poll periodically for the progress display while [state] is observed. */
    val currentPositionMs: Long

    /** Remaining time of the sleep timer in milliseconds, updated every second; `null` while none is running. */
    val sleepTimerRemainingMs: Flow<Long?>

    /** The sleep timer runs until the end of the chapter or episode instead of a fixed duration. */
    val sleepTimerToEnd: Flow<Boolean>

    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false)

    /**
     * Voice search ("Play … on EarLoft"): an item without a media ID with [query] as search term; the session picks the
     * songs (`LibrarySessionCallback.voiceSongs`, empty term = shuffled favorites), as with Android Auto. `false` if the request
     * finds nothing: then nothing is played and the queue stays as it is.
     */
    suspend fun playFromSearch(query: String): Boolean

    /**
     * Starts an audiobook at the saved position (or at [startPositionMs], e.g. a chapter) after the
     * progress has been synced with the server.
     * `null` if the book can't be loaded (no network and never downloaded).
     */
    suspend fun playBook(bookId: Long, startPositionMs: Long? = null): PreparedBook?

    /**
     * Resumes the saved music queue at its position, thereby replacing a loaded book or episode
     * (music-only widget, "Queue" row on the music home); without a saved queue nothing. With [index]
     * (position in [SavedMusicQueue.entries]) it starts at that title from the beginning, unless it is the saved one.
     */
    fun resumeMusic(index: Int? = null)

    /**
     * The saved music queue while no song is loaded (a book or episode or nothing at all, e.g. after
     * a book ended while casting); with a song (and without a saved queue) `null`.
     */
    val savedMusicQueue: Flow<SavedMusicQueue?>

    fun skipBack()

    fun skipForward()

    /** Seeks in file [trackIndex] to [positionMs] (position within the file). */
    fun seekToTrack(trackIndex: Int, positionMs: Long)

    /**
     * Plays [songs] after the current title. If the player holds a book or episode, they go behind the
     * saved title of the music queue instead (see [queuedForMusic]); the spoken content keeps playing unchanged.
     */
    fun playNext(songs: List<Song>)

    /** Appends [songs] to the queue; with a book or episode to the saved music queue (like [playNext]). */
    fun addToQueue(songs: List<Song>)

    /** Number of songs that [playNext] or [addToQueue] put into the saved music queue because spoken content was playing. */
    val queuedForMusic: Flow<Int>

    /** The current title keeps playing, everything after it gives way to [songs] (mix from now playing); without a queue like [play]. */
    fun replaceUpcoming(songs: List<Song>)

    fun togglePlayPause()

    fun pause()

    fun next()

    fun previous()

    fun seekTo(positionMs: Long)

    fun skipToQueueItem(index: Int)

    fun removeQueueItem(index: Int)

    fun moveQueueItem(from: Int, to: Int)

    /** "Clear queue": everything except the current title leaves the queue; playback continues. */
    fun clearQueue()

    fun toggleShuffle()

    fun cycleRepeatMode()

    /** Changes the cast device's volume by one step; nothing without cast. */
    fun changeRemoteVolume(up: Boolean)

    fun startSleepTimer(minutes: Int)

    /** Sleep timer until the end of the chapter (books with chapters), the episode, the book or the song. */
    fun startSleepTimerToEnd()

    fun cancelSleepTimer()
}
