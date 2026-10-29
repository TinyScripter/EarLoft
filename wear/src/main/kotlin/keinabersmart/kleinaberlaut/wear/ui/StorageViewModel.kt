package keinabersmart.kleinaberlaut.wear.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.download.DownloadRepository
import keinabersmart.kleinaberlaut.core.model.DownloadOverview
import keinabersmart.kleinaberlaut.core.wearsync.WearSettingsDto
import keinabersmart.kleinaberlaut.wear.download.WatchState
import keinabersmart.kleinaberlaut.wear.download.estimateBytes
import keinabersmart.kleinaberlaut.core.database.dao.WearDao
import keinabersmart.kleinaberlaut.core.model.SourceType
import keinabersmart.kleinaberlaut.core.wearsync.WearQuality
import keinabersmart.kleinaberlaut.wear.sync.PhoneCatalog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StorageUiState(
    val overview: DownloadOverview? = null,
    /** Feste Obergrenze der Downloads (Einstellung am Handy), sonst `null`. */
    val limitBytes: Long? = null,
    val missingForStorage: Int = 0,
    val lastError: String? = null,
    /** Noch über das Handy zu übertragende Bytes (Original); 0, wenn nichts wartet. */
    val phoneBacklogBytes: Long = 0,
    /** Einstellung vom Handy: Downloads nur am Ladegerät. */
    val requireCharging: Boolean = false,
    val charging: Boolean = true,
) {
    /** Es wartet etwas, aber der Auftrag startet erst am Ladegerät. */
    val waitingForCharger: Boolean get() = requireCharging && !charging && (overview?.pendingSongs ?: 0) > 0
}

@HiltViewModel
class StorageViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val downloads: DownloadRepository,
    private val catalog: PhoneCatalog,
    private val wearDao: WearDao,
    private val watchState: WatchState,
) : ViewModel() {

    private val _clearFailed = MutableStateFlow(false)

    /** `true`, wenn das Handy für „Alles entfernen“ nicht erreichbar war. */
    val clearFailed: StateFlow<Boolean> = _clearFailed

    val state: StateFlow<StorageUiState> = combine(
        downloads.overview(),
        watchState.settings,
        watchState.missingForStorage,
        watchState.lastError,
        context.chargingFlow(),
    ) { overview, settings: WearSettingsDto, missing, error, charging ->
        val backlog = if (overview.pendingSongs > 0) phoneBacklog(settings.quality) else 0
        StorageUiState(overview, settings.storageLimitBytes, missing, error, backlog, settings.requireCharging, charging)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageUiState())

    private suspend fun phoneBacklog(quality: WearQuality): Long =
        wearDao.getPendingCandidates().filter { it.sourceType == SourceType.PHONE }.sumOf { estimateBytes(it, quality) }

    /** Das Handy bestimmt die Auswahl; lokal löschen würde beim nächsten Abgleich wieder geladen. */
    fun clearAll() {
        viewModelScope.launch { _clearFailed.value = !catalog.clearSelections() }
    }

    fun dismissClearFailed() {
        _clearFailed.value = false
    }

    fun retryFailed() {
        watchState.clearAllServerFailures()
        viewModelScope.launch { downloads.retryFailed() }
    }
}

/** Ob die Uhr am Ladegerät hängt (wie die Job-Bedingung „Laden“: Kabel oder Dock). Der Sticky-Broadcast liefert sofort den Stand. */
private fun Context.chargingFlow(): Flow<Boolean> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(intent.isPlugged())
        }
    }
    val current = ContextCompat.registerReceiver(
        this@chargingFlow,
        receiver,
        IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    current?.let { trySend(it.isPlugged()) }
    awaitClose { unregisterReceiver(receiver) }
}.distinctUntilChanged()

private fun Intent.isPlugged(): Boolean = getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
