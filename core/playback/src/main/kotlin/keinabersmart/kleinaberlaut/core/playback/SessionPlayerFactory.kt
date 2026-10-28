package keinabersmart.kleinaberlaut.core.playback

import android.content.Context
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

    /** Höchstzahl Titel, die [player] in der Warteschlange hält (Cast: Größe einer Nachricht); siehe [queueWindow]. */
    fun maxQueueSize(player: Player): Int = Int.MAX_VALUE

    /** Main-Thread. Von [requested] Titeln kamen nur [kept] in die Warteschlange. */
    fun onQueueLimited(kept: Int, requested: Int) = Unit
}
