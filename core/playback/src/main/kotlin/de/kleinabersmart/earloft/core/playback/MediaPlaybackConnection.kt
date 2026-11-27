package de.kleinabersmart.earloft.core.playback

import androidx.media3.session.SessionResult
import androidx.media3.session.SessionCommand
import android.os.Bundle
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
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
import de.kleinabersmart.earloft.core.data.di.LoggingExceptionHandler
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.spoken.BookPlaybackSource
import de.kleinabersmart.earloft.core.data.spoken.PreparedBook
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.plus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PlaybackConnection] via a [MediaController] to the [PlaybackService]. Only use via [PlaybackConnection]; public
 * only because of [PlaybackConnectionBindings].
 *
 * It only connects once someone observes [state] or controls the player – not already on creation. Otherwise
 * every process start (background job, message from the watch) would start the service along with player and restore.
 * If nobody observes any more and no command is running, the controller is released after [RELEASE_DELAY_MS]: a bound
 * service would otherwise never end, even if nothing plays any more and the app has long been in the background. [state] keeps
 * the last state until the next connection.
 */
@Singleton
class MediaPlaybackConnection @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sleepTimer: SleepTimer,
    private val bookPlaybackSource: BookPlaybackSource,
    private val continueListening: ContinueListening,
    private val queuePersistence: QueuePersistence,
    spokenSettings: SpokenSettings,
    /** The cast factory of the phone app; the session runs in this process, so it holds what the receiver doesn't. */
    private val sessionPlayerFactory: Optional<SessionPlayerFactory>,
) : PlaybackConnection {
    // With a handler: an exception in a start (resume music, enqueue for music) is logged instead of ending the app.
    private val scope = MainScope() + LoggingExceptionHandler

    /** Skip distances for books and episodes; main thread only. */
    private var bookSteps = SeekSteps.DEFAULT
    private var podcastSteps = SeekSteps.DEFAULT

    /** Whether the current item is an episode; lets the skip distances be set again without a controller. */
    private var currentIsEpisode = false

    /** The current or pending connection; `null` while there is none. Main thread only. */
    private var connection: CompletableDeferred<MediaController>? = null

    /** Commands currently waiting for or using the controller; while > 0, it is not released. */
    private var commandsInFlight = 0
    private var releaseJob: Job? = null
    private val _state = MutableStateFlow(PlayerState())

    /** The first state from the player has arrived; before that an empty [_state] just means "not connected yet". */
    private val stateKnown = MutableStateFlow(false)

    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _connected = MutableStateFlow(false)

    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _positionJumps = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val positionJumps: Flow<Unit> = _positionJumps.asSharedFlow()

    private val _playbackStarts = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val playbackStarts: Flow<Unit> = _playbackStarts.asSharedFlow()

    private val remoteHandOver = RemoteHandOver()

    override val currentPositionMs: Long
        get() = remoteHandOver.positionMs(SystemClock.elapsedRealtime()) ?: connectedController()?.currentPosition ?: 0

    init {
        scope.launch {
            combine(spokenSettings.bookSeek, spokenSettings.podcastSeek, ::Pair).collect { (book, podcast) ->
                bookSteps = book
                podcastSteps = podcast
                _state.update { state -> state.copy(spoken = state.spoken?.copy(seek = stepsFor(currentIsEpisode))) }
            }
        }
        scope.launch {
            _state.subscriptionCount.map { it > 0 }.distinctUntilChanged().collect { observed ->
                if (observed) connect() else scheduleRelease()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun connectedController(): MediaController? =
        connection?.takeIf { it.isCompleted && !it.isCancelled }?.getCompleted()

    /** Builds the [MediaController] if no connection exists or is being established; main thread only. */
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
                        // Session gone (service ended): reconnect as long as someone is observing, otherwise on next access.
                        override fun onDisconnected(controller: MediaController) {
                            if (connection !== pending) return
                            connection = null
                            _connected.value = false
                            if (_state.subscriptionCount.value > 0) connect()
                        }
                    })
                    .buildAsync()
                    .await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No connection to the playback service", e)
                if (connection === pending) connection = null
                pending.completeExceptionally(e)
                return@launch
            }
            if (connection !== pending) {
                // Released in the meantime.
                mediaController.release()
                pending.cancel()
                return@launch
            }
            mediaController.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (playWhenReady) _playbackStarts.tryEmit(Unit)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val byItself = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                    if (mediaItem != null && !byItself && mediaController.playWhenReady) _playbackStarts.tryEmit(Unit)
                }

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
            stateKnown.value = true
            pending.complete(mediaController)
            _connected.value = true
        }
        return pending
    }

    /** Releases the controller after [RELEASE_DELAY_MS] if by then nobody is observing and no command is running. */
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
            _connected.value = false
            if (released.isCompleted && !released.isCancelled) released.getCompleted().release()
            // If the connection is still being established, the setup releases it itself (connection !== pending).
        }
    }

    /** Runs [block] with a connected controller; afterwards it may be released if nobody is observing any more. */
    private suspend fun <T> useController(block: suspend MediaController.() -> T): T? {
        commandsInFlight++
        try {
            val controller = try {
                connect().await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null // already logged
            }
            return controller.block()
        } finally {
            commandsInFlight--
            scheduleRelease()
        }
    }

    override fun play(songs: List<Song>, startIndex: Int, shuffle: Boolean) = withController {
        if (songs.isEmpty()) return@withController
        val index = if (shuffle) songs.indices.random() else startIndex.coerceIn(songs.indices)
        if (shuffle && deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            // The cast receiver knows no shuffle and plays in list order: started at a random place, the titles before it never
            // came (review G42). Like at the handover (E11) the list goes over already shuffled, with the start title first.
            val order = listOf(index) + (songs.indices - index).shuffled()
            setMediaItems(order.map { songs[it].toMediaItem() }, 0, 0)
        } else {
            shuffleModeEnabled = shuffle
            setMediaItems(songs.map { it.toMediaItem() }, index, 0)
        }
        prepare()
        playSetItems()
    }

    override suspend fun playFromSearch(query: String): Boolean = withContext(Dispatchers.Main.immediate) {
        useController {
            // Only a request with results is set and played: otherwise the session keeps the queue and "play" would continue it.
            val check = sendCustomCommand(SessionCommand(ACTION_VOICE_CHECK, Bundle.EMPTY), Bundle().apply { putString(EXTRA_VOICE_QUERY, query) })
                .await()
            if (check.resultCode != SessionResult.RESULT_SUCCESS) return@useController false
            val request = MediaItem.RequestMetadata.Builder().setSearchQuery(query).build()
            setMediaItem(MediaItem.Builder().setRequestMetadata(request).build())
            prepare()
            playSetItems()
            true
        } ?: false
    }

    override suspend fun playBook(bookId: Long, startPositionMs: Long?): PreparedBook? {
        // A podcast episode goes to the very front of "Continue listening" immediately, even if it can't be loaded.
        continueListening.started(bookId)
        val prepared = bookPlaybackSource.prepare(bookId) ?: return null
        // The MediaController may only be accessed from the main thread.
        withContext(Dispatchers.Main.immediate) {
            useController { playBook(prepared, startPositionMs) }
        }
        return prepared
    }

    override fun resumeMusic(index: Int?) {
        scope.launch {
            val restored = queuePersistence.restore() ?: return@launch
            // A title other than the saved one starts from the beginning.
            val start = index?.takeIf { it in restored.items.indices && it != restored.startIndex }
            useController {
                shuffleModeEnabled = restored.shuffle
                repeatMode = restored.repeatMode
                setMediaItems(restored.items, start ?: restored.startIndex, if (start != null) 0 else restored.startPositionMs)
                prepare()
                playSetItems()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val savedMusicQueue: Flow<SavedMusicQueue?> = combine(_state, stateKnown) { state, known ->
        // Without a song: a book or episode is loaded, or nothing at all (e.g. after a book ended while casting).
        state.current == null && (state.spoken != null || known)
    }
        .distinctUntilChanged()
        .flatMapLatest { noMusic -> if (noMusic) queuePersistence.savedQueue else flowOf(null) }

    override fun skipBack() = withController { seekBack() }

    override fun skipForward() = withController { seekForward() }

    /**
     * The file's index in the player is its track index only while the player holds the whole book; while casting it holds a window
     * of a long book. A file in the window is sought directly, otherwise the session finds it or loads a window around it (review4 T11).
     */
    override fun seekToTrack(trackIndex: Int, positionMs: Long) = withController {
        val bookId = currentMediaItem?.bookId
        val index = (0 until mediaItemCount).firstOrNull { index ->
            getMediaItemAt(index).let { it.bookId == bookId && it.bookTrackIndex == trackIndex }
        }
        when {
            index != null -> seekTo(index, positionMs)
            bookId != null -> sendCustomCommand(
                SessionCommand(ACTION_SEEK_BOOK_TRACK, Bundle.EMPTY),
                Bundle().apply {
                    putInt(EXTRA_TRACK_INDEX, trackIndex)
                    putLong(EXTRA_POSITION_MS, positionMs)
                },
            )
            else -> seekTo(trackIndex, positionMs)
        }
    }

    private val _queuedForMusic = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    override val queuedForMusic: Flow<Int> = _queuedForMusic.asSharedFlow()

    override fun playNext(songs: List<Song>) = withController {
        if (currentMediaItem?.isSpoken == true) return@withController queueForMusic(songs, next = true)
        val position = if (mediaItemCount == 0) 0 else currentMediaItemIndex + 1
        addMediaItems(position, songs.map { it.toMediaItem() })
        if (playbackState == Player.STATE_IDLE) prepare()
    }

    override fun addToQueue(songs: List<Song>) = withController {
        if (currentMediaItem?.isSpoken == true) return@withController queueForMusic(songs, next = false)
        addMediaItems(songs.map { it.toMediaItem() })
        if (playbackState == Player.STATE_IDLE) prepare()
    }

    /**
     * A book or episode replaces the player's queue. Songs after it would play after the spoken content instead of
     * the next episode, and would overwrite the saved music queue; that's why they go there.
     */
    private fun queueForMusic(songs: List<Song>, next: Boolean) {
        if (songs.isEmpty()) return
        scope.launch {
            queuePersistence.addToSavedQueue(songs.map { it.id }, next)
            _queuedForMusic.emit(songs.size)
        }
    }

    override fun replaceUpcoming(songs: List<Song>) = withController {
        // The mix has its own order (e.g. never the same artist twice in a row). With shuffle, "after the
        // current title" would mean the shuffled order, and Media3 would shuffle the mix again: as with `play`, shuffle ends here.
        shuffleModeEnabled = false
        if (mediaItemCount == 0) {
            setMediaItems(songs.map { it.toMediaItem() })
            prepare()
            playSetItems()
            return@withController
        }
        // Also with nothing after the current title: while casting this drops the reserve behind the receiver's window.
        clearAfterCurrent()
        addMediaItems(songs.map { it.toMediaItem() })
        if (playbackState == Player.STATE_IDLE) prepare()
    }

    override fun togglePlayPause() = withController {
        // Waiting for a suitable output (watch without headphones) counts as playing: otherwise the start couldn't be called off,
        // and music would begin later when headphones connect (review4 T60).
        val waitingForOutput = playWhenReady && playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT
        if (isPlaying || waitingForOutput) {
            pause()
        } else {
            if (playbackState == Player.STATE_IDLE) prepare()
            // End of the queue: without resetting, play() would do nothing.
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

    override fun clearQueue() = withController {
        val current = currentMediaItemIndex
        if (current + 1 < mediaItemCount) removeMediaItems(current + 1, mediaItemCount)
        if (current > 0) removeMediaItems(0, current)
    }

    override suspend fun queueSongIds(): List<Long> = withContext(Dispatchers.Main) {
        sessionPlayerFactory.orElse(null)?.fullQueue()?.mapNotNull { it.songId } ?: state.value.queue.map { it.songId }
    }

    override fun toggleShuffle() = withController { shuffleModeEnabled = !shuffleModeEnabled }

    override fun cycleRepeatMode() = withController { repeatMode = nextRepeatMode(repeatMode) }

    override fun changeRemoteVolume(up: Boolean) = withController {
        if (deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) return@withController
        if (!isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)) return@withController
        // Without C.VOLUME_FLAG_SHOW_UI: the app shows the volume itself.
        if (up) increaseDeviceVolume(0) else decreaseDeviceVolume(0)
    }

    override val sleepTimerRemainingMs: Flow<Long?> get() = sleepTimer.remainingMs

    override val sleepTimerToEnd: Flow<Boolean> get() = sleepTimer.state.map { it?.toEnd == true }.distinctUntilChanged()

    override fun startSleepTimer(minutes: Int) = sleepTimer.start(minutes * 60_000L)

    override fun startSleepTimerToEnd() = sleepTimer.startToEnd()

    override fun cancelSleepTimer() = sleepTimer.cancel()

    private fun withController(block: MediaController.() -> Unit) {
        scope.launch { useController { block() } }
    }

    private fun Player.toState(previous: PlayerState, queueChanged: Boolean): PlayerState {
        val queue = if (queueChanged) (0 until mediaItemCount).mapNotNull { getMediaItemAt(it).toQueueEntry() } else previous.queue
        val orderChanged = queueChanged || shuffleModeEnabled != previous.shuffle || currentMediaItemIndex != previous.currentIndex
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
            laterIndices = if (orderChanged) laterIndices() else previous.laterIndices,
            // Only a start that waits for the output: paused, nothing is held back (review4 T60).
            outputSuppressed = playWhenReady && playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT,
            isRemote = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE,
            deviceVolume = deviceVolume,
            maxDeviceVolume = deviceInfo.maxVolume,
        )
    }

    /** The windows after the current one in playback order (shuffle order if on), without repeat; see [PlayerState.laterIndices]. */
    private fun Player.laterIndices(): List<Int> {
        val timeline = currentTimeline
        var index = currentMediaItemIndex
        if (timeline.isEmpty || index !in 0 until timeline.windowCount) return emptyList()
        val later = ArrayList<Int>()
        while (later.size < timeline.windowCount) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
            if (index == C.INDEX_UNSET) break
            later += index
        }
        return later
    }

    private fun MediaItem.toSpokenEntry(): SpokenEntry? {
        val id = bookId ?: return null
        currentIsEpisode = isEpisode
        return SpokenEntry(
            bookId = id,
            trackIndex = bookTrackIndex ?: 0,
            title = mediaMetadata.title?.toString().orEmpty(),
            author = mediaMetadata.artist?.toString().orEmpty(),
            seek = stepsFor(isEpisode),
            isEpisode = isEpisode,
        )
    }

    private fun stepsFor(episode: Boolean): SeekSteps = if (episode) podcastSteps else bookSteps

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
         * The controller stays connected this long after the last observer or command. Covers short gaps
         * (rotation, a short switch to another app; the ViewModels in turn hold for 5 s) without binding the service for long.
         */
        const val RELEASE_DELAY_MS = 10_000L
    }
}

/** Public so that ViewModel tests can replace the binding with a fake via `@TestInstallIn`. */
@Module
@InstallIn(SingletonComponent::class)
interface PlaybackConnectionBindings {
    @Binds
    fun bindPlaybackConnection(connection: MediaPlaybackConnection): PlaybackConnection
}
