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
 * Audiobooks and episodes use different skip distances than music, configurable per area (concept 12.4). Media3
 * only knows one pair per player; that's why this wrapper answers [getSeekBackIncrement], [getSeekForwardIncrement],
 * [seekBack] and [seekForward] depending on the current item. Notification, Android Auto, widget and app all talk to
 * the session and thus to this player.
 *
 * If the skip distances change (different item, new setting), it reports this to its listeners: the session passes it on to the
 * controllers, which would otherwise calculate with the old distance (the app briefly showed a wrong position after seeking).
 *
 * The current chapter ([chapterTitle]) is put in the player's metadata before the author ("Chapter 12 · Author"), i.e. in
 * notification, lock screen and Android Auto. The app reads the items' metadata and sees none of this.
 */
@OptIn(UnstableApi::class)
internal class SpokenAwarePlayer(player: Player) : ForwardingPlayer(player) {

    /** From the settings; only set on the main thread. */
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

    /**
     * Seeks by the given milliseconds from the current position in the loaded book, also to files the player doesn't hold: while casting it holds
     * only a window of a book with many files (review5 P13). `false` if that is not possible. Main thread.
     */
    var bookSeek: ((deltaMs: Long) -> Boolean)? = null

    /** Chapter of the current book or episode; only set on the main thread. */
    var chapterTitle: String? = null
        set(value) {
            if (field == value) return
            field = value
            reportMetadata()
        }

    /**
     * Metadata from the underlying player (new item, tags from the file) reaches the listeners without the chapter. This
     * listener is always registered last there (see [addListener]) and reports it afterwards with the chapter.
     */
    private val chapterRedispatch = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            if (chapterTitle != null && currentMediaItem?.isSpoken == true) reportMetadata()
        }
    }

    /** The listeners registered here (they are forwarded to the underlying player); main thread. */
    private val listeners = LinkedHashSet<Player.Listener>()
    private var reportedBack = seekBackIncrement
    private var reportedForward = seekForwardIncrement

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = reportIncrements()
        })
    }

    /** Skip distances of the current item; `null` for music. */
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

    /** Also seeks across file boundaries (audiobook made of several MP3 files), beyond the player's window too ([bookSeek]). */
    private fun seekByMs(deltaMs: Long) {
        val target = currentPosition + deltaMs
        val duration = duration
        val beyondFile = target < 0 || (duration != C.TIME_UNSET && target > duration)
        val neighbor = if (target < 0) hasPreviousMediaItem() else hasNextMediaItem()
        if (beyondFile && !neighbor && bookSeek?.invoke(deltaMs) == true) return
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
