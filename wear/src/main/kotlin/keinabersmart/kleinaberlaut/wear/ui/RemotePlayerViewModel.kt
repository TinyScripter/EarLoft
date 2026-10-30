package keinabersmart.kleinaberlaut.wear.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.playback.PlayerState
import keinabersmart.kleinaberlaut.core.playback.QueueEntry
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteAction
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteStateDto
import keinabersmart.kleinaberlaut.wear.sync.PhoneRemote
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Wiedergabe-Ansicht des Handys (Wear-Konzept 15): zeigt, was das Handy meldet, und schickt die Bedienung dorthin.
 * Play/Pause und Zufall springen sofort um und gelten, bis das Handy seinen Zustand meldet.
 */
@HiltViewModel
class RemotePlayerViewModel @Inject constructor(private val remote: PhoneRemote) : ViewModel(), NowPlayingModel {

    private val remoteState: StateFlow<WearRemoteStateDto?> =
        // Die Nebenwirkungen hängen am Fluss, nicht an einem eigenen Collector: Ein dauerhafter Collector hielte die Anmeldung
        // beim Handy offen (und damit dessen Schreiben), auch wenn die Ansicht nicht sichtbar ist.
        remote.state
            .onEach { dto ->
                // Das Handy hat gemeldet: Die vorläufigen Werte gelten nicht mehr.
                pendingPlaying.value = null
                pendingShuffle.value = null
                if (dto != null && !_volumeVisible.value) _volume.value = dto.volume
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val pendingPlaying = MutableStateFlow<Boolean?>(null)
    private val pendingShuffle = MutableStateFlow<Boolean?>(null)
    private var pendingJob: Job? = null

    override val state: StateFlow<PlayerState> = combine(remoteState, pendingPlaying, pendingShuffle) { remote, playing, shuffle ->
        val dto = remote ?: return@combine PlayerState()
        PlayerState(
            current = if (dto.hasMedia) QueueEntry(0, dto.title, dto.artist, dto.albumId, dto.albumTitle) else null,
            isPlaying = playing ?: dto.isPlaying,
            durationMs = dto.durationMs,
            shuffle = shuffle ?: dto.shuffle,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerState())

    override val positionMs: StateFlow<Long> = flow {
        while (true) {
            emit(remoteState.value?.positionAt(System.currentTimeMillis()) ?: 0L)
            delay(POSITION_INTERVAL_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    override val cover: StateFlow<Bitmap?> = combine(remoteState, remote.cover) { dto, cover ->
        cover?.takeIf { it.first == dto?.albumId }?.second
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override val seedColor: StateFlow<Int?> = remoteState.map { it?.seedColor }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Einen Sleep-Timer gibt es nur auf der Uhr selbst. */
    override val sleepTimerRemainingMs: StateFlow<Long?> = MutableStateFlow(null)

    private val _volume = MutableStateFlow(0f)
    override val volume: StateFlow<Float> = _volume

    private val _volumeVisible = MutableStateFlow(false)
    override val volumeVisible: StateFlow<Boolean> = _volumeVisible
    private var hideVolumeJob: Job? = null
    private val crown = CrownStepper(CROWN_PX_PER_STEP)

    override fun togglePlayPause() {
        pending { pendingPlaying.value = !state.value.isPlaying }
        send(WearRemoteAction.PLAY_PAUSE)
    }

    override fun next() = send(WearRemoteAction.NEXT)

    override fun previous() = send(WearRemoteAction.PREVIOUS)

    override fun toggleShuffle() {
        pending { pendingShuffle.value = !state.value.shuffle }
        send(WearRemoteAction.TOGGLE_SHUFFLE)
    }

    override fun seekTo(positionMs: Long) = send(WearRemoteAction.SEEK, positionMs)

    override fun cancelSleepTimer() = Unit

    override fun onCrown(pixels: Float) {
        val steps = crown.add(pixels)
        val dto = remoteState.value
        if (steps != 0 && dto != null && dto.volumeMax > 0) {
            val current = (_volume.value * dto.volumeMax).roundToInt()
            val target = (current + steps).coerceIn(0, dto.volumeMax)
            if (target != current) {
                _volume.value = target / dto.volumeMax.toFloat()
                send(WearRemoteAction.VOLUME_STEPS, (target - current).toLong())
            }
        }
        _volumeVisible.value = true
        hideVolumeJob?.cancel()
        hideVolumeJob = viewModelScope.launch {
            delay(VOLUME_VISIBLE_MS)
            _volumeVisible.value = false
        }
    }

    private fun send(action: WearRemoteAction, value: Long = 0) {
        viewModelScope.launch { remote.send(action, value) }
    }

    /** Setzt vorläufige Werte; meldet das Handy nichts zurück (nicht erreichbar), verfallen sie nach kurzer Zeit. */
    private fun pending(set: () -> Unit) {
        set()
        pendingJob?.cancel()
        pendingJob = viewModelScope.launch {
            delay(PENDING_MS)
            pendingPlaying.value = null
            pendingShuffle.value = null
        }
    }

    private companion object {
        const val POSITION_INTERVAL_MS = 500L
        const val CROWN_PX_PER_STEP = 30f
        const val VOLUME_VISIBLE_MS = 1_500L
        const val PENDING_MS = 3_000L
    }
}
