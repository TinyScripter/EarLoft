package de.kleinabersmart.earloft.ui.theme

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
    /** Standard: wie das System. */
    val mode: Flow<ThemeMode> = context.themeSettingsDataStore.data.map { prefs ->
        prefs[KEY_MODE]?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } } ?: ThemeMode.SYSTEM
    }

    suspend fun setMode(mode: ThemeMode) {
        context.themeSettingsDataStore.edit { it[KEY_MODE] = mode.name }
    }

    /** Farben aus dem System (Material You, ab Android 12) statt aus dem Logo. Standard: nein. */
    val systemColors: Flow<Boolean> = context.themeSettingsDataStore.data.map { it[KEY_SYSTEM_COLORS] ?: false }

    suspend fun setSystemColors(enabled: Boolean) {
        context.themeSettingsDataStore.edit { it[KEY_SYSTEM_COLORS] = enabled }
    }

    /** Mini-Player und Player in den Farben des Covers statt in den App-Farben. Standard: ja. */
    val playerCoverColors: Flow<Boolean> = context.themeSettingsDataStore.data.map { it[KEY_PLAYER_COVER_COLORS] ?: true }

    suspend fun setPlayerCoverColors(enabled: Boolean) {
        context.themeSettingsDataStore.edit { it[KEY_PLAYER_COVER_COLORS] = enabled }
    }

    /** Das Widget in den Farben des Covers statt in den App-Farben. Standard: ja. */
    val widgetCoverColors: Flow<Boolean> = context.themeSettingsDataStore.data.map { it[KEY_WIDGET_COVER_COLORS] ?: true }

    suspend fun setWidgetCoverColors(enabled: Boolean) {
        context.themeSettingsDataStore.edit { it[KEY_WIDGET_COVER_COLORS] = enabled }
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
        val KEY_PLAYER_COVER_COLORS = booleanPreferencesKey("player_cover_colors")
        val KEY_WIDGET_COVER_COLORS = booleanPreferencesKey("widget_cover_colors")
    }
}
