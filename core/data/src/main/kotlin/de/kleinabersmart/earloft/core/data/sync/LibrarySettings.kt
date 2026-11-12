package de.kleinabersmart.earloft.core.data.sync

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

private val KEY_LOCAL_MEDIA = booleanPreferencesKey("use_local_media")

private val Context.librarySettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "library_settings",
    produceMigrations = { context -> listOf(LocalSourcesDefault(context, listOf(KEY_LOCAL_MEDIA))) },
)

@Singleton
class LibrarySettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * Musik auf dem Gerät (MediaStore) in die Bibliothek aufnehmen (Standard: aus, siehe [LocalSourcesDefault]). Aus = nur
     * Server; die lokalen Songs verschwinden dann aus der Bibliothek, siehe [LibraryRefresher].
     */
    val useLocalMedia: Flow<Boolean> = context.librarySettingsDataStore.data.map { it[KEY_LOCAL_MEDIA] ?: false }

    suspend fun setUseLocalMedia(enabled: Boolean) {
        context.librarySettingsDataStore.edit { it[KEY_LOCAL_MEDIA] = enabled }
    }

    /** Abstand des automatischen Vollabgleichs der Server in Tagen: [SYNC_NEVER], 1, 7 oder 30. */
    val syncIntervalDays: Flow<Int> = context.librarySettingsDataStore.data.map { it[KEY_SYNC_DAYS] ?: DEFAULT_SYNC_DAYS }

    suspend fun setSyncIntervalDays(days: Int) {
        context.librarySettingsDataStore.edit { it[KEY_SYNC_DAYS] = days.coerceAtLeast(SYNC_NEVER) }
    }

    companion object {
        /** Kein automatischer Abgleich; nur von Hand (und beim Einrichten oder Einschalten eines Servers). */
        const val SYNC_NEVER = 0
        val SYNC_DAY_CHOICES = listOf(SYNC_NEVER, 1, 7, 30)
        const val DEFAULT_SYNC_DAYS = 1

        private val KEY_SYNC_DAYS = intPreferencesKey("sync_interval_days")
    }
}
