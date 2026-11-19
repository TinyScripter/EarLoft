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
import de.kleinabersmart.earloft.core.playback.restoreOrder
import de.kleinabersmart.earloft.core.playback.songId
import de.kleinabersmart.earloft.ui.snackbar.Snackbars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chromecast (Default Media Receiver): [CastPlayer] switches between the local ExoPlayer and the receiver by itself
 * when connecting. The receiver loads directly from the server; songs, books and episodes that exist only on the phone
 * are served by the phone itself ([PhoneMediaServer], only on Wi-Fi and in formats the receiver knows; otherwise they are
 * skipped). Otherwise the receiver plays books and episodes from Audiobookshelf or from the feed. ReplayGain and gapless aren't available there.
 *
 * The queue goes to the receiver as a single message, and cast messages may be at most
 * 64 KB – one entry needs about 700 bytes (two URLs with credentials plus metadata).
 * The receiver silently discards larger messages (seen on the device: 620 titles). Its status also carries
 * the whole queue every time (about 820 bytes per title); beyond roughly 75 titles none get through anymore.
 * That's why the receiver holds at most [MAX_REMOTE_QUEUE] titles; the rest sits in [reserve] and is appended in small chunks
 * ([REFILL_CHUNK]) as soon as fewer than [REFILL_BELOW] titles follow and the receiver has reported the previous queue
 * ([refillIfNeeded]); in exchange, the longest-played titles move from the receiver into the [reserve]
 * ([makeRoom]). When switching back to the phone, the whole queue is reassembled.
 *
 * If the receiver can't load the titles (for example because it can't reach the server or rejects its
 * certificate), its queue stays empty and the player shows nothing anymore ([RemoteQueueWatch], when
 * connecting as well as for every new queue during the session). If the songs also exist on another server,
 * the queue is sent once more from there ([watchRemoteLoad]); otherwise the session ends and the original
 * queue returns to the phone paused. A receiver without a queue would otherwise reject everything else as well
 * ("Invalid Request"), and a book started afterwards would go nowhere.
 *
 * If a session was running when the process last ended, the player rejoins it on start ([CastRejoin]) and takes over
 * the receiver's state. Whatever was only in the [reserve] is lost then.
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
    private val reserve = QueueReserve<MediaItem> { it.mediaId }

    /** Counts every new queue; a refill that stems from an older one is discarded. */
    private var generation = 0
    private var refilling = false

    /**
     * Media IDs of the queue last sent to the receiver, until it reports it. Until then the player shows an empty
     * (or briefly still the old) timeline; a refill based on that would take every chunk for the last one, append the whole reserve
     * at once, and the receiver discarded the chunks mid-load (seen on the device, 2026-11-18: 1401 titles, after
     * connecting it stayed at 50).
     */
    private var awaitedQueue: Set<String>? = null

    /** IDs (receiver item IDs) of the titles [makeRoom] is currently removing from the receiver, until it reports that. */
    private var removing: List<Any>? = null
    private var sessionPlayer: DeferredRemotePlayPlayer? = null
    private var refillHandler: Handler? = null
    private val removalTimeout = Runnable { finishRemoval() }

    /** Starts the [watchRemoteLoad] check for a queue set during the session. */
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
                    // Rejoined: the phone's player is empty after the restart; the music shuffle state is in the stored state.
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
        // If the receiver had no room left (endless mix at the last title), nothing would come until the next transition otherwise.
        castPlayer?.let(::refillIfNeeded)
    }

    /**
     * Everything after the current title has been removed (mix): whatever hasn't reached the receiver yet is dropped too, otherwise it
     * would come before the new titles (review D8). A refill in progress belongs to the old queue.
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
            override fun onRepeatModeChanged(repeatMode: Int) = refillIfNeeded(player)

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

    /** Main thread. The removal by [makeRoom] has been reported (or superseded): held-back seeks run now. */
    private fun finishRemoval() {
        if (removing == null) return
        removing = null
        refillHandler?.removeCallbacks(removalTimeout)
        sessionPlayer?.releaseSeeks()
    }

    /**
     * If another app takes over the receiver, its queue is empty and switching back to the phone would bring nothing
     * back. That's why [HandOver.lastRemote] remembers the receiver's state on every event and every [SNAPSHOT_MS] while
     * it has something; the position is then at most that old. At the same time [CastRejoin] learns until when it will keep playing.
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

    /** Main thread. [items] has just been sent to the receiver as a new queue. */
    private fun awaitQueue(items: List<MediaItem>) {
        awaitedQueue = items.mapTo(HashSet()) { it.mediaId }.takeIf { it.isNotEmpty() }
    }

    /** Main thread. Whether the receiver has reported the most recently sent queue ([awaitedQueue]). */
    private fun hasReportedQueue(player: Player): Boolean {
        if (player.currentTimeline.isEmpty) return false
        val awaited = awaitedQueue ?: return true
        if ((0 until player.mediaItemCount).none { player.getMediaItemAt(it).mediaId in awaited }) return false
        awaitedQueue = null
        return true
    }

    /**
     * Main thread. Appends the next chunk of the [reserve] as soon as the receiver is about to run out of titles. With "repeat all"
     * the next round follows the last title ([QueueReserve.nextRound]); otherwise the receiver would repeat only what it still holds.
     */
    private fun refillIfNeeded(player: Player) {
        if (refilling || player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) return
        val repeatAll = player.repeatMode == Player.REPEAT_MODE_ALL
        if (reserve.remaining == 0 && !(repeatAll && reserve.hasBefore)) return
        if (!hasReportedQueue(player) || itemsAhead(player) >= REFILL_BELOW) return
        if (reserve.remaining == 0 && !reserve.nextRound()) return
        refilling = true
        val expected = generation
        val songIds = reserve.peek(REFILL_CHUNK).mapNotNull { it.songId }
        scope.launch {
            // The converter has to return synchronously; that's why the addresses are resolved beforehand.
            cache.prepare(songIds)
            withContext(Dispatchers.Main) {
                refilling = false
                val stillValid = expected == generation && player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
                if (!stillValid || !hasReportedQueue(player)) return@withContext
                // Only once the receiver has reported the removal is there room; then it continues via [onTimelineChanged].
                if (!makeRoom(player)) return@withContext
                val room = MAX_REMOTE_QUEUE - player.mediaItemCount
                val next = reserve.take(minOf(REFILL_CHUNK, room))
                if (next.isEmpty()) return@withContext
                player.addMediaItems(next)
                // Only refill further once the receiver has reported the chunk; until then [itemsAhead] doesn't count it.
                awaitQueue(next)
            }
        }
    }

    /**
     * Main thread. The receiver includes all titles of its queue in every status (about 820 bytes per title, measured
     * 2026-11-18); beyond roughly 75 titles the status no longer fits in one message and the app hears nothing from the receiver anymore.
     * That's why the queue there stays at most [MAX_REMOTE_QUEUE]: to make room for a chunk ([REFILL_CHUNK]), the
     * longest-played titles move from the receiver into the [reserve] (before the window) and come back when switching to the phone.
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
        // Not while a seek may still be unconfirmed (see [DeferredRemotePlayPlayer.holdSeeks]).
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
        // From the back so the indices before stay correct; contiguous chunks in one message.
        drop.ranges().asReversed().forEach { range -> player.removeMediaItems(range.first, range.last + 1) }
        return false
    }

    /** How many titles still follow the current one (in playback order), counted up to [REFILL_BELOW]. */
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
     * When connecting, [CastPlayer] hands over the queue immediately and synchronously. Between "connecting"
     * and "connected" the receiver starts its app (usually over a second) – enough time to
     * resolve the addresses.
     */
    private fun prepareOnSessionStart(player: Player) {
        val cast = Cast.getSingletonInstance(appContext)
        sessionListener?.let(cast::removeSessionManagerListener)
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarting(session: CastSession) {
                handOver.resuming = rejoin.isRejoining(session)
                handOver.checkRejoin = handOver.resuming
                handOver.rejected = false
                // Before resolving: addresses served by the phone are valid only in this session.
                phone.newSession()
                prepareQueue()
            }

            override fun onSessionResuming(session: CastSession, sessionId: String) {
                phone.resumeSession()
                prepareQueue()
            }

            override fun onSessionStarted(session: CastSession, sessionId: String) {
                // Not `resuming`: that resets `transferState`, and depending on the listener order it may already run earlier
                // (in RemoteCastPlayer's `onSessionStarted`).
                val checkRejoin = handOver.checkRejoin
                handOver.checkRejoin = false
                if (checkRejoin && !rejoin.isOurs(sessionId)) {
                    // No longer the previous session: if nothing was running on the receiver, selecting launched the app there
                    // and it gets stopped again; otherwise it belongs to another app and keeps running.
                    val launched = session.applicationConnectionResult?.wasLaunched == true
                    Log.i(TAG, "Session $sessionId is not the previous one (newly launched: $launched), leaving again")
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
     * If the receiver has discarded a queue sent to it when connecting or during the session
     * ([RemoteQueueWatch], deadline [QUEUE_WATCH_MS]), it probably can't reach the server of the current song. The server
     * then stays excluded for the session ([CastMediaCache.exclude]), and the queue goes out once more with the
     * songs that also exist on another server (`retryElsewhere`; on the device: Jellyfin via public address
     * unreachable, the same song from Emby on the LAN). If nothing is left, the session ends and the queue returns
     * to the phone paused. Until 2026-11-04, "still empty after 8 s" applied when connecting; but books from Audiobookshelf
     * take about 9 s on the receiver, and connecting failed because of that.
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
                    Log.i(TAG, "Receiver cannot reach $failedSource, sending the queue from another server")
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
            // The state before sending (when connecting: empty) already counts.
            queueWatch.onItems(itemIds())
            handler.postDelayed(timeout, QUEUE_WATCH_MS)
        }
        watchQueue = { state ->
            awaitQueue(state.mediaItems)
            // Whatever didn't fit into the message is in the reserve (just newly set); everything goes back to the phone.
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

    /** Main thread. */
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

        /** For this long after a seek it may still be unconfirmed by the receiver. */
        private const val SEEK_SETTLE_MS = 2_000L

        /** At the latest then, held-back seeks run, even without a report from the receiver. */
        private const val REMOVAL_TIMEOUT_MS = 3_000L
        private const val SNAPSHOT_MS = 5_000L
        private const val TAG = "CastSession"
    }
}

/**
 * What goes to the receiver from state [state]: only songs it can load (addresses prepared in [cache]), at most
 * [CastSessionPlayerFactory.MAX_REMOTE_QUEUE] around the current one; the rest goes into the [reserve]. The receiver knows
 * no shuffle: with shuffle the current song goes first and the others follow in random order ([queueWindow], review E11).
 * Reports skipped songs via [onSkipped].
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
    val shuffle = state.shuffleModeEnabled
    if (castable.size == items.size && items.size <= CastSessionPlayerFactory.MAX_REMOTE_QUEUE && !shuffle) return state
    onSkipped(items.size - castable.size, items.size)
    if (castable.isEmpty()) {
        return state.buildUpon().setMediaItems(emptyList()).setCurrentMediaItemIndex(C.INDEX_UNSET).build()
    }
    val current = state.currentMediaItemIndex
    val startInCastable = castable.indexOfFirst { it >= current }.takeIf { it >= 0 } ?: 0
    val window = queueWindow(castable, startInCastable, CastSessionPlayerFactory.MAX_REMOTE_QUEUE, shuffle)
    reserve.reset(window.before.map(items::get), window.after.map(items::get))
    return state.buildUpon()
        .setMediaItems(window.items.map(items::get))
        .setCurrentMediaItemIndex(window.startIndex)
        .apply { if (window.items[window.startIndex] != current) setCurrentPosition(0) }
        .build()
}

/** This state's queue in the list order of [original] ([restoreOrder]). */
@OptIn(UnstableApi::class)
private fun PlayerTransferState.inOrderOf(original: PlayerTransferState): PlayerTransferState {
    val (items, index) = restoreOrder(mediaItems, currentMediaItemIndex, original.mediaItems) { it.mediaId }
    return buildUpon().setMediaItems(items).setCurrentMediaItemIndex(index).build()
}

/** Ascending indices as contiguous ranges. */
private fun List<Int>.ranges(): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    for (index in this) {
        val last = ranges.lastOrNull()
        if (last != null && last.last + 1 == index) ranges[ranges.lastIndex] = last.first..index else ranges += index..index
    }
    return ranges
}

/**
 * Until when (wall clock) [player] will probably play its queue: the rest of the current title and all following ones
 * at the current speed; one day with repeat. `null` if it isn't playing or the duration is unknown.
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
 * [state]: what was last sent to the receiver (only if not empty). [rescue]: after a failed
 * load – the queue is restored from this when switching back to the phone. [lastRemote]: the receiver's last
 * state with a queue, in case another app takes it over. [resuming]: the app rejoins after a
 * restart ([CastRejoin]) – the receiver then keeps its state. [checkRejoin]: when the session starts, check
 * whether it's the previous one; unlike [resuming] this doesn't reset `transferState`. [rejected]: that turned out to be a
 * different session; the phone keeps its state. [phoneShuffle]: shuffle on the phone at handover; the cast player
 * has none and returns "off", so this value applies again when switching back.
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
 * What goes back from the receiver to the phone: [rebuild] rebuilds entries without an address on the phone (books and episodes after
 * an app restart, see [SpokenItemRebuilder]); whatever still has none after that is dropped, as with
 * `TransferCallback.DEFAULT` (foreign session).
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

/** Hands only songs with a server source to the receiver; everything the phone can play goes back to the phone. */
@OptIn(UnstableApi::class)
private class CastTransferCallback(
    private val cache: CastMediaCache,
    private val handOver: HandOver,
    private val reserve: QueueReserve<MediaItem>,
    private val rebuild: (List<MediaItem>) -> List<MediaItem>,
    private val onSkipped: (skipped: Int, total: Int) -> Unit,
    /** What goes to the receiver as the queue (window without reserve). */
    private val onSent: (List<MediaItem>) -> Unit,
    /** The app has rejoined a running session ([HandOver.resuming]); sets [HandOver.phoneShuffle] afterwards. */
    private val onRejoined: () -> Unit,
) : CastPlayer.TransferCallback {

    override fun transferState(sourcePlayer: Player, targetPlayer: Player) {
        if (targetPlayer !is RemoteCastPlayer && handOver.rejected) {
            // The local player was only paused during the switch and still has its queue.
            handOver.rejected = false
            handOver.resuming = false
            return
        }
        if (targetPlayer !is RemoteCastPlayer) {
            val rescue = handOver.rescue
            val lastRemote = handOver.lastRemote
            val handedOver = handOver.state
            handOver.state = null
            handOver.rescue = null
            handOver.lastRemote = null
            val remaining = reserve.takeUnless { it.isEmpty() }
            val source = PlayerTransferState.fromPlayer(sourcePlayer)
            // Empty because another app has taken over the receiver: the last state returns to the phone paused.
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
            // Music only: a book or episode started while casting plays without shuffle.
            val shuffle = handOver.phoneShuffle
            handOver.phoneShuffle = null
            val music = back.mediaItems.getOrNull(back.currentMediaItemIndex)?.songId != null
            // With shuffle the receiver got a shuffled list ([castableState]); the phone gets its list order back.
            val ordered = if (shuffle == true && music && handedOver != null) back.inOrderOf(handedOver) else back
            val withShuffle = if (shuffle != null && music) ordered.buildUpon().setShuffleModeEnabled(shuffle).build() else ordered
            withShuffle.playableOnPhone(rebuild).setToPlayer(targetPlayer)
            reserve.clear()
            return
        }
        val state = PlayerTransferState.fromPlayer(sourcePlayer)
        handOver.rescue = null
        handOver.lastRemote = null
        reserve.clear()
        // Rejoined: the receiver keeps playing what it has. The phone's queue (empty after the restart
        // or an older state) would otherwise replace or pause it.
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
