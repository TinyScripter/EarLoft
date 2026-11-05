package de.kleinabersmart.tinyamp.core.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Optionaler Ersatz für den Player der Session. Die Handy-App legt damit Chromecast um den lokalen
 * ExoPlayer; ohne Bindung (Uhr) spielt die Session direkt den ExoPlayer.
 */
interface SessionPlayerFactory {

    /** Main-Thread. Der zurückgegebene Player übernimmt [localPlayer], auch beim `release()`. */
    fun create(context: Context, localPlayer: ExoPlayer): Player

    /**
     * Bevor Songs von einem Controller in die Warteschlange kommen: die, die [player] gerade abspielen
     * kann (z. B. ohne rein lokale Songs beim Casten). Main-Thread, darf suspendieren.
     */
    suspend fun playableSongIds(player: Player, songIds: Collection<Long>): Set<Long> = songIds.toSet()

    /**
     * Höchstzahl Titel, die [player] auf einmal annimmt (Cast: Größe einer Nachricht); siehe [queueWindow].
     * Was darüber liegt, hält die Fabrik zurück und reicht es nach ([onQueueReplaced], [appendPending]).
     */
    fun maxQueueSize(player: Player): Int = Int.MAX_VALUE

    /**
     * Main-Thread. Eine neue Warteschlange wurde gesetzt; was nicht hineinpasste, liegt in [before] (vor dem
     * Ausschnitt) und [after] (dahinter). Beides leer, wenn alles passte.
     */
    fun onQueueReplaced(before: List<MediaItem>, after: List<MediaItem>) = Unit

    /** Main-Thread. Liegen noch Titel zurück, gehören neue Titel ans Ende davon ([appendPending]) statt direkt in den Player. */
    fun hasPendingItems(): Boolean = false

    /** Main-Thread. Hängt [items] hinter die zurückgehaltenen Titel. */
    fun appendPending(items: List<MediaItem>) = Unit
}
