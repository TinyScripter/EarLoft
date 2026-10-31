package keinabersmart.kleinaberlaut.core.data.podcast

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.podcastSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "podcast_settings")

@Singleton
class PodcastSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Abstand der automatischen Aktualisierung in Stunden; 0 = nur von Hand. */
    val refreshHours: Flow<Int> = context.podcastSettingsDataStore.data.map { it[KEY_REFRESH_HOURS] ?: DEFAULT_REFRESH_HOURS }

    suspend fun setRefreshHours(hours: Int) {
        context.podcastSettingsDataStore.edit { it[KEY_REFRESH_HOURS] = hours.coerceAtLeast(0) }
    }

    /** Auch über Mobilfunk laden (Standard: nein, nur WLAN). */
    val useMobileData: Flow<Boolean> = context.podcastSettingsDataStore.data.map { it[KEY_MOBILE] ?: false }

    suspend fun setUseMobileData(enabled: Boolean) {
        context.podcastSettingsDataStore.edit { it[KEY_MOBILE] = enabled }
    }

    /**
     * Nach wie vielen Tagen der Download einer zu Ende gehörten Folge gelöscht wird: [DELETE_HEARD_IMMEDIATELY] (0),
     * eine Anzahl Tage oder [DELETE_HEARD_NEVER] (-1).
     */
    val deleteHeardAfterDays: Flow<Int> =
        context.podcastSettingsDataStore.data.map { it[KEY_DELETE_HEARD_DAYS] ?: DEFAULT_DELETE_HEARD_DAYS }

    suspend fun setDeleteHeardAfterDays(days: Int) {
        context.podcastSettingsDataStore.edit { it[KEY_DELETE_HEARD_DAYS] = days.coerceAtLeast(DELETE_HEARD_NEVER) }
    }

    /** Wie viele der neuesten ungehörten Folgen die Download-Automatik je Podcast bereithält. */
    val autoDownloadCount: Flow<Int> = context.podcastSettingsDataStore.data.map { it[KEY_AUTO_COUNT] ?: DEFAULT_AUTO_DOWNLOAD_COUNT }

    suspend fun setAutoDownloadCount(count: Int) {
        context.podcastSettingsDataStore.edit { it[KEY_AUTO_COUNT] = count.coerceAtLeast(1) }
    }

    /** Ist eine Folge zu Ende, startet die nächste der Zeile „Weiterhören“ (Standard: an). */
    val autoPlayNext: Flow<Boolean> = context.podcastSettingsDataStore.data.map { it[KEY_AUTO_PLAY_NEXT] ?: true }

    suspend fun setAutoPlayNext(enabled: Boolean) {
        context.podcastSettingsDataStore.edit { it[KEY_AUTO_PLAY_NEXT] = enabled }
    }

    companion object {
        val AUTO_DOWNLOAD_COUNT_CHOICES = listOf(1, 2, 3, 5, 10)
        const val DEFAULT_AUTO_DOWNLOAD_COUNT = 3

        private val KEY_AUTO_COUNT = intPreferencesKey("auto_download_count")

        /** Auswahl in den Einstellungen. */
        val REFRESH_HOUR_CHOICES = listOf(0, 1, 3, 6, 12, 24)
        const val DEFAULT_REFRESH_HOURS = 6

        const val DELETE_HEARD_NEVER = -1
        const val DELETE_HEARD_IMMEDIATELY = 0
        val DELETE_HEARD_CHOICES = listOf(DELETE_HEARD_IMMEDIATELY, 1, 3, 7, 14, 30, DELETE_HEARD_NEVER)
        const val DEFAULT_DELETE_HEARD_DAYS = 3

        private val KEY_DELETE_HEARD_DAYS = intPreferencesKey("delete_heard_days")
        private val KEY_REFRESH_HOURS = intPreferencesKey("refresh_hours")
        private val KEY_MOBILE = booleanPreferencesKey("mobile_data")
        private val KEY_AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
    }
}
