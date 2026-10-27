package keinabersmart.kleinaberlaut.core.download

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.downloadSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "download_settings")

@Singleton
class DownloadSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Standard: nur über ungetaktete Netze (WLAN) laden. */
    val wifiOnly: Flow<Boolean> = context.downloadSettingsDataStore.data.map { it[KEY_WIFI_ONLY] ?: true }

    suspend fun setWifiOnly(wifiOnly: Boolean) {
        context.downloadSettingsDataStore.edit { it[KEY_WIFI_ONLY] = wifiOnly }
    }

    private companion object {
        val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
    }
}
