package de.kleinabersmart.earloft.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import de.kleinabersmart.earloft.core.data.backup.BackedUpSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** App appearance: light, dark or follow the system. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.themeSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_settings")

@Singleton
class ThemeSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Default: follow the system. */
    val mode: Flow<ThemeMode> = context.themeSettingsDataStore.data.map { prefs ->
        prefs[KEY_MODE]?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } } ?: ThemeMode.SYSTEM
    }

    suspend fun setMode(mode: ThemeMode) {
        context.themeSettingsDataStore.edit { it[KEY_MODE] = mode.name }
    }

    /** Colors from the system (Material You, from Android 12) instead of from the logo. Default: no. */
    val systemColors: Flow<Boolean> = context.themeSettingsDataStore.data.map { it[KEY_SYSTEM_COLORS] ?: false }

    suspend fun setSystemColors(enabled: Boolean) {
        context.themeSettingsDataStore.edit { it[KEY_SYSTEM_COLORS] = enabled }
    }

    /** Mini player and player in the cover's colors instead of the app colors. Default: yes. */
    val playerCoverColors: Flow<Boolean> = context.themeSettingsDataStore.data.map { it[KEY_PLAYER_COVER_COLORS] ?: true }

    suspend fun setPlayerCoverColors(enabled: Boolean) {
        context.themeSettingsDataStore.edit { it[KEY_PLAYER_COVER_COLORS] = enabled }
    }

    /**
     * Former switch "Cover colors in widget", default: yes. Since every widget has its own colors, only used for widgets
     * created before this version (`NowPlayingWidgetUpdater`).
     */
    val widgetCoverColors: Flow<Boolean> = context.themeSettingsDataStore.data.map { it[KEY_WIDGET_COVER_COLORS] ?: true }

    /** Show the navigation bar in the playback screens too. Default: yes. */
    val alwaysShowNavigationBar: Flow<Boolean> = context.themeSettingsDataStore.data.map { it[KEY_ALWAYS_NAV] ?: true }

    suspend fun setAlwaysShowNavigationBar(enabled: Boolean) {
        context.themeSettingsDataStore.edit { it[KEY_ALWAYS_NAV] = enabled }
    }

    /** Slide the mini player out of the way while scrolling down a list (`MiniPlayerScrollHide`). Default: yes. */
    val hideMiniPlayerOnScroll: Flow<Boolean> = context.themeSettingsDataStore.data.map { it[KEY_HIDE_MINI_PLAYER_ON_SCROLL] ?: true }

    suspend fun setHideMiniPlayerOnScroll(enabled: Boolean) {
        context.themeSettingsDataStore.edit { it[KEY_HIDE_MINI_PLAYER_ON_SCROLL] = enabled }
    }

    private companion object {
        val KEY_MODE = stringPreferencesKey("mode")
        val KEY_ALWAYS_NAV = booleanPreferencesKey("always_show_navigation_bar")
        val KEY_HIDE_MINI_PLAYER_ON_SCROLL = booleanPreferencesKey("hide_mini_player_on_scroll")
        val KEY_SYSTEM_COLORS = booleanPreferencesKey("system_colors")
        val KEY_PLAYER_COVER_COLORS = booleanPreferencesKey("player_cover_colors")
        val KEY_WIDGET_COVER_COLORS = booleanPreferencesKey("widget_cover_colors")
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object ThemeSettingsBackupModule {
    /** Part of the full backup (`FullBackup`). */
    @Provides
    @IntoSet
    fun provideBackedUpSettings(@ApplicationContext context: Context) = BackedUpSettings("theme_settings", context.themeSettingsDataStore)
}
