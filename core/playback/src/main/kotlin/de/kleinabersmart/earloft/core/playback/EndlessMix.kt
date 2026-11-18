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

/** „Endlos weiterspielen“ (Musik → Wiedergabe): Am Ende der Warteschlange kommt ein Mix dazu. Standard aus. */
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
 * Hängt einen Mix an, sobald der letzte Song der Warteschlange läuft ([isEnabled]); Ausgangspunkt ist dieser Song, Songs, die
 * schon in der Warteschlange stehen, fallen weg. Nur bei Musik, ohne Wiederholen (dort gibt es kein Ende) und ohne Zufall:
 * Media3 verteilte die neuen Titel über die ganze gemischte Reihenfolge, auch vor den laufenden, und sie kämen nie dran.
 *
 * Läuft auch beim Casten: Liegen dort noch Titel in der Reserve ([SessionPlayerFactory.hasPendingItems]), ist das Ende noch
 * nicht erreicht; was der Empfänger nicht spielen kann oder was über eine Nachricht hinausgeht, regelt die Fabrik wie beim
 * Hinzufügen über die Session.
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
     * Der Song, zu dem zuletzt ein Mix gesucht wurde: Ein leerer Mix wird nicht bei jedem Ereignis neu gesucht. Auch der Song,
     * hinter dem die Warteschlange geleert wurde: bis zum nächsten Song kein Mix (Review V8).
     */
    private var lastSeed: Long? = null

    /** Stand beim letzten Ereignis: Anzahl der Titel und ob einer nach dem laufenden kam. */
    private var lastCount = 0
    private var hadNext = false

    override fun onEvents(player: Player, events: Player.Events) {
        // Ein anderer Song (auch eine neue Warteschlange): Ein vorher leerer Mix darf es wieder versuchen.
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            lastSeed = null
        } else if (events.contains(Player.EVENT_TIMELINE_CHANGED) && clearedBehindCurrent()) {
            // „Warteschlange leeren“ bzw. den Rest entfernt: Der Nutzer will hier aufhören, nicht gleich 50 neue Titel.
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
     * Auch beim Einschalten der Einstellung: Läuft gerade der letzte Song, kommt der Mix sofort. Sucht noch ein Mix zu einem
     * anderen Song (langsamer Server, inzwischen neue Warteschlange), wird er verworfen: Sonst endete der neue letzte Song ohne Mix.
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
            // Inzwischen weitergesprungen, etwas angehängt oder ausgeschaltet: Der Mix gehört nicht mehr hierher.
            if (items.isEmpty() || seedAtEnd() != seed) return@launch
            Log.d(TAG, "Mix nach Song $seed: ${items.size} Titel angehängt")
            val room = factory.roomIn(player)
            player.addMediaItems(items.take(room))
            if (items.size > room) factory?.appendPending(items.drop(room))
        }
    }

    /** Gleicher Song, aber weniger Titel und keiner mehr danach, wo vorher einer war. */
    private fun clearedBehindCurrent(): Boolean =
        hadNext && player.mediaItemCount < lastCount && !player.hasNextMediaItem() && factory?.hasPendingItems() != true

    /** Der laufende Song, wenn er der letzte ist und ein Mix angehängt werden soll; sonst `null`. */
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
