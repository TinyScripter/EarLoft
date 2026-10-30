package keinabersmart.kleinaberlaut.cast

import android.content.Context
import android.os.Handler
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
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
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import keinabersmart.kleinaberlaut.core.playback.SessionPlayerFactory
import keinabersmart.kleinaberlaut.core.playback.queueWindow
import keinabersmart.kleinaberlaut.core.playback.songId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chromecast (Default Media Receiver): [CastPlayer] wechselt beim Verbinden selbst zwischen dem lokalen
 * ExoPlayer und dem Empfänger. Der Empfänger lädt direkt vom Server; Songs, die nur auf dem Handy liegen,
 * werden beim Casten übersprungen. ReplayGain und Gapless gibt es dort nicht.
 *
 * Die Warteschlange geht als eine Nachricht an den Empfänger, und Cast-Nachrichten dürfen höchstens
 * 64 KB groß sein – ein Eintrag braucht etwa 700 Byte (zwei URLs mit Zugangsdaten plus Metadaten).
 * Größere Warteschlangen verwirft der Empfänger ohne Fehlermeldung (am Gerät gesehen: 620 Titel).
 * Deshalb hält die Warteschlange beim Casten höchstens [MAX_REMOTE_QUEUE] Titel.
 *
 * Kann der Empfänger die Titel nicht laden (zum Beispiel weil er den Server nicht erreicht oder dessen
 * Zertifikat ablehnt), bleibt seine Warteschlange leer und der Player zeigt nichts mehr an. Dann beendet
 * [watchRemoteLoad] die Sitzung und die ursprüngliche Warteschlange kommt pausiert aufs Handy zurück.
 */
@OptIn(UnstableApi::class)
@Singleton
internal class CastSessionPlayerFactory @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val cache: CastMediaCache,
    @param:ApplicationScope private val scope: CoroutineScope,
) : SessionPlayerFactory {

    private var sessionListener: SessionManagerListener<CastSession>? = null
    private val handOver = HandOver()

    override fun create(context: Context, localPlayer: ExoPlayer): Player {
        val remotePlayer = RemoteCastPlayer.Builder(context)
            .setMediaItemConverter(CastMediaItemConverter(cache))
            .build()
        val player = CastPlayer.Builder(context)
            .setLocalPlayer(localPlayer)
            .setRemotePlayer(remotePlayer)
            .setTransferCallback(CastTransferCallback(cache, handOver, ::showSkipped, ::onQueueLimited))
            .build()
        prepareOnSessionStart(player)
        watchRemoteLoad(player)
        return DeferredRemotePlayPlayer(player)
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

    override fun onQueueLimited(kept: Int, requested: Int) {
        Toast.makeText(appContext, appContext.getString(R.string.cast_queue_limited, kept, requested), Toast.LENGTH_LONG).show()
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
     * Wurde eine nicht leere Warteschlange an den Empfänger übergeben und ist sie nach [REMOTE_LOAD_TIMEOUT_MS]
     * dort immer noch leer, hat der Empfänger sie abgelehnt.
     */
    private fun watchRemoteLoad(player: Player) {
        val handler = Handler(player.applicationLooper)
        val check = Runnable {
            val handedOver = handOver.state ?: return@Runnable
            if (player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE || player.mediaItemCount > 0) return@Runnable
            handOver.rescue = handedOver
            Toast.makeText(appContext, R.string.cast_load_failed, Toast.LENGTH_LONG).show()
            Cast.getSingletonInstance(appContext).endCurrentSession(true)
        }
        player.addListener(object : Player.Listener {
            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                handler.removeCallbacks(check)
                val remote = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
                if (remote && handOver.state != null) handler.postDelayed(check, REMOTE_LOAD_TIMEOUT_MS)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (player.mediaItemCount > 0) handler.removeCallbacks(check)
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
        Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val MAX_REMOTE_QUEUE = 50
        private const val REMOTE_LOAD_TIMEOUT_MS = 8_000L
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
    private val onSkipped: (skipped: Int, total: Int) -> Unit,
    private val onLimited: (kept: Int, requested: Int) -> Unit,
) : CastPlayer.TransferCallback {

    override fun transferState(sourcePlayer: Player, targetPlayer: Player) {
        if (targetPlayer !is RemoteCastPlayer) {
            val rescue = handOver.rescue
            handOver.state = null
            handOver.rescue = null
            if (rescue != null) {
                rescue.buildUpon().setPlayWhenReady(false).build().setToPlayer(targetPlayer)
            } else {
                CastPlayer.TransferCallback.DEFAULT.transferState(sourcePlayer, targetPlayer)
            }
            return
        }
        val state = PlayerTransferState.fromPlayer(sourcePlayer)
        handOver.rescue = null
        handOver.state = state.takeIf { it.mediaItems.isNotEmpty() }
        val items = state.mediaItems
        cache.prepareBlocking(items.mapNotNull { it.songId })
        val castable = items.indices.filter { items[it].songId?.let(cache::isCastable) == true }
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
        if (window.items.size < castable.size) onLimited(window.items.size, castable.size)
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
