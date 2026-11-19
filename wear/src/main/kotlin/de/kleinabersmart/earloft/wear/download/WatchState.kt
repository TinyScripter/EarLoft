package de.kleinabersmart.earloft.wear.download

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearSettingsDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import javax.inject.Inject
import javax.inject.Singleton

/** Running download of file [trackIndex] of episode or book [bookId]; [total] is `null` while the size is unknown. */
data class SpokenFileProgress(val bookId: Long, val trackIndex: Int, val done: Long, val total: Long?)

/**
 * What the watch remembers without it belonging in the library: the settings from the phone
 * (`/earloft/settings`, Wear concept 4) and the messages for the status report.
 */
@Singleton
class WatchState @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("watch_state", Context.MODE_PRIVATE)

    /** Failed attempts at loading from the server per `sourceUid`; after [SERVER_ATTEMPTS] it goes via the phone (Wear concept 8.3). */
    private val serverFailures = context.getSharedPreferences("watch_server_failures", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<WearSettingsDto> = _settings.asStateFlow()

    private val _missingForStorage = MutableStateFlow(prefs.getInt(KEY_MISSING, 0))

    /** Tracks not queued because of the storage limit (Wear concept 8.1). */
    val missingForStorage: StateFlow<Int> = _missingForStorage.asStateFlow()

    private val _lastError = MutableStateFlow(prefs.getString(KEY_LAST_ERROR, null))
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _waitingForWifi = MutableStateFlow(prefs.getBoolean(KEY_WAITING_WIFI, false))

    /** The last run got no Wi-Fi (off or out of range); the downloads wait for the next attempt. */
    val waitingForWifi: StateFlow<Boolean> = _waitingForWifi.asStateFlow()

    private val _spokenFileProgress = MutableStateFlow<SpokenFileProgress?>(null)

    /**
     * The file of an episode or book currently downloading (in memory only: unlike for songs, the progress is not stored in
     * the database); `null` if none is downloading.
     */
    val spokenFileProgress: StateFlow<SpokenFileProgress?> = _spokenFileProgress.asStateFlow()

    fun setSpokenFileProgress(progress: SpokenFileProgress?) {
        _spokenFileProgress.value = progress
    }

    /** Server and manifest from the phone that were last fully applied (see `PhoneLibrarySync`). */
    var appliedPhoneStamp: String?
        get() = prefs.getString(KEY_APPLIED_STAMP, null)
        set(value) = prefs.edit().putString(KEY_APPLIED_STAMP, value).apply()

    /** @return `true` if something changed. */
    fun updateSettings(settings: WearSettingsDto): Boolean {
        if (settings == _settings.value) return false
        prefs.edit().putString(KEY_SETTINGS, WearCodec.encode(settings).decodeToString()).apply()
        _settings.value = settings
        return true
    }

    /** Counts a failed attempt at loading from the server. @return the count so far. */
    fun recordServerFailure(sourceUid: String): Int {
        val count = serverFailures.getInt(sourceUid, 0) + 1
        serverFailures.edit().putInt(sourceUid, count).apply()
        return count
    }

    /** Counts a failed attempt for several songs at once (the server was unreachable, not just one song). */
    fun recordServerFailures(sourceUids: Collection<String>) {
        if (sourceUids.isEmpty()) return
        serverFailures.edit().apply { sourceUids.forEach { putInt(it, serverFailures.getInt(it, 0) + 1) } }.apply()
    }

    fun hasServerFailedTooOften(sourceUid: String): Boolean = serverFailures.getInt(sourceUid, 0) >= SERVER_ATTEMPTS

    /**
     * Notes that these songs now come via the phone although the server does not count any failed attempts (yet), e.g. because
     * the watch does not know the source. Only for displaying the remaining time; the server is still asked first.
     */
    fun markViaPhone(sourceUids: Collection<String>) {
        if (sourceUids.isEmpty()) return
        serverFailures.edit().apply { sourceUids.forEach { putBoolean(VIA_PHONE_PREFIX + it, true) } }.apply()
    }

    fun isMarkedViaPhone(sourceUid: String): Boolean = serverFailures.getBoolean(VIA_PHONE_PREFIX + sourceUid, false)

    fun clearServerFailures(sourceUid: String) {
        val viaPhoneKey = VIA_PHONE_PREFIX + sourceUid
        if (serverFailures.contains(sourceUid) || serverFailures.contains(viaPhoneKey)) {
            serverFailures.edit().remove(sourceUid).remove(viaPhoneKey).apply()
        }
    }

    /** For "Retry": otherwise every song would go straight to the phone without asking the server again. */
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

    /**
     * Counts a run in which the phone was unreachable for a download. @return the count so far; after
     * [PHONE_ATTEMPTS] the job waits for the charger or for the phone to check in (Review V1).
     */
    fun recordPhoneUnreachable(): Int {
        val count = prefs.getInt(KEY_PHONE_FAILURES, 0) + 1
        prefs.edit().putInt(KEY_PHONE_FAILURES, count).apply()
        return count
    }

    /** The phone delivered or checked in: full attempts again. */
    fun clearPhoneUnreachable() {
        if (prefs.contains(KEY_PHONE_FAILURES) || prefs.contains(KEY_WAITING_PHONE)) {
            prefs.edit().remove(KEY_PHONE_FAILURES).remove(KEY_WAITING_PHONE).apply()
        }
    }

    /** After [PHONE_ATTEMPTS] runs without the phone the job waits for the charger; if the phone checks in, it continues right away. */
    var waitingForPhone: Boolean
        get() = prefs.getBoolean(KEY_WAITING_PHONE, false)
        set(value) = prefs.edit().putBoolean(KEY_WAITING_PHONE, value).apply()

    /**
     * Consecutive runs of the download job that ended with a retry (no Wi-Fi, server or phone unreachable).
     * Replaces WorkManager's `runAttemptCount`: it also counts stops by the system (such as the brief disconnect during
     * adaptive charging) and so lengthened the interval every time (Review W1).
     */
    val retryRuns: Int
        get() = prefs.getInt(KEY_RETRY_RUNS, 0)

    /** @return the count including this run. */
    fun recordRetry(): Int {
        val count = retryRuns + 1
        prefs.edit().putInt(KEY_RETRY_RUNS, count).apply()
        return count
    }

    /** A run ended without a retry, or a new job starts from scratch (new selection, charger). */
    fun clearRetries() {
        if (prefs.contains(KEY_RETRY_RUNS)) prefs.edit().remove(KEY_RETRY_RUNS).apply()
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
        /** Failed attempts per song before the watch fetches the file from the phone. */
        const val SERVER_ATTEMPTS = 3

        /** Runs without a reachable phone before the job waits for the charger (Review V1, as with Wi-Fi). */
        const val PHONE_ATTEMPTS = 5

        private const val VIA_PHONE_PREFIX = "via_phone:"

        private const val TAG = "WatchState"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_MISSING = "missing_for_storage"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_WAITING_WIFI = "waiting_wifi"
        private const val KEY_APPLIED_STAMP = "applied_phone_stamp"
        private const val KEY_PHONE_FAILURES = "phone_unreachable_runs"
        private const val KEY_WAITING_PHONE = "waiting_phone"
        private const val KEY_RETRY_RUNS = "retry_runs"
    }
}
