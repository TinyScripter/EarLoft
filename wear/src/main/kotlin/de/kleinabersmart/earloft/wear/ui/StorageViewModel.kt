package de.kleinabersmart.earloft.wear.ui

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
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.download.DownloadRepository
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.DownloadOverview
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.core.wearsync.WearClearScope
import de.kleinabersmart.earloft.core.wearsync.WearDownloadError
import de.kleinabersmart.earloft.core.wearsync.WearSettingsDto
import de.kleinabersmart.earloft.core.wearsync.WearThumbnailFile
import de.kleinabersmart.earloft.wear.download.SelectionItem
import de.kleinabersmart.earloft.wear.download.StoredSpoken
import de.kleinabersmart.earloft.wear.download.WatchState
import de.kleinabersmart.earloft.wear.download.WatchStorageRepository
import de.kleinabersmart.earloft.wear.download.WearDownloadPlanner
import de.kleinabersmart.earloft.wear.sync.PhoneSelections
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StorageUiState(
    val overview: DownloadOverview? = null,
    /** Fixed upper limit for downloads (setting on the phone), otherwise `null`. */
    val limitBytes: Long? = null,
    val missingForStorage: Int = 0,
    val lastError: WearDownloadError? = null,
    /** Bytes still to be transferred via the phone (original); 0 if nothing is waiting. */
    val phoneBacklogBytes: Long = 0,
    /** Setting from the phone: downloads only on the charger. */
    val requireCharging: Boolean = false,
    val charging: Boolean = true,
    /** The last run got no Wi-Fi. */
    val noWifi: Boolean = false,
    /** The waiting job only starts on the charger after repeated attempts without Wi-Fi or phone (review4 T58). */
    val jobOnCharger: Boolean = false,
) {
    /** Something is waiting, but the watch gets no Wi-Fi (the charger takes precedence, as it does not even start the run). */
    val waitingForWifi: Boolean get() = noWifi && !waitingForCharger && (overview?.pendingSongs ?: 0) > 0

    /** Something is waiting, but the job only starts on the charger (by the setting of the phone or after repeated attempts). */
    val waitingForCharger: Boolean get() = (requireCharging || jobOnCharger) && !charging && (overview?.pendingSongs ?: 0) > 0
}

enum class StoredSection { MUSIC, PODCASTS, BOOKS }

/** What the phone needs for deselecting: kind and ID on the phone, or server/subscription and identifier. */
sealed interface StoredRef {
    data class Music(val kind: WearSelectionKind, val phoneRefId: Long?) : StoredRef

    data class Spoken(val serverId: String, val remoteId: String) : StoredRef
}

/**
 * An album (or playlist, favorites), an episode or a book on the watch, removable individually. [key] is unique across
 * all sections. [removing]: deselected, the phone has not sent the new manifest yet.
 */
data class StoredEntry(
    val key: String,
    val section: StoredSection,
    val title: String,
    val bytes: Long,
    val loaded: Boolean,
    val ref: StoredRef,
    val cover: CoverRef? = null,
    val removing: Boolean = false,
)

@HiltViewModel
class StorageViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val downloads: DownloadRepository,
    private val phoneSelections: PhoneSelections,
    private val storage: WatchStorageRepository,
    private val watchState: WatchState,
    private val bookDao: BookDao,
    private val planner: WearDownloadPlanner,
) : ViewModel() {

    private val _removeFailed = MutableStateFlow(false)

    /** `true` if the phone was unreachable for "Remove all" or for removing an entry. */
    val removeFailed: StateFlow<Boolean> = _removeFailed

    /** Deselected entries ([StoredEntry.key]) until the manifest removes them or [REMOVING_TIMEOUT_MS] passes. */
    private val removing = MutableStateFlow<Set<String>>(emptySet())

    /** Music in the phone's order, then episodes and books by title. */
    val entries: StateFlow<List<StoredEntry>> = combine(storage.selections(), storage.storedSpoken(), removing) { selections, spoken, removingNow ->
        val music = selections.map { it.toEntry() }
        val (episodes, books) = spoken.partition { it.kind == BookKind.EPISODE }
        val spokenEntries = episodes.map { it.toEntry(StoredSection.PODCASTS) } + books.map { it.toEntry(StoredSection.BOOKS) }
        (music + spokenEntries).map { it.copy(removing = it.key in removingNow) }
    }.orOnError(emptyList()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<StorageUiState> = combine(
        storage.overview(),
        watchState.settings,
        watchState.missingForStorage,
        watchState.lastError,
        combine(context.chargingFlow(), watchState.waitingForWifi, watchState.jobOnCharger, ::Triple),
    ) { overview, settings: WearSettingsDto, missing, error, (charging, noWifi, jobOnCharger) ->
        val backlog = if (overview.pendingSongs > 0) storage.phoneBacklogBytes(settings.quality) else 0
        StorageUiState(overview, settings.storageLimitBytes, missing, error, backlog, settings.requireCharging, charging, noWifi, jobOnCharger)
    }.orOnError(StorageUiState()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageUiState())

    /**
     * Clears the selection on the phone: everything or just one section ([section]). The phone determines the selection; deleting locally
     * would be downloaded again on the next sync.
     */
    fun clear(section: StoredSection?) {
        viewModelScope.launch {
            _removeFailed.value = false
            val scope = when (section) {
                null -> WearClearScope.ALL
                StoredSection.MUSIC -> WearClearScope.MUSIC
                StoredSection.PODCASTS -> WearClearScope.PODCASTS
                StoredSection.BOOKS -> WearClearScope.BOOKS
            }
            if (!phoneSelections.clearSelections(scope)) {
                _removeFailed.value = true
                return@launch
            }
            markRemoving(entries.value.filter { section == null || it.section == section }.map { it.key })
        }
    }

    /** Deselects the entry on the phone (like the "On the watch" switch); the watch deletes it with the next manifest. */
    fun remove(entry: StoredEntry) {
        viewModelScope.launch {
            _removeFailed.value = false
            val sent = when (val ref = entry.ref) {
                is StoredRef.Music -> phoneSelections.setSelected(ref.kind, ref.phoneRefId, selected = false)
                is StoredRef.Spoken -> phoneSelections.deselectSpoken(ref.serverId, ref.remoteId)
            }
            if (!sent) {
                _removeFailed.value = true
                return@launch
            }
            markRemoving(listOf(entry.key))
        }
    }

    /** If an entry remains although the phone got the message (e.g. episode already gone there), it becomes selectable again. */
    private fun markRemoving(keys: Collection<String>) {
        removing.update { it + keys }
        viewModelScope.launch {
            delay(REMOVING_TIMEOUT_MS)
            removing.update { it - keys.toSet() }
        }
    }

    fun dismissRemoveFailed() {
        _removeFailed.value = false
    }

    /** Songs as well as episodes and books; the latter would otherwise wait until the phone adds them to the manifest again. */
    fun retryFailed() {
        watchState.clearAllServerFailures()
        watchState.clearPhoneUnreachable()
        viewModelScope.launch {
            downloads.retryFailed()
            if (bookDao.resetFailedDownloads() > 0) planner.plan()
        }
    }
}

/** The phone sends the manifest after 2 s of quiet; as with the "On the watch" switch, the desired state applies at most that long. */
private const val REMOVING_TIMEOUT_MS = 20_000L

private fun SelectionItem.toEntry(): StoredEntry {
    // Album covers from the phone (as in the "Phone" section); playlists and favorites have none.
    val cover = phoneRefId?.takeIf { kind == WearSelectionKind.ALBUM }?.let { CoverRef.Phone(WearThumbnailFile.Ref.Album(it)) }
    // Distinct songs: a playlist with a song twice would otherwise never count as loaded (review G49).
    return StoredEntry(key, StoredSection.MUSIC, title, bytes, downloadedCount >= distinctSongs, StoredRef.Music(kind, phoneRefId), cover)
}

private fun StoredSpoken.toEntry(section: StoredSection) =
    StoredEntry("book:$bookId", section, title, bytes, loaded, StoredRef.Spoken(serverId, remoteId), CoverRef.Book(bookId))

/** Whether the watch is on the charger (like the job constraint "charging": cable or dock). The sticky broadcast delivers the state immediately. */
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
