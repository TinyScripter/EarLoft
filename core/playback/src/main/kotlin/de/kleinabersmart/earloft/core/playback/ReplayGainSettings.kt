package de.kleinabersmart.earloft.core.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.model.ReplayGainMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_settings")

@Singleton
class ReplayGainSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val mode: Flow<ReplayGainMode> = context.playbackSettingsDataStore.data.map { prefs ->
        ReplayGainMode.entries.firstOrNull { it.name == prefs[KEY_MODE] } ?: ReplayGainMode.AUTO
    }

    /** Zusätzliche Verstärkung in dB für Titel mit ReplayGain-Werten. */
    val preAmpDb: Flow<Float> = context.playbackSettingsDataStore.data.map { it[KEY_PRE_AMP] ?: 0f }

    suspend fun setMode(mode: ReplayGainMode) {
        context.playbackSettingsDataStore.edit { it[KEY_MODE] = mode.name }
    }

    suspend fun setPreAmpDb(preAmpDb: Float) {
        context.playbackSettingsDataStore.edit { it[KEY_PRE_AMP] = preAmpDb.coerceIn(MIN_PRE_AMP_DB, MAX_PRE_AMP_DB) }
    }

    companion object {
        const val MIN_PRE_AMP_DB = -12f
        const val MAX_PRE_AMP_DB = 12f

        private val KEY_MODE = stringPreferencesKey("replay_gain_mode")
        private val KEY_PRE_AMP = floatPreferencesKey("replay_gain_pre_amp_db")
    }
}
