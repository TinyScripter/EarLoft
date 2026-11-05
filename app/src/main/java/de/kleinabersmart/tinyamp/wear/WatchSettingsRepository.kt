package de.kleinabersmart.tinyamp.wear

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.tinyamp.core.wearsync.WearQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Einstellungen der Uhr; sie werden am Handy gesetzt (Wear-Konzept 1, „Einstellungen → Uhr“). */
data class WatchSettings(
    val quality: WearQuality = WearQuality.OPUS_128,
    /** Feste Obergrenze in GB; `null` = bis auf 1 GB freien Speicher (W1). */
    val storageLimitGb: Int? = null,
    val requireCharging: Boolean = true,
)

private val Context.watchSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "watch_settings")

@Singleton
class WatchSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val settings: Flow<WatchSettings> = context.watchSettingsDataStore.data.map { prefs ->
        WatchSettings(
            quality = prefs[KEY_QUALITY]?.let { name -> WearQuality.entries.firstOrNull { it.name == name } } ?: WearQuality.OPUS_128,
            storageLimitGb = prefs[KEY_STORAGE_LIMIT_GB],
            requireCharging = prefs[KEY_REQUIRE_CHARGING] ?: true,
        )
    }

    suspend fun setQuality(quality: WearQuality) {
        context.watchSettingsDataStore.edit { it[KEY_QUALITY] = quality.name }
    }

    suspend fun setStorageLimitGb(gigabytes: Int?) {
        context.watchSettingsDataStore.edit { if (gigabytes == null) it.remove(KEY_STORAGE_LIMIT_GB) else it[KEY_STORAGE_LIMIT_GB] = gigabytes }
    }

    suspend fun setRequireCharging(requireCharging: Boolean) {
        context.watchSettingsDataStore.edit { it[KEY_REQUIRE_CHARGING] = requireCharging }
    }

    private companion object {
        val KEY_QUALITY = stringPreferencesKey("quality")
        val KEY_STORAGE_LIMIT_GB = intPreferencesKey("storage_limit_gb")
        val KEY_REQUIRE_CHARGING = booleanPreferencesKey("require_charging")
    }
}
