package keinabersmart.kleinaberlaut.wear.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.data.repository.AlbumColorRepository
import keinabersmart.kleinaberlaut.core.data.repository.ArtworkRepository
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.playback.PlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/** Wiedergabe-Bildschirm (Wear-Konzept 11): Zustand des Players, Fortschritt, Cover und Lautstärke der Krone. */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val playback: PlaybackConnection,
    artwork: ArtworkRepository,
    albumColors: AlbumColorRepository,
) : ViewModel(), NowPlayingModel {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    override val state: StateFlow<PlayerState> = playback.state

    /**
     * Position in Millisekunden; regelmäßig abgefragt nur, solange der Bildschirm sichtbar ist und es spielt. Pausiert einmal
     * und nach jedem Sprung (Spulen mit der Krone, Titelwechsel).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val positionMs: StateFlow<Long> = playback.state
        .map { it.isPlaying to it.current }
        .distinctUntilChanged()
        .flatMapLatest { (playing, _) ->
            flow {
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
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val cover: StateFlow<Bitmap?> = playback.state
        .map { it.current?.albumId }
        .distinctUntilChanged()
        .mapLatest { albumId -> albumId?.let { withContext(Dispatchers.IO) { artwork.albumCover(it, COVER_SIZE_PX) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Akzentfarbe des laufenden Albums (ARGB); `null`, solange sie fehlt oder nichts läuft. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val seedColor: StateFlow<Int?> = playback.state
        .map { it.current?.albumId }
        .distinctUntilChanged()
        .flatMapLatest { albumId -> if (albumId == null) flowOf(null) else albumColors.seedColor(albumId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _volume = MutableStateFlow(readVolume())

    /** 0..1 für die Anzeige beim Drehen der Krone. */
    override val volume: StateFlow<Float> = _volume

    override fun togglePlayPause() = playback.togglePlayPause()

    override fun next() = playback.next()

    override fun previous() = playback.previous()

    override fun toggleShuffle() = playback.toggleShuffle()

    override fun seekTo(positionMs: Long) = playback.seekTo(positionMs)

    /** Restzeit des Sleep-Timers; `null`, solange keiner läuft. */
    override val sleepTimerRemainingMs: StateFlow<Long?> = playback.sleepTimerRemainingMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun startSleepTimer(minutes: Int) = playback.startSleepTimer(minutes)

    override fun cancelSleepTimer() = playback.cancelSleepTimer()

    private val _volumeVisible = MutableStateFlow(false)

    /** Der Lautstärkebalken steht kurz nach jeder Drehung der Krone. */
    override val volumeVisible: StateFlow<Boolean> = _volumeVisible

    private var hideVolumeJob: Job? = null
    private val crown = CrownStepper(CROWN_PX_PER_STEP)

    /** Dreht jemand an der Krone, ändert sich die Lautstärke schrittweise, und der Balken wird kurz sichtbar. */
    override fun onCrown(pixels: Float) {
        val steps = crown.add(pixels)
        if (steps != 0) changeVolume(steps)
        showVolume()
    }

    private fun showVolume() {
        _volumeVisible.value = true
        hideVolumeJob?.cancel()
        hideVolumeJob = viewModelScope.launch {
            delay(VOLUME_VISIBLE_MS)
            _volumeVisible.value = false
        }
    }

    /** [steps] > 0 lauter, < 0 leiser. */
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

    private companion object {
        const val POSITION_INTERVAL_MS = 500L
        /** Knapp unter einer langsamen Rastung (rund 34 px), damit jede Rastung genau einen Schritt gibt. */
        const val CROWN_PX_PER_STEP = 30f
        const val VOLUME_VISIBLE_MS = 1_500L
        const val COVER_SIZE_PX = 400
    }
}
