package de.kleinabersmart.earloft.wear.ui

import android.graphics.Bitmap
import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.SpokenEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * Was die Wiedergabe-Ansicht braucht, damit sie die Uhr selbst ([PlayerViewModel]) oder das Handy
 * ([RemotePlayerViewModel]) bedienen kann.
 */
interface NowPlayingModel {
    /** Bei einem Buch oder einer Folge ist [PlayerState.durationMs] die Länge des ganzen Buchs. */
    val state: StateFlow<PlayerState>

    /** Position in Millisekunden, bei einem Buch oder einer Folge im ganzen Buch. */
    val positionMs: StateFlow<Long>
    val cover: StateFlow<Bitmap?>

    /** Akzentfarbe des laufenden Albums (ARGB); `null`, solange sie fehlt. */
    val seedColor: StateFlow<Int?>

    /** 0..1 für die Anzeige beim Drehen der Krone. */
    val volume: StateFlow<Float>
    val volumeVisible: StateFlow<Boolean>

    /** Restzeit des Sleep-Timers; `null`, solange keiner läuft (oder er sich hier nicht stellen lässt). */
    val sleepTimerRemainingMs: StateFlow<Long?>

    fun togglePlayPause()

    fun next()

    fun previous()

    fun toggleShuffle()

    /** Wiederholen: aus → alle → Titel → aus. */
    fun cycleRepeat()

    /** Buch oder Folge: um die eingestellte Sprungweite zurück bzw. vor ([de.kleinabersmart.earloft.core.playback.SpokenEntry.seek]). */
    fun skipBack()

    fun skipForward()

    /** In der Zeit von [positionMs], bei einem Buch oder einer Folge also im ganzen Buch. */
    fun seekTo(positionMs: Long)

    fun cancelSleepTimer()

    /** Die Krone wurde um [pixels] gedreht: ändert die Lautstärke. */
    fun onCrown(pixels: Float)
}

/** Was die Kapitelliste braucht: Kapitel der Uhr selbst ([PlayerViewModel]) oder des Handys ([RemotePlayerViewModel]). */
interface ChaptersModel {
    /** Kapitel des Buchs bzw. der Folge; leer bei Musik und ohne Kapitel. */
    val chapters: StateFlow<List<BookChapter>>

    /** Das laufende Kapitel. */
    val currentChapter: StateFlow<BookChapter?>

    /** Springt an [bookPositionMs] (Position im ganzen Buch), z. B. den Beginn eines Kapitels. */
    fun seekToBook(bookPositionMs: Long)
}

/**
 * Der Ausschnitt des Buchs, den der Fortschrittsring zeigt: bei Büchern mit Kapiteln nur das laufende Kapitel, damit sich auch
 * in langen Büchern genau spulen lässt (wie der Kapitel-Regler am Handy). Rechnet zwischen Buchzeit und Zeit im Ring um.
 */
internal data class RingWindow(val startMs: Long, val durationMs: Long) {
    fun toRing(bookPositionMs: Long): Long = (bookPositionMs - startMs).coerceIn(0, durationMs)

    /** Knapp vor dem Ende bleiben, damit ein Sprung ans Ende nicht schon im nächsten Kapitel landet. */
    fun toBook(ringPositionMs: Long): Long = startMs + ringPositionMs.coerceIn(0, (durationMs - 1).coerceAtLeast(0))
}

/**
 * Das Kapitel [chapter] als Ausschnitt, wenn ein Buch läuft; `null` (der Ring zeigt alles) bei Musik, Folgen und ohne
 * Kapitel. Folgen bleiben ganz wie am Handy, wo sie nur den einen Regler haben.
 */
internal fun ringWindow(spoken: SpokenEntry?, chapter: BookChapter?): RingWindow? {
    if (spoken == null || spoken.isEpisode || chapter == null || chapter.durationMs <= 0) return null
    return RingWindow(chapter.startMs, chapter.durationMs)
}
