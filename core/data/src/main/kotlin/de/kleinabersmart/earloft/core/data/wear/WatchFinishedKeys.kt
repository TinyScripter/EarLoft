package de.kleinabersmart.earloft.core.data.wear

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The key of an episode (subscription and guid) or book (server and identifier) that is the same on phone and watch. */
data class WatchSpokenKey(val serverId: String, val remoteId: String)

/**
 * Episodes and books selected for the watch that were listened to the end on the phone (review5 8.12). They drop out of the
 * selection at once, and the watch must not take that for a removal: many at once ("Mark all as played") look like a phone that
 * lost its data, and the watch would hold them for a day. Their keys go along in the episode manifest for [KEEP_MS], long enough
 * for a watch that was out of range in between (it only ever sees the latest manifest, not each one).
 */
@Singleton
class WatchFinishedKeys @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(keys: Collection<WatchSpokenKey>, now: Long = System.currentTimeMillis()) {
        val added = keys.filter { it.serverId.isStorable() && it.remoteId.isStorable() }
        if (added.isEmpty()) return
        val kept = read().filterKeys { it !in added }
        write(kept + added.associateWith { now })
    }

    /** The keys finished during the last [KEEP_MS]; older ones are forgotten. */
    @Synchronized
    fun current(now: Long = System.currentTimeMillis()): List<WatchSpokenKey> {
        val all = read()
        val recent = all.filterValues { now - it < KEEP_MS }
        if (recent.size != all.size) write(recent)
        return recent.entries.sortedBy { it.value }.map { it.key }
    }

    private fun read(): Map<WatchSpokenKey, Long> =
        preferences.getString(KEY_FINISHED, null).orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split(FIELD)
            val at = parts.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
            if (parts.size != 3) return@mapNotNull null
            WatchSpokenKey(parts[1], parts[2]) to at
        }.toMap()

    private fun write(entries: Map<WatchSpokenKey, Long>) = preferences.edit {
        putString(KEY_FINISHED, entries.entries.joinToString(LINE) { (key, at) -> "$at$FIELD${key.serverId}$FIELD${key.remoteId}" })
    }

    /** A key with a separator stays out (the watch then holds it like any removal); feeds' guids practically never contain one. */
    private fun String.isStorable() = !contains(FIELD) && !contains(LINE)

    internal companion object {
        const val PREFS = "watch_finished"
        const val KEY_FINISHED = "finished"
        const val FIELD = "\t"
        const val LINE = "\n"

        /** A week: the watch's own hold for removals lasts a day (`RemovalGuard` on the watch). */
        const val KEEP_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
