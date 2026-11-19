package de.kleinabersmart.earloft.core.playback

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.media3.common.Player
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** "Keep playing endlessly" (Music → Playback): at the end of the queue a mix is added. Off by default. */
@Singleton
class EndlessMixSettings @Inject constructor(@param:ApplicationContext private val context: Context) {
    val enabled: Flow<Boolean> = context.playbackSettingsDataStore.data.map { it[KEY_ENABLED] ?: false }

    suspend fun setEnabled(enabled: Boolean) {
        context.playbackSettingsDataStore.edit { it[KEY_ENABLED] = enabled }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("endless_mix")
    }
}

/**
 * Appends a mix as soon as the last song of the queue plays ([isEnabled]); the seed is this song, songs that are
 * already in the queue are dropped. Only for music, without repeat (there is no end there) and without shuffle:
 * Media3 would spread the new titles over the whole shuffled order, including before the current one, and they would never play.
 *
 * Also runs while casting: if titles are still in the reserve there ([SessionPlayerFactory.hasPendingItems]), the end is not
 * reached yet; whatever the receiver can't play or what exceeds one message is handled by the factory as when
 * adding via the session.
 */
internal class EndlessMix(
    private val player: Player,
    private val isEnabled: () -> Boolean,
    private val mix: suspend (songId: Long) -> List<Song>,
    private val factory: SessionPlayerFactory?,
    private val scope: CoroutineScope,
) : Player.Listener {
    private var job: Job? = null

    /**
     * The song a mix was last searched for: an empty mix is not searched again on every event. Also the song
     * after which the queue was cleared: no mix until the next song (review V8).
     */
    private var lastSeed: Long? = null

    /** State at the last event: number of titles and whether one came after the current one. */
    private var lastCount = 0
    private var hadNext = false

    override fun onEvents(player: Player, events: Player.Events) {
        // A different song (also a new queue): a previously empty mix may try again.
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            lastSeed = null
        } else if (events.contains(Player.EVENT_TIMELINE_CHANGED) && clearedBehindCurrent()) {
            // "Clear queue" or removed the rest: the user wants to stop here, not get 50 new titles right away.
            lastSeed = player.currentMediaItem?.songId
            job?.cancel()
        }
        lastCount = player.mediaItemCount
        hadNext = player.hasNextMediaItem()
        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
            )
        ) {
            check()
        }
    }

    /**
     * Also when the setting is turned on: if the last song is playing, the mix comes immediately. If a mix is still being searched for
     * another song (slow server, new queue in the meantime), it is discarded: otherwise the new last song would end without a mix.
     */
    fun check() {
        val seed = seedAtEnd()
        if (job?.isActive == true) {
            if (seed == lastSeed) return
            job?.cancel()
        }
        if (seed == null || seed == lastSeed) return
        lastSeed = seed
        val queued = (0 until player.mediaItemCount).mapNotNullTo(HashSet()) { player.getMediaItemAt(it).songId }
        job = scope.launch {
            val found = mix(seed).filter { it.id !in queued }
            val playable = factory?.playableSongIds(player, found.map { it.id }) ?: found.mapTo(HashSet()) { it.id }
            val items = found.filter { it.id in playable }.map { it.toMediaItem() }
            // Skipped ahead, something appended or turned off in the meantime: the mix no longer belongs here.
            if (items.isEmpty() || seedAtEnd() != seed) return@launch
            Log.d(TAG, "Mix after song $seed: appended ${items.size} tracks")
            val room = factory.roomIn(player)
            player.addMediaItems(items.take(room))
            if (items.size > room) factory?.appendPending(items.drop(room))
        }
    }

    /** Same song, but fewer titles and none after it where there was one before. */
    private fun clearedBehindCurrent(): Boolean =
        hadNext && player.mediaItemCount < lastCount && !player.hasNextMediaItem() && factory?.hasPendingItems() != true

    /** The current song if it is the last one and a mix should be appended; otherwise `null`. */
    private fun seedAtEnd(): Long? {
        if (!isEnabled() || player.repeatMode != Player.REPEAT_MODE_OFF || player.shuffleModeEnabled) return null
        val songId = player.currentMediaItem?.songId ?: return null
        if (player.hasNextMediaItem() || factory?.hasPendingItems() == true) return null
        return songId
    }

    private companion object {
        const val TAG = "EndlessMix"
    }
}
