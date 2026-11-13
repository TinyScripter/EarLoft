package de.kleinabersmart.earloft.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps

/**
 * Bei Hörbüchern und Folgen gelten andere Sprungweiten als bei Musik, einstellbar je Bereich (Konzept 12.4). Media3
 * kennt pro Player nur ein Paar; deshalb beantwortet dieser Wrapper [getSeekBackIncrement], [getSeekForwardIncrement],
 * [seekBack] und [seekForward] je nach aktuellem Item. Benachrichtigung, Android Auto, Widget und App sprechen alle mit
 * der Session und damit mit diesem Player.
 *
 * Ändern sich die Sprungweiten (anderes Item, neue Einstellung), meldet er das seinen Listenern: Die Session gibt es an die
 * Controller weiter, die sonst mit der alten Weite rechneten (die App zeigte nach dem Spulen kurz eine falsche Position).
 *
 * Das laufende Kapitel ([chapterTitle]) steht in den Metadaten des Players vor dem Autor („Kapitel 12 · Autor“), also in
 * Benachrichtigung, Sperrbildschirm und Android Auto. Die App liest die Metadaten der Items und sieht davon nichts.
 */
@OptIn(UnstableApi::class)
internal class SpokenAwarePlayer(player: Player) : ForwardingPlayer(player) {

    /** Aus den Einstellungen; nur auf dem Main-Thread setzen. */
    var bookSteps: SeekSteps = SeekSteps.DEFAULT
        set(value) {
            field = value
            reportIncrements()
        }
    var podcastSteps: SeekSteps = SeekSteps.DEFAULT
        set(value) {
            field = value
            reportIncrements()
        }

    /** Kapitel des laufenden Buchs bzw. der Folge; nur auf dem Main-Thread setzen. */
    var chapterTitle: String? = null
        set(value) {
            if (field == value) return
            field = value
            reportMetadata()
        }

    /**
     * Metadaten vom eigentlichen Player (neues Item, Tags aus der Datei) kommen ohne Kapitel bei den Listenern an. Dieser
     * Listener steht dort immer als letzter (siehe [addListener]) und meldet sie danach mit Kapitel.
     */
    private val chapterRedispatch = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            if (chapterTitle != null && currentMediaItem?.isSpoken == true) reportMetadata()
        }
    }

    /** Die Listener, die hier angemeldet sind (an den eigentlichen Player gehen sie weiter); Main-Thread. */
    private val listeners = LinkedHashSet<Player.Listener>()
    private var reportedBack = seekBackIncrement
    private var reportedForward = seekForwardIncrement

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = reportIncrements()
        })
    }

    /** Sprungweiten des aktuellen Items; `null` bei Musik. */
    val currentSteps: SeekSteps?
        get() {
            val item = currentMediaItem?.takeIf { it.isSpoken } ?: return null
            return if (item.isEpisode) podcastSteps else bookSteps
        }

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners += listener
        wrappedPlayer.removeListener(chapterRedispatch)
        wrappedPlayer.addListener(chapterRedispatch)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners -= listener
    }

    private fun reportMetadata() {
        val metadata = mediaMetadata
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build())
        listeners.toList().forEach { it.onMediaMetadataChanged(metadata) }
        listeners.toList().forEach { it.onEvents(this, events) }
    }

    override fun getMediaMetadata(): MediaMetadata = withChapter(super.getMediaMetadata())

    private fun withChapter(metadata: MediaMetadata): MediaMetadata {
        val chapter = chapterTitle ?: return metadata
        if (currentMediaItem?.isSpoken != true) return metadata
        val subtitle = listOfNotNull(chapter, metadata.artist?.toString()?.takeIf { it.isNotBlank() }).joinToString(" · ")
        return metadata.buildUpon().setArtist(subtitle).setSubtitle(subtitle).build()
    }

    override fun getSeekBackIncrement(): Long = currentSteps?.backMs ?: super.getSeekBackIncrement()

    override fun getSeekForwardIncrement(): Long = currentSteps?.forwardMs ?: super.getSeekForwardIncrement()

    override fun seekBack() {
        currentSteps?.let { seekByMs(-it.backMs) } ?: super.seekBack()
    }

    override fun seekForward() {
        currentSteps?.let { seekByMs(it.forwardMs) } ?: super.seekForward()
    }

    private fun reportIncrements() {
        val back = seekBackIncrement
        val forward = seekForwardIncrement
        val events = FlagSet.Builder()
        if (back != reportedBack) {
            reportedBack = back
            events.add(Player.EVENT_SEEK_BACK_INCREMENT_CHANGED)
            listeners.toList().forEach { it.onSeekBackIncrementChanged(back) }
        }
        if (forward != reportedForward) {
            reportedForward = forward
            events.add(Player.EVENT_SEEK_FORWARD_INCREMENT_CHANGED)
            listeners.toList().forEach { it.onSeekForwardIncrementChanged(forward) }
        }
        val flags = events.build()
        if (flags.size() > 0) listeners.toList().forEach { it.onEvents(this, Player.Events(flags)) }
    }

    /** Springt auch über Dateigrenzen hinweg (Hörbuch aus mehreren MP3-Dateien). */
    private fun seekByMs(deltaMs: Long) {
        val target = currentPosition + deltaMs
        val duration = duration
        when {
            target < 0 && hasPreviousMediaItem() -> {
                val previousDuration = getMediaItemAt(currentMediaItemIndex - 1).mediaMetadata.durationMs
                if (previousDuration != null) {
                    seekTo(currentMediaItemIndex - 1, (previousDuration + target).coerceAtLeast(0))
                } else {
                    seekTo(0)
                }
            }
            duration != C.TIME_UNSET && target > duration && hasNextMediaItem() ->
                seekTo(currentMediaItemIndex + 1, target - duration)
            else -> seekTo(target.coerceIn(0, duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE))
        }
    }
}
