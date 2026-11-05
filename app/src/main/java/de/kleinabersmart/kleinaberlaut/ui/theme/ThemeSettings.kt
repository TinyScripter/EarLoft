package de.kleinabersmart.kleinaberlaut.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** App-Darstellung: hell, dunkel oder wie das System. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.themeSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_settings")

@Singleton
class ThemeSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Standard: dunkel. */
    val mode: Flow<ThemeMode> = context.themeSettingsDataStore.data.map { prefs ->
        prefs[KEY_MODE]?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } } ?: ThemeMode.DARK
    }

    suspend fun setMode(mode: ThemeMode) {
        context.themeSettingsDataStore.edit { it[KEY_MODE] = mode.name }
    }

    /** Farben aus dem System (Material You, ab Android 12) statt aus dem Logo. Standard: nein. */
    val systemColors: Flow<Boolean> = context.themeSettingsDataStore.data.map { it[KEY_SYSTEM_COLORS] ?: false }

    suspend fun setSystemColors(enabled: Boolean) {
        context.themeSettingsDataStore.edit { it[KEY_SYSTEM_COLORS] = enabled }
    }

    /** Navigationsleiste auch in den Wiedergabefenstern zeigen. Standard: ja. */
    val alwaysShowNavigationBar: Flow<Boolean> = context.themeSettingsDataStore.data.map { it[KEY_ALWAYS_NAV] ?: true }

    suspend fun setAlwaysShowNavigationBar(enabled: Boolean) {
        context.themeSettingsDataStore.edit { it[KEY_ALWAYS_NAV] = enabled }
    }

    private companion object {
        val KEY_MODE = stringPreferencesKey("mode")
        val KEY_ALWAYS_NAV = booleanPreferencesKey("always_show_navigation_bar")
        val KEY_SYSTEM_COLORS = booleanPreferencesKey("system_colors")
    }
}
