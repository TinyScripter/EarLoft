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

/** Gespeicherte Warteschlange samt Position, z. B. für das Fortsetzen nach einem Neustart. */
data class RestoredQueue(
    val items: List<MediaItem>,
    val startIndex: Int,
    val startPositionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
)

/**
 * Die gespeicherte Musik-Warteschlange [saved] mit [songIds] darin: bei [next] direkt hinter dem gespeicherten Titel
 * ([savedIndex]), sonst am Ende; ist sie leer, nur [songIds].
 */
internal fun insertIntoSavedQueue(saved: List<Long>, savedIndex: Int, songIds: List<Long>, next: Boolean): List<Long> {
    if (saved.isEmpty()) return songIds
    if (!next) return saved + songIds
    val at = savedIndex.coerceIn(0, saved.lastIndex) + 1
    return saved.take(at) + songIds + saved.drop(at)
}

/** Die gespeicherte Warteschlange aus [songs] mit dem Titel an [savedIndex] (wie [QueuePersistence.restore] begrenzt); leer `null`. */
internal fun savedMusicQueueOf(songs: List<Song>, savedIndex: Int): SavedMusicQueue? {
    if (songs.isEmpty()) return null
    val entries = songs.map { QueueEntry(songId = it.id, title = it.title, artist = it.artist, albumId = it.albumId, albumTitle = it.albumTitle) }
    return SavedMusicQueue(entries, savedIndex.coerceIn(0, songs.lastIndex))
}

/**
 * Stelle von [songId] in der gespeicherten Warteschlange [songIds]: das erste Vorkommen ab [near] (dem bisher gespeicherten
 * Titel), sonst das nächste davor; `null`, wenn der Song nicht darin steht. So trifft ein doppelt eingereihter Song die
 * Stelle, an der die Wiedergabe gerade ist.
 */
internal fun indexOfSong(songIds: List<Long>, songId: Long, near: Int): Int? {
    val from = near.coerceIn(0, (songIds.size - 1).coerceAtLeast(0))
    for (i in from until songIds.size) if (songIds[i] == songId) return i
    for (i in from - 1 downTo 0) if (songIds[i] == songId) return i
    return null
}

/** Speichert Warteschlange (Room) und Position/Modi (DataStore) – Konzept 5.5. */
@Singleton
class QueuePersistence @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playbackStateRepository: PlaybackStateRepository,
    private val libraryRepository: LibraryRepository,
    private val bookKeys: BookKeys,
) : BookKeyRenamer {
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
        // Zu Ende gespielt: Am Titelende wiederhergestellt würde „Abspielen“ sofort wieder enden.
        val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
        saveMusicPosition(player.currentMediaItemIndex, position, player.shuffleModeEnabled, player.repeatMode, scope)
    }

    /**
     * Beim Casten: Der Cast-Player hält nur ein Fenster der Warteschlange (Reserve, entfernte gespielte Titel) und kennt
     * keinen Zufall. Gespeichert bleiben deshalb Warteschlange und Modi vom Handy; nur der laufende Song ([songId], in der
     * gespeicherten Warteschlange gesucht) und seine Stelle werden übernommen. Steht er nicht darin (beim Casten
     * eingereiht), bleibt alles, wie es ist.
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

    /** Der gespeicherte Zufall der Musik (etwa nach dem Wiederanhängen an eine Cast-Sitzung, wenn der Player ihn nicht kennt). */
    suspend fun savedShuffle(): Boolean = context.playbackDataStore.data.first()[KEY_SHUFFLE] ?: false

    /** Stelle und Modi der Musik-Warteschlange, auch wenn der Player schon etwas anderes hält (Wechsel zu einem Buch). */
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
     * Merkt, ob zuletzt ein Buch/eine Folge ([bookId]) oder Musik (`null`) lief; so wird beim Start genau das geladen.
     * Gespeichert wird der Schlüssel des Buchs ([BookKeys]), damit die Datei mit dem Backup auf ein neues Gerät passt.
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

    /** Das Buch bzw. die Folge, die zuletzt lief; `null`, wenn zuletzt Musik lief oder es das Buch hier (noch) nicht gibt. */
    suspend fun lastBookId(): Long? {
        val prefs = context.playbackDataStore.data.first()
        val key = prefs[KEY_LAST_BOOK] ?: migrateLegacyLastBook(prefs) ?: return null
        return key.takeIf { it.isNotEmpty() }?.let { bookKeys.idOf(it) }
    }

    /**
     * Das Buch bzw. die Folge, die im Player geladen ist oder beim nächsten Start geladen wird, bei jeder Änderung; ohne
     * Verbindung zum Player (die hält den Wiedergabe-Dienst wach). Frühere `bookId`-Einträge zählen hier nicht.
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

    /** Frühere Versionen speicherten die `bookId`; sie wird einmal in den Schlüssel umgeschrieben. */
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
     * Songs für die Musik, solange der Player ein Buch bzw. eine Folge hält: Sie gehen in die gespeicherte Musik-Warteschlange,
     * die „Musik fortsetzen“ ([restore]) lädt, statt hinter das Gesprochene. Bei [next] hinter den gespeicherten Titel, sonst
     * ans Ende. Position und Modi bleiben; nur eine leere Warteschlange beginnt beim ersten neuen Song.
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
     * Die gespeicherte Musik-Warteschlange bei jeder Änderung, so wie [restore] sie lädt; `null`, solange sie leer ist. Nur
     * beobachten, während Gesprochenes geladen ist: Bei Musik ändert sich der gespeicherte Titel mit jedem Titelwechsel.
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
        /** Schlüssel des Buchs ([BookKeys]); leer, wenn zuletzt Musik lief. */
        val KEY_LAST_BOOK = stringPreferencesKey("last_book_key")
        val LEGACY_LAST_BOOK = longPreferencesKey("last_book_id")
        const val LEGACY_NO_BOOK = -1L
    }
}
