package de.kleinabersmart.earloft.wear.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import androidx.core.graphics.scale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.repository.AlbumColorRepository
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.data.repository.seedColorOf
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.model.BookTimeline
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.SpokenEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/**
 * Now playing screen (Wear concept 11): player state, progress, cover and crown volume. If a book
 * or episode is playing (16.1), image and color come from there, and position, length and seeking apply to the whole book (as in
 * remote control); the player itself only knows the position within the file.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val playback: PlaybackConnection,
    artwork: ArtworkRepository,
    albumColors: AlbumColorRepository,
    bookCovers: BookCovers,
    books: BookRepository,
) : ViewModel(), NowPlayingModel, ChaptersModel, SleepTimerModel {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** Timeline of the current book or episode (files and chapters) with its ID; `null` for music. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val timeline: StateFlow<Pair<Long, BookTimeline>?> = playback.state
        .map { it.spoken?.bookId }
        .distinctUntilChanged()
        .mapLatest { bookId -> bookId?.let { id -> books.timeline(id)?.let { id to it } } }
        .orOnError(null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The timeline of [spoken]; `null` for music and as long as it is (still) that of another book. */
    private fun Pair<Long, BookTimeline>?.of(spoken: SpokenEntry?): BookTimeline? =
        this?.takeIf { spoken != null && it.first == spoken.bookId }?.second

    /** For a book or episode, with the length of the whole book instead of the current file. */
    override val state: StateFlow<PlayerState> = combine(playback.state, timeline) { state, timeline ->
        val length = timeline.of(state.spoken)?.durationMs?.takeIf { it > 0 }
        if (length != null) state.copy(durationMs = length) else state
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), playback.state.value)

    /**
     * Position in milliseconds, for spoken content within the whole book; polled regularly only while the screen is visible
     * and it is playing. When paused, once and after every jump (seeking with the crown, track change).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val positionMs: StateFlow<Long> = playback.state
        .map { Triple(it.isPlaying, it.current, it.spoken) }
        .distinctUntilChanged()
        .flatMapLatest { (playing, _, spoken) ->
            val inTrack = flow {
                emit(playback.currentPositionMs)
                if (playing) {
                    while (true) {
                        delay(POSITION_INTERVAL_MS)
                        emit(playback.currentPositionMs)
                    }
                } else {
                    playback.positionJumps.collect { emit(playback.currentPositionMs) }
                }
            }
            // Without a timeline (music, or not read yet) it stays the position within the file, like the length in [state].
            combine(inTrack, timeline) { position, timeline ->
                if (spoken == null) position else timeline.of(spoken)?.toBookPosition(spoken.trackIndex, position) ?: position
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Album and book of the current item: what the cover and its color belong to. */
    private val coverKey = playback.state.map { it.current?.albumId to it.spoken?.bookId }.distinctUntilChanged()

    /**
     * The current item's cover with the key it belongs to, decoded and scaled once off the main thread (review H49); the book's
     * accent color is taken from it instead of decoding the file a second time.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val keyedCover = coverKey
        .mapLatest { key ->
            val (albumId, bookId) = key
            key to when {
                bookId != null -> withContext(Dispatchers.Default) { bookCovers.cover(bookId)?.scaledTo(COVER_SIZE_PX) }
                albumId != null -> withContext(Dispatchers.IO) { artwork.albumCover(albumId, COVER_SIZE_PX) }
                else -> null
            }
        }
        .orOnError(null)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    override val cover: StateFlow<Bitmap?> = keyedCover
        .map { it?.second }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Accent color of the current album or episode (ARGB); `null` as long as it is missing or nothing is playing. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val seedColor: StateFlow<Int?> = coverKey
        .flatMapLatest { key ->
            val (albumId, bookId) = key
            when {
                bookId != null -> keyedCover.filter { it?.first == key }.map { keyed ->
                    keyed?.second?.let { withContext(Dispatchers.Default) { seedColorOf(it) } }
                }
                albumId != null -> albumColors.seedColor(albumId)
                else -> flowOf(null)
            }
        }
        .orOnError(null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override val chapters: StateFlow<List<BookChapter>> = timeline
        .map { it?.second?.chapters.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** For spoken content, [positionMs] already applies to the whole book, like the chapters. */
    override val currentChapter: StateFlow<BookChapter?> = combine(timeline, playback.state, positionMs) { timeline, state, position ->
        timeline.of(state.spoken)?.chapterAt(position)
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override fun seekToBook(bookPositionMs: Long) {
        val target = timeline.value?.second?.locate(bookPositionMs) ?: return
        playback.seekToTrack(target.trackIndex, target.offsetMs)
    }

    private val _volume = MutableStateFlow(readVolume())

    /** 0..1 for the display while turning the crown. */
    override val volume: StateFlow<Float> = _volume

    override fun togglePlayPause() = playback.togglePlayPause()

    override fun next() = playback.next()

    override fun previous() = playback.previous()

    override fun toggleShuffle() = playback.toggleShuffle()

    override fun cycleRepeat() = playback.cycleRepeatMode()

    // Podcast episodes on the watch (Wear concept 16.1); the view only shows the buttons for spoken content.
    override fun skipBack() = playback.skipBack()

    override fun skipForward() = playback.skipForward()

    /** In the time base of [positionMs]: for spoken content with a timeline within the whole book, otherwise within the file. */
    override fun seekTo(positionMs: Long) {
        if (timeline.value.of(playback.state.value.spoken) != null) seekToBook(positionMs) else playback.seekTo(positionMs)
    }

    /** Remaining time of the sleep timer; `null` as long as none is running. */
    override val sleepTimerRemainingMs: StateFlow<Long?> = playback.sleepTimerRemainingMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override fun startSleepTimer(minutes: Int) = playback.startSleepTimer(minutes)

    override val sleepToEndLabel: StateFlow<Int?> = combine(playback.state.map { it.spoken }.distinctUntilChanged(), chapters, ::sleepToEndLabelFor)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override fun startSleepTimerToEnd() = playback.startSleepTimerToEnd()

    override fun cancelSleepTimer() = playback.cancelSleepTimer()

    private val _volumeVisible = MutableStateFlow(false)

    /** The volume bar is shown briefly after every turn of the crown. */
    override val volumeVisible: StateFlow<Boolean> = _volumeVisible

    private var hideVolumeJob: Job? = null
    private val crown = CrownStepper(CROWN_PX_PER_STEP)

    /** When someone turns the crown, the volume changes step by step and the bar becomes briefly visible. */
    override fun onCrown(pixels: Float) {
        val steps = crown.add(pixels)
        if (steps != 0) changeVolume(steps)
        showVolume()
    }

    private fun showVolume() {
        // Read again: headset buttons or another Bluetooth device may have changed it since (review H50).
        _volume.value = readVolume()
        _volumeVisible.value = true
        hideVolumeJob?.cancel()
        hideVolumeJob = viewModelScope.launch {
            delay(VOLUME_VISIBLE_MS)
            _volumeVisible.value = false
        }
    }

    /** [steps] > 0 louder, < 0 quieter. */
    private fun changeVolume(steps: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        _volume.value = readVolume()
    }

    private fun readVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat() else 0f
    }

    /** Episode images come with up to 800 px; the watch shows them blurred in the background, as large as album covers. */
    private fun Bitmap.scaledTo(maxPx: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxPx) return this
        val factor = maxPx.toFloat() / longest
        return scale((width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1))
    }

    private companion object {
        const val POSITION_INTERVAL_MS = 500L
        /** Just below a slow detent (around 34 px) so that every detent gives exactly one step. */
        const val CROWN_PX_PER_STEP = 30f
        const val VOLUME_VISIBLE_MS = 1_500L
        const val COVER_SIZE_PX = 400
    }
}
