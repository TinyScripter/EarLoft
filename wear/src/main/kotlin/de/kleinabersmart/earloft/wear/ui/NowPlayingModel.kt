package de.kleinabersmart.earloft.wear.ui

import android.graphics.Bitmap
import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.SpokenEntry
import de.kleinabersmart.earloft.wear.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the now playing view needs so that it can control the watch itself ([PlayerViewModel]) or the phone
 * ([RemotePlayerViewModel]).
 */
interface NowPlayingModel {
    /** For a book or episode, [PlayerState.durationMs] is the length of the whole book. */
    val state: StateFlow<PlayerState>

    /** Position in milliseconds; for a book or episode, within the whole book. */
    val positionMs: StateFlow<Long>
    val cover: StateFlow<Bitmap?>

    /** Accent color of the current album (ARGB); `null` as long as it is missing. */
    val seedColor: StateFlow<Int?>

    /** 0..1 for the display while turning the crown. */
    val volume: StateFlow<Float>
    val volumeVisible: StateFlow<Boolean>

    /** Remaining time of the sleep timer; `null` as long as none is running (or it cannot be set here). */
    val sleepTimerRemainingMs: StateFlow<Long?>

    /** A hint in place of the artist (string resource), e.g. that the phone is not reachable; `null`: none. */
    val notice: StateFlow<Int?> get() = NO_NOTICE

    fun togglePlayPause()

    fun next()

    fun previous()

    fun toggleShuffle()

    /** Repeat: off → all → track → off. */
    fun cycleRepeat()

    /** Book or episode: skip back or forward by the configured interval ([de.kleinabersmart.earloft.core.playback.SpokenEntry.seek]). */
    fun skipBack()

    fun skipForward()

    /** In the time base of [positionMs], i.e. within the whole book for a book or episode. */
    fun seekTo(positionMs: Long)

    fun cancelSleepTimer()

    /** The crown was turned by [pixels]: changes the volume. */
    fun onCrown(pixels: Float)
}

/** What the sleep timer views need: the timer of the watch itself ([PlayerViewModel]) or that of the phone ([RemotePlayerViewModel]). */
interface SleepTimerModel {
    /** Text of the first entry "End of chapter" or "End of episode"; `null` if there is none ([sleepToEndLabelFor]). */
    val sleepToEndLabel: StateFlow<Int?>

    fun startSleepTimer(minutes: Int)

    fun startSleepTimerToEnd()
}

/**
 * First entry in the sleep timer (as on the phone): episodes until their end, books with chapters until the end of the chapter; `null` for
 * music and books without chapters (those only end after hours).
 */
internal fun sleepToEndLabelFor(spoken: SpokenEntry?, chapters: List<BookChapter>): Int? = when {
    spoken == null -> null
    spoken.isEpisode -> R.string.sleep_timer_episode_end
    chapters.isNotEmpty() -> R.string.sleep_timer_chapter_end
    else -> null
}

/** What the chapter list needs: chapters of the watch itself ([PlayerViewModel]) or of the phone ([RemotePlayerViewModel]). */
interface ChaptersModel {
    /** Chapters of the book or episode; empty for music and without chapters. */
    val chapters: StateFlow<List<BookChapter>>

    /** The current chapter. */
    val currentChapter: StateFlow<BookChapter?>

    /** Seeks to [bookPositionMs] (position in the whole book), e.g. the start of a chapter. */
    fun seekToBook(bookPositionMs: Long)
}

/**
 * The section of the book the progress ring shows: for books with chapters only the current chapter, so that seeking is precise
 * even in long books (like the chapter slider on the phone). Converts between book time and time within the ring.
 */
internal data class RingWindow(val startMs: Long, val durationMs: Long) {
    fun toRing(bookPositionMs: Long): Long = (bookPositionMs - startMs).coerceIn(0, durationMs)

    /** Stay just before the end so that a seek to the end does not already land in the next chapter. */
    fun toBook(ringPositionMs: Long): Long = startMs + ringPositionMs.coerceIn(0, (durationMs - 1).coerceAtLeast(0))
}

/**
 * The chapter [chapter] as a section when a book is playing; `null` (the ring shows everything) for music, episodes and without
 * chapters. Episodes stay whole as on the phone, where they only have the one slider.
 */
internal fun ringWindow(spoken: SpokenEntry?, chapter: BookChapter?): RingWindow? {
    if (spoken == null || spoken.isEpisode || chapter == null || chapter.durationMs <= 0) return null
    return RingWindow(chapter.startMs, chapter.durationMs)
}

private val NO_NOTICE: StateFlow<Int?> = MutableStateFlow(null)
