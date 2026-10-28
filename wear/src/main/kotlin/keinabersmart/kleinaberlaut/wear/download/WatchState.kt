package keinabersmart.kleinaberlaut.wear.download

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearSettingsDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Was die Uhr sich merkt, ohne dass es in die Bibliothek gehört: die Einstellungen vom Handy
 * (`/kal/settings`, Wear-Konzept 4) und die Meldungen für den Statusbericht.
 */
@Singleton
class WatchState @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("watch_state", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<WearSettingsDto> = _settings.asStateFlow()

    private val _missingForStorage = MutableStateFlow(prefs.getInt(KEY_MISSING, 0))

    /** Titel, die wegen der Speichergrenze nicht eingereiht sind (Wear-Konzept 8.1). */
    val missingForStorage: StateFlow<Int> = _missingForStorage.asStateFlow()

    private val _lastError = MutableStateFlow(prefs.getString(KEY_LAST_ERROR, null))
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** @return `true`, wenn sich etwas geändert hat. */
    fun updateSettings(settings: WearSettingsDto): Boolean {
        if (settings == _settings.value) return false
        prefs.edit().putString(KEY_SETTINGS, WearCodec.encode(settings).decodeToString()).apply()
        _settings.value = settings
        return true
    }

    fun setMissingForStorage(count: Int) {
        prefs.edit().putInt(KEY_MISSING, count).apply()
        _missingForStorage.value = count
    }

    fun setLastError(message: String?) {
        prefs.edit().putString(KEY_LAST_ERROR, message).apply()
        _lastError.value = message
    }

    private fun readSettings(): WearSettingsDto {
        val json = prefs.getString(KEY_SETTINGS, null) ?: return WearSettingsDto()
        return try {
            WearCodec.decode<WearSettingsDto>(json.encodeToByteArray())
        } catch (e: SerializationException) {
            Log.w(TAG, "Gespeicherte Einstellungen nicht lesbar, nehme Standardwerte", e)
            WearSettingsDto()
        }
    }

    private companion object {
        const val TAG = "WatchState"
        const val KEY_SETTINGS = "settings"
        const val KEY_MISSING = "missing_for_storage"
        const val KEY_LAST_ERROR = "last_error"
    }
}
