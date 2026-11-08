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
    /** Feste Obergrenze der Downloads (Einstellung am Handy), sonst `null`. */
    val limitBytes: Long? = null,
    val missingForStorage: Int = 0,
    val lastError: String? = null,
    /** Noch über das Handy zu übertragende Bytes (Original); 0, wenn nichts wartet. */
    val phoneBacklogBytes: Long = 0,
    /** Einstellung vom Handy: Downloads nur am Ladegerät. */
    val requireCharging: Boolean = false,
    val charging: Boolean = true,
    /** Der letzte Lauf bekam kein WLAN. */
    val noWifi: Boolean = false,
) {
    /** Es wartet etwas, aber die Uhr bekommt kein WLAN (Vorrang hat das Ladegerät, das den Lauf gar nicht erst startet). */
    val waitingForWifi: Boolean get() = noWifi && !waitingForCharger && (overview?.pendingSongs ?: 0) > 0

    /** Es wartet etwas, aber der Auftrag startet erst am Ladegerät. */
    val waitingForCharger: Boolean get() = requireCharging && !charging && (overview?.pendingSongs ?: 0) > 0
}

enum class StoredSection { MUSIC, PODCASTS, BOOKS }

/** Was das Handy zum Abwählen braucht: Art und ID am Handy bzw. Server/Abo und Kennung. */
sealed interface StoredRef {
    data class Music(val kind: WearSelectionKind, val phoneRefId: Long?) : StoredRef

    data class Spoken(val serverId: String, val remoteId: String) : StoredRef
}

/**
 * Ein Album (bzw. Playlist, Favoriten), eine Folge oder ein Buch auf der Uhr, einzeln zu entfernen. [key] ist eindeutig über
 * alle Abschnitte. [removing]: abgewählt, das Handy hat das neue Manifest noch nicht geschickt.
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

    /** `true`, wenn das Handy für „Alles entfernen“ bzw. das Entfernen eines Eintrags nicht erreichbar war. */
    val removeFailed: StateFlow<Boolean> = _removeFailed

    /** Abgewählte Einträge ([StoredEntry.key]), bis das Manifest sie entfernt oder [REMOVING_TIMEOUT_MS] vergeht. */
    private val removing = MutableStateFlow<Set<String>>(emptySet())

    /** Musik in der Reihenfolge des Handys, dann Folgen und Bücher nach Titel. */
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
        context.chargingFlow().combine(watchState.waitingForWifi, ::Pair),
    ) { overview, settings: WearSettingsDto, missing, error, (charging, noWifi) ->
        val backlog = if (overview.pendingSongs > 0) storage.phoneBacklogBytes(settings.quality) else 0
        StorageUiState(overview, settings.storageLimitBytes, missing, error, backlog, settings.requireCharging, charging, noWifi)
    }.orOnError(StorageUiState()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageUiState())

    /**
     * Leert die Auswahl am Handy: alles bzw. nur einen Abschnitt ([section]). Das Handy bestimmt die Auswahl; lokal löschen
     * würde beim nächsten Abgleich wieder geladen.
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

    /** Wählt den Eintrag am Handy ab (wie der Schalter „Auf der Uhr“); die Uhr löscht ihn mit dem nächsten Manifest. */
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

    /** Bleibt ein Eintrag, obwohl das Handy die Nachricht bekam (z. B. Folge dort schon weg), wird er wieder wählbar. */
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

    /** Songs wie Folgen und Bücher; Letztere warteten sonst, bis das Handy sie neu ins Manifest nimmt. */
    fun retryFailed() {
        watchState.clearAllServerFailures()
        viewModelScope.launch {
            downloads.retryFailed()
            if (bookDao.resetFailedDownloads() > 0) planner.plan()
        }
    }
}

/** Das Handy schickt das Manifest nach 2 s Ruhe; wie beim Schalter „Auf der Uhr“ gilt der gewünschte Stand höchstens so lange. */
private const val REMOVING_TIMEOUT_MS = 20_000L

private fun SelectionItem.toEntry(): StoredEntry {
    // Album-Cover vom Handy (wie im Bereich „Telefon“); Playlists und Favoriten haben keins.
    val cover = phoneRefId?.takeIf { kind == WearSelectionKind.ALBUM }?.let { CoverRef.Phone(WearThumbnailFile.Ref.Album(it)) }
    return StoredEntry(key, StoredSection.MUSIC, title, bytes, downloadedCount >= songCount, StoredRef.Music(kind, phoneRefId), cover)
}

private fun StoredSpoken.toEntry(section: StoredSection) =
    StoredEntry("book:$bookId", section, title, bytes, loaded, StoredRef.Spoken(serverId, remoteId), CoverRef.Book(bookId))

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
