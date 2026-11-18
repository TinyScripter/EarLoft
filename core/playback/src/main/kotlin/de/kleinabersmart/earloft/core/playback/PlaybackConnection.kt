package de.kleinabersmart.earloft.core.playback

import androidx.media3.common.Player
import de.kleinabersmart.earloft.core.data.spoken.PreparedBook
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps
import de.kleinabersmart.earloft.core.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Wiederholen reihum: aus → alle → Titel → aus (`Player.REPEAT_MODE_*`). */
fun nextRepeatMode(mode: Int): Int = when (mode) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
}

data class QueueEntry(
    val songId: Long,
    val title: String,
    val artist: String,
    val albumId: Long?,
    val albumTitle: String = "",
)

/**
 * Die gespeicherte Musik-Warteschlange, während ein Buch bzw. eine Folge geladen ist: [entries] wie beim Fortsetzen geladen
 * (Songs, die es nicht mehr gibt, fehlen), [index] der Titel, bei dem die Musik weiterginge.
 */
data class SavedMusicQueue(val entries: List<QueueEntry>, val index: Int)

/** Das laufende Hörbuch (Konzept 12); die Position im Buch rechnet die UI über die Zeitachse aus. */
data class SpokenEntry(
    val bookId: Long,
    val trackIndex: Int,
    val title: String,
    val author: String,
    /** Sprungweiten der Spulen-Knöpfe, aus den Einstellungen des Bereichs (Bücher bzw. Podcasts). */
    val seek: SeekSteps = SeekSteps.DEFAULT,
    /** Eine Podcast-Folge statt eines Buchs. */
    val isEpisode: Boolean = false,
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
    /** Lautstärke des Cast-Geräts in Stufen von 0 bis [maxDeviceVolume]; nur bei [isRemote] aussagekräftig. */
    val deviceVolume: Int = 0,
    val maxDeviceVolume: Int = 0,
) {
    val hasMedia: Boolean get() = current != null || spoken != null
}

/**
 * Verbindung der App-UI zur Wiedergabe. Alle Methoden müssen auf dem Main-Thread aufgerufen werden (Vorgabe von
 * `MediaController`). Umgesetzt von [MediaPlaybackConnection]; als Schnittstelle, damit ViewModels mit einem Fake
 * testbar sind (Review A3).
 */
interface PlaybackConnection {

    val state: StateFlow<PlayerState>

    /** Meldet Sprünge der Position (Spulen, Titelwechsel), die der [state] nicht abbildet. */
    val positionJumps: Flow<Unit>

    /** Aktuelle Position (0 ohne Verbindung); für die Fortschrittsanzeige periodisch abfragen, solange [state] beobachtet wird. */
    val currentPositionMs: Long

    /** Restzeit des Sleep-Timers in Millisekunden, jede Sekunde neu; `null`, solange keiner läuft. */
    val sleepTimerRemainingMs: Flow<Long?>

    /** Der Sleep-Timer läuft bis zum Ende des Kapitels bzw. der Folge statt eine feste Dauer. */
    val sleepTimerToEnd: Flow<Boolean>

    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false)

    /**
     * Sprachsuche („Spiele … mit EarLoft“): ein Item ohne Media-ID mit [query] als Suchbegriff; die Session sucht die
     * Songs aus (`LibrarySessionCallback.voiceSongs`, leerer Begriff = gemischte Favoriten), wie bei Android Auto.
     */
    fun playFromSearch(query: String)

    /**
     * Startet ein Hörbuch an der gespeicherten Stelle (oder bei [startPositionMs], z. B. einem Kapitel), nachdem der
     * Fortschritt mit dem Server abgeglichen ist.
     * `null`, wenn das Buch nicht zu laden ist (kein Netz und noch nie geladen).
     */
    suspend fun playBook(bookId: Long, startPositionMs: Long? = null): PreparedBook?

    /**
     * Setzt die gespeicherte Musik-Warteschlange an ihrer Stelle fort und löst damit ein geladenes Buch bzw. eine Folge ab
     * (Widget nur für Musik, Zeile „Warteschlange“ auf dem Musik-Start); ohne gespeicherte Warteschlange nichts. Mit [index]
     * (Stelle in [SavedMusicQueue.entries]) beginnt sie bei diesem Titel von vorn, außer es ist der gespeicherte.
     */
    fun resumeMusic(index: Int? = null)

    /**
     * Die gespeicherte Musik-Warteschlange, solange kein Song geladen ist (ein Buch bzw. eine Folge oder gar nichts, etwa nach
     * dem Ende eines Buchs beim Casten); mit Song (und ohne gespeicherte) `null`.
     */
    val savedMusicQueue: Flow<SavedMusicQueue?>

    fun skipBack()

    fun skipForward()

    /** Springt in Datei [trackIndex] an die Stelle [positionMs] (Position in der Datei). */
    fun seekToTrack(trackIndex: Int, positionMs: Long)

    /**
     * Spielt [songs] nach dem laufenden Titel. Hält der Player ein Buch bzw. eine Folge, gehen sie stattdessen hinter den
     * gespeicherten Titel der Musik-Warteschlange (siehe [queuedForMusic]); das Gesprochene läuft unverändert weiter.
     */
    fun playNext(songs: List<Song>)

    /** Hängt [songs] an die Warteschlange; bei einem Buch bzw. einer Folge an die gespeicherte Musik-Warteschlange (wie [playNext]). */
    fun addToQueue(songs: List<Song>)

    /** Anzahl Songs, die [playNext] bzw. [addToQueue] in die gespeicherte Musik-Warteschlange gelegt haben, weil Gesprochenes lief. */
    val queuedForMusic: Flow<Int>

    /** Der laufende Titel spielt weiter, alles nach ihm weicht [songs] (Mix aus Now Playing); ohne Warteschlange wie [play]. */
    fun replaceUpcoming(songs: List<Song>)

    fun togglePlayPause()

    fun pause()

    fun next()

    fun previous()

    fun seekTo(positionMs: Long)

    fun skipToQueueItem(index: Int)

    fun removeQueueItem(index: Int)

    fun moveQueueItem(from: Int, to: Int)

    /** „Warteschlange leeren“: Alles außer dem laufenden Titel verlässt die Warteschlange; die Wiedergabe läuft weiter. */
    fun clearQueue()

    fun toggleShuffle()

    fun cycleRepeatMode()

    /** Ändert die Lautstärke des Cast-Geräts um eine Stufe; ohne Cast nichts. */
    fun changeRemoteVolume(up: Boolean)

    fun startSleepTimer(minutes: Int)

    /** Sleep-Timer bis zum Ende des Kapitels (Bücher mit Kapiteln) bzw. der Folge oder des Buchs. */
    fun startSleepTimerToEnd()

    fun cancelSleepTimer()
}
