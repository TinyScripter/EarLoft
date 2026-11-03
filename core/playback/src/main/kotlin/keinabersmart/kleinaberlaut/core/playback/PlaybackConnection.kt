package keinabersmart.kleinaberlaut.core.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.data.podcast.ContinueListening
import keinabersmart.kleinaberlaut.core.data.spoken.BookPlaybackSource
import keinabersmart.kleinaberlaut.core.data.spoken.PreparedBook
import keinabersmart.kleinaberlaut.core.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class QueueEntry(
    val songId: Long,
    val title: String,
    val artist: String,
    val albumId: Long?,
    val albumTitle: String = "",
)

/** Das laufende Hörbuch (Konzept 12); die Position im Buch rechnet die UI über die Zeitachse aus. */
data class SpokenEntry(
    val bookId: Long,
    val trackIndex: Int,
    val title: String,
    val author: String,
)

data class PlayerState(
    val current: QueueEntry? = null,
    /** Statt [current], wenn ein Hörbuch läuft. */
    val spoken: SpokenEntry? = null,
    val speed: Float = 1f,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    /** Wiedergabe angehalten, weil die Ausgabe nicht passt (Uhr ohne Kopfhörer, Wear-Konzept 9). */
    val outputSuppressed: Boolean = false,
    /** Wiedergabe auf einem anderen Gerät (Chromecast). */
    val isRemote: Boolean = false,
) {
    val hasMedia: Boolean get() = current != null || spoken != null
}

/**
 * Verbindung der App-UI zum [PlaybackService]. Alle Methoden müssen auf dem Main-Thread
 * aufgerufen werden (Vorgabe von [MediaController]).
 *
 * Verbunden wird erst, wenn jemand [state] beobachtet oder den Player steuert – nicht schon beim Erzeugen. Sonst
 * startete jeder Prozessstart (Hintergrund-Job, Nachricht der Uhr) den Service samt Player und Wiederherstellen.
 * Beobachtet niemand mehr und läuft kein Befehl, wird der Controller nach [RELEASE_DELAY_MS] freigegeben: Ein gebundener
 * Service würde sonst nie beendet, auch wenn nichts mehr spielt und die App längst im Hintergrund ist. [state] behält
 * bis zur nächsten Verbindung den letzten Stand.
 */
@Singleton
class PlaybackConnection @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sleepTimer: SleepTimer,
    private val bookPlaybackSource: BookPlaybackSource,
    private val continueListening: ContinueListening,
) {
    private val scope = MainScope()

    /** Die laufende bzw. aufgebaute Verbindung; `null`, solange keine besteht. Nur auf dem Main-Thread. */
    private var connection: CompletableDeferred<MediaController>? = null

    /** Befehle, die gerade auf den Controller warten oder ihn benutzen; solange > 0, wird nicht freigegeben. */
    private var commandsInFlight = 0
    private var releaseJob: Job? = null
    private val _state = MutableStateFlow(PlayerState())

    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _positionJumps = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Meldet Sprünge der Position (Spulen, Titelwechsel), die der [state] nicht abbildet. */
    val positionJumps: Flow<Unit> = _positionJumps.asSharedFlow()

    /** Aktuelle Position (0 ohne Verbindung); für die Fortschrittsanzeige periodisch abfragen, solange [state] beobachtet wird. */
    val currentPositionMs: Long
        get() = connectedController()?.currentPosition ?: 0

    init {
        scope.launch {
            _state.subscriptionCount.map { it > 0 }.distinctUntilChanged().collect { observed ->
                if (observed) connect() else scheduleRelease()
            }
        }
    }

    private fun connectedController(): MediaController? =
        connection?.takeIf { it.isCompleted && !it.isCancelled }?.getCompleted()

    /** Baut den [MediaController] auf, falls keine Verbindung besteht oder entsteht; nur auf dem Main-Thread. */
    private fun connect(): CompletableDeferred<MediaController> {
        releaseJob?.cancel()
        releaseJob = null
        connection?.let { return it }
        val pending = CompletableDeferred<MediaController>()
        connection = pending
        scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val mediaController = try {
                MediaController.Builder(context, token)
                    .setListener(object : MediaController.Listener {
                        // Session weg (Service beendet): neu verbinden, solange jemand beobachtet, sonst beim nächsten Zugriff.
                        override fun onDisconnected(controller: MediaController) {
                            if (connection !== pending) return
                            connection = null
                            if (_state.subscriptionCount.value > 0) connect()
                        }
                    })
                    .buildAsync()
                    .await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Keine Verbindung zum Wiedergabe-Service", e)
                if (connection === pending) connection = null
                pending.completeExceptionally(e)
                return@launch
            }
            if (connection !== pending) {
                // Inzwischen freigegeben.
                mediaController.release()
                pending.cancel()
                return@launch
            }
            mediaController.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) _positionJumps.tryEmit(Unit)
                    _state.value = player.toState(
                        previous = _state.value,
                        queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED),
                    )
                }
            })
            _state.value = mediaController.toState(previous = PlayerState(), queueChanged = true)
            pending.complete(mediaController)
        }
        return pending
    }

    /** Gibt den Controller nach [RELEASE_DELAY_MS] frei, wenn bis dahin niemand beobachtet und kein Befehl läuft. */
    private fun scheduleRelease() {
        if (_state.subscriptionCount.value > 0 || commandsInFlight > 0 || connection == null) return
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(RELEASE_DELAY_MS)
            if (_state.subscriptionCount.value > 0 || commandsInFlight > 0) return@launch
            val released = connection ?: return@launch
            connection = null
            releaseJob = null
            if (released.isCompleted && !released.isCancelled) released.getCompleted().release()
            // Entsteht die Verbindung noch, gibt der Aufbau sie selbst frei (connection !== pending).
        }
    }

    /** Führt [block] mit verbundenem Controller aus; danach darf freigegeben werden, wenn niemand mehr beobachtet. */
    private suspend fun <T> useController(block: suspend MediaController.() -> T): T? {
        commandsInFlight++
        try {
            val controller = try {
                connect().await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null // schon geloggt
            }
            return controller.block()
        } finally {
            commandsInFlight--
            scheduleRelease()
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

    /**
     * Startet ein Hörbuch an der gespeicherten Stelle (oder bei [startPositionMs], z. B. einem Kapitel), nachdem der
     * Fortschritt mit dem Server abgeglichen ist.
     * `null`, wenn das Buch nicht zu laden ist (kein Netz und noch nie geladen).
     */
    suspend fun playBook(bookId: Long, startPositionMs: Long? = null): PreparedBook? {
        // Eine Podcast-Folge steht sofort ganz vorn in „Weiterhören“, auch wenn sie sich nicht laden lässt.
        continueListening.started(bookId)
        val prepared = bookPlaybackSource.prepare(bookId) ?: return null
        // Der MediaController darf nur vom Main-Thread angesprochen werden.
        withContext(Dispatchers.Main.immediate) {
            useController { playBook(prepared, startPositionMs) }
        }
        return prepared
    }

    fun skipBack() = withController { seekBack() }

    fun skipForward() = withController { seekForward() }

    /** Springt in Datei [trackIndex] an die Stelle [positionMs] (Position in der Datei). */
    fun seekToTrack(trackIndex: Int, positionMs: Long) = withController { seekTo(trackIndex, positionMs) }

    fun setSpeed(speed: Float) = withController { setPlaybackSpeed(speed) }

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
            // Ende der Warteschlange: Ohne Zurücksetzen würde play() nichts tun.
            if (playbackState == Player.STATE_ENDED) seekToDefaultPosition()
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

    /** Restzeit des Sleep-Timers in Millisekunden, jede Sekunde neu; `null`, solange keiner läuft. */
    val sleepTimerRemainingMs: Flow<Long?> get() = sleepTimer.remainingMs

    fun startSleepTimer(minutes: Int) = sleepTimer.start(minutes * 60_000L)

    fun cancelSleepTimer() = sleepTimer.cancel()

    private fun withController(block: MediaController.() -> Unit) {
        scope.launch { useController { block() } }
    }

    private fun Player.toState(previous: PlayerState, queueChanged: Boolean): PlayerState {
        val queue = if (queueChanged) (0 until mediaItemCount).mapNotNull { getMediaItemAt(it).toQueueEntry() } else previous.queue
        return PlayerState(
            current = currentMediaItem?.toQueueEntry(),
            spoken = currentMediaItem?.toSpokenEntry(),
            speed = playbackParameters.speed,
            isPlaying = isPlaying,
            durationMs = duration.takeIf { it > 0 } ?: currentMediaItem?.mediaMetadata?.durationMs ?: 0,
            shuffle = shuffleModeEnabled,
            repeatMode = repeatMode,
            queue = queue,
            currentIndex = currentMediaItemIndex,
            outputSuppressed = playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT,
            isRemote = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE,
        )
    }

    private fun MediaItem.toSpokenEntry(): SpokenEntry? {
        val id = bookId ?: return null
        return SpokenEntry(
            bookId = id,
            trackIndex = bookTrackIndex ?: 0,
            title = mediaMetadata.title?.toString().orEmpty(),
            author = mediaMetadata.artist?.toString().orEmpty(),
        )
    }

    private fun MediaItem.toQueueEntry(): QueueEntry? {
        val id = songId ?: return null
        return QueueEntry(
            songId = id,
            title = mediaMetadata.title?.toString().orEmpty(),
            artist = mediaMetadata.artist?.toString().orEmpty(),
            albumId = mediaMetadata.albumIdExtra,
            albumTitle = mediaMetadata.albumTitle?.toString().orEmpty(),
        )
    }

    private companion object {
        const val TAG = "PlaybackConnection"

        /**
         * So lange bleibt der Controller nach dem letzten Beobachter bzw. Befehl verbunden. Deckt kurze Lücken ab
         * (Drehen, kurzer Wechsel in eine andere App; die ViewModels halten ihrerseits 5 s), ohne den Service lange zu binden.
         */
        const val RELEASE_DELAY_MS = 10_000L
    }
}
