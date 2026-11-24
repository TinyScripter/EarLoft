package de.kleinabersmart.earloft.core.data.podcast

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.data.backup.SettingsMerge
import de.kleinabersmart.earloft.core.data.spoken.BookKeyRenamer
import de.kleinabersmart.earloft.core.data.spoken.BookKeys
import de.kleinabersmart.earloft.core.database.dao.BookDao
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

internal val Context.episodeQueueDataStore: DataStore<Preferences> by preferencesDataStore(name = "episode_queue")

/**
 * The podcast queue is the "Continue listening" row (concept 12.4). This holds its order, as far as it
 * has been set (episodes and books share the list, each row reads only its own): [order] at the very front, behind it
 * the episodes that appear there on their own (started and new ones). Anyone changing the row uses `ContinueListening`.
 *
 * Each entry is stored as `(serverId, remoteId)` of the book instead of the `bookId`, which a new device assigns anew: the
 * file comes along with the backup (review K9), and its entries apply again as soon as a sync or scan has created the
 * episodes. Externally it stays with `bookId`s.
 */
@Singleton
class EpisodeQueue @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val bookDao: BookDao,
) : BookKeyRenamer {
    private val migration = Mutex()

    @Volatile
    private var migrated = false

    private val prefs: Flow<Preferences> = flow {
        migrateLegacyIds()
        emitAll(context.episodeQueueDataStore.data)
    }

    private val idsByKey: Flow<Map<String, Long>> =
        bookDao.observeBookRefs().map { refs -> refs.associate { key(it.serverId, it.remoteId) to it.bookId } }

    /** Fixed order, the frontmost episode first. */
    val order: Flow<List<Long>> =
        combine(prefs, idsByKey) { prefs, ids -> prefs.read(KEY_ORDER).mapNotNull { ids[it] } }.distinctUntilChanged()

    /**
     * Episodes removed by hand from this row or from "Recently added" on Home (one list for both); they no longer appear
     * there until they are played or inserted again.
     */
    val hidden: Flow<Set<Long>> =
        combine(prefs, idsByKey) { prefs, ids -> prefs.read(KEY_HIDDEN).mapNotNullTo(HashSet()) { ids[it] } }.distinctUntilChanged()

    /**
     * "Clear" on the "Recently added" shelf: publication time of the newest episode it showed; only newer episodes appear there.
     * Leaves "Continue listening" alone.
     */
    val recentClearedUntil: Flow<Long> = prefs.map { it[KEY_RECENT_CLEARED] ?: 0L }.distinctUntilChanged()

    suspend fun setRecentClearedUntil(addedAt: Long) {
        context.episodeQueueDataStore.edit { it[KEY_RECENT_CLEARED] = addedAt }
    }

    /**
     * The IDs (guid) of the episodes of [feedId] in the fixed order: the sync doesn't remove them, even if the
     * feed no longer lists them (review D23).
     */
    suspend fun queuedRemoteIds(feedId: String): List<String> {
        val prefix = key(feedId, "")
        return prefs.first().read(KEY_ORDER).filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    /** Puts the episode in first position. */
    suspend fun moveToFront(bookId: Long) = edit(bookId) { key, order -> listOf(key) + (order - key) }

    /** Puts the episode in second position, behind the one currently playing; if it is already in the fixed order, it stays there. */
    suspend fun insertSecond(bookId: Long) = edit(bookId) { key, order ->
        if (key in order) order else order.take(1) + key + order.drop(1)
    }

    /**
     * Puts the episode right behind [anchorId] ("Play next"), even if it is already elsewhere; [anchorId] is also
     * moved to the front if it wasn't fixed yet. Without [anchorId], in first position.
     */
    suspend fun insertAfter(bookId: Long, anchorId: Long?) {
        val keys = keysOf(listOfNotNull(bookId, anchorId))
        val key = keys[bookId] ?: return
        val anchor = anchorId?.let(keys::get)
        update { order ->
            val rest = order - key
            when {
                anchor == null -> listOf(key) + rest
                anchor in rest -> rest.toMutableList().apply { add(indexOf(anchor) + 1, key) }
                else -> listOf(anchor, key) + rest
            }
        }
    }

    /** Appends the episode at the end (if it is already in the fixed order, it stays in its place). */
    suspend fun append(bookId: Long) = edit(bookId) { key, order -> if (key in order) order else order + key }

    /** Sets the entire order (editing the queue); the episodes in it are no longer hidden. */
    suspend fun setOrder(bookIds: List<Long>) {
        val keys = keysOf(bookIds)
        val ordered = bookIds.distinct().mapNotNull { keys[it] }
        update { old -> ordered + (old - ordered.toSet()) }
    }

    /**
     * The row was reordered: [bookIds] is its new order, [movedId] the moved episode. Only what holds its
     * new place is fixed: it and everything before it, because episodes appearing on their own always come after the fixed ones.
     * Behind it, fixed episodes stay fixed and all others keep appearing there on their own ("In Continue listening" and
     * "Keep episodes" still apply to them).
     */
    suspend fun move(bookIds: List<Long>, movedId: Long) {
        val keys = keysOf(bookIds)
        val line = bookIds.distinct().mapNotNull { keys[it] }
        val moved = keys[movedId] ?: return
        val cut = line.indexOf(moved) + 1
        update { old ->
            val wasFixed = old.toSet()
            val fixed = line.take(cut) + line.drop(cut).filter { it in wasFixed }
            fixed + (old - fixed.toSet())
        }
    }

    /** Removes the episode from the row, whether it is fixed or appears there on its own. */
    suspend fun remove(bookId: Long) {
        val key = keysOf(listOf(bookId))[bookId] ?: return
        migrateLegacyIds()
        context.episodeQueueDataStore.edit { prefs ->
            prefs.write(KEY_ORDER, prefs.read(KEY_ORDER) - key)
            val hidden = prefs.read(KEY_HIDDEN)
            if (key !in hidden) prefs.write(KEY_HIDDEN, hidden + key)
        }
    }

    /** Undoes [remove] for an episode that wasn't in the fixed order: it appears on its own again where its rules allow. */
    suspend fun unhide(bookId: Long) {
        val key = keysOf(listOf(bookId))[bookId] ?: return
        migrateLegacyIds()
        context.episodeQueueDataStore.edit { prefs -> prefs.write(KEY_HIDDEN, prefs.read(KEY_HIDDEN) - key) }
    }

    /** Removes the episodes from the row, like [remove] for each, in one step. */
    suspend fun removeAll(bookIds: List<Long>) {
        val keys = keysOf(bookIds).values.toSet()
        if (keys.isEmpty()) return
        migrateLegacyIds()
        context.episodeQueueDataStore.edit { prefs ->
            prefs.write(KEY_ORDER, prefs.read(KEY_ORDER).filter { it !in keys })
            val hidden = prefs.read(KEY_HIDDEN)
            prefs.write(KEY_HIDDEN, hidden + (keys - hidden.toSet()))
        }
    }

    override suspend fun renameBookKeys(renames: Map<String, String>) {
        migrateLegacyIds()
        context.episodeQueueDataStore.edit { prefs ->
            prefs.write(KEY_ORDER, prefs.read(KEY_ORDER).map { renames[it] ?: it })
            prefs.write(KEY_HIDDEN, prefs.read(KEY_HIDDEN).map { renames[it] ?: it })
        }
    }

    private suspend fun edit(bookId: Long, change: (key: String, order: List<String>) -> List<String>) {
        val key = keysOf(listOf(bookId))[bookId] ?: return
        update { order -> change(key, order) }
    }

    /** Changes the order; the episodes in it are no longer hidden afterwards. */
    private suspend fun update(change: (List<String>) -> List<String>) {
        migrateLegacyIds()
        context.episodeQueueDataStore.edit { prefs ->
            prefs.write(KEY_ORDER, change(prefs.read(KEY_ORDER)))
            // The changed episode is now back in the row; all others keep their state.
            val keys = prefs.read(KEY_ORDER).toSet()
            prefs.write(KEY_HIDDEN, prefs.read(KEY_HIDDEN).filter { it !in keys })
        }
    }

    private suspend fun keysOf(bookIds: List<Long>): Map<Long, String> =
        bookIds.distinct().chunked(MAX_ARGS).flatMap { bookDao.bookRefsOf(it) }.associate { it.bookId to key(it.serverId, it.remoteId) }

    /**
     * Earlier versions stored `bookId`s; they are rewritten to keys once. If this installation created the
     * database itself, the file came with a backup and its IDs belong to another device: discard.
     */
    private suspend fun migrateLegacyIds() {
        if (migrated) return
        migration.withLock {
            if (migrated) return
            val stored = context.episodeQueueDataStore.data.first()
            if (LEGACY_ORDER in stored || LEGACY_HIDDEN in stored) {
                val order = stored.readLegacy(LEGACY_ORDER)
                val hidden = stored.readLegacy(LEGACY_HIDDEN)
                // The query opens the database; only then is it known whether it was just created.
                val keys = keysOf(order + hidden).takeUnless { EarLoftDatabase.createdOnThisInstall(context) }.orEmpty()
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

    companion object {
        private val KEY_ORDER = stringPreferencesKey("order_keys")
        private val KEY_HIDDEN = stringPreferencesKey("hidden_keys")
        private val KEY_RECENT_CLEARED = longPreferencesKey("recent_cleared_until")
        private val LEGACY_ORDER = stringPreferencesKey("order")
        private val LEGACY_HIDDEN = stringPreferencesKey("hidden")

        /** Control character that occurs neither in a guid (XML) nor in a path or a server ID. */
        private const val ENTRY_SEPARATOR = "\u001E"
        private const val MAX_ARGS = 500

        private fun key(serverId: String, remoteId: String) = BookKeys.encode(serverId, remoteId)

        /**
         * When restoring a backup: local order and hidden entries first, then the missing backed-up ones, with
         * the local IDs of their servers and subscriptions.
         */
        internal val BACKUP_MERGES: Map<String, SettingsMerge> = listOf(KEY_ORDER, KEY_HIDDEN).associate { preference ->
            preference.name to SettingsMerge { current, saved, ids ->
                fun split(value: Any?) = (value as? String).orEmpty().split(ENTRY_SEPARATOR).filter { it.isNotEmpty() }
                val here = split(current)
                val known = here.toSet()
                val added = split(saved).map { entry ->
                    val serverId = entry.substringBefore(BookKeys.SEPARATOR)
                    ids[serverId]?.let { it + entry.removePrefix(serverId) } ?: entry
                }.filter { it !in known }
                (here + added).distinct().joinToString(ENTRY_SEPARATOR)
            }
        }
    }
}
