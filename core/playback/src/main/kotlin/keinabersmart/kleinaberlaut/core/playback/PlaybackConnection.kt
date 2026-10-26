package keinabersmart.kleinaberlaut.core.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class QueueEntry(
    val songId: Long,
    val title: String,
    val artist: String,
    val albumId: Long?,
)

data class PlayerState(
    val current: QueueEntry? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
) {
    val hasMedia: Boolean get() = current != null
}

/**
 * Verbindung der App-UI zum [PlaybackService]. Alle Methoden müssen auf dem Main-Thread
 * aufgerufen werden (Vorgabe von [MediaController]).
 */
@Singleton
class PlaybackConnection @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val scope = MainScope()
    private val controller = CompletableDeferred<MediaController>()
    private val _state = MutableStateFlow(PlayerState())

    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Aktuelle Position; für die Fortschrittsanzeige periodisch abfragen. */
    val currentPositionMs: Long
        get() = if (controller.isCompleted) controller.getCompleted().currentPosition else 0

    init {
        scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val mediaController = MediaController.Builder(context, token).buildAsync().await()
            mediaController.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    _state.value = player.toState(
                        previous = _state.value,
                        queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED),
                    )
                }
            })
            _state.value = mediaController.toState(previous = PlayerState(), queueChanged = true)
            controller.complete(mediaController)
        }
    }

    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) = withController {
        if (songs.isEmpty()) return@withController
        shuffleModeEnabled = shuffle
        val index = if (shuffle) songs.indices.random() else startIndex.coerceIn(songs.indices)
        setMediaItems(songs.map { it.toMediaItem() }, index, 0)
        prepare()
        play()
    }

    fun playNext(songs: List<Song>) = withController {
        val position = if (mediaItemCount == 0) 0 else currentMediaItemIndex + 1
        addMediaItems(position, songs.map { it.toMediaItem() })
        if (playbackState == Player.STATE_IDLE) prepare()
    }

    fun addToQueue(songs: List<Song>) = withController {
        addMediaItems(songs.map { it.toMediaItem() })
        if (playbackState == Player.STATE_IDLE) prepare()
    }

    fun togglePlayPause() = withController {
        if (isPlaying) {
            pause()
        } else {
            if (playbackState == Player.STATE_IDLE) prepare()
            play()
        }
    }

    fun pause() = withController { pause() }

    fun next() = withController { seekToNext() }

    fun previous() = withController { seekToPrevious() }

    fun seekTo(positionMs: Long) = withController { seekTo(positionMs) }

    fun skipToQueueItem(index: Int) = withController {
        seekToDefaultPosition(index)
        play()
    }

    fun removeQueueItem(index: Int) = withController { removeMediaItem(index) }

    fun moveQueueItem(from: Int, to: Int) = withController { moveMediaItem(from, to) }

    fun toggleShuffle() = withController { shuffleModeEnabled = !shuffleModeEnabled }

    fun cycleRepeatMode() = withController {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun withController(block: MediaController.() -> Unit) {
        scope.launch { controller.await().block() }
    }

    private fun Player.toState(previous: PlayerState, queueChanged: Boolean): PlayerState {
        val queue = if (queueChanged) (0 until mediaItemCount).mapNotNull { getMediaItemAt(it).toQueueEntry() } else previous.queue
        return PlayerState(
            current = currentMediaItem?.toQueueEntry(),
            isPlaying = isPlaying,
            durationMs = duration.takeIf { it > 0 } ?: currentMediaItem?.mediaMetadata?.durationMs ?: 0,
            shuffle = shuffleModeEnabled,
            repeatMode = repeatMode,
            queue = queue,
            currentIndex = currentMediaItemIndex,
        )
    }

    private fun MediaItem.toQueueEntry(): QueueEntry? {
        val id = songId ?: return null
        return QueueEntry(
            songId = id,
            title = mediaMetadata.title?.toString().orEmpty(),
            artist = mediaMetadata.artist?.toString().orEmpty(),
            albumId = mediaMetadata.albumIdExtra,
        )
    }
}
