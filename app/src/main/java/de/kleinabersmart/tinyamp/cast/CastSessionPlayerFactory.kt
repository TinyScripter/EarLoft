package de.kleinabersmart.tinyamp.cast

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.compose.material3.SnackbarDuration
import androidx.media3.cast.Cast
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlayerTransferState
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.core.data.di.ApplicationScope
import de.kleinabersmart.tinyamp.core.playback.QueueReserve
import de.kleinabersmart.tinyamp.core.playback.SessionPlayerFactory
import de.kleinabersmart.tinyamp.core.playback.queueWindow
import de.kleinabersmart.tinyamp.core.playback.songId
import de.kleinabersmart.tinyamp.ui.snackbar.Snackbars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chromecast (Default Media Receiver): [CastPlayer] wechselt beim Verbinden selbst zwischen dem lokalen
 * ExoPlayer und dem Empfänger. Der Empfänger lädt direkt vom Server; Songs, die nur auf dem Handy liegen,
 * werden beim Casten übersprungen. Bücher und Folgen spielt der Empfänger von Audiobookshelf bzw. aus dem Feed. ReplayGain und Gapless gibt es dort nicht.
 *
 * Die Warteschlange geht als eine Nachricht an den Empfänger, und Cast-Nachrichten dürfen höchstens
 * 64 KB groß sein – ein Eintrag braucht etwa 700 Byte (zwei URLs mit Zugangsdaten plus Metadaten).
 * Größere Nachrichten verwirft der Empfänger ohne Fehlermeldung (am Gerät gesehen: 620 Titel).
 * Deshalb geht beim Verbinden nur ein Fenster von höchstens [MAX_REMOTE_QUEUE] Titeln hinaus; der Rest liegt in
 * [reserve] und wird in kleinen Stücken ([REFILL_CHUNK]) angehängt, sobald weniger als [REFILL_BELOW] Titel
 * folgen ([refillIfNeeded]). Beim Wechsel zurück aufs Handy kommt die ganze Warteschlange wieder zusammen.
 *
 * Kann der Empfänger die Titel nicht laden (zum Beispiel weil er den Server nicht erreicht oder dessen
 * Zertifikat ablehnt), bleibt seine Warteschlange leer und der Player zeigt nichts mehr an. Dann beendet
 * [watchRemoteLoad] die Sitzung und die ursprüngliche Warteschlange kommt pausiert aufs Handy zurück – beim
 * Verbinden wie bei jeder neuen Warteschlange während der Sitzung ([RemoteQueueWatch]). Ein Empfänger in diesem
 * Zustand lehnte sonst auch alles Weitere ab („Invalid Request“), und ein danach gestartetes Buch ginge ins Leere.
 */
@OptIn(UnstableApi::class)
@Singleton
internal class CastSessionPlayerFactory @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val cache: CastMediaCache,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val snackbars: Snackbars,
) : SessionPlayerFactory {

    private var sessionListener: SessionManagerListener<CastSession>? = null
    private val handOver = HandOver()
    private val reserve = QueueReserve<MediaItem>()

    /** Zählt jede neue Warteschlange; ein Nachladen, das auf eine ältere zurückgeht, wird verworfen. */
    private var generation = 0
    private var refilling = false

    /** Startet die Prüfung von [watchRemoteLoad] für eine während der Sitzung gesetzte Warteschlange. */
    private var watchQueue: (PlayerTransferState) -> Unit = {}

    override fun create(context: Context, localPlayer: ExoPlayer): Player {
        val remotePlayer = RemoteCastPlayer.Builder(context)
            .setMediaItemConverter(CastMediaItemConverter(cache))
            .build()
        val player = CastPlayer.Builder(context)
            .setLocalPlayer(localPlayer)
            .setRemotePlayer(remotePlayer)
            .setTransferCallback(CastTransferCallback(cache, handOver, reserve, ::showSkipped))
            .build()
        prepareOnSessionStart(player)
        watchRemoteLoad(player)
        keepQueueFilled(player)
        return DeferredRemotePlayPlayer(player) { state -> watchQueue(state) }
    }

    override suspend fun playableSongIds(player: Player, songIds: Collection<Long>): Set<Long> {
        if (player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) return songIds.toSet()
        cache.prepare(songIds)
        val castable = songIds.filterTo(HashSet(), cache::isCastable)
        showSkipped(skipped = songIds.size - castable.size, total = songIds.size)
        return castable
    }

    override fun maxQueueSize(player: Player): Int =
        if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) MAX_REMOTE_QUEUE else Int.MAX_VALUE

    override fun onQueueReplaced(before: List<MediaItem>, after: List<MediaItem>) {
        generation++
        reserve.reset(before, after)
    }

    override fun hasPendingItems(): Boolean = reserve.remaining > 0

    override fun appendPending(items: List<MediaItem>) = reserve.append(items)

    private fun keepQueueFilled(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refillIfNeeded(player)
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = refillIfNeeded(player)
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refillIfNeeded(player)
            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = refillIfNeeded(player)
        })
    }

    /** Main-Thread. Hängt das nächste Stück der [reserve] an, sobald dem Empfänger die Titel auszugehen drohen. */
    private fun refillIfNeeded(player: Player) {
        if (refilling || reserve.remaining == 0 || player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) return
        if (itemsAhead(player) >= REFILL_BELOW) return
        refilling = true
        val expected = generation
        val songIds = reserve.peek(REFILL_CHUNK).mapNotNull { it.songId }
        scope.launch {
            // Der Konverter muss synchron liefern; die Adressen werden deshalb vorher aufgelöst.
            cache.prepare(songIds)
            withContext(Dispatchers.Main) {
                refilling = false
                val stillValid = expected == generation && player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
                if (!stillValid) return@withContext
                val next = reserve.take(REFILL_CHUNK)
                if (next.isEmpty()) return@withContext
                player.addMediaItems(next)
                refillIfNeeded(player)
            }
        }
    }

    /** Wie viele Titel nach dem aktuellen noch kommen (in Wiedergabereihenfolge), gezählt bis [REFILL_BELOW]. */
    private fun itemsAhead(player: Player): Int {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return 0
        var index = player.currentMediaItemIndex
        var count = 0
        while (count < REFILL_BELOW) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) break
            count++
        }
        return count
    }

    /**
     * Beim Verbinden übergibt [CastPlayer] die Warteschlange sofort und synchron. Zwischen „verbindet“
     * und „verbunden“ startet der Empfänger seine App (meist über eine Sekunde) – Zeit genug, die
     * Adressen aufzulösen.
     */
    private fun prepareOnSessionStart(player: Player) {
        val cast = Cast.getSingletonInstance(appContext)
        sessionListener?.let(cast::removeSessionManagerListener)
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarting(session: CastSession) = prepareQueue()
            override fun onSessionResuming(session: CastSession, sessionId: String) = prepareQueue()
            override fun onSessionStarted(session: CastSession, sessionId: String) = Unit
            override fun onSessionStartFailed(session: CastSession, error: Int) = Unit
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionEnded(session: CastSession, error: Int) = Unit
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = Unit
            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit

            private fun prepareQueue() {
                cache.clear()
                val songIds = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).songId }
                scope.launch { cache.prepare(songIds) }
            }
        }
        cast.addSessionManagerListener(listener)
        sessionListener = listener
    }

    /**
     * Hat der Empfänger eine Warteschlange verworfen, die beim Verbinden oder während der Sitzung an ihn ging
     * ([RemoteQueueWatch], Frist [QUEUE_WATCH_MS]), endet die Sitzung und sie kommt pausiert aufs Handy zurück.
     * Bis 2026-11-04 galt beim Verbinden „nach 8 s noch leer“; Bücher von Audiobookshelf brauchen am Empfänger aber
     * etwa 9 s, und das Verbinden scheiterte daran.
     */
    private fun watchRemoteLoad(player: Player) {
        val handler = Handler(player.applicationLooper)
        val queueWatch = RemoteQueueWatch()
        fun isRemote() = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
        fun itemIds() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        fun rescue() {
            val handedOver = handOver.state ?: return
            queueWatch.stop()
            handOver.rescue = handedOver
            snackbars.show(appContext.getString(R.string.cast_load_failed), SnackbarDuration.Long)
            Cast.getSingletonInstance(appContext).endCurrentSession(true)
        }
        val timeout = Runnable { if (queueWatch.onTimeout(player.mediaItemCount) && isRemote()) rescue() }
        fun watch(state: PlayerTransferState) {
            handler.removeCallbacks(timeout)
            queueWatch.start(state.mediaItems.map { it.mediaId })
            // Der Stand vor dem Senden (beim Verbinden: leer) zählt schon mit.
            queueWatch.onItems(itemIds())
            handler.postDelayed(timeout, QUEUE_WATCH_MS)
        }
        watchQueue = { state ->
            // Was nicht in die Nachricht passte, liegt in der Reserve (gerade erst neu gesetzt); zurück aufs Handy geht alles.
            handOver.state = if (reserve.isEmpty()) {
                state
            } else {
                val (all, index) = reserve.complete(state.mediaItems, state.currentMediaItemIndex)
                state.buildUpon().setMediaItems(all).setCurrentMediaItemIndex(index).build()
            }
            handOver.state?.let(::watch)
        }
        player.addListener(object : Player.Listener {
            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                handler.removeCallbacks(timeout)
                queueWatch.stop()
                val handedOver = handOver.state
                if (isRemote() && handedOver != null) watch(handedOver)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (!queueWatch.isWatching || !isRemote()) return
                if (queueWatch.onItems(itemIds())) {
                    handler.removeCallbacks(timeout)
                    rescue()
                }
            }
        })
    }

    /** Main-Thread. */
    private fun showSkipped(skipped: Int, total: Int) {
        if (skipped <= 0) return
        val text = if (skipped == total) {
            appContext.resources.getQuantityString(R.plurals.cast_nothing_castable, total)
        } else {
            appContext.resources.getQuantityString(R.plurals.cast_skipped_local, skipped, skipped)
        }
        snackbars.show(text, SnackbarDuration.Long)
    }

    companion object {
        const val MAX_REMOTE_QUEUE = 50
        private const val REFILL_BELOW = 15
        private const val REFILL_CHUNK = 25
        private const val QUEUE_WATCH_MS = 20_000L
    }
}

/**
 * [state]: was zuletzt an den Empfänger ging (nur wenn nicht leer). [rescue]: nach einem gescheiterten
 * Laden – beim Zurückwechseln aufs Handy wird daraus die Warteschlange wiederhergestellt.
 */
@OptIn(UnstableApi::class)
private class HandOver {
    @Volatile var state: PlayerTransferState? = null
    @Volatile var rescue: PlayerTransferState? = null
}

/** Übergibt nur Songs mit Server-Quelle an den Empfänger; zurück aufs Handy geht alles. */
@OptIn(UnstableApi::class)
private class CastTransferCallback(
    private val cache: CastMediaCache,
    private val handOver: HandOver,
    private val reserve: QueueReserve<MediaItem>,
    private val onSkipped: (skipped: Int, total: Int) -> Unit,
) : CastPlayer.TransferCallback {

    override fun transferState(sourcePlayer: Player, targetPlayer: Player) {
        if (targetPlayer !is RemoteCastPlayer) {
            val rescue = handOver.rescue
            handOver.state = null
            handOver.rescue = null
            val remaining = reserve.takeUnless { it.isEmpty() }
            if (rescue != null) {
                rescue.buildUpon().setPlayWhenReady(false).build().setToPlayer(targetPlayer)
            } else if (remaining != null) {
                val state = PlayerTransferState.fromPlayer(sourcePlayer)
                val (all, index) = remaining.complete(state.mediaItems, state.currentMediaItemIndex)
                state.buildUpon().setMediaItems(all).setCurrentMediaItemIndex(index).build().setToPlayer(targetPlayer)
            } else {
                CastPlayer.TransferCallback.DEFAULT.transferState(sourcePlayer, targetPlayer)
            }
            reserve.clear()
            return
        }
        val state = PlayerTransferState.fromPlayer(sourcePlayer)
        handOver.rescue = null
        reserve.clear()
        handOver.state = state.takeIf { it.mediaItems.isNotEmpty() }
        val items = state.mediaItems
        cache.prepareBlocking(items.mapNotNull { it.songId })
        val castable = items.indices.filter { cache.isCastable(items[it]) }
        if (castable.size == items.size && items.size <= CastSessionPlayerFactory.MAX_REMOTE_QUEUE) {
            state.setToPlayer(targetPlayer)
            return
        }
        onSkipped(items.size - castable.size, items.size)
        if (castable.isEmpty()) {
            state.buildUpon().setMediaItems(emptyList()).setCurrentMediaItemIndex(C.INDEX_UNSET).build().setToPlayer(targetPlayer)
            return
        }
        val current = state.currentMediaItemIndex
        val startInCastable = castable.indexOfFirst { it >= current }.takeIf { it >= 0 } ?: 0
        val window = queueWindow(castable, startInCastable, CastSessionPlayerFactory.MAX_REMOTE_QUEUE, sourcePlayer.shuffleModeEnabled)
        reserve.reset(window.before.map(items::get), window.after.map(items::get))
        state.buildUpon()
            .setMediaItems(window.items.map(items::get))
            .setCurrentMediaItemIndex(window.startIndex)
            .apply { if (window.items[window.startIndex] != current) setCurrentPosition(0) }
            .build()
            .setToPlayer(targetPlayer)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface CastModule {
    @Binds
    fun bindSessionPlayerFactory(factory: CastSessionPlayerFactory): SessionPlayerFactory
}
