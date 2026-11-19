package de.kleinabersmart.earloft.core.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import de.kleinabersmart.earloft.core.data.repository.ResolvedPlayback
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * While streaming, preloads the next titles of the queue into the stream cache (concept 7.2 "Preloading"): on Wi-Fi the
 * next and the one after it completely, over mobile data only the next one and at most [meteredLimitBytes] of it. This protects
 * gapless playback on a fluctuating network and bridges dead spots. Not the current title: ExoPlayer itself keeps its rest locked
 * in the cache, a second download would store nothing.
 *
 * It only starts when ExoPlayer isn't loading (the current title's buffer is full), so the two don't compete for
 * bandwidth. If the titles change, the running download is aborted. Local files and downloads are skipped.
 */
@OptIn(UnstableApi::class)
internal class StreamPreloader(
    private val player: Player,
    /** A new data source per title that writes into the stream cache. */
    private val dataSource: () -> CacheDataSource,
    /** See [de.kleinabersmart.earloft.core.data.repository.PlaybackSourceResolver.candidates]. */
    private val candidatesOf: suspend (songId: Long) -> List<ResolvedPlayback>,
    private val isMetered: () -> Boolean,
    private val scope: CoroutineScope,
    /** See [preferCachedOriginals]. */
    private val isFullyCached: (cacheKey: String) -> Boolean = { false },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val meteredLimitBytes: Long = METERED_LIMIT_BYTES,
) : Player.Listener {

    /** What is being preloaded right now or was last; `null` before the first time. */
    private var plan: Plan? = null
    private var started = false
    private var job: Job? = null

    private data class Plan(val songIds: List<Long>, val limitBytes: Long?)

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(*TRIGGERS)) update()
    }

    /** When the service ends. */
    fun release() {
        job?.cancel()
        job = null
    }

    private fun update() {
        val active = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING
        val metered = isMetered()
        val next = if (active) upcomingSongIds(player, if (metered) 1 else 2) else emptyList()
        val wanted = Plan(next, if (metered) meteredLimitBytes else null)
        if (wanted != plan) {
            job?.cancel()
            job = null
            plan = wanted
            started = false
        }
        if (!started && wanted.songIds.isNotEmpty() && !player.isLoading) {
            started = true
            job = scope.launch { preload(wanted.songIds, wanted.limitBytes) }
        }
    }

    /**
     * Loads [songIds] into the cache one after another, at most [limitBytes] each (`null`: complete) – titles transcoded by the server always
     * complete, they are small (at most 192 kbit/s) and only seekable with a known length. [CacheWriter] doesn't load
     * what's already there again. If a title fails due to the network, the rest is left for the next occasion.
     */
    internal suspend fun preload(songIds: List<Long>, limitBytes: Long?) {
        for (songId in songIds) {
            val playback = candidatesOf(songId).preferCachedOriginals(isFullyCached).firstOrNull() ?: continue
            val location = playback.location as? PlaybackLocation.Remote ?: continue
            val limit = if (playback.transcodedTo != null) null else limitBytes
            val spec = DataSpec.Builder()
                .setUri(location.uri)
                .setKey(location.cacheKey)
                .setLength(limit ?: C.LENGTH_UNSET.toLong())
                .build()
            if (!cache(songId, spec)) return
        }
    }

    private suspend fun cache(songId: Long, spec: DataSpec): Boolean = withContext(ioDispatcher) {
        var cachedBytes = 0L
        val writer = CacheWriter(dataSource(), spec, null) { _, bytesCached, _ -> cachedBytes = bytesCached }
        // Blocks; cancelling the coroutine aborts the download via cancel().
        val canceller = launch { try { awaitCancellation() } finally { writer.cancel() } }
        try {
            writer.cache()
            Log.d(TAG, "Song $songId vorgeladen, $cachedBytes Bytes im Cache")
            true
        } catch (e: IOException) {
            ensureActive()
            Log.d(TAG, "Song $songId nicht vorgeladen: $e")
            false
        } finally {
            canceller.cancel()
        }
    }

    internal companion object {
        const val TAG = "StreamPreloader"
        const val METERED_LIMIT_BYTES = 10L * 1024 * 1024

        private val TRIGGERS = intArrayOf(
            Player.EVENT_TIMELINE_CHANGED,
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_IS_LOADING_CHANGED,
            Player.EVENT_PLAYBACK_STATE_CHANGED,
            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
            Player.EVENT_REPEAT_MODE_CHANGED,
        )

        /**
         * The songs of the next [count] entries after the current one, in playback order (shuffle, repeat of the whole
         * queue). "Repeat one" counts like "off": what is preloaded is what comes when skipping ahead.
         */
        fun upcomingSongIds(player: Player, count: Int): List<Long> {
            val timeline = player.currentTimeline
            if (timeline.isEmpty) return emptyList()
            val repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else player.repeatMode
            val window = Timeline.Window()
            val current = player.currentMediaItemIndex
            val result = mutableListOf<Long>()
            var index = current
            repeat(count) {
                index = if (index == C.INDEX_UNSET) C.INDEX_UNSET else timeline.getNextWindowIndex(index, repeatMode, player.shuffleModeEnabled)
                if (index != C.INDEX_UNSET && index != current) timeline.getWindow(index, window).mediaItem.songId?.let(result::add)
            }
            return result.distinct()
        }
    }
}

/** Whether the content for [key] is completely in the cache (the length is only known after loading it fully once). */
@OptIn(UnstableApi::class)
internal fun Cache.isFullyCached(key: String): Boolean {
    val length = ContentMetadata.getContentLength(getContentMetadata(key))
    return length != C.LENGTH_UNSET.toLong() && isCached(key, 0, length)
}
