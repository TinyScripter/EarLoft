package de.kleinabersmart.kleinaberlaut.wear.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Was die Uhr dem Handy über ihre Folgen schon gemeldet hat bzw. von ihm übernommen hat (Wear-Konzept 16.1): je Folge
 * der Zeitpunkt des Fortschritts und je Statistik-Eintrag die gehörte Zeit. Nur was darüber hinausgeht, geht ans Handy;
 * einen vom Handy übernommenen Stand schickt die Uhr so nicht zurück.
 */
@Singleton
class ReportedEpisodes @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("watch_episode_reports", Context.MODE_PRIVATE)

    fun progressAt(feedId: String, guid: String): Long = prefs.getLong(progressKey(feedId, guid), 0)

    fun markProgress(feedId: String, guid: String, updatedAt: Long) {
        prefs.edit().putLong(progressKey(feedId, guid), updatedAt).apply()
    }

    /** Gemeldete Zeit des Statistik-Eintrags ([groupKey] = Adresse des Feeds, [itemKey] = guid). */
    fun listenedMs(groupKey: String, itemKey: String): Long = prefs.getLong(listeningKey(groupKey, itemKey), 0)

    fun markListened(groupKey: String, itemKey: String, listenedMs: Long) {
        prefs.edit().putLong(listeningKey(groupKey, itemKey), listenedMs).apply()
    }

    // Trennzeichen, das in keiner Adresse und keiner guid vorkommt.
    private fun progressKey(feedId: String, guid: String) = "p\u0000$feedId\u0000$guid"

    private fun listeningKey(groupKey: String, itemKey: String) = "l\u0000$groupKey\u0000$itemKey"
}
