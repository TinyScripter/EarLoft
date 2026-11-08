package de.kleinabersmart.tinyamp.ui.spoken

import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.core.data.di.ApplicationScope
import de.kleinabersmart.tinyamp.core.data.podcast.DatabaseExportResult
import de.kleinabersmart.tinyamp.core.data.podcast.DatabaseExporter
import de.kleinabersmart.tinyamp.core.data.podcast.PodcastSourceSwitch
import de.kleinabersmart.tinyamp.core.data.podcast.SourceCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Ablauf von „Quelle wechseln“ (Konzept 12.10). */
sealed interface SourceSwitchState {
    data object Idle : SourceSwitchState
    data object Checking : SourceSwitchState
    data class Failed(@param:StringRes val message: Int, val detail: String? = null) : SourceSwitchState

    /** Geprüft; wartet auf die Bestätigung. */
    data class Ready(val episodes: Int, val matched: Int, val keptUnmatched: Int) : SourceSwitchState
    data object Switching : SourceSwitchState
    data object Done : SourceSwitchState
}

/** Ergebnis des Datenbank-Exports im Dialog; `null` = fehlgeschlagen. */
data class SwitchExport(val result: DatabaseExportResult?)

@HiltViewModel(assistedFactory = SourceSwitchViewModel.Factory::class)
class SourceSwitchViewModel @AssistedInject constructor(
    @Assisted private val feedId: String,
    @param:ApplicationContext private val context: Context,
    private val sourceSwitch: PodcastSourceSwitch,
    private val databaseExporter: DatabaseExporter,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow<SourceSwitchState>(SourceSwitchState.Idle)
    val state: StateFlow<SourceSwitchState> = _state

    private val _export = MutableStateFlow<SwitchExport?>(null)
    val export: StateFlow<SwitchExport?> = _export

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting

    private var checked: SourceCheck.Ready? = null

    /** Lädt die neue Quelle und ordnet die Folgen zu; geändert wird noch nichts. */
    fun check(input: String) {
        _state.value = SourceSwitchState.Checking
        viewModelScope.launch {
            _state.value = when (val result = sourceSwitch.check(feedId, input.trim())) {
                is SourceCheck.Ready -> {
                    checked = result
                    SourceSwitchState.Ready(result.episodes, result.matched, result.keptUnmatched)
                }
                SourceCheck.SameSource -> SourceSwitchState.Failed(R.string.podcast_source_switch_same)
                is SourceCheck.Taken -> SourceSwitchState.Failed(R.string.podcast_source_switch_taken, result.title)
                is SourceCheck.Failed -> SourceSwitchState.Failed(result.result.message)
            }
        }
    }

    /**
     * Beim Schließen des Dialogs: Das ViewModel gehört zur Seite und überlebt ihn; ohne Zurücksetzen schlösse sich der
     * nächste Dialog nach einem Wechsel sofort wieder („Done“), und alte Fehler und Export-Ergebnisse stünden darin.
     */
    fun reset() {
        checked = null
        _state.value = SourceSwitchState.Idle
        _export.value = null
    }

    /** Zurück zur Eingabe. */
    fun back() {
        checked = null
        _state.value = SourceSwitchState.Idle
    }

    /** Führt den geprüften Wechsel aus; im App-Scope, damit er nicht halb stehen bleibt, wenn die Seite geht. */
    fun switch() {
        val ready = checked ?: return
        _state.value = SourceSwitchState.Switching
        viewModelScope.launch {
            val switched = applicationScope.async { sourceSwitch.switch(ready) }.await()
            checked = null
            _state.value = if (switched) SourceSwitchState.Done else SourceSwitchState.Failed(R.string.podcast_source_switch_changed)
        }
    }

    /** Sicherung vor dem Wechsel: dieselbe Datei wie Einstellungen → Podcasts → „Datenbank exportieren“. */
    fun exportDatabase(uri: Uri) {
        _exporting.value = true
        viewModelScope.launch {
            val file = File(context.cacheDir, "export.db")
            val result = try {
                databaseExporter.export(file).also {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                            ?: throw IOException("Datei nicht beschreibbar")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Datenbank-Export fehlgeschlagen", e)
                null
            } catch (e: SQLiteException) {
                Log.w(TAG, "Datenbank-Export fehlgeschlagen", e)
                null
            } finally {
                withContext(Dispatchers.IO) { file.delete() }
            }
            _export.value = SwitchExport(result)
            _exporting.value = false
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(feedId: String): SourceSwitchViewModel
    }

    private companion object {
        const val TAG = "SourceSwitch"
    }
}
