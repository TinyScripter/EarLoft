package de.kleinabersmart.tinyamp.core.data.podcast

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.tinyamp.core.database.TinyAmpDatabase
import de.kleinabersmart.tinyamp.core.data.spoken.BookKeys
import de.kleinabersmart.tinyamp.core.database.dao.BookDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.episodeQueueDataStore: DataStore<Preferences> by preferencesDataStore(name = "episode_queue")

/**
 * Die Warteschlange der Podcasts ist die Zeile „Weiterhören“ (Konzept 12.4). Hier steht ihre Reihenfolge, soweit sie
 * festgelegt wurde (Folgen und Bücher teilen die Liste, jede Zeile liest nur ihre eigenen): [order] ganz vorn, dahinter
 * die Folgen, die von selbst dort stehen (angefangene und neue). Wer die Zeile ändert, nutzt `ContinueListening`.
 *
 * Gespeichert wird je Eintrag `(serverId, remoteId)` des Buchs statt der `bookId`, die ein neues Gerät neu vergibt: Die
 * Datei kommt mit dem Backup mit (Review K9), und ihre Einträge gelten wieder, sobald Abgleich oder Scan die Folgen
 * angelegt haben. Nach außen bleibt es bei `bookId`s.
 */
@Singleton
class EpisodeQueue @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val bookDao: BookDao,
) {
    private val migration = Mutex()

    @Volatile
    private var migrated = false

    private val prefs: Flow<Preferences> = flow {
        migrateLegacyIds()
        emitAll(context.episodeQueueDataStore.data)
    }

    private val idsByKey: Flow<Map<String, Long>> =
        bookDao.observeBookRefs().map { refs -> refs.associate { key(it.serverId, it.remoteId) to it.bookId } }

    /** Festgelegte Reihenfolge, die vorderste Folge zuerst. */
    val order: Flow<List<Long>> =
        combine(prefs, idsByKey) { prefs, ids -> prefs.read(KEY_ORDER).mapNotNull { ids[it] } }.distinctUntilChanged()

    /** Von Hand aus der Zeile genommene Folgen; sie erscheinen nicht mehr, bis sie gespielt oder wieder eingefügt werden. */
    val hidden: Flow<Set<Long>> =
        combine(prefs, idsByKey) { prefs, ids -> prefs.read(KEY_HIDDEN).mapNotNullTo(HashSet()) { ids[it] } }.distinctUntilChanged()

    /** Stellt die Folge an die erste Stelle. */
    suspend fun moveToFront(bookId: Long) = edit(bookId) { key, order -> listOf(key) + (order - key) }

    /** Stellt die Folge an die zweite Stelle, hinter die gerade gespielte; steht sie schon in der festgelegten Reihenfolge, bleibt sie dort. */
    suspend fun insertSecond(bookId: Long) = edit(bookId) { key, order ->
        if (key in order) order else order.take(1) + key + order.drop(1)
    }

    /** Hängt die Folge ans Ende (steht sie schon in der festgelegten Reihenfolge, bleibt sie an ihrer Stelle). */
    suspend fun append(bookId: Long) = edit(bookId) { key, order -> if (key in order) order else order + key }

    /** Legt die ganze Reihenfolge fest (Bearbeiten der Warteschlange); die Folgen darin sind nicht mehr ausgeblendet. */
    suspend fun setOrder(bookIds: List<Long>) {
        val keys = keysOf(bookIds)
        val ordered = bookIds.distinct().mapNotNull { keys[it] }
        update { old -> ordered + (old - ordered.toSet()) }
    }

    /** Nimmt die Folge aus der Zeile, egal ob sie festgelegt oder von selbst dort steht. */
    suspend fun remove(bookId: Long) {
        val key = keysOf(listOf(bookId))[bookId] ?: return
        migrateLegacyIds()
        context.episodeQueueDataStore.edit { prefs ->
            prefs.write(KEY_ORDER, prefs.read(KEY_ORDER) - key)
            val hidden = prefs.read(KEY_HIDDEN)
            if (key !in hidden) prefs.write(KEY_HIDDEN, hidden + key)
        }
    }

    private suspend fun edit(bookId: Long, change: (key: String, order: List<String>) -> List<String>) {
        val key = keysOf(listOf(bookId))[bookId] ?: return
        update { order -> change(key, order) }
    }

    /** Ändert die Reihenfolge; die Folgen darin sind danach nicht mehr ausgeblendet. */
    private suspend fun update(change: (List<String>) -> List<String>) {
        migrateLegacyIds()
        context.episodeQueueDataStore.edit { prefs ->
            prefs.write(KEY_ORDER, change(prefs.read(KEY_ORDER)))
            // Die geänderte Folge steht jetzt wieder in der Zeile; alle anderen behalten ihren Zustand.
            val keys = prefs.read(KEY_ORDER).toSet()
            prefs.write(KEY_HIDDEN, prefs.read(KEY_HIDDEN).filter { it !in keys })
        }
    }

    private suspend fun keysOf(bookIds: List<Long>): Map<Long, String> =
        bookIds.distinct().chunked(MAX_ARGS).flatMap { bookDao.bookRefsOf(it) }.associate { it.bookId to key(it.serverId, it.remoteId) }

    /**
     * Frühere Versionen speicherten `bookId`s; sie werden einmal in Schlüssel umgeschrieben. Hat diese Installation die
     * Datenbank selbst angelegt, kam die Datei mit einem Backup und ihre Ids gehören zu einem anderen Gerät: verwerfen.
     */
    private suspend fun migrateLegacyIds() {
        if (migrated) return
        migration.withLock {
            if (migrated) return
            val stored = context.episodeQueueDataStore.data.first()
            if (LEGACY_ORDER in stored || LEGACY_HIDDEN in stored) {
                val order = stored.readLegacy(LEGACY_ORDER)
                val hidden = stored.readLegacy(LEGACY_HIDDEN)
                // Die Abfrage öffnet die Datenbank; erst danach steht fest, ob sie gerade neu angelegt wurde.
                val keys = keysOf(order + hidden).takeUnless { TinyAmpDatabase.createdOnThisInstall(context) }.orEmpty()
                context.episodeQueueDataStore.edit { prefs ->
                    prefs.write(KEY_ORDER, order.mapNotNull { keys[it] })
                    prefs.write(KEY_HIDDEN, hidden.mapNotNull { keys[it] })
                    prefs.remove(LEGACY_ORDER)
                    prefs.remove(LEGACY_HIDDEN)
                }
            }
            migrated = true
        }
    }

    private fun Preferences.read(key: Preferences.Key<String>): List<String> =
        this[key].orEmpty().split(ENTRY_SEPARATOR).filter { it.isNotEmpty() }

    private fun MutablePreferences.write(key: Preferences.Key<String>, keys: List<String>) {
        this[key] = keys.joinToString(ENTRY_SEPARATOR)
    }

    private fun Preferences.readLegacy(key: Preferences.Key<String>): List<Long> =
        this[key].orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    private companion object {
        val KEY_ORDER = stringPreferencesKey("order_keys")
        val KEY_HIDDEN = stringPreferencesKey("hidden_keys")
        val LEGACY_ORDER = stringPreferencesKey("order")
        val LEGACY_HIDDEN = stringPreferencesKey("hidden")

        /** Steuerzeichen, das weder in einer guid (XML) noch in einem Pfad oder einer Server-Kennung vorkommt. */
        const val ENTRY_SEPARATOR = "\u001E"
        const val MAX_ARGS = 500

        fun key(serverId: String, remoteId: String) = BookKeys.encode(serverId, remoteId)
    }
}
