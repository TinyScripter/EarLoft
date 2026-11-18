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
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.wear.R
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

/**
 * Wiedergabe-Bildschirm (Wear-Konzept 11): Zustand des Players, Fortschritt, Cover und Lautstärke der Krone. Spielt ein Buch
 * oder eine Folge (16.1), kommen Bild und Farbe von dort, und Position, Länge und Spulen gelten im ganzen Buch (wie in der
 * Fernsteuerung); der Player selbst kennt nur die Position in der Datei.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val playback: PlaybackConnection,
    artwork: ArtworkRepository,
    albumColors: AlbumColorRepository,
    bookCovers: BookCovers,
    books: BookRepository,
) : ViewModel(), NowPlayingModel, ChaptersModel {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** Zeitachse des laufenden Buchs bzw. der Folge (Dateien und Kapitel) mit dessen ID; `null` bei Musik. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val timeline: StateFlow<Pair<Long, BookTimeline>?> = playback.state
        .map { it.spoken?.bookId }
        .distinctUntilChanged()
        .mapLatest { bookId -> bookId?.let { id -> books.timeline(id)?.let { id to it } } }
        .orOnError(null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Die Zeitachse von [spoken]; `null` bei Musik und solange sie (noch) die eines anderen Buchs ist. */
    private fun Pair<Long, BookTimeline>?.of(spoken: SpokenEntry?): BookTimeline? =
        this?.takeIf { spoken != null && it.first == spoken.bookId }?.second

    /** Bei einem Buch oder einer Folge mit der Länge des ganzen Buchs statt der laufenden Datei. */
    override val state: StateFlow<PlayerState> = combine(playback.state, timeline) { state, timeline ->
        val length = timeline.of(state.spoken)?.durationMs?.takeIf { it > 0 }
        if (length != null) state.copy(durationMs = length) else state
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), playback.state.value)

    /**
     * Position in Millisekunden, bei Gesprochenem im ganzen Buch; regelmäßig abgefragt nur, solange der Bildschirm sichtbar ist
     * und es spielt. Pausiert einmal und nach jedem Sprung (Spulen mit der Krone, Titelwechsel).
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
            // Ohne Zeitachse (Musik, oder noch nicht gelesen) bleibt es die Position in der Datei, wie die Länge im [state].
            combine(inTrack, timeline) { position, timeline ->
                if (spoken == null) position else timeline.of(spoken)?.toBookPosition(spoken.trackIndex, position) ?: position
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val cover: StateFlow<Bitmap?> = playback.state
        .map { it.current?.albumId to it.spoken?.bookId }
        .distinctUntilChanged()
        .mapLatest { (albumId, bookId) ->
            when {
                bookId != null -> bookCovers.cover(bookId)?.scaledTo(COVER_SIZE_PX)
                albumId != null -> withContext(Dispatchers.IO) { artwork.albumCover(albumId, COVER_SIZE_PX) }
                else -> null
            }
        }
        .orOnError(null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Akzentfarbe des laufenden Albums bzw. der Folge (ARGB); `null`, solange sie fehlt oder nichts läuft. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val seedColor: StateFlow<Int?> = playback.state
        .map { it.current?.albumId to it.spoken?.bookId }
        .distinctUntilChanged()
        .flatMapLatest { (albumId, bookId) ->
            when {
                bookId != null -> flow { emit(bookCovers.seedColor(bookId)) }
                albumId != null -> albumColors.seedColor(albumId)
                else -> flowOf(null)
            }
        }
        .orOnError(null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override val chapters: StateFlow<List<BookChapter>> = timeline
        .map { it?.second?.chapters.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** [positionMs] gilt bei Gesprochenem schon im ganzen Buch, wie die Kapitel. */
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

    /** 0..1 für die Anzeige beim Drehen der Krone. */
    override val volume: StateFlow<Float> = _volume

    override fun togglePlayPause() = playback.togglePlayPause()

    override fun next() = playback.next()

    override fun previous() = playback.previous()

    override fun toggleShuffle() = playback.toggleShuffle()

    override fun cycleRepeat() = playback.cycleRepeatMode()

    // Podcast-Folgen auf der Uhr (Wear-Konzept 16.1); die Ansicht zeigt die Knöpfe nur bei Gesprochenem.
    override fun skipBack() = playback.skipBack()

    override fun skipForward() = playback.skipForward()

    /** In der Zeit von [positionMs]: bei Gesprochenem mit Zeitachse im ganzen Buch, sonst in der Datei. */
    override fun seekTo(positionMs: Long) {
        if (timeline.value.of(playback.state.value.spoken) != null) seekToBook(positionMs) else playback.seekTo(positionMs)
    }

    /** Restzeit des Sleep-Timers; `null`, solange keiner läuft. */
    override val sleepTimerRemainingMs: StateFlow<Long?> = playback.sleepTimerRemainingMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun startSleepTimer(minutes: Int) = playback.startSleepTimer(minutes)

    /**
     * Erster Eintrag im Sleep-Timer: Folgen bis zu ihrem Ende, Bücher mit Kapiteln bis zum Kapitelende; `null` bei Musik
     * und Büchern ohne Kapitel. Wert ist der Text des Eintrags.
     */
    val sleepToEndLabel: StateFlow<Int?> = combine(playback.state.map { it.spoken?.isEpisode }.distinctUntilChanged(), chapters) { episode, chapters ->
        when {
            episode == null -> null
            episode -> R.string.sleep_timer_episode_end
            chapters.isNotEmpty() -> R.string.sleep_timer_chapter_end
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun startSleepTimerToEnd() = playback.startSleepTimerToEnd()

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

    /** Bilder der Folgen kommen mit bis zu 800 px; die Uhr zeigt sie unscharf im Hintergrund, so groß wie Albumcover. */
    private fun Bitmap.scaledTo(maxPx: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxPx) return this
        val factor = maxPx.toFloat() / longest
        return scale((width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1))
    }

    private companion object {
        const val POSITION_INTERVAL_MS = 500L
        /** Knapp unter einer langsamen Rastung (rund 34 px), damit jede Rastung genau einen Schritt gibt. */
        const val CROWN_PX_PER_STEP = 30f
        const val VOLUME_VISIBLE_MS = 1_500L
        const val COVER_SIZE_PX = 400
    }
}
