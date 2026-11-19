package de.kleinabersmart.earloft.wear.sync

import android.content.Context
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
        prefs.edit().putLong(progressKey(feedId, guid), updatedAt).apply()
    }

    /** Reported time of the statistics entry ([groupKey] = feed address, [itemKey] = guid). */
    fun listenedMs(groupKey: String, itemKey: String): Long = prefs.getLong(listeningKey(groupKey, itemKey), 0)

    fun markListened(groupKey: String, itemKey: String, listenedMs: Long) {
        prefs.edit().putLong(listeningKey(groupKey, itemKey), listenedMs).apply()
    }

    // Separator that occurs in no address and no guid.
    private fun progressKey(feedId: String, guid: String) = "p\u0000$feedId\u0000$guid"

    private fun listeningKey(groupKey: String, itemKey: String) = "l\u0000$groupKey\u0000$itemKey"
}
