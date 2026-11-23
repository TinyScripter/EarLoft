package de.kleinabersmart.earloft.wear.sync

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the watch has already reported to the phone about its episodes or applied from it (Wear concept 16.1): per episode
 * the timestamp of the progress and per statistics entry the listened time. Only what goes beyond that is sent to the phone;
 * this way the watch does not send back a state applied from the phone.
 */
@Singleton
class ReportedEpisodes @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("watch_episode_reports", Context.MODE_PRIVATE)

    fun progressAt(feedId: String, guid: String): Long = prefs.getLong(progressKey(feedId, guid), 0)

    fun markProgress(feedId: String, guid: String, updatedAt: Long) {
        prefs.edit { putLong(progressKey(feedId, guid), updatedAt) }
    }

    /** Reported time of the statistics entry ([groupKey] = feed address, [itemKey] = guid). */
    fun listenedMs(groupKey: String, itemKey: String): Long = prefs.getLong(listeningKey(groupKey, itemKey), 0)

    fun markListened(groupKey: String, itemKey: String, listenedMs: Long) {
        prefs.edit { putLong(listeningKey(groupKey, itemKey), listenedMs) }
    }

    /**
     * Marks what one report sent, in a single write (review2 J95). [checkedAt]: the statistics up to then are reported; the next
     * report only reads entries listened to since ([listeningCheckedAt]).
     */
    fun markReported(progress: List<Triple<String, String, Long>>, listening: List<Triple<String, String, Long>>, checkedAt: Long) {
        prefs.edit {
            progress.forEach { (feedId, guid, updatedAt) -> putLong(progressKey(feedId, guid), updatedAt) }
            listening.forEach { (groupKey, itemKey, listenedMs) -> putLong(listeningKey(groupKey, itemKey), listenedMs) }
            putLong(KEY_CHECKED_AT, checkedAt)
        }
    }

    fun listeningCheckedAt(): Long = prefs.getLong(KEY_CHECKED_AT, 0)

    /** Forgets the progress of episodes and books no longer on the watch; [kept] as (feedId or serverId, guid or remoteId). */
    fun retainProgress(kept: Collection<Pair<String, String>>) {
        val keep = kept.mapTo(HashSet()) { (feedId, guid) -> progressKey(feedId, guid) }
        val stale = prefs.all.keys.filter { it.startsWith(PROGRESS_PREFIX) && it !in keep }
        if (stale.isNotEmpty()) prefs.edit { stale.forEach(::remove) }
    }

    // Separator that occurs in no address and no guid.
    private fun progressKey(feedId: String, guid: String) = "$PROGRESS_PREFIX$feedId\u0000$guid"

    private fun listeningKey(groupKey: String, itemKey: String) = "l\u0000$groupKey\u0000$itemKey"

    private companion object {
        const val PROGRESS_PREFIX = "p\u0000"
        const val KEY_CHECKED_AT = "listening_checked_at"
    }
}
