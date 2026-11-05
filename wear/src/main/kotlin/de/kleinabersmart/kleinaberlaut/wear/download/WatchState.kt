package de.kleinabersmart.kleinaberlaut.wear.download

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.kleinaberlaut.core.wearsync.WearCodec
import de.kleinabersmart.kleinaberlaut.core.wearsync.WearSettingsDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import javax.inject.Inject
import javax.inject.Singleton

/** Laufender Download der Datei [trackIndex] von Folge bzw. Buch [bookId]; [total] ist `null`, solange die Größe unbekannt ist. */
data class SpokenFileProgress(val bookId: Long, val trackIndex: Int, val done: Long, val total: Long?)

/**
 * Was die Uhr sich merkt, ohne dass es in die Bibliothek gehört: die Einstellungen vom Handy
 * (`/kal/settings`, Wear-Konzept 4) und die Meldungen für den Statusbericht.
 */
@Singleton
class WatchState @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("watch_state", Context.MODE_PRIVATE)

    /** Fehlversuche beim Laden vom Server je `sourceUid`; nach [SERVER_ATTEMPTS] geht es über das Handy (Wear-Konzept 8.3). */
    private val serverFailures = context.getSharedPreferences("watch_server_failures", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<WearSettingsDto> = _settings.asStateFlow()

    private val _missingForStorage = MutableStateFlow(prefs.getInt(KEY_MISSING, 0))

    /** Titel, die wegen der Speichergrenze nicht eingereiht sind (Wear-Konzept 8.1). */
    val missingForStorage: StateFlow<Int> = _missingForStorage.asStateFlow()

    private val _lastError = MutableStateFlow(prefs.getString(KEY_LAST_ERROR, null))
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _waitingForWifi = MutableStateFlow(prefs.getBoolean(KEY_WAITING_WIFI, false))

    /** Der letzte Lauf bekam kein WLAN (aus oder außer Reichweite); die Downloads warten auf den nächsten Versuch. */
    val waitingForWifi: StateFlow<Boolean> = _waitingForWifi.asStateFlow()

    private val _spokenFileProgress = MutableStateFlow<SpokenFileProgress?>(null)

    /**
     * Die Datei einer Folge bzw. eines Buchs, die gerade lädt (nur im Speicher: Anders als bei Songs steht der Stand nicht in
     * der Datenbank); `null`, wenn keine lädt.
     */
    val spokenFileProgress: StateFlow<SpokenFileProgress?> = _spokenFileProgress.asStateFlow()

    fun setSpokenFileProgress(progress: SpokenFileProgress?) {
        _spokenFileProgress.value = progress
    }

    /** Server und Manifest vom Handy, die zuletzt vollständig übernommen wurden (siehe `PhoneLibrarySync`). */
    var appliedPhoneStamp: String?
        get() = prefs.getString(KEY_APPLIED_STAMP, null)
        set(value) = prefs.edit().putString(KEY_APPLIED_STAMP, value).apply()

    /** @return `true`, wenn sich etwas geändert hat. */
    fun updateSettings(settings: WearSettingsDto): Boolean {
        if (settings == _settings.value) return false
        prefs.edit().putString(KEY_SETTINGS, WearCodec.encode(settings).decodeToString()).apply()
        _settings.value = settings
        return true
    }

    /** Zählt einen Fehlversuch beim Laden vom Server. @return die Anzahl bisher. */
    fun recordServerFailure(sourceUid: String): Int {
        val count = serverFailures.getInt(sourceUid, 0) + 1
        serverFailures.edit().putInt(sourceUid, count).apply()
        return count
    }

    /** Zählt einen Fehlversuch für mehrere Songs auf einmal (der Server war nicht erreichbar, nicht nur ein Song). */
    fun recordServerFailures(sourceUids: Collection<String>) {
        if (sourceUids.isEmpty()) return
        serverFailures.edit().apply { sourceUids.forEach { putInt(it, serverFailures.getInt(it, 0) + 1) } }.apply()
    }

    fun hasServerFailedTooOften(sourceUid: String): Boolean = serverFailures.getInt(sourceUid, 0) >= SERVER_ATTEMPTS

    fun clearServerFailures(sourceUid: String) {
        if (serverFailures.contains(sourceUid)) serverFailures.edit().remove(sourceUid).apply()
    }

    /** Für „Erneut versuchen“: Sonst ginge jeder Song sofort ans Handy, ohne dass der Server noch einmal gefragt wird. */
    fun clearAllServerFailures() {
        serverFailures.edit().clear().apply()
    }

    fun setMissingForStorage(count: Int) {
        prefs.edit().putInt(KEY_MISSING, count).apply()
        _missingForStorage.value = count
    }

    fun setLastError(message: String?) {
        prefs.edit().putString(KEY_LAST_ERROR, message).apply()
        _lastError.value = message
    }

    fun setWaitingForWifi(waiting: Boolean) {
        if (waiting == _waitingForWifi.value) return
        prefs.edit().putBoolean(KEY_WAITING_WIFI, waiting).apply()
        _waitingForWifi.value = waiting
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

    companion object {
        /** Fehlversuche je Song, bevor die Uhr die Datei beim Handy holt. */
        const val SERVER_ATTEMPTS = 3

        private const val TAG = "WatchState"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_MISSING = "missing_for_storage"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_WAITING_WIFI = "waiting_wifi"
        private const val KEY_APPLIED_STAMP = "applied_phone_stamp"
    }
}
