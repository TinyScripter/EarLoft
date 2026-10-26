package keinabersmart.kleinaberlaut.core.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.data.repository.PlaybackStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_state")

/** Gespeicherte Warteschlange samt Position, z. B. für das Fortsetzen nach einem Neustart. */
data class RestoredQueue(
    val items: List<MediaItem>,
    val startIndex: Int,
    val startPositionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
)

/** Speichert Warteschlange (Room) und Position/Modi (DataStore) – Konzept 5.5. */
@Singleton
class QueuePersistence @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playbackStateRepository: PlaybackStateRepository,
    private val libraryRepository: LibraryRepository,
) {
    private var queueSaveJob: Job? = null

    /** Muss auf dem Player-Thread aufgerufen werden; liest den Zustand dort und speichert asynchron. */
    fun saveQueueDebounced(player: Player, scope: CoroutineScope) {
        val songIds = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).songId }
        queueSaveJob?.cancel()
        queueSaveJob = scope.launch {
            delay(QUEUE_SAVE_DELAY_MS)
            playbackStateRepository.saveQueue(songIds)
        }
    }

    fun savePosition(player: Player, scope: CoroutineScope) {
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val shuffle = player.shuffleModeEnabled
        val repeat = player.repeatMode
        scope.launch {
            context.playbackDataStore.edit {
                it[KEY_INDEX] = index
                it[KEY_POSITION] = position
                it[KEY_SHUFFLE] = shuffle
                it[KEY_REPEAT] = repeat
            }
        }
    }

    suspend fun restore(): RestoredQueue? {
        val songs = libraryRepository.songs(playbackStateRepository.loadQueue())
        if (songs.isEmpty()) return null
        val prefs = context.playbackDataStore.data.first()
        val index = (prefs[KEY_INDEX] ?: 0).coerceIn(0, songs.lastIndex)
        return RestoredQueue(
            items = songs.map { it.toMediaItem() },
            startIndex = index,
            startPositionMs = prefs[KEY_POSITION] ?: 0L,
            shuffle = prefs[KEY_SHUFFLE] ?: false,
            repeatMode = prefs[KEY_REPEAT] ?: Player.REPEAT_MODE_OFF,
        )
    }

    private companion object {
        const val QUEUE_SAVE_DELAY_MS = 1_000L
        val KEY_INDEX = intPreferencesKey("index")
        val KEY_POSITION = longPreferencesKey("position_ms")
        val KEY_SHUFFLE = booleanPreferencesKey("shuffle")
        val KEY_REPEAT = intPreferencesKey("repeat_mode")
    }
}
