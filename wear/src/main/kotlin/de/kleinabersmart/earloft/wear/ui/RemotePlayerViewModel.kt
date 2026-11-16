package de.kleinabersmart.earloft.wear.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps
import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.QueueEntry
import de.kleinabersmart.earloft.core.playback.SpokenEntry
import de.kleinabersmart.earloft.core.playback.nextRepeatMode
import de.kleinabersmart.earloft.core.wearsync.WearRemoteAction
import de.kleinabersmart.earloft.core.wearsync.WearRemoteChaptersDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteStateDto
import de.kleinabersmart.earloft.wear.sync.PhoneRemote
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Wiedergabe-Ansicht des Handys (Wear-Konzept 15): zeigt, was das Handy meldet, und schickt die Bedienung dorthin.
 * Play/Pause, Zufall und Wiederholen springen sofort um und gelten, bis das Handy seinen Zustand meldet.
 */
@HiltViewModel
class RemotePlayerViewModel @Inject constructor(private val remote: PhoneRemote) : ViewModel(), NowPlayingModel, ChaptersModel {

    private val remoteState: StateFlow<WearRemoteStateDto?> =
        // Die Nebenwirkungen hängen am Fluss, nicht an einem eigenen Collector: Ein dauerhafter Collector hielte die Anmeldung
        // beim Handy offen (und damit dessen Schreiben), auch wenn die Ansicht nicht sichtbar ist.
        remote.state
            .onEach { dto ->
                // Das Handy hat gemeldet: Die vorläufigen Werte gelten nicht mehr.
                pendingPlaying.value = null
                pendingShuffle.value = null
                pendingRepeat.value = null
                if (dto != null && !_volumeVisible.value) _volume.value = dto.volume
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val pendingPlaying = MutableStateFlow<Boolean?>(null)
    private val pendingShuffle = MutableStateFlow<Boolean?>(null)
    private val pendingRepeat = MutableStateFlow<Int?>(null)
    private var pendingJob: Job? = null

    override val state: StateFlow<PlayerState> = combine(
        remoteState,
        pendingPlaying,
        pendingShuffle,
        pendingRepeat,
    ) { remote, playing, shuffle, repeat ->
        val dto = remote ?: return@combine PlayerState()
        val spoken = dto.hasMedia && dto.isSpoken
        PlayerState(
            current = if (dto.hasMedia && !spoken) QueueEntry(0, dto.title, dto.artist, dto.albumId, dto.albumTitle) else null,
            // Position und Länge sind schon Buchzeiten; eine Datei (trackIndex) gibt es für die Uhr nicht.
            spoken = if (spoken) {
                SpokenEntry(dto.bookId ?: 0, 0, dto.title, dto.artist, SeekSteps(dto.seekBackSeconds, dto.seekForwardSeconds))
            } else {
                null
            },
            speed = dto.speed,
            isPlaying = playing ?: dto.isPlaying,
            durationMs = dto.durationMs,
            shuffle = shuffle ?: dto.shuffle,
            repeatMode = repeat ?: dto.repeatMode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerState())

    /**
     * Aus dem gemeldeten Stand fortgerechnet; regelmäßig nur, solange das Handy spielt, sonst bei jeder neuen Meldung. Nur der
     * Startwert vergleicht Handy- und Uhr-Uhrzeit (die Meldung kann älter sein, Wear OS stellt die Uhr nach dem Handy); weiter
     * zählt die monotone Uhr, damit ein Zeitabgleich unterwegs die Anzeige nicht springen lässt.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val positionMs: StateFlow<Long> = remoteState
        .flatMapLatest { dto ->
            flow {
                val start = dto?.positionAt(System.currentTimeMillis()) ?: 0L
                val startedAt = SystemClock.elapsedRealtime()
                emit(start)
                while (dto?.isPlaying == true) {
                    delay(POSITION_INTERVAL_MS)
                    val position = start + ((SystemClock.elapsedRealtime() - startedAt) * dto.speed.toDouble()).toLong()
                    emit(if (dto.durationMs > 0) position.coerceAtMost(dto.durationMs) else position)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    override val cover: StateFlow<Bitmap?> = combine(remoteState, remote.cover) { dto, cover ->
        cover?.takeIf { it.first == dto?.coverKey }?.second
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override val seedColor: StateFlow<Int?> = remoteState.map { it?.seedColor }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override val chapters: StateFlow<List<BookChapter>> = combine(remoteState, remote.chapters, ::chaptersOf)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Aus der fortgerechneten Position: Der Name wechselt mitten in der Datei, ohne dass das Handy etwas meldet. Neu zusammengesetzt
     * wird nur beim Wechsel, nicht im 500-ms-Takt der Position.
     */
    override val currentChapter: StateFlow<BookChapter?> = combine(chapters, positionMs, ::chapterAt)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Das Handy wählt Datei und Versatz über seine Zeitachse ([WearRemoteAction.SEEK] in Buchzeit). */
    override fun seekToBook(bookPositionMs: Long) = seekTo(bookPositionMs)

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

    override fun cycleRepeat() {
        pending { pendingRepeat.value = nextRepeatMode(state.value.repeatMode) }
        send(WearRemoteAction.CYCLE_REPEAT)
    }

    override fun skipBack() = send(WearRemoteAction.SKIP_BACK)

    override fun skipForward() = send(WearRemoteAction.SKIP_FORWARD)

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
            pendingRepeat.value = null
        }
    }

    private companion object {
        const val POSITION_INTERVAL_MS = 500L
        const val CROWN_PX_PER_STEP = 30f
        const val VOLUME_VISIBLE_MS = 1_500L
        const val PENDING_MS = 3_000L
    }
}

/**
 * Die Kapitel aus [chapters], wenn sie zum Buch bzw. zur Folge gehören, die das Handy laut [state] spielt; bei Musik, ohne
 * Meldung oder mit den Kapiteln eines vorigen Buchs keine.
 */
internal fun chaptersOf(state: WearRemoteStateDto?, chapters: WearRemoteChaptersDto?): List<BookChapter> {
    if (state == null || !state.hasMedia || !state.isSpoken || chapters == null || chapters.bookId != state.bookId) return emptyList()
    return chapters.chapters.mapIndexed { index, chapter ->
        BookChapter(index, chapter.title, chapter.startMs, chapter.endMs, unavailable = chapter.unavailable)
    }
}

/** Das Kapitel an [positionMs] (Buchzeit), wie [de.kleinabersmart.earloft.core.model.BookTimeline.chapterAt]. */
internal fun chapterAt(chapters: List<BookChapter>, positionMs: Long): BookChapter? =
    chapters.lastOrNull { it.startMs <= positionMs } ?: chapters.firstOrNull()
