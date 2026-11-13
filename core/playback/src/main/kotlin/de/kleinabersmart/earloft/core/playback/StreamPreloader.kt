package de.kleinabersmart.earloft.core.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
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
 * Lädt beim Streamen die nächsten Titel der Warteschlange vorab in den Stream-Cache (Konzept 7.2 „Vorladen“): im WLAN den
 * nächsten und übernächsten ganz, über mobile Daten nur den nächsten und davon höchstens [meteredLimitBytes]. Das schützt
 * Gapless bei schwankendem Netz und überbrückt Funklöcher. Den laufenden Titel nicht: Dessen Rest hält ExoPlayer selbst im
 * Cache gesperrt, ein zweiter Download würde nichts speichern.
 *
 * Es geht erst los, wenn ExoPlayer gerade nicht lädt (der Puffer des laufenden Titels ist voll), damit beide nicht um die
 * Leitung konkurrieren. Ändern sich die Titel, bricht der laufende Download ab. Lokale Dateien und Downloads überspringt er.
 */
@OptIn(UnstableApi::class)
internal class StreamPreloader(
    private val player: Player,
    /** Je Titel eine neue Datenquelle, die in den Stream-Cache schreibt. */
    private val dataSource: () -> CacheDataSource,
    /** Siehe [de.kleinabersmart.earloft.core.data.repository.PlaybackSourceResolver.candidates]. */
    private val candidatesOf: suspend (songId: Long) -> List<ResolvedPlayback>,
    private val isMetered: () -> Boolean,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val meteredLimitBytes: Long = METERED_LIMIT_BYTES,
) : Player.Listener {

    /** Was gerade vorgeladen wird bzw. zuletzt wurde; `null` vor dem ersten Mal. */
    private var plan: Plan? = null
    private var started = false
    private var job: Job? = null

    private data class Plan(val songIds: List<Long>, val limitBytes: Long?)

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(*TRIGGERS)) update()
    }

    /** Beim Beenden des Dienstes. */
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
     * Lädt [songIds] nacheinander in den Cache, je höchstens [limitBytes] (`null`: ganz). Schon Vorhandenes lädt
     * [CacheWriter] nicht noch einmal. Scheitert ein Titel am Netz, bleibt der Rest für den nächsten Anlass.
     */
    internal suspend fun preload(songIds: List<Long>, limitBytes: Long?) {
        for (songId in songIds) {
            val location = candidatesOf(songId).firstOrNull()?.location as? PlaybackLocation.Remote ?: continue
            val spec = DataSpec.Builder()
                .setUri(location.uri)
                .setKey(location.cacheKey)
                .setLength(limitBytes ?: C.LENGTH_UNSET.toLong())
                .build()
            if (!cache(songId, spec)) return
        }
    }

    private suspend fun cache(songId: Long, spec: DataSpec): Boolean = withContext(ioDispatcher) {
        var cachedBytes = 0L
        val writer = CacheWriter(dataSource(), spec, null) { _, bytesCached, _ -> cachedBytes = bytesCached }
        // Blockiert; ein Abbruch der Coroutine bricht den Download über cancel() ab.
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
         * Die Songs der nächsten [count] Einträge nach dem laufenden, in Abspielreihenfolge (Zufall, Wiederholen der ganzen
         * Warteschlange). „Einen wiederholen“ zählt wie „aus“: Vorgeladen wird, was beim Weiterspringen kommt.
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
