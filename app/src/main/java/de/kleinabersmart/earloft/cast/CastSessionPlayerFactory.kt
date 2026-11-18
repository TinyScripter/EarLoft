package de.kleinabersmart.earloft.cast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.util.Log
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
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.repository.PhoneCastServer
import de.kleinabersmart.earloft.core.playback.QueuePersistence
import de.kleinabersmart.earloft.core.playback.QueueReserve
import de.kleinabersmart.earloft.core.playback.SessionPlayerFactory
import de.kleinabersmart.earloft.core.playback.SpokenItemRebuilder
import de.kleinabersmart.earloft.core.playback.oldestPlayed
import de.kleinabersmart.earloft.core.playback.queueWindow
import de.kleinabersmart.earloft.core.playback.songId
import de.kleinabersmart.earloft.ui.snackbar.Snackbars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chromecast (Default Media Receiver): [CastPlayer] wechselt beim Verbinden selbst zwischen dem lokalen
 * ExoPlayer und dem Empfänger. Der Empfänger lädt direkt vom Server; Songs, Bücher und Folgen, die nur auf dem Handy liegen,
 * liefert das Handy selbst aus ([PhoneMediaServer], nur im WLAN und in Formaten, die der Empfänger kennt; sonst werden sie
 * übersprungen). Bücher und Folgen spielt der Empfänger sonst von Audiobookshelf bzw. aus dem Feed. ReplayGain und Gapless gibt es dort nicht.
 *
 * Die Warteschlange geht als eine Nachricht an den Empfänger, und Cast-Nachrichten dürfen höchstens
 * 64 KB groß sein – ein Eintrag braucht etwa 700 Byte (zwei URLs mit Zugangsdaten plus Metadaten).
 * Größere Nachrichten verwirft der Empfänger ohne Fehlermeldung (am Gerät gesehen: 620 Titel). Sein Status trägt außerdem
 * jedes Mal die ganze Warteschlange (etwa 820 Byte je Titel); über etwa 75 Titel kommt keiner mehr an.
 * Deshalb hält der Empfänger höchstens [MAX_REMOTE_QUEUE] Titel; der Rest liegt in [reserve] und wird in kleinen Stücken
 * ([REFILL_CHUNK]) angehängt, sobald weniger als [REFILL_BELOW] Titel folgen und der Empfänger die vorige Warteschlange
 * gemeldet hat ([refillIfNeeded]); dafür gehen die am längsten gespielten Titel aus dem Empfänger in die [reserve]
 * ([makeRoom]). Beim Wechsel zurück aufs Handy kommt die ganze Warteschlange wieder zusammen.
 *
 * Kann der Empfänger die Titel nicht laden (zum Beispiel weil er den Server nicht erreicht oder dessen
 * Zertifikat ablehnt), bleibt seine Warteschlange leer und der Player zeigt nichts mehr an ([RemoteQueueWatch], beim
 * Verbinden wie bei jeder neuen Warteschlange während der Sitzung). Liegen die Songs auch auf einem anderen Server, geht
 * die Warteschlange noch einmal von dort hinaus ([watchRemoteLoad]); sonst endet die Sitzung und die ursprüngliche
 * Warteschlange kommt pausiert aufs Handy zurück. Ein Empfänger ohne Warteschlange lehnte sonst auch alles Weitere ab
 * („Invalid Request“), und ein danach gestartetes Buch ginge ins Leere.
 *
 * Lief beim letzten Ende des Prozesses eine Sitzung, hängt sich der Player beim Start wieder an ([CastRejoin]) und übernimmt
 * den Stand des Empfängers. Was nur in der [reserve] lag, ist dann verloren.
 */
@OptIn(UnstableApi::class)
@Singleton
internal class CastSessionPlayerFactory @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val cache: CastMediaCache,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val snackbars: Snackbars,
    private val spokenItems: SpokenItemRebuilder,
    private val rejoin: CastRejoin,
    private val phone: PhoneMediaServer,
    private val queuePersistence: QueuePersistence,
) : SessionPlayerFactory {

    private var sessionListener: SessionManagerListener<CastSession>? = null
    private var castPlayer: Player? = null
    private val handOver = HandOver()
    private val reserve = QueueReserve<MediaItem>()

    /** Zählt jede neue Warteschlange; ein Nachladen, das auf eine ältere zurückgeht, wird verworfen. */
    private var generation = 0
    private var refilling = false

    /**
     * Media-IDs der Warteschlange, die zuletzt an den Empfänger ging, bis er sie meldet. So lange zeigt der Player eine leere
     * (oder kurz noch die alte) Zeitachse; ein Nachladen darauf hielte jedes Stück für das letzte, hängte die ganze Reserve
     * auf einmal an, und der Empfänger verwarf die Stücke mitten im Laden (am Gerät gesehen, 2026-11-18: 1401 Titel, nach
     * dem Verbinden blieb es bei 50).
     */
    private var awaitedQueue: Set<String>? = null

    /** Kennungen (Item-IDs des Empfängers) der Titel, die [makeRoom] gerade aus dem Empfänger nimmt, bis er es meldet. */
    private var removing: List<Any>? = null
    private var sessionPlayer: DeferredRemotePlayPlayer? = null
    private var refillHandler: Handler? = null
    private val removalTimeout = Runnable { finishRemoval() }

    /** Startet die Prüfung von [watchRemoteLoad] für eine während der Sitzung gesetzte Warteschlange. */
    private var watchQueue: (PlayerTransferState) -> Unit = {}

    override fun create(context: Context, localPlayer: ExoPlayer): Player {
        val remotePlayer = RemoteCastPlayer.Builder(context)
            .setMediaItemConverter(CastMediaItemConverter(cache))
            .build()
        val player = CastPlayer.Builder(context)
            .setLocalPlayer(localPlayer)
            .setRemotePlayer(remotePlayer)
            .setTransferCallback(
                CastTransferCallback(cache, handOver, reserve, spokenItems::playable, ::showSkipped, ::awaitQueue) {
                    // Wieder angehängt: Der Player des Handys ist nach dem Neustart leer, der Zufall der Musik steht im Gespeicherten.
                    scope.launch { handOver.phoneShuffle = queuePersistence.savedShuffle() }
                },
            )
            .build()
        castPlayer = player
        prepareOnSessionStart(player)
        watchRemoteLoad(player)
        keepQueueFilled(player)
        rememberRemoteState(player)
        scope.launch(Dispatchers.Main) { rejoin.select() }
        return DeferredRemotePlayPlayer(player, onRemoteQueue = { state -> watchQueue(state) }, onClearedToEnd = ::clearPending)
            .also { sessionPlayer = it }
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
        finishRemoval()
        reserve.reset(before, after)
    }

    override fun hasPendingItems(): Boolean = reserve.remaining > 0

    override fun appendPending(items: List<MediaItem>) {
        reserve.append(items)
        // Hatte der Empfänger keinen Platz mehr (Endlos-Mix beim letzten Titel), kommt sonst erst beim nächsten Wechsel etwas.
        castPlayer?.let(::refillIfNeeded)
    }

    /**
     * Alles hinter dem laufenden Titel ist entfernt (Mix): Auch was noch nicht beim Empfänger war, fällt weg, sonst käme es
     * vor den neuen Titeln (Review D8). Ein laufendes Nachladen gehört zur alten Warteschlange.
     */
    private fun clearPending() {
        generation++
        reserve.clearAfter()
    }

    private fun keepQueueFilled(player: Player) {
        refillHandler = Handler(player.applicationLooper)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refillIfNeeded(player)
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refillIfNeeded(player)

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val removed = removing
                if (removed != null && removed.all { timeline.getIndexOfPeriod(it) == C.INDEX_UNSET }) finishRemoval()
                refillIfNeeded(player)
            }

            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                finishRemoval()
                refillIfNeeded(player)
            }
        })
    }

    /** Main-Thread. Das Entfernen von [makeRoom] ist gemeldet (oder überholt): Zurückgehaltene Sprünge laufen jetzt. */
    private fun finishRemoval() {
        if (removing == null) return
        removing = null
        refillHandler?.removeCallbacks(removalTimeout)
        sessionPlayer?.releaseSeeks()
    }

    /**
     * Übernimmt eine andere App den Empfänger, ist seine Warteschlange leer, und beim Wechsel zurück aufs Handy käme nichts
     * an. Deshalb merkt sich [HandOver.lastRemote] den Stand des Empfängers bei jedem Ereignis und alle [SNAPSHOT_MS], solange
     * er etwas hat; die Position ist dann höchstens so alt. Zugleich erfährt [CastRejoin], bis wann er noch spielt.
     */
    private fun rememberRemoteState(player: Player) {
        val handler = Handler(player.applicationLooper)
        fun isRemote() = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
        fun snapshot() {
            if (!isRemote() || player.mediaItemCount == 0) return
            handOver.lastRemote = PlayerTransferState.fromPlayer(player)
            rejoin.keepUntil(playingUntil(player))
        }
        val tick = object : Runnable {
            override fun run() {
                snapshot()
                if (isRemote()) handler.postDelayed(this, SNAPSHOT_MS)
            }
        }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = snapshot()

            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                handler.removeCallbacks(tick)
                if (isRemote()) handler.postDelayed(tick, SNAPSHOT_MS)
            }
        })
    }

    /** Main-Thread. [items] ging gerade als neue Warteschlange an den Empfänger. */
    private fun awaitQueue(items: List<MediaItem>) {
        awaitedQueue = items.mapTo(HashSet()) { it.mediaId }.takeIf { it.isNotEmpty() }
    }

    /** Main-Thread. Ob der Empfänger die zuletzt gesendete Warteschlange gemeldet hat ([awaitedQueue]). */
    private fun hasReportedQueue(player: Player): Boolean {
        if (player.currentTimeline.isEmpty) return false
        val awaited = awaitedQueue ?: return true
        if ((0 until player.mediaItemCount).none { player.getMediaItemAt(it).mediaId in awaited }) return false
        awaitedQueue = null
        return true
    }

    /** Main-Thread. Hängt das nächste Stück der [reserve] an, sobald dem Empfänger die Titel auszugehen drohen. */
    private fun refillIfNeeded(player: Player) {
        if (refilling || reserve.remaining == 0 || player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) return
        if (!hasReportedQueue(player) || itemsAhead(player) >= REFILL_BELOW) return
        refilling = true
        val expected = generation
        val songIds = reserve.peek(REFILL_CHUNK).mapNotNull { it.songId }
        scope.launch {
            // Der Konverter muss synchron liefern; die Adressen werden deshalb vorher aufgelöst.
            cache.prepare(songIds)
            withContext(Dispatchers.Main) {
                refilling = false
                val stillValid = expected == generation && player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
                if (!stillValid || !hasReportedQueue(player)) return@withContext
                // Erst wenn der Empfänger das Entfernen gemeldet hat, ist Platz; dann geht es über [onTimelineChanged] weiter.
                if (!makeRoom(player)) return@withContext
                val room = MAX_REMOTE_QUEUE - player.mediaItemCount
                val next = reserve.take(minOf(REFILL_CHUNK, room))
                if (next.isEmpty()) return@withContext
                player.addMediaItems(next)
                // Erst weiter nachladen, wenn der Empfänger das Stück gemeldet hat; bis dahin zählt [itemsAhead] es nicht mit.
                awaitQueue(next)
            }
        }
    }

    /**
     * Main-Thread. Der Empfänger schickt in jedem Status alle Titel seiner Warteschlange mit (etwa 820 Byte je Titel, 2026-11-18
     * gemessen); über etwa 75 Titel passt der Status nicht mehr in eine Nachricht, und die App erfährt nichts mehr vom Empfänger.
     * Deshalb bleibt die Warteschlange dort bei höchstens [MAX_REMOTE_QUEUE]: Damit ein Stück ([REFILL_CHUNK]) Platz hat, gehen die am
     * längsten gespielten Titel aus dem Empfänger in die [reserve] (vor das Fenster) und kommen beim Wechsel aufs Handy zurück.
     */
    private fun makeRoom(player: Player): Boolean {
        if (removing != null) return false
        val excess = player.mediaItemCount + REFILL_CHUNK - MAX_REMOTE_QUEUE
        if (excess <= 0) return true
        val timeline = player.currentTimeline
        val shuffle = player.shuffleModeEnabled
        val drop = oldestPlayed(player.currentMediaItemIndex, excess) { index ->
            timeline.getPreviousWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
        if (drop.isEmpty()) return true
        // Nicht, solange ein Sprung noch unbestätigt sein kann (siehe [DeferredRemotePlayPlayer.holdSeeks]).
        val sinceSeek = SystemClock.elapsedRealtime() - (sessionPlayer?.lastSeekAtMs ?: 0L)
        if (sinceSeek < SEEK_SETTLE_MS) {
            refillHandler?.postDelayed({ refillIfNeeded(player) }, SEEK_SETTLE_MS - sinceSeek)
            return false
        }
        val window = Timeline.Window()
        removing = drop.map { timeline.getWindow(it, window).uid }
        sessionPlayer?.holdSeeks()
        refillHandler?.postDelayed(removalTimeout, REMOVAL_TIMEOUT_MS)
        reserve.keepBefore(drop.map(player::getMediaItemAt))
        // Von hinten, damit die Indizes davor stimmen; zusammenhängende Stücke in einer Nachricht.
        drop.ranges().asReversed().forEach { range -> player.removeMediaItems(range.first, range.last + 1) }
        return false
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
            override fun onSessionStarting(session: CastSession) {
                handOver.resuming = rejoin.isRejoining(session)
                handOver.checkRejoin = handOver.resuming
                handOver.rejected = false
                // Vor dem Auflösen: Adressen vom Handy gelten nur in dieser Sitzung.
                phone.newSession()
                prepareQueue()
            }

            override fun onSessionResuming(session: CastSession, sessionId: String) = prepareQueue()

            override fun onSessionStarted(session: CastSession, sessionId: String) {
                // Nicht `resuming`: Das setzt `transferState` zurück, und das läuft je nach Reihenfolge der Listener schon vorher
                // (im `onSessionStarted` von RemoteCastPlayer).
                val checkRejoin = handOver.checkRejoin
                handOver.checkRejoin = false
                if (checkRejoin && !rejoin.isOurs(sessionId)) {
                    // Nicht mehr die Sitzung von vorher: Lief beim Empfänger nichts, hat das Auswählen die App dort
                    // gestartet, und sie wird wieder beendet; sonst gehört sie einer anderen App und läuft weiter.
                    val launched = session.applicationConnectionResult?.wasLaunched == true
                    Log.i(TAG, "Sitzung $sessionId ist nicht die von vorher (neu gestartet: $launched), löse mich wieder")
                    handOver.rejected = true
                    cast.endCurrentSession(launched)
                    return
                }
                rejoin.remember(session, sessionId)
            }

            override fun onSessionStartFailed(session: CastSession, error: Int) {
                phone.stop()
                rejoin.failed()
                handOver.resuming = false
                handOver.checkRejoin = false
            }

            override fun onSessionEnding(session: CastSession) = rejoin.forget()
            override fun onSessionEnded(session: CastSession, error: Int) {
                rejoin.failed()
                rejoin.forget()
                phone.stop()
            }
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                val sessionId = session.sessionId ?: return
                rejoin.remember(session, sessionId)
            }

            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit

            private fun prepareQueue() {
                cache.clear()
                val songIds = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).songId }
                scope.launch {
                    cache.prepareServerKeys()
                    cache.prepare(songIds)
                }
            }
        }
        cast.addSessionManagerListener(listener)
        sessionListener = listener
    }

    /**
     * Hat der Empfänger eine Warteschlange verworfen, die beim Verbinden oder während der Sitzung an ihn ging
     * ([RemoteQueueWatch], Frist [QUEUE_WATCH_MS]), erreicht er vermutlich den Server des aktuellen Songs nicht. Der Server
     * bleibt dann für die Sitzung außen vor ([CastMediaCache.exclude]), und die Warteschlange geht noch einmal mit den
     * Songs hinaus, die auch auf einem anderen Server liegen (`retryElsewhere`; am Gerät: Jellyfin per öffentlicher Adresse
     * unerreichbar, derselbe Song von Emby im LAN). Bleibt nichts übrig, endet die Sitzung und die Warteschlange kommt
     * pausiert aufs Handy zurück. Bis 2026-11-04 galt beim Verbinden „nach 8 s noch leer“; Bücher von Audiobookshelf
     * brauchen am Empfänger aber etwa 9 s, und das Verbinden scheiterte daran.
     */
    private fun watchRemoteLoad(player: Player) {
        val handler = Handler(player.applicationLooper)
        val queueWatch = RemoteQueueWatch()
        fun isRemote() = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
        fun itemIds() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        fun rescue(handedOver: PlayerTransferState) {
            queueWatch.stop()
            handOver.rescue = handedOver
            snackbars.show(appContext.getString(R.string.cast_load_failed), SnackbarDuration.Long)
            Cast.getSingletonInstance(appContext).endCurrentSession(true)
        }
        lateinit var watch: (PlayerTransferState) -> Unit

        fun retryElsewhere(handedOver: PlayerTransferState, failedSource: String) {
            val expected = ++generation
            scope.launch {
                cache.prepare(handedOver.mediaItems.mapNotNull { it.songId })
                withContext(Dispatchers.Main) {
                    if (expected != generation || !isRemote() || handOver.state !== handedOver) return@withContext
                    if (handedOver.mediaItems.none(cache::isCastable)) {
                        rescue(handedOver)
                        return@withContext
                    }
                    Log.i(TAG, "Empfänger erreicht $failedSource nicht, die Warteschlange geht von einem anderen Server hinaus")
                    reserve.clear()
                    val retry = castableState(handedOver, cache, reserve, ::showSkipped)
                    awaitQueue(retry.mediaItems)
                    retry.setToPlayer(player)
                    watch(handedOver)
                }
            }
        }

        fun onLoadFailed() {
            val handedOver = handOver.state ?: return
            queueWatch.stop()
            val failedSource = handedOver.mediaItems.getOrNull(handedOver.currentMediaItemIndex)?.let(cache::get)?.sourceId
            if (failedSource != null && cache.exclude(failedSource)) retryElsewhere(handedOver, failedSource) else rescue(handedOver)
        }
        val timeout = Runnable { if (queueWatch.onTimeout(player.mediaItemCount) && isRemote()) onLoadFailed() }
        watch = { state ->
            handler.removeCallbacks(timeout)
            queueWatch.start(state.mediaItems.map { it.mediaId })
            // Der Stand vor dem Senden (beim Verbinden: leer) zählt schon mit.
            queueWatch.onItems(itemIds())
            handler.postDelayed(timeout, QUEUE_WATCH_MS)
        }
        watchQueue = { state ->
            awaitQueue(state.mediaItems)
            // Was nicht in die Nachricht passte, liegt in der Reserve (gerade erst neu gesetzt); zurück aufs Handy geht alles.
            handOver.state = if (reserve.isEmpty()) {
                state
            } else {
                val (all, index) = reserve.complete(state.mediaItems, state.currentMediaItemIndex)
                state.buildUpon().setMediaItems(all).setCurrentMediaItemIndex(index).build()
            }
            handOver.state?.let { watch(it) }
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
                    onLoadFailed()
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

        /** So lange nach einem Sprung kann er beim Empfänger noch unbestätigt sein. */
        private const val SEEK_SETTLE_MS = 2_000L

        /** Spätestens dann laufen zurückgehaltene Sprünge, auch ohne Meldung des Empfängers. */
        private const val REMOVAL_TIMEOUT_MS = 3_000L
        private const val SNAPSHOT_MS = 5_000L
        private const val TAG = "CastSession"
    }
}

/**
 * Was vom Stand [state] an den Empfänger geht: nur Songs, die er laden kann (Adressen in [cache] vorbereitet), höchstens
 * [CastSessionPlayerFactory.MAX_REMOTE_QUEUE] um den aktuellen herum; der Rest kommt in die [reserve]. Meldet übersprungene
 * Songs über [onSkipped].
 */
@OptIn(UnstableApi::class)
private fun castableState(
    state: PlayerTransferState,
    cache: CastMediaCache,
    reserve: QueueReserve<MediaItem>,
    onSkipped: (skipped: Int, total: Int) -> Unit,
): PlayerTransferState {
    val items = state.mediaItems
    val castable = items.indices.filter { cache.isCastable(items[it]) }
    if (castable.size == items.size && items.size <= CastSessionPlayerFactory.MAX_REMOTE_QUEUE) return state
    onSkipped(items.size - castable.size, items.size)
    if (castable.isEmpty()) {
        return state.buildUpon().setMediaItems(emptyList()).setCurrentMediaItemIndex(C.INDEX_UNSET).build()
    }
    val current = state.currentMediaItemIndex
    val startInCastable = castable.indexOfFirst { it >= current }.takeIf { it >= 0 } ?: 0
    val window = queueWindow(castable, startInCastable, CastSessionPlayerFactory.MAX_REMOTE_QUEUE, state.shuffleModeEnabled)
    reserve.reset(window.before.map(items::get), window.after.map(items::get))
    return state.buildUpon()
        .setMediaItems(window.items.map(items::get))
        .setCurrentMediaItemIndex(window.startIndex)
        .apply { if (window.items[window.startIndex] != current) setCurrentPosition(0) }
        .build()
}

/** Aufsteigende Indizes als zusammenhängende Bereiche. */
private fun List<Int>.ranges(): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    for (index in this) {
        val last = ranges.lastOrNull()
        if (last != null && last.last + 1 == index) ranges[ranges.lastIndex] = last.first..index else ranges += index..index
    }
    return ranges
}

/**
 * Bis wann (Wanduhr) [player] mit seiner Warteschlange voraussichtlich spielt: Rest des aktuellen und alle folgenden Titel
 * beim aktuellen Tempo; mit Wiederholung einen Tag. `null`, wenn er nicht spielt oder die Dauer unbekannt ist.
 */
private fun playingUntil(player: Player): Long? {
    if (!player.isPlaying || player.duration == C.TIME_UNSET) return null
    val now = System.currentTimeMillis()
    if (player.repeatMode != Player.REPEAT_MODE_OFF) return now + 24 * 60 * 60_000L
    val timeline = player.currentTimeline
    val window = Timeline.Window()
    var remainingMs = (player.duration - player.currentPosition).coerceAtLeast(0)
    for (index in player.currentMediaItemIndex + 1 until timeline.windowCount) {
        val durationMs = timeline.getWindow(index, window).durationMs
        if (durationMs != C.TIME_UNSET) remainingMs += durationMs
    }
    return now + (remainingMs / player.playbackParameters.speed).toLong()
}

/**
 * [state]: was zuletzt an den Empfänger ging (nur wenn nicht leer). [rescue]: nach einem gescheiterten
 * Laden – beim Zurückwechseln aufs Handy wird daraus die Warteschlange wiederhergestellt. [lastRemote]: der letzte
 * Stand des Empfängers mit Warteschlange, falls eine andere App ihn übernimmt. [resuming]: Die App hängt sich nach einem
 * Neustart wieder an ([CastRejoin]) – der Empfänger behält dann seinen Stand. [checkRejoin]: Beim Start der Sitzung ist zu
 * prüfen, ob es die von vorher ist; anders als [resuming] setzt das `transferState` nicht zurück. [rejected]: Dabei kam eine
 * andere Sitzung heraus, das Handy behält seinen Stand. [phoneShuffle]: Zufall am Handy bei der Übergabe; der Cast-Player
 * kennt keinen und gibt „aus“ zurück, beim Zurückwechseln gilt wieder dieser Wert.
 */
@OptIn(UnstableApi::class)
private class HandOver {
    @Volatile var phoneShuffle: Boolean? = null
    @Volatile var resuming = false
    @Volatile var checkRejoin = false
    @Volatile var rejected = false
    @Volatile var state: PlayerTransferState? = null
    @Volatile var rescue: PlayerTransferState? = null
    @Volatile var lastRemote: PlayerTransferState? = null
}

/**
 * Was vom Empfänger aufs Handy zurückgeht: Einträge ohne Adresse auf dem Handy baut [rebuild] neu (Bücher und Folgen nach
 * einem Neustart der App, siehe [SpokenItemRebuilder]); was danach noch keine hat, fällt weg wie bei
 * `TransferCallback.DEFAULT` (fremde Sitzung).
 */
@OptIn(UnstableApi::class)
internal fun PlayerTransferState.playableOnPhone(rebuild: (List<MediaItem>) -> List<MediaItem>): PlayerTransferState {
    val items = rebuild(mediaItems)
    val playable = items.indices.filter { items[it].localConfiguration?.uri.let { uri -> uri != null && uri != Uri.EMPTY } }
    if (playable.size == items.size) return buildUpon().setMediaItems(items).build()
    val current = currentMediaItemIndex
    val index = when {
        playable.isEmpty() -> C.INDEX_UNSET
        else -> playable.indexOfFirst { it >= current }.takeIf { it >= 0 } ?: (playable.size - 1)
    }
    return buildUpon()
        .setMediaItems(playable.map(items::get))
        .setCurrentMediaItemIndex(index)
        .apply { if (current !in playable) setCurrentPosition(0) }
        .build()
}

/** Übergibt nur Songs mit Server-Quelle an den Empfänger; zurück aufs Handy geht alles, was das Handy abspielen kann. */
@OptIn(UnstableApi::class)
private class CastTransferCallback(
    private val cache: CastMediaCache,
    private val handOver: HandOver,
    private val reserve: QueueReserve<MediaItem>,
    private val rebuild: (List<MediaItem>) -> List<MediaItem>,
    private val onSkipped: (skipped: Int, total: Int) -> Unit,
    /** Was als Warteschlange an den Empfänger geht (Fenster ohne Reserve). */
    private val onSent: (List<MediaItem>) -> Unit,
    /** Die App hat sich an eine laufende Sitzung gehängt ([HandOver.resuming]); setzt [HandOver.phoneShuffle] nachträglich. */
    private val onRejoined: () -> Unit,
) : CastPlayer.TransferCallback {

    override fun transferState(sourcePlayer: Player, targetPlayer: Player) {
        if (targetPlayer !is RemoteCastPlayer && handOver.rejected) {
            // Der lokale Player wurde beim Wechsel nur angehalten und hat seine Warteschlange noch.
            handOver.rejected = false
            handOver.resuming = false
            return
        }
        if (targetPlayer !is RemoteCastPlayer) {
            val rescue = handOver.rescue
            val lastRemote = handOver.lastRemote
            handOver.state = null
            handOver.rescue = null
            handOver.lastRemote = null
            val remaining = reserve.takeUnless { it.isEmpty() }
            val source = PlayerTransferState.fromPlayer(sourcePlayer)
            // Leer, weil eine andere App den Empfänger übernommen hat: der letzte Stand kommt pausiert aufs Handy.
            val takenOver = source.mediaItems.isEmpty() && lastRemote != null
            val state = if (takenOver) lastRemote.buildUpon().setPlayWhenReady(false).build() else source
            val back = if (rescue != null) {
                rescue.buildUpon().setPlayWhenReady(false).build()
            } else if (remaining != null) {
                val (all, index) = remaining.complete(state.mediaItems, state.currentMediaItemIndex)
                state.buildUpon().setMediaItems(all).setCurrentMediaItemIndex(index).build()
            } else {
                state
            }
            // Nur für Musik: Ein Buch bzw. eine Folge, die beim Casten gestartet wurde, spielt ohne Zufall.
            val shuffle = handOver.phoneShuffle
            handOver.phoneShuffle = null
            val music = back.mediaItems.getOrNull(back.currentMediaItemIndex)?.songId != null
            val withShuffle = if (shuffle != null && music) back.buildUpon().setShuffleModeEnabled(shuffle).build() else back
            withShuffle.playableOnPhone(rebuild).setToPlayer(targetPlayer)
            reserve.clear()
            return
        }
        val state = PlayerTransferState.fromPlayer(sourcePlayer)
        handOver.rescue = null
        handOver.lastRemote = null
        reserve.clear()
        // Wieder angehängt: Der Empfänger spielt weiter, was er hat. Die Warteschlange des Handys (nach dem Neustart leer
        // oder ein älterer Stand) würde sie sonst ersetzen oder anhalten.
        if (handOver.resuming || handOver.rejected) {
            handOver.phoneShuffle = null
            if (handOver.resuming) onRejoined()
            handOver.resuming = false
            handOver.state = null
            onSent(emptyList())
            return
        }
        handOver.phoneShuffle = state.shuffleModeEnabled
        handOver.state = state.takeIf { it.mediaItems.isNotEmpty() }
        cache.prepareBlocking(state.mediaItems.mapNotNull { it.songId })
        val sent = castableState(state, cache, reserve, onSkipped)
        onSent(sent.mediaItems)
        sent.setToPlayer(targetPlayer)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface CastModule {
    @Binds
    fun bindSessionPlayerFactory(factory: CastSessionPlayerFactory): SessionPlayerFactory

    @Binds
    fun bindPhoneCastServer(server: PhoneMediaServer): PhoneCastServer
}
