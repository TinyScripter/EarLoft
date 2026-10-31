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

    companion object {
        /** Auswahl in den Einstellungen. */
        val REFRESH_HOUR_CHOICES = listOf(0, 1, 3, 6, 12, 24)
        const val DEFAULT_REFRESH_HOURS = 6

        private val KEY_REFRESH_HOURS = intPreferencesKey("refresh_hours")
        private val KEY_MOBILE = booleanPreferencesKey("mobile_data")
    }
}
