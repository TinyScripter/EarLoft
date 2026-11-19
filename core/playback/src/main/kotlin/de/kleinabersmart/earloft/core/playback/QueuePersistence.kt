package de.kleinabersmart.earloft.core.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.repository.PlaybackStateRepository
import de.kleinabersmart.earloft.core.data.spoken.BookKeyRenamer
import de.kleinabersmart.earloft.core.data.spoken.BookKeys
import de.kleinabersmart.earloft.core.model.Song
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_state")

/** Saved queue including position, e.g. for resuming after a restart. */
data class RestoredQueue(
    val items: List<MediaItem>,
    val startIndex: Int,
    val startPositionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
)

/**
 * The saved music queue [saved] with [songIds] in it: with [next] right after the saved title
 * ([savedIndex]), otherwise at the end; if it is empty, just [songIds].
 */
internal fun insertIntoSavedQueue(saved: List<Long>, savedIndex: Int, songIds: List<Long>, next: Boolean): List<Long> {
    if (saved.isEmpty()) return songIds
    if (!next) return saved + songIds
    val at = savedIndex.coerceIn(0, saved.lastIndex) + 1
    return saved.take(at) + songIds + saved.drop(at)
}

/** The saved queue from [songs] with the title at [savedIndex] (limited like [QueuePersistence.restore]); `null` if empty. */
internal fun savedMusicQueueOf(songs: List<Song>, savedIndex: Int): SavedMusicQueue? {
    if (songs.isEmpty()) return null
    val entries = songs.map { QueueEntry(songId = it.id, title = it.title, artist = it.artist, albumId = it.albumId, albumTitle = it.albumTitle) }
    return SavedMusicQueue(entries, savedIndex.coerceIn(0, songs.lastIndex))
}

/**
 * Position of [songId] in the saved queue [songIds]: the first occurrence from [near] (the previously saved
 * title), otherwise the closest one before it; `null` if the song isn't in it. That way a song enqueued twice hits the
 * position where playback currently is.
 */
internal fun indexOfSong(songIds: List<Long>, songId: Long, near: Int): Int? {
    val from = near.coerceIn(0, (songIds.size - 1).coerceAtLeast(0))
    for (i in from until songIds.size) if (songIds[i] == songId) return i
    for (i in from - 1 downTo 0) if (songIds[i] == songId) return i
    return null
}

/** Saves the queue (Room) and position/modes (DataStore) – concept 5.5. */
@Singleton
class QueuePersistence @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playbackStateRepository: PlaybackStateRepository,
    private val libraryRepository: LibraryRepository,
    private val bookKeys: BookKeys,
) : BookKeyRenamer {
    private var queueSaveJob: Job? = null

    /** Must be called on the player thread; reads the state there and saves asynchronously. */
    fun saveQueueDebounced(player: Player, scope: CoroutineScope) {
        val songIds = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).songId }
        queueSaveJob?.cancel()
        queueSaveJob = scope.launch {
            delay(QUEUE_SAVE_DELAY_MS)
            playbackStateRepository.saveQueue(songIds)
        }
    }

    fun savePosition(player: Player, scope: CoroutineScope) {
        // Played to the end: restored at the title's end, "Play" would end again immediately.
        val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
        saveMusicPosition(player.currentMediaItemIndex, position, player.shuffleModeEnabled, player.repeatMode, scope)
    }

    /**
     * While casting: the cast player only holds a window of the queue (reserve, removed played titles) and knows
     * no shuffle. That's why the queue and modes from the phone stay saved; only the current song ([songId], looked up in the
     * saved queue) and its position are taken over. If it isn't in it (enqueued while
     * casting), everything stays as it is.
     */
    fun saveRemotePosition(songId: Long, position: Long, scope: CoroutineScope) {
        scope.launch {
            val saved = playbackStateRepository.loadQueue()
            val prefs = context.playbackDataStore.data.first()
            val index = indexOfSong(saved, songId, near = prefs[KEY_INDEX] ?: 0) ?: return@launch
            context.playbackDataStore.edit {
                it[KEY_INDEX] = index
                it[KEY_POSITION] = position
            }
        }
    }

    /** The music's saved shuffle (e.g. after reattaching to a cast session, when the player doesn't know it). */
    suspend fun savedShuffle(): Boolean = context.playbackDataStore.data.first()[KEY_SHUFFLE] ?: false

    /** Position and modes of the music queue, even if the player already holds something else (switch to a book). */
    fun saveMusicPosition(index: Int, position: Long, shuffle: Boolean, repeat: Int, scope: CoroutineScope) {
        scope.launch {
            context.playbackDataStore.edit {
                it[KEY_INDEX] = index
                it[KEY_POSITION] = position
                it[KEY_SHUFFLE] = shuffle
                it[KEY_REPEAT] = repeat
            }
        }
    }

    /**
     * Remembers whether a book/episode ([bookId]) or music (`null`) played last; exactly that is loaded on start.
     * The book's key ([BookKeys]) is stored so that the file matches on a new device restored from backup.
     */
    fun saveLastBook(bookId: Long?, scope: CoroutineScope) {
        scope.launch {
            val key = bookId?.let { bookKeys.keyOf(it) }.orEmpty()
            context.playbackDataStore.edit {
                it[KEY_LAST_BOOK] = key
                it.remove(LEGACY_LAST_BOOK)
            }
        }
    }

    /** The book or episode that played last; `null` if music played last or the book doesn't exist here (yet). */
    suspend fun lastBookId(): Long? {
        val prefs = context.playbackDataStore.data.first()
        val key = prefs[KEY_LAST_BOOK] ?: migrateLegacyLastBook(prefs) ?: return null
        return key.takeIf { it.isNotEmpty() }?.let { bookKeys.idOf(it) }
    }

    /**
     * The book or episode loaded in the player or to be loaded on the next start, on every change; without a
     * connection to the player (which keeps the playback service awake). Earlier `bookId` entries don't count here.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lastBookIds: Flow<Long?> = context.playbackDataStore.data
        .map { it[KEY_LAST_BOOK].orEmpty() }
        .distinctUntilChanged()
        .mapLatest { key -> key.takeIf { it.isNotEmpty() }?.let { bookKeys.idOf(it) } }

    override suspend fun renameBookKeys(renames: Map<String, String>) {
        context.playbackDataStore.edit { prefs ->
            prefs[KEY_LAST_BOOK]?.let { renames[it] }?.let { prefs[KEY_LAST_BOOK] = it }
        }
    }

    /** Earlier versions stored the `bookId`; it is rewritten into the key once. */
    private suspend fun migrateLegacyLastBook(prefs: Preferences): String? {
        val legacy = prefs[LEGACY_LAST_BOOK] ?: return null
        val key = legacy.takeIf { it != LEGACY_NO_BOOK }?.let { bookKeys.keyOfLegacyId(it) }.orEmpty()
        context.playbackDataStore.edit {
            it[KEY_LAST_BOOK] = key
            it.remove(LEGACY_LAST_BOOK)
        }
        return key
    }

    /**
     * Songs for the music while the player holds a book or episode: they go into the saved music queue
     * that "Resume music" ([restore]) loads, instead of after the spoken content. With [next] after the saved title, otherwise
     * at the end. Position and modes stay; only an empty queue starts at the first new song.
     */
    suspend fun addToSavedQueue(songIds: List<Long>, next: Boolean) {
        if (songIds.isEmpty()) return
        val saved = playbackStateRepository.loadQueue()
        val index = context.playbackDataStore.data.first()[KEY_INDEX] ?: 0
        playbackStateRepository.saveQueue(insertIntoSavedQueue(saved, index, songIds, next))
        if (saved.isEmpty()) {
            context.playbackDataStore.edit {
                it[KEY_INDEX] = 0
                it[KEY_POSITION] = 0L
            }
        }
    }

    /**
     * The saved music queue on every change, as [restore] loads it; `null` while it is empty. Only
     * observe while spoken content is loaded: with music, the saved title changes with every title change.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val savedQueue: Flow<SavedMusicQueue?> = combine(
        playbackStateRepository.observeQueue(),
        context.playbackDataStore.data.map { it[KEY_INDEX] ?: 0 }.distinctUntilChanged(),
        ::Pair,
    ).mapLatest { (songIds, index) -> savedMusicQueueOf(libraryRepository.songs(songIds), index) }

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
        /** Key of the book ([BookKeys]); empty if music played last. */
        val KEY_LAST_BOOK = stringPreferencesKey("last_book_key")
        val LEGACY_LAST_BOOK = longPreferencesKey("last_book_id")
        const val LEGACY_NO_BOOK = -1L
    }
}
