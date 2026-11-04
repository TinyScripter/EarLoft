package keinabersmart.kleinaberlaut.core.playback

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import keinabersmart.kleinaberlaut.core.data.podcast.ContinueListening
import keinabersmart.kleinaberlaut.core.data.spoken.BookPlaybackSource
import keinabersmart.kleinaberlaut.core.data.spoken.PreparedBook
import keinabersmart.kleinaberlaut.core.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PlaybackConnection] über einen [MediaController] zum [PlaybackService]. Nur über [PlaybackConnection] verwenden; öffentlich
 * nur wegen [PlaybackConnectionBindings].
 *
 * Verbunden wird erst, wenn jemand [state] beobachtet oder den Player steuert – nicht schon beim Erzeugen. Sonst
 * startete jeder Prozessstart (Hintergrund-Job, Nachricht der Uhr) den Service samt Player und Wiederherstellen.
 * Beobachtet niemand mehr und läuft kein Befehl, wird der Controller nach [RELEASE_DELAY_MS] freigegeben: Ein gebundener
 * Service würde sonst nie beendet, auch wenn nichts mehr spielt und die App längst im Hintergrund ist. [state] behält
 * bis zur nächsten Verbindung den letzten Stand.
 */
@Singleton
class MediaPlaybackConnection @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sleepTimer: SleepTimer,
    private val bookPlaybackSource: BookPlaybackSource,
    private val continueListening: ContinueListening,
) : PlaybackConnection {
    private val scope = MainScope()

    /** Die laufende bzw. aufgebaute Verbindung; `null`, solange keine besteht. Nur auf dem Main-Thread. */
    private var connection: CompletableDeferred<MediaController>? = null

    /** Befehle, die gerade auf den Controller warten oder ihn benutzen; solange > 0, wird nicht freigegeben. */
    private var commandsInFlight = 0
    private var releaseJob: Job? = null
    private val _state = MutableStateFlow(PlayerState())

    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _positionJumps = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val positionJumps: Flow<Unit> = _positionJumps.asSharedFlow()

    private val remoteHandOver = RemoteHandOver()

    override val currentPositionMs: Long
        get() = remoteHandOver.positionMs(SystemClock.elapsedRealtime()) ?: connectedController()?.currentPosition ?: 0

    init {
        scope.launch {
            _state.subscriptionCount.map { it > 0 }.distinctUntilChanged().collect { observed ->
                if (observed) connect() else scheduleRelease()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
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
                    val previous = _state.value
                    _state.value = remoteHandOver.update(
                        previous = previous,
                        next = player.toState(previous, queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED)),
                        queueEmpty = player.currentTimeline.isEmpty,
                        positionMs = player.currentPosition,
                        nowMs = SystemClock.elapsedRealtime(),
                    )
                }
            })
            _state.value = mediaController.toState(previous = PlayerState(), queueChanged = true)
            pending.complete(mediaController)
        }
        return pending
    }

    /** Gibt den Controller nach [RELEASE_DELAY_MS] frei, wenn bis dahin niemand beobachtet und kein Befehl läuft. */
    @OptIn(ExperimentalCoroutinesApi::class)
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

    override fun play(songs: List<Song>, startIndex: Int, shuffle: Boolean) = withController {
        if (songs.isEmpty()) return@withController
        shuffleModeEnabled = shuffle
        val index = if (shuffle) songs.indices.random() else startIndex.coerceIn(songs.indices)
        setMediaItems(songs.map { it.toMediaItem() }, index, 0)
        prepare()
        play()
    }

    override fun playFromSearch(query: String) = withController {
        val request = MediaItem.RequestMetadata.Builder().setSearchQuery(query).build()
        setMediaItem(MediaItem.Builder().setRequestMetadata(request).build())
        prepare()
        play()
    }

    override suspend fun playBook(bookId: Long, startPositionMs: Long?): PreparedBook? {
        // Eine Podcast-Folge steht sofort ganz vorn in „Weiterhören“, auch wenn sie sich nicht laden lässt.
        continueListening.started(bookId)
        val prepared = bookPlaybackSource.prepare(bookId) ?: return null
        // Der MediaController darf nur vom Main-Thread angesprochen werden.
        withContext(Dispatchers.Main.immediate) {
            useController { playBook(prepared, startPositionMs) }
        }
        return prepared
    }

    override fun skipBack() = withController { seekBack() }

    override fun skipForward() = withController { seekForward() }

    override fun seekToTrack(trackIndex: Int, positionMs: Long) = withController { seekTo(trackIndex, positionMs) }

    override fun setSpeed(speed: Float) = withController { setPlaybackSpeed(speed) }

    override fun playNext(songs: List<Song>) = withController {
        val position = if (mediaItemCount == 0) 0 else currentMediaItemIndex + 1
        addMediaItems(position, songs.map { it.toMediaItem() })
        if (playbackState == Player.STATE_IDLE) prepare()
    }

    override fun addToQueue(songs: List<Song>) = withController {
        addMediaItems(songs.map { it.toMediaItem() })
        if (playbackState == Player.STATE_IDLE) prepare()
    }

    override fun togglePlayPause() = withController {
        if (isPlaying) {
            pause()
        } else {
            if (playbackState == Player.STATE_IDLE) prepare()
            // Ende der Warteschlange: Ohne Zurücksetzen würde play() nichts tun.
            if (playbackState == Player.STATE_ENDED) seekToDefaultPosition()
            play()
        }
    }

    override fun pause() = withController { pause() }

    override fun next() = withController { seekToNext() }

    override fun previous() = withController { seekToPrevious() }

    override fun seekTo(positionMs: Long) = withController { seekTo(positionMs) }

    override fun skipToQueueItem(index: Int) = withController {
        seekToDefaultPosition(index)
        play()
    }

    override fun removeQueueItem(index: Int) = withController { removeMediaItem(index) }

    override fun moveQueueItem(from: Int, to: Int) = withController { moveMediaItem(from, to) }

    override fun toggleShuffle() = withController { shuffleModeEnabled = !shuffleModeEnabled }

    override fun cycleRepeatMode() = withController {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun changeRemoteVolume(up: Boolean) = withController {
        if (deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) return@withController
        if (!isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)) return@withController
        // Ohne C.VOLUME_FLAG_SHOW_UI: Die App zeigt die Lautstärke selbst an.
        if (up) increaseDeviceVolume(0) else decreaseDeviceVolume(0)
    }

    override val sleepTimerRemainingMs: Flow<Long?> get() = sleepTimer.remainingMs

    override fun startSleepTimer(minutes: Int) = sleepTimer.start(minutes * 60_000L)

    override fun cancelSleepTimer() = sleepTimer.cancel()

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
            deviceVolume = deviceVolume,
            maxDeviceVolume = deviceInfo.maxVolume,
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

/** Öffentlich, damit ViewModel-Tests die Bindung per `@TestInstallIn` durch einen Fake ersetzen können. */
@Module
@InstallIn(SingletonComponent::class)
interface PlaybackConnectionBindings {
    @Binds
    fun bindPlaybackConnection(connection: MediaPlaybackConnection): PlaybackConnection
}
